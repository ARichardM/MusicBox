/*
 * Al cerrar y volver a abrir la web, todo queda donde se dejó: la pantalla (inicio, biblioteca o la playlist abierta con su
 * desplazamiento), la canción y la cola, y el minuto en que iba. El audio no arranca solo: al pulsar Reproducir continúa
 * desde ese minuto.
 */
(function () {
  const KEY = 'musicbox_session_v1';
  let resume = null;

  function slim(track) {
    return track ? { id: track.id, title: track.title, artist: track.artist, album: track.album, duration: track.duration, thumbnail: track.thumbnail, url: track.url } : null;
  }

  function currentPosition() {
    try {
      if (state.isOfflinePlayback) return audio.currentTime || 0;
      if (typeof ytPlayer !== 'undefined' && ytPlayer?.getCurrentTime) return ytPlayer.getCurrentTime() || 0;
    } catch {}
    return 0;
  }

  function save() {
    try {
      const track = state.currentTrack;
      const main = document.getElementById('main-content');
      const view = activePlaylist ? { kind: 'playlist', id: activePlaylist.id, folder: activePlaylist.kind === 'folder' }
        : state.activeView === 'library' ? { kind: 'library' } : { kind: 'home' };
      const previous = JSON.parse(localStorage.getItem(KEY) || 'null');
      const position = track ? currentPosition() : 0;
      // Mientras la canción restaurada aún no se ha cargado, se conserva el minuto guardado en vez de pisarlo con 0.
      const keepSaved = resume && track && resume.id === track.id && position === 0 && previous?.track?.id === track.id;
      localStorage.setItem(KEY, JSON.stringify({
        track: slim(track), queue: state.queue.slice(0, 150).map(slim), index: state.queueIndex,
        position: keepSaved ? previous.position : position, view, scroll: main ? main.scrollTop : 0, savedAt: Date.now()
      }));
    } catch {}
  }

  async function restoreView(view, scroll) {
    try {
      if (view?.kind === 'playlist') {
        const playlists = await storage.getAllPlaylists();
        const target = playlists.find((p) => p.id === view.id);
        if (target) await loadPlaylistView(target);
      } else if (view?.kind === 'library') await loadLibraryView();
      if (scroll) requestAnimationFrame(() => { document.getElementById('main-content').scrollTop = scroll; });
    } catch {}
  }

  function restore() {
    let saved = null;
    try { saved = JSON.parse(localStorage.getItem(KEY) || 'null'); } catch {}
    if (!saved) return;
    if (saved.track && saved.track.id) {
      state.queue = (saved.queue || []).filter((t) => t && t.id);
      if (!state.queue.some((t) => t.id === saved.track.id)) state.queue.unshift(saved.track);
      state.queueIndex = Math.max(0, state.queue.findIndex((t) => t.id === saved.track.id));
      state.currentTrack = state.queue[state.queueIndex];
      resume = { id: saved.track.id, position: saved.position || 0 };
      updatePlayerBarUI(state.currentTrack);
      if (typeof updateMediaSessionMetadata === 'function') updateMediaSessionMetadata(state.currentTrack);
      const duration = state.currentTrack.duration || 0;
      updateProgress(resume.position, duration);
      document.body.classList.add('has-track');
    }
    restoreView(saved.view, saved.scroll);
  }

  // Pulsar Reproducir sobre una canción restaurada la carga y continúa desde el minuto guardado.
  function resumePending() { return resume && state.currentTrack && resume.id === state.currentTrack.id && !state.isPlaying; }
  function startResume() {
    const pending = resume;
    resume = null;
    Promise.resolve(playTrack(state.currentTrack, true)).then(() => {
      if (pending.position < 2) return;
      const deadline = Date.now() + 9000;
      const tick = () => {
        const ready = state.isOfflinePlayback ? audio.readyState >= 1 : (typeof ytReady !== 'undefined' && ytReady && ytPlayer?.getDuration?.() > 0);
        if (ready) { seekTo(pending.position); return; }
        if (Date.now() < deadline) setTimeout(tick, 150);
      };
      tick();
    });
  }
  const nativeToggle = togglePlayPause;
  togglePlayPause = function () {
    if (resumePending()) { startResume(); return; }
    return nativeToggle.apply(this, arguments);
  };
  const button = document.getElementById('play-pause-btn');
  if (button) button.addEventListener('click', (event) => {
    if (!resumePending()) return;
    event.stopImmediatePropagation();
    startResume();
  }, true);

  // Cualquier otra acción de reproducción cancela la reanudación pendiente.
  const nativePlay = playTrack;
  playTrack = function (track) {
    if (!(resume && track?.id === resume.id)) resume = null;
    return nativePlay.apply(this, arguments);
  };

  // La siguiente canción se resuelve en el servidor mientras suena la actual: al llegar a ella empieza sin esperar.
  const warmed = new Set();
  setInterval(async () => {
    if (!state.isPlaying || !navigator.onLine || state.queue.length < 2) return;
    const index = state.queueIndex + 1 < state.queue.length ? state.queueIndex + 1 : state.repeatMode === 'all' ? 0 : -1;
    const next = state.queue[index];
    if (!next || warmed.has(next.id) || String(next.id).startsWith('local:') || !/^[A-Za-z0-9_-]{11}$/.test(next.id)) return;
    warmed.add(next.id);
    if (warmed.size > 200) warmed.delete(warmed.values().next().value);
    try { if (!(await storage.getOfflineTrack(next.id))?.audioBlob) fetch(apiUrl(`api/stream/${next.id}`), { headers: { Range: 'bytes=0-1' } }).catch(() => {}); } catch {}
  }, 3000);

  setInterval(save, 4000);
  window.addEventListener('pagehide', save);
  document.addEventListener('visibilitychange', () => { if (document.visibilityState === 'hidden') save(); });
  window.musicboxSession = { save, restore };

  const start = () => setTimeout(restore, 400);
  if (document.readyState === 'complete') start(); else window.addEventListener('load', start);
})();
