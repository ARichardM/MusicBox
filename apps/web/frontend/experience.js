/* Navigation and playback behavior shared with the native MusicBox experiences. */
state.autoplayEnabled = localStorage.getItem('musicbox_autoplay') !== 'false';
state.crossfadeSeconds = Math.max(0,Math.min(12,Number(localStorage.getItem('musicbox_crossfade')) || 0));
function setWebCrossfadeEnabled(enabled) {
  cancelWebFade();
  if(state.crossfadeSeconds>0)localStorage.setItem('musicbox_crossfade_last',state.crossfadeSeconds);
  state.crossfadeSeconds=enabled?Math.max(1,Math.min(12,Number(localStorage.getItem('musicbox_crossfade_last'))||5)):0;
  localStorage.setItem('musicbox_crossfade',state.crossfadeSeconds);
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
function renderTrackList(list,tracks,create=createTrackRow) {
  disposeTrackList();
  const main=document.getElementById('main-content');
  let start=-1,end=-1,frame=0;
  const top=document.createElement('div'),bottom=document.createElement('div');
  top.className=bottom.className='virtual-spacer';
  const offset=()=>list.getBoundingClientRect().top-main.getBoundingClientRect().top+main.scrollTop;
  const draw=()=>{
    frame=0;
    if(!list.isConnected){disposeTrackList();return;}
    const first=Math.max(0,Math.min(Math.max(0,tracks.length-Math.ceil(main.clientHeight/82)),Math.floor((main.scrollTop-offset())/82)-6));
    const last=Math.min(tracks.length,first+Math.ceil(main.clientHeight/82)+14);
    if(first===start&&last===end)return;start=first;end=last;
    top.style.height=Math.max(0,first*82-8)+'px';top.hidden=!first;
    bottom.style.height=Math.max(0,(tracks.length-last)*82-8)+'px';bottom.hidden=last>=tracks.length;
    list.replaceChildren(top,...tracks.slice(first,last).map(track=>create(track)),bottom);
    if(!tracks.length){const empty=document.createElement('p');empty.className='empty-state';empty.textContent='No hay canciones con estos filtros.';list.replaceChildren(empty);}
  };
  const scroll=()=>{if(!frame)frame=requestAnimationFrame(draw);};
  main.addEventListener('scroll',scroll,{passive:true});window.addEventListener('resize',scroll);
  disposeTrackList=()=>{main.removeEventListener('scroll',scroll);window.removeEventListener('resize',scroll);cancelAnimationFrame(frame);};
  list.scrollToTrack=id=>{
    const index=tracks.findIndex(track=>track.id===id);
    if(index>=0){main.scrollTop=Math.max(0,offset()+index*82-main.clientHeight/2);start=-1;draw();}
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
  if(collection.tracks?.length){
    state.queue=[...collection.tracks];
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
    playbackSource = activePlaylist?.tracks?.some(row=>row.id===track.id) ? activePlaylist : null;
    if (playbackSource) { state.queue=[...(currentLibraryTracks.some(row=>row.id===track.id)?currentLibraryTracks:playbackSource.tracks)]; state.queueIndex=state.queue.findIndex(row=>row.id===track.id); fromQueue=true; }
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
  const [playlists,favorites,offline,history]=await Promise.all([storage.getAllPlaylists(),storage.getAllFavorites(),storage.getAllOfflineTracks(),storage.getRecentHistory(100)]);
  const songs=[...new Map([...offline,...favorites,...playlists.flatMap(row=>row.tracks||[]),...history].filter(Boolean).map(row=>[row.id,row])).values()];
  return {playlists:[...(favorites.length?[{id:'favorites',name:'Favoritos',tracks:favorites,favorite:true}]:[]),...playlists],songs,
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
  for(const [label,icon,action] of [['Configurar playlist','settings',()=>playlistSettings(collection)],['Compartir playlist (CSV)','external',()=>exportPlaylist(collection)]]){
    const button=actionButton(label,icon,()=>{close();action();},'context-item');button.setAttribute('role','menuitem');menu.append(button);
  }
  const rect=event.currentTarget.getBoundingClientRect();menu.style.display='block';menu.style.position='fixed';menu.style.left=Math.max(8,Math.min(innerWidth-248,rect.right-240))+'px';menu.style.top=Math.min(innerHeight-110,rect.bottom+6)+'px';
  document.body.append(menu);menu.querySelector('button').focus();document.addEventListener('pointerdown',outside);document.addEventListener('keydown',keys);
}
function libraryShell(title,context='') {
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
  actions.append(actionButton('Importar playlist','playlist',importPlaylistDialog,'pill-btn'),actionButton('Nueva playlist','plus',openNewPlaylistModal),actionButton('Ir a canción actual','locate',locatePlayingTrack));
  const refresh=actionButton('Actualizar biblioteca','refresh',()=>runRefresh(refresh,async()=>{if(state.user?.authenticated)await syncWithFirestore(state.user.uid,true);await loadLibraryView(libraryFilter);}));actions.append(refresh);
  // Como en el escritorio: el orden y el contador van a la derecha de las pestañas; importar queda en la cabecera.
  actions.prepend(actionButton('Importar carpeta','folder',()=>importLocalFiles(true)),actionButton('Importar canciones','music',importLocalFiles));
  actions.insertBefore(actionButton('Aleatorio','shuffle',()=>{const pool=currentLibraryTracks||[];if(!pool.length)return;state.queue=[...pool].sort(()=>Math.random()-.5);state.queueIndex=0;playTrack(state.queue[0],true);}),actions.querySelector('[aria-label="Actualizar biblioteca"]'));
  const sort=document.createElement('select');sort.id='library-sort';sort.className='collection-sort';sort.setAttribute('aria-label','Ordenar');
  for(const [key,label] of [['recent','Más recientes'],['default','Orden original'],['title','Título A–Z'],['artist','Artista A–Z'],['plays','Más escuchadas']])sort.add(new Option(label,key));
  const total=document.createElement('small');total.className='collection-count library-total';
  const tabs=main.querySelector('.search-filter-tabs');if(tabs)tabs.append(sort,total);else main.querySelector('.library-toolbar').append(sort,total);
  const render=()=>{
    const query=main.querySelector('input').value.toLocaleLowerCase();
    let items=[...data[libraryFilter]].filter(row=>`${row.name||row.title} ${row.artist||''}`.toLocaleLowerCase().includes(query));
    if(sort.value==='title'||sort.value==='artist')items.sort((a,b)=>String(a[sort.value]||a.name||'').localeCompare(String(b[sort.value]||b.name||'')));
    if(sort.value==='recent')items.sort((a,b)=>(b.createdAt||b.savedAt||0)-(a.createdAt||a.savedAt||0));
    if(sort.value==='plays')items.sort((a,b)=>(b.plays||0)-(a.plays||0));
    const list=main.querySelector('#library-content-list');list.replaceChildren();list.classList.toggle('collection-grid',libraryFilter!=='songs');
    total.textContent=items.length+' '+(libraryFilter==='songs'?'canciones':libraryFilter==='playlists'?'playlists':'carpetas');
    if(libraryFilter==='songs'){currentLibraryTracks=items;renderTrackList(list,items);if(!items.length&&!query){const empty=document.createElement('div');empty.className='library-empty';empty.innerHTML='<strong>Tu biblioteca está vacía</strong><span>El navegador no puede leer tus carpetas por sí solo: importa canciones o una carpeta para empezar.</span>';const row=document.createElement('div');row.className='library-empty-actions';row.append(actionButton('Importar canciones','plus',importLocalFiles,'pill-btn'),actionButton('Importar carpeta','folder',()=>importLocalFiles(true),'pill-btn'));empty.append(row);list.replaceChildren(empty);}}
    else items.forEach(row=>list.append(collectionCard(row)));
    if(!items.length&&libraryFilter!=='songs'){const empty=document.createElement('p');empty.className='empty-state';empty.textContent='No hay elementos con estos filtros.';list.append(empty);}
  };
  main.querySelector('input').oninput=render;sort.onchange=render;render();
};
loadPlaylistView = async function(collection) {
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
  actions.append(actionButton('Aleatorio','shuffle',()=>{if(!collection.tracks.length)return;playbackSource=collection;state.queue=[...collection.tracks];state.queueIndex=Math.floor(Math.random()*state.queue.length);playTrack(state.queue[state.queueIndex],true);}));
  const artist=document.createElement('select');artist.className='artist-filter';artist.setAttribute('aria-label','Filtrar por artista');artist.add(new Option('Artistas',''));
  [...new Set(collection.tracks.map(row=>row.artist||row.channel).filter(Boolean))].sort().forEach(name=>artist.add(new Option(name,name)));toolbar.append(artist);
  const sort=document.createElement('select');sort.className='collection-sort';sort.setAttribute('aria-label','Ordenar');
  for(const [key,label] of [['default','Orden original'],['title','Título A–Z'],['artist','Artista A–Z'],['recent','Más recientes'],['plays','Más escuchadas'],['duration','Más cortas']])sort.add(new Option(label,key));
  if(matchMedia('(max-width:680px), (pointer:coarse) and (max-height:600px)').matches)toolbar.append(sort);else main.querySelector('.search-filter-tabs').append(sort);
  if(!collection.kind&&!collection.favorite){
    const auto=document.createElement('label');auto.className='auto-download-pill';auto.append(document.createTextNode('Auto-descarga'));
    const toggle=document.createElement('input');toggle.type='checkbox';toggle.checked=!!collection.autoDownload;
    toggle.onchange=async()=>{collection.autoDownload=toggle.checked;await storage.savePlaylist(collection);if(toggle.checked&&!verificationMode)runAutoDownloads();};auto.append(toggle);toolbar.append(auto);
  }
  const suggestions=document.createElement('details');suggestions.className='playlist-suggestions';
  const summary=document.createElement('summary');summary.textContent='Para esta playlist';suggestions.append(summary);
  const related=document.createElement('div');suggestions.append(related);
  let loaded=false;
  suggestions.ontoggle=async()=>{if(!suggestions.open||loaded)return;loaded=true;related.textContent='Buscando…';try{const rows=await relatedToCollection(collection.tracks,collection.tracks);related.replaceChildren(...rows.slice(0,8).map(row=>createTrackRow(row)));if(!rows.length)related.textContent='No se encontraron canciones relacionadas.';}catch{loaded=false;related.textContent='No se pudieron cargar las sugerencias. Cierra y vuelve a abrir para reintentar.';}};
  toolbar.after(suggestions);
  const render=()=>{
    const query=main.querySelector('input').value.toLocaleLowerCase();const list=main.querySelector('#library-content-list');list.replaceChildren();
    const tracks=collection.tracks.filter(track=>(!artist.value||(track.artist||track.channel)===artist.value)&&`${track.title} ${track.artist}`.toLocaleLowerCase().includes(query));
    if(['title','artist'].includes(sort.value))tracks.sort((a,b)=>String(a[sort.value]||'').localeCompare(String(b[sort.value]||'')));
    if(sort.value==='duration')tracks.sort((a,b)=>(a.duration||0)-(b.duration||0));
    if(sort.value==='recent')tracks.reverse();
    if(sort.value==='plays'){const plays=new Map(webStatsRows().map(row=>[row.id,row.plays]));tracks.sort((a,b)=>(plays.get(b.id)||0)-(plays.get(a.id)||0));}
    currentLibraryTracks=tracks;
    renderTrackList(list,tracks,track=>{
      const row=createTrackRow(track);const index=collection.tracks.indexOf(track);
      if(!collection.kind&&!collection.favorite){
        row.querySelector('.row-actions').append(actionButton('Quitar de esta playlist','close',async event=>{
          event.stopPropagation();collection.tracks.splice(index,1);await storage.savePlaylist(collection);render();
        }));
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
  main.querySelector('.home-search-input').oninput=render;artist.onchange=render;sort.onchange=render;render();main.scrollTop=collectionPositions.get(collection.id)||0;
};

let preparedNext=null, preparingNext=false, outgoingAudio=null, outgoingBlobUrl=null, webFadeStarting=false, fadeFrame=0;
function cancelWebFade() {
  clearTimeout(fadeFrame);
  if(outgoingAudio){outgoingAudio.pause();outgoingAudio.removeAttribute('src');outgoingAudio.load();outgoingAudio.remove();outgoingAudio=null;}
  if(outgoingBlobUrl){URL.revokeObjectURL(outgoingBlobUrl);outgoingBlobUrl=null;}
  if(preparedNext){preparedNext.media.removeAttribute('src');preparedNext.media.load();if(preparedNext.url)URL.revokeObjectURL(preparedNext.url);preparedNext=null;}
  audio.volume=state.isMuted?0:state.volume;
}
async function prepareWebFade() {
  if(!state.crossfadeSeconds||!state.isOfflinePlayback||state.videoDockOpen||!state.isPlaying||outgoingAudio||webFadeStarting)return;
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
  outgoingAudio=audio;audio=ready.media;audio.id='local-media-player';outgoingAudio.removeAttribute('id');
  document.getElementById('yt-player-container').append(audio);setupAudioListeners();audio.volume=0;
  currentBlobUrl=ready.url;++playbackGeneration;listeningClock.reset();
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

const artworkCache=new Map();
let artworkRequest=null,artworkTimer=0;
const staticPlayerUI=updatePlayerBarUI;
updatePlayerBarUI=function(track){staticPlayerUI(track);scheduleArtwork(track);paintPlaybackSource();};
function scheduleArtwork(track) {
  clearTimeout(artworkTimer);artworkRequest?.abort();
  const cover=document.querySelector('.now-playing-cover-wrap');
  let video=cover.querySelector('.animated-cover');
  if(!video){video=document.createElement('video');video.className='animated-cover';video.muted=true;video.loop=true;video.playsInline=true;video.setAttribute('aria-hidden','true');cover.append(video);}
  video.pause();video.style.opacity='0';video.removeAttribute('src');video.load();
  if(!track?.title||!track.artist||document.hidden||matchMedia('(prefers-reduced-motion:reduce)').matches)return;
  const key=JSON.stringify([track.title,track.artist,track.album||'',track.duration||0]);
  const generation=playbackGeneration;
  artworkTimer=setTimeout(async()=>{
    const controller=new AbortController();artworkRequest=controller;
    try {
      let url=artworkCache.get(key);
      if(url===undefined){const params=new URLSearchParams({title:track.title,artist:track.artist,album:track.album||'',duration:Math.round(track.duration||0)});const response=await fetch(apiUrl('api/artwork?'+params),{signal:controller.signal});if(!response.ok)return;url=(await response.json()).url||'';artworkCache.set(key,url);if(artworkCache.size>128)artworkCache.delete(artworkCache.keys().next().value);}
      if(controller.signal.aborted||generation!==playbackGeneration||!/^https:\/\/mvod\.itunes\.apple\.com\/.*\.mp4(?:\?|$)/.test(url))return;
      video.onloadeddata=()=>{video.style.opacity='1';video.play().catch(()=>{video.style.opacity='0';});};
      video.onerror=()=>{video.style.opacity='0';};video.src=url;video.load();
    }catch(error){if(error.name!=='AbortError')video.style.opacity='0';}
  },120);
}
document.addEventListener('visibilitychange',()=>{if(state.currentTrack)scheduleArtwork(state.currentTrack);});

document.addEventListener('DOMContentLoaded',()=>{
  const fadeToggle=actionButton('Crossfade','shuffle',()=>{setWebCrossfadeEnabled(!state.crossfadeSeconds);closeTrackContextMenu();},'context-item');
  fadeToggle.id='crossfade-menu-toggle';fadeToggle.setAttribute('role','menuitemcheckbox');fadeToggle.setAttribute('aria-checked',String(state.crossfadeSeconds>0));
  document.getElementById('track-context-menu').append(fadeToggle);
  document.getElementById('nav-library').addEventListener('click',event=>{
    if(state.activeView!=='library'&&resumeCollection){event.stopImmediatePropagation();loadPlaylistView(resumeCollection);}
  },true);
  const right=document.querySelector('.player-right');
  right.append(actionButton('Más opciones','more',event=>{if(state.currentTrack)openTrackContextMenu(event,state.currentTrack);}));
  right.append(actionButton('Ampliar reproductor','expand',()=>{if(state.currentTrack)document.body.classList.toggle('player-expanded');}));
  document.addEventListener('keydown',event=>{if(event.key==='Escape')document.body.classList.remove('player-expanded');});
  const body=document.querySelector('#settings-modal .modal-scroll-body');
  const card=document.createElement('section');card.className='settings-card';
  card.innerHTML=`<label class="settings-row">Reproducción automática<input id="autoplay-setting" type="checkbox" ${state.autoplayEnabled?'checked':''}></label>
   <label class="settings-row">Transiciones suaves<input id="crossfade-setting" type="checkbox" ${state.crossfadeSeconds?'checked':''}></label>
   <label class="settings-row">Mostrar el crossfade en la barra de progreso<input id="crossfade-bar-setting" type="checkbox" ${localStorage.getItem('musicbox_crossfade_bar')!=='0'?'checked':''}></label>
   <label class="settings-row">Crossfade<input id="crossfade-duration" aria-label="Duración del crossfade" type="range" min="1" max="12" value="${state.crossfadeSeconds||5}"><output id="crossfade-value">${state.crossfadeSeconds} s</output></label>`;
  body.prepend(card);
  card.querySelector('#autoplay-setting').onchange=event=>{state.autoplayEnabled=event.target.checked;localStorage.setItem('musicbox_autoplay',state.autoplayEnabled);if(state.autoplayEnabled)checkAutoplayQueue();};
  card.querySelector('#crossfade-bar-setting').onchange=event=>localStorage.setItem('musicbox_crossfade_bar',event.target.checked?'1':'0');
  const duration=card.querySelector('#crossfade-duration');
  const update=()=>{cancelWebFade();state.crossfadeSeconds=card.querySelector('#crossfade-setting').checked?Number(duration.value):0;duration.disabled=!state.crossfadeSeconds;localStorage.setItem('musicbox_crossfade',state.crossfadeSeconds);card.querySelector('output').value=state.crossfadeSeconds+' s';};
  card.querySelector('#crossfade-setting').onchange=event=>setWebCrossfadeEnabled(event.target.checked);
  duration.oninput=()=>{update();localStorage.setItem('musicbox_crossfade_last',state.crossfadeSeconds);fadeToggle.setAttribute('aria-checked',String(state.crossfadeSeconds>0));};duration.disabled=!state.crossfadeSeconds;
  const main=document.getElementById('main-content');let gesture=null;
  main.style.touchAction='pan-y';
  main.addEventListener('pointerdown',event=>{if(!matchMedia('(max-width:680px), (pointer:coarse) and (max-height:600px)').matches||event.target.closest('button,input,select,a,.for-you-grid,.artists-carousel'))return;gesture={x:event.clientX,y:event.clientY};});
  main.addEventListener('pointercancel',()=>gesture=null);
  main.addEventListener('pointerup',async event=>{
    if(!gesture)return;const dx=event.clientX-gesture.x,dy=event.clientY-gesture.y;gesture=null;
    if(Math.abs(dx)<70||Math.abs(dx)<Math.abs(dy)*1.5)return;
    if(activePlaylist){const collections=(await libraryData())[activePlaylist.kind==='folder'?'folders':'playlists'];const index=collections.findIndex(row=>row.id===activePlaylist.id);const target=index+(dx<0?1:-1);if(target<0&&dx>0)loadLibraryView(libraryFilter);else if(target>=0&&collections[target])loadPlaylistView(collections[target]);}
    else if(state.activeView==='library'&&dx<0&&rememberedCollection)loadPlaylistView(rememberedCollection);
    else if(dx<0){if(state.activeView!=='library'&&resumeCollection)loadPlaylistView(resumeCollection);else loadLibraryView();}else loadHomeView();
  });
});
