/* Navigation and playback behavior shared with the native MusicBox experiences. */
state.autoplayEnabled = localStorage.getItem('musicbox_autoplay') !== 'false';
state.crossfadeSeconds = Math.max(0,Math.min(12,Number(localStorage.getItem('musicbox_crossfade')) || 0));
function setWebCrossfadeEnabled(enabled) {
  cancelWebFade();
  if(state.crossfadeSeconds>0)localStorage.setItem('musicbox_crossfade_last',state.crossfadeSeconds);
  state.crossfadeSeconds=enabled?Math.max(1,Math.min(12,Number(localStorage.getItem('musicbox_crossfade_last'))||5)):0;
  localStorage.setItem('musicbox_crossfade',state.crossfadeSeconds);accountPreference('crossfadeSeconds',state.crossfadeSeconds);
  if(typeof showToast==='function')showToast(state.crossfadeSeconds>0?`Crossfade activado · ${state.crossfadeSeconds} s`:'Crossfade desactivado');
  const toggle=document.getElementById('crossfade-setting'),range=document.getElementById('crossfade-duration');
  if(toggle)toggle.checked=enabled;
  if(range){range.disabled=!enabled;if(enabled)range.value=state.crossfadeSeconds;}
  const output=document.getElementById('crossfade-value');if(output)output.value=state.crossfadeSeconds+' s';
  document.getElementById('crossfade-menu-toggle')?.setAttribute('aria-checked',String(enabled));

}
let playbackSource = null;
let rememberedCollection = null;
let libraryFilter = 'songs';
let currentLibraryTracks=[];
let resumeCollection = null;
let radioRequest = null;
let radioGeneration = -1;
const collectionPositions = new Map();
let disposeTrackList=()=>{};
let disposeCollectionControls=()=>{};
function renderTrackList(list,tracks,create=createTrackRow) {
  disposeTrackList();
  const main=document.getElementById('main-content');
  const rowPitch = 72;
  let start=-1,end=-1,frame=0;
  const top=document.createElement('div'),bottom=document.createElement('div');
  top.className=bottom.className='virtual-spacer';
  const offset=()=>list.getBoundingClientRect().top-main.getBoundingClientRect().top+main.scrollTop;
  const draw=()=>{
    frame=0;
    if(!list.isConnected){disposeTrackList();return;}
    const first=Math.max(0,Math.min(Math.max(0,tracks.length-Math.ceil(main.clientHeight/rowPitch)),Math.floor((main.scrollTop-offset())/rowPitch)-6));
    const last=Math.min(tracks.length,first+Math.ceil(main.clientHeight/rowPitch)+14);
    if(first===start&&last===end)return;start=first;end=last;
    top.style.height=Math.max(0,first*rowPitch-8)+'px';top.hidden=!first;
    bottom.style.height=Math.max(0,(tracks.length-last)*rowPitch-8)+'px';bottom.hidden=last>=tracks.length;
    list.replaceChildren(top,...tracks.slice(first,last).map(track=>create(track)),bottom);
    if(!tracks.length){const empty=document.createElement('p');empty.className='empty-state';empty.textContent='No hay canciones con estos filtros.';list.replaceChildren(empty);}
  };
  const scroll=()=>{if(!frame)frame=requestAnimationFrame(draw);};
  main.addEventListener('scroll',scroll,{passive:true});window.addEventListener('resize',scroll);
  disposeTrackList=()=>{main.removeEventListener('scroll',scroll);window.removeEventListener('resize',scroll);cancelAnimationFrame(frame);};
  list.scrollToTrack=id=>{
    const index=tracks.findIndex(track=>track.id===id);
    if(index>=0){main.scrollTop=Math.max(0,offset()+index*rowPitch-main.clientHeight/2);start=-1;draw();}
  };
  draw();
}
let playbackKind='Canciones';
/* "Reproduciendo desde": the folder, playlist or list the queue came from (same labels as the app). */
function rememberPlaybackSource(){
  playbackKind=playbackSource?(playbackSource.kind==='folder'?'Carpeta':'Playlist'):state.activeView==='history'?'Historial':state.activeView==='library'?'Canciones':'Búsqueda de YouTube';
}
async function switchWebPlaylist(collection){
  if(!collection)return;
  playbackSource=collection;
  await loadPlaylistView(collection);
  if(activePlaylist === collection && currentLibraryTracks.length){
    state.queue=[...currentLibraryTracks];
    state.queueIndex=state.isShuffle?Math.floor(Math.random()*state.queue.length):0;
    await playTrack(state.queue[state.queueIndex],true);
  }else{
    rememberPlaybackSource();
    paintPlaybackSource();
  }
}
async function stepWebPlaylist(step){
  const playlists=(await libraryData()).playlists||[];
  if(!playlists.length)return;
  const currentId=playbackSource?.id||activePlaylist?.id;
  const idx=playlists.findIndex(p=>p.id===currentId);
  const next=idx>=0?playlists[(idx+step+playlists.length)%playlists.length]:playlists[step>=0?0:playlists.length-1];
  await switchWebPlaylist(next);
}
async function openPlayerSourcePicker(anchor){
  const existing=document.getElementById('player-source-popup');
  if(existing){existing.closePopup?.();return;}
  const playlists=(await libraryData()).playlists||[];
  if(!playlists.length)return;
  const popup=document.createElement('div');
  popup.id='player-source-popup';
  popup.className='player-source-popup';
  popup.setAttribute('role','listbox');
  anchor.classList.add('open');
  anchor.setAttribute('aria-expanded','true');
  const close=()=>{
    popup.classList.add('closing');
    anchor.classList.remove('open');
    anchor.setAttribute('aria-expanded','false');
    document.removeEventListener('pointerdown',outside);
    document.removeEventListener('keydown',keys);
    setTimeout(()=>popup.remove(),140);
  };
  const outside=e=>{if(!popup.contains(e.target)&&!anchor.contains(e.target))close();};
  const keys=e=>{if(e.key==='Escape'){e.stopPropagation();close();}};
  popup.closePopup=close;
  const head=document.createElement('div');
  head.className='player-source-popup-head';
  head.innerHTML='<strong>Playlists</strong>';
  head.append(actionButton('Cerrar','close',close,'icon-btn-sm'));
  const list=document.createElement('div');
  list.className='player-source-popup-list';
  for(const pl of playlists){
    const active=playbackSource?.id===pl.id;
    const item=document.createElement('button');
    item.type='button';
    item.className='player-source-option'+(active?' active':'');
    item.setAttribute('role','option');
    item.setAttribute('aria-selected',String(active));
    const thumb=pl.tracks?.[0]?.thumbnail||'icons/playlist.svg';
    item.innerHTML=`<img src="${escapeHTML(thumb)}" alt=""><span class="player-source-option-info"><strong>${escapeHTML(pl.name)}</strong><small>${pl.tracks?.length||0} canciones</small></span>${active?'<span class="player-source-check">✓</span>':''}`;
    item.onclick=async e=>{e.stopPropagation();close();await switchWebPlaylist(pl);};
    list.append(item);
  }
  popup.append(head,list);
  const rect=anchor.getBoundingClientRect();
  popup.style.left=Math.max(12,Math.min(innerWidth-300,rect.left))+'px';
  popup.style.top=Math.min(innerHeight-280,rect.bottom+8)+'px';
  document.body.append(popup);
  setTimeout(()=>{document.addEventListener('pointerdown',outside);document.addEventListener('keydown',keys);},0);
}
function paintPlaybackSource(){
  const box=document.getElementById('player-source');if(!box)return;
  const named=!!playbackSource&&!!state.currentTrack;
  box.hidden=!state.currentTrack;
  const kindEl=document.getElementById('player-source-kind');
  const nameEl=document.getElementById('player-source-name');
  const prevText=(kindEl?.textContent||'')+'|'+(nameEl?.textContent||'');
  if(kindEl)kindEl.textContent=playbackKind;
  if(nameEl)nameEl.textContent=named?playbackSource.name:'';
  box.classList.toggle('has-name',named);
  const nextText=(kindEl?.textContent||'')+'|'+(nameEl?.textContent||'');
  if(prevText&&prevText!==nextText&&!matchMedia('(prefers-reduced-motion:reduce)').matches){
    const val=box.querySelector('.player-source-value');
    val?.animate([{opacity:.25,transform:'translateY(4px)'},{opacity:1,transform:'translateY(0)'}],{duration:190,easing:'cubic-bezier(.2,.8,.2,1)'});
  }
  if(!box.dataset.bound){
    box.dataset.bound='1';
    box.tabIndex=0;
    box.setAttribute('role','button');
    box.setAttribute('aria-haspopup','listbox');
    box.setAttribute('aria-expanded','false');
    box.title='Cambiar de playlist';
    const val=box.querySelector('.player-source-value');
    if(val&&!val.querySelector('.player-source-chevron')){
      const chev=document.createElement('img');
      chev.src='icons/chevron.svg';
      chev.alt='';
      chev.className='player-source-chevron';
      val.append(chev);
    }
    box.addEventListener('click',e=>{e.stopPropagation();openPlayerSourcePicker(box);});
    box.addEventListener('keydown',e=>{if(e.key==='Enter'||e.key===' '){e.preventDefault();e.stopPropagation();openPlayerSourcePicker(box);}});
  }
}
const nativePlayTrack = playTrack;
playTrack = async function(track, fromQueue=false) {
  cancelWebFade();
  if (!fromQueue) {
    const index = state.activeView === 'library' ? currentLibraryTracks.findIndex(row=>row.id===track.id) : -1;
    playbackSource = index >= 0 ? activePlaylist : null;
    if (index >= 0) {
      state.queue = [...currentLibraryTracks];
      state.queueIndex = index;
      fromQueue = true;
    }
  } else if (activePlaylist?.tracks?.some(row=>row.id===track.id) && !playbackSource) playbackSource=activePlaylist;
  rememberPlaybackSource();
  const started=nativePlayTrack(track,fromQueue);paintPlaybackSource();return started;
};

