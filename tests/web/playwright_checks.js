const { chromium } = require(process.env.MUSICBOX_PLAYWRIGHT_MODULE || 'playwright');
const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
let base = process.env.MUSICBOX_TEST_URL;
const artifacts = path.resolve('apps/web/.test-artifacts');
fs.mkdirSync(artifacts,{recursive:true});

(async () => {
  let server;
  if (!base) {
    const root=path.resolve('apps/web/frontend');
    server=http.createServer((req,res)=>{
      const url=new URL(req.url,'http://localhost');
      if(url.pathname==='/runtime-config.js'){res.setHeader('Content-Type','text/javascript');return res.end('window.MUSICBOX_CONFIG={}');}
      const file=path.resolve(root,'.'+(url.pathname==='/'?'/index.html':decodeURIComponent(url.pathname)));
      if(!file.startsWith(root+path.sep)){res.writeHead(403);return res.end();}
      const types={'.html':'text/html','.js':'text/javascript','.css':'text/css','.svg':'image/svg+xml','.png':'image/png'};
      fs.readFile(file,(error,data)=>{if(error){res.writeHead(404);return res.end();}res.setHeader('Content-Type',types[path.extname(file)]||'application/octet-stream');res.end(data);});
    });
    await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
    base='http://127.0.0.1:'+server.address().port;
  }
  const browser = await chromium.launch({headless:true});
  const reports = [];
  try {
    for (const viewport of [{width:390,height:844},{width:1280,height:800},{width:360,height:740},{width:768,height:1024}]) {
      const context = await browser.newContext({viewport,locale:'es-CL',serviceWorkers:viewport.width === 390 ? 'allow' : 'block'});
      const page = await context.newPage();
      const errors=[]; page.on('pageerror',error=>errors.push(error.message));
      // Firebase is disabled in verification; avoid loading third-party identity scripts in tests.
      await page.route('**/www.gstatic.com/firebasejs/**',route=>route.abort());
      if(server) await page.route('**/api/**',route=>{
        const url=new URL(route.request().url());
        const media=path.join(artifacts,'verification-video.mp4');
        if(url.pathname.startsWith('/api/stream/')&&url.searchParams.get('video')==='1'&&fs.existsSync(media)){
          return route.fulfill({status:200,contentType:'video/mp4',path:media});
        }
        const songs=[1,2,3,6].map(i=>({id:'testTrack0'+i,title:'Canción de prueba '+i,artist:'MusicBox',duration:30,thumbnail:'logo_cube.png',kind:'song'}));
        return route.fulfill({json:url.pathname.startsWith('/api/recommendations')?{songs,artists:[]}:url.pathname.startsWith('/api/lyrics/')?{lines:[]}:url.pathname.startsWith('/api/artwork')?{url:''}:[]});
      });
      await page.goto(base + '/?verification=1');
      await page.waitForFunction(() => typeof storage !== 'undefined' && storage.db && typeof importLocalFiles === 'function');
      const result = await page.evaluate(fs.readFileSync('tests/web/browser_checks.js','utf8'));
      if (viewport.width === 1280) reports.push({test:'Firestore simulado',...await page.evaluate(fs.readFileSync('tests/web/cloud_checks.js','utf8'))});
      assert.equal(errors.length,0,JSON.stringify(errors));
      reports.push({...result,errors});
      await page.evaluate(async () => {
        document.getElementById('toast-container')?.replaceChildren();
        document.getElementById('close-queue-btn').click(); document.getElementById('close-lyrics-btn').click();
        state.searchQuery=''; await loadHomeView();
        document.getElementById('main-content').scrollTop=0;
        document.querySelector('.main-surface').scrollLeft=0;
      });
      await page.locator('#for-you-grid .for-you-card[data-track-id]').first().waitFor({timeout:45000});
      await page.locator('#main-content img').evaluateAll(images => Promise.all(images.filter(img => {
        const rect=img.getBoundingClientRect(); return rect.top < innerHeight && rect.bottom > 0 && rect.left < innerWidth;
      }).map(img => img.complete ? Promise.resolve() : new Promise(resolve => {img.addEventListener('load',resolve,{once:true});img.addEventListener('error',resolve,{once:true});setTimeout(resolve,8000);} ))));
      await page.screenshot({path:path.join(artifacts,`home-${viewport.width}.png`),fullPage:true,animations:'disabled'});
      if (viewport.width <= 680) {
        await page.locator('.player-left').click();
        await page.screenshot({path:path.join(artifacts,`player-${viewport.width}.png`),fullPage:true,animations:'disabled'});
        const controls = await page.locator('#next-btn').boundingBox();
        assert(controls.x >= 0 && controls.x + controls.width <= viewport.width);
      }
      if (viewport.width === 390) {
        await page.evaluate(async () => { await navigator.serviceWorker.register('/sw.js'); await navigator.serviceWorker.ready; });
        await page.reload();
        await page.waitForFunction(()=>navigator.serviceWorker.controller && typeof storage !== 'undefined' && storage.db);
        await context.setOffline(true);
        await page.reload();
        await page.waitForFunction(()=>typeof storage !== 'undefined' && storage.db);
        const offline = await page.evaluate(async () => {
          state.isMuted=true; const tracks=await storage.getAllOfflineTracks(); await playTrack(tracks[0]);
          return {db:storage.db.name,count:tracks.length,offline:state.isOfflinePlayback,src:audio.src};
        });
        assert(offline.count>=3 && offline.offline && offline.src.startsWith('blob:'),'Offline shell and local audio');
        await page.waitForFunction(()=>audio.readyState>=2);
        reports.push({test:'PWA sin conexión',passed:true,database:offline.db});
        await context.setOffline(false);
      }
      if (viewport.width === 1280 && fs.existsSync(path.join(artifacts,'verification-video.mp4'))) {
        await page.locator('#nav-library').click();
        const chooserPromise=page.waitForEvent('filechooser');
        await page.locator('.library-actions').getByRole('button',{name:'Más opciones',exact:true}).click();
        await page.getByRole('menuitem',{name:'Importar canciones',exact:true}).click();
        const chooser=await chooserPromise; await chooser.setFiles(path.join(artifacts,'verification-video.mp4'));
        await page.waitForFunction(async()=> (await storage.getAllOfflineTracks()).some(t=>t.id.startsWith('local:')));
        await page.evaluate(async()=>{state.isMuted=true;const track=(await storage.getAllOfflineTracks()).find(t=>t.id.startsWith('local:'));await playTrack(track);});
        await page.waitForFunction(()=>audio.videoWidth>0);
        await page.locator('#video-toggle-btn').click();
        await page.locator('.now-playing-cover-wrap #local-media-player').waitFor({state:'visible'});
        assert(await page.locator('.now-playing-cover-wrap #local-media-player').isVisible(),'Imported MP4 plays in the artwork frame');
        await page.locator('.now-playing-cover-wrap').click();
        await page.waitForFunction(()=>document.body.classList.contains('cover-zoom'));
        await page.setViewportSize({width:900,height:620});
        await page.waitForFunction(()=>document.getElementById('local-media-player').getBoundingClientRect().width<=900);
        const zoomedVideo=await page.locator('.now-playing-cover-wrap #local-media-player').boundingBox();
        assert(zoomedVideo?.width>0 && zoomedVideo.width<=900,'Inline video zoom fits a smaller window');
        await page.keyboard.press('Escape');
        await page.setViewportSize(viewport);
        await page.evaluate(()=>audio.pause());
        const downloadPromise=page.waitForEvent('download');
        await page.evaluate(()=>openTrackContextMenu({clientX:40,clientY:40},state.currentTrack));
        await page.locator('#ctx-save-file').click();
        const exported=await downloadPromise;const exportPath=path.join(artifacts,'exported-video.mp4');await exported.saveAs(exportPath);
        assert.equal(fs.statSync(exportPath).size,fs.statSync(path.join(artifacts,'verification-video.mp4')).size,'Exported file keeps all original bytes');
        reports.push({test:'Importación y reproducción de MP4 local',passed:true});
        reports.push({test:'Guardar archivo MP4 desde el navegador',passed:true});
        if(server){
          await page.evaluate(async()=>{
            const track=(await storage.getAllOfflineTracks()).find(t=>t.id==='testTrack01');
            await playTrack(track);
          });
          await page.waitForFunction(()=>audio.readyState>=2&&audio.videoWidth===0);
          await page.evaluate(()=>setInlineVideo(true));
          await page.waitForFunction(()=>audio.videoWidth>0&&document.querySelector('.now-playing-cover-wrap').contains(audio));
          await page.evaluate(()=>setInlineVideo(false));
          await page.waitForFunction(()=>audio.readyState>=2&&audio.videoWidth===0&&audio.src.startsWith('blob:'));
          reports.push({test:'Cambiar de audio guardado a vídeo por streaming y volver',passed:true});
        }
      }
      await context.close();
    }
    fs.writeFileSync(path.join(artifacts,'browser-results.json'),JSON.stringify(reports,null,2));
    console.log(JSON.stringify(reports.map(r=>({test:r.test,viewport:r.viewport,passed:r.passed,errors:r.errors})),null,2));
  } finally { await browser.close(); if(server)await new Promise(resolve=>server.close(resolve)); }
})().catch(error=>{console.error(error);process.exitCode=1;});
