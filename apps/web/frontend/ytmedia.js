// Respaldo de reproducción en línea con el reproductor oficial de YouTube.
// Cuando el servidor no consigue el audio (YouTube bloquea su IP), el navegador del usuario sí puede reproducirlo
// con el iframe. <yt-media> imita lo que la app usa de <video> (src, play, pause, currentTime, duration, volume y
// sus eventos) para que el resto del reproductor funcione igual. Su src tiene la forma «yt:<id>».
(() => {
  let apiReady = null;
  function loadApi() {
    if (window.YT?.Player) return Promise.resolve();
    if (apiReady) return apiReady;
    apiReady = new Promise((resolve, reject) => {
      const previous = window.onYouTubeIframeAPIReady;
      window.onYouTubeIframeAPIReady = () => { previous?.(); resolve(); };
      const script = document.createElement('script');
      script.src = 'https://www.youtube.com/iframe_api';
      script.onerror = () => { apiReady = null; reject(new Error('No se pudo cargar el reproductor de YouTube')); };
      document.head.append(script);
    });
    return apiReady;
  }

  const EMBED_BLOCKED = new Set([101, 150, 153]);
  const clipSources = new Map();
  async function clipFor(id) {
    if (clipSources.has(id)) return clipSources.get(id);
    let clip = '';
    try {
      const response = await fetch(apiUrl(`api/video-source/${id}`));
      if (response.ok) clip = (await response.json()).id || '';
    } catch {}
    clipSources.set(id, clip);
    return clip;
  }

  class YouTubeMedia extends HTMLElement {
    static get observedAttributes() { return ['src']; }

    constructor() {
      super();
      this.isYouTube = true;
      this.playsInline = true; this.controls = false; this.preload = 'auto';
      this._player = null; this._ready = false; this._id = ''; this._loadedId = '';
      this._paused = true; this._duration = NaN; this._readyState = 0; this._error = null;
      this._volume = 1; this._muted = false; this._pendingTime = 0; this._metadataSent = false;
      this._triedClip = false; this._showVideo = false; this._ticker = 0; this._loadQueued = false; this._cuedAt = 0;
    }

    connectedCallback() {
      if (!this._host) {
        this._host = document.createElement('div');
        this._host.style.cssText = 'width:100%;height:100%';
        this.append(this._host);
      }
      this._queueLoad();
    }

    attributeChangedCallback() { this._pendingTime = 0; this._loadedId = ''; this._queueLoad(); }

    get src() { return this.getAttribute('src') || ''; }
    set src(value) { this.setAttribute('src', value); }
    get currentSrc() { return this.src; }
    get paused() { return this._paused; }
    get ended() { return this._ready && this._player?.getPlayerState?.() === 0; }
    get readyState() { return this._readyState; }
    get error() { return this._error; }
    get duration() { return this._duration; }
    get videoWidth() { return this._showVideo && this._readyState >= 2 ? 1280 : 0; }
    get videoHeight() { return this._showVideo && this._readyState >= 2 ? 720 : 0; }
    get currentTime() {
      if (!this._ready || this._loadedId !== this._id) return this._pendingTime;
      return this._player.getCurrentTime?.() || 0;
    }
    set currentTime(seconds) {
      const target = Math.max(0, Number(seconds) || 0);
      this._emit('seeking');
      if (this._ready && this._loadedId === this._id) this._player.seekTo(target, true);
      else this._pendingTime = target;
      setTimeout(() => this._emit('seeked'), 0);
    }
    get volume() { return this._volume; }
    set volume(value) {
      this._volume = Math.max(0, Math.min(1, Number(value) || 0));
      if (this._ready) this._player.setVolume(Math.round(this._volume * 100));
      this._emit('volumechange');
    }
    get muted() { return this._muted; }
    set muted(value) {
      this._muted = !!value;
      if (this._ready) this._muted ? this._player.mute() : this._player.unMute();
      this._emit('volumechange');
    }

    // En modo MP4 se carga el videoclip (si la canción tiene uno distinto) y el marco queda visible.
    async setVideoVisible(visible) {
      this._showVideo = !!visible;
      this.classList.toggle('yt-media-hidden', !visible);
      const songId = this._songId();
      if (!songId) return;
      const target = visible ? (await clipFor(songId)) || songId : this._id;
      if (target && target !== this._id) this._switchTo(target);
      else if (visible && this._readyState >= 2) this._emit('loadeddata');
    }

    play() {
      this._paused = false;
      if (this._ready && this._loadedId === this._id) {
        this._player.playVideo();
      }
      return Promise.resolve();
    }

    pause() {
      const wasPlaying = !this._paused;
      this._paused = true;
      if (this._ready) this._player.pauseVideo();
      if (wasPlaying) this._emit('pause');
    }

    load() { this._loadQueued = false; this._load(); }

    _songId() { return (this.src.match(/^yt:([A-Za-z0-9_-]{11})$/) || [])[1] || ''; }

    _queueLoad() {
      if (this._loadQueued) return;
      this._loadQueued = true;
      queueMicrotask(() => { if (this._loadQueued) this.load(); });
    }

    _emit(type) { this.dispatchEvent(new Event(type)); }

    _reset() {
      clearInterval(this._ticker);
      this._ticker = 0;
      this._duration = NaN; this._readyState = 0; this._error = null; this._metadataSent = false;
    }

    _load() {
      const id = this._songId();
      this._reset();
      this._triedClip = false;
      if (!id) {
        this._id = ''; this._loadedId = ''; this._pendingTime = 0;
        if (this._ready) this._player.stopVideo();
        this._emit('emptied');
        return;
      }
      this._emit('loadstart');
      if (this._showVideo) {
        clipFor(id).then((clip) => { if (this._songId() === id) this._switchTo(clip || id); });
      } else this._switchTo(id);
    }

    _switchTo(id) {
      const position = this._loadedId ? this.currentTime : this._pendingTime;
      this._reset();
      this._id = id;
      this._pendingTime = position;
      this._emit('waiting');
      loadApi().then(() => this._start()).catch(() => this._fail(2));
    }

    _start() {
      if (!this.isConnected) return;
      if (!this._player) {
        this._player = new YT.Player(this._host, {
          width: '100%', height: '100%',
          playerVars: { autoplay: 0, controls: 0, disablekb: 1, fs: 0, iv_load_policy: 3, playsinline: 1, rel: 0 },
          events: {
            onReady: () => { this._ready = true; this._cue(); },
            onStateChange: (event) => this._stateChanged(event.data),
            onError: (event) => this._playerError(event.data),
          },
        });
        return;
      }
      if (this._ready) this._cue();
    }

    _cue() {
      if (!this._id) return;
      this._player.setVolume(Math.round(this._volume * 100));
      this._muted ? this._player.mute() : this._player.unMute();
      this._loadedId = this._id;
      this._cuedAt = performance.now();
      const options = { videoId: this._id, startSeconds: this._pendingTime || 0 };
      if (this._paused) this._player.cueVideoById(options);
      else this._player.loadVideoById(options);
    }

    _metadata() {
      const duration = this._player.getDuration?.() || 0;
      if (duration > 0 && duration !== this._duration) {
        this._duration = duration;
        this._emit('durationchange');
      }
      if (!this._metadataSent && duration > 0) {
        this._metadataSent = true;
        this._readyState = 4;
        for (const type of ['loadedmetadata', 'loadeddata', 'canplay']) this._emit(type);
      }
    }

    _stateChanged(state) {
      if (this._loadedId !== this._id) return;
      if (state === YT.PlayerState.CUED) {
        this._metadata();
        if (!this._paused) this._player.playVideo();
      } else if (state === YT.PlayerState.PLAYING) {
        this._metadata();
        this._readyState = 4;
        if (this._paused) { this._player.pauseVideo(); return; }
        this._emit('playing');
        clearInterval(this._ticker);
        this._ticker = setInterval(() => { this._metadata(); this._emit('timeupdate'); }, 250);
      } else if (state === YT.PlayerState.PAUSED) {
        // La pausa pedida a la canción anterior puede llegar después de cargar la nueva: no es del usuario.
        if (!this._paused && performance.now() - this._cuedAt < 2000) { this._player.playVideo(); return; }
        clearInterval(this._ticker);
        if (!this._paused) { this._paused = true; this._emit('pause'); }
      } else if (state === YT.PlayerState.BUFFERING) {
        this._readyState = 2;
        this._emit('waiting');
      } else if (state === YT.PlayerState.ENDED) {
        clearInterval(this._ticker);
        this._paused = true;
        this._emit('ended');
      }
    }

    _playerError(code) {
      // Algunas pistas de audio no permiten incrustarse: se intenta una vez con su videoclip.
      const songId = this._songId();
      if (EMBED_BLOCKED.has(code) && !this._triedClip && songId) {
        this._triedClip = true;
        clipFor(songId).then((clip) => {
          if (clip && clip !== this._id && this._songId() === songId) this._switchTo(clip);
          else this._fail(code);
        });
        return;
      }
      this._fail(code);
    }

    _fail(code) {
      clearInterval(this._ticker);
      this._paused = true;
      this._error = { code, message: 'YouTube no pudo reproducir este contenido' };
      this._emit('error');
    }
  }

  if (!customElements.get('yt-media')) customElements.define('yt-media', YouTubeMedia);
})();