async function relatedToCollection(tracks, excluded=state.queue) {
  const known=new Set(excluded.map(row=>row.id));
  const titles=new Set(excluded.map(row=>row.title?.toLocaleLowerCase()));
  const eligible=tracks.filter(row=>row.id && !row.id.startsWith('local:'));
  const seeds=[...new Map([0,.33,.66,1].map(part=>eligible[Math.floor((eligible.length-1)*part)]).filter(Boolean).map(row=>[row.id,row])).values()];
  const results=await Promise.allSettled(seeds.map(async seed=>{
    const response=await fetch(apiUrl(`api/related/${encodeURIComponent(seed.id)}`),{signal:AbortSignal.timeout(12000)});
    if(!response.ok)throw new Error('No se pudieron cargar canciones relacionadas.');
    return response.json();
  }));
  if(results.length&&results.every(result=>result.status==='rejected'))throw new Error('No se pudieron cargar canciones relacionadas.');
  const ranked=new Map();
  for(const result of results) if(result.status==='fulfilled' && Array.isArray(result.value)) result.value.forEach((row,index)=>{
    if(!row.id || known.has(row.id) || titles.has(row.title?.toLocaleLowerCase()) || row.kind==='video' || Number(row.duration)>600)return;
    const entry=ranked.get(row.id)||{row,score:0};entry.score+=1/(index+1);ranked.set(row.id,entry);
  });
  const artists=new Map(), chosen=[], seen=new Set(titles);
  for(const {row} of [...ranked.values()].sort((a,b)=>b.score-a.score)) {
    const title=row.title?.toLocaleLowerCase(),artist=(row.artist||row.channel||'').toLocaleLowerCase();
    if(seen.has(title)||(artist && (artists.get(artist)||0)>=3))continue;
    chosen.push(row);seen.add(title);artists.set(artist,(artists.get(artist)||0)+1);
    if(chosen.length===15)break;
  }
  return chosen;
}
checkAutoplayQueue = async function() {
  if(!state.autoplayEnabled || state.repeatMode!=='off' || !navigator.onLine || state.queueIndex<state.queue.length-2)return;
  if(radioRequest){
    if(radioGeneration===playbackGeneration)return radioRequest;
    await radioRequest;
    return checkAutoplayQueue();
  }
  const generation=playbackGeneration;
  radioGeneration=generation;
  const snapshot=[...(playbackSource?.tracks||state.queue)];
  radioRequest=(async()=>{
    try {
      const tracks=await relatedToCollection(snapshot);
      if(generation!==playbackGeneration || !state.autoplayEnabled)return;
      const known=new Set(state.queue.map(row=>row.id));state.queue.push(...tracks.filter(row=>!known.has(row.id)));
      renderQueue();
    } catch { if(generation===playbackGeneration)showToast('No se pudieron cargar canciones relacionadas. Revisa tu conexión.'); }
    finally {radioRequest=null;}
  })();
  return radioRequest;
};
handleTrackEnded = async function() {
  if(state.repeatMode==='one')return playTrack(state.currentTrack,true);
  if(state.queueIndex===state.queue.length-1 && state.repeatMode==='off')await checkAutoplayQueue();
  playNext();
};

