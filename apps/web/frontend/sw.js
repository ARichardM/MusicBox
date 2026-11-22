const CACHE_NAME = "musicbox-cache-v2.4.10";
const SHELL_ASSETS = [
  "./",
  "./index.html",
  "./landing.html",
  "./style.css",
  "./app.js",
  "./ytmedia.js",
  "./core.js",
  "./account.js",
  "./images.js",
  "./parity.js",
  "./stats.js",
  "./experience.js",
  "./mixbar.js",
  "./session.js",
  "./pages.js",
  "./selects.js",
  "./motion.js",
  "./pages.css",
  "./experience.css",
  "./icons/settings.svg",
  "./i18n.js",
  "./manifest.webmanifest",
  "./favicon.ico",
  "./logo_cube.png",
  "./logo_wave.png",
  "./logo_complete.png",
  "./icons/account.svg",
  "./icons/back.svg",
  "./icons/check.svg",
  "./icons/chevron.svg",
  "./icons/close.svg",
  "./icons/collapse.svg",
  "./icons/cookie.svg",
  "./icons/copy.svg",
  "./icons/crop.svg",
  "./icons/download.svg",
  "./icons/downloaded.svg",
  "./icons/expand.svg",
  "./icons/expand_window.svg",
  "./icons/external.svg",
  "./icons/filter.svg",
  "./icons/folder.svg",
  "./icons/forward.svg",
  "./icons/google.svg",
  "./icons/heart.svg",
  "./icons/heart_filled.svg",
  "./icons/history.svg",
  "./icons/image.svg",
  "./icons/info.svg",
  "./icons/link.svg",
  "./icons/locate.svg",
  "./icons/logout.svg",
  "./icons/lyrics.svg",
  "./icons/moon.svg",
  "./icons/more.svg",
  "./icons/music.svg",
  "./icons/next.svg",
  "./icons/next_page.svg",
  "./icons/paste.svg",
  "./icons/pause.svg",
  "./icons/play.svg",
  "./icons/playlist.svg",
  "./icons/plus.svg",
  "./icons/prev_page.svg",
  "./icons/previous.svg",
  "./icons/queue.svg",
  "./icons/refresh.svg",
  "./icons/repeat.svg",
  "./icons/repeat_one.svg",
  "./icons/retry.svg",
  "./icons/save.svg",
  "./icons/scan.svg",
  "./icons/search.svg",
  "./icons/shuffle.svg",
  "./icons/sidebar_collapse.svg",
  "./icons/sidebar_expand.svg",
  "./icons/sort.svg",
  "./icons/sparkle.svg",
  "./icons/stop.svg",
  "./icons/sun.svg",
  "./icons/trash.svg",
  "./icons/video.svg",
  "./icons/volume.svg",
  "./icons/volume_muted.svg",
  "./icons/warning.svg"
];

self.addEventListener('install', event => {
  event.waitUntil(caches.open(CACHE_NAME).then(async cache => {
    await cache.addAll(SHELL_ASSETS);
    try { const config=await fetch('./runtime-config.js'); if(config.ok)await cache.put('./runtime-config.js',config); } catch {}
  }).then(() => self.skipWaiting()));
});
self.addEventListener('activate', event => {
  event.waitUntil(caches.keys().then(keys => Promise.all(keys.filter(key => key.startsWith('musicbox-cache-') && key !== CACHE_NAME).map(key => caches.delete(key)))).then(() => self.clients.claim()));
});
self.addEventListener('fetch', event => {
  const url = new URL(event.request.url);
  if (url.origin !== self.location.origin || event.request.method !== 'GET' || url.pathname.includes('/api/stream/')) return;
  if (url.pathname.includes('/api/')) {
    event.respondWith(fetch(event.request).catch(() => new Response(JSON.stringify({detail:'Sin conexión'}), {status:503,headers:{'Content-Type':'application/json'}})));
    return;
  }
  const navigation = event.request.mode === 'navigate';
  event.respondWith((async () => {
    const cache = await caches.open(CACHE_NAME);
    try {
      const response = await fetch(event.request);
      if (response.ok) await cache.put(event.request,response.clone());
      return response;
    } catch {
      const cached = await cache.match(event.request,{ignoreSearch:true});
      if (cached) return cached;
      if(url.pathname.endsWith('/runtime-config.js'))return new Response('window.MUSICBOX_FIREBASE_CONFIG=null;', {headers:{'Content-Type':'text/javascript'}});
      if (navigation) return await cache.match('./index.html');
      return new Response('Sin conexión',{status:503});
    }
  })());
});
