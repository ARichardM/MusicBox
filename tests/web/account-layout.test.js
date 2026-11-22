/* Real browser and media, with an in-memory account. Never connects to Firebase. */
const assert=require('node:assert/strict');
const http=require('node:http');
const fs=require('node:fs/promises');
const path=require('node:path');
const {chromium}=require(process.env.MUSICBOX_PLAYWRIGHT_MODULE||'playwright');
const frontend=path.resolve('apps/web/frontend');
const artifacts=path.resolve(process.env.MUSICBOX_TEST_ARTIFACTS||'apps/web/.test-artifacts');
const videoFile=process.env.MUSICBOX_VIDEO_FIXTURE||path.join(artifacts,'verification-video.mp4');
const tracks=Array.from({length:70},(_,i)=>({id:'verify'+String(i).padStart(5,'0'),title:'Canción '+(i+1),artist:'Artista de prueba',duration:12,thumbnail:'logo_complete.png'}));
let cloud={playlists:{playlists:{Favoritos:[],Mix:tracks.slice(0,2).map(row=>'youtube:'+row.id)},
  onlineTracks:Object.fromEntries(tracks.map(row=>[row.id,row])),entryAddedAt:{Mix:{'youtube:verify00000':100,'youtube:verify00001':200}}},preferences:{crossfadeSeconds:4,autoplay:false,librarySort:'recent:1'}};