async function runRefresh(button,work) {
  if(button.disabled)return;
  button.disabled=true;button.classList.add('refreshing');button.setAttribute('aria-busy','true');
  try {if(await work()===false)throw new Error('Refresh failed');showToast('Actualizado');}
  catch {showToast('No se pudo actualizar. Revisa tu conexión e inténtalo de nuevo.');}
  finally {button.disabled=false;button.classList.remove('refreshing');button.removeAttribute('aria-busy');}
}
function saveCollectionPosition() {
  if(activePlaylist)collectionPositions.set(activePlaylist.id,document.getElementById('main-content').scrollTop);
}
const nativeHomeView=loadHomeView;
loadHomeView=function(){
  disposeTrackList();
  if(state.activeView==='library'){saveCollectionPosition();resumeCollection=activePlaylist;}
  activePlaylist=null;
  return nativeHomeView();
};
async function locatePlayingTrack() {
  if(!state.currentTrack)return;
  if(playbackSource){
    const data=await libraryData();
    const collection=[...data.playlists,...data.folders].find(row=>row.id===playbackSource.id);
    await loadPlaylistView(collection||playbackSource);
  }
  else await loadLibraryView('songs');
  const main=document.getElementById('main-content');
  main.querySelector('#library-content-list')?.scrollToTrack?.(state.currentTrack.id);
  const row=[...main.querySelectorAll('[data-track-id]')].find(row=>row.dataset.trackId===state.currentTrack.id);
  if(row){row.scrollIntoView({block:'center',behavior:'smooth'});row.classList.add('located');setTimeout(()=>row.classList.remove('located'),1600);}
  else if(!state.queueOpen)document.getElementById('queue-toggle-btn').click();
}
async function libraryData() {
  const [playlists,favorites,offline]=await Promise.all([storage.getAllPlaylists(),storage.getAllFavorites(),storage.getAllOfflineTracks()]);
  favorites.sort((a,b)=>(a.position??Number.MAX_SAFE_INTEGER)-(b.position??Number.MAX_SAFE_INTEGER));
  playlists.sort((a,b)=>(a.order??Number.MAX_SAFE_INTEGER)-(b.order??Number.MAX_SAFE_INTEGER)||String(a.id).localeCompare(String(b.id)));
  const collections = [...playlists];
  if (favorites.length) collections.unshift({id:'favorites',name:'Favoritos',tracks:favorites,favorite:true,
    entryAddedAt:Object.fromEntries(favorites.map(row=>[MusicBoxCore.entryKey(row.id),row.addedAt||0]))});
  const account = !!state.user?.authenticated;
  const byId = new Map(), dates = new Map();
  const ordered = [...collections].sort((a,b)=>a.name<b.name?-1:a.name>b.name?1:0);
  for (const list of ordered) {
    for (const [index, row] of (list.tracks || []).entries()) {
      const key = MusicBoxCore.entryKey(row.id);
      if (account && key.startsWith('local:')) continue;
      if (!byId.has(row.id)) byId.set(row.id,row);
      const at = list.entryAddedAt?.[key] || index + 1;
      dates.set(row.id,Math.min(dates.get(row.id) || at,at));
    }
  }
  if (!account) for (const row of offline) if (!byId.has(row.id)) byId.set(row.id,row);
  const songs=[...byId.values()].map(row=>({...row,addedAt:dates.get(row.id)||row.savedAt||0}));
  return {playlists:collections,songs,
    folders:[...new Set(offline.map(row=>row.folder).filter(Boolean))].map(name=>({id:'folder:'+name,name,kind:'folder',tracks:offline.filter(row=>row.folder===name)}))};
}
function collectionCard(collection) {
  const card=document.createElement('div');card.className='collection-card';card.tabIndex=0;
  const image=document.createElement('img');image.src=collection.tracks?.[0]?.thumbnail||`icons/${collection.kind==='folder'?'folder':'playlist'}.svg`;image.alt='';image.loading='lazy';
  const info=document.createElement('div');info.className='collection-card-info';
  const title=document.createElement('strong');title.textContent=collection.name;
  const subtitle=document.createElement('small');subtitle.textContent=`${collection.tracks?.length||0} canciones`;
  info.append(title,subtitle);card.append(image,info);
  if(collection.kind!=='folder'&&!collection.favorite)card.append(actionButton('Opciones de playlist','more',event=>{event.stopPropagation();collectionMenu(event,collection);}));
  card.onclick=()=>loadPlaylistView(collection);card.onkeydown=event=>{if(event.target===card&&(event.key==='Enter'||event.key===' ')){event.preventDefault();card.click();}};
  return card;
}
function collectionMenu(event,collection) {
  actionMenu(event,[['Configurar playlist','settings',()=>playlistSettings(collection)],['Compartir playlist (CSV)','external',()=>exportPlaylist(collection)]]);
}
/** Menú de «⋯»: `items` son [etiqueta, icono, acción]. Se cierra con Escape, clic fuera o al elegir una acción. */
function actionMenu(event,items) {
  document.getElementById('collection-context-menu')?.closeMenu?.();
  const menu=document.createElement('div');menu.id='collection-context-menu';menu.className='context-menu';
  const trigger=event.currentTarget;
  const close=()=>{menu.remove();document.removeEventListener('pointerdown',outside);document.removeEventListener('keydown',keys);trigger.focus();};
  const outside=event=>{if(!menu.contains(event.target)&&!trigger.contains(event.target))close();};
  const keys=event=>{
    if(event.key==='Escape'){event.preventDefault();close();}
    if(event.key==='ArrowDown'||event.key==='ArrowUp'){
      event.preventDefault();const buttons=[...menu.querySelectorAll('button')],index=buttons.indexOf(document.activeElement);
      buttons[(index+(event.key==='ArrowDown'?1:-1)+buttons.length)%buttons.length]?.focus();
    }
  };
  menu.closeMenu=close;menu.setAttribute('role','menu');
  for(const [label,icon,action] of items){
    const button=actionButton(label,icon,()=>{close();action();},'context-item');button.setAttribute('role','menuitem');menu.append(button);
  }
  const rect=event.currentTarget.getBoundingClientRect();menu.style.display='block';menu.style.position='fixed';menu.style.left=Math.max(8,Math.min(innerWidth-248,rect.right-240))+'px';menu.style.top=(rect.bottom+6)+'px';
  document.body.append(menu);
  menu.style.top=Math.max(8,Math.min(innerHeight-menu.offsetHeight-8,rect.bottom+6))+'px';
  menu.querySelector('button').focus();document.addEventListener('pointerdown',outside);document.addEventListener('keydown',keys);
}
function libraryShell(title,context='') {
  disposeCollectionControls();
  disposeTrackList();
  const main=document.getElementById('main-content');
  main.classList.toggle('viewing-collection',!!context);
  main.innerHTML=`<header class="library-heading"><div><small class="collection-context">${escapeHTML(context)}</small><h1 class="page-heading">${escapeHTML(title)}</h1></div><div class="library-actions"></div></header>
    <div class="home-search-card"><img src="icons/search.svg" alt=""><input id="library-search-input" class="home-search-input" placeholder="Buscar en tu música"></div>
    <div class="search-filter-tabs"></div><div class="library-toolbar"></div><div class="tracks-list" id="library-content-list"></div>`;
  for(const [key,label] of [['songs','Canciones'],['playlists','Playlists'],['folders','Carpetas']])main.querySelector('.search-filter-tabs').append(actionButton(label,'',()=>loadLibraryView(key),libraryFilter===key?'filter-tab active':'filter-tab'));
  return main;
}
loadLibraryView = async function(filter='songs') {
  currentLibraryTracks=[];
  resumeCollection=null;
  saveCollectionPosition();activePlaylist=null;libraryFilter=['songs','folders','playlists'].includes(filter)?filter:'songs';
  state.activeView='library';updateActiveNavButton('library');
  const data=await libraryData();
  if(state.activeView!=='library')return;
  const main=libraryShell(libraryFilter==='playlists'?'Playlists':libraryFilter==='folders'?'Carpetas':'Mi música');
  const actions=main.querySelector('.library-actions');
  // Cabecera mínima: a la vista quedan Nueva playlist, Ir a canción actual y Aleatorio; el resto va en el menú «⋯».
  actions.append(actionButton('Nueva playlist','plus',openNewPlaylistModal),actionButton('Ir a canción actual','locate',locatePlayingTrack),
    actionButton('Aleatorio','shuffle',()=>{const pool=currentLibraryTracks||[];if(!pool.length)return;state.queue=[...pool].sort(()=>Math.random()-.5);state.queueIndex=0;playTrack(state.queue[0],true);}));
  const more=actionButton('Más opciones','more',event=>actionMenu(event,[
    ['Importar carpeta','folder',()=>importLocalFiles(true)],
    ['Importar canciones','music',importLocalFiles],
    ['Importar playlist','playlist',importPlaylistDialog],
    ['Actualizar biblioteca','refresh',()=>runRefresh(more,async()=>{if(state.user?.authenticated)await syncWithFirestore(state.user.uid,true);await loadLibraryView(libraryFilter);})]
  ]));actions.append(more);
  const sort=document.createElement('select');sort.id='library-sort';sort.className='collection-sort';sort.setAttribute('aria-label','Ordenar');
  for(const [key,label] of [['recent','Más recientes'],['default','Orden original'],['title','Título A–Z'],['artist','Artista A–Z'],['plays','Más escuchadas'],['duration','Más cortas']])sort.add(new Option(label,key));
  sort.value=(localStorage.getItem('musicbox_library_sort')||'default:1').split(':')[0];
  sort.addEventListener('change',()=>{localStorage.setItem('musicbox_library_sort',sort.value+':1');accountPreference('librarySort',sort.value+':1');});
  const total=document.createElement('small');total.className='collection-count library-total';
  const tabs=main.querySelector('.search-filter-tabs');if(tabs)tabs.append(sort,total);else main.querySelector('.library-toolbar').append(sort,total);
  const render=()=>{
    const query=main.querySelector('input').value.toLocaleLowerCase();
    let items=[...data[libraryFilter]].filter(row=>`${row.name||row.title} ${row.artist||''}`.toLocaleLowerCase().includes(query));
    const reverse = (localStorage.getItem('musicbox_library_sort')||'default:1').endsWith(':0');
    if (libraryFilter === 'songs') {
      const dates = new Map(data.songs.map(row=>[row.id,row.addedAt]));
      const plays = sort.value === 'plays' ? new Map(webStatsRows().map(row=>[row.id,row.plays])) : undefined;
      MusicBoxCore.sortTracks(items,sort.value,reverse,dates,plays);
    } else {
      if(sort.value==='title'||sort.value==='artist')items.sort((a,b)=>String(a.name||'').localeCompare(String(b.name||'')));
      if(sort.value==='recent')items.sort((a,b)=>(b.createdAt||0)-(a.createdAt||0));
      if(sort.value==='plays')items.sort((a,b)=>(b.plays||0)-(a.plays||0));
      if(reverse)items.reverse();
    }
    const list=main.querySelector('#library-content-list');list.replaceChildren();list.classList.toggle('collection-grid',libraryFilter!=='songs');
    total.textContent=items.length+' '+(libraryFilter==='songs'?'canciones':libraryFilter==='playlists'?'playlists':'carpetas');
    if(libraryFilter==='songs'){currentLibraryTracks=items;renderTrackList(list,items);if(!items.length&&!query){const empty=document.createElement('div');empty.className='library-empty';empty.innerHTML='<strong>Tu biblioteca está vacía</strong><span>El navegador no puede leer tus carpetas por sí solo: importa canciones o una carpeta para empezar.</span>';const row=document.createElement('div');row.className='library-empty-actions';row.append(actionButton('Importar canciones','plus',importLocalFiles,'pill-btn'),actionButton('Importar carpeta','folder',()=>importLocalFiles(true),'pill-btn'));empty.append(row);list.replaceChildren(empty);}}
    else items.forEach(row=>list.append(collectionCard(row)));
    if(!items.length&&libraryFilter==='playlists'&&!query){
      // Sin playlists: el vacío ofrece importar una, centrado como el de canciones.
      const empty=document.createElement('div');empty.className='library-empty';
      empty.innerHTML='<strong>No hay playlists todavía</strong><span>Crea una con el botón + o importa una playlist para empezar.</span>';
      const row=document.createElement('div');row.className='library-empty-actions';row.append(actionButton('Importar playlist','playlist',importPlaylistDialog,'pill-btn'));
      empty.append(row);list.classList.remove('collection-grid');list.replaceChildren(empty);
    } else if(!items.length&&libraryFilter!=='songs'){const empty=document.createElement('p');empty.className='empty-state';empty.textContent='No hay elementos con estos filtros.';list.append(empty);}
  };
  main.querySelector('input').oninput=render;sort.onchange=render;installAccountSort(sort,render);render();
};
function createAutoDownloadPill(collection) {
  const button = actionButton('Auto-descarga', 'download', async () => {
    if (!state.webDownloadsEnabled) {
      showToast(WEB_DOWNLOADS_DISABLED_MSG, { kind: 'info' });
      return;
    }
    button.disabled = true;
    try {
      const latest = (await storage.getAllPlaylists()).find(playlist => playlist.id === collection.id);
      if (!latest) return;
      latest.autoDownload = !latest.autoDownload;
      await storage.savePlaylist(latest);
      collection.autoDownload = latest.autoDownload;
      button.updateProgress();
      if (latest.autoDownload && !verificationMode) runAutoDownloads();
    } finally {
      button.disabled = !state.webDownloadsEnabled;
    }
  }, 'pill-btn playlist-auto-download');
  button.append(Object.assign(document.createElement('i'), { className: 'auto-download-switch' }));
  const label = button.querySelector('span');
  let phase = '';
  button.updateProgress = () => {
    const enabled = Boolean(state.webDownloadsEnabled);
    const batch = enabled && collection.autoDownload ? autoDownloadBatches.get(collection.id) : null;
    const next = batch?.phase || '';
    const progress = batch && downloadJobs.get(batch.activeId)?.progress || 0;
    button.style.setProperty('--batch-progress', batch ? (batch.done + progress / 100) * 100 / batch.total + '%' : '0%');
    button.dataset.phase = next;
    button.disabled = !enabled;
    button.setAttribute('aria-disabled', String(!enabled));
    button.setAttribute('aria-pressed', String(!!collection.autoDownload));
    label.textContent = next === 'running' ? 'Auto-descarga ' + batch.done + '/' + batch.total : 'Auto-descarga';
    button.title = !enabled ? 'Auto-descarga disponible en Android y Windows'
      : next === 'failed' ? 'Algunas canciones quedaron pendientes' : label.textContent;
    if (phase === 'running' && next === 'complete') {
      button.classList.add('download-success');
      setTimeout(() => button.classList.remove('download-success'), 950);
    }
    phase = next;
  };
  const onAvailability = () => {
    if (!button.isConnected) { document.removeEventListener('musicbox-downloads-availability', onAvailability); return; }
    button.updateProgress();
  };
  document.addEventListener('musicbox-downloads-availability', onAvailability);
  button.updateProgress();
  return button;
}

