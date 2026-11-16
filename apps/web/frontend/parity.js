/* Browser interactions following the existing QML interface. */
const downloadJobs = new Map();
function downloadHistory() { try { return JSON.parse(localStorage.getItem('musicbox_download_history') || '[]'); } catch { return []; } }
function recordDownload(job, status, message='') {
  const entries=downloadHistory();entries.unshift({track:{id:job.track.id,title:job.track.title,artist:job.track.artist,thumbnail:job.track.thumbnail},format:job.format,quality:job.quality,status,message,at:Date.now()});
  localStorage.setItem('musicbox_download_history',JSON.stringify(entries.slice(0,100)));
}
const loadPlaybackHistoryView=loadHistoryView;
loadHistoryView = async function(mode='downloads') {
  if(mode==='plays') {
    await loadPlaybackHistoryView();
    addHistoryTabs('plays');return;
  }
  state.activeView='history';updateActiveNavButton('history');
  const main=document.getElementById('main-content');
  main.innerHTML='<h1 class="page-heading">Historial</h1><div id="download-history-list" class="tracks-list"></div>';
  addHistoryTabs('downloads');
  const list=document.getElementById('download-history-list');
  const entries=downloadHistory();
  for(const entry of entries) {
    const row=document.createElement('div');row.className='row-item';
    const status={done:'Completada',failed:'Error',cancelled:'Cancelada'}[entry.status] || entry.status;
    row.innerHTML=`<div class="row-details"><div class="row-title">${escapeHTML(entry.track.title)}</div><div class="row-subtitle">${escapeHTML(`${entry.format?.toUpperCase()} · ${entry.quality} · ${status}`)}</div><small>${escapeHTML(new Date(entry.at).toLocaleString())}</small></div>`;
    if(entry.status!=='done')row.append(actionButton('Reintentar descarga','retry',()=>downloadTrackForOffline(entry.track)));
    list.append(row);
  }
  if(!entries.length)list.textContent='Todavía no hay descargas en este navegador.';
  const clear=actionButton('Limpiar historial','trash',async()=>{if(await confirmAction('Limpiar historial','¿Borrar el registro de descargas? Los archivos guardados se conservan.')){localStorage.removeItem('musicbox_download_history');loadHistoryView();}},'pill-btn-danger');
  main.append(clear);
};
function addHistoryTabs(mode) {
  const tabs=document.createElement('div');tabs.className='library-toolbar';
  for(const [key,label] of [['downloads','Descargas'],['plays','Reproducciones']])tabs.append(actionButton(label,'',()=>loadHistoryView(key),key===mode?'pill-btn-accent':'pill-btn'));
  document.getElementById('main-content').querySelector('h1').after(tabs);
}
function renderDownloadJobs() {
  if (state.activeView !== 'downloads') return;
  let panel = document.getElementById('active-download-jobs');
  if (!panel) { panel = document.createElement('div'); panel.id = 'active-download-jobs'; document.getElementById('main-content').append(panel); }
  panel.replaceChildren();
  for (const job of downloadJobs.values()) {
    const row = document.createElement('div'); row.className = 'row-item';
    const title = document.createElement('span'); title.className = 'row-details'; title.textContent = job.track.title;
    const progress = document.createElement('span'); progress.className = 'download-progress'; progress.textContent = job.progress === null ? 'Descargando…' : `${job.progress}%`;
    row.append(title, progress, actionButton('Cancelar descarga', 'close', () => job.controller.abort()));
    panel.append(row);
  }
}
function actionButton(label, icon, action, className = 'icon-btn') {
  const button = document.createElement('button');
  button.className = className;
  button.type = 'button';
  button.title = label;
  button.setAttribute('aria-label', label);
  if (icon) button.innerHTML = `<img src="icons/${icon}.svg" alt="">`;
  else button.textContent = label;
  if (icon && /context-item|pill-btn|nav-item/.test(className)) {
    const text=document.createElement('span');text.textContent=label;
    if(className.includes('nav-item'))text.className='nav-label';
    button.append(text);
  }
  button.onclick = action;
  return button;
}

function openDialog(title, content) {
  const previous = document.activeElement;
  const dialog = document.createElement('dialog');
  dialog.className = 'musicbox-dialog';
  const header = document.createElement('div');
  header.className = 'modal-header';
  const heading = document.createElement('h2');
  heading.textContent = title;
  header.append(heading, actionButton('Cerrar', 'close', () => dialog.close()));
  dialog.append(header, content);
  const nativeClose = dialog.close.bind(dialog);
  dialog.close = value => { nativeClose(value); dialog.remove(); previous?.focus(); };
  dialog.addEventListener('click', e => { if (e.target === dialog) dialog.close(); });
  dialog.addEventListener('close', () => { dialog.remove(); previous?.focus(); });
  document.body.append(dialog);
  dialog.showModal();
  return dialog;
}

