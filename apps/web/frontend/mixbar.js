/*
 * Barra de progreso con relieve y crossfade (equivalente web de WaveSeekBar de Android).
 *
 * - MixEnv mide una vez el volumen de cada canción (altos y bajos) y dónde empieza y termina el sonido de verdad.
 * - MixBar dibuja ese relieve sobre la barra de progreso mientras el crossfade está activo, marca el tramo de mezcla
 *   (las barras de la canción que sale bajan y las de la siguiente suben) y se acerca a ese tramo mientras suena.
 */
(function () {
  const COLS = 120, FINE = 128, FINE_MS = 125, FINE_SECONDS = 16;
  const memory = new Map(), pending = new Map();
  const failed = new Set();

  function barEnabled() { return localStorage.getItem('musicbox_crossfade_bar') !== '0'; }
  function wanted() { return state.crossfadeSeconds > 0 && barEnabled(); }

  /* ---------- Almacenamiento (memoria + IndexedDB) ---------- */
  let dbPromise = null;
  function db() {
    if (!dbPromise) dbPromise = new Promise((resolve) => {
      try {
        const request = indexedDB.open('musicbox-waveforms-v2', 1);
        request.onupgradeneeded = () => request.result.createObjectStore('env');
        request.onsuccess = () => resolve(request.result);
        request.onerror = () => resolve(null);
      } catch { resolve(null); }
    });
    return dbPromise;
  }
  async function readStored(id) {
    const database = await db();
    if (!database) return null;
    return new Promise((resolve) => {
      try {
        const request = database.transaction('env').objectStore('env').get(id);
        request.onsuccess = () => resolve(request.result || null);
        request.onerror = () => resolve(null);
      } catch { resolve(null); }
    });
  }
  async function writeStored(id, value) {
    const database = await db();
    if (!database) return;
    try { database.transaction('env', 'readwrite').objectStore('env').put(value, id); } catch {}
  }

  /* ---------- Análisis del audio ---------- */
  function normalise(values, peak) {
    return values.map((v) => Math.max(.08, Math.min(1, Math.pow(v / peak, .62))));
  }

  function analyse(buffer) {
    const rate = buffer.sampleRate, durationMs = buffer.duration * 1000;
    const channels = [];
    for (let c = 0; c < buffer.numberOfChannels; c++) channels.push(buffer.getChannelData(c));
    const bucketSamples = Math.max(1, Math.round(rate * FINE_MS / 1000));
    const bucketCount = Math.ceil(buffer.length / bucketSamples);
    const levels = new Float32Array(bucketCount);
    for (let b = 0; b < bucketCount; b++) {
      let sum = 0, count = 0;
      const start = b * bucketSamples, end = Math.min(buffer.length, start + bucketSamples);
      for (let i = start; i < end; i += 4) {
        let mono = 0;
        for (let c = 0; c < channels.length; c++) mono += channels[c][i];
        mono /= channels.length;
        sum += mono * mono; count++;
      }
      levels[b] = count ? Math.sqrt(sum / count) : 0;
    }
    const coarse = [];
    for (let i = 0; i < COLS; i++) {
      const from = Math.floor(i * bucketCount / COLS), to = Math.max(from + 1, Math.floor((i + 1) * bucketCount / COLS));
      let sum = 0;
      for (let b = from; b < to && b < bucketCount; b++) sum += levels[b] * levels[b];
      coarse.push(Math.sqrt(sum / Math.max(1, to - from)));
    }
    let peak = 0.0001;
    for (const v of levels) peak = Math.max(peak, v);
    const silent = peak < .003, threshold = Math.max(.004, peak * .03);
    let first = 0;
    while (first < bucketCount - 1 && levels[first] < threshold) first++;
    let last = bucketCount - 1;
    while (last > 0 && levels[last] < threshold) last--;
    let audibleStart = first * FINE_MS < 300 ? 0 : first * FINE_MS;
    let audibleEnd = last >= bucketCount - 2 ? durationMs : Math.min(durationMs, (last + 1) * FINE_MS);
    if (silent || audibleEnd < durationMs / 4) { audibleStart = 0; audibleEnd = durationMs; first = 0; last = bucketCount - 1; }
    const head = [], tail = [];
    for (let k = 0; k < FINE; k++) {
      const h = first + k, t = last - (FINE - 1 - k);
      head.push(h < bucketCount ? levels[h] : 0);
      tail.push(t >= 0 && t < bucketCount ? levels[t] : 0);
    }
    return {
      coarse: normalise(coarse, peak), head: normalise(head, peak), tail: normalise(tail, peak),
      durationMs, audibleStartMs: audibleStart, audibleEndMs: audibleEnd
    };
  }

  async function audioFor(track) {
    const stored = await storage.getOfflineTrack(track.id).catch(() => null);
    if (stored?.audioBlob) return stored.audioBlob.arrayBuffer();
    if (!navigator.onLine || String(track.id).startsWith('local:') || serverStreamBlocked()) return null;
    const response = await fetch(apiUrl(`api/stream/${track.id}`));
    if (!response.ok) return null;
    const size = Number(response.headers.get('content-length')) || 0;
    if (size > 40 * 1024 * 1024) return null;
    return response.arrayBuffer();
  }

  async function compute(track) {
    const stored = await readStored(track.id);
    if (stored) return stored;
    const data = await audioFor(track);
    if (!data || data.byteLength > 40 * 1024 * 1024) return null;
    const context = new (window.OfflineAudioContext || window.webkitOfflineAudioContext)(1, 1, 44100);
    const buffer = await context.decodeAudioData(data);
    if (buffer.duration > 20 * 60) return null;
    const envelope = analyse(buffer);
    writeStored(track.id, envelope);
    return envelope;
  }

  const MixEnv = {
    peek(id) { return memory.get(id) || null; },
    request(track) {
      if (!track?.id) return Promise.resolve(null);
      if (memory.has(track.id)) return Promise.resolve(memory.get(track.id));
      if (failed.has(track.id)) return Promise.resolve(null);
      if (!pending.has(track.id)) {
        pending.set(track.id, compute(track).then((envelope) => {
          if (envelope) memory.set(track.id, envelope); else failed.add(track.id);
          pending.delete(track.id);
          return envelope;
        }).catch(() => { failed.add(track.id); pending.delete(track.id); return null; }));
      }
      return pending.get(track.id);
    },
    /** Mide la canción actual y la siguiente en segundo plano, para mezclar sobre sonido y no sobre silencio. */
    warm() {
      if (!state.crossfadeSeconds) return;
      const current = state.currentTrack;
      if (current) MixEnv.request(current);
      const index = state.queueIndex + 1 < state.queue.length ? state.queueIndex + 1 : state.repeatMode === 'all' ? 0 : -1;
      if (index >= 0 && state.queue[index]) MixEnv.request(state.queue[index]);
    },
    /** Segundos desde los que la canción deja de sonar (o su duración si no se sabe). */
    endSeconds(id, duration) {
      const envelope = memory.get(id);
      return envelope && envelope.audibleEndMs > duration * 250 ? Math.min(duration, envelope.audibleEndMs / 1000) : duration;
    },
    startSeconds(id) {
      const envelope = memory.get(id);
      return envelope && envelope.audibleStartMs > 300 ? envelope.audibleStartMs / 1000 : 0;
    }
  };
  window.MixEnv = MixEnv;

  /* ---------- Dibujo ---------- */
  function hash(text) {
    let h = 0;
    for (let i = 0; i < text.length; i++) h = (Math.imul(h, 31) + text.charCodeAt(i)) | 0;
    return h;
  }
  function random(seed) {
    let s = seed >>> 0 || 1;
    return () => { s ^= s << 13; s >>>= 0; s ^= s >>> 17; s ^= s << 5; s >>>= 0; return s / 4294967296; };
  }
  function approximate(seed) {
    const rnd = random(hash(String(seed)) + 17);
    const a = .09 + rnd() * .07, b = .31 + rnd() * .2, c = .017 + rnd() * .02;
    const pa = rnd() * 6.28, pb = rnd() * 6.28, pc = rnd() * 6.28;
    const out = [];
    for (let i = 0; i < COLS; i++) {
      const slow = .5 + .5 * Math.sin(a * i + pa), mid = .5 + .5 * Math.sin(b * i + pb), section = .5 + .5 * Math.sin(c * i + pc);
      const value = .22 + .42 * slow * (.55 + .45 * mid) + .16 * section + .12 * rnd();
      out.push(Math.max(.16, Math.min(.88, value)));
    }
    for (let i = 0; i < 4; i++) { const k = (i + 1) / 5; out[i] *= k; out[COLS - 1 - i] *= k; }
    return out;
  }
  const ease = (t) => 1 - Math.pow(1 - t, 3);
  const smooth = (t) => t < .5 ? 2 * t * t : 1 - Math.pow(-2 * t + 2, 2) / 2;

  class MixBar {
    constructor(wrap) {
      this.wrap = wrap;
      this.canvas = document.createElement('canvas');
      this.canvas.className = 'progress-wave';
      wrap.append(this.canvas);
      this.ctx = this.canvas.getContext('2d');
      this.display = approximate('');
      this.from = this.display.slice(); this.to = this.display.slice();
      this.key = null; this.real = false; this.morphStart = 0; this.morph = 1; this.realness = 1;
      this.waveAlpha = 0; this.zoneShown = 0; this.zoom = 0; this.mixFraming = null;
      this.envelope = null; this.nextEnvelope = null;
      this.size = { w: 0, h: 0, dpr: 1 };
      new ResizeObserver(() => this.resize()).observe(wrap);
      this.last = performance.now();
      requestAnimationFrame((t) => this.frame(t));
      wrap.addEventListener('click', (event) => this.seek(event), true);
    }
    resize() {
      const rect = this.wrap.getBoundingClientRect(), dpr = window.devicePixelRatio || 1;
      this.size = { w: rect.width, h: rect.height, dpr };
      this.canvas.width = Math.max(1, Math.round(rect.width * dpr));
      this.canvas.height = Math.max(1, Math.round(rect.height * dpr));
    }
    /** Con el zoom activo la barra muestra solo un tramo: el clic debe resolverse con esa misma ventana. */
    seek(event) {
      if (this.zoom < .02 || !this.view) return;
      event.stopImmediatePropagation();
      const rect = this.wrap.getBoundingClientRect();
      const fraction = this.view.v0 + (event.clientX - rect.left) / rect.width * (this.view.v1 - this.view.v0);
      const duration = this.view.duration;
      if (duration > 0 && typeof seekTo === 'function') seekTo(Math.max(0, Math.min(duration, fraction * duration)));
    }
    setEnvelope(key, envelope, next) {
      this.nextEnvelope = next || null;
      const real = !!envelope;
      if (key === this.key && envelope === this.envelope && real === this.real) return;
      this.key = key; this.envelope = envelope; this.real = real;
      this.from = this.display.slice();
      this.to = real ? envelope.coarse.slice() : approximate(key || '');
      this.realFrom = this.realness; this.realTo = real ? 1 : 0;
      this.morphStart = performance.now(); this.morph = 0;
      if (matchMedia('(prefers-reduced-motion: reduce)').matches) { this.display = this.to.slice(); this.morph = 1; this.realness = this.realTo; }
    }
    sampleBase(fraction) {
      const position = Math.max(0, Math.min(1, fraction)) * COLS - .5;
      const i0 = Math.max(0, Math.min(COLS - 1, Math.floor(position))), i1 = Math.min(COLS - 1, i0 + 1);
      const k = Math.max(0, Math.min(1, position - Math.floor(position)));
      return this.display[i0] + (this.display[i1] - this.display[i0]) * k;
    }
    tailAt(timeMs, endMs) {
      const tail = this.envelope && this.envelope.tail;
      if (!tail) return -1;
      const index = (timeMs - (endMs - FINE_SECONDS * 1000)) / (FINE_SECONDS * 1000) * tail.length;
      return tail[Math.max(0, Math.min(tail.length - 1, Math.floor(index)))];
    }
    frame(now) {
      const dt = Math.min(64, now - this.last); this.last = now;
      const reduced = matchMedia('(prefers-reduced-motion: reduce)').matches;
      const on = wanted() && audio.hasAttribute('src') && !state.videoDockOpen;
      const media = (typeof outgoingAudio !== 'undefined' && outgoingAudio) ? outgoingAudio : audio;
      const mixing = typeof outgoingAudio !== 'undefined' && !!outgoingAudio;
      const approach = (current, target, ms) => reduced ? target : current + Math.sign(target - current) * Math.min(Math.abs(target - current), dt / ms);
      this.waveAlpha = approach(this.waveAlpha, on ? 1 : 0, 460);
      this.wrap.classList.toggle('wave-on', this.waveAlpha > .01);
      if (this.waveAlpha > .005) this.draw(media, mixing, approach);
      else this.ctx.clearRect(0, 0, this.canvas.width, this.canvas.height);
      requestAnimationFrame((t) => this.frame(t));
    }
    draw(media, mixing, approach) {
      const { w, h, dpr } = this.size;
      if (w < 2 || h < 2) return;
      const ctx = this.ctx;
      ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
      ctx.clearRect(0, 0, w, h);
      const duration = Number.isFinite(media.duration) && media.duration > 0 ? media.duration : 0;
      const frozen = mixing && typeof outgoingMix !== 'undefined' ? outgoingMix : null;
      const track = mixing ? frozen?.track || this.outgoingTrack : state.currentTrack;
      if (!mixing) this.outgoingTrack = track;
      const envelope = track ? MixEnv.peek(track.id) : null;
      const index = state.queueIndex + 1 < state.queue.length ? state.queueIndex + 1 : state.repeatMode === 'all' ? 0 : -1;
      const nextTrack = mixing ? state.currentTrack : (index >= 0 ? state.queue[index] : null);
      const nextEnvelope = nextTrack ? MixEnv.peek(nextTrack.id) : null;
      if (!mixing) this.setEnvelope(track ? track.id : '', envelope, nextEnvelope);
      else this.nextEnvelope = nextEnvelope;
      // Relieve: cada barra arranca un poco después que la anterior.
      if (this.morph < 1) {
        const elapsed = performance.now() - this.morphStart;
        this.morph = Math.min(1, elapsed / (this.real ? 1000 : 900));
        this.realness = this.realFrom + (this.realTo - this.realFrom) * this.morph;
        for (let i = 0; i < COLS; i++) {
          const local = Math.max(0, Math.min(1, this.morph * 2.2 - (i / COLS) * 1.2));
          const eased = ease(local) + Math.sin(local * Math.PI) * .06;
          this.display[i] = this.from[i] + (this.to[i] - this.from[i]) * eased;
        }
      }
      const zoneSeconds = frozen?.seconds ?? ((!state.queue?.length || state.repeatMode === 'one' || index < 0 && !mixing) ? 0 : state.crossfadeSeconds);
      const endSeconds = frozen?.endSeconds ?? (duration ? MixEnv.endSeconds(track?.id || '', duration) : 0);
      const zoneTarget = duration && endSeconds >= zoneSeconds * 2 ? zoneSeconds / duration : 0;
      const endFraction = duration ? Math.min(1, endSeconds / duration) : 1;
      this.zoneShown = approach(this.zoneShown, zoneTarget, 560);
      this.zoom = approach(this.zoom, mixing && zoneTarget > 0 ? 1 : 0, mixing ? 720 : 880);
      // Al terminar la mezcla el zoom tarda en salir: se conserva el encuadre de la mezcla en vez de recalcularlo con la
      // canción nueva, para que la ventana se abra suave y no dé un salto.
      const framing = { start: Math.max(0, endFraction - zoneTarget * 2.2), end: Math.min(1, endFraction + Math.max(.01, zoneTarget * .15)) };
      if (mixing) this.mixFraming = framing;
      else if (this.zoom <= .001) this.mixFraming = null;
      const { start: zoomStart, end: zoomEnd } = !mixing && this.mixFraming ? this.mixFraming : framing;
      const e = smooth(this.zoom);
      const v0 = e * zoomStart, v1 = 1 + (zoomEnd - 1) * e;
      this.view = { v0, v1, duration };
      const progress = duration ? Math.max(0, Math.min(1, media.currentTime / duration)) : 0;
      const centre = h / 2, maxHalf = Math.max(2, Math.min(centre - 1, 13));
      const pitch = w / COLS, barWidth = Math.max(1.3, pitch * .6);
      const certainty = (.74 + .26 * this.realness) * this.waveAlpha;
      const zoneStartFraction = endFraction - this.zoneShown;
      const accent = getComputedStyle(document.documentElement).getPropertyValue('--accent').trim() || '#ff7a1a';
      const dark = document.documentElement.dataset.theme === 'light';
      const ink = dark ? '0,0,0' : '255,255,255';
      const roundBar = (x, half, width, fill) => {
        ctx.fillStyle = fill;
        ctx.beginPath();
        if (ctx.roundRect) ctx.roundRect(x - width / 2, centre - half, width, half * 2, width / 2); else ctx.rect(x - width / 2, centre - half, width, half * 2);
        ctx.fill();
      };
      for (let i = 0; i < COLS; i++) {
        const f = v0 + (i + .5) / COLS * (v1 - v0);
        const x = pitch * (i + .5);
        const played = f <= progress;
        let height = this.sampleBase(f);
        if (this.zoom > .01 && envelope && envelope.tail) {
          const t = this.tailAt(f * duration * 1000, endFraction * duration * 1000);
          if (t >= 0 && f * duration * 1000 >= endFraction * duration * 1000 - FINE_SECONDS * 1000) height += (Math.max(.08, t) - height) * this.zoom;
        }
        let incoming = 0;
        if (this.zoneShown > .001 && f >= zoneStartFraction && f <= endFraction) {
          const t = Math.max(0, Math.min(1, (f - zoneStartFraction) / Math.max(.0001, this.zoneShown)));
          const zoneMs = this.zoneShown * duration * 1000;
          const tailValue = this.tailAt(endFraction * duration * 1000 - zoneMs + t * zoneMs, endFraction * duration * 1000);
          const outgoing = tailValue >= 0 ? Math.max(.08, tailValue) : height;
          height = outgoing * Math.cos(t * Math.PI / 2);
          const head = this.nextEnvelope && this.nextEnvelope.head;
          let source = .5;
          if (head) source = Math.max(.08, head[Math.max(0, Math.min(head.length - 1, Math.floor(t * zoneMs / (FINE_SECONDS * 1000) * head.length)))]);
          incoming = source * Math.sin(t * Math.PI / 2);
        }
        roundBar(x, Math.max(1, height * maxHalf), barWidth, `rgba(${ink},${(played ? 1 : .38) * certainty})`);
        if (incoming > 0) roundBar(x, Math.max(1, incoming * maxHalf), barWidth * .56, played ? accent : accent + '99');
      }
    }
  }

  function init() {
    const wrap = document.getElementById('progress-wrap');
    if (!wrap || wrap.dataset.mixbar) return;
    wrap.dataset.mixbar = '1';
    window.mixBar = new MixBar(wrap);
    setInterval(() => MixEnv.warm(), 1500);
  }
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init); else init();
})();