function createPlaylistSuggestions(collection, onAdded) {
  const panel = document.createElement('section');
  panel.className = 'playlist-suggestions';
  panel.setAttribute('aria-label', 'Para esta playlist');
  panel.innerHTML = '<div class="suggestions-header"><span class="suggestions-sparkle" aria-hidden="true"></span><strong>Para esta playlist</strong><div class="suggestions-pages"></div></div><div class="suggestions-content"><p role="status"></p><div class="suggestions-carousel"></div></div>';

  const header = panel.querySelector('.suggestions-header');
  const pages = panel.querySelector('.suggestions-pages');
  const content = panel.querySelector('.suggestions-content');
  const status = panel.querySelector('[role="status"]');
  const carousel = panel.querySelector('.suggestions-carousel');
  let loaded = false;
  let adding = false;
  let rows = [];
  let collapsed = localStorage.getItem('musicbox_suggestions_collapsed') === '1';

  function syncPages() {
    const cards = [...carousel.children];
    const offset = cards[0]?.offsetLeft || 0;
    const active = cards.reduce((closest, card, index) => {
      const distance = node => Math.abs(node.offsetLeft - offset - carousel.scrollLeft);
      return distance(card) < distance(cards[closest]) ? index : closest;
    }, 0);
    [...pages.children].forEach((button, index) => button.setAttribute('aria-current', String(index === active)));
  }

  function paint() {
    carousel.replaceChildren();
    pages.replaceChildren();
    for (const [index, track] of rows.entries()) {
      const card = document.createElement('div');
      card.className = 'suggestion-card';
      const play = document.createElement('button');
      play.type = 'button';
      play.className = 'suggestion-track';
      play.innerHTML = `<img src="${escapeHTML(track.thumbnail || 'icons/music.svg')}" alt="" loading="lazy"><span><strong>${escapeHTML(track.title)}</strong><small>${escapeHTML(track.artist || track.channel || '')}</small></span>`;
      play.onclick = () => playTrack(track);
      const add = actionButton('Agregar ' + track.title + ' a esta playlist', 'plus', async () => {
        if (adding) return;
        adding = true;
        add.disabled = true;
        try {
          const latest = (await storage.getAllPlaylists()).find(playlist => playlist.id === collection.id);
          if (!latest) return;
          if (!latest.tracks.some(item => item.id === track.id)) {
            latest.tracks.push(track);
            await storage.savePlaylist(latest);
            if (latest.autoDownload && !verificationMode) runAutoDownloads();
          }
          collection.tracks = latest.tracks;
          collection.entryAddedAt = latest.entryAddedAt;
          rows = rows.filter(item => item.id !== track.id);
          onAdded();
          paint();
          status.textContent = rows.length ? '' : 'No hay más sugerencias.';
        } catch {
          showToast('No se pudo agregar la canción. Inténtalo de nuevo.');
        } finally {
          adding = false;
          add.disabled = false;
        }
      }, 'icon-btn suggestion-add');
      card.append(play, add);
      carousel.append(card);
      if (rows.length > 1) {
        const page = actionButton('Ver sugerencia ' + (index + 1), '', () => {
          const first = carousel.firstElementChild;
          const behavior = matchMedia('(prefers-reduced-motion: reduce)').matches ? 'instant' : 'smooth';
          carousel.scrollTo({ left: card.offsetLeft - first.offsetLeft, behavior });
        }, 'suggestions-page');
        page.textContent = '';
        pages.append(page);
      }
    }
    syncPages();
  }

  async function load() {
    if (refresh.disabled || !panel.isConnected) return;
    refresh.disabled = true;
    panel.setAttribute('aria-busy', 'true');
    status.textContent = rows.length ? '' : 'Buscando sugerencias…';
    try {
      rows = (await relatedToCollection(collection.tracks, collection.tracks)).slice(0, 5);
      loaded = true;
      carousel.scrollLeft = 0;
      paint();
      status.textContent = rows.length ? '' : 'No se encontraron canciones relacionadas.';
    } catch {
      status.textContent = 'No se pudieron cargar las sugerencias. Intenta actualizar.';
    } finally {
      panel.removeAttribute('aria-busy');
      refresh.disabled = false;
    }
  }

  const refresh = actionButton('Actualizar sugerencias', 'refresh', load);
  const toggle = actionButton('Ocultar sugerencias', 'chevron', () => {
    collapsed = !collapsed;
    localStorage.setItem('musicbox_suggestions_collapsed', collapsed ? '1' : '0');
    syncVisibility();
    if (!collapsed && !loaded) load();
  });
  function syncVisibility() {
    content.hidden = collapsed;
    pages.hidden = collapsed;
    const label = collapsed ? 'Mostrar sugerencias' : 'Ocultar sugerencias';
    toggle.setAttribute('aria-expanded', String(!collapsed));
    toggle.setAttribute('aria-label', label);
    toggle.title = label;
  }
  header.append(refresh, toggle);
  carousel.addEventListener('scroll', syncPages, { passive: true });
  syncVisibility();
  return {
    panel,
    show(visible) {
      panel.hidden = !visible;
      if (visible && !collapsed && !loaded) load();
    }
  };
}

