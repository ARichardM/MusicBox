/* Account data has portable identities. Files, volume and the active session stay on this device. */
function accountActivity() {
  try { return JSON.parse(localStorage.getItem('musicbox_activity:'+(state.user?.uid||'guest'))||'{}'); }
  catch { return {}; }
}
function mergeAccountActivity(incoming) {
  const merged=MusicBoxCore.mergeActivity(accountActivity(),incoming||{});
  localStorage.setItem('musicbox_activity:'+(state.user?.uid||'guest'),JSON.stringify(merged));
  return merged;
}
function recordAccountPlay(track) {
  if(!/^[A-Za-z0-9_-]{11}$/.test(track?.id||''))return;
  const activity=accountActivity();
  const row={id:track.id,at:Date.now(),title:track.title||'',artist:track.artist||track.channel||'',
    thumbnail:track.thumbnail||'',duration:track.duration||0};
  mergeAccountActivity({...activity,played:{...activity.played,[track.id]:row}});
  scheduleCloudSave();
}
function recordAccountQuery(query) {
  query=String(query||'').trim();if(!query)return;
  const activity=accountActivity();
  mergeAccountActivity({...activity,queries:{...activity.queries,[query]:Date.now()}});
  scheduleCloudSave();
}
function accountPreference(key,value) {
  if(applyingCloud)return;
  dirtyPreferences[key]=value;scheduleCloudSave();
}
let appliedAccountPreferences="";
function applyAccountPreferences(prefs={}) {
  const values={...prefs,...dirtyPreferences};
  const signature=JSON.stringify(Object.keys(values).sort().map(key=>[key,values[key]]));
  if(signature===appliedAccountPreferences)return;
  appliedAccountPreferences=signature;
  if(typeof values.darkMode==='boolean')setDarkMode(values.darkMode,false);
  if(['blue','orange'].includes(values.accentColor))applyPrimaryColor(values.accentColor,false);
  applyArtworkPreferences(values);
  if(['MP3','MP4'].includes(values.autoFormat))state.downloadFormat=values.autoFormat.toLowerCase();
  const qualityKey=state.downloadFormat==='mp3'?'quality_mp3':'quality_mp4';
  const quality=Number.parseInt(values[qualityKey],10);
  if((state.downloadFormat==='mp3'?[128,192,256,320]:[360,480,720,1080]).includes(quality))state.downloadQuality=quality;
  if(typeof values.autoplay==='boolean'){
    state.autoplayEnabled=values.autoplay;localStorage.setItem('musicbox_autoplay',String(values.autoplay));
  }
  if(Number.isInteger(values.crossfadeSeconds)&&values.crossfadeSeconds>=0&&values.crossfadeSeconds<=12){
    if(state.crossfadeSeconds!==values.crossfadeSeconds)cancelWebFade();
    state.crossfadeSeconds=values.crossfadeSeconds;localStorage.setItem('musicbox_crossfade',state.crossfadeSeconds);
  }
  for(const [key,stored] of [['crossfadeBar','musicbox_crossfade_bar'],['lyricsShown','musicbox_lyrics_open'],['zoomMp3','musicbox_zoom_mp3']]){
    if(typeof values[key]==='boolean')localStorage.setItem(stored,values[key]?'1':'0');
  }
  if(typeof values.librarySort==='string'&&/^(default|title|artist|recent|plays|duration):[01]$/.test(values.librarySort)){
    localStorage.setItem('musicbox_library_sort',values.librarySort);
  }
  if(typeof values.lyricsShown==='boolean'){
    const shown=values.lyricsShown&&!!state.lyrics?.length;
    state.lyricsOpen=shown;
    document.getElementById('lyrics-drawer')?.classList.toggle('open',shown);
    document.body.classList.toggle('lyrics-open',shown);
    document.getElementById('lyrics-toggle-btn')?.classList.toggle('active',shown);
  }
  const autoplay=document.getElementById('autoplay-setting'),fade=document.getElementById('crossfade-setting'),duration=document.getElementById('crossfade-duration'),zone=document.getElementById('crossfade-bar-setting'),output=document.getElementById('crossfade-value');
  if(autoplay)autoplay.checked=state.autoplayEnabled;
  if(fade)fade.checked=state.crossfadeSeconds>0;
  if(duration){duration.value=state.crossfadeSeconds||5;duration.disabled=!state.crossfadeSeconds;}
  if(zone)zone.checked=localStorage.getItem('musicbox_crossfade_bar')!=='0';
  if(output)output.value=state.crossfadeSeconds+' s';
  document.dispatchEvent(new Event('musicbox-account-preferences'));
}
let cloudAccountWatch=null,cloudAccountUid='',cloudAccountRead=Promise.resolve();
function stopAccountWatch(){cloudAccountWatch?.();cloudAccountWatch=null;cloudAccountUid='';}
function watchCloudAccount(uid){
  if(cloudAccountUid===uid)return;stopAccountWatch();cloudAccountUid=uid;
  cloudAccountWatch=fbDb.collection('users').doc(uid).collection('state').doc('musicbox').onSnapshot(snapshot=>{
    if(!snapshot.exists||snapshot.metadata?.hasPendingWrites)return;
    cloudAccountRead=cloudAccountRead.then(async()=>{
      if(state.user?.uid!==uid||!cloudBase||cloudSaving)return;
      const root=JSON.parse(snapshot.data().payload||'{}'),remote=root.playlists||{};
      mergeAccountActivity(root.activity);mergeWebStats(root.clicks);applyAccountPreferences(root.preferences);
      const base=cloudBase;
      if(JSON.stringify(base)===JSON.stringify(remote))return;
      const local=MusicBoxCore.section(await storage.getAllPlaylists(),await storage.getAllFavorites(),base);
      if(state.user?.uid!==uid)return;
      const incoming=MusicBoxCore.merge(base,local,remote),changed=JSON.stringify(local)!==JSON.stringify(incoming);
      if(changed)await applyCloudSection(incoming);
      if(state.user?.uid!==uid)return;
      cloudBase=remote;localStorage.setItem('musicbox_cloud_base:'+uid,JSON.stringify(remote));
      if(changed&&state.activeView==='library'){
        const selected=activePlaylist;
        if(selected&&!selected.kind){const updated=(await libraryData()).playlists.find(row=>row.id===selected.id);if(updated)await loadPlaylistView(updated);else await loadLibraryView('playlists');}
        else await loadLibraryView(libraryFilter);
      }
      if(JSON.stringify(incoming)!==JSON.stringify(remote))scheduleCloudSave();
    }).catch(()=>showToast('No se pudo actualizar la biblioteca. Revisa tu conexión.',{kind:'error',retry:()=>syncWithFirestore(uid,true)}));
  },()=>showToast('No se pudo sincronizar. Revisa tu conexión.',{kind:'error',retry:()=>syncWithFirestore(uid,true)}));
}

