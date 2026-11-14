/* Same grow-only per-device counters as desktop click_stats.py. */
let webStatsOwner = null;
let webStatsCache = {};
let statsUnflushed = 0;
function statsStorageKey() { return 'musicbox_stats:' + (verificationMode ? 'verification:' : '') + (state.user?.uid || 'guest'); }
function getWebStats() {
  const owner=statsStorageKey();
  if (owner !== webStatsOwner) {
    webStatsOwner=owner;
    try {webStatsCache=JSON.parse(localStorage.getItem(owner) || '{}');} catch {webStatsCache={};}
  }
  return webStatsCache;
}
function mergeWebStats(remote) {
  webStatsCache=MusicBoxCore.mergeStats(remote || {},getWebStats());
  localStorage.setItem(statsStorageKey(),JSON.stringify(webStatsCache));
}
function recordWebStats(track, millis=0, start=false) {
  const stats=getWebStats();
  const local=track.id?.startsWith('local:');
  const titleKey=String(track.title || '').normalize('NFKD').replace(/[\u0300-\u036f]/g,'').toLowerCase().replace(/[(\[][^)\]]*[)\]]/g,' ').replace(/[\W_]+/g,' ').trim();
  const key=!local && track.id ? 'yt:'+track.id : 't:'+titleKey;
  stats.v=1; stats.devices ||= {}; stats.daily ||= {}; stats.listening ||= {}; stats.titles ||= {};
  const counts=stats.devices[webDeviceId] ||= {};
  const daily=(stats.daily[webDeviceId] ||= {})[key] ||= {};
  const row=(stats.listening[webDeviceId] ||= {})[key] ||= {title:track.title,artist:track.artist || '',thumbnail:track.thumbnail || '',ms:0,plays:0};
  row.title=track.title; row.artist=track.artist || ''; row.thumbnail=track.thumbnail || ''; row.ms=(row.ms || 0)+Math.max(0,Math.round(millis));
  stats.titles[key]=track.title;
  if (start) { counts[key]=(counts[key] || 0)+1; const day=new Date().toISOString().slice(0,10);daily[day]=(daily[day] || 0)+1;row.plays=(row.plays || 0)+1; }
  statsUnflushed+=millis;
  if (start || statsUnflushed>=10000) { statsUnflushed=0;localStorage.setItem(statsStorageKey(),JSON.stringify(stats));scheduleCloudSave(); }
}
function webStatsRows() {
  const stats=getWebStats(), rows={};
  for (const bucket of Object.values(stats.listening || {})) for(const [key,value] of Object.entries(bucket)) {
    const row=rows[key] ||= {id:key.startsWith('yt:')?key.slice(3):'',key,title:value.title || stats.titles?.[key] || '',artist:value.artist || '',thumbnail:value.thumbnail || 'logo_cube.png',plays:0,listenedMs:0};
    row.listenedMs+=Number(value.ms) || 0;
  }
  for (const bucket of Object.values(stats.devices || {})) for(const [key,count] of Object.entries(bucket)) {
    const row=rows[key] ||= {id:key.startsWith('yt:')?key.slice(3):'',key,title:stats.titles?.[key] || key,artist:'',thumbnail:'logo_cube.png',plays:0,listenedMs:0};
    row.plays+=Number(count) || 0;
  }
  return Object.values(rows).sort((a,b)=>b.plays-a.plays || b.listenedMs-a.listenedMs);
}
window.addEventListener('pagehide',()=>{if(webStatsOwner)localStorage.setItem(webStatsOwner,JSON.stringify(webStatsCache));});