loadPlaylistView = async function(collection) {
  currentLibraryTracks = [];
  resumeCollection=collection;
  saveCollectionPosition();activePlaylist=collection;rememberedCollection=collection;libraryFilter=collection.kind==='folder'?'folders':'playlists';
  state.activeView='library';updateActiveNavButton('library');
  const main=libraryShell(collection.name,collection.kind==='folder'?'CARPETA':'PLAYLIST');
  const actions=main.querySelector('.library-actions');
  main.querySelector('.library-heading').prepend(actionButton('Volver','back',()=>loadLibraryView(libraryFilter)));
  const count=document.createElement('small');count.className='collection-count';count.textContent=collection.tracks.length+' canciones';main.querySelector('.library-heading h1').after(count);
  const collections=(await libraryData())[libraryFilter];
  if(activePlaylist!==collection)return;
  const collectionIndex=collections.findIndex(row=>row.id===collection.id);
  for(const [step,label,icon] of [[-1,'Playlist anterior','prev_page'],[1,'Playlist siguiente','next_page']]){
    const adjacent=collections[collectionIndex+step];const button=actionButton(label,icon,()=>loadPlaylistView(adjacent));button.classList.add('collection-page-button');button.disabled=collectionIndex<0||!adjacent;actions.append(button);
  }
  actions.append(actionButton('Ir a canción actual','locate',locatePlayingTrack));
  const refresh=actionButton('Actualizar playlist','refresh',()=>runRefresh(refresh,async()=>{
    if(state.user?.authenticated)await syncWithFirestore(state.user.uid,true);
    const updated=(await libraryData())[libraryFilter].find(row=>row.id===collection.id);
    await loadPlaylistView(updated||collection);
  }));actions.append(refresh);
  if(!collection.kind&&!collection.favorite)actions.append(actionButton('Opciones de playlist','more',event=>collectionMenu(event,collection)));
  const toolbar=main.querySelector('.library-toolbar');
  actions.append(actionButton('Aleatorio','shuffle',()=>{if(!currentLibraryTracks.length)return;playbackSource=collection;state.queue=[...currentLibraryTracks];state.queueIndex=Math.floor(Math.random()*state.queue.length);playTrack(state.queue[state.queueIndex],true);}));
  const artist=document.createElement('select');artist.className='artist-filter';artist.setAttribute('aria-label','Filtrar por artista');artist.add(new Option('Artistas',''));
  [...new Set(collection.tracks.map(row=>row.artist||row.channel).filter(Boolean))].sort().forEach(name=>artist.add(new Option(name,name)));toolbar.append(artist);
  const sort=document.createElement('select');sort.className='collection-sort';sort.setAttribute('aria-label','Ordenar');
  for(const [key,label] of [['default','Orden original'],['title','Título A–Z'],['artist','Artista A–Z'],['recent','Más recientes'],['plays','Más escuchadas'],['duration','Más cortas']])sort.add(new Option(label,key));
  sort.value=(localStorage.getItem('musicbox_library_sort')||'default:1').split(':')[0];
  sort.addEventListener('change',()=>{localStorage.setItem('musicbox_library_sort',sort.value+':1');accountPreference('librarySort',sort.value+':1');});
  if(matchMedia('(max-width:680px), (pointer:coarse) and (max-height:600px)').matches)toolbar.append(sort);else main.querySelector('.search-filter-tabs').append(sort);
  if (!collection.kind && !collection.favorite) toolbar.append(createAutoDownloadPill(collection));
  const suggestions = !collection.kind && !collection.favorite ? createPlaylistSuggestions(collection, () => {
    count.textContent = collection.tracks.length + ' canciones';
    render();
  }) : null;
  if (suggestions) toolbar.after(suggestions.panel);
  const render=()=>{
    const query=main.querySelector('input').value.toLocaleLowerCase();const list=main.querySelector('#library-content-list');list.replaceChildren();
    suggestions?.show(!query && !artist.value && collection.tracks.some(track => /^[A-Za-z0-9_-]{11}$/.test(track.id)));
    const tracks=collection.tracks.filter(track=>(!artist.value||(track.artist||track.channel)===artist.value)&&`${track.title} ${track.artist}`.toLocaleLowerCase().includes(query));
    const dates = new Map(collection.tracks.map((row,index)=>[row.id,collection.entryAddedAt?.[MusicBoxCore.entryKey(row.id)] || index + 1]));
    const plays = sort.value === 'plays' ? new Map(webStatsRows().map(row=>[row.id,row.plays])) : undefined;
    MusicBoxCore.sortTracks(tracks,sort.value,(localStorage.getItem('musicbox_library_sort')||'default:1').endsWith(':0'),dates,plays);
    currentLibraryTracks=tracks;
    renderTrackList(list,tracks,track=>{
      const isPlaylist = !collection.kind && !collection.favorite;
      const removeTrack = isPlaylist ? async () => {
        const latest = (await storage.getAllPlaylists()).find(item => item.id === collection.id);
        if (!latest) return;
        latest.tracks = latest.tracks.filter(item => item.id !== track.id);
        await storage.savePlaylist(latest);
        collection.tracks = latest.tracks;
        count.textContent = collection.tracks.length + ' canciones';
        if (activePlaylist === collection) render();
      } : null;
      if (removeTrack) removeTrack.playlistId = collection.id;
      const row=createTrackRow(track,false,removeTrack);const index=collection.tracks.indexOf(track);
      if(isPlaylist){
        row.draggable=!query;
        row.ondragstart=event=>event.dataTransfer.setData('text/plain',String(index));
        row.ondragover=event=>event.preventDefault();
        row.ondrop=async event=>{
          event.preventDefault();if(query)return;const from=Number(event.dataTransfer.getData('text/plain'));
          if(!Number.isInteger(from)||from<0||from>=collection.tracks.length)return;
          const [item]=collection.tracks.splice(from,1);collection.tracks.splice(index,0,item);
          await storage.savePlaylist(collection);render();
        };
      }
      return row;
    });
  };
  main.querySelector('.home-search-input').oninput=render;artist.onchange=render;sort.onchange=render;installAccountSort(sort,render);installCollectionControls(main);render();main.scrollTop=collectionPositions.get(collection.id)||0;
};


