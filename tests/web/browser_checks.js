/* Run through preview_evaluate on /?verification=1 only. Synthetic local data. */
(async () => {
  if (!verificationMode || storage.db.name !== 'MusicBoxVerification' || fbAuth) throw new Error('Verification isolation required');
  const results = [];
  const assert = (condition, name) => { if (!condition) throw new Error(name); results.push(name); };
  const waitFor = async predicate => {
    const deadline = Date.now() + 8000;
    while (!predicate()) { if (Date.now() > deadline) throw new Error('Timed out: ' + predicate); await new Promise(resolve => setTimeout(resolve, 40)); }
  };
  const originalFetch = window.fetch;
  state.language = 'es'; applyLanguage();
  const songs = [
    {id:'testTrack01', title:'Canción de prueba', artist:'MusicBox', duration:30, thumbnail:'logo_cube.png', kind:'song'},
    {id:'testTrack02', title:'Segunda canción', artist:'MusicBox', duration:30, thumbnail:'logo_cube.png', kind:'song'},
    {id:'testTrack03', title:'Tercera canción', artist:'MusicBox', duration:30, thumbnail:'logo_cube.png', kind:'song'}
  ];
  let downloadFailure = false;
  let holdDownload = false;
  const pcm = new ArrayBuffer(44 + 8000 * 2 * 30);
  const data = new DataView(pcm);
  const text = (offset, value) => [...value].forEach((c,i) => data.setUint8(offset+i,c.charCodeAt(0)));
  text(0,'RIFF'); data.setUint32(4,pcm.byteLength-8,true); text(8,'WAVE'); text(12,'fmt '); data.setUint32(16,16,true);
  data.setUint16(20,1,true); data.setUint16(22,1,true); data.setUint32(24,8000,true); data.setUint32(28,16000,true);
  data.setUint16(32,2,true); data.setUint16(34,16,true); text(36,'data'); data.setUint32(40,pcm.byteLength-44,true);
  const wav = new Blob([pcm], {type:'audio/wav'});
  window.fetch = async (input, options) => {
    const url = new URL(typeof input === 'string' ? input : input.url, location.href);
    const json = (body,status=200) => new Response(JSON.stringify(body), {status,headers:{'Content-Type':'application/json'}});
    if (url.pathname.startsWith('/api/recommendations')) return json({artists:[{name:'MusicBox',browse_id:'testArtist',photo:'logo_cube.png'}],songs});
    if (url.pathname.startsWith('/api/search')) {
      const q = url.searchParams.get('q'); await new Promise(r => setTimeout(r,q === 'slow' ? 180 : 10));
      return json([{...songs[q === 'slow' ? 0 : 1], title:q}]);
    }
    if (url.pathname.startsWith('/api/related/')) return json([]);
    if (url.pathname.startsWith('/api/lyrics/')) {
      const id = url.pathname.split('/').pop(); await new Promise(r => setTimeout(r,id === songs[0].id ? 120 : 10));
      return json({lines:[{text:id,timeMs:0},{text:'Segunda línea',timeMs:5000}],synced:true});
    }
    if (url.pathname.startsWith('/api/stream/') || url.pathname.startsWith('/api/download/')) {
      if (holdDownload) return new Response(new ReadableStream({start(controller){options.signal.addEventListener('abort',()=>controller.error(new DOMException('Cancelled','AbortError')),{once:true});}}),{headers:{'Content-Type':'audio/wav'}});
      if (downloadFailure) return json({detail:'Proveedor de prueba temporalmente no disponible'},503);
      return new Response(wav,{headers:{'Content-Type':'audio/wav','Content-Length':String(wav.size)}});
    }
    if (url.pathname.startsWith('/api/cookies/status')) return json({active:false});
    if (url.pathname.startsWith('/api/import/preview')) return json({name:'Importada',platform:'youtube',tracks:songs.map(t=>({title:t.title,artist:t.artist,seconds:t.duration,direct:t}))});
    return originalFetch(input,options);
  };
  try {
    await storage.clearAllOfflineTracks(); await storage.clearHistory();
    await new Promise((resolve,reject) => { const tx = storage.db.transaction(['favorites','playlists'],'readwrite'); for(const name of ['favorites','playlists']) tx.objectStore(name).clear(); tx.oncomplete=resolve; tx.onerror=reject; });
    assert(!fbAuth && !fbDb, 'Firebase desactivado en verificación');
    await loadHomeView(); await waitFor(() => document.querySelectorAll('.for-you-card').length === 3);
    assert(document.querySelector('.for-you-cover-wrap').getBoundingClientRect().width === document.querySelector('.for-you-cover-wrap').getBoundingClientRect().height, 'Carátula cuadrada');
    const first = performSearch('slow'); const second = performSearch('fast'); await Promise.all([first,second]);
    assert(document.querySelector('.row-title').textContent === 'fast', 'Búsqueda tardía descartada');
    state.searchQuery = '';
    await storage.saveOfflineTrack(songs[0],wav,null); await storage.saveOfflineTrack(songs[1],wav,null);
    state.isMuted = true;
    state.queue = [...songs]; state.queueIndex = 0;
    await playTrack(songs[0],true); await waitFor(() => state.isPlaying && audio.readyState >= 2);
    assert(state.isOfflinePlayback && audio.src.startsWith('blob:'), 'Audio local reproducible');
    seekTo(5); assert(audio.currentTime >= 4.9, 'Salto en audio local');
    togglePlayPause(); await waitFor(() => !state.isPlaying); assert(audio.paused,'Pausa real de audio');
    togglePlayPause(); await waitFor(() => state.isPlaying);
    await waitFor(() => webStatsRows().some(row => row.listenedMs > 0));
    assert(webStatsRows().some(row => row.plays >= 1 && row.listenedMs > 0),'Clics y tiempo de escucha real registrados');
    moveQueue(2,0); assert(state.queue[state.queueIndex].id === songs[0].id,'Ordenar cola conserva pista actual');
    await playTrack(songs[1]); await waitFor(() => state.isPlaying);
    await waitFor(() => state.lyrics[0]?.text === songs[1].id);
    await new Promise(r => setTimeout(r,160)); assert(state.lyrics[0].text === songs[1].id,'Letra tardía descartada');
    state.lyricsOpen = true; updateActiveLyric(0); updateActiveLyric(0);
    assert(document.querySelector('.lyric-line.active')?.textContent === songs[1].id,'Letra activa persiste entre actualizaciones');
    document.getElementById('close-lyrics-btn').click();
    const favorite = await storage.toggleFavorite(songs[1]); assert(favorite && await storage.isFavorite(songs[1].id),'Favorito persistido');
    assert(!(await storage.toggleFavorite(songs[1])),'Quitar favorito');
    await loadLibraryView('playlists'); openNewPlaylistModal();
    document.getElementById('new-playlist-name').value = 'Playlist de prueba'; document.getElementById('confirm-new-playlist-btn').click();
    await waitFor(() => document.getElementById('new-playlist-modal').style.display === 'none');
    let playlist = (await storage.getAllPlaylists())[0]; assert(playlist?.name === 'Playlist de prueba','Crear playlist');
    await openAddToPlaylistModal(songs[0]); document.querySelector('#playlists-picker-list .context-item').click();
    await waitFor(() => document.getElementById('add-to-playlist-modal').style.display === 'none');
    playlist = (await storage.getAllPlaylists())[0]; assert(playlist.tracks.length === 1,'Agregar canción a playlist');
    loadPlaylistView(playlist); assert(document.querySelector('#playlist-tracks-list .row-title').textContent === songs[0].title,'Abrir playlist');
    await playlistSettings(playlist);
    const form = document.querySelector('dialog form'); form.elements.name.value='Renombrada'; form.elements.description.value='Descripción de prueba'; form.elements.pinned.checked=true; form.requestSubmit();
    await waitFor(() => !document.querySelector('dialog'));
    playlist = (await storage.getAllPlaylists())[0]; assert(playlist.name === 'Renombrada' && playlist.pinned,'Editar y fijar playlist');
    await importPlaylistDialog();
    const importForm = document.querySelector('dialog form'); importForm.elements.url.value='https://www.youtube.com/playlist?list=PLabcdefghijklm'; importForm.requestSubmit();
    await waitFor(() => importForm.elements.name);
    assert(importForm.querySelector('.import-preview-list').children.length === 3,'Revisar playlist antes de importar');
    importForm.elements.first.value='2'; importForm.elements.last.value='3'; importForm.requestSubmit();
    await waitFor(() => !document.querySelector('dialog'));
    assert((await storage.getAllPlaylists()).find(pl=>pl.name==='Importada')?.tracks.length === 2,'Importar rango de playlist');
    await loadHomeView(); await waitFor(() => document.querySelector('.pinned-playlists button'));
    assert(document.querySelector('.pinned-playlists').textContent.includes('Renombrada'),'Playlist fijada en Inicio');
    const malicious = createTrackRow({...songs[2], title:'<img src=x onerror="window.injected=true">'});
    assert(!malicious.querySelector('.row-title img') && !window.injected,'Título malicioso tratado como texto');
    setDarkMode(false,false); applyPrimaryColor('orange',false);
    assert(document.documentElement.dataset.theme==='light' && document.documentElement.dataset.accent==='orange','Tema claro y color naranjo');
    setDarkMode(true,false); applyPrimaryColor('blue',false);
    state.language='en'; applyLanguage(); assert(document.documentElement.lang === 'en' && document.getElementById('nav-home').textContent.trim()==='Home','Idioma inglés traduce la navegación'); state.language='es'; applyLanguage();
    await loadDownloadsView(); downloadFailure=true; await downloadTrackForOffline(songs[2]);
    assert(!await storage.getOfflineTrack(songs[2].id) && !downloadJobs.size,'Descarga fallida no guarda audio inválido');
    downloadFailure=false; await downloadTrackForOffline(songs[2]);
    assert((await storage.getOfflineTrack(songs[2].id)).audioBlob.size === wav.size,'Descarga completa guardada');
    holdDownload=true;const cancelledTrack={...songs[2],id:'testTrack04'};const downloading=downloadTrackForOffline(cancelledTrack);
    await waitFor(()=>downloadJobs.has(cancelledTrack.id));downloadJobs.get(cancelledTrack.id).controller.abort();await downloading;holdDownload=false;
    assert(!await storage.getOfflineTrack(cancelledTrack.id) && !downloadJobs.size,'Cancelar descarga libera el trabajo y no guarda archivos parciales');
    await loadDownloadsView();
    document.getElementById('download-format').value='mp4';document.getElementById('download-format').dispatchEvent(new Event('change'));
    assert(state.downloadFormat==='mp4' && state.downloadQuality===720,'Formato MP4 selecciona calidad de video válida');
    document.getElementById('download-format').value='mp3';document.getElementById('download-format').dispatchEvent(new Event('change'));
    document.getElementById('download-quality').value='320';document.getElementById('download-quality').dispatchEvent(new Event('change'));
    assert(state.downloadFormat==='mp3' && state.downloadQuality===320,'Formato MP3 guarda la calidad elegida');
    const autoTrack={...songs[2],id:'testTrack05',title:'Descarga automática de prueba'};
    await storage.savePlaylist({id:'auto-test',name:'Automática',autoDownload:true,tracks:[autoTrack]});await runAutoDownloads();
    assert(!!await storage.getOfflineTrack(autoTrack.id),'Playlist con descarga automática guarda canciones faltantes');
    await loadLibraryView('songs'); assert(document.getElementById('library-sort'),'Orden de biblioteca disponible');
    document.getElementById('library-search-input').value='Segunda'; document.getElementById('library-search-input').dispatchEvent(new Event('input'));
    assert(document.querySelectorAll('#library-content-list .row-item').length === 1,'Filtro de biblioteca');
    await loadHistoryView('plays'); assert(document.querySelectorAll('#history-content-list .row-item').length === 2,'Historial solo registra reproducciones reales');
    await loadHistoryView(); assert(document.querySelector('#download-history-list').textContent.includes('Completada'),'Historial de descargas disponible');
    if (matchMedia('(max-width:680px)').matches) {
      const tabs = [...document.querySelectorAll('.nav-item')].filter(e => e.getClientRects().length);
      assert(tabs.length === 2 && tabs[0].textContent.trim() === 'Inicio' && tabs[1].textContent.trim() === 'Música','Navegación móvil Android con dos pestañas');
      assert(document.querySelector('.mobile-account').getClientRects().length,'Cuenta accesible en móvil');
      document.querySelector('.player-left').click(); assert(document.body.classList.contains('player-expanded'),'Minirreproductor abre pantalla completa');
      const footer=document.getElementById('bottom-player').getBoundingClientRect(); assert(footer.height >= innerHeight - 1 && footer.top===0,'Reproductor móvil ocupa pantalla');
      assert(document.getElementById('shuffle-btn').getClientRects().length && document.getElementById('lyrics-toggle-btn').getClientRects().length,'Aleatorio y letras accesibles en reproductor móvil');
      document.querySelector('.mobile-player-top button').click(); assert(!document.body.classList.contains('player-expanded'),'Cerrar reproductor móvil');
    }
    assert(document.documentElement.scrollWidth <= innerWidth,'Sin desbordamiento horizontal');
    audio.pause();
    return {passed:results.length,results,viewport:{width:innerWidth,height:innerHeight},database:storage.db.name};
  } finally { window.fetch = originalFetch; audio.pause(); }
})()
