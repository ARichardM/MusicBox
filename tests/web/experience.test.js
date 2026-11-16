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
      const slowGate=new Promise(resolve=>releaseSlow=resolve);
      await page.route('**/www.gstatic.com/**',route=>route.abort());
      await page.route('**/www.youtube.com/**',route=>route.abort());
      await page.route('**/api/**',async route=>{
        const url=new URL(route.request().url());
        if(url.pathname==='/api/stream/slowTest001')await slowGate;
        if(url.pathname.startsWith('/api/stream/'))return route.fulfill({status:200,contentType:'audio/wav',body:wave});
        let data=[];
        if(url.pathname.startsWith('/api/recommendations'))data={songs,artists:[]};
        if(url.pathname.startsWith('/api/related/'))data=[songs[3]];
        if(url.pathname.startsWith('/api/lyrics/'))data={lines:[{text:'Letra de prueba',timeMs:0}],synced:true};
        if(url.pathname.startsWith('/api/artwork'))data={url:''};
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
      const coverBefore=await page.locator('.now-playing-cover-wrap').boundingBox();
      await page.locator('.now-playing-cover-wrap').click();
      await page.waitForTimeout(380);
      const coverFull=await page.locator('.now-playing-cover-wrap').boundingBox();
      assert(Math.abs(coverFull.x)<2&&Math.abs(coverFull.y)<2&&Math.abs(coverFull.width-viewport.width)<2,'Immersive cover reaches the top and both screen edges');
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