function installCollectionControls(main) {
  const controls=document.createElement('div'),inner=document.createElement('div');
  controls.className='collection-controls';inner.className='collection-controls-inner';controls.append(inner);
  const nodes=[...main.children].filter(node=>node.matches('.home-search-card,.search-filter-tabs,.library-toolbar,.playlist-suggestions'));
  if(!nodes.length)return;nodes[0].before(controls);inner.append(...nodes);
  let previous=main.scrollTop,travel=0,frame=0,suppressUntil=0,touchY=null;
  const editing=()=>inner.contains(document.activeElement)&&document.activeElement.matches('input,textarea');
  const setHidden=hidden=>{
    if(controls.classList.contains('controls-hidden')===hidden)return;
    suppressUntil=performance.now()+260;travel=0;controls.classList.toggle('controls-hidden',hidden);
    controls.inert=hidden;
  };
  const scroll=()=>{if(frame)return;frame=requestAnimationFrame(()=>{
    frame=0;if(!controls.isConnected){disposeCollectionControls();return;}
    const current=main.scrollTop,delta=current-previous;previous=current;
    if(performance.now()<suppressUntil||editing())return;
    if(current<16){setHidden(false);return;}
    if(delta*travel<0)travel=0;travel+=delta;
    if(travel>32)setHidden(true);else if(travel< -20)setHidden(false);
  });};
  const wheel=event=>{if(event.deltaY< -8&&!editing())setHidden(false);};
  const touchStart=event=>{touchY=event.touches[0]?.clientY??null;};
  const touchMove=event=>{const next=event.touches[0]?.clientY;if(touchY!==null&&next-touchY>14&&!editing())setHidden(false);touchY=next;};
  main.addEventListener('scroll',scroll,{passive:true});main.addEventListener('wheel',wheel,{passive:true});
  main.addEventListener('touchstart',touchStart,{passive:true});main.addEventListener('touchmove',touchMove,{passive:true});
  disposeCollectionControls=()=>{cancelAnimationFrame(frame);main.removeEventListener('scroll',scroll);main.removeEventListener('wheel',wheel);main.removeEventListener('touchstart',touchStart);main.removeEventListener('touchmove',touchMove);};
}

let preparedNext=null, preparingNext=false, outgoingAudio=null, outgoingMix=null, outgoingBlobUrl=null, webFadeStarting=false, fadeFrame=0;
function cancelWebFade() {
  clearTimeout(fadeFrame);
  if(outgoingAudio){outgoingAudio.pause();outgoingAudio.removeAttribute('src');outgoingAudio.load();outgoingAudio.remove();outgoingAudio=null;outgoingMix=null;}
  if(outgoingBlobUrl){URL.revokeObjectURL(outgoingBlobUrl);outgoingBlobUrl=null;}
  if(preparedNext){preparedNext.media.removeAttribute('src');preparedNext.media.load();if(preparedNext.url)URL.revokeObjectURL(preparedNext.url);preparedNext=null;}
  audio.volume=state.isMuted?0:state.volume;
}
async function prepareWebFade() {
  // El iframe de YouTube no se puede mezclar: con el servidor bloqueado no hay crossfade.
  if(!state.crossfadeSeconds||state.videoDockOpen||!state.isPlaying||outgoingAudio||webFadeStarting||audio.isYouTube)return;
  const endAt=window.MixEnv&&state.currentTrack?MixEnv.endSeconds(state.currentTrack.id,audio.duration):audio.duration;
  const remaining=endAt-audio.currentTime;
  if(!Number.isFinite(remaining)||endAt<state.crossfadeSeconds*2||state.repeatMode==='one')return;
  if(window.MixEnv)MixEnv.warm();
  const index=state.queueIndex+1<state.queue.length?state.queueIndex+1:state.repeatMode==='all'?0:-1;
  const next=state.queue[index];if(!next)return;
  if(!preparedNext&&!preparingNext){
    preparingNext=true;const generation=playbackGeneration;
    try{
      const stored=await storage.getOfflineTrack(next.id);
      if(generation!==playbackGeneration)return;
      if(!stored?.audioBlob&&(!navigator.onLine||next.id.startsWith('local:')))return;
      const media=document.createElement('video');media.playsInline=true;media.preload='auto';media.style.display='none';
      const url=stored?.audioBlob?URL.createObjectURL(stored.audioBlob):null;
      media.src=url||apiUrl(`api/stream/${next.id}`);media.load();
      preparedNext={media,url,track:next,index,generation,local:!!stored?.audioBlob};
    }finally{preparingNext=false;}
  }
  if(!preparedNext||preparedNext.generation!==playbackGeneration||preparedNext.track.id!==next.id||preparedNext.media.readyState<3||remaining>state.crossfadeSeconds)return;
  const ready=preparedNext;webFadeStarting=true;ready.media.volume=0;
  // La siguiente entra donde empieza a sonar, no en su silencio inicial.
  const entering=window.MixEnv?MixEnv.startSeconds(ready.track.id):0;
  if(entering>0){try{ready.media.currentTime=entering;}catch{}}
  try{await ready.media.play();}
  catch{if(preparedNext===ready)cancelWebFade();return;}
  finally{webFadeStarting=false;}
  // A pause, seek or manual selection may have happened while play() was pending.
  if(preparedNext!==ready||ready.generation!==playbackGeneration||!state.isPlaying)return;
  preparedNext=null;outgoingBlobUrl=currentBlobUrl;
  outgoingMix={track:state.currentTrack,endSeconds:endAt,seconds:state.crossfadeSeconds};
  outgoingAudio=audio;audio=ready.media;audio.id='local-media-player';outgoingAudio.removeAttribute('id');
  document.getElementById('player-video-surface').append(audio);setupAudioListeners();audio.volume=0;
  currentBlobUrl=ready.url;state.isOfflinePlayback=ready.local;streamRetried=false;++playbackGeneration;listeningClock.reset();
  state.currentTrack=ready.track;state.queueIndex=ready.index;
  const old=outgoingAudio, span=Math.max(.1,remaining)*1000;
  // Título y portada cambian a mitad de la mezcla; la barra sigue a la que sale hasta el final.
  const swap=()=>{if(outgoingAudio!==old||state.currentTrack?.id!==ready.track.id)return;updatePlayerBarUI(ready.track);updateMediaSessionMetadata(ready.track);highlightPlayingTracks();loadLyrics(ready.track);showOfflineIndicator(ready.local);};
  setTimeout(swap,span/2);
  const start=performance.now();
  const tick=now=>{
    if(outgoingAudio!==old)return;
    if(audio.paused){cancelWebFade();return;}
    const progress=Math.min(1,(now-start)/span),volume=state.isMuted?0:state.volume;
    // Potencia constante: la que sale sigue el coseno y la que entra el seno, sin bajón de volumen a mitad.
    old.volume=volume*Math.cos(progress*Math.PI/2);audio.volume=volume*Math.sin(progress*Math.PI/2);
    if(progress<1)fadeFrame=setTimeout(()=>tick(performance.now()),25);else cancelWebFade();
  };
  fadeFrame=setTimeout(()=>tick(performance.now()),25);checkAutoplayQueue();
}
setInterval(()=>{prepareWebFade().catch(()=>cancelWebFade());},150);
const nativeSeek=seekTo;
seekTo=function(seconds){cancelWebFade();listeningClock.previous=null;nativeSeek(seconds);};
window.addEventListener('pagehide',cancelWebFade);

const artworkDefaults = {
  downloadAnimatedArtwork: false, animatedArtworkQuality: 'medium', staticArtworkQuality: 'medium'
};
const artworkPreferences = { ...artworkDefaults };
const artworkQualities = ['low', 'medium', 'high'];
try { applyArtworkPreferences(JSON.parse(localStorage.getItem('musicbox_artwork_preferences') || '{}'), false, false); }
catch (_) { /* A malformed local value falls back to defaults. */ }

