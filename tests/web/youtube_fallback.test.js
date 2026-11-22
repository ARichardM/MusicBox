/* Servidor bloqueado por YouTube (503 en /api/stream y /api/download, como en producción en la 2.4.8):
   la web sigue con el reproductor oficial de YouTube y la auto-descarga se pausa con aviso.
   Necesita conexión a YouTube; el servidor local de MusicBox es simulado. */
const assert=require('node:assert/strict');
const http=require('node:http');
const fs=require('node:fs/promises');
const path=require('node:path');
const {chromium}=require(process.env.MUSICBOX_PLAYWRIGHT_MODULE||'playwright');
const frontend=path.resolve('apps/web/frontend');
const songs=[{id:'qp0AktOIAag',title:'Zenzenzense - movie ver.',artist:'RADWIMPS',duration:286,thumbnail:'logo_complete.png'},
  {id:'Mw66DyjQxBI',title:'前前前世 (original ver.)',artist:'RADWIMPS',duration:276,thumbnail:'logo_complete.png'}];
const blocked=['aaaaaaaaaa1','aaaaaaaaaa2','aaaaaaaaaa3'].map(id=>({id,title:id,artist:'Prueba'}));
const hits={stream:0,download:0};
const json=(res,status,body)=>{res.writeHead(status,{'Content-Type':'application/json'});res.end(JSON.stringify(body));};
const server=http.createServer(async(req,res)=>{
  try{
    const url=new URL(req.url,'http://localhost');
    if(url.pathname==='/runtime-config.js'){res.setHeader('Content-Type','text/javascript');return res.end('window.MUSICBOX_CONFIG={}');}
    if(url.pathname.startsWith('/api/stream/')){hits.stream++;return json(res,503,{detail:'YouTube no permitió obtener el audio desde el servidor.'});}
    if(url.pathname.startsWith('/api/download/')){hits.download++;return json(res,503,{detail:'YouTube no permitió descargar desde el servidor.'});}
    if(url.pathname==='/api/video-source/qp0AktOIAag')return json(res,200,{id:'PDSkFeMVNFs'});
    if(url.pathname.startsWith('/api/video-source/'))return json(res,200,{id:''});
    if(url.pathname.startsWith('/api/recommendations'))return json(res,200,{songs:[],artists:[]});
    if(url.pathname.startsWith('/api/lyrics/'))return json(res,200,{lines:[]});
    if(url.pathname.startsWith('/api/'))return json(res,200,[]);
    const file=path.resolve(frontend,'.'+(url.pathname==='/'?'/index.html':decodeURIComponent(url.pathname)));
    if(!file.startsWith(frontend+path.sep)){res.writeHead(403);return res.end();}
    res.setHeader('Content-Type',({'.html':'text/html','.js':'text/javascript','.css':'text/css','.png':'image/png','.svg':'image/svg+xml'})[path.extname(file)]||'application/octet-stream');
    res.end(await fs.readFile(file));
  }catch{res.writeHead(404);res.end();}
});
(async()=>{
  await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
  const browser=await chromium.launch({headless:process.env.MUSICBOX_VISIBLE!=='1',args:['--autoplay-policy=no-user-gesture-required']});
  try{
    const page=await (await browser.newContext({viewport:{width:1372,height:1000},locale:'es-CL',serviceWorkers:'block'})).newPage();
    const errors=[];page.on('pageerror',error=>errors.push(error.message));
    await page.route(/firebasejs|firebaseio|identitytoolkit/,route=>route.abort());
    await page.goto(`http://127.0.0.1:${server.address().port}/?verification=1`);
    await page.waitForFunction(()=>storage.db&&typeof playTrack==='function');
    const playing=(extra='true',timeout=45000)=>page.waitForFunction(`audio.isYouTube&&state.isPlaying&&(${extra})`,null,{timeout});

    await page.evaluate(async songs=>{state.queue=songs.slice();state.queueIndex=0;await playTrack(songs[0],true);},songs);
    await playing('audio.currentTime>1');
    assert(!/No se pudo abrir el audio/.test(await page.evaluate(()=>document.querySelector('.toast')?.textContent||'')),'Sin aviso de error al seguir con YouTube');
    assert.equal(await page.evaluate(()=>Math.round(audio.duration)),286,'La duración llega a la barra');

    await page.evaluate(()=>togglePlayPause());
    await page.waitForFunction(()=>!state.isPlaying&&audio.paused);
    const paused=await page.evaluate(()=>audio.currentTime);await page.waitForTimeout(1200);
    assert(Math.abs(await page.evaluate(()=>audio.currentTime)-paused)<.3,'En pausa no avanza');
    await page.evaluate(()=>togglePlayPause());await page.waitForFunction(()=>state.isPlaying);
    await page.evaluate(()=>seekTo(60));await page.waitForFunction(()=>audio.currentTime>59&&audio.currentTime<70,null,{timeout:15000});

    await page.evaluate(()=>{document.body.classList.add('player-expanded');setInlineVideo(true);});
    await playing("state.videoDockOpen&&audio._id==='PDSkFeMVNFs'&&audio.currentTime>55");
    const frame=await page.locator('#local-media-player iframe').boundingBox();
    assert(frame.width>200&&frame.height>100,'El MP4 se ve en el marco del reproductor');
    await page.evaluate(()=>setInlineVideo(false));
    await page.waitForFunction(()=>!state.videoDockOpen&&state.isPlaying,null,{timeout:10000});

    const streamRequests=hits.stream;
    await page.evaluate(()=>playNext());
    await playing("state.currentTrack.id==='Mw66DyjQxBI'&&audio.currentTime>1&&audio.currentTime<20");
    assert.equal(hits.stream,streamRequests,'Con el servidor bloqueado, la siguiente canción no lo vuelve a esperar');

    await page.evaluate(async tracks=>{await storage.savePlaylist({id:'bloqueada',name:'Bloqueada',autoDownload:true,tracks});await runAutoDownloads();},[...songs,...blocked]);
    assert.equal(hits.download,0,'Sin descargas web habilitadas la auto-descarga no consulta al servidor');
    await page.evaluate(async()=>{setWebDownloadsEnabled(true);await runAutoDownloads();});
    assert.equal(hits.download,3,'La auto-descarga se pausa tras tres rechazos seguidos');
    assert(/Auto-descarga en pausa/.test(await page.evaluate(()=>[...document.querySelectorAll('.toast')].map(t=>t.textContent).join(' '))),'Avisa de la pausa');
    assert.deepEqual(errors,[]);
    console.log('OK: servidor bloqueado → YouTube (audio, pausa, salto, MP4, siguiente) y auto-descarga en pausa');
  }finally{await browser.close();server.close();}
})().catch(error=>{console.error(error);process.exit(1);});
