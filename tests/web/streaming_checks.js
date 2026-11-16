/* Opt-in live-provider checks. Isolated DB, no account; unlike fixtures these can fail upstream. */
const assert=require('node:assert/strict');
const fs=require('node:fs/promises');
const {chromium}=require(process.env.MUSICBOX_PLAYWRIGHT_MODULE||'playwright');
const base=process.env.MUSICBOX_TEST_URL||'http://127.0.0.1:8018';
(async()=>{
  const browser=await chromium.launch({headless:true});const reports=[];
  try{
    for(const viewport of [{width:390,height:844},{width:1280,height:800}]){
      const context=await browser.newContext({viewport,serviceWorkers:'block'});
      const page=await context.newPage();
      await page.goto(base+'/?verification=1');
      await page.waitForFunction(()=>typeof storage!=='undefined'&&storage.db&&typeof prepareWebFade==='function');
      await page.evaluate(async()=>{
        state.autoplayEnabled=false;state.isMuted=true;state.crossfadeSeconds=4;
        const tracks=await Promise.all(['6DeDzsCGbsQ','2tw2QDSeBQQ'].map(async id=>{
          const response=await fetch('/api/search?q='+encodeURIComponent('https://www.youtube.com/watch?v='+id)+'&type=songs');
          if(!response.ok)throw new Error('Live search failed');return(await response.json())[0];
        }));
        state.queue=tracks;state.queueIndex=0;await playTrack(tracks[0],true);
        document.body.classList.add('player-expanded');
      });
      await page.waitForFunction(()=>audio.readyState>=3&&preparedNext?.media.readyState>=3,{},{timeout:45000});
      const result=await page.evaluate(async()=>{
        const chips=document.querySelector('.mobile-player-modes');const buttons=[...chips.children];
        let replacements=0;const observer=new MutationObserver(records=>replacements+=records.filter(row=>row.type==='childList').length);
        observer.observe(chips,{childList:true,subtree:true});
        const known=buttons[1];const samples=[];
        audio.muted=true;preparedNext.media.muted=true;state.isMuted=false;state.volume=.4;audio.volume=.4;
        const timer=setInterval(()=>samples.push({overlap:!!outgoingAudio,out:outgoingAudio?.volume||0,incoming:audio.volume}),40);
        audio.currentTime=audio.duration-6;
        await new Promise(resolve=>setTimeout(resolve,8500));
        clearInterval(timer);observer.disconnect();audio.pause();
        const overlap=samples.filter(row=>row.overlap);
        return {width:innerWidth,replacements,sameVideoChip:known===chips.children[1],overlap:overlap.length,
          gainCrossed:overlap.some(row=>row.out>.05&&row.incoming>.05),advanced:state.queueIndex===1,released:!outgoingAudio};
      });
      assert.equal(result.width,viewport.width);assert(result.sameVideoChip);assert.equal(result.replacements,0);
      assert(result.overlap>20&&result.gainCrossed&&result.advanced&&result.released,JSON.stringify(result));
      reports.push(result);console.log(JSON.stringify(result));await context.close();
    }
    await fs.mkdir('apps/web/.test-artifacts',{recursive:true});
    await fs.writeFile('apps/web/.test-artifacts/live-streaming-results.json',JSON.stringify(reports,null,2));
  }finally{await browser.close();}
})().catch(error=>{console.error(error);process.exitCode=1;});