function installAccountSort(select,render){
  const natural=[...select.options].map(option=>option.text);
  const update=()=>{
    if(!select.isConnected){document.removeEventListener('musicbox-account-preferences',update);return;}
    const [mode,direction]=(localStorage.getItem('musicbox_library_sort')||'default:1').split(':');select.value=mode;
    [...select.options].forEach((option,index)=>option.text=natural[index]);
    const labels={default:'Orden inverso',title:'Título Z–A',artist:'Artista Z–A',recent:'Más antiguas',plays:'Menos escuchadas',duration:'Más largas'};
    if(direction==='0'&&select.selectedOptions[0])select.selectedOptions[0].text=labels[mode]||mode;
    render();
  };
  document.addEventListener('musicbox-account-preferences',update);update();
}

function organizeAccountSettings(){
  const body=document.querySelector('#settings-modal .modal-scroll-body');
  const cookies=body.querySelector('#cookies-status-desc')?.closest('.settings-row');
  if(!cookies)return;
  const downloads=cookies.parentElement;downloads.id='settings-downloads-block';
  if(cookies.nextElementSibling?.classList.contains('settings-divider'))cookies.nextElementSibling.remove();
  const advanced=document.createElement('details');advanced.className='settings-advanced';
  const summary=document.createElement('summary');summary.textContent='Opciones avanzadas';advanced.append(summary,cookies);
  downloads.append(advanced);
  const preferences=document.createElement('div');preferences.className='settings-download-preferences';
  preferences.innerHTML='<label>Formato<select id="settings-download-format" aria-label="Formato de descarga"><option value="mp3">MP3</option><option value="mp4">MP4</option></select></label><label>Calidad<select id="settings-download-quality" aria-label="Calidad de descarga"></select></label>';
  downloads.prepend(preferences);
  const format=preferences.querySelector('#settings-download-format'),quality=preferences.querySelector('#settings-download-quality');
  const paint=()=>{format.value=state.downloadFormat;const values=format.value==='mp3'?[128,192,256,320]:[360,480,720,1080];quality.replaceChildren(...values.map(n=>new Option(n+(format.value==='mp3'?' kbps':'p'),n)));quality.value=values.includes(state.downloadQuality)?state.downloadQuality:(format.value==='mp3'?192:720);};
  format.onchange=()=>{state.downloadFormat=format.value;state.downloadQuality=format.value==='mp3'?192:720;paint();save();};
  quality.onchange=()=>{state.downloadQuality=Number(quality.value);save();};
  const save=()=>{localStorage.setItem('musicbox_download_format',state.downloadFormat);localStorage.setItem('musicbox_download_quality',state.downloadQuality);accountPreference('autoFormat',state.downloadFormat.toUpperCase());accountPreference(state.downloadFormat==='mp3'?'quality_mp3':'quality_mp4',state.downloadQuality+(state.downloadFormat==='mp3'?' kbps':'p'));};
  const artwork=body.querySelector('.artwork-settings');if(artwork)downloads.after(artwork);
  document.addEventListener('musicbox-account-preferences',paint);paint();
}
document.addEventListener('DOMContentLoaded',organizeAccountSettings);