async function confirmAction(title, description) {
  return new Promise(resolve => {
    const body = document.createElement('div');
    body.className = 'modal-body';
    const text = document.createElement('p');
    text.textContent = description;
    const actions = document.createElement('div');
    actions.className = 'modal-actions';
    const dialog = openDialog(title, body);
    let accepted = false;
    actions.append(actionButton('Cancelar', '', () => dialog.close(), 'pill-btn-ghost'),
      actionButton('Confirmar', '', () => { accepted = true; dialog.close(); }, 'pill-btn-danger'));
    body.append(text, actions);
    dialog.addEventListener('close', () => resolve(accepted));
  });
}

function moveQueue(from, to) {
  if (to < 0 || to >= state.queue.length || from === to) return;
  const playing = state.queue[state.queueIndex];
  const [item] = state.queue.splice(from, 1);
  state.queue.splice(to, 0, item);
  state.queueIndex = state.queue.indexOf(playing);
  renderQueue();
}

renderQueue = function () {
  const list = document.getElementById('queue-body');
  list.replaceChildren();
  if (!state.queue.length) { list.textContent = 'La cola está vacía'; return; }
  state.queue.forEach((track, index) => {
    const row = document.createElement('div');
    row.className = `row-item queue-row ${index === state.queueIndex ? 'playing' : ''}`;
    row.draggable = true;
    row.addEventListener('dragstart', e => e.dataTransfer.setData('text/plain', String(index)));
    row.addEventListener('dragover', e => e.preventDefault());
    row.addEventListener('drop', e => { e.preventDefault(); const from = Number(e.dataTransfer.getData('text/plain')); if (Number.isInteger(from)) moveQueue(from, index); });
    const play = actionButton(`Reproducir ${track.title}`, '', () => { state.queueIndex = index; playTrack(track, true); }, 'queue-track');
    play.innerHTML = `<img src="${escapeHTML(track.thumbnail || 'logo_complete.png')}" alt=""><span><strong>${escapeHTML(track.title)}</strong><small>${escapeHTML(track.artist || '')}</small></span>`;
    const controls = document.createElement('div');
    controls.className = 'row-actions';
    const up = actionButton('Subir en la cola', 'prev_page', () => moveQueue(index, index - 1));
    const down = actionButton('Bajar en la cola', 'next_page', () => moveQueue(index, index + 1));
    up.disabled = index === 0;
    down.disabled = index === state.queue.length - 1;
    const remove = actionButton('Quitar de la cola', 'close', () => {
      if (index === state.queueIndex) return;
      state.queue.splice(index, 1);
      if (index < state.queueIndex) state.queueIndex--;
      renderQueue();
    });
    remove.disabled = index === state.queueIndex;
    controls.append(up, down, remove);
    row.append(play, controls);
    list.append(row);
  });
};

async function playlistSettings(playlist) {
  const form = document.createElement('form');
  form.className = 'modal-body playlist-form';
  form.innerHTML = `<label>Nombre<input name="name" class="modal-input" required maxlength="80" value="${escapeHTML(playlist.name)}"></label>
    <label>Descripción<textarea name="description" class="modal-input" maxlength="500">${escapeHTML(playlist.description || '')}</textarea></label>
    <label class="check-row"><input name="pinned" type="checkbox" ${playlist.pinned ? 'checked' : ''}> Fijar en Inicio</label>
    <label class="check-row"><input name="autoDownload" type="checkbox" ${playlist.autoDownload ? 'checked' : ''}> Descarga automática</label>
    <p class="setting-desc">Las canciones locales de otro dispositivo se conservan y se reproducen desde ese dispositivo.</p>
    <div class="modal-actions"><button type="button" class="pill-btn-danger" id="delete-playlist">Eliminar playlist</button><button class="pill-btn-accent" type="submit">Guardar</button></div>`;
  const dialog = openDialog('Configurar playlist', form);
  form.onsubmit = async e => {
    e.preventDefault();
    const name = form.elements.name.value.trim();
    if (!name || name === 'Favoritos') { showToast('Elige otro nombre para la playlist.'); return; }
    if ((await storage.getAllPlaylists()).some(pl => pl.id !== playlist.id && pl.name.toLocaleLowerCase() === name.toLocaleLowerCase())) {
      showToast('Ya existe una playlist con ese nombre.'); return;
    }
    playlist.name = name;
    playlist.description = form.elements.description.value.trim();
    playlist.pinned = form.elements.pinned.checked;
    playlist.autoDownload = form.elements.autoDownload.checked;
    await storage.savePlaylist(playlist);
    dialog.close();
    loadPlaylistView(playlist);
    if (playlist.autoDownload) runAutoDownloads();
  };
  form.querySelector('#delete-playlist').onclick = async () => {
    if (!await confirmAction('Eliminar playlist', `¿Eliminar «${playlist.name}»? Las canciones descargadas se conservan.`)) return;
    const tx = storage.db.transaction('playlists', 'readwrite');
    tx.objectStore('playlists').delete(playlist.id);
    tx.oncomplete = () => { scheduleCloudSave(); dialog.close(); loadLibraryView('playlists'); };
  };
}

