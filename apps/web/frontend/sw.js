const CACHE_NAME = "musicbox-cache-v5";
const SHELL_ASSETS = [
  "./",
  "./index.html",
  "./landing.html",
  "./style.css",
  "./app.js",
  "./manifest.webmanifest",
  "./favicon.ico",
  "./logo_cube.png",
  "./logo_wave.png",
  "./icons/play.svg",
  "./icons/pause.svg",
  "./icons/next.svg",
  "./icons/previous.svg",
  "./icons/shuffle.svg",
  "./icons/repeat.svg",
  "./icons/repeat_one.svg",
  "./icons/volume.svg",
  "./icons/volume_muted.svg",
  "./icons/heart.svg",
  "./icons/heart_filled.svg",
  "./icons/lyrics.svg",
  "./icons/queue.svg",
  "./icons/download.svg",
  "./icons/downloaded.svg",
  "./icons/search.svg",
  "./icons/playlist.svg",
  "./icons/music.svg",
  "./icons/history.svg",
  "./icons/sidebar_collapse.svg",
  "./icons/sidebar_expand.svg",
  "./icons/cookie.svg",
  "./icons/plus.svg",
  "./icons/back.svg",
  "./icons/more.svg",
  "./icons/moon.svg",
  "./icons/sun.svg",
  "./icons/close.svg",
  "./icons/trash.svg"
];

self.addEventListener("install", (event) => {
  event.waitUntil(
    caches.open(CACHE_NAME).then((cache) => {
      return cache.addAll(SHELL_ASSETS).catch((err) => {
        console.warn("Algunos assets no se pudieron pre-cachear:", err);
      });
    })
  );
  self.skipWaiting();
});

self.addEventListener("activate", (event) => {
  event.waitUntil(
    caches.keys().then((keys) => {
      return Promise.all(
        keys.map((key) => {
          if (key !== CACHE_NAME) {
            return caches.delete(key);
          }
        })
      );
    })
  );
  self.clients.claim();
});

self.addEventListener("fetch", (event) => {
  const url = new URL(event.request.url);

  // Ignorar peticiones a otros orígenes (Firebase, Google Auth, YouTube CDN, etc.)
  if (url.origin !== self.location.origin) {
    return;
  }

  // No interceptar llamadas de streaming
  if (url.pathname.includes("/api/stream/")) {
    return;
  }

  // API calls: Network first con fallback JSON
  if (url.pathname.includes("/api/")) {
    event.respondWith(
      fetch(event.request).catch(() => {
        return new Response(JSON.stringify({ error: "Offline" }), {
          headers: { "Content-Type": "application/json" }
        });
      })
    );
    return;
  }

  // Páginas HTML (navegación): Network first para asegurar que siempre ven la última versión
  if (event.request.mode === "navigate" || url.pathname.endsWith(".html") || url.pathname.endsWith("/")) {
    event.respondWith(
      fetch(event.request)
        .then((networkResponse) => {
          if (networkResponse && networkResponse.status === 200) {
            const clone = networkResponse.clone();
            caches.open(CACHE_NAME).then((cache) => cache.put(event.request, clone));
          }
          return networkResponse;
        })
        .catch(() => caches.match(event.request) || caches.match("./index.html"))
    );
    return;
  }

  // Shell & assets estáticos: Cache first con revalidación
  event.respondWith(
    caches.match(event.request).then((cachedResponse) => {
      if (cachedResponse) {
        fetch(event.request).then((networkResponse) => {
          if (networkResponse && networkResponse.status === 200) {
            caches.open(CACHE_NAME).then((cache) => cache.put(event.request, networkResponse));
          }
        }).catch(() => {});
        return cachedResponse;
      }
      return fetch(event.request);
    })
  );
});
