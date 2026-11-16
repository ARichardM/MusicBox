/*
 * Historial y Descargas con el mismo diseño y flujo que el escritorio (HistoryPanel.qml y DownloadsPage.qml).
 * Lo que el navegador no puede hacer (elegir una carpeta de destino, abrir la carpeta de un archivo) se sustituye por su
 * equivalente: las descargas se guardan en este navegador y "Carpeta" lleva a la lista de archivos guardados.
 */
(function () {
  const PAGE_SIZE = 100;
  const historyState = { query: '', filter: 'all', page: 0, expanded: false, selected: null };
  const downloadsState = { links: '', mode: 'single', range: '', tab: 'files', log: [] };

  Object.assign(MUSICBOX_EN, {
    'Reproduciendo desde': 'Playing from', 'Cambiar de playlist': 'Switch playlist', 'Carpeta': 'Folder', 'Búsqueda de YouTube': 'YouTube search', 'Historial': 'History',
    'de': 'of', 'Agregada a la cola': 'Added to queue',
    'Buscar en el historial': 'Search history', 'Buscar': 'Search', 'Todo': 'All', 'Descargas': 'Downloads', 'Reproducciones': 'Plays',
    'Errores': 'Errors', 'Ampliar': 'Expand', 'Reducir': 'Collapse', 'FECHA': 'DATE', 'CANCIÓN / VIDEO': 'SONG / VIDEO', 'TIPO': 'TYPE',
    'ESTADO': 'STATE', 'Guardada': 'Saved', 'Error': 'Error', 'Cancelada': 'Cancelled', 'Reproducida': 'Played', 'Abrir': 'Open',
    'Carpeta': 'Folder', 'Cola': 'Queue', 'Limpiar historial': 'Clear history', 'Sin registros': 'No records',
    'Todavía no hay registros que mostrar.': 'There are no records to show yet.', 'registros': 'records',
    'Audio y video, a tu manera.': 'Audio and video, your way.', 'Importar canciones…': 'Import songs…', 'Importar carpeta…': 'Import folder…',
    'Buscar videos por título o artista': 'Search videos by title or artist', 'Buscar con portadas': 'Search with covers',
    'TUS ENLACES': 'YOUR LINKS', 'TU DESCARGA': 'YOUR DOWNLOAD', 'Pegar enlaces': 'Paste links', 'Limpiar': 'Clear',
    'Pega aquí tus enlaces de YouTube…': 'Paste your YouTube links here…', 'Aún no hay enlaces': 'No links yet', 'enlace': 'link', 'enlaces': 'links',
    'Un enlace por línea': 'One link per line', 'Archivos': 'Files', 'Historial': 'History', 'Registro': 'Log', 'Informe de canciones': 'Song report',
    'CONFIGURA TU DESCARGA': 'SET UP YOUR DOWNLOAD', '¿Qué quieres guardar?': 'What do you want to save?', 'Solo audio': 'Audio only',
    'Video + audio': 'Video + audio', 'Calidad': 'Quality', 'Incluye portada y metadatos disponibles.': 'Includes cover art and available metadata.',
    'Cantidad': 'Amount', 'Un video por enlace': 'One video per link', 'Lista completa o un rango': 'Full playlist or a range',
    'Rango, p. ej. 1-5': 'Range, e.g. 1-5', 'Destino': 'Destination', 'Este navegador (sin conexión)': 'This browser (offline)',
    'Descargar': 'Download', 'Listo para descargar': 'Ready to download', 'Pega uno o más enlaces y pulsa Descargar.': 'Paste one or more links and press Download.',
    'EN ESPERA': 'WAITING', 'DESCARGANDO': 'DOWNLOADING', 'COMPLETADA': 'COMPLETED', '¡Descargas terminadas!': 'Downloads finished!',
    'Abre un archivo con un clic o consulta la lista de archivos guardados.': 'Open a file with one click or check the saved files list.',
    'Archivos listos': 'Files ready', 'errores': 'errors', 'Todavía no hay archivos guardados en este navegador.': 'There are no files saved in this browser yet.',
    'Sin actividad todavía.': 'No activity yet.', 'canciones guardadas': 'saved songs', 'con error': 'with errors', 'Espacio usado': 'Space used',
    'Mostrar más': 'Show more', 'Ordenar': 'Sort', 'canciones': 'songs', 'Más recientes': 'Newest', 'Orden original': 'Original order',
    'Importar canciones': 'Import songs', 'Importar carpeta': 'Import folder', 'Aleatorio': 'Shuffle',
    'Tu biblioteca está vacía': 'Your library is empty', 'El navegador no puede leer tus carpetas por sí solo: importa canciones o una carpeta para empezar.': 'The browser cannot read your folders by itself: import songs or a folder to get started.',
    'ACERCA DE': 'ABOUT', 'Agregar a la cola': 'Add to queue', 'Al día': 'Up to date', 'Cola de reproducción': 'Play queue',
    'Cookies de YouTube': 'YouTube cookies', 'ESTADÍSTICAS': 'STATISTICS',
    'El acceso de YouTube del servidor lo configura el administrador. La reproducción en línea sigue disponible.': "The server's YouTube access is configured by the administrator. Online playback remains available.",
    'Español': 'Spanish', 'Estás al día. Tienes la última versión de MusicBox Web con reproducción híbrida y soporte PWA.': 'You are up to date. You have the latest version of MusicBox Web with hybrid playback and PWA support.',
    'Ir a canción actual': 'Go to current song', 'Mostrar el crossfade en la barra de progreso': 'Show the crossfade on the progress bar',
    'Música sin conexión (IndexedDB)': 'Offline music (IndexedDB)', 'No hay canciones recomendadas en este momento.': 'There are no recommended songs right now.',
    'Nombre de la playlist...': 'Playlist name...', 'Ocultar vista de video': 'Hide video view', 'Posición de reproducción': 'Playback position',
    'Reproduce una canción para ver su letra': 'Play a song to see its lyrics', 'Reproducir a continuación': 'Play next',
    'Selecciona una canción para escuchar': 'Select a song to listen to', 'TUS CANCIONES MÁS ESCUCHADAS': 'YOUR MOST PLAYED SONGS',
    'Ver mis estadísticas ›': 'View my statistics ›', 'Ver página de presentación (Landing)': 'View the landing page', 'Verificando en servidor...': 'Checking on the server...',
    'playlists': 'playlists', 'carpetas': 'folders', 'Más escuchadas': 'Most played', 'Título A–Z': 'Title A–Z', 'Artista A–Z': 'Artist A–Z'
  });
  const language = () => (state.language === 'auto' ? navigator.language.slice(0, 2) : state.language);
  const T = (text) => (language() === 'en' ? (MUSICBOX_EN[text] || text) : text);

  /* ---------------------------------------------------------------- Historial ---------------------------------------------------------------- */
  function playHistory() { return storage.getRecentHistory(500).catch(() => []); }

  async function collectHistory() {
    const rows = [];
    for (const entry of downloadHistory()) {
      rows.push({ at: entry.at, track: entry.track, type: (entry.format || 'mp3').toUpperCase(), state: entry.status === 'done' ? 'Guardada' : entry.status === 'cancelled' ? 'Cancelada' : 'Error', kind: 'download', detail: `${entry.quality || ''} ${entry.message || ''}`.trim() });
    }
    for (const play of await playHistory()) rows.push({ at: play.playedAt || 0, track: play, type: 'Audio', state: 'Reproducida', kind: 'play', detail: '' });
    rows.sort((a, b) => b.at - a.at);
    return rows;
  }

  function stamp(value) {
    const d = new Date(value);
    const pad = (n) => String(n).padStart(2, '0');
    return `${pad(d.getDate())}/${pad(d.getMonth() + 1)}/${String(d.getFullYear()).slice(2)} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
  }

  loadHistoryView = async function loadHistoryTable() {
    state.activeView = 'history';
    updateActiveNavButton('history');
    const main = document.getElementById('main-content');
    main.innerHTML = '<h1 class="page-heading">Historial</h1><div class="history-card" id="history-card"></div>';
    const card = document.getElementById('history-card');
    const all = await collectHistory();
    const render = () => {
      const q = historyState.query.trim().toLowerCase();
      const rows = all.filter((row) => {
        if (historyState.filter === 'downloads' && row.kind !== 'download') return false;
        if (historyState.filter === 'plays' && row.kind !== 'play') return false;
        if (historyState.filter === 'errors' && row.state !== 'Error') return false;
        return !q || `${row.track.title} ${row.track.artist || ''}`.toLowerCase().includes(q);
      });
      const pages = Math.max(1, Math.ceil(rows.length / PAGE_SIZE));
      historyState.page = Math.min(historyState.page, pages - 1);
      const slice = rows.slice(historyState.page * PAGE_SIZE, (historyState.page + 1) * PAGE_SIZE);
      card.classList.toggle('expanded', historyState.expanded);
      card.innerHTML = `
        <div class="history-toolbar">
          <label class="history-search"><img src="icons/search.svg" alt=""><input id="history-query" type="search" placeholder="Buscar en el historial" value="${escapeHTML(historyState.query)}"></label>
          <button class="pill-btn-accent" id="history-search-btn" type="button">Buscar</button>
          <select id="history-filter" aria-label="Filtrar">
            <option value="all">Todo</option><option value="downloads">Descargas</option><option value="plays">Reproducciones</option><option value="errors">Errores</option>
          </select>
          <button class="history-expand" id="history-expand" type="button"><img src="icons/expand.svg" alt=""><span>${historyState.expanded ? 'Reducir' : 'Ampliar'}</span></button>
        </div>
        <div class="history-head"><span>FECHA</span><span>CANCIÓN / VIDEO</span><span>TIPO</span><span>ESTADO</span></div>
        <div class="history-rows" role="listbox" aria-label="Historial"></div>
        <div class="history-foot">
          <button class="history-action" data-action="open" type="button"><img src="icons/play.svg" alt=""><span>Abrir</span></button>
          <button class="history-action" data-action="folder" type="button"><img src="icons/folder.svg" alt=""><span>Carpeta</span></button>
          <button class="history-action" data-action="queue" type="button"><img src="icons/plus.svg" alt=""><span>Cola</span></button>
          <button class="history-action danger" data-action="clear" type="button"><img src="icons/trash.svg" alt=""><span>Limpiar historial</span></button>
          <span class="history-count">${rows.length ? `${historyState.page * PAGE_SIZE + 1}–${historyState.page * PAGE_SIZE + slice.length} ${T('de')} ${rows.length}` : '0'} registros</span>
          <button class="icon-btn history-page" data-page="-1" type="button" aria-label="Anterior" ${historyState.page === 0 ? 'disabled' : ''}><img src="icons/prev_page.svg" alt=""></button>
          <button class="icon-btn history-page" data-page="1" type="button" aria-label="Siguiente" ${historyState.page >= pages - 1 ? 'disabled' : ''}><img src="icons/next_page.svg" alt=""></button>
        </div>`;
      card.querySelector('#history-filter').value = historyState.filter;
      const list = card.querySelector('.history-rows');
      if (!slice.length) list.innerHTML = '<div class="history-empty">Todavía no hay registros que mostrar.</div>';
      slice.forEach((row, index) => {
        const item = document.createElement('div');
        item.className = 'history-row' + (historyState.selected === row ? ' selected' : '');
        item.setAttribute('role', 'option');
        item.setAttribute('aria-selected', String(historyState.selected === row));
        item.tabIndex = 0;
        const stateClass = row.state === 'Guardada' || row.state === 'Reproducida' ? 'ok' : 'bad';
        item.innerHTML = `<span class="history-date">${stamp(row.at)}</span>
          <span class="history-song"><strong>${escapeHTML(row.track.title || '')}</strong>${row.track.artist ? ` · ${escapeHTML(row.track.artist)}` : ''}${historyState.expanded && row.detail ? `<small>${escapeHTML(row.detail)}</small>` : ''}</span>
          <span class="history-type">${escapeHTML(row.type)}</span><span class="history-state ${stateClass}">${row.state}</span>`;
        const select = () => { historyState.selected = row; render(); };
        item.addEventListener('click', select);
        item.addEventListener('dblclick', () => playTrack(row.track));
        item.addEventListener('keydown', (event) => { if (event.key === 'Enter') playTrack(row.track); else if (event.key === ' ') { event.preventDefault(); select(); } });
        list.append(item);
      });
      const apply = () => { historyState.query = card.querySelector('#history-query').value; historyState.page = 0; render(); };
      card.querySelector('#history-search-btn').onclick = apply;
      card.querySelector('#history-query').onkeydown = (event) => { if (event.key === 'Enter') apply(); };
      card.querySelector('#history-filter').onchange = (event) => { historyState.filter = event.target.value; historyState.page = 0; render(); };
      card.querySelector('#history-expand').onclick = () => { historyState.expanded = !historyState.expanded; render(); };
      card.querySelectorAll('.history-page').forEach((button) => { button.onclick = () => { historyState.page += Number(button.dataset.page); render(); }; });
      card.querySelectorAll('.history-action').forEach((button) => {
        const needsRow = button.dataset.action !== 'clear';
        button.disabled = needsRow && !historyState.selected;
        button.onclick = async () => {
          const row = historyState.selected;
          if (button.dataset.action === 'open' && row) playTrack(row.track);
          else if (button.dataset.action === 'queue' && row) { state.queue.push(row.track); renderQueue(); showToast('Agregada a la cola'); }
          else if (button.dataset.action === 'folder' && row) loadDownloadsView();
          else if (button.dataset.action === 'clear' && await confirmAction('Limpiar historial', '¿Borrar el historial de descargas y de reproducciones? Los archivos guardados se conservan.')) {
            localStorage.removeItem('musicbox_download_history');
            await storage.clearHistory();
            historyState.selected = null; loadHistoryView();
          }
        };
      });
    };
    render();
  };

  /* ---------------------------------------------------------------- Descargas ---------------------------------------------------------------- */
  function parseLinks(text) {
    const seen = new Set(), out = [];
    for (const line of text.split(/\r?\n|\s+/)) {
      const value = line.trim();
      if (!/^https?:\/\//i.test(value) || seen.has(value)) continue;
      seen.add(value); out.push(value);
    }
    return out;
  }
  function log(message) {
    downloadsState.log.unshift(`${new Date().toLocaleTimeString()}  ${message}`);
    if (downloadsState.log.length > 80) downloadsState.log.pop();
  }

  async function resolveLink(url) {
    const response = await fetch(apiUrl(`api/search?q=${encodeURIComponent(url)}`));
    if (!response.ok) throw new Error('No se pudo leer el enlace.');
    const data = await response.json();
    return Array.isArray(data) ? data[0] : null;
  }

  async function playlistTracks(url, range) {
    const match = url.match(/[?&]list=([A-Za-z0-9_-]+)/);
    if (!match) return null;
    const response = await fetch(apiUrl(`api/playlist/${match[1]}`));
    if (!response.ok) return null;
    const data = await response.json();
    let tracks = Array.isArray(data) ? data : (data.tracks || []);
    const spec = range.match(/^(\d+)\s*-\s*(\d+)$/);
    if (spec) tracks = tracks.slice(Number(spec[1]) - 1, Number(spec[2]));
    return tracks;
  }

  async function startDownloads() {
    const links = parseLinks(downloadsState.links);
    if (!links.length) { showToast('Pega al menos un enlace de YouTube.'); return; }
    log(`Se prepararon ${links.length} enlace(s).`);
    for (const url of links) {
      try {
        let tracks = null;
        if (downloadsState.mode === 'playlist') tracks = await playlistTracks(url, downloadsState.range);
        if (!tracks) { const single = await resolveLink(url); tracks = single ? [single] : []; }
        if (!tracks.length) { log(`Sin resultados para ${url}`); continue; }
        for (const track of tracks) {
          log(`Descargando «${track.title}»`);
          const ok = await downloadTrackForOffline(track);
          log(ok ? `Guardada «${track.title}»` : `Error con «${track.title}»`);
        }
      } catch (error) { log(`Error: ${error.message}`); showToast(error.message); }
    }
    if (state.activeView === 'downloads') loadDownloadsView();
  }

  function jobSummary() {
    const jobs = [...downloadJobs.values()];
    const history = downloadHistory();
    const recent = history.filter((entry) => Date.now() - entry.at < 30 * 60 * 1000);
    return { running: jobs, done: recent.filter((e) => e.status === 'done').length, failed: recent.filter((e) => e.status !== 'done').length };
  }

  const originalRender = typeof renderDownloadJobs === 'function' ? renderDownloadJobs : null;
  renderDownloadJobs = function () {
    if (state.activeView !== 'downloads') return;
    const status = document.getElementById('dl-status');
    if (status) fillStatus(status);
  };

  function fillStatus(target) {
    const { running, done, failed } = jobSummary();
    const total = running.length;
    let badge = 'EN ESPERA', title = 'Listo para descargar', detail = 'Pega uno o más enlaces y pulsa Descargar.', percent = 0, tone = 'idle';
    if (total) {
      badge = 'DESCARGANDO'; tone = 'busy';
      const known = running.filter((job) => job.progress !== null);
      percent = known.length ? Math.round(known.reduce((sum, job) => sum + job.progress, 0) / known.length) : 0;
      title = total === 1 ? running[0].track.title : `${total} descargas en curso`;
      detail = running.map((job) => job.track.title).slice(0, 2).join(' · ');
    } else if (done || failed) {
      badge = 'COMPLETADA'; tone = 'done'; percent = 100; title = '¡Descargas terminadas!';
      detail = 'Abre un archivo con un clic o consulta la lista de archivos guardados.';
    }
    target.className = `dl-card dl-status ${tone}`;
    target.innerHTML = `<div class="dl-status-top"><span class="dl-step">02</span><span class="dl-step-title">TU DESCARGA</span><span class="dl-badge ${tone}">${badge}</span></div>
      <div class="dl-status-body"><div class="dl-status-icon ${tone}">${tone === 'done' ? '✓' : tone === 'busy' ? '↓' : '⬇'}</div>
        <div><strong>${escapeHTML(title)}</strong><p>${escapeHTML(detail)}</p></div></div>
      <div class="dl-progress"><div class="dl-progress-track"><div class="dl-progress-fill ${tone}" style="width:${percent}%"></div></div><span>${percent}%</span></div>
      <small>${T('Archivos listos')}: ${done} · ${T('errores')}: ${failed}</small>`;
  }

  loadDownloadsView = async function loadDownloadsDesktopLayout() {
    state.activeView = 'downloads';
    updateActiveNavButton('downloads');
    const main = document.getElementById('main-content');
    const tracks = await storage.getAllOfflineTracks();
    const totalBytes = tracks.reduce((sum, track) => sum + (track.sizeBytes || 0), 0);
    main.innerHTML = `
      <div class="dl-header">
        <div class="home-section-title-wrap"><h1 class="page-heading" style="margin-bottom:2px">Descargas</h1><span class="home-section-subtitle">Audio y video, a tu manera.</span></div>
        <div class="dl-header-actions">
          <button class="pill-btn" id="dl-import-songs" type="button"><img src="icons/plus.svg" alt=""><span>Importar canciones…</span></button>
          <button class="pill-btn" id="dl-import-folder" type="button"><img src="icons/folder.svg" alt=""><span>Importar carpeta…</span></button>
          <span class="dl-format-badge">MP3 / MP4</span>
        </div>
      </div>
      <div class="dl-search"><img src="icons/search.svg" alt=""><input id="dl-search-input" type="search" placeholder="Buscar videos por título o artista"><button class="pill-btn-accent" id="dl-search-btn" type="button">Buscar con portadas</button></div>
      <div class="dl-grid">
        <div class="dl-left">
          <section class="dl-card"><div class="dl-card-head"><span class="dl-step">01</span><span class="dl-step-title">TUS ENLACES</span>
            <span class="dl-card-actions"><button class="link-btn" id="dl-clear" type="button">Limpiar</button><button class="link-btn accent" id="dl-paste" type="button"><img src="icons/paste.svg" alt=""><span>Pegar enlaces</span></button></span></div>
            <textarea id="dl-links" rows="4" placeholder="Pega aquí tus enlaces de YouTube…" spellcheck="false">${escapeHTML(downloadsState.links)}</textarea>
            <div class="dl-card-foot"><span id="dl-link-count"></span><span>Un enlace por línea</span></div></section>
          <section class="dl-card dl-status" id="dl-status"></section>
          <section class="dl-card dl-results"><div class="dl-tabs" role="tablist">
            ${[['files', `${T('Archivos')} (${tracks.length})`], ['history', 'Historial'], ['log', 'Registro'], ['report', 'Informe de canciones']].map(([key, label]) => `<button role="tab" class="dl-tab${downloadsState.tab === key ? ' active' : ''}" data-tab="${key}" type="button">${label}</button>`).join('')}
          </div><div class="dl-tab-body" id="dl-tab-body"></div></section>
        </div>
        <aside class="dl-panel"><div class="dl-panel-title">CONFIGURA TU DESCARGA</div>
          <h2>¿Qué quieres guardar?</h2>
          <div class="dl-formats">
            <button class="dl-format${state.downloadFormat === 'mp3' ? ' active' : ''}" data-format="mp3" type="button"><img src="icons/music.svg" alt=""><strong>MP3</strong><small>Solo audio</small></button>
            <button class="dl-format${state.downloadFormat === 'mp4' ? ' active' : ''}" data-format="mp4" type="button"><img src="icons/video.svg" alt=""><strong>MP4</strong><small>Video + audio</small></button>
          </div>
          <label class="dl-label" for="dl-quality">Calidad</label><select id="dl-quality"></select><small class="dl-hint">Incluye portada y metadatos disponibles.</small>
          <div class="dl-label">Cantidad</div>
          <label class="dl-radio${downloadsState.mode === 'single' ? ' active' : ''}"><input type="radio" name="dl-mode" value="single" ${downloadsState.mode === 'single' ? 'checked' : ''}><span>Un video por enlace</span></label>
          <label class="dl-radio${downloadsState.mode === 'playlist' ? ' active' : ''}"><input type="radio" name="dl-mode" value="playlist" ${downloadsState.mode === 'playlist' ? 'checked' : ''}><span>Lista completa o un rango</span></label>
          <input id="dl-range" class="dl-range" type="text" placeholder="Rango, p. ej. 1-5" value="${escapeHTML(downloadsState.range)}" ${downloadsState.mode === 'playlist' ? '' : 'hidden'}>
          <div class="dl-destination"><span>Destino</span><strong>Este navegador (sin conexión)</strong></div>
          <small class="dl-hint">${tracks.length} ${T('canciones guardadas')} · ${(totalBytes / 1048576).toFixed(1)} MB</small>
          <button class="dl-start" id="dl-start" type="button"><img src="icons/download.svg" alt=""><span>Descargar</span></button>
        </aside>
      </div>`;
    const q = (selector) => main.querySelector(selector);
    const quality = q('#dl-quality');
    const fillQuality = () => {
      const values = state.downloadFormat === 'mp3' ? [128, 192, 256, 320] : [360, 480, 720, 1080];
      if (!values.includes(state.downloadQuality)) state.downloadQuality = state.downloadFormat === 'mp3' ? 192 : 720;
      quality.replaceChildren(...values.map((n) => new Option(n + (state.downloadFormat === 'mp3' ? ' kbps' : 'p'), n)));
      quality.value = state.downloadQuality;
    };
    fillQuality();
    const remember = () => { localStorage.setItem('musicbox_download_format', state.downloadFormat); localStorage.setItem('musicbox_download_quality', state.downloadQuality); };
    quality.onchange = () => { state.downloadQuality = Number(quality.value); remember(); };
    main.querySelectorAll('.dl-format').forEach((button) => button.onclick = () => {
      state.downloadFormat = button.dataset.format; remember();
      main.querySelectorAll('.dl-format').forEach((b) => b.classList.toggle('active', b === button)); fillQuality();
    });
    main.querySelectorAll('input[name=dl-mode]').forEach((radio) => radio.onchange = () => {
      downloadsState.mode = radio.value;
      main.querySelectorAll('.dl-radio').forEach((label) => label.classList.toggle('active', label.contains(radio) && radio.checked));
      q('#dl-range').hidden = downloadsState.mode !== 'playlist';
    });
    q('#dl-range').oninput = (event) => { downloadsState.range = event.target.value; };
    const links = q('#dl-links');
    const count = () => { downloadsState.links = links.value; const n = parseLinks(links.value).length; q('#dl-link-count').textContent = n ? `${n} ${T(n === 1 ? 'enlace' : 'enlaces')}` : T('Aún no hay enlaces'); };
    links.oninput = count; count();
    q('#dl-clear').onclick = () => { links.value = ''; count(); };
    q('#dl-paste').onclick = async () => { try { links.value = ((links.value ? links.value + '\n' : '') + await navigator.clipboard.readText()).trim(); count(); } catch { showToast('No se pudo leer el portapapeles.'); } };
    q('#dl-start').onclick = startDownloads;
    q('#dl-import-songs').onclick = () => importLocalFiles();
    q('#dl-import-folder').onclick = () => importLocalFiles(true);
    const search = () => { const value = q('#dl-search-input').value.trim(); if (value) { loadHomeView().then(() => performSearch(value)); } };
    q('#dl-search-btn').onclick = search;
    q('#dl-search-input').onkeydown = (event) => { if (event.key === 'Enter') search(); };
    fillStatus(q('#dl-status'));
    const body = q('#dl-tab-body');
    const showTab = () => {
      main.querySelectorAll('.dl-tab').forEach((tab) => { tab.classList.toggle('active', tab.dataset.tab === downloadsState.tab); tab.setAttribute('aria-selected', String(tab.dataset.tab === downloadsState.tab)); });
      body.replaceChildren();
      if (downloadsState.tab === 'files') {
        if (!tracks.length) body.textContent = 'Todavía no hay archivos guardados en este navegador.';
        for (const track of tracks.slice(0, 60)) {
          const row = document.createElement('button'); row.type = 'button'; row.className = 'dl-file';
          row.innerHTML = `<span class="dl-file-ok">✓</span><span class="dl-file-name">${escapeHTML(track.title)}${track.artist ? ' · ' + escapeHTML(track.artist) : ''}</span><span class="dl-file-badge">${escapeHTML((track.format || 'MP3').toString().toUpperCase())}</span>`;
          row.onclick = () => playTrack(track);
          body.append(row);
        }
      } else if (downloadsState.tab === 'history') {
        const entries = downloadHistory().slice(0, 30);
        if (!entries.length) body.textContent = 'Sin actividad todavía.';
        for (const entry of entries) {
          const row = document.createElement('div'); row.className = 'dl-file';
          row.innerHTML = `<span class="dl-file-ok ${entry.status === 'done' ? '' : 'bad'}">${entry.status === 'done' ? '✓' : '!'}</span><span class="dl-file-name">${escapeHTML(entry.track.title)}</span><span class="dl-file-badge">${(entry.format || '').toUpperCase()}</span>`;
          body.append(row);
        }
      } else if (downloadsState.tab === 'log') {
        const pre = document.createElement('pre'); pre.className = 'dl-log'; pre.textContent = downloadsState.log.join('\n') || 'Sin actividad todavía.'; body.append(pre);
      } else {
        const history = downloadHistory();
        const ok = history.filter((e) => e.status === 'done').length;
        body.innerHTML = `<div class="dl-report"><div><strong>${tracks.length}</strong><span>canciones guardadas</span></div><div><strong>${history.length - ok}</strong><span>con error</span></div><div><strong>${(totalBytes / 1048576).toFixed(1)} MB</strong><span>Espacio usado</span></div></div>`;
      }
    };
    main.querySelectorAll('.dl-tab').forEach((tab) => tab.onclick = () => { downloadsState.tab = tab.dataset.tab; showTab(); });
    showTab();
  };
})();

// El texto inicial del reproductor está en una zona que el traductor no toca (guarda nombres de artistas): se traduce aparte.
document.addEventListener('DOMContentLoaded', () => {
  const artist = document.getElementById('player-artist');
  if (!artist) return;
  const hint = 'Selecciona una canción para escuchar';
  const apply = () => {
    const english = (document.documentElement.lang || navigator.language || 'es').slice(0, 2) === 'en';
    if (english && artist.textContent === hint) artist.textContent = MUSICBOX_EN[hint];
  };
  apply();
  new MutationObserver(apply).observe(artist, { childList: true });
});
