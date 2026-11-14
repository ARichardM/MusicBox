/* Fake Firestore only. Never connect to an account. */
(async () => {
 if (!verificationMode || fbDb || fbAuth) throw new Error('An isolated verification page is required');
 const originalUser=state.user;
 const originalSection=MusicBoxCore.section(await storage.getAllPlaylists(),await storage.getAllFavorites());
 const previousOwner=localStorage.getItem('musicbox_library_owner');
 const uid='musicbox-test-account'; const results=[];
 const check=(condition,label)=>{if(!condition)throw new Error(label);results.push(label);};
 const rootPath=`users/${uid}/state/musicbox`;
 const track={id:'abcdefghijk',title:'Cloud song',channel:'Cloud artist',duration:90};
 const documents=new Map([[rootPath,{payload:JSON.stringify({schema:1,clicks:{keep:true},devices:{windows:{name:'Windows'}},preferences:{language:'es',accentColor:'orange'},playlists:{playlists:{Favoritos:['youtube:abcdefghijk'],Original:['youtube:abcdefghijk','C:\\Music\\local.mp3']},onlineTracks:{abcdefghijk:track},descriptions:{Original:'Original description'},autoDownload:['Original']}})}]]);
 const snap=path=>({exists:documents.has(path),data:()=>documents.get(path)});
 const reference=path=>({path,collection:name=>reference(path+'/'+name),doc:name=>reference(path+'/'+name),get:async()=>snap(path),onSnapshot:(next)=>{next(snap(path));return()=>{};}});
 const fake={collection:name=>reference(name),runTransaction:async callback=>callback({get:async ref=>snap(ref.path),set:(ref,value)=>documents.set(ref.path,value)})};
 try {
  state.user={uid,name:'Test',authenticated:true,signedInAt:Date.now()}; fbDb=fake;
  await syncWithFirestore(uid); clearTimeout(cloudSaveTimer);
  const playlists=await storage.getAllPlaylists();
  check(playlists[0].tracks[0].artist==='Cloud artist','Metadatos de Android/Windows normalizados');
  check(playlists[0].unavailableEntries[0]==='C:\\Music\\local.mp3','Entrada local de Windows conservada');
  check(state.accent==='orange','Preferencia de color compartida');
  const localTrack={id:'bcdefghijkl',title:'Local addition',artist:'Browser'};
  await storage.toggleFavorite(localTrack); clearTimeout(cloudSaveTimer);
  let remote=JSON.parse(documents.get(rootPath).payload);
  remote.playlists.playlists.Concurrent=['youtube:abcdefghijk'];
  remote.devices.android={name:'Android'};
  documents.set(rootPath,{payload:JSON.stringify(remote)});
  await pushStateToFirestore(); clearTimeout(cloudSaveTimer);
  remote=JSON.parse(documents.get(rootPath).payload);
  check(remote.playlists.playlists.Concurrent.length===1,'Adición simultánea de otra plataforma conservada');
  check(remote.playlists.playlists.Favoritos.includes('youtube:bcdefghijkl'),'Favorito exportado como ID compartido');
  check(remote.clicks.keep && remote.devices.android && remote.devices.windows,'Clics y dispositivos ajenos conservados');
  check((await storage.getAllPlaylists()).some(pl=>pl.name==='Concurrent'),'Cambios remotos reconciliados con la copia local');
  await storage.savePlaylist({id:'unsent',name:'Sin enviar',tracks:[localTrack]});clearTimeout(cloudSaveTimer);
  fbDb={...fake,runTransaction:async()=>{throw new Error('Simulated offline');}};
  await pushStateToFirestore();clearTimeout(cloudSaveTimer);
  fbDb=fake; cloudBase=null;
  await syncWithFirestore(uid);clearTimeout(cloudSaveTimer);
  check((await storage.getAllPlaylists()).some(pl=>pl.name==='Sin enviar'),'Error de red y reconexión conservan cambios locales');
  return {passed:results.length,results};
 } finally {
  clearTimeout(cloudSaveTimer); revocationUnsubscribe?.();revocationUnsubscribe=null; fbDb=null;fbAuth=null;cloudBase=null;
  state.user=originalUser;
  await applyCloudSection(originalSection);
  for(const suffix of ['cloud_base:','cloud_local:','pending_prefs:'])localStorage.removeItem('musicbox_'+suffix+uid);
  if(previousOwner)localStorage.setItem('musicbox_library_owner',previousOwner);else localStorage.removeItem('musicbox_library_owner');
  setDarkMode(true,false);applyPrimaryColor('blue',false);
 }
})()
