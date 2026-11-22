/* Isolated browser checks: synthetic media and API fixtures, no real account or playlists. */
const assert=require('node:assert/strict');
const http=require('node:http');
const fs=require('node:fs/promises');
const path=require('node:path');
const {chromium}=require(process.env.MUSICBOX_PLAYWRIGHT_MODULE||'playwright');
const frontend=path.resolve('apps/web/frontend');
const wave=Buffer.alloc(44+8000*2*60);
wave.write('RIFF');wave.writeUInt32LE(wave.length-8,4);wave.write('WAVEfmt ',8);wave.writeUInt32LE(16,16);wave.writeUInt16LE(1,20);wave.writeUInt16LE(1,22);wave.writeUInt32LE(8000,24);wave.writeUInt32LE(16000,28);wave.writeUInt16LE(2,32);wave.writeUInt16LE(16,34);wave.write('data',36);wave.writeUInt32LE(wave.length-44,40);
const songs=[0,1,2,3].map(i=>({id:'testTrack0'+i,title:'Canción '+i,artist:'Artista '+i,duration:60,thumbnail:'logo_complete.png',url:'https://www.youtube.com/watch?v=testTrack0'+i}));
const server=http.createServer(async(req,res)=>{
  try {
    let pathname=new URL(req.url,'http://localhost').pathname;
    if(pathname==='/runtime-config.js'){res.setHeader('Content-Type','text/javascript');return res.end('window.MUSICBOX_CONFIG={}');}
    if(pathname==='/')pathname='/index.html';
    const file=path.resolve(frontend,'.'+decodeURIComponent(pathname));
    if(!file.startsWith(frontend+path.sep)){res.writeHead(403);return res.end();}
    const mime={'.html':'text/html','.js':'text/javascript','.css':'text/css','.svg':'image/svg+xml','.png':'image/png','.ico':'image/x-icon'};
    res.setHeader('Content-Type',mime[path.extname(file)]||'application/octet-stream');res.end(await fs.readFile(file));
  } catch {res.writeHead(404);res.end();}
});
(async()=>{
  await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
  const browser=await chromium.launch({headless:true});
  try {
    for(const viewport of [{width:1180,height:760},{width:900,height:620},{width:390,height:844},{width:360,height:740},{width:844,height:390}]){
      const context=await browser.newContext({viewport,hasTouch:viewport.height===390,isMobile:viewport.height===390,locale:'es-CL',serviceWorkers:viewport.width===390?'allow':'block'});
      const page=await context.newPage();const errors=[];page.on('pageerror',error=>errors.push(error.message));
      let releaseSlow;
      let carouselFixture = false;
      let allowRetriedDownload = false;
      let normalStreamRequests = 0;
      let suggestionRequests = 0;
      const extraSuggestion = { ...songs[3], id:'testTrack04', title:'Otra sugerencia' };
      const slowGate=new Promise(resolve=>releaseSlow=resolve);
      const searchPageRequests=[];
      let holdTrack03=false,releaseTrack03;const track03Gate=new Promise(resolve=>releaseTrack03=resolve);
      await page.route('**/www.gstatic.com/**',route=>route.abort());
      await page.route('**/www.youtube.com/**',route=>route.abort());
      // googleusercontent rechaza las peticiones con referrer de la web; solo sirve la imagen si no lo llevan.
      const pixel=Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII=','base64');
      await page.route('**/yt3.googleusercontent.com/**',route=>route.request().headers()['referer']?route.abort():route.fulfill({status:200,contentType:'image/png',body:pixel}));
      await page.route('**/api/**',async route=>{
        const url=new URL(route.request().url());
        if(url.pathname==='/api/stream/slowTest001')await slowGate;
        if(url.pathname==='/api/stream/testTrack03'&&holdTrack03&&!url.searchParams.has('video'))await track03Gate;
        if(url.pathname==='/api/stream/testTrack06'&&!url.searchParams.has('video')){
          normalStreamRequests++;
          if(normalStreamRequests===1)return route.fulfill({status:503,json:{detail:'Interrupción de prueba'}});
        }
        if(url.pathname==='/api/stream/testTrack01'&&url.searchParams.has('video'))return route.fulfill({status:404,json:{detail:'Esta canción no tiene un clip disponible.'}});
        if(url.pathname.startsWith('/api/stream/'))return route.fulfill({status:200,contentType:'audio/wav',body:wave});
        let data=[];
        if(url.pathname.startsWith('/api/recommendations'))data={songs,artists:[]};
        if(url.pathname.startsWith('/api/related/')){
          suggestionRequests++;
          data=carouselFixture?[songs[3],extraSuggestion]:[songs[3]];
        }
        if(url.pathname==='/api/download/testTrack08'){
          return allowRetriedDownload ? route.fulfill({status:200,contentType:'audio/wav',body:wave})
            : route.fulfill({status:503,json:{detail:'Falla de prueba'}});
        }
        if(url.pathname==='/api/download/testTrack09')return route.fulfill({status:200,contentType:'audio/wav',body:wave});
        if(url.pathname.startsWith('/api/lyrics/'))data={lines:[{text:'Letra de prueba',timeMs:0}],synced:true};
        if(url.pathname==='/api/search/page'){
          const token=url.searchParams.get('continuation')||'';searchPageRequests.push(token);
          const make=(from,count)=>Array.from({length:count},(_,i)=>({id:'pageTrk'+String(from+i).padStart(3,'0'),title:'Resultado '+(from+i),artist:'Artista',duration:60,thumbnail:'logo_complete.png'}));
          data=token===''?{items:make(0,12),continuation:'tok1'}:token==='tok1'?{items:[...make(12,12),make(0,1)[0]],continuation:'tok2'}:{items:make(24,5),continuation:''};
        }
        if(url.pathname.startsWith('/api/artwork'))data={url:''};
        if(url.pathname.startsWith('/api/artist/'))data={name:'Artista de prueba',subscribers:'1 M',photo:'',top_songs:[],albums:[{title:'Álbum uno',year:'2024',browse_id:'MPREalbum0001',thumbnail:'logo_complete.png'},{title:'Álbum dos',year:'2023',browse_id:'MPREalbum0002',thumbnail:'logo_complete.png'}],singles:[{title:'Sencillo uno',year:'',browse_id:'MPREsingle001',thumbnail:'logo_complete.png'}]};
        if(url.pathname==='/api/health')data={status:'ok',downloads_enabled:true,stream_blocked:false};
        if(url.pathname.startsWith('/api/cookies'))data={active:false};
        return route.fulfill({json:data});
      });
      await page.goto(`http://127.0.0.1:${server.address().port}/?verification=1`);
      await page.waitForFunction(()=>typeof storage!=='undefined'&&storage.db&&typeof locatePlayingTrack==='function');
      await page.evaluate(async({songs,base64})=>{
        state.isMuted=true;state.autoplayEnabled=false;
        const blob=new Blob([Uint8Array.from(atob(base64),c=>c.charCodeAt(0))],{type:'audio/wav'});
        for(const song of songs.slice(0,3))await storage.saveOfflineTrack({...song,folder:'Carpeta de prueba'},blob,null);
        await storage.savePlaylist({id:'chill',name:'Chill',tracks:songs.slice(0,2)});
        await storage.savePlaylist({id:'gym',name:'Gym',tracks:[songs[2]]});
        await loadPlaylistView((await storage.getAllPlaylists())[0]);
      },{songs,base64:wave.toString('base64')});
      await page.locator('#library-content-list .row-item').first().click();
      await page.waitForFunction(()=>state.isPlaying&&audio.readyState>=2);
      assert.equal(await page.locator('#library-content-list .download-btn').count(),0,'Track rows have no standalone download action');
      // Los controles de reproducción no van pegados: separación mínima entre cada par de botones visibles (barra compacta).
      const controlGaps=await page.evaluate(()=>{const buttons=[...document.querySelectorAll('.player-controls > button')].filter(button=>getComputedStyle(button).display!=='none');const rects=buttons.map(button=>button.getBoundingClientRect());return rects.slice(1).map((rect,index)=>Math.round(rect.left-rects[index].right));});
      assert(controlGaps.length>=3&&Math.min(...controlGaps)>=(viewport.width>=900?8:4),'Los controles de la barra compacta tienen aire: '+JSON.stringify(controlGaps));
      if(viewport.width>=900){
        await page.locator('#bottom-player .player-right .more-btn').click();
        assert(await page.locator('#track-context-menu').isVisible(),'The compact player menu stays open');
        assert(await page.locator('#ctx-download').isVisible(),'Download is available from the menu');
        assert(await page.locator('#ctx-play').isVisible(),'Play/Pause belongs to the player menu, as in Windows');
        assert(await page.locator('#crossfade-menu-toggle').isVisible(),'Crossfade belongs to the player menu');
        await page.keyboard.press('Escape');
        assert(!(await page.locator('#track-context-menu').isVisible()));
      }
      // Como en Windows (videoAvailable): sin clip de vídeo no se ofrecen ni MP4 ni el botón de vídeo; con clip sí.
      await page.evaluate(()=>playTrack({id:'testTrack00',title:'Canción 0',artist:'Artista 0',duration:60,thumbnail:'logo_complete.png'}));
      await page.waitForFunction(()=>videoClips.get('testTrack00')===true,null,{timeout:8000});
      if(viewport.width>=900)assert(await page.locator('#video-toggle-btn').isVisible(),'Con clip, el botón de vídeo está disponible');
      else assert(!(await page.locator('#video-toggle-btn').evaluate(node=>node.hidden)),'Con clip, el botón de vídeo no se oculta');
      assert(!(await page.locator('.mobile-player-modes button',{hasText:'MP4'}).evaluate(node=>node.classList.contains('mode-unavailable'))),'Con clip, el chip MP4 está disponible');
      await page.evaluate(()=>playTrack({id:'testTrack01',title:'Canción 1',artist:'Artista 1',duration:60,thumbnail:'logo_complete.png'}));
      await page.waitForFunction(()=>videoClips.get('testTrack01')===false,null,{timeout:8000});
      assert(await page.locator('#video-toggle-btn').evaluate(node=>node.hidden),'Sin clip, el botón de vídeo se oculta');
      await page.waitForFunction(()=>document.querySelectorAll('.mobile-player-modes button')[1].classList.contains('mode-unavailable'),null,{timeout:4000});
      await page.evaluate(()=>playTrack({id:'testTrack00',title:'Canción 0',artist:'Artista 0',duration:60,thumbnail:'logo_complete.png'}));
      await page.waitForFunction(()=>!document.getElementById('video-toggle-btn').hidden,null,{timeout:4000});
      // Página de artista: álbumes y sencillos con tarjetas de tamaño fijo (sin estilo salían del tamaño natural de la imagen).
      await page.evaluate(()=>loadArtistView('UCtestartist01','Artista de prueba'));
      await page.waitForFunction(()=>document.querySelectorAll('#artist-albums-row .album-card').length===2);
      const albumBoxes=await page.evaluate(()=>[...document.querySelectorAll('.album-card .album-cover')].map(image=>{const box=image.getBoundingClientRect();return {w:Math.round(box.width),h:Math.round(box.height)};}));
      assert(albumBoxes.length===3&&albumBoxes.every(box=>box.w>=100&&box.w<=220&&Math.abs(box.w-box.h)<=2),'Las portadas de álbum son cuadradas y de tamaño de tarjeta: '+JSON.stringify(albumBoxes));
      await page.evaluate(async()=>{await loadPlaylistView((await storage.getAllPlaylists())[0]);});
      // Mientras carga una canción en línea: el botón cambia su icono por un spinner visible y la fila muestra otro (como en Windows).
      await page.evaluate(async({song})=>{
        await storage.savePlaylist({id:'zz-load',name:'ZZ carga',tracks:[song]});
        await loadPlaylistView((await storage.getAllPlaylists()).find(item=>item.id==='zz-load'));
      },{song:songs[3]});
      await page.locator('#library-content-list .row-item').first().waitFor();
      holdTrack03=true;
      await page.evaluate(song=>{playTrack(song);},songs[3]);
      await page.waitForFunction(()=>document.body.classList.contains('playback-loading'),null,{timeout:6000});
      const loading=await page.evaluate(()=>{
        const button=document.getElementById('play-pause-btn'),ring=getComputedStyle(button,'::after');
        const wrap=document.querySelector('.row-item.playing .row-cover-wrap'),rowRing=wrap&&getComputedStyle(wrap,'::before');
        return {iconOpacity:getComputedStyle(document.getElementById('play-pause-icon')).opacity,ringAnimation:ring.animationName,ringWidth:parseFloat(ring.width),ringBorder:ring.borderTopWidth,rowRingAnimation:rowRing?.animationName||null};
      });
      assert.equal(loading.iconOpacity,'0','El icono de reproducir se oculta mientras carga: '+JSON.stringify(loading));
      assert(loading.ringAnimation==='playback-spin'&&loading.ringWidth>=14&&parseFloat(loading.ringBorder)>=3,'El spinner del botón es claramente visible: '+JSON.stringify(loading));
      assert.equal(loading.rowRingAnimation,'playback-spin','La fila de la canción que carga muestra su spinner: '+JSON.stringify(loading));
      // Gira de verdad (no basta con que tenga nombre de animación): se compara su rotación en dos instantes, también con «reducir animaciones».
      const spinTransform=()=>page.evaluate(()=>getComputedStyle(document.getElementById('play-pause-btn'),'::after').transform);
      for(const reducedMotion of ['no-preference','reduce']){
        await page.emulateMedia({reducedMotion});
        const first=await spinTransform();await page.waitForTimeout(420);const second=await spinTransform();
        assert(first!=='none'&&second!=='none'&&first!==second,'El spinner gira con reducedMotion='+reducedMotion+': '+first+' -> '+second);
      }
      await page.emulateMedia({reducedMotion:'no-preference'});
      releaseTrack03();holdTrack03=false;
      await page.waitForFunction(()=>!document.body.classList.contains('playback-loading'),null,{timeout:8000});
      await page.evaluate(async()=>{
        await new Promise(resolve=>{const request=storage.db.transaction('playlists','readwrite').objectStore('playlists').delete('zz-load');request.onsuccess=resolve;request.onerror=resolve;});
        await loadPlaylistView((await storage.getAllPlaylists())[0]);
      });
      // Se vuelve a la canción guardada con la que seguían los pasos siguientes.
      await page.locator('#library-content-list .row-item').first().click();
      await page.waitForFunction(()=>state.currentTrack?.id==='testTrack00'&&state.isPlaying&&audio.readyState>=2);
      // Scroll infinito (Canciones y también «Todos», el filtro por defecto): se pide la página siguiente, una vez cada una y sin duplicados.
      for(const searchFilter of ['songs','all']){
      searchPageRequests.length=0;
      await page.evaluate(async(searchFilter)=>{state.searchQuery='';loadHomeView();await new Promise(resolve=>setTimeout(resolve,300));await performSearch('prueba',searchFilter);},searchFilter);
      await page.waitForFunction(()=>document.querySelectorAll('#home-dynamic-content .row-item:not(.skeleton)').length>=12);
      for(let attempt=0;attempt<10&&await page.locator('.search-more').count();attempt++){
        await page.locator('.search-more').scrollIntoViewIfNeeded();
        await page.waitForTimeout(350);
      }
      await page.waitForFunction(()=>!document.querySelector('.search-more'),null,{timeout:8000});
      const pagedRows=await page.evaluate(()=>[...document.querySelectorAll('#home-dynamic-content .row-item:not(.skeleton)')].map(row=>row.dataset.trackId));
      assert.equal(pagedRows.length,29,'Las tres páginas se cargan sin duplicar el resultado repetido: '+pagedRows.length);
      assert.equal(new Set(pagedRows).size,29,'Sin filas repetidas');
      assert.deepEqual(searchPageRequests,['','tok1','tok2'],'Cada página se pide una sola vez y se detiene al acabarse');
      await page.waitForTimeout(500);
      assert.equal(searchPageRequests.length,3,'No se piden más páginas al terminar');
      }
      await page.evaluate(async()=>{await loadPlaylistView((await storage.getAllPlaylists())[0]);});
      // Letras como panel lateral: tocar fuera lo cierra (como el Drawer de Windows); dentro del panel y en el reproductor no.
      if(viewport.width>=681){
        const wasExpanded=await page.evaluate(()=>document.body.classList.contains('player-expanded'));
        await page.evaluate(()=>{document.body.classList.remove('player-expanded');renderLyrics({lines:[{text:'Primera línea',timeMs:0},{text:'Segunda línea',timeMs:4000}],synced:true});if(!state.lyricsOpen)document.getElementById('lyrics-toggle-btn').click();});
        await page.waitForFunction(()=>document.getElementById('lyrics-drawer').classList.contains('open'));
        await page.locator('#lyrics-drawer .drawer-title').click();
        await page.locator('#bottom-player .player-controls').click({position:{x:2,y:2},force:true}).catch(()=>{});
        assert(await page.evaluate(()=>state.lyricsOpen&&document.getElementById('lyrics-drawer').classList.contains('open')),'Dentro del panel y en el reproductor, las letras siguen abiertas');
        // En pantallas pequeñas el panel de letras ocupa toda la pantalla: no hay un «fuera» donde tocar.
        const coversScreen=await page.evaluate(()=>document.getElementById('lyrics-drawer').getBoundingClientRect().width>=innerWidth-8);
        if(!coversScreen){
          await page.locator('#main-content').click({position:{x:6,y:6},force:true});
          await page.waitForFunction(()=>!document.getElementById('lyrics-drawer').classList.contains('open'),null,{timeout:4000});
          assert.equal(await page.evaluate(()=>localStorage.getItem('musicbox_lyrics_open')),'0','Cerrar al tocar fuera guarda que están cerradas');
        }else await page.evaluate(()=>document.getElementById('close-lyrics-btn').click());
        if(wasExpanded)await page.evaluate(()=>document.body.classList.add('player-expanded'));
        await page.waitForFunction(()=>!document.documentElement.classList.contains('player-morph'));
      }
      // Letras: el seguimiento automático de la línea activa no pelea con quien desplaza la lista a mano.
      await page.evaluate(()=>{renderLyrics({lines:Array.from({length:70},(_,index)=>({text:'Línea de prueba número '+index,timeMs:index*3000})),synced:true});if(!state.lyricsOpen)document.getElementById('lyrics-toggle-btn').click();});
      await page.waitForFunction(()=>document.getElementById('lyrics-drawer').classList.contains('open'));
      await page.evaluate(()=>{lyricsUserScrollAt=0;updateActiveLyric(0);});
      await page.waitForTimeout(600);
      const lyricsBox=await page.locator('#lyrics-body').boundingBox();
      await page.mouse.move(lyricsBox.x+lyricsBox.width/2,lyricsBox.y+lyricsBox.height/2);
      await page.mouse.wheel(0,500);
      await page.waitForTimeout(250);
      const manualTop=await page.evaluate(()=>document.getElementById('lyrics-body').scrollTop);
      assert(manualTop>100,'El usuario desplazó la letra: '+manualTop);
      await page.evaluate(()=>updateActiveLyric(150000));
      await page.waitForTimeout(700);
      assert(Math.abs(await page.evaluate(()=>document.getElementById('lyrics-body').scrollTop)-manualTop)<4,'Un cambio de línea no arrastra la lista mientras el usuario la desplaza');
      await page.evaluate(()=>{lyricsUserScrollAt=0;delete document.getElementById('lyric-60')?.dataset.highlighted;updateActiveLyric(180000);});
      await page.waitForFunction(top=>Math.abs(document.getElementById('lyrics-body').scrollTop-top)>150,manualTop,{timeout:4000});
      await page.evaluate(()=>{document.getElementById('close-lyrics-btn').click();});
      // La cabecera de Mi música deja a la vista Nueva playlist, Ir a canción actual y Aleatorio; lo demás va en el menú «⋯».
      await page.evaluate(()=>loadLibraryView('songs'));
      await page.locator('.library-actions').waitFor();
      assert.deepEqual(await page.locator('.library-actions > button').evaluateAll(nodes=>nodes.map(node=>node.getAttribute('aria-label'))),['Nueva playlist','Ir a canción actual','Aleatorio','Más opciones'],'Only New playlist, Go to current song, Shuffle and the menu are visible in the header');
      await page.locator('.library-actions').getByRole('button',{name:'Más opciones',exact:true}).click();
      assert.deepEqual(await page.getByRole('menuitem').allTextContents(),['Importar carpeta','Importar canciones','Importar playlist','Actualizar biblioteca'],'The menu has the remaining library actions');
      await page.keyboard.press('Escape');
      assert.equal(await page.getByRole('menuitem').count(),0,'Escape closes the library menu');
      await page.evaluate(async()=>{await loadPlaylistView((await storage.getAllPlaylists())[0]);});
      // Sin playlists, el filtro Playlists ofrece «Importar playlist» centrado en el vacío.
      await page.evaluate(()=>{const real=storage.getAllPlaylists;window.__realPlaylists=real;storage.getAllPlaylists=async()=>[];return loadLibraryView('playlists');});
      await page.locator('#library-content-list .library-empty').waitFor();
      assert.equal(await page.locator('#library-content-list .library-empty strong').textContent(),'No hay playlists todavía');
      const emptyBox=await page.locator('#library-content-list .library-empty-actions button').boundingBox(),listBox=await page.locator('#library-content-list').boundingBox();
      assert.equal(await page.locator('#library-content-list .library-empty-actions button').textContent().then(text=>text.trim()),'Importar playlist');
      assert(Math.abs((emptyBox.x+emptyBox.width/2)-(listBox.x+listBox.width/2))<6,'El botón Importar playlist está centrado');
      await page.locator('#library-content-list .library-empty-actions button').click();
      assert.equal(await page.locator('dialog.musicbox-dialog h2').textContent(),'Importar playlist');
      await page.keyboard.press('Escape');
      await page.evaluate(async()=>{storage.getAllPlaylists=window.__realPlaylists;await loadPlaylistView((await storage.getAllPlaylists())[0]);});
      // Las portadas remotas (googleusercontent) cargan tanto creadas con plantillas como cambiando su src después.
      await page.evaluate(()=>{
        const holder=document.createElement('div');holder.id='remote-cover-test';
        holder.innerHTML='<img id="remote-a" src="https://yt3.googleusercontent.com/cover-a=w120-h120" alt="">';
        document.body.append(holder);
        const late=document.createElement('img');late.id='remote-b';holder.append(late);late.src='https://yt3.googleusercontent.com/cover-b=w120-h120';
      });
      await page.waitForFunction(()=>['remote-a','remote-b'].every(id=>{const image=document.getElementById(id);return image.complete&&image.naturalWidth>0;}),null,{timeout:8000});
      await page.evaluate(()=>document.getElementById('remote-cover-test').remove());
      // Menú de una fila dentro de una playlist: mismas acciones que Windows, sin las del reproductor.
      await page.locator('#library-content-list .row-item .more-btn').first().click();
      assert(await page.locator('#track-context-menu').isVisible(),'Row menu opens');
      assert(!(await page.locator('#ctx-play').isVisible()),'Row menus do not repeat Play');
      assert(!(await page.locator('#crossfade-menu-toggle').isVisible()),'Row menus do not show Crossfade');
      assert(await page.locator('#ctx-move-playlist').isVisible(),'A song inside a playlist can be moved to another one');
      await page.waitForFunction(()=>!document.getElementById('ctx-delete-device').hidden);
      assert(await page.locator('#ctx-delete-device').isVisible(),'Downloaded songs can be deleted from the device');
      await page.locator('#ctx-delete-device').click();
      assert.equal(await page.locator('dialog.musicbox-dialog h2').textContent(),'Eliminar del dispositivo');
      await page.locator('dialog.musicbox-dialog').getByRole('button',{name:'Cancelar',exact:true}).click();
      assert(await page.evaluate(()=>storage.getOfflineTrack(state.currentTrack.id).then(Boolean)),'Cancelling keeps the saved copy');
      await page.locator('#library-content-list .row-item .more-btn').first().click();
      await page.locator('#ctx-move-playlist').click();
      assert.equal(await page.locator('#add-to-playlist-modal .modal-title').textContent(),'Mover a otra playlist');
      await page.locator('#playlists-picker-list .context-item').first().waitFor();
      assert.deepEqual((await page.locator('#playlists-picker-list .context-item span').allTextContents()).map(text=>text.replace(/ \(\d+\)$/,'')),['Gym'],'The source playlist is not offered');
      await page.locator('#cancel-add-playlist-btn').click();
      const moved=await page.evaluate(async()=>{
        const realAll=storage.getAllPlaylists,realSave=storage.savePlaylist,saved=[],track=state.currentTrack;let removed=0;
        storage.getAllPlaylists=async()=>[{id:'src',name:'Origen',tracks:[track]},{id:'dst',name:'Destino',tracks:[]}];
        storage.savePlaylist=async playlist=>{saved.push(playlist);return playlist;};
        try{
          await openAddToPlaylistModal(track,Object.assign(async()=>{removed++;},{playlistId:'src'}));
          const offered=[...document.querySelectorAll('#playlists-picker-list .context-item span')].map(node=>node.textContent);
          document.querySelector('#playlists-picker-list .context-item').click();
          await new Promise(resolve=>setTimeout(resolve,60));
          return {offered,saved:saved.map(playlist=>[playlist.id,playlist.tracks.length]),removed};
        }finally{storage.getAllPlaylists=realAll;storage.savePlaylist=realSave;}
      });
      assert.deepEqual(moved,{offered:['Destino (0)'],saved:[['dst',1]],removed:1},'Moving adds to the target and removes from the source');
      const deleted=await page.evaluate(async()=>{
        const real={confirm:window.confirmAction,offline:storage.getOfflineTrack,remove:storage.removeOfflineTrack,all:storage.getAllPlaylists,save:storage.savePlaylist,fav:storage.isFavorite,view:state.activeView};
        const removed=[],saved=[];
        window.confirmAction=async()=>true;storage.getOfflineTrack=async()=>({audioBlob:{}});storage.removeOfflineTrack=async id=>{removed.push(id);};
        storage.getAllPlaylists=async()=>[{id:'p',tracks:[{id:'local-file-1'},{id:'abcdefghijk'}]}];
        storage.savePlaylist=async playlist=>{saved.push(playlist.tracks.map(item=>item.id));};storage.isFavorite=async()=>false;state.activeView='test';
        try{
          await deleteTrackFromDevice({id:'abcdefghijk',title:'En línea'});
          const online={removed:[...removed],saved:[...saved]};removed.length=0;saved.length=0;
          await deleteTrackFromDevice({id:'local-file-1',title:'Local'});
          return {online,local:{removed,saved}};
        }finally{window.confirmAction=real.confirm;storage.getOfflineTrack=real.offline;storage.removeOfflineTrack=real.remove;storage.getAllPlaylists=real.all;storage.savePlaylist=real.save;storage.isFavorite=real.fav;state.activeView=real.view;}
      });
      assert.deepEqual(deleted,{online:{removed:['abcdefghijk'],saved:[]},local:{removed:['local-file-1'],saved:[['abcdefghijk']]}},
        'An online song keeps its playlists; an imported file leaves them');
      await page.evaluate(()=>openAddToPlaylistModal(state.currentTrack));
      assert((await page.locator('#playlists-picker-list .playlist-picker-cover').first().getAttribute('src')).includes('logo_complete.png'),'Playlist chooser uses its real cover');
      await page.locator('#cancel-add-playlist-btn').click();
      await page.evaluate(async()=>{await loadLibraryView('songs');await locatePlayingTrack();});
      assert.equal(await page.locator('.library-heading h1').textContent(),'Chill','Current song restores its source playlist');
      await page.getByRole('button',{name:'Actualizar playlist',exact:true}).click();
      await page.waitForFunction(()=>!document.querySelector('.refreshing'));
      assert.equal(await page.locator('.library-heading h1').textContent(),'Chill');
      await page.evaluate(()=>loadLibraryView('playlists'));
      const playlistSize=await page.locator('.collection-card').first().boundingBox();
      await page.evaluate(()=>loadLibraryView('folders'));
      const folderSize=await page.locator('.collection-card').first().boundingBox();
      assert.equal(folderSize.width,playlistSize.width);assert.equal(folderSize.height,playlistSize.height);
      await page.evaluate(()=>loadLibraryView('playlists'));
      assert.equal(await page.locator('.collection-card > img').first().evaluate(node=>getComputedStyle(node).objectFit),'contain');
      await page.getByRole('button',{name:'Opciones de playlist',exact:true}).first().click();
      assert.equal(await page.getByRole('menuitem').count(),2);
      assert.equal(await page.locator('[role="menuitem"] img').count(),2,'Every collection action has an icon');
      await page.keyboard.press('Escape');
      assert.equal(await page.getByRole('menu').count(),0,'Escape dismisses the menu');
      if(viewport.width<=680||viewport.height===390){
        // Orden horizontal: Buscar | Música | playlist 1 | playlist 2. Dedo a la derecha desde la primera playlist vuelve a Música.
        await page.evaluate(async()=>{
          const first=(await storage.getAllPlaylists())[0];await loadPlaylistView(first);
          const main=document.getElementById('main-content');const fire=(type,x)=>main.dispatchEvent(new PointerEvent(type,{clientX:x,clientY:320,bubbles:true}));
          fire('pointerdown',60);fire('pointerup',280);
        });
        await page.waitForFunction(()=>activePlaylist===null);
        // Dedo a la izquierda desde Música abre la última playlist visitada.
        await page.evaluate(()=>{const main=document.getElementById('main-content');main.dispatchEvent(new PointerEvent('pointerdown',{clientX:280,clientY:320,bubbles:true}));main.dispatchEvent(new PointerEvent('pointerup',{clientX:60,clientY:320,bubbles:true}));});
        await page.waitForFunction(()=>activePlaylist?.id==='chill');
      }
      await page.evaluate(()=>document.body.classList.add('player-expanded'));
      await page.waitForFunction(()=>document.body.classList.contains('player-expanded')&&!document.documentElement.classList.contains('player-morph'));
      // Con vídeo, la portada pasa a 16:9 y aprovecha el ancho: ya no queda un clip pequeño dentro de un cuadrado con bandas negras.
      const coverBox=()=>page.evaluate(()=>{const box=document.querySelector('.now-playing-cover-wrap').getBoundingClientRect();return {w:Math.round(box.width),h:Math.round(box.height),zoom:document.body.classList.contains('cover-zoom')};});
      const squareCover=await coverBox();
      if(!squareCover.zoom){
        await page.evaluate(()=>paintInlineVideo(true));
        await page.waitForTimeout(350);
        const videoCover=await coverBox();
        await page.evaluate(()=>paintInlineVideo(false));
        await page.waitForTimeout(250);
        assert(Math.abs(videoCover.w/videoCover.h-16/9)<.06,'La portada con vídeo es 16:9: '+JSON.stringify({squareCover,videoCover}));
        assert(videoCover.w>=squareCover.w-1,'El vídeo nunca queda más pequeño que la portada cuadrada: '+JSON.stringify({squareCover,videoCover}));
        if(viewport.width>=900)assert(videoCover.w>=squareCover.w*1.5,'En ventanas anchas el vídeo gana tamaño: '+JSON.stringify({squareCover,videoCover}));
        assert.deepEqual(await coverBox(),squareCover,'Al cerrar el vídeo la portada vuelve a ser cuadrada');
      }
      // La cola abierta desde el reproductor ampliado debe verse: no puede quedar tapada por el propio reproductor.
      await page.evaluate(()=>{if(!document.getElementById('queue-drawer').classList.contains('open'))document.getElementById('queue-toggle-btn').click();});
      await page.waitForFunction(()=>document.getElementById('queue-drawer').classList.contains('open'));
      await page.waitForTimeout(450);
      const queueOnTop=await page.evaluate(()=>{const box=document.getElementById('queue-drawer').getBoundingClientRect();const hit=document.elementFromPoint(box.x+box.width/2,box.y+Math.min(box.height/2,200));return !!hit?.closest('#queue-drawer');});
      assert(queueOnTop,'La cola queda por encima del reproductor ampliado');
      await page.evaluate(()=>document.getElementById('queue-toggle-btn').click());
      await page.waitForFunction(()=>!document.getElementById('queue-drawer').classList.contains('open'));
      if(viewport.width>=900){
        await page.evaluate(()=>{
          localStorage.setItem('musicbox_lyrics_open','1');
          renderLyrics({lines:[{text:'Letra de prueba',timeMs:0}]});
        });
        await page.waitForFunction(()=>document.body.classList.contains('lyrics-open'));
        const source=await page.locator('#player-source').boundingBox();
        const modes=await page.locator('.mobile-player-modes').boundingBox();
        assert(source.x+source.width<=modes.x,'The playlist source and modes do not overlap with lyrics open');
        assert(await page.locator('.mobile-player-modes').evaluate(node=>parseFloat(getComputedStyle(node).borderRadius)>=20),'The mode selector has a rounded frame');
        await fs.mkdir('apps/web/.test-artifacts',{recursive:true});
        await page.screenshot({path:`apps/web/.test-artifacts/player-lyrics-${viewport.width}.png`,animations:'disabled'});
        await page.locator('.expanded-player-actions .more-btn:visible').click();
        assert(await page.locator('#track-context-menu').isVisible());
        await page.keyboard.press('Escape');
        assert(await page.evaluate(()=>document.body.classList.contains('player-expanded')),'Escape closes the menu before the player');
      }
      const coverBefore=await page.locator('.now-playing-cover-wrap').boundingBox();
      await page.locator('.now-playing-cover-wrap').click();
      await page.waitForTimeout(380);
      const coverFull=await page.locator('.now-playing-cover-wrap').boundingBox();
      const drawer=await page.locator('#lyrics-drawer').boundingBox();
      const expectedWidth=viewport.width>=900&&drawer ? drawer.x-19 : viewport.width;
      assert(Math.abs(coverFull.x)<2&&Math.abs(coverFull.y)<2&&Math.abs(coverFull.width-expectedWidth)<2,'Immersive cover leaves room for lyrics on desktop');
      // La portada inmersiva aprovecha el alto libre: entre el título y los controles no debe quedar un hueco muerto.
      const tallHeight=Math.max(viewport.height,900);
      await page.setViewportSize({width:viewport.width,height:tallHeight});
      await page.waitForTimeout(700);
      const gaps=await page.evaluate(()=>{const info=document.querySelector('.now-playing-info').getBoundingClientRect(),center=document.querySelector('.player-center').getBoundingClientRect();return {gap:Math.round(center.top-info.bottom),viewport:innerWidth+'x'+innerHeight};});
      await page.setViewportSize(viewport);
      await page.waitForTimeout(500);
      assert(gaps.gap<90,'Sin hueco muerto bajo el título en el reproductor ampliado: '+JSON.stringify(gaps));
      await page.keyboard.press('Escape');
      await page.waitForTimeout(380);
      assert(await page.evaluate(()=>document.body.classList.contains('player-expanded')),'Escape restores artwork before closing the player');
      assert(Math.abs((await page.locator('.now-playing-cover-wrap').boundingBox()).width-coverBefore.width)<2,'Artwork restores its original frame');
      await page.evaluate(()=>{state.crossfadeSeconds=7;setWebCrossfadeEnabled(false);setWebCrossfadeEnabled(true);});
      assert.equal(await page.evaluate(()=>state.crossfadeSeconds),7,'Quick crossfade toggle preserves duration');
      await page.evaluate(()=>setWebCrossfadeEnabled(false));
      await page.evaluate(()=>renderLyrics({lines:[]}));await page.waitForTimeout(280);
      assert(!(await page.locator('.mobile-player-modes button').nth(2).evaluate(node=>node.classList.contains('mode-unavailable'))),'A brief absence does not make the lyrics chip blink away');
      await page.waitForFunction(()=>document.querySelectorAll('.mobile-player-modes button')[2].classList.contains('mode-unavailable'),null,{timeout:4000});
      await page.evaluate(()=>renderLyrics({lines:[{text:'Lyric ready',timeMs:0}]}));
      assert(await page.locator('.mobile-player-modes button').nth(2).evaluate(node=>parseFloat(getComputedStyle(node).transitionDuration)>0),'Lyrics availability animates instead of replacing the chip');
      await page.evaluate(()=>audio.dispatchEvent(new Event('loadedmetadata')));
      assert(await page.locator('.mobile-player-modes button').nth(2).evaluate(node=>!node.classList.contains('mode-unavailable')),'A metadata update does not hide an available lyrics chip');
      const play=await page.locator('#play-pause-btn').boundingBox();
      assert(play&&play.x>=0&&play.x+play.width<=viewport.width,'Expanded controls fit');
      const timeline=await page.locator('.progress-container').boundingBox();
      assert(timeline&&timeline.width>100&&timeline.height>0&&timeline.y>=0&&timeline.y+timeline.height<=viewport.height,'Expanded timeline is visible');
      await fs.mkdir('apps/web/.test-artifacts',{recursive:true});
      await page.screenshot({path:`apps/web/.test-artifacts/player-${viewport.width}.png`,animations:'disabled'});
      await page.evaluate(()=>document.body.classList.remove('player-expanded'));
      await page.evaluate(()=>{
        window.savedQueue=[...state.queue];
        state.queue=Array.from({length:20},(_,i)=>({...state.currentTrack,id:'queue'+i,title:'Queue '+i}));
        renderQueue();document.getElementById('queue-toggle-btn').click();
        const body=document.getElementById('queue-body');body.scrollTop=body.scrollHeight;
      });
      await page.waitForTimeout(300);
      const queueBottom=await page.locator('#queue-body .queue-row').last().boundingBox();
      const queueViewport=await page.locator('#queue-body').boundingBox();
      assert(queueBottom.y+queueBottom.height<=queueViewport.y+queueViewport.height+1,'Final queue row is fully visible');
      await page.evaluate(()=>{document.getElementById('close-queue-btn').click();state.queue=savedQueue;renderQueue();});
      await page.evaluate(async()=>{
        state.autoplayEnabled=true;state.repeatMode='off';const pl=(await storage.getAllPlaylists()).find(row=>row.id==='gym');await loadPlaylistView(pl);playbackSource=pl;state.queue=[...pl.tracks];state.queueIndex=0;await checkAutoplayQueue();
      });
      assert.equal(await page.evaluate(()=>state.queue.at(-1).id),songs[3].id,'Autoplay appends related songs');
      await page.evaluate(async()=>{
        const tracks=Array.from({length:500},(_,index)=>({id:'stress'+String(index).padStart(5,'0'),title:'Track '+index,artist:'Test',thumbnail:'logo_complete.png'}));
        await loadPlaylistView({id:'stress',name:'Large isolated playlist',tracks});
        document.getElementById('library-content-list').scrollToTrack('stress00499');
      });
      assert(await page.locator('#library-content-list .row-item').count()<50,'Large playlists render only the visible range');
      assert.equal(await page.locator('[data-track-id="stress00499"]').count(),1,'A far-away current track can be revealed');
      if(viewport.width===1180){
        carouselFixture=true;
        await page.locator('#close-lyrics-btn').click();
        await page.evaluate(async song=>{
          await storage.savePlaylist({id:'carousel-test',name:'Prueba de sugerencias',tracks:[song]});
          await loadPlaylistView((await storage.getAllPlaylists()).find(playlist=>playlist.id==='carousel-test'));
        },songs[0]);
        const banner=page.getByRole('region',{name:'Para esta playlist'});
        await banner.getByRole('button',{name:'Ver sugerencia 2'}).click();
        await page.waitForFunction(()=>document.querySelectorAll('.suggestions-page')[1]?.getAttribute('aria-current')==='true');
        const requestsBeforeRefresh=suggestionRequests;
        await banner.getByRole('button',{name:'Actualizar sugerencias'}).click();
        await page.waitForFunction(()=>!document.querySelector('.playlist-suggestions').hasAttribute('aria-busy'));
        assert(suggestionRequests>requestsBeforeRefresh,'Refresh fetches suggestions again');
        await page.screenshot({path:'apps/web/.test-artifacts/playlist-suggestions.png',animations:'disabled'});
        await banner.getByRole('button',{name:'Agregar Otra sugerencia a esta playlist'}).click();
        await page.waitForFunction(async()=>(await storage.getAllPlaylists()).find(playlist=>playlist.id==='carousel-test').tracks.length===2);
        await page.waitForFunction(()=>document.querySelectorAll('.suggestion-card').length===1);
        await banner.getByRole('button',{name:'Ocultar sugerencias'}).click();
        assert(!(await banner.locator('.suggestions-carousel').isVisible()));
        await banner.getByRole('button',{name:'Mostrar sugerencias'}).click();
        assert(await banner.locator('.suggestions-carousel').isVisible());
        await page.getByRole('button',{name:'Opciones de playlist',exact:true}).click();
        await page.getByRole('menuitem',{name:'Configurar playlist',exact:true}).click();
        await page.getByRole('switch',{name:'Descargar canciones nuevas'}).check();
        await page.getByRole('button',{name:'Guardar',exact:true}).click();
        await page.waitForFunction(async()=>(await storage.getAllPlaylists()).find(playlist=>playlist.id==='carousel-test').autoDownload);
        carouselFixture=false;
        await page.waitForFunction(()=>!reconcilingDownloads);
        await page.evaluate(async song=>{
          await storage.savePlaylist({id:'download-test',name:'Prueba offline',autoDownload:true,tracks:[
            {...song,id:'testTrack08',title:'Descarga fallida'},{...song,id:'testTrack09',title:'Descarga válida'}
          ]});
          await runAutoDownloads();
        },songs[0]);
        assert(await page.evaluate(async()=>(await storage.getOfflineTrack('testTrack09'))?.audioBlob instanceof Blob),'Auto-download continues after one track fails');
        await context.setOffline(true);
        allowRetriedDownload=true;
        await page.evaluate(()=>runAutoDownloads());
        assert(!(await page.evaluate(()=>storage.getOfflineTrack('testTrack08'))),'Pending downloads wait for the connection');
        await context.setOffline(false);
        await page.evaluate(()=>runAutoDownloads());
        assert(await page.evaluate(async()=>(await storage.getOfflineTrack('testTrack08'))?.audioBlob instanceof Blob),'A pending track is saved when the connection returns');
        await page.evaluate(async song=>{
          state.crossfadeSeconds=0;
          await playTrack({...song,id:'testTrack06'});
        },songs[0]);
        await page.waitForFunction(()=>state.isPlaying && audio.currentTime>.1);
        assert.equal(normalStreamRequests,2,'An online song uses the MusicBox stream and retries one server failure');
        assert.equal(await page.locator('iframe').count(),0,'Normal audio does not create a YouTube iframe');
        await page.evaluate(async({songs})=>{
          state.autoplayEnabled=false;state.crossfadeSeconds=3;
          state.queue=[songs[0],{...songs[1],id:'slowTest001'}];state.queueIndex=0;await playTrack(state.queue[0],true);
        },{songs});
        await page.waitForFunction(()=>audio.readyState>=3&&preparedNext!==null);
        assert(await page.evaluate(()=>audio.currentTime<10),'Streaming preload starts near the beginning of the current song');
        await page.evaluate(async()=>{audio.currentTime=58;await prepareWebFade();});
        assert(await page.evaluate(()=>state.queueIndex===0&&!outgoingAudio&&!audio.paused),'Slow next stream does not interrupt the current song or start a silent fade');
        releaseSlow();
        await page.evaluate(async({songs})=>{state.autoplayEnabled=false;state.crossfadeSeconds=3;state.queue=songs.slice(0,2);state.queueIndex=0;await playTrack(state.queue[0],true);},{songs});
        await page.waitForFunction(()=>audio.readyState>=3&&state.isPlaying);
        await page.evaluate(async()=>{audio.currentTime=55;await prepareWebFade();});
        await page.waitForFunction(()=>preparedNext?.media.readyState>=3);
        const fallback=await page.evaluate(async()=>{
          const playing=audio;preparedNext.media.play=()=>Promise.reject(new Error('Synthetic next-player failure'));
          audio.currentTime=58;await prepareWebFade();
          return audio===playing&&state.queueIndex===0&&!audio.paused&&outgoingAudio===null;
        });
        assert(fallback,'An unavailable next player preserves the current audio and queue index');
        await page.evaluate(async()=>{audio.currentTime=55;await prepareWebFade();});
        await page.waitForFunction(()=>preparedNext?.media.readyState>=3);
        await page.evaluate(async()=>{audio.currentTime=58;await prepareWebFade();});
        await page.waitForFunction(()=>outgoingAudio!==null&&state.queueIndex===1);
        const gains=await page.evaluate(()=>({sum:audio.volume+outgoingAudio.volume,target:state.isMuted?0:state.volume}));
        assert(Math.abs(gains.sum-gains.target)<.01,'Crossfade keeps complementary gain');
        await page.evaluate(()=>seekTo(10));
        assert.equal(await page.evaluate(()=>outgoingAudio===null&&preparedNext===null),true,'Seek cancels outgoing audio');
        await page.evaluate(async()=>{state.queueIndex=0;await playTrack(state.queue[0],true);});
        await page.waitForFunction(()=>audio.readyState>=3&&state.isPlaying);
        await page.evaluate(async()=>{audio.currentTime=55;await prepareWebFade();});
        await page.waitForFunction(()=>preparedNext?.media.readyState>=3);
        await page.evaluate(async()=>{audio.currentTime=58;await prepareWebFade();audio.pause();});
        await page.waitForFunction(()=>outgoingAudio===null);
        assert(await page.evaluate(()=>audio.paused),'Pause releases the outgoing engine without relying on animation frames');
      }
      assert.deepEqual(errors,[],`No JavaScript errors at ${viewport.width}`);
      if(viewport.width===390){
        await page.evaluate(async()=>{await navigator.serviceWorker.register('/sw.js');await navigator.serviceWorker.ready;});
        await page.waitForFunction(()=>navigator.serviceWorker.controller);
        await context.setOffline(true);await page.reload();
        await page.waitForFunction(()=>typeof storage!=='undefined'&&storage.db&&typeof locatePlayingTrack==='function');
        await page.evaluate(async()=>{state.isMuted=true;state.autoplayEnabled=false;const tracks=await storage.getAllOfflineTracks();await playTrack(tracks[0]);});
        await page.waitForFunction(()=>state.isOfflinePlayback&&audio.readyState>=2);
        assert.equal(await page.evaluate(()=>audio.src.startsWith('blob:')),true,'Offline shell and downloaded music still work');
      }
      console.log(`OK ${viewport.width}x${viewport.height}: source, refresh, cards, navigation, expanded player, autoplay`);
      await context.close();
    }
  }finally{await browser.close();await new Promise(resolve=>server.close(resolve));}
})().catch(error=>{console.error(error);server.close();process.exitCode=1;});