function exportPlaylist(playlist) {
  const quote = s => '"' + String(s || '').replaceAll('"', '""') + '"';
  const rows = [['Título', 'Artista', 'URL'], ...(playlist.tracks || []).map(t => [t.title, t.artist, t.url || `https://www.youtube.com/watch?v=${t.id}`])];
  saveBlob(new Blob(['\ufeff' + rows.map(r => r.map(quote).join(',')).join('\r\n')], { type: 'text/csv;charset=utf-8' }), playlist.name + '.csv');
}

function saveBlob(blob, name) {
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url; link.download = name; link.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}

loadPlaylistView = function (playlist) {
  state.activeView = 'library';
  activePlaylist = playlist;
  updateActiveNavButton('library');
  const main = document.getElementById('main-content');
  main.innerHTML = `<button class="icon-btn" id="playlist-back-btn" aria-label="Volver a playlists"><img src="icons/back.svg" alt=""></button>
    <div class="collection-header"><div class="collection-cover"><img src="${escapeHTML(playlist.tracks?.[0]?.thumbnail || 'icons/playlist.svg')}" alt=""></div>
    <div class="collection-info"><h1 class="page-heading">${escapeHTML(playlist.name)}</h1><p>${escapeHTML(playlist.description || '')}</p>
    <small>${playlist.tracks?.length || 0} canciones${playlist.unavailableEntries?.length ? ` · ${playlist.unavailableEntries.length} en otro dispositivo` : ''}</small></div></div>
    <div class="collection-toolbar" id="playlist-toolbar"></div>
    <label class="sr-only" for="playlist-search">Buscar en la playlist</label><input id="playlist-search" class="modal-input" placeholder="Buscar en la playlist">
    <div id="playlist-tracks-list" class="tracks-list"></div>`;
  document.getElementById('playlist-back-btn').onclick = () => { activePlaylist = null; loadLibraryView('playlists'); };
  const toolbar = document.getElementById('playlist-toolbar');
  const play = actionButton('Reproducir', 'play', () => {
    state.queue = [...playlist.tracks]; state.queueIndex = 0; playTrack(state.queue[0], true);
  }, 'pill-btn-accent');
  play.disabled = !playlist.tracks?.length;
  toolbar.append(play, actionButton('Aleatorio', 'shuffle', () => {
    if (!playlist.tracks?.length) return;
    state.queue = [...playlist.tracks]; state.queueIndex = Math.floor(Math.random() * state.queue.length);
    state.isShuffle = true; document.getElementById('shuffle-btn').classList.add('active'); playTrack(state.queue[state.queueIndex], true);
  }, 'pill-btn'), actionButton('Configurar playlist', 'more', () => playlistSettings(playlist)),
  actionButton('Compartir playlist (CSV)', 'external', () => exportPlaylist(playlist)));
  const render = () => {
    const list = document.getElementById('playlist-tracks-list');
    list.replaceChildren();
    const query = document.getElementById('playlist-search').value.toLocaleLowerCase();
    (playlist.tracks || []).forEach((track, index) => {
      if (!`${track.title} ${track.artist}`.toLocaleLowerCase().includes(query)) return;
      const row = createTrackRow(track);
      row.querySelector('.row-actions').append(actionButton('Quitar de la playlist', 'close', async () => {
        playlist.tracks.splice(index, 1); await storage.savePlaylist(playlist); loadPlaylistView(playlist);
      }));
      row.draggable = !query;
      row.ondragstart = e => e.dataTransfer.setData('text/plain', String(index));
      row.ondragover = e => e.preventDefault();
      row.ondrop = async e => {
        e.preventDefault(); if (query) return;
        const from = Number(e.dataTransfer.getData('text/plain'));
        if (!Number.isInteger(from) || from < 0 || from >= playlist.tracks.length) return;
        const [item] = playlist.tracks.splice(from, 1); playlist.tracks.splice(index, 0, item);
        await storage.savePlaylist(playlist); render();
      };
      list.append(row);
    });
    if (!list.children.length) list.textContent = query ? 'No se encontraron canciones.' : 'Agrega canciones desde el menú Más opciones.';
  };
  document.getElementById('playlist-search').oninput = render;
  render();
};

async function showTrackInfo(track) {
  const body = document.createElement('div');
  body.className = 'modal-body';
  body.innerHTML = `<h3>${escapeHTML(track.title)}</h3><p>${escapeHTML(track.artist || '')}</p><p>${escapeHTML(track.album || '')}</p>
    <p>${formatDuration(track.duration)} · ${await storage.getOfflineTrack(track.id) ? 'Descargada' : 'YouTube Music'}</p>`;
  const link = document.createElement('a');
  link.textContent = 'Abrir en YouTube'; link.href = `https://www.youtube.com/watch?v=${encodeURIComponent(track.id)}`;
  link.target = '_blank'; link.rel = 'noopener'; link.className = 'pill-btn';
  body.append(link);
  openDialog('Información de la canción', body);
}