let releaseLock=null,lock=Promise.resolve();
const pages=[];
const server=http.createServer(async(req,res)=>{
  try{
    const url=new URL(req.url,'http://localhost');
    if(url.pathname==='/runtime-config.js'){res.setHeader('Content-Type','text/javascript');return res.end('window.MUSICBOX_CONFIG={}');}
    if(url.pathname.startsWith('/api/stream/')){res.setHeader('Content-Type','video/mp4');return res.end(await fs.readFile(videoFile));}
    if(url.pathname.startsWith('/api/')){res.setHeader('Content-Type','application/json');return res.end(JSON.stringify(url.pathname.includes('/lyrics/')?{lines:[{text:'Letra de prueba',timeMs:0}],synced:true}:url.pathname.includes('/recommendations')?{songs:[],artists:[]}:{}));}
    const file=path.resolve(frontend,'.'+(url.pathname==='/'?'/index.html':decodeURIComponent(url.pathname)));
    if(!file.startsWith(frontend+path.sep)){res.writeHead(403);return res.end();}
    res.setHeader('Content-Type',({'.html':'text/html','.js':'text/javascript','.css':'text/css','.png':'image/png','.svg':'image/svg+xml'})[path.extname(file)]||'application/octet-stream');
    res.end(await fs.readFile(file));
  }catch{res.writeHead(404);res.end();}
});
(async()=>{
  await fs.mkdir(artifacts,{recursive:true});await fs.access(videoFile);
  await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
  const browser=await chromium.launch({headless:process.env.MUSICBOX_VISIBLE!=='1'});
  const errors=[];
  try{
    for(let i=0;i<2;i++){
      const context=await browser.newContext({viewport:{width:1180,height:760},locale:'es-CL',serviceWorkers:'block'}),page=await context.newPage();pages.push(page);
      page.on('pageerror',error=>errors.push(error.message));
      await page.route('https://**/*',route=>route.abort());
      await page.exposeFunction('fakeRead',()=>JSON.stringify(cloud));
      await page.exposeFunction('fakeWrite',payload=>{cloud=JSON.parse(payload);for(const target of pages)setTimeout(()=>target.evaluate(payload=>window.accountSnapshot?.(payload),payload).catch(()=>{}),0);});
      await page.exposeFunction('fakeBegin',async()=>{const before=lock;lock=new Promise(resolve=>releaseLock=resolve);await before;});
      await page.exposeFunction('fakeEnd',()=>releaseLock?.());
      await page.goto(`http://127.0.0.1:${server.address().port}/?verification=1`);
      await page.waitForFunction(()=>storage.db&&typeof watchCloudAccount==='function');
      await page.evaluate(async()=>{
        state.user={uid:'isolated-account',authenticated:true,name:'Verificación'};state.isMuted=true;
        const snapshot=payload=>({exists:true,metadata:{hasPendingWrites:false},data:()=>({payload})});
        const ref={collection(){return this;},doc(){return this;},get:async()=>snapshot(await fakeRead()),
          set:async()=>{},onSnapshot(callback){window.accountSnapshot=payload=>callback(snapshot(payload));return ()=>{window.accountSnapshot=null;};}};
        fbDb={collection:()=>ref,runTransaction:async fn=>{await fakeBegin();try{return await fn({get:ref.get,set:(_ref,value)=>{window.transactionPayload=value.payload;}}).then(async result=>{if(window.transactionPayload){await fakeWrite(window.transactionPayload);window.transactionPayload=null;}return result;});}finally{await fakeEnd();}}};
        await syncWithFirestore('isolated-account',true);clearTimeout(cloudSaveTimer);
      });
    }
    const [first,second]=pages;
    await first.evaluate(() => {
      state.user.photo = '/logo_complete.png';
      checkAuthSession();
    });
    await first.waitForFunction(() => ['sidebar-account-avatar', 'modal-user-avatar'].every(id => {
      const image = document.getElementById(id).querySelector('img');
      return image?.naturalWidth > 0 && !image.hidden;
    }));
    assert.deepEqual(await first.evaluate(() => ['sidebar-account-avatar', 'modal-user-avatar'].map(id => {
      const style = getComputedStyle(document.getElementById(id));
      return [style.width, style.borderRadius, style.overflow];
    })), [['32px','50%','hidden'], ['60px','50%','hidden']]);
    await first.evaluate(() => {
      state.user.photo = '/missing-account-photo.png';
      checkAuthSession();
    });
    await first.waitForFunction(() => ['sidebar-account-avatar', 'modal-user-avatar'].every(id => {
      const avatar = document.getElementById(id);
      return !avatar.querySelector('img') && !avatar.querySelector('span').hidden;
    }));
    await first.evaluate(async track=>{const playlist=(await storage.getAllPlaylists()).find(row=>row.name==='Mix');playlist.tracks.push(track);await storage.savePlaylist(playlist);accountPreference('crossfadeSeconds',7);recordAccountQuery('Banda de prueba');recordAccountPlay(track);clearTimeout(cloudSaveTimer);await pushStateToFirestore();},tracks[2]);
    await second.waitForFunction(async()=>{const playlist=(await storage.getAllPlaylists()).find(row=>row.name==='Mix');return playlist?.tracks.some(row=>row.id==='verify00002')&&state.crossfadeSeconds===7&&accountActivity().queries?.['Banda de prueba'];});
    assert(cloud.playlists.entryAddedAt.Mix['youtube:verify00002']>=200,'New memberships have a shared date');
    assert(cloud.activity.played.verify00002,'Played history belongs to the account');
    for (const page of pages) {
      const order = await page.evaluate(async () => {
        await loadPlaylistView((await libraryData()).playlists.find(row=>row.name==='Mix'));
        return currentLibraryTracks.map(row=>row.id);
      });
      assert.deepEqual(order, ['verify00002','verify00001','verify00000']);
    }
    await first.evaluate(() => {
      accountPreference('librarySort','recent:0');
      clearTimeout(cloudSaveTimer);
      return pushStateToFirestore();
    });
    await second.waitForFunction(() => currentLibraryTracks[0]?.id === 'verify00000');
    await first.evaluate(() => {
      accountPreference('librarySort','recent:1');
      clearTimeout(cloudSaveTimer);
      return pushStateToFirestore();
    });
    await second.waitForFunction(() => currentLibraryTracks[0]?.id === 'verify00002');
    // An open collection and active sort survive a metadata-only account update.
    await first.evaluate(async tracks=>{await storage.savePlaylist({id:'long',name:'Larga',tracks});await loadPlaylistView((await storage.getAllPlaylists()).find(row=>row.id==='long'));clearTimeout(cloudSaveTimer);},tracks);
    await first.locator('#main-content').hover();await first.mouse.wheel(0,650);
    await first.waitForFunction(()=>document.querySelector('.collection-controls')?.classList.contains('controls-hidden'));
    await first.waitForTimeout(400);assert((await first.locator('.collection-controls').boundingBox()).height<2,'Controls stay collapsed after the layout settles');
    await first.mouse.wheel(0,-160);
    await first.waitForFunction(()=>!document.querySelector('.collection-controls')?.classList.contains('controls-hidden'));
    await first.evaluate(()=>showToast('No se pudo reproducir. Revisa tu conexión.',{kind:'error',retry:()=>{window.retried=true;}}));
    assert.equal(await first.locator('.toast').getAttribute('role'),'alert');
    await first.getByRole('button',{name:'REINTENTAR',exact:true}).click();
    await first.waitForFunction(()=>window.retried);
    // Keep the same native video node through zoom, lyrics and resizing.
    await first.evaluate(async track=>{state.isMuted=true;state.queue=[track];state.queueIndex=0;await playTrack(track,true);await setInlineVideo(true);document.body.classList.add('player-expanded');window.testMedia=audio;},tracks[0]);
    await first.waitForFunction(()=>audio.readyState>=2&&audio.videoWidth>0&&document.body.classList.contains('player-expanded'));
    first.setDefaultTimeout(8000);
    for(const viewport of [{width:1180,height:760},{width:900,height:620},{width:390,height:844}]){
      console.log('Checking video + lyrics',viewport);
      await first.setViewportSize(viewport);
      await first.evaluate(()=>{renderLyrics({lines:[{text:'Letra de prueba',timeMs:0}],synced:true});if(!state.lyricsOpen)document.getElementById('lyrics-toggle-btn').click();});
      await first.waitForTimeout(350);
      await first.locator('.now-playing-cover-wrap').click();
      await first.waitForFunction(()=>document.body.classList.contains('cover-zoom'));
      const samples=await first.evaluate(()=>new Promise(resolve=>{
        const rows=[],until=performance.now()+450;
        const tick=()=>{const cover=document.querySelector('.now-playing-cover-wrap').getBoundingClientRect();rows.push({w:cover.width,h:cover.height,display:getComputedStyle(audio).display,same:audio===window.testMedia});if(performance.now()<until)requestAnimationFrame(tick);else resolve(rows);};tick();
      }));
      assert(samples.every(row=>row.w>0&&row.h>0&&row.display!=='none'&&row.same),'Video surface stays mounted and visible');
      if(viewport.width>=900){
        const cover=await first.locator('.now-playing-cover-wrap').boundingBox(),drawer=await first.locator('#lyrics-drawer').boundingBox();
        assert(cover.x+cover.width<=drawer.x-8,'Zoom recalculates the width beside the lyrics');
      }
      const overlay=await first.locator('.now-playing-cover-wrap').evaluate(node=>getComputedStyle(node,'::after').backgroundImage);
      assert(overlay.includes('linear-gradient'),'Video blends into the controls');
      await first.screenshot({path:path.join(artifacts,`account-video-${viewport.width}.png`)});
      await first.locator('.now-playing-cover-wrap').click();
      await first.waitForFunction(()=>!document.body.classList.contains('cover-zoom'));
    }
    await first.evaluate(()=>document.body.classList.remove('player-expanded'));
    await first.waitForFunction(()=>!document.documentElement.classList.contains('player-morph'));
    for(const viewport of [{width:1180,height:760},{width:390,height:844}]){
      await first.setViewportSize(viewport);
      await first.evaluate(()=>document.getElementById('settings-btn').click());
      await first.locator('#settings-modal').waitFor({state:'visible'});
      const geometry=await first.evaluate(()=>{
        const selectors=['.settings-user-card','#settings-appearance-block','#playback-settings-card','#settings-downloads-block'];
        return selectors.map(selector=>{const r=document.querySelector(selector).getBoundingClientRect();return {selector,y:r.y,bottom:r.bottom,h:r.height};});
      });
      assert(geometry.every(row=>row.h>40),'Settings sections measure their content');
      for(let i=1;i<geometry.length;i++)assert(geometry[i-1].bottom<=geometry[i].y,'Settings stay in a single non-overlapping column');
      await first.screenshot({path:path.join(artifacts,`settings-account-${viewport.width}.png`)});
      await first.locator('#playback-settings-card').scrollIntoViewIfNeeded();
      await first.screenshot({path:path.join(artifacts,`settings-playback-${viewport.width}.png`)});
      await first.locator('#close-settings-btn').click();
    }
    // Web downloads are disabled by default: buttons are blocked with clear messaging while local import stays active.
    await first.setViewportSize({width:1180,height:760});
    await first.evaluate(async()=>{await loadPlaylistView((await libraryData()).playlists.find(row=>row.name==='Mix'));});
    assert(await first.locator('.playlist-auto-download').isDisabled(),'Auto-download pill is disabled on web by default');
    await first.locator('#library-content-list .row-item .more-btn').first().click();
    await first.locator('#track-context-menu').waitFor({state:'visible'});
    assert.equal(await first.locator('#ctx-download').getAttribute('aria-disabled'),'true','Context menu download item is marked disabled');
    assert(await first.locator('#ctx-download-badge').isVisible(),'Context menu download item shows app-only badge');
    await first.locator('#ctx-download').click();
    assert(/Android o Windows/.test(await first.evaluate(()=>[...document.querySelectorAll('.toast')].map(t=>t.textContent).join(' '))),'Clicking blocked download explains to use Android or Windows');
    await first.evaluate(()=>loadDownloadsView());
    await first.locator('.dl-unavailable-notice').waitFor({state:'visible'});
    assert(await first.locator('#dl-start').isDisabled(),'Main Download button is disabled on web');
    assert(await first.locator('#dl-links').isDisabled(),'Links input is disabled on web');
    assert(!(await first.locator('#dl-import-songs').isDisabled()),'Local song import stays enabled on web');
    assert(!(await first.locator('#dl-import-folder').isDisabled()),'Local folder import stays enabled on web');
    await first.screenshot({path:path.join(artifacts,'downloads-blocked-1180.png')});
    await first.setViewportSize({width:390,height:844});
    await first.screenshot({path:path.join(artifacts,'downloads-blocked-390.png')});
    assert.deepEqual(errors,[]);console.log('OK: settings geometry, blocked web downloads, two account sessions, shared dates/preferences/activity, collection scroll, retry notices, MP4 + lyrics + zoom at three sizes');
  }catch(error){
    await pages[0].screenshot({path:path.join(artifacts,'account-layout-failure.png')});
    console.log(await pages[0].evaluate(()=>Object.fromEntries(['.player-left','.now-playing-cover-wrap','#lyrics-drawer','.player-center'].map(selector=>{const r=document.querySelector(selector).getBoundingClientRect();return [selector,{x:r.x,y:r.y,w:r.width,h:r.height}];}))));
    throw error;
  }finally{await browser.close();server.close();}
})().catch(error=>{console.error(error);process.exitCode=1;server.close();});