function applyArtworkPreferences(values, save = false, refresh = true) {
  let changed = false;
  for (const key of Object.keys(artworkDefaults)) {
    const value = values[key];
    const valid = key === 'downloadAnimatedArtwork' ? typeof value === 'boolean' : artworkQualities.includes(value);
    if (!valid || artworkPreferences[key] === value) continue;
    artworkPreferences[key] = value;
    if (save) dirtyPreferences[key] = value;
    changed = true;
  }
  if (!changed) return;
  localStorage.setItem('musicbox_artwork_preferences', JSON.stringify(artworkPreferences));
  if (save) scheduleCloudSave();
  if (refresh) {
    document.dispatchEvent(new Event('musicbox-artwork-preferences'));
    if (state.currentTrack) updatePlayerBarUI(state.currentTrack);
  }
}

function mountArtworkPreferences(host) {
  if (!host) return;
  const settings = document.createElement('fieldset');
  settings.className = 'artwork-settings';
  const legend = document.createElement('legend');
  legend.textContent = 'Portadas';
  settings.append(legend);
  const add = (key, label, options) => {
    const row = document.createElement('label');
    const text = document.createElement('span');
    text.textContent = label;
    const input = document.createElement(options ? 'select' : 'input');
    input.dataset.artworkPref = key;
    if (options) options.forEach((title, index) => input.add(new Option(title, artworkQualities[index])));
    else input.type = 'checkbox';
    input.onchange = () => applyArtworkPreferences({ [key]: options ? input.value : input.checked }, true);
    row.append(text, input);
    settings.append(row);
  };
  add('downloadAnimatedArtwork', 'Descargar portadas animadas');
  add('animatedArtworkQuality', 'Calidad de portada animada', ['Hasta 480p', 'Hasta 720p', 'Hasta 1080p']);
  add('staticArtworkQuality', 'Calidad de portada normal', ['400 px', '800 px', '1200 px']);
  host.append(settings);
  paintArtworkPreferences();
}

function paintArtworkPreferences() {
  for (const input of document.querySelectorAll('[data-artwork-pref]')) {
    const value = artworkPreferences[input.dataset.artworkPref];
    if (input.type === 'checkbox') input.checked = value;
    else input.value = value;
  }
}
document.addEventListener('musicbox-artwork-preferences', paintArtworkPreferences);