async function showAlternatives(track) {
  const body = document.createElement('div'); body.className = 'modal-body tracks-list'; body.textContent = 'Buscando alternativas…';
  const dialog = openDialog('Ver alternativas', body);
  try {
    const response = await fetch(apiUrl(`api/search?q=${encodeURIComponent(`${track.title} ${track.artist || ''}`)}&type=songs`));
    if (!response.ok) throw new Error();
    const tracks = await response.json();
    body.replaceChildren();
    tracks.filter(t => t.id !== track.id).forEach(t => {
      const row = createTrackRow(t); body.append(row);
      row.addEventListener('click', e => { if (!e.target.closest('.row-actions')) dialog.close(); });
    });
    if (!body.children.length) body.textContent = 'No se encontraron otras grabaciones.';
  } catch { body.textContent = 'No se pudo buscar. Revisa tu conexión e inténtalo de nuevo.'; }
}

function setupAccessibility() {
  const enhance = root => {
    root.querySelectorAll('button').forEach(button => {
      if (!button.textContent.trim() && !button.hasAttribute('aria-label')) button.setAttribute('aria-label', button.title || button.querySelector('img')?.alt || 'Cerrar');
    });
    root.querySelectorAll('.artist-bubble,.for-you-card,.album-card,.row-item').forEach(item => {
      if (item.hasAttribute('tabindex')) return;
      item.tabIndex = 0;
      item.addEventListener('keydown', e => {
        if (e.target !== item) return;
        if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); item.click(); }
      });
    });
    root.querySelectorAll('.modal-backdrop').forEach(modal => {
      modal.setAttribute('role', 'dialog'); modal.setAttribute('aria-modal', 'true');
      const heading = modal.querySelector('h2');
      if (heading) { heading.id ||= modal.id + '-title'; modal.setAttribute('aria-labelledby', heading.id); }
    });
  };
  enhance(document);
  new MutationObserver(mutations => {
    for (const mutation of mutations) for (const node of mutation.addedNodes) if (node.nodeType === 1) enhance(node.parentElement || node);
  }).observe(document.body, { childList: true, subtree: true });
  document.addEventListener('keydown', e => {
    const modal = [...document.querySelectorAll('.modal-backdrop')].reverse().find(el => el.style.display !== 'none');
    if (e.key === 'Escape') {
      closeTrackContextMenu();
      if (modal) modal.style.display = 'none';
      for (const id of ['close-queue-btn', 'close-lyrics-btn']) document.getElementById(id).click();
      return;
    }
    if (modal && e.key === 'Tab') {
      const controls = [...modal.querySelectorAll('button,input,textarea,select,a[href]')].filter(el => el.getClientRects().length && !el.disabled);
      const first = controls[0], last = controls.at(-1);
      if (e.shiftKey && (document.activeElement === first || !modal.contains(document.activeElement))) { e.preventDefault(); last?.focus(); }
      else if (!e.shiftKey && (document.activeElement === last || !modal.contains(document.activeElement))) { e.preventDefault(); first?.focus(); }
      return;
    }
    if (e.code === 'Space' && !modal && !document.querySelector('dialog[open]') && !e.target.closest('input,textarea,select,button,[tabindex]')) { e.preventDefault(); togglePlayPause(); }
  });
  for (const [id, label] of [['progress-wrap', 'Posición de reproducción'], ['volume-wrap', 'Volumen']]) {
    const slider = document.getElementById(id); slider.tabIndex = 0; slider.setAttribute('role', 'slider'); slider.setAttribute('aria-label', label); slider.setAttribute('aria-valuemin', '0');
    slider.addEventListener('keydown', e => {
      if (!['ArrowLeft','ArrowRight','Home','End'].includes(e.key)) return;
      e.preventDefault();
      if (id === 'progress-wrap') {
        const max = Number(slider.getAttribute('aria-valuemax')) || 0;
        const current = Number(slider.getAttribute('aria-valuenow')) || 0;
        seekTo(e.key === 'Home' ? 0 : e.key === 'End' ? max : Math.max(0, Math.min(max, current + (e.key === 'ArrowRight' ? 5 : -5))));
      } else {
        state.volume = e.key === 'Home' ? 0 : e.key === 'End' ? 1 : Math.max(0, Math.min(1, state.volume + (e.key === 'ArrowRight' ? .05 : -.05)));
        state.isMuted = false; audio.volume = state.volume; ytPlayer?.unMute?.(); ytPlayer?.setVolume?.(state.volume * 100);
        document.getElementById('volume-fill').style.width = state.volume * 100 + '%';
        slider.setAttribute('aria-valuenow', Math.round(state.volume * 100));
      }
    });
  }
}

