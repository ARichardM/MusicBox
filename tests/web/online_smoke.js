const {chromium} = require('playwright');
const fs = require('node:fs');
const path = require('node:path');
(async () => {
 const browser=await chromium.launch({headless:false});
 const context=await browser.newContext({viewport:{width:1280,height:800},locale:'es-CL',serviceWorkers:'block'});
 const page=await context.newPage(); const errors=[];
 page.on('console',message=>{if(message.type()==='warning'||message.type()==='error') errors.push(message.text());});
 try {
  await page.goto((process.env.MUSICBOX_TEST_URL || 'http://127.0.0.1:8015')+'/?verification=1');
  await page.waitForFunction(()=>typeof storage !== 'undefined' && storage.db);
  await page.locator('#home-search-input').fill(process.env.MUSICBOX_SMOKE_QUERY || 'https://www.youtube.com/watch?v=yKNxeF4KMsY');
  await page.locator('#search-action-btn').click();
  await page.locator('.row-item[data-track-id]').first().waitFor({timeout:45000});
  await page.locator('.row-item[data-track-id]').first().click();
  let playing=false;
  try {await page.waitForFunction(()=>state.isPlaying && ytPlayer?.getCurrentTime?.()>1,{},{timeout:25000});playing=true;} catch {}
  const result=await page.evaluate(()=>({track:state.currentTrack,ytReady,isPlaying:state.isPlaying,time:ytPlayer?.getCurrentTime?.(),playerState:ytPlayer?.getPlayerState?.(),db:storage.db.name,api:apiUrl('api/search?q=test&type=songs')}));
  result.playingVerified=playing;result.diagnostics=errors;
  fs.mkdirSync('apps/web/.test-artifacts',{recursive:true});
  fs.writeFileSync(path.resolve('apps/web/.test-artifacts/online-smoke.json'),JSON.stringify(result,null,2));
  console.log(JSON.stringify(result,null,2));
 } finally {await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});