function sizedArtwork(url, quality) {
  const size = {low:400, medium:800, high:1200}[quality] || 800;
  if (/^https:\/\/[^/]*\.googleusercontent\.com\//.test(url)) return url.replace(/=w\d+-h\d+.*$/, '=w' + size + '-h' + size);
  if (/^https:\/\/[^/]*\.mzstatic\.com\//.test(url)) return url.replace(/\/\d+x\d+(?=bb\.)/, '/' + size + 'x' + size);
  if (/^https:\/\/i\.ytimg\.com\/vi\//.test(url)) {
    const filename = {low:'hqdefault', medium:'sddefault', high:'maxresdefault'}[quality];
    return url.replace(/\/(?:hq|mq|sd|maxres)default\.jpg$/, '/' + filename + '.jpg');
  }
  return url;
}

const artworkCache = new Map();
async function lookupArtwork(track, quality, signal) {
  const key = JSON.stringify([track.title, track.artist, track.album || '', track.duration || 0, quality]);
  if (!artworkCache.has(key)) {
    const params = new URLSearchParams({
      title: track.title, artist: track.artist, album: track.album || '',
      duration: Math.round(track.duration || 0), quality
    });
    const response = await fetch(apiUrl('api/artwork?' + params), {signal});
    if (!response.ok) return '';
    const url = (await response.json()).url || '';
    artworkCache.set(key, url);
    if (artworkCache.size > 128) artworkCache.delete(artworkCache.keys().next().value);
  }
  const url = artworkCache.get(key);
  return /^https:\/\/mvod\.itunes\.apple\.com\/.*\.mp4(?:\?|$)/.test(url) ? url : '';
}

async function downloadTrackArtwork(track) {
  const prefs = { ...artworkPreferences };
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 8000);
  const blob = async (url, type) => {
    if (!url) return null;
    try {
      const response = await fetch(url, {signal:controller.signal});
      if (!response.ok || !response.headers.get('content-type')?.startsWith(type)) return null;
      return await response.blob();
    } catch (_) { return null; }
  };
  try {
    const thumbnail = track.thumbnail || '';
    const [staticArtworkBlob, animatedArtworkBlob] = await Promise.all([
      blob(sizedArtwork(thumbnail, prefs.staticArtworkQuality), 'image/').then(result => result || blob(thumbnail, 'image/')),
      prefs.downloadAnimatedArtwork && track.title && track.artist
        ? lookupArtwork(track, prefs.animatedArtworkQuality, controller.signal).then(url => blob(url, 'video/')).catch(() => null)
        : null
    ]);
    return { staticArtworkBlob, animatedArtworkBlob, animatedArtworkQuality: prefs.animatedArtworkQuality };
  } finally { clearTimeout(timeout); }
}

let artworkRequest = null, artworkTimer = 0, artworkObjectUrl = '', artworkPaintId = 0;
const staticPlayerUI=updatePlayerBarUI;
updatePlayerBarUI=function(track){staticPlayerUI(track);scheduleArtwork(track);paintPlaybackSource();};
function scheduleArtwork(track) {
  clearTimeout(artworkTimer);artworkRequest?.abort();
  const cover=document.querySelector('.now-playing-cover-wrap');
  let video=cover.querySelector('.animated-cover');
  if(!video){video=document.createElement('video');video.className='animated-cover';video.muted=true;video.loop=true;video.playsInline=true;video.setAttribute('aria-hidden','true');cover.append(video);}
  video.onloadeddata = null;
  video.onerror = null;
  video.pause();video.style.opacity='0';video.removeAttribute('src');video.load();
  if (artworkObjectUrl) URL.revokeObjectURL(artworkObjectUrl);
  artworkObjectUrl = '';
  if(!track?.title||!track.artist||document.hidden||matchMedia('(prefers-reduced-motion:reduce)').matches)return;
  const generation = playbackGeneration;
  const paintId = ++artworkPaintId;
  const quality = artworkPreferences.animatedArtworkQuality;
  artworkTimer=setTimeout(async()=>{
    const controller=new AbortController();artworkRequest=controller;
    try {
      const stale = () => controller.signal.aborted || generation !== playbackGeneration || paintId !== artworkPaintId;
      const stored = await storage.getOfflineTrack(track.id);
      if (stale()) return;
      const useLocal = stored?.animatedArtworkBlob && (!navigator.onLine || stored.animatedArtworkQuality === quality);
      const url = useLocal ? '' : navigator.onLine ? await lookupArtwork(track, quality, controller.signal) : '';
      if (stale() || (!useLocal && !url)) return;
      if (useLocal) artworkObjectUrl = URL.createObjectURL(stored.animatedArtworkBlob);
      video.onloadeddata=()=>{video.style.opacity='1';video.play().catch(()=>{video.style.opacity='0';});};
      video.onerror=()=>{video.style.opacity='0';};video.src=artworkObjectUrl || url;video.load();
    }catch(error){if(error.name!=='AbortError')video.style.opacity='0';}
  },120);
}
document.addEventListener('visibilitychange',()=>{if(state.currentTrack)scheduleArtwork(state.currentTrack);});

document.addEventListener('DOMContentLoaded',()=>{
  const fadeToggle=actionButton('Crossfade','shuffle',()=>{setWebCrossfadeEnabled(!state.crossfadeSeconds);closeTrackContextMenu();},'context-item');
  fadeToggle.id='crossfade-menu-toggle';fadeToggle.setAttribute('role','menuitemcheckbox');fadeToggle.setAttribute('aria-checked',String(state.crossfadeSeconds>0));
  const removeTrack = actionButton('Quitar de esta playlist','close',()=>{},'context-item');
  removeTrack.id='ctx-remove-playlist'; removeTrack.hidden=true;
  const moveTrack = actionButton('Mover a otra playlist','forward',()=>{},'context-item');
  moveTrack.id='ctx-move-playlist'; moveTrack.hidden=true;
  const deleteFromDevice = actionButton('Eliminar del dispositivo','trash',()=>{},'context-item');
  deleteFromDevice.id='ctx-delete-device'; deleteFromDevice.hidden=true;
  document.getElementById('ctx-add-playlist').after(moveTrack);
  document.getElementById('track-context-menu').append(removeTrack,deleteFromDevice,fadeToggle);
  document.getElementById('nav-library').addEventListener('click',event=>{
    if(state.activeView!=='library'&&resumeCollection){event.stopImmediatePropagation();loadPlaylistView(resumeCollection);}
  },true);
  const right=document.querySelector('.player-right');
  const more = actionButton('Más opciones', 'more', event => {
    if (state.currentTrack) openTrackContextMenu(event, state.currentTrack);
  }, 'icon-btn more-btn');
  const expand = actionButton('Ampliar reproductor', 'expand', () => {
    if (state.currentTrack) document.body.classList.toggle('player-expanded');
  }, 'icon-btn player-expand-btn');
  right.append(more, expand);

  const desktop = matchMedia('(min-width:681px) and (min-height:601px), (min-width:681px) and (pointer:fine)');
  const player = document.getElementById('bottom-player');
  const header = player.querySelector('.mobile-player-top');
  const left = player.querySelector('.player-left');
  const source = document.getElementById('player-source');
  const sourceTools = document.createElement('div');
  sourceTools.className = 'player-source-tools';
  const previousPlaylist = actionButton('Playlist anterior','prev_page',()=>stepWebPlaylist(-1));
  const nextPlaylist = actionButton('Playlist siguiente','next_page',()=>stepWebPlaylist(1));
  sourceTools.append(previousPlaylist,nextPlaylist);
  const favorite = document.getElementById('player-fav-btn');
  const queue = document.getElementById('queue-toggle-btn');
  const volume = right.querySelector('.volume-container');
  const center = player.querySelector('.player-center');
  const transport = player.querySelector('.player-controls');
  const actions = document.createElement('div');
  actions.className = 'expanded-player-actions';
  const primary = document.createElement('div');
  primary.className = 'expanded-player-primary';
  const syncPlayerLayout = () => {
    const expanded = desktop.matches && document.body.classList.contains('player-expanded');
    if (expanded && !actions.isConnected) {
      sourceTools.prepend(source);
      header.prepend(sourceTools);
      libraryData().then(data => {
        previousPlaylist.hidden = nextPlaylist.hidden = data.playlists.length <= 1;
      }).catch(() => { previousPlaylist.hidden = nextPlaylist.hidden = true; });
      primary.append(favorite, queue);
      right.insertBefore(more, volume);
      actions.append(primary, transport, right);
      center.prepend(actions);
    } else if (!expanded && actions.isConnected) {
      left.prepend(source);
      sourceTools.remove();
      left.append(favorite);
      right.insertBefore(queue, volume);
      right.insertBefore(more, expand);
      center.prepend(transport);
      player.append(right);
      actions.remove();
    }
  };
  desktop.addEventListener('change', syncPlayerLayout);
  new MutationObserver(syncPlayerLayout).observe(document.body, { attributes: true, attributeFilter: ['class'] });
  syncPlayerLayout();
  document.addEventListener('keydown',event=>{if(event.key==='Escape')document.body.classList.remove('player-expanded');});
  const body=document.querySelector('#settings-modal .modal-scroll-body');
  const card=document.createElement('section');card.className='settings-card';
  card.innerHTML=`<div class="settings-row"><span>Reproducción automática</span><label class="switch"><input id="autoplay-setting" aria-label="Reproducción automática" type="checkbox" ${state.autoplayEnabled?'checked':''}><span class="slider"></span></label></div>
   <div class="settings-row"><span>Transiciones suaves</span><label class="switch"><input id="crossfade-setting" aria-label="Transiciones suaves" type="checkbox" ${state.crossfadeSeconds?'checked':''}><span class="slider"></span></label></div>
   <div class="settings-row"><span>Mostrar el crossfade en la barra de progreso</span><label class="switch"><input id="crossfade-bar-setting" aria-label="Mostrar el crossfade en la barra de progreso" type="checkbox" ${localStorage.getItem('musicbox_crossfade_bar')!=='0'?'checked':''}><span class="slider"></span></label></div>
   <label class="settings-row">Crossfade<input id="crossfade-duration" aria-label="Duración del crossfade" type="range" min="1" max="12" value="${state.crossfadeSeconds||5}"><output id="crossfade-value">${state.crossfadeSeconds} s</output></label>`;
  card.id='playback-settings-card';
  const heading=document.createElement('h3');heading.className='settings-section-title';heading.id='playback-settings-heading';heading.textContent='REPRODUCCIÓN';
  card.setAttribute('aria-labelledby',heading.id);
  body.querySelector('#settings-appearance-block').after(heading,card);
  card.querySelector('#autoplay-setting').onchange=event=>{state.autoplayEnabled=event.target.checked;localStorage.setItem('musicbox_autoplay',state.autoplayEnabled);accountPreference('autoplay',state.autoplayEnabled);if(state.autoplayEnabled)checkAutoplayQueue();};
  card.querySelector('#crossfade-bar-setting').onchange=event=>{localStorage.setItem('musicbox_crossfade_bar',event.target.checked?'1':'0');accountPreference('crossfadeBar',event.target.checked);};
  const duration=card.querySelector('#crossfade-duration');
  const update=()=>{cancelWebFade();state.crossfadeSeconds=card.querySelector('#crossfade-setting').checked?Number(duration.value):0;duration.disabled=!state.crossfadeSeconds;localStorage.setItem('musicbox_crossfade',state.crossfadeSeconds);accountPreference('crossfadeSeconds',state.crossfadeSeconds);card.querySelector('output').value=state.crossfadeSeconds+' s';};
  card.querySelector('#crossfade-setting').onchange=event=>setWebCrossfadeEnabled(event.target.checked);
  duration.oninput=()=>{update();localStorage.setItem('musicbox_crossfade_last',state.crossfadeSeconds);fadeToggle.setAttribute('aria-checked',String(state.crossfadeSeconds>0));};duration.disabled=!state.crossfadeSeconds;
  const main=document.getElementById('main-content');let gesture=null;
  main.style.touchAction='pan-y';
  main.addEventListener('pointerdown',event=>{if(!matchMedia('(max-width:680px), (pointer:coarse) and (max-height:600px)').matches||event.target.closest('button,input,select,a,.for-you-grid,.artists-carousel,.playlist-suggestions'))return;gesture={x:event.clientX,y:event.clientY};});
  main.addEventListener('pointercancel',()=>gesture=null);
  main.addEventListener('pointerup',async event=>{
    if(!gesture)return;const dx=event.clientX-gesture.x,dy=event.clientY-gesture.y;gesture=null;
    if(Math.abs(dx)<70||Math.abs(dx)<Math.abs(dy)*1.5)return;
    if(activePlaylist){const collections=(await libraryData())[activePlaylist.kind==='folder'?'folders':'playlists'];const index=collections.findIndex(row=>row.id===activePlaylist.id);const target=index+(dx<0?1:-1);if(target<0&&dx>0)loadLibraryView(libraryFilter);else if(target>=0&&collections[target])loadPlaylistView(collections[target]);}
    else if(state.activeView==='library'&&dx<0&&rememberedCollection)loadPlaylistView(rememberedCollection);
    else if(dx<0){if(state.activeView!=='library'&&resumeCollection)loadPlaylistView(resumeCollection);else loadLibraryView();}else loadHomeView();
  });
});