document.addEventListener('DOMContentLoaded', () => {
  const menu = document.getElementById('track-context-menu');
  const save = actionButton('Guardar archivo', 'save', async () => {
    const track = state.contextMenuTrack; closeTrackContextMenu();
    const stored = track && await storage.getOfflineTrack(track.id);
    if (!stored?.audioBlob) { showToast('Primero descarga o importa esta canción.'); return; }
    const mime = stored.audioBlob.type.split(';')[0];
    const extension = {'audio/mpeg':'mp3','video/mp4':'mp4','audio/mp4':'m4a','audio/wav':'wav','audio/webm':'webm','audio/ogg':'ogg','audio/flac':'flac'}[mime] || 'audio';
    saveBlob(stored.audioBlob, (track.title || track.id).replace(/[\\/:*?"<>|]/g, '_') + '.' + extension);
  }, 'context-item');
  save.id='ctx-save-file'; menu.append(save);
  for (const [label, icon, action] of [['Ver alternativas', 'sparkle', showAlternatives], ['Información de la canción', 'info', showTrackInfo]]) {
    menu.append(actionButton(label, icon, () => { const track = state.contextMenuTrack; closeTrackContextMenu(); if (track) action(track); }, 'context-item'));
  }
  const mobileSettings = actionButton('Configuración', 'more', () => document.getElementById('settings-btn').click(), 'nav-item mobile-settings');
  document.querySelector('.nav-section').append(mobileSettings);
  const nav = document.getElementById('nav-library');
  nav.querySelector('.nav-label').dataset.desktopLabel = 'Mi música';
  const mobileHeader = document.createElement('div'); mobileHeader.className = 'mobile-player-top';
  mobileHeader.append(actionButton('Cerrar reproductor', 'collapse', () => document.body.classList.remove('player-expanded')));
  const modes = document.createElement('div'); modes.className = 'mobile-player-modes';
  modes.append(actionButton('MP3', '', () => document.getElementById('hide-video-dock-btn').click(), 'pill-btn-sm'),
    actionButton('MP4', '', () => document.getElementById('video-toggle-btn').click(), 'pill-btn-sm'),
    actionButton('Letra', '', () => document.getElementById('lyrics-toggle-btn').click(), 'pill-btn-sm'));
  mobileHeader.append(modes, actionButton('Favorito', 'heart', () => document.getElementById('player-fav-btn').click()),
    actionButton('Más opciones', 'more', e => { if (state.currentTrack) openTrackContextMenu(e, state.currentTrack); }, 'icon-btn more-btn'));
  document.getElementById('bottom-player').prepend(mobileHeader);
  const modeButtons = [...modes.children];
  let modeTrack = null;
  let modeLyricsReady = false;
  let updateModes = () => {
    const track = state.currentTrack;
    const available = [true, !!track && (!track.id.startsWith('local:') || audio.videoWidth > 0),
      !!track && modeLyricsReady];
    // Los chips aparecen al instante con su animación, pero solo se ocultan si la ausencia se mantiene: así no parpadean al cambiar de canción.
    modeButtons.forEach((button, index) => {
      const apply = (hidden) => {
        button.classList.toggle('mode-unavailable', hidden);
        button.setAttribute('aria-hidden', String(hidden));
        button.tabIndex = hidden ? -1 : 0;
      };
      clearTimeout(button._hideTimer);
      if (available[index]) apply(false);
      else if (button.classList.contains('mode-unavailable')) apply(true);
      else button._hideTimer = setTimeout(() => { if (!button.classList.contains('mode-unavailable')) apply(true); }, 1800);
    });
  };
  const priorPlayerUI = updatePlayerBarUI;
  updatePlayerBarUI = function(track) {
    const changed = modeTrack !== track?.id;
    priorPlayerUI(track);
    if (changed) { modeTrack = track?.id; modeLyricsReady = false; }
    updateModes();
  };
  const priorRenderLyrics = renderLyrics;
  renderLyrics = function(data) {
    priorRenderLyrics(data);
    modeLyricsReady = !!data?.lines?.length;
    const wantsLyrics = localStorage.getItem('musicbox_lyrics_open') !== '0';
    if (document.body.classList.contains('player-expanded') && localStorage.getItem('musicbox_lyrics_open') === null) {
      state.lyricsOpen = true;
    } else {
      state.lyricsOpen = localStorage.getItem('musicbox_lyrics_open') === '1' || (document.body.classList.contains('player-expanded') && wantsLyrics);
    }
    const drawer = document.getElementById('lyrics-drawer');
    const btn = document.getElementById('lyrics-toggle-btn');
    if (drawer) drawer.classList.toggle('open', !!state.lyricsOpen && modeLyricsReady);
    if (btn) btn.classList.toggle('active', !!state.lyricsOpen && modeLyricsReady);
    document.body.classList.toggle('lyrics-open', !!state.lyricsOpen && modeLyricsReady);
    updateModes();
  };
  audio.addEventListener('loadedmetadata', () => updateModes());
  updateModes();
  // El chip activo se rellena y los demás mantienen buen contraste (igual que en escritorio y Android).
  const paintModes = () => {
    const video = document.getElementById('video-toggle-btn')?.classList.contains('active') && document.getElementById('video-dock') && !document.getElementById('video-dock').classList.contains('dock-hidden');
    const lyrics = document.getElementById('lyrics-toggle-btn')?.classList.contains('active') && !document.getElementById('lyrics-drawer')?.classList.contains('mode-unavailable');
    [!video, !!video, !!lyrics].forEach((on, index) => { modeButtons[index].classList.toggle('mode-active', on); modeButtons[index].setAttribute('aria-pressed', String(on)); });
  };
  const modeObserver = new MutationObserver(paintModes);
  for (const id of ['video-toggle-btn', 'lyrics-toggle-btn', 'video-dock']) { const node = document.getElementById(id); if (node) modeObserver.observe(node, { attributes: true, attributeFilter: ['class'] }); }
  const afterModes = updateModes;
  updateModes = () => { afterModes(); paintModes(); };
  paintModes();
  document.querySelector('.now-playing-cover-wrap').tabIndex = 0;
  const openPlayer = () => { if (matchMedia('(max-width:680px), (pointer:coarse) and (max-height:600px)').matches && state.currentTrack) document.body.classList.add('player-expanded'); };
  document.querySelector('.player-left').addEventListener('click', e => { if (!e.target.closest('button,.player-source')) openPlayer(); });
  document.querySelector('.now-playing-cover-wrap').addEventListener('keydown', e => { if (e.key === 'Enter') openPlayer(); });
  const miniQueue = actionButton('Abrir cola', 'queue', () => document.getElementById('queue-toggle-btn').click(), 'control-btn mini-queue');
  document.querySelector('.player-controls').insertBefore(miniQueue, document.getElementById('next-btn'));
  const updateMobile = () => {
    nav.querySelector('.nav-label').textContent = matchMedia('(max-width:680px), (pointer:coarse) and (max-height:600px)').matches ? 'Música' : 'Mi música';
    document.body.classList.toggle('has-track', !!state.currentTrack);
    if (!document.body.classList.contains('player-expanded')) document.body.classList.remove('cover-zoom');
    const main = document.getElementById('main-content');
    if (main.querySelector('h1') && !main.querySelector('.mobile-account')) {
      const account = actionButton('Cuenta', 'account', () => document.getElementById('settings-btn').click(), 'icon-btn mobile-account');
      main.prepend(account);
    }
  };
  new MutationObserver(updateMobile).observe(document.getElementById('main-content'), { childList:true });
  new MutationObserver(updateMobile).observe(document.getElementById('player-title'), { childList:true });
  window.addEventListener('resize', updateMobile);
  updateMobile();
  let touchStart = null;
  const cover = document.querySelector('.now-playing-cover-wrap');
  const slot = document.createElement('div'); slot.style.display = 'contents'; cover.before(slot); slot.append(cover);
  let coverRest = null, coverAnimation = null;
  const restoreCover = (animate = true) => {
    if (animate) localStorage.setItem('musicbox_zoom_mp3', '0');
    coverAnimation?.cancel();
    const finish=()=>{cover.removeAttribute('style');slot.style.cssText='display:contents';document.body.classList.remove('cover-zoom');coverRest=null;};
    if(!coverRest||!animate||matchMedia('(prefers-reduced-motion:reduce)').matches){finish();return;}
    coverAnimation=cover.animate([{left:'0px',top:'0px',width:innerWidth+'px',height:cover.style.height,borderRadius:'0px'},
      {left:coverRest.left+'px',top:coverRest.top+'px',width:coverRest.width+'px',height:coverRest.height+'px',borderRadius:'20px'}],{duration:320,easing:'cubic-bezier(.2,.8,.2,1)'});
    coverAnimation.finished.then(finish).catch(()=>{});
  };
  const toggleCover = (remember = true) => {
    if (remember && !coverRest) localStorage.setItem('musicbox_zoom_mp3', '1');
    if (coverRest) { restoreCover(); return; }
    coverRest = cover.getBoundingClientRect();
    const top = document.querySelector('.now-playing-info').getBoundingClientRect().top;
    slot.style.cssText = `display:block;flex:none;align-self:center;width:${coverRest.width}px;height:${coverRest.height}px`;
    const before = {left:coverRest.left+'px',top:coverRest.top+'px',width:coverRest.width+'px',height:coverRest.height+'px',borderRadius:getComputedStyle(cover).borderRadius};
    Object.assign(cover.style,{position:'fixed',left:'0px',top:'0px',width:'100vw',height:Math.max(coverRest.height,top)+'px',margin:'0',maxWidth:'none',borderRadius:'0',zIndex:'1'});
    document.body.classList.add('cover-zoom');
    if(!matchMedia('(prefers-reduced-motion:reduce)').matches)coverAnimation=cover.animate([before,{left:'0px',top:'0px',width:innerWidth+'px',height:Math.max(coverRest.height,top)+'px',borderRadius:'0px'}],{duration:320,easing:'cubic-bezier(.2,.8,.2,1)'});
  };
  // El zoom elegido se recuerda: al abrir de nuevo el reproductor grande vuelve como se dejó.
  new MutationObserver(()=>{
    const expanded=document.body.classList.contains('player-expanded');
    if(expanded&&localStorage.getItem('musicbox_lyrics_open')!=='0'&&modeLyricsReady&&!state.lyricsOpen){
      state.lyricsOpen=true;
      document.getElementById('lyrics-drawer')?.classList.add('open');
      document.getElementById('lyrics-toggle-btn')?.classList.add('active');
      document.body.classList.add('lyrics-open');
      paintModes();
    }
    if(coverRest&&!expanded)restoreCover(false);
    else if(expanded&&!coverRest&&localStorage.getItem('musicbox_zoom_mp3')==='1')requestAnimationFrame(()=>requestAnimationFrame(()=>{if(document.body.classList.contains('player-expanded')&&!coverRest)toggleCover(false);}));
  }).observe(document.body,{attributes:true,attributeFilter:['class']});
  document.addEventListener('keydown',event=>{if(event.key==='Escape'&&coverRest){event.stopImmediatePropagation();restoreCover();}},true);
  document.querySelector('.now-playing-cover-wrap').addEventListener('pointerdown', e => { touchStart = {x:e.clientX,y:e.clientY}; });
  document.querySelector('.now-playing-cover-wrap').addEventListener('pointerup', e => {
    if (!touchStart) return;
    const dx=e.clientX-touchStart.x,dy=e.clientY-touchStart.y; touchStart=null;
    if (document.body.classList.contains('player-expanded') && Math.abs(dx)<10 && Math.abs(dy)<10) toggleCover();
    else if (document.body.classList.contains('player-expanded') && dy>80 && Math.abs(dx)<60) document.body.classList.remove('player-expanded');
    else if (Math.abs(dx)>80 && Math.abs(dy)<50) dx<0 ? playNext() : playPrevious();
  });
  const settingsBody = document.querySelector('#settings-modal .modal-scroll-body');
  const links = document.createElement('div'); links.className = 'collection-toolbar mobile-account-links';
  links.append(actionButton('Descargas', 'download', () => { document.getElementById('settings-modal').style.display = 'none'; loadDownloadsView(); }, 'pill-btn'),
    actionButton('Historial', 'history', () => { document.getElementById('settings-modal').style.display = 'none'; loadHistoryView(); }, 'pill-btn'));
  links.append(actionButton('Dispositivos', 'info', showDevices, 'pill-btn'));
  settingsBody.prepend(links);
  setupAccessibility();
  // Keep the iframe visible only after a song has actually been selected.
  document.getElementById('video-dock').classList.add('dock-hidden');
  state.videoDockOpen = false;
});

async function showDevices() {
  const body=document.createElement('div'); body.className='modal-body';
  const dialog=openDialog('Dispositivos',body);
  if (!state.user?.authenticated || !fbDb) { body.textContent='Inicia sesión con Google para ver los dispositivos de tu cuenta.'; return; }
  const uid=state.user.uid;
  try {
    const ref=fbDb.collection('users').doc(uid).collection('state').doc('musicbox');
    const snap=await ref.get(); const remote=snap.exists?JSON.parse(snap.data().payload || '{}'):{};
    for (const [id,device] of Object.entries(remote.devices || {})) {
      const row=document.createElement('div'); row.className='settings-row';
      const name=document.createElement('span'); name.textContent=`${device.name || device.platform || 'Dispositivo'}${id === webDeviceId ? ' · Este navegador' : ''}`;
      row.append(name);
      if (id !== webDeviceId) row.append(actionButton('Desconectar', 'logout', async () => {
        if (!await confirmAction('Desconectar dispositivo',`¿Cerrar la sesión de «${device.name || device.platform || 'Dispositivo'}»?`)) return;
        try {
          if (state.user?.uid !== uid) return;
          await fbDb.collection('users').doc(uid).collection('state').doc('revocations').set({['d_'+id]:Date.now()},{merge:true});
          await fbDb.runTransaction(async transaction=>{
            const current=await transaction.get(ref); const payload=JSON.parse(current.data().payload || '{}');
            delete payload.devices?.[id]; transaction.set(ref,{payload:JSON.stringify(payload)},{merge:true});
          });
          row.remove(); showToast('Dispositivo desconectado');
        } catch { showToast('No se pudo desconectar el dispositivo. Reintenta.'); }
      }, 'pill-btn-danger'));
      body.append(row);
    }
    if (!body.children.length) body.textContent='No hay dispositivos registrados.';
  } catch { body.textContent='No se pudo consultar la cuenta. Revisa tu conexión.'; }
}

async function importLocalFiles(folder = false) {
  const input = document.createElement('input'); input.type = 'file'; input.accept = 'audio/*,video/mp4'; input.multiple = true;
  if (folder === true) input.webkitdirectory = true;
  input.onchange = async () => {
    let count = 0;
    for (const file of input.files) {
      if (!file.type.startsWith('audio/') && file.type !== 'video/mp4') continue;
      const id = 'local:' + (file.webkitRelativePath || file.name) + ':' + file.size + ':' + file.lastModified;
      const track = { id, title: file.name.replace(/\.[^.]+$/, ''), artist:'Archivo local', duration:0,
        thumbnail:'logo_complete.png', folder: file.webkitRelativePath?.split('/').slice(0,-1).join('/') || 'Archivos importados' };
      try { await storage.saveOfflineTrack(track, file, null); count++; }
      catch { showToast('No hay espacio suficiente para importar más archivos.'); break; }
    }
    showToast(`${count} canciones importadas`); loadLibraryView();
  };
  input.click();
}

async function importPlaylistDialog() {
  const form = document.createElement('form'); form.className = 'modal-body playlist-form';
  form.innerHTML = `<p>Pega el enlace de una playlist pública de YouTube, Spotify, Deezer, Apple Music o SoundCloud.</p>
    <label>Enlace<input name="url" type="url" class="modal-input" required placeholder="https://…"></label>
    <div id="import-preview"></div><p id="import-status" role="status"></p>
    <div class="modal-actions"><button class="pill-btn-accent" type="submit">Revisar playlist</button></div>`;
  const dialog = openDialog('Importar playlist', form);
  const status = form.querySelector('#import-status');
  const submit = form.querySelector('[type=submit]');
  const controller = new AbortController();
  dialog.addEventListener('close', () => controller.abort());
  let preview = null;
  form.onsubmit = async e => {
    e.preventDefault(); submit.disabled = true;
    try {
      if (!preview) {
        status.textContent = 'Leyendo playlist…';
        const response = await fetch(apiUrl(`api/import/preview?url=${encodeURIComponent(form.elements.url.value)}`), {signal:controller.signal});
        const data = await response.json();
        if (!response.ok) throw new Error(data.detail || 'No se pudo leer la playlist.');
        preview = data;
        form.elements.url.disabled = true;
        const panel = form.querySelector('#import-preview');
        panel.innerHTML = `<label>Nombre<input name="name" class="modal-input" required maxlength="80" value="${escapeHTML(preview.name)}"></label>
          <div class="import-range"><label>Desde<input type="number" name="first" min="1" max="${preview.tracks.length}" value="1" class="modal-input"></label>
          <label>Hasta<input type="number" name="last" min="1" max="${preview.tracks.length}" value="${preview.tracks.length}" class="modal-input"></label></div>`;
        const list = document.createElement('div'); list.className = 'import-preview-list';
        preview.tracks.forEach((track,index) => { const row = document.createElement('p'); row.textContent = `${index + 1}. ${track.title} · ${track.artist}`; list.append(row); });
        panel.append(list);
        status.textContent = `${preview.tracks.length} canciones. Revisa el nombre y el rango antes de importar.`;
        submit.textContent = 'Importar canciones';
      } else {
        const name = form.elements.name.value.trim();
        if (!name || name === 'Favoritos' || (await storage.getAllPlaylists()).some(pl => pl.name.toLocaleLowerCase() === name.toLocaleLowerCase())) throw new Error('Elige un nombre de playlist que no exista.');
        const first = Number(form.elements.first.value), last = Number(form.elements.last.value);
        if (first > last) throw new Error('El rango de canciones no es válido.');
        const selected = preview.tracks.slice(first - 1,last), tracks = [], seen = new Set(); let skipped = 0;
        for (let i = 0; i < selected.length; i++) {
          if (controller.signal.aborted || !dialog.open) return;
          status.textContent = `Buscando coincidencias: ${i + 1} / ${selected.length}`;
          const source = selected[i]; let track = source.direct;
          if (!track) {
            const response = await fetch(apiUrl('api/import/match'), {method:'POST', headers:{'Content-Type':'application/json'}, body:JSON.stringify({title:source.title,artist:source.artist,seconds:source.seconds}),signal:controller.signal});
            if (!response.ok) throw new Error('No se pudo buscar una coincidencia. Reintenta la importación.');
            track = await response.json();
          }
          if (track?.id && !seen.has(track.id)) { seen.add(track.id); tracks.push(track); } else skipped++;
        }
        if (!tracks.length) throw new Error('No se encontraron coincidencias seguras. No se creó una playlist vacía.');
        const playlist = {id:'pl_' + Date.now(),name,tracks,createdAt:Date.now()};
        await storage.savePlaylist(playlist);
        dialog.close(); loadPlaylistView(playlist); showToast(`${tracks.length} canciones importadas${skipped ? ` · ${skipped} sin coincidencia segura` : ''}`);
      }
    } catch (error) { if (error.name !== 'AbortError') status.textContent = error.message; }
    finally { submit.disabled = false; }
  };
}

// The dictionary is shared with desktop; dynamic DOM is translated when it renders.
function applyLanguage() {
  const language = state.language === 'auto' ? navigator.language.slice(0, 2) : state.language;
  document.documentElement.lang = language === 'en' ? 'en' : 'es';
  document.querySelectorAll('[data-lang]').forEach(button=>button.classList.toggle('active',button.dataset.lang===state.language));
  if (window.musicboxTranslate) window.musicboxTranslate(document.body, language);
}

let reconcilingDownloads=false;
async function runAutoDownloads() {
  if(reconcilingDownloads || !navigator.onLine)return;
  reconcilingDownloads=true;
  try {
    for(const playlist of await storage.getAllPlaylists()) {
      if(!playlist.autoDownload)continue;
      for(const track of playlist.tracks || []) {
        if(downloadJobs.has(track.id) || await storage.getOfflineTrack(track.id))continue;
        if(!await downloadTrackForOffline(track))return;
      }
    }
  } finally {reconcilingDownloads=false;}
}
window.addEventListener('online',()=>{if(!verificationMode)runAutoDownloads();});
