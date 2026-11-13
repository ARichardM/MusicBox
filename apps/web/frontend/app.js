/**
 * MusicBox Web - Frontend Principal con Paridad 100% Desktop
 * Motor de Reproducción Híbrido (Online YouTube IFrame + Offline IndexedDB)
 */

// --- Base de Datos IndexedDB (MusicBoxDB) ---
class MusicBoxStorage {
  constructor() {
    this.dbName = "MusicBoxDB";
    this.version = 2;
    this.db = null;
  }

  async init() {
    return new Promise((resolve, reject) => {
      const request = indexedDB.open(this.dbName, this.version);

      request.onupgradeneeded = (e) => {
        const db = e.target.result;
        if (!db.objectStoreNames.contains("offline_tracks")) {
          db.createObjectStore("offline_tracks", { keyPath: "id" });
        }
        if (!db.objectStoreNames.contains("favorites")) {
          db.createObjectStore("favorites", { keyPath: "id" });
        }
        if (!db.objectStoreNames.contains("playlists")) {
          db.createObjectStore("playlists", { keyPath: "id" });
        }
        if (!db.objectStoreNames.contains("history")) {
          const histStore = db.createObjectStore("history", { keyPath: "id" });
          histStore.createIndex("playedAt", "playedAt", { unique: false });
        }
      };

      request.onsuccess = (e) => {
        this.db = e.target.result;
        resolve();
      };

      request.onerror = (e) => reject(e);
    });
  }

  // --- Descargas Offline ---
  async saveOfflineTrack(track, audioBlob, lyricsData) {
    return new Promise((resolve, reject) => {
      const tx = this.db.transaction("offline_tracks", "readwrite");
      const store = tx.objectStore("offline_tracks");
      const data = {
        id: track.id,
        title: track.title,
        artist: track.artist,
        album: track.album || "",
        duration: track.duration || 0,
        thumbnail: track.thumbnail || "",
        audioBlob: audioBlob,
        sizeBytes: audioBlob ? audioBlob.size : 0,
        lyrics: lyricsData || null,
        savedAt: Date.now()
      };
      const req = store.put(data);
      req.onsuccess = () => resolve(data);
      req.onerror = (e) => reject(e);
    });
  }

  async getOfflineTrack(id) {
    return new Promise((resolve) => {
      const tx = this.db.transaction("offline_tracks", "readonly");
      const store = tx.objectStore("offline_tracks");
      const req = store.get(id);
      req.onsuccess = () => resolve(req.result || null);
      req.onerror = () => resolve(null);
    });
  }

  async getAllOfflineTracks() {
    return new Promise((resolve) => {
      const tx = this.db.transaction("offline_tracks", "readonly");
      const store = tx.objectStore("offline_tracks");
      const req = store.getAll();
      req.onsuccess = () => resolve(req.result || []);
      req.onerror = () => resolve([]);
    });
  }

  async removeOfflineTrack(id) {
    return new Promise((resolve, reject) => {
      const tx = this.db.transaction("offline_tracks", "readwrite");
      const store = tx.objectStore("offline_tracks");
      const req = store.delete(id);
      req.onsuccess = () => resolve();
      req.onerror = (e) => reject(e);
    });
  }

  async clearAllOfflineTracks() {
    return new Promise((resolve, reject) => {
      const tx = this.db.transaction("offline_tracks", "readwrite");
      const store = tx.objectStore("offline_tracks");
      const req = store.clear();
      req.onsuccess = () => resolve();
      req.onerror = (e) => reject(e);
    });
  }

  // --- Favoritos ---
  async addFavorite(track) {
    if (!track || !track.id) return;
    return new Promise((resolve, reject) => {
      const tx = this.db.transaction("favorites", "readwrite");
      const store = tx.objectStore("favorites");
      store.put({ ...track, addedAt: Date.now() });
      tx.oncomplete = () => resolve(true);
      tx.onerror = (e) => reject(e);
    });
  }

  async toggleFavorite(track) {
    const isFav = await this.isFavorite(track.id);
    return new Promise((resolve, reject) => {
      const tx = this.db.transaction("favorites", "readwrite");
      const store = tx.objectStore("favorites");
      if (isFav) {
        store.delete(track.id);
        if (typeof pushStateToFirestore === "function") setTimeout(pushStateToFirestore, 600);
        resolve(false);
      } else {
        store.put({ ...track, addedAt: Date.now() });
        if (typeof pushStateToFirestore === "function") setTimeout(pushStateToFirestore, 600);
        resolve(true);
      }
    });
  }

  async isFavorite(id) {
    return new Promise((resolve) => {
      const tx = this.db.transaction("favorites", "readonly");
      const store = tx.objectStore("favorites");
      const req = store.get(id);
      req.onsuccess = () => resolve(!!req.result);
      req.onerror = () => resolve(false);
    });
  }

  async getAllFavorites() {
    return new Promise((resolve) => {
      const tx = this.db.transaction("favorites", "readonly");
      const store = tx.objectStore("favorites");
      const req = store.getAll();
      req.onsuccess = () => resolve(req.result || []);
      req.onerror = () => resolve([]);
    });
  }

  // --- Historial ---
  async recordHistory(track) {
    if (!track || !track.id) return;
    return new Promise((resolve) => {
      const tx = this.db.transaction("history", "readwrite");
      const store = tx.objectStore("history");
      const getReq = store.get(track.id);
      getReq.onsuccess = () => {
        const existing = getReq.result;
        const plays = existing ? (existing.plays || 1) + 1 : 1;
        store.put({
          ...track,
          plays,
          playedAt: Date.now()
        });
        resolve();
      };
      getReq.onerror = () => resolve();
    });
  }

  async getRecentHistory(limit = 40) {
    return new Promise((resolve) => {
      const tx = this.db.transaction("history", "readonly");
      const store = tx.objectStore("history");
      const req = store.getAll();
      req.onsuccess = () => {
        const items = req.result || [];
        items.sort((a, b) => (b.playedAt || 0) - (a.playedAt || 0));
        resolve(items.slice(0, limit));
      };
      req.onerror = () => resolve([]);
    });
  }

  async clearHistory() {
    return new Promise((resolve, reject) => {
      const tx = this.db.transaction("history", "readwrite");
      const store = tx.objectStore("history");
      const req = store.clear();
      req.onsuccess = () => resolve();
      req.onerror = (e) => reject(e);
    });
  }

  // --- Playlists ---
  async getAllPlaylists() {
    return new Promise((resolve) => {
      const tx = this.db.transaction("playlists", "readonly");
      const store = tx.objectStore("playlists");
      const req = store.getAll();
      req.onsuccess = () => resolve(req.result || []);
      req.onerror = () => resolve([]);
    });
  }

  async savePlaylist(playlist) {
    return new Promise((resolve, reject) => {
      const tx = this.db.transaction("playlists", "readwrite");
      const store = tx.objectStore("playlists");
      const req = store.put(playlist);
      req.onsuccess = () => resolve(playlist);
      req.onerror = (e) => reject(e);
    });
  }
}

// --- Estado Global ---
const state = {
  user: JSON.parse(localStorage.getItem("musicbox_user") || "null"),
  currentTrack: null,
  queue: [],
  queueIndex: -1,
  isPlaying: false,
  isShuffle: false,
  repeatMode: "off", // 'off' | 'all' | 'one'
  volume: 0.8,
  isMuted: false,
  activeView: "home",
  lyrics: [],
  lyricsOpen: false,
  queueOpen: false,
  videoDockOpen: true,
  isOfflinePlayback: false,
  searchQuery: "",
  searchFilter: "all",
  theme: localStorage.getItem("musicbox_theme") || "dark",
  accent: localStorage.getItem("musicbox_accent") || "blue",
  language: localStorage.getItem("musicbox_lang") || "auto",
  sidebarCollapsed: localStorage.getItem("musicbox_sidebar_collapsed") === "true",
  cachedRecommendations: null,
  contextMenuTrack: null
};

const storage = new MusicBoxStorage();
const audio = new Audio(); // Motor para reproducción offline (Blobs IndexedDB)
let ytPlayer = null; // Motor para streaming online
let ytReady = false;
let syncInterval = null;

// Helper para URLs de la API relativas
function apiUrl(endpoint) {
  let base = window.location.pathname;
  if (!base.endsWith("/")) base += "/";
  return base + endpoint.replace(/^\//, "");
}

// ==========================================================================
// Control y Callback de YouTube IFrame API
// ==========================================================================
let pendingVideoId = null;

function initYTPlayer(initialVideoId) {
  if (initialVideoId) pendingVideoId = initialVideoId;
  if (ytPlayer) {
    if (ytReady && pendingVideoId && ytPlayer.loadVideoById) {
      if (ytPlayer.unMute) ytPlayer.unMute();
      if (ytPlayer.setVolume) ytPlayer.setVolume(state.volume * 100);
      ytPlayer.loadVideoById(pendingVideoId);
      ytPlayer.playVideo();
      pendingVideoId = null;
    }
    return;
  }

  if (!window.YT || !window.YT.Player) {
    console.log("[MusicBox] Esperando a que cargue la API de YouTube...");
    return;
  }

  const host = document.getElementById("yt-player-host");
  if (!host) return;

  try {
    ytPlayer = new YT.Player("yt-player-host", {
      height: "100%",
      width: "100%",
      videoId: pendingVideoId || "",
      playerVars: {
        autoplay: 1,
        controls: 1,
        disablekb: 0,
        fs: 1,
        playsinline: 1,
        rel: 0,
        modestbranding: 1
      },
      events: {
        onReady: (event) => {
          ytReady = true;
          console.log("[MusicBox] YouTube Player Listo");
          if (ytPlayer.unMute) ytPlayer.unMute();
          if (ytPlayer.setVolume) ytPlayer.setVolume(state.volume * 100);
          if (pendingVideoId && ytPlayer.loadVideoById) {
            ytPlayer.loadVideoById(pendingVideoId);
            ytPlayer.playVideo();
            pendingVideoId = null;
          }
        },
        onStateChange: (event) => {
          // YT.PlayerState: 1 (playing), 2 (paused), 0 (ended), 3 (buffering)
          if (event.data === 1) {
            state.isPlaying = true;
            updatePlayPauseButton();
            setBrandMarkAnimated(true);
            if ("mediaSession" in navigator) navigator.mediaSession.playbackState = "playing";
          } else if (event.data === 2) {
            state.isPlaying = false;
            updatePlayPauseButton();
            setBrandMarkAnimated(false);
            if ("mediaSession" in navigator) navigator.mediaSession.playbackState = "paused";
          } else if (event.data === 0) {
            handleTrackEnded();
          }
        },
        onError: (e) => {
          console.warn("[MusicBox] YouTube player warning/error:", e.data);
          if (e.data === 150 || e.data === 101) {
            showToast("Video con restricción de incrustación externa. Pasando al siguiente...");
            setTimeout(() => playNext(), 1200);
          }
        }
      }
    });
  } catch (err) {
    console.error("[MusicBox] Error al instanciar YT.Player:", err);
  }
}

window.initYTPlayer = initYTPlayer;
window.onYouTubeIframeAPIReady = function () {
  window.__ytReady = true;
  initYTPlayer();
};

if (window.__ytReady || (window.YT && window.YT.Player)) {
  initYTPlayer();
}

// ==========================================================================
// Inicialización al Cargar el DOM
// ==========================================================================
document.addEventListener("DOMContentLoaded", async () => {
  // Aplicar tema y acento
  document.documentElement.setAttribute("data-theme", state.theme);
  if (state.accent === "orange") document.documentElement.setAttribute("data-accent", "orange");

  // Inicializar BrandMark Wave Canvas en Sidebar y en Login
  initBrandMarkCanvas("brand-canvas", "brand-mark");
  initBrandMarkCanvas("login-brand-canvas", "login-brand-mark");

  // Sidebar Collapse inicial
  if (state.sidebarCollapsed) {
    document.getElementById("app-sidebar").classList.add("collapsed");
    document.getElementById("sidebar-toggle-icon").src = "icons/sidebar_expand.svg";
    document.getElementById("sidebar-toggle-btn").title = "Expandir panel";
  }

  // Base de datos IndexedDB
  await storage.init();

  // Gestión de Sesión / Login (Desktop Main.qml:800-810)
  checkAuthSession();

  // Service Worker
  if ("serviceWorker" in navigator) {
    navigator.serviceWorker.register("sw.js").catch(() => {});
  }

  // Persistencia de almacenamiento
  if (navigator.storage && navigator.storage.persist) {
    navigator.storage.persist().catch(() => {});
  }

  setupAudioListeners();
  setupMediaSession();
  setupUIListeners();
  setupVideoDock();

  // Bucle de sincronización de tiempo (lyrics + progress)
  startSyncLoop();

  // Cargar vista de inicio
  loadHomeView();

  // Inicializar listeners de Firebase Auth
  setupFirebaseAuthListeners();
});

// ==========================================================================
// Autenticación Real con Firebase y Google (Desktop Parity Main.qml:800-810)
// ==========================================================================
const firebaseConfig = {
  apiKey: "AIzaSyDl2piIE8XKag0rcycwC_NOYcbVhgVeMWU",
  authDomain: "musicbox-813e3.firebaseapp.com",
  projectId: "musicbox-813e3",
  storageBucket: "musicbox-813e3.firebasestorage.app",
  messagingSenderId: "1008640530987"
};

let fbApp = null;
let fbAuth = null;
let fbDb = null;

function initFirebase() {
  if (window.firebase && !fbApp) {
    try {
      fbApp = firebase.initializeApp(firebaseConfig);
      fbAuth = firebase.auth();
      fbDb = firebase.firestore();
      console.log("[MusicBox] Firebase inicializado con éxito");
    } catch (e) {
      console.error("[MusicBox] Error al inicializar Firebase:", e);
    }
  }
}

function setupFirebaseAuthListeners() {
  initFirebase();
  if (!fbAuth) return;

  // Manejar retorno de redirect en caso de que popup haya sido bloqueado
  fbAuth.getRedirectResult().then(async (result) => {
    if (result && result.user) {
      const fbUser = result.user;
      const user = {
        uid: fbUser.uid,
        name: fbUser.displayName || fbUser.email,
        email: fbUser.email,
        photo: fbUser.photoURL || "",
        authenticated: true,
        signedInAt: Date.now()
      };
      state.user = user;
      localStorage.setItem("musicbox_user", JSON.stringify(user));
      document.getElementById("login-overlay").style.display = "none";
      checkAuthSession();
      showToast(`Bienvenido, ${user.name}`);
      await syncWithFirestore(fbUser.uid);
    }
  }).catch((err) => {
    console.warn("[MusicBox] Error getRedirectResult:", err);
  });

  fbAuth.onAuthStateChanged(async (fbUser) => {
    if (fbUser) {
      const user = {
        uid: fbUser.uid,
        name: fbUser.displayName || fbUser.email,
        email: fbUser.email,
        photo: fbUser.photoURL || "",
        authenticated: true,
        signedInAt: Date.now()
      };
      state.user = user;
      localStorage.setItem("musicbox_user", JSON.stringify(user));
      const loginOverlay = document.getElementById("login-overlay");
      if (loginOverlay) loginOverlay.style.display = "none";
      checkAuthSession();
      await syncWithFirestore(fbUser.uid);
    }
  });
}

function checkAuthSession() {
  const loginOverlay = document.getElementById("login-overlay");
  const avatarSpan = document.getElementById("sidebar-account-initial");
  const avatarModal = document.getElementById("modal-user-initial");
  const nameModal = document.getElementById("modal-user-name");
  const emailModal = document.getElementById("modal-user-email");
  const avatarWrap = document.getElementById("sidebar-account-avatar");
  const modalAvatarWrap = document.getElementById("modal-user-avatar");

  if (!state.user) {
    loginOverlay.style.display = "flex";
  } else {
    loginOverlay.style.display = "none";
    const initial = (state.user.name || state.user.email || "U").charAt(0).toUpperCase();

    if (state.user.photo) {
      if (avatarWrap) avatarWrap.innerHTML = `<img src="${state.user.photo}" style="width:100%;height:100%;border-radius:50%;object-fit:cover;" alt="" />`;
      if (modalAvatarWrap) modalAvatarWrap.innerHTML = `<img src="${state.user.photo}" style="width:100%;height:100%;border-radius:50%;object-fit:cover;" alt="" />`;
    } else {
      if (avatarWrap) avatarWrap.innerHTML = `<span>${initial}</span>`;
      if (modalAvatarWrap) modalAvatarWrap.innerHTML = `<span>${initial}</span>`;
    }

    if (nameModal) nameModal.textContent = state.user.name || "Usuario MusicBox";
    if (emailModal) emailModal.textContent = state.user.email || "openmusicbox.duckdns.org";
  }

  // Botón Continuar con Google Real
  document.getElementById("google-login-btn").onclick = async () => {
    const spinner = document.getElementById("login-spinner");
    const label = document.getElementById("google-login-label");
    const errorText = document.getElementById("login-error-text");

    initFirebase();
    if (!fbAuth) {
      if (errorText) {
        errorText.textContent = "El servicio de autenticación de Google aún no está listo. Intenta en unos segundos.";
        errorText.style.display = "block";
      }
      return;
    }

    spinner.style.display = "block";
    label.textContent = "Conectando con Google...";
    if (errorText) errorText.style.display = "none";

    const provider = new firebase.auth.GoogleAuthProvider();
    provider.addScope("email");
    provider.addScope("profile");

    try {
      const result = await fbAuth.signInWithPopup(provider);
      const fbUser = result.user;
      console.log("[MusicBox] Login exitoso con Google:", fbUser);

      const user = {
        uid: fbUser.uid,
        name: fbUser.displayName || fbUser.email,
        email: fbUser.email,
        photo: fbUser.photoURL || "",
        authenticated: true,
        signedInAt: Date.now()
      };
      state.user = user;
      localStorage.setItem("musicbox_user", JSON.stringify(user));

      spinner.style.display = "none";
      label.textContent = "Continuar con Google";
      loginOverlay.style.display = "none";
      checkAuthSession();
      showToast(`Bienvenido, ${user.name}`);

      await syncWithFirestore(fbUser.uid);
    } catch (error) {
      console.error("[MusicBox] Error al iniciar sesión con Google:", error);
      spinner.style.display = "none";
      label.textContent = "Continuar con Google";

      if (error.code === "auth/popup-blocked") {
        try {
          await fbAuth.signInWithRedirect(provider);
          return;
        } catch (redirErr) {
          console.error("[MusicBox] Error en redirect:", redirErr);
        }
      }

      if (errorText) {
        if (error.code === "auth/popup-closed-by-user") {
          errorText.textContent = "Ventana de autenticación cerrada.";
        } else if (error.code === "auth/unauthorized-domain") {
          errorText.textContent = "El dominio openmusicbox.duckdns.org fue autorizado en Firebase. Aguarda 1 minuto para la propagación de Google.";
        } else {
          errorText.textContent = error.message || error.code || "Error al conectar con Google.";
        }
        errorText.style.display = "block";
      }
    }
  };

  // Botón Continuar como Invitado
  document.getElementById("guest-login-btn").onclick = () => {
    const user = {
      name: "Invitado",
      email: "invitado@openmusicbox.duckdns.org",
      authenticated: false,
      signedInAt: Date.now()
    };
    state.user = user;
    localStorage.setItem("musicbox_user", JSON.stringify(user));
    loginOverlay.style.display = "none";
    checkAuthSession();
    showToast("Has ingresado como invitado");
  };

  // Cerrar Sesión en Configuración
  document.getElementById("logout-button").onclick = async () => {
    if (fbAuth && state.user && state.user.authenticated) {
      try {
        await fbAuth.signOut();
      } catch (e) {
        console.warn("SignOut error:", e);
      }
    }
    state.user = null;
    localStorage.removeItem("musicbox_user");
    document.getElementById("settings-modal").style.display = "none";
    loginOverlay.style.display = "flex";
    showToast("Sesión cerrada");
  };
}

// Sincronización con Firestore (Paridad total con Desktop y Android)
async function syncWithFirestore(uid) {
  initFirebase();
  if (!fbDb || !uid) return;
  try {
    const docRef = fbDb.collection("users").doc(uid).collection("state").doc("musicbox");
    const docSnap = await docRef.get();
    if (docSnap.exists) {
      const data = docSnap.data();
      if (data && data.payload) {
        let cloudState = null;
        try {
          cloudState = JSON.parse(data.payload);
        } catch (e) {
          console.warn("[MusicBox] Error parseando payload de Firestore:", e);
        }

        if (cloudState) {
          console.log("[MusicBox] Biblioteca sincronizada de la nube:", cloudState);

          if (cloudState.playlists && cloudState.playlists.playlists) {
            const playlistsMap = cloudState.playlists.playlists;
            for (const [name, tracks] of Object.entries(playlistsMap)) {
              if (name === "Favoritos") {
                if (Array.isArray(tracks)) {
                  for (const tr of tracks) {
                    await storage.addFavorite(tr);
                  }
                }
              } else {
                await storage.savePlaylist({ id: name, name, tracks: tracks || [] });
              }
            }
          } else if (Array.isArray(cloudState.favorites)) {
            for (const tr of cloudState.favorites) {
              await storage.addFavorite(tr);
            }
          }

          if (cloudState.preferences) {
            if (typeof cloudState.preferences.darkMode === "boolean") {
              const theme = cloudState.preferences.darkMode ? "dark" : "light";
              state.theme = theme;
              document.documentElement.setAttribute("data-theme", theme);
              localStorage.setItem("musicbox_theme", theme);
              const sw = document.getElementById("settings-dark-switch");
              if (sw) sw.checked = theme === "dark";
            }
            if (cloudState.preferences.primaryColor) {
              applyPrimaryColor(cloudState.preferences.primaryColor);
            }
          }

          showToast("Biblioteca sincronizada con tu cuenta");
          if (state.activeView === "library") loadLibraryView();
        }
      }
    }
  } catch (err) {
    console.warn("[MusicBox] Error al leer Firestore:", err);
  }
}

async function pushStateToFirestore() {
  initFirebase();
  if (!fbDb || !state.user || !state.user.authenticated || !state.user.uid) return;
  try {
    const favorites = await storage.getAllFavorites();
    const playlists = await storage.getAllPlaylists();
    const playlistsMap = { "Favoritos": favorites };
    for (const pl of playlists) {
      playlistsMap[pl.name] = pl.tracks || [];
    }

    const payloadObj = {
      preferences: {
        darkMode: state.theme === "dark",
        primaryColor: state.primaryColor || "blue"
      },
      playlists: { playlists: playlistsMap },
      favorites: favorites,
      devices: {
        "web-client": {
          name: "MusicBox Web",
          os: "Web",
          lastActive: Date.now()
        }
      }
    };

    const docRef = fbDb.collection("users").doc(state.user.uid).collection("state").doc("musicbox");
    await docRef.set({ payload: JSON.stringify(payloadObj) }, { merge: true });
    console.log("[MusicBox] Cambios guardados en la nube");
  } catch (err) {
    console.warn("[MusicBox] Error al guardar en Firestore:", err);
  }
}

// ==========================================================================
// BrandMark Canvas: Onda Sonora Cian Animada (Desktop Main.qml:127-130)
// ==========================================================================
let brandPhase = 0;
let brandIsHovered = false;
let brandIsAnimated = false;
let brandAnimId = null;

function initBrandMarkCanvas(canvasId, markId) {
  const canvas = document.getElementById(canvasId);
  if (!canvas) return;
  const ctx = canvas.getContext("2d");

  const brandMarkEl = document.getElementById(markId);
  if (brandMarkEl) {
    brandMarkEl.addEventListener("mouseenter", () => {
      brandIsHovered = true;
      startBrandAnimation();
    });
    brandMarkEl.addEventListener("mouseleave", () => {
      brandIsHovered = false;
      if (!state.isPlaying) stopBrandAnimation();
    });
  }

  drawBrandWave(ctx, 0, false);
}

function setBrandMarkAnimated(animated) {
  brandIsAnimated = animated;
  const brandMarkEl = document.getElementById("brand-mark");
  if (brandMarkEl) {
    brandMarkEl.classList.toggle("animated", animated);
  }
  if (animated || brandIsHovered) {
    startBrandAnimation();
  } else {
    stopBrandAnimation();
  }
}

function startBrandAnimation() {
  if (brandAnimId) return;

  function loop() {
    brandPhase = (brandPhase + 0.012) % 1;
    const canvas1 = document.getElementById("brand-canvas");
    const canvas2 = document.getElementById("login-brand-canvas");
    if (canvas1) drawBrandWave(canvas1.getContext("2d"), brandPhase, true);
    if (canvas2) drawBrandWave(canvas2.getContext("2d"), brandPhase, true);
    brandAnimId = requestAnimationFrame(loop);
  }
  brandAnimId = requestAnimationFrame(loop);
}

function stopBrandAnimation() {
  if (brandAnimId) {
    cancelAnimationFrame(brandAnimId);
    brandAnimId = null;
  }
  const canvas1 = document.getElementById("brand-canvas");
  const canvas2 = document.getElementById("login-brand-canvas");
  if (canvas1) drawBrandWave(canvas1.getContext("2d"), 0, false);
  if (canvas2) drawBrandWave(canvas2.getContext("2d"), 0, false);
}

function drawBrandWave(ctx, phase, live) {
  const w = ctx.canvas.width;
  const h = ctx.canvas.height;
  ctx.clearRect(0, 0, w, h);

  const raw = [
    [18, 238], [32, 245], [43, 238], [56, 192], [67, 190],
    [83, 282], [94, 295], [120, 122], [130, 128], [148, 361],
    [158, 364], [177, 244], [187, 242], [201, 302], [220, 312]
  ];

  const s = Math.min(w / 446, h / 488);
  const ox = (w - 446 * s) / 2;
  const oy = (h - 488 * s) / 2;

  const pts = [];
  for (let i = 0; i < raw.length; i++) {
    const t = i / (raw.length - 1);
    const motion = live
      ? Math.sin(phase * Math.PI * 2 - (raw[i][0] / 446) * Math.PI * 4) * Math.sin(t * Math.PI)
      : 0;
    pts.push([ox + raw[i][0] * s, oy + (raw[i][1] + 16 * motion) * s]);
  }

  ctx.beginPath();
  ctx.moveTo(pts[0][0], pts[0][1]);
  for (let j = 1; j < pts.length - 1; j++) {
    const mx = (pts[j][0] + pts[j + 1][0]) / 2;
    const my = (pts[j][1] + pts[j + 1][1]) / 2;
    ctx.quadraticCurveTo(pts[j][0], pts[j][1], mx, my);
  }
  ctx.lineTo(pts[pts.length - 1][0], pts[pts.length - 1][1]);

  ctx.strokeStyle = state.accent === "orange" ? "#FFBD7A" : "#50E9FF";
  ctx.lineWidth = Math.max(2, 9 * s);
  ctx.lineCap = "round";
  ctx.lineJoin = "round";
  ctx.shadowColor = state.accent === "orange" ? "#FF9F43" : "#0A84FF";
  ctx.shadowBlur = 8 * s * 2;
  ctx.stroke();
}

// ==========================================================================
// Dock de Video y Listeners de Audio
// ==========================================================================
function setupVideoDock() {
  const dock = document.getElementById("video-dock");
  const minimizeBtn = document.getElementById("minimize-video-btn");
  const hideBtn = document.getElementById("hide-video-dock-btn");
  const videoToggleBtn = document.getElementById("video-toggle-btn");

  minimizeBtn.onclick = () => {
    dock.classList.toggle("minimized");
  };

  hideBtn.onclick = () => {
    dock.classList.add("dock-hidden");
    state.videoDockOpen = false;
    videoToggleBtn.classList.remove("active");
  };

  videoToggleBtn.onclick = () => {
    state.videoDockOpen = !state.videoDockOpen;
    dock.classList.toggle("dock-hidden", !state.videoDockOpen);
    videoToggleBtn.classList.toggle("active", state.videoDockOpen);
    showToast(state.videoDockOpen ? "Video visible" : "Video minimizado");
  };
}

function setupAudioListeners() {
  audio.volume = state.volume;

  audio.addEventListener("play", () => {
    if (state.isOfflinePlayback) {
      state.isPlaying = true;
      updatePlayPauseButton();
      setBrandMarkAnimated(true);
      if ("mediaSession" in navigator) navigator.mediaSession.playbackState = "playing";
    }
  });

  audio.addEventListener("pause", () => {
    if (state.isOfflinePlayback) {
      state.isPlaying = false;
      updatePlayPauseButton();
      setBrandMarkAnimated(false);
      if ("mediaSession" in navigator) navigator.mediaSession.playbackState = "paused";
    }
  });

  audio.addEventListener("ended", () => {
    if (state.isOfflinePlayback) {
      handleTrackEnded();
    }
  });
}

function startSyncLoop() {
  if (syncInterval) clearInterval(syncInterval);
  syncInterval = setInterval(() => {
    let current = 0;
    let duration = state.currentTrack?.duration || 0;

    if (state.isOfflinePlayback) {
      current = audio.currentTime || 0;
      if (audio.duration && isFinite(audio.duration)) duration = audio.duration;
    } else if (ytReady && ytPlayer && ytPlayer.getCurrentTime) {
      current = ytPlayer.getCurrentTime() || 0;
      const d = ytPlayer.getDuration();
      if (d && isFinite(d) && d > 0) duration = d;
    }

    updateProgress(current, duration);
    updateActiveLyric(current * 1000);
  }, 250);
}

function setupMediaSession() {
  if (!("mediaSession" in navigator)) return;

  navigator.mediaSession.setActionHandler("play", () => togglePlayPause());
  navigator.mediaSession.setActionHandler("pause", () => togglePlayPause());
  navigator.mediaSession.setActionHandler("previoustrack", () => playPrevious());
  navigator.mediaSession.setActionHandler("nexttrack", () => playNext());
  navigator.mediaSession.setActionHandler("seekto", (details) => {
    if (details.seekTime !== null && isFinite(details.seekTime)) {
      seekTo(details.seekTime);
    }
  });
}

function updateMediaSessionMetadata(track) {
  if (!("mediaSession" in navigator) || !track) return;
  navigator.mediaSession.metadata = new MediaMetadata({
    title: track.title,
    artist: track.artist,
    album: track.album || "MusicBox",
    artwork: [
      { src: track.thumbnail, sizes: "192x192", type: "image/jpeg" },
      { src: track.thumbnail, sizes: "512x512", type: "image/jpeg" }
    ]
  });
}

// ==========================================================================
// Reproducción Híbrida: Online (YouTube IFrame) + Offline (IndexedDB)
// ==========================================================================
async function playTrack(track, fromQueue = false) {
  if (!track) return;

  state.currentTrack = track;
  if (!fromQueue) {
    const existingIdx = state.queue.findIndex((t) => t.id === track.id);
    if (existingIdx !== -1) {
      state.queueIndex = existingIdx;
    } else {
      state.queue.unshift(track);
      state.queueIndex = 0;
    }
  }

  updatePlayerBarUI(track);
  updateMediaSessionMetadata(track);
  storage.recordHistory(track);

  // 1. Verificar si está en descargas locales (Modo Offline)
  const offlineTrack = await storage.getOfflineTrack(track.id);

  if (offlineTrack && offlineTrack.audioBlob) {
    state.isOfflinePlayback = true;
    showOfflineIndicator(true);

    if (ytReady && ytPlayer && ytPlayer.stopVideo) ytPlayer.stopVideo();

    const blobUrl = URL.createObjectURL(offlineTrack.audioBlob);
    audio.src = blobUrl;
    audio.play().catch(() => {});

    if (offlineTrack.lyrics) {
      state.lyrics = offlineTrack.lyrics.lines || [];
      renderLyrics(offlineTrack.lyrics);
    } else {
      loadLyrics(track);
    }
  } else {
    // 2. Modo Online
    if (!navigator.onLine) {
      showToast("Sin conexión: descarga la canción para escucharla offline");
      return;
    }

    state.isOfflinePlayback = false;
    showOfflineIndicator(false);
    audio.pause();

    const videoDock = document.getElementById("video-dock");
    if (videoDock) {
      videoDock.classList.remove("dock-hidden");
      state.videoDockOpen = true;
      const toggleBtn = document.getElementById("video-toggle-btn");
      if (toggleBtn) toggleBtn.classList.add("active");
    }
    const dockTitle = document.getElementById("video-dock-title");
    if (dockTitle) dockTitle.textContent = `${track.title} · ${track.artist || ''}`;

    pendingVideoId = track.id;
    if (!ytPlayer) {
      initYTPlayer(track.id);
    } else if (ytReady && ytPlayer && ytPlayer.loadVideoById) {
      try {
        if (ytPlayer.unMute) ytPlayer.unMute();
        if (ytPlayer.setVolume) ytPlayer.setVolume(state.volume * 100);
        ytPlayer.loadVideoById(track.id);
        ytPlayer.playVideo();
        pendingVideoId = null;
      } catch (err) {
        console.warn("[MusicBox] Error al cargar video en ytPlayer:", err);
      }
    }
    loadLyrics(track);
  }

  checkAutoplayQueue(track.id);
  highlightPlayingTracks();
}

function togglePlayPause() {
  if (!state.currentTrack && state.queue.length > 0) {
    playTrack(state.queue[0]);
    return;
  }
  if (!state.currentTrack) return;

  if (state.isOfflinePlayback) {
    if (audio.paused) audio.play();
    else audio.pause();
  } else if (ytPlayer) {
    try {
      if (ytReady && ytPlayer.getPlayerState) {
        const playerState = ytPlayer.getPlayerState();
        if (playerState === 1) {
          ytPlayer.pauseVideo();
        } else {
          if (ytPlayer.unMute) ytPlayer.unMute();
          ytPlayer.playVideo();
        }
      } else if (state.currentTrack) {
        playTrack(state.currentTrack);
      }
    } catch (err) {
      console.warn("[MusicBox] Error al pausar/reproducir:", err);
    }
  }
}

function seekTo(seconds) {
  if (state.isOfflinePlayback) {
    audio.currentTime = seconds;
  } else if (ytReady && ytPlayer && ytPlayer.seekTo) {
    ytPlayer.seekTo(seconds, true);
  }
}

function playNext() {
  if (state.queue.length === 0) return;
  if (state.isShuffle) {
    const nextIdx = Math.floor(Math.random() * state.queue.length);
    state.queueIndex = nextIdx;
    playTrack(state.queue[nextIdx], true);
    return;
  }
  if (state.queueIndex + 1 < state.queue.length) {
    state.queueIndex++;
    playTrack(state.queue[state.queueIndex], true);
  } else if (state.repeatMode === "all") {
    state.queueIndex = 0;
    playTrack(state.queue[0], true);
  }
}

function playPrevious() {
  const current = state.isOfflinePlayback ? audio.currentTime : (ytPlayer?.getCurrentTime?.() || 0);
  if (current > 3) {
    seekTo(0);
    return;
  }
  if (state.queueIndex > 0) {
    state.queueIndex--;
    playTrack(state.queue[state.queueIndex], true);
  } else {
    seekTo(0);
  }
}

function handleTrackEnded() {
  if (state.repeatMode === "one") {
    seekTo(0);
    if (state.isOfflinePlayback) audio.play();
    else ytPlayer?.playVideo?.();
  } else {
    playNext();
  }
}

async function checkAutoplayQueue(videoId) {
  if (state.queueIndex >= state.queue.length - 3 && navigator.onLine) {
    try {
      const resp = await fetch(apiUrl(`api/related/${videoId}`));
      const relatedTracks = await resp.json();
      if (Array.isArray(relatedTracks)) {
        const currentIds = new Set(state.queue.map((t) => t.id));
        const newTracks = relatedTracks.filter((t) => !currentIds.has(t.id));
        state.queue.push(...newTracks);
        renderQueue();
      }
    } catch (e) {
      console.warn("No se pudo cargar autoplay:", e);
    }
  }
}

// ==========================================================================
// Descarga Offline en IndexedDB
// ==========================================================================
async function downloadTrackForOffline(track, btnElement) {
  if (!navigator.onLine) {
    showToast("Necesitas conexión para descargar");
    return;
  }

  showToast(`Descargando "${track.title}"...`);
  if (btnElement) {
    btnElement.style.opacity = "0.5";
    btnElement.style.pointerEvents = "none";
  }

  try {
    const streamResp = await fetch(apiUrl(`api/stream/${track.id}`));
    if (!streamResp.ok) throw new Error("Fallo al descargar stream de audio");
    const audioBlob = await streamResp.blob();

    let lyricsData = null;
    try {
      const lyricsResp = await fetch(apiUrl(`api/lyrics/${track.id}?title=${encodeURIComponent(track.title)}&artist=${encodeURIComponent(track.artist)}`));
      lyricsData = await lyricsResp.json();
    } catch (err) {}

    await storage.saveOfflineTrack(track, audioBlob, lyricsData);
    showToast(`Guardada offline: ${track.title}`);

    if (btnElement) {
      btnElement.innerHTML = `<img src="icons/downloaded.svg" class="nav-icon" style="filter: invert(65%) sepia(85%) saturate(400%) hue-rotate(85deg);" />`;
      btnElement.style.opacity = "1";
      btnElement.style.pointerEvents = "auto";
    }

    if (state.activeView === "downloads") {
      loadDownloadsView();
    }
  } catch (err) {
    console.error("Error al descargar:", err);
    showToast("Para descargar se requiere configurar cookies de YouTube en Configuración");
    if (btnElement) {
      btnElement.style.opacity = "1";
      btnElement.style.pointerEvents = "auto";
    }
  }
}

// ==========================================================================
// Letras Sincronizadas
// ==========================================================================
async function loadLyrics(track) {
  const container = document.getElementById("lyrics-body");
  container.innerHTML = `<div class="lyrics-empty">Buscando letra...</div>`;

  try {
    const url = apiUrl(`api/lyrics/${track.id}?title=${encodeURIComponent(track.title)}&artist=${encodeURIComponent(track.artist)}&duration=${track.duration || 0}`);
    const resp = await fetch(url);
    const data = await resp.json();
    renderLyrics(data);
  } catch (err) {
    container.innerHTML = `<div class="lyrics-empty">No se encontró letra para esta canción</div>`;
  }
}

function renderLyrics(data) {
  const container = document.getElementById("lyrics-body");
  container.innerHTML = "";

  if (!data || !data.lines || data.lines.length === 0) {
    container.innerHTML = `<div class="lyrics-empty">Letra no disponible</div>`;
    state.lyrics = [];
    return;
  }

  state.lyrics = data.lines;

  data.lines.forEach((line, index) => {
    const el = document.createElement("div");
    el.className = "lyric-line";
    el.id = `lyric-${index}`;
    el.textContent = line.text;
    if (line.timeMs >= 0) {
      el.addEventListener("click", () => {
        seekTo(line.timeMs / 1000);
      });
    }
    container.appendChild(el);
  });
}

function updateActiveLyric(currentMs) {
  if (!state.lyricsOpen || !state.lyrics || state.lyrics.length === 0) return;

  let activeIndex = -1;
  for (let i = 0; i < state.lyrics.length; i++) {
    if (state.lyrics[i].timeMs >= 0 && currentMs >= state.lyrics[i].timeMs) {
      activeIndex = i;
    } else if (state.lyrics[i].timeMs > currentMs) {
      break;
    }
  }

  if (activeIndex !== -1) {
    document.querySelectorAll(".lyric-line.active").forEach((el) => el.classList.remove("active"));
    const activeEl = document.getElementById(`lyric-${activeIndex}`);
    if (activeEl) {
      activeEl.classList.add("active");
      activeEl.scrollIntoView({ behavior: "smooth", block: "center" });
    }
  }
}

// ==========================================================================
// Vistas Principales (Inicio, Mi Música, Descargas, Historial)
// ==========================================================================

// --- Vista: Inicio (Desktop Calque) ---
async function loadHomeView() {
  state.activeView = "home";
  updateActiveNavButton("home");

  const container = document.getElementById("main-content");
  container.innerHTML = `
    <h1 class="page-heading">Inicio</h1>

    <!-- Gran Tarjeta de Búsqueda Desktop -->
    <div class="home-search-card" id="home-search-card">
      <img src="icons/search.svg" class="home-search-icon" alt="">
      <input type="text" id="home-search-input" class="home-search-input" placeholder="Canción, artista o enlace" autocomplete="off" spellcheck="false" value="${state.searchQuery || ''}">
      <button class="search-clear-btn" id="search-clear-btn" style="${state.searchQuery ? 'display:flex;' : 'display:none;'}" title="Limpiar">
        <img src="icons/close.svg" alt="">
      </button>
      <button class="search-action-btn" id="search-action-btn">Buscar</button>
    </div>

    <!-- Tabs de Filtro (Todos, Canciones, Videos, Artistas) -->
    <div class="search-filter-tabs" id="search-filter-tabs" style="${state.searchQuery ? 'display:flex;' : 'display:none;'}">
      <button class="filter-tab ${state.searchFilter === 'all' ? 'active' : ''}" data-filter="all">Todos</button>
      <button class="filter-tab ${state.searchFilter === 'songs' ? 'active' : ''}" data-filter="songs">Canciones</button>
      <button class="filter-tab ${state.searchFilter === 'videos' ? 'active' : ''}" data-filter="videos">Videos</button>
      <button class="filter-tab ${state.searchFilter === 'artists' ? 'active' : ''}" data-filter="artists">Artistas</button>
    </div>

    <!-- Contenedor Dinámico: Resultados de Búsqueda o Descubrimiento -->
    <div id="home-dynamic-content">
      <div class="home-section" id="section-artists">
        <div class="home-section-header">
          <div class="home-section-title-wrap">
            <span class="home-section-title">Artistas para ti</span>
          </div>
        </div>
        <div class="artists-scroll-row" id="artists-row">
          ${Array(8).fill('<div class="artist-bubble skeleton" style="height:140px;"></div>').join("")}
        </div>
      </div>

      <div class="home-section" id="section-for-you">
        <div class="home-section-header">
          <div class="home-section-title-wrap">
            <span class="home-section-title">Para ti</span>
            <span class="home-section-subtitle">Para empezar a descubrir</span>
          </div>
          <button class="icon-btn" id="refresh-recommendations-btn" title="Actualizar">
            <img src="icons/refresh.svg" alt="Actualizar">
          </button>
        </div>
        <div class="for-you-grid" id="for-you-grid">
          ${Array(12).fill('<div class="for-you-card skeleton"></div>').join("")}
        </div>
      </div>

      <div class="home-section" id="section-recent" style="display:none;">
        <div class="home-section-header">
          <div class="home-section-title-wrap">
            <span class="home-section-title">Escuchadas recientemente</span>
          </div>
        </div>
        <div class="artists-scroll-row" id="recent-row"></div>
      </div>
    </div>
  `;

  setupHomeSearchListeners();

  if (state.searchQuery && state.searchQuery.trim().length >= 2) {
    performSearch(state.searchQuery, state.searchFilter);
  } else {
    loadHomeData();
  }
}

async function loadHomeData() {
  const recentTracks = await storage.getRecentHistory(10);
  const recentSection = document.getElementById("section-recent");
  const recentRow = document.getElementById("recent-row");

  if (recentSection && recentRow && recentTracks.length > 0) {
    recentSection.style.display = "block";
    recentRow.innerHTML = "";
    recentTracks.forEach((track) => {
      recentRow.appendChild(createRecentMiniCard(track));
    });
  }

  try {
    const resp = await fetch(apiUrl("api/recommendations"));
    const data = await resp.json();
    state.cachedRecommendations = data;

    renderHomeArtists(data.artists || []);
    renderHomeForYou(data.songs || data || []);
  } catch (err) {
    console.error("Error al cargar recomendaciones:", err);
  }
}

function renderHomeArtists(artists) {
  const row = document.getElementById("artists-row");
  if (!row) return;
  row.innerHTML = "";

  if (!artists || artists.length === 0) {
    document.getElementById("section-artists").style.display = "none";
    return;
  }

  artists.forEach((art) => {
    const bubble = document.createElement("div");
    bubble.className = "artist-bubble";
    bubble.innerHTML = `
      <div class="artist-bubble-photo-wrap">
        ${art.photo
          ? `<img src="${art.photo}" class="artist-bubble-img" alt="${art.name}" loading="lazy" />`
          : `<div class="artist-bubble-fallback">${art.name.charAt(0).toUpperCase()}</div>`
        }
      </div>
      <div class="artist-bubble-name">${art.name}</div>
    `;
    bubble.addEventListener("click", () => {
      loadArtistView(art.browse_id, art.name, art.photo);
    });
    row.appendChild(bubble);
  });
}

function renderHomeForYou(songs) {
  const grid = document.getElementById("for-you-grid");
  if (!grid) return;
  grid.innerHTML = "";

  if (!songs || songs.length === 0) {
    grid.innerHTML = `<div style="color:var(--text-muted);grid-column:1/-1;">No hay canciones recomendadas en este momento.</div>`;
    return;
  }

  songs.forEach((track) => {
    grid.appendChild(createForYouCard(track));
  });
}

function createForYouCard(track) {
  const card = document.createElement("div");
  card.className = "for-you-card";
  card.setAttribute("data-track-id", track.id);
  if (state.currentTrack && state.currentTrack.id === track.id) {
    card.classList.add("playing");
  }

  card.innerHTML = `
    <div class="for-you-cover-wrap">
      <img src="${track.thumbnail}" class="for-you-cover" loading="lazy" alt="${track.title}" />
      <div class="for-you-play-overlay">
        <img src="icons/play.svg" class="for-you-play-icon" alt="Play" />
      </div>
    </div>
    <div class="for-you-title">${track.title}</div>
    <div class="for-you-artist">${track.artist || track.subtitle || ''}</div>
    <div class="for-you-actions">
      <button class="icon-btn fav-btn" title="Favorito">
        <img src="icons/heart.svg" alt="Favorito" />
      </button>
      <button class="icon-btn more-btn" title="Más opciones">
        <img src="icons/more.svg" alt="Opciones" />
      </button>
    </div>
  `;

  card.addEventListener("click", (e) => {
    if (e.target.closest(".for-you-actions")) return;
    playTrack(track);
  });

  const favBtn = card.querySelector(".fav-btn");
  storage.isFavorite(track.id).then((isFav) => {
    if (isFav) favBtn.querySelector("img").src = "icons/heart_filled.svg";
  });

  favBtn.addEventListener("click", async (e) => {
    e.stopPropagation();
    const isNowFav = await storage.toggleFavorite(track);
    favBtn.querySelector("img").src = isNowFav ? "icons/heart_filled.svg" : "icons/heart.svg";
    showToast(isNowFav ? "Guardado en Favoritos" : "Eliminado de Favoritos");
  });

  const moreBtn = card.querySelector(".more-btn");
  moreBtn.addEventListener("click", (e) => {
    e.stopPropagation();
    openTrackContextMenu(e, track);
  });

  return card;
}

function createRecentMiniCard(track) {
  const el = document.createElement("div");
  el.className = "album-card";
  el.style.width = "140px";
  el.style.minWidth = "140px";
  el.style.height = "180px";
  el.innerHTML = `
    <img src="${track.thumbnail}" class="album-cover" style="width:124px;height:124px;" loading="lazy" />
    <div class="album-title">${track.title}</div>
    <div class="album-subtitle">${track.artist}</div>
  `;
  el.addEventListener("click", () => playTrack(track));
  return el;
}

// --- Búsqueda en Inicio ---
function setupHomeSearchListeners() {
  const searchInput = document.getElementById("home-search-input");
  const clearBtn = document.getElementById("search-clear-btn");
  const searchBtn = document.getElementById("search-action-btn");
  const filterTabs = document.getElementById("search-filter-tabs");

  let debounceTimer = null;

  const triggerSearch = () => {
    const q = searchInput.value.trim();
    if (q.length >= 2) {
      clearBtn.style.display = "flex";
      filterTabs.style.display = "flex";
      performSearch(q, state.searchFilter);
    } else if (q.length === 0) {
      clearBtn.style.display = "none";
      filterTabs.style.display = "none";
      state.searchQuery = "";
      loadHomeView();
    }
  };

  searchInput.addEventListener("input", () => {
    clearTimeout(debounceTimer);
    debounceTimer = setTimeout(triggerSearch, 350);
  });

  searchInput.addEventListener("keydown", (e) => {
    if (e.key === "Enter") {
      clearTimeout(debounceTimer);
      triggerSearch();
    }
  });

  searchBtn.addEventListener("click", () => {
    clearTimeout(debounceTimer);
    triggerSearch();
  });

  clearBtn.addEventListener("click", () => {
    searchInput.value = "";
    state.searchQuery = "";
    clearBtn.style.display = "none";
    filterTabs.style.display = "none";
    loadHomeView();
  });

  document.querySelectorAll(".filter-tab").forEach((tab) => {
    tab.addEventListener("click", () => {
      document.querySelectorAll(".filter-tab").forEach((t) => t.classList.remove("active"));
      tab.classList.add("active");
      state.searchFilter = tab.getAttribute("data-filter");
      if (searchInput.value.trim()) {
        performSearch(searchInput.value.trim(), state.searchFilter);
      }
    });
  });

  const refreshBtn = document.getElementById("refresh-recommendations-btn");
  if (refreshBtn) {
    refreshBtn.addEventListener("click", () => {
      showToast("Actualizando recomendaciones...");
      loadHomeData();
    });
  }
}

async function performSearch(query, filter = "all") {
  state.searchQuery = query;
  state.searchFilter = filter;

  const contentArea = document.getElementById("home-dynamic-content");
  if (!contentArea) return;

  contentArea.innerHTML = `
    <div class="tracks-list" id="search-results-list">
      ${Array(8).fill('<div class="row-item skeleton"></div>').join("")}
    </div>
  `;

  try {
    const resp = await fetch(apiUrl(`api/search?q=${encodeURIComponent(query)}&type=${filter}`));
    const results = await resp.json();

    contentArea.innerHTML = "";

    if (!Array.isArray(results) || results.length === 0) {
      contentArea.innerHTML = `<div style="padding:40px;color:var(--text-muted);text-align:center;">No se encontraron resultados para "${query}"</div>`;
      return;
    }

    if (filter === "artists") {
      const grid = document.createElement("div");
      grid.className = "for-you-grid";
      results.forEach((art) => {
        const bubble = document.createElement("div");
        bubble.className = "artist-bubble";
        bubble.style.width = "140px";
        bubble.innerHTML = `
          <div class="artist-bubble-photo-wrap" style="width:110px;height:110px;border-radius:55px;">
            ${art.photo
              ? `<img src="${art.photo}" class="artist-bubble-img" alt="${art.name}" loading="lazy" />`
              : `<div class="artist-bubble-fallback">${art.name.charAt(0).toUpperCase()}</div>`
            }
          </div>
          <div class="artist-bubble-name" style="font-size:14px;">${art.name}</div>
        `;
        bubble.addEventListener("click", () => loadArtistView(art.browse_id, art.name, art.photo));
        grid.appendChild(bubble);
      });
      contentArea.appendChild(grid);
    } else {
      const list = document.createElement("div");
      list.className = "tracks-list";
      results.forEach((track) => {
        list.appendChild(createTrackRow(track));
      });
      contentArea.appendChild(list);
    }
  } catch (err) {
    console.error("Error en búsqueda:", err);
    contentArea.innerHTML = `<div style="padding:40px;color:var(--text-muted);text-align:center;">Error al buscar canciones. Inténtalo nuevamente.</div>`;
  }
}

// --- Fila de Canción (RowItem Desktop) ---
function createTrackRow(track, isOfflineView = false) {
  const row = document.createElement("div");
  row.className = "row-item";
  row.setAttribute("data-track-id", track.id);
  if (state.currentTrack && state.currentTrack.id === track.id) {
    row.classList.add("playing");
  }

  const durationStr = formatDuration(track.duration);

  row.innerHTML = `
    <div class="row-cover-wrap">
      <img src="${track.thumbnail}" class="row-cover" loading="lazy" alt="${track.title}" />
    </div>
    <div class="row-details">
      <div class="row-title">${track.title}</div>
      <div class="row-subtitle">${track.artist || track.subtitle || ''}</div>
    </div>
    <div class="row-duration">${durationStr}</div>
    <div class="row-actions">
      <button class="icon-btn fav-btn" title="Favorito">
        <img src="icons/heart.svg" alt="Favorito" />
      </button>
      <button class="icon-btn download-btn" title="${isOfflineView ? 'Eliminar Descarga' : 'Descargar Canción'}">
        <img src="${isOfflineView ? 'icons/downloaded.svg' : 'icons/download.svg'}" alt="Descargar" />
      </button>
      <button class="icon-btn more-btn" title="Más opciones">
        <img src="icons/more.svg" alt="Opciones" />
      </button>
    </div>
  `;

  row.addEventListener("click", (e) => {
    if (e.target.closest(".row-actions")) return;
    playTrack(track);
  });

  const favBtn = row.querySelector(".fav-btn");
  storage.isFavorite(track.id).then((isFav) => {
    if (isFav) favBtn.querySelector("img").src = "icons/heart_filled.svg";
  });

  favBtn.addEventListener("click", async (e) => {
    e.stopPropagation();
    const isNowFav = await storage.toggleFavorite(track);
    favBtn.querySelector("img").src = isNowFav ? "icons/heart_filled.svg" : "icons/heart.svg";
    showToast(isNowFav ? "Guardado en Favoritos" : "Eliminado de Favoritos");
    if (state.activeView === "library") loadLibraryView();
  });

  const dlBtn = row.querySelector(".download-btn");
  dlBtn.addEventListener("click", async (e) => {
    e.stopPropagation();
    if (isOfflineView) {
      await storage.removeOfflineTrack(track.id);
      showToast(`Eliminada de descargas: ${track.title}`);
      loadDownloadsView();
    } else {
      downloadTrackForOffline(track, dlBtn);
    }
  });

  const moreBtn = row.querySelector(".more-btn");
  moreBtn.addEventListener("click", (e) => {
    e.stopPropagation();
    openTrackContextMenu(e, track);
  });

  return row;
}

// --- Vista: Mi Música (Library Desktop) ---
async function loadLibraryView(filter = "songs") {
  state.activeView = "library";
  updateActiveNavButton("library");

  const container = document.getElementById("main-content");
  container.innerHTML = `
    <div class="home-section-header">
      <h1 class="page-heading" style="margin-bottom:0;">Mi música</h1>
      <button class="pill-btn-accent" id="new-playlist-top-btn">
        <img src="icons/plus.svg" style="width:16px;height:16px;filter:brightness(0) invert(1);" alt="">
        <span>Nueva Playlist</span>
      </button>
    </div>

    <!-- Barra de Búsqueda en Mi Música (Desktop Main.qml:403) -->
    <div class="home-search-card" style="height:54px;margin-top:14px;">
      <img src="icons/search.svg" style="width:18px;height:18px;opacity:0.6;margin-left:14px;" alt="">
      <input type="text" id="library-search-input" class="home-search-input" placeholder="Buscar en tu música" style="font-size:14px;">
      <button class="search-clear-btn" id="library-search-clear" style="display:none;" title="Limpiar">
        <img src="icons/close.svg" alt="">
      </button>
    </div>

    <!-- Chips de Mi Música: Canciones, Playlists, Favoritas, Descargadas -->
    <div class="search-filter-tabs" style="margin-top:14px;">
      <button class="filter-tab ${filter === 'songs' ? 'active' : ''}" data-lib="songs">Canciones</button>
      <button class="filter-tab ${filter === 'playlists' ? 'active' : ''}" data-lib="playlists">Playlists</button>
      <button class="filter-tab ${filter === 'favorites' ? 'active' : ''}" data-lib="favorites">Favoritas</button>
      <button class="filter-tab ${filter === 'downloads' ? 'active' : ''}" data-lib="downloads">Descargadas</button>
    </div>

    <div class="tracks-list" id="library-content-list" style="margin-top:18px;">
      ${Array(6).fill('<div class="row-item skeleton"></div>').join("")}
    </div>
  `;

  document.querySelectorAll("[data-lib]").forEach((btn) => {
    btn.addEventListener("click", () => {
      loadLibraryView(btn.getAttribute("data-lib"));
    });
  });

  const newPlBtn = document.getElementById("new-playlist-top-btn");
  if (newPlBtn) {
    newPlBtn.addEventListener("click", openNewPlaylistModal);
  }

  const listContainer = document.getElementById("library-content-list");
  const searchInput = document.getElementById("library-search-input");
  const searchClear = document.getElementById("library-search-clear");

  let allLoadedItems = [];

  const renderItems = (items) => {
    listContainer.innerHTML = "";
    if (items.length === 0) {
      listContainer.innerHTML = `<div style="padding:40px;color:var(--text-muted);text-align:center;">No se encontraron elementos con este filtro.</div>`;
      return;
    }
    if (filter === "playlists") {
      const grid = document.createElement("div");
      grid.className = "for-you-grid";
      items.forEach((pl) => {
        const card = document.createElement("div");
        card.className = "for-you-card";
        card.innerHTML = `
          <div class="for-you-cover-wrap" style="display:flex;align-items:center;justify-content:center;background:#242a35;">
            <img src="icons/playlist.svg" style="width:40px;height:40px;opacity:0.7;" alt="">
          </div>
          <div class="for-you-title">${pl.name}</div>
          <div class="for-you-artist">${(pl.tracks || []).length} canciones</div>
        `;
        card.addEventListener("click", () => loadPlaylistView(pl));
        grid.appendChild(card);
      });
      listContainer.appendChild(grid);
    } else {
      items.forEach((t) => listContainer.appendChild(createTrackRow(t, filter === "downloads")));
    }
  };

  if (filter === "favorites") {
    allLoadedItems = await storage.getAllFavorites();
  } else if (filter === "downloads") {
    allLoadedItems = await storage.getAllOfflineTracks();
  } else if (filter === "playlists") {
    allLoadedItems = await storage.getAllPlaylists();
  } else {
    const [favs, hist, dls] = await Promise.all([
      storage.getAllFavorites(),
      storage.getRecentHistory(50),
      storage.getAllOfflineTracks()
    ]);
    const seen = new Set();
    const songs = [];
    [...dls, ...favs, ...hist].forEach((t) => {
      if (t && t.id && !seen.has(t.id)) {
        seen.add(t.id);
        songs.push(t);
      }
    });
    allLoadedItems = songs;
  }

  renderItems(allLoadedItems);

  // Filtrado en vivo de Mi Música
  searchInput.addEventListener("input", () => {
    const q = searchInput.value.trim().toLowerCase();
    searchClear.style.display = q ? "flex" : "none";
    if (!q) {
      renderItems(allLoadedItems);
    } else {
      const filtered = allLoadedItems.filter((item) => {
        const title = (item.title || item.name || "").toLowerCase();
        const artist = (item.artist || item.subtitle || "").toLowerCase();
        return title.includes(q) || artist.includes(q);
      });
      renderItems(filtered);
    }
  });

  searchClear.addEventListener("click", () => {
    searchInput.value = "";
    searchClear.style.display = "none";
    renderItems(allLoadedItems);
  });
}

// --- Vista: Descargas (Downloads Desktop) ---
async function loadDownloadsView() {
  state.activeView = "downloads";
  updateActiveNavButton("downloads");

  const container = document.getElementById("main-content");
  container.innerHTML = `
    <div class="home-section-header">
      <div class="home-section-title-wrap">
        <h1 class="page-heading" style="margin-bottom:2px;">Descargas</h1>
        <span class="home-section-subtitle">Música disponible sin conexión (100% Offline)</span>
      </div>
      <div class="offline-pill" id="storage-quota-pill">Calculando...</div>
    </div>

    <div class="tracks-list" id="downloads-content-list" style="margin-top:20px;">
      ${Array(4).fill('<div class="row-item skeleton"></div>').join("")}
    </div>
  `;

  const tracks = await storage.getAllOfflineTracks();
  const listContainer = document.getElementById("downloads-content-list");
  const quotaPill = document.getElementById("storage-quota-pill");

  let totalBytes = 0;
  tracks.forEach((t) => {
    totalBytes += t.sizeBytes || 0;
  });
  const mbUsed = (totalBytes / (1024 * 1024)).toFixed(1);
  quotaPill.textContent = `${tracks.length} canciones · ${mbUsed} MB`;

  listContainer.innerHTML = "";
  if (tracks.length === 0) {
    listContainer.innerHTML = `
      <div style="padding:50px;color:var(--text-muted);text-align:center;">
        <img src="icons/download.svg" style="width:48px;height:48px;opacity:0.3;margin-bottom:12px;" alt="" />
        <div style="font-size:16px;font-weight:700;color:var(--text-primary);margin-bottom:6px;">No hay música descargada</div>
        <div>Descarga canciones usando el botón de flecha hacia abajo en cualquier pista para escucharlas sin conexión.</div>
      </div>
    `;
    return;
  }

  tracks.forEach((t) => listContainer.appendChild(createTrackRow(t, true)));
}

// --- Vista: Historial (History Desktop) ---
async function loadHistoryView() {
  state.activeView = "history";
  updateActiveNavButton("history");

  const container = document.getElementById("main-content");
  container.innerHTML = `
    <div class="home-section-header">
      <div class="home-section-title-wrap">
        <h1 class="page-heading" style="margin-bottom:2px;">Historial</h1>
        <span class="home-section-subtitle">Tus reproducciones recientes</span>
      </div>
      <button class="pill-btn-danger" id="clear-hist-btn">
        <img src="icons/trash.svg" style="width:14px;height:14px;" alt="">
        <span>Limpiar historial</span>
      </button>
    </div>

    <div class="tracks-list" id="history-content-list" style="margin-top:20px;">
      ${Array(6).fill('<div class="row-item skeleton"></div>').join("")}
    </div>
  `;

  document.getElementById("clear-hist-btn").addEventListener("click", async () => {
    if (confirm("¿Seguro que deseas borrar el historial de reproducción?")) {
      await storage.clearHistory();
      loadHistoryView();
    }
  });

  const history = await storage.getRecentHistory(60);
  const listContainer = document.getElementById("history-content-list");
  listContainer.innerHTML = "";

  if (history.length === 0) {
    listContainer.innerHTML = `<div style="padding:40px;color:var(--text-muted);text-align:center;">No hay reproducciones recientes registradas.</div>`;
    return;
  }

  history.forEach((t) => listContainer.appendChild(createTrackRow(t)));
}

// --- Vista: Página de Artista (Desktop Parity) ---
async function loadArtistView(browseId, name, photo = "") {
  const container = document.getElementById("main-content");
  container.innerHTML = `
    <button class="icon-btn" id="artist-back-btn" style="margin-bottom:16px;" title="Volver">
      <img src="icons/back.svg" alt="Volver">
    </button>

    <div class="artist-page-header">
      <img src="${photo || 'logo_cube.png'}" class="artist-header-avatar" id="artist-avatar" alt="${name}" />
      <div class="artist-header-info">
        <h1 class="artist-header-name" id="artist-name">${name}</h1>
        <div class="artist-header-subs" id="artist-subs">Cargando información del artista...</div>
        <div style="margin-top:10px;">
          <button class="pill-btn-accent" id="artist-play-all-btn" style="display:none;">
            <img src="icons/play.svg" style="width:16px;height:16px;filter:brightness(0) invert(1);" alt="">
            <span>Reproducir</span>
          </button>
        </div>
      </div>
    </div>

    <div class="home-section">
      <div class="home-section-header">
        <span class="home-section-title">Canciones más escuchadas</span>
      </div>
      <div class="tracks-list" id="artist-top-songs">
        ${Array(5).fill('<div class="row-item skeleton"></div>').join("")}
      </div>
    </div>

    <div class="home-section" id="artist-albums-section" style="display:none;">
      <div class="home-section-header">
        <span class="home-section-title">Álbumes</span>
      </div>
      <div class="albums-scroll-row" id="artist-albums-row"></div>
    </div>

    <div class="home-section" id="artist-singles-section" style="display:none;">
      <div class="home-section-header">
        <span class="home-section-title">Sencillos y EPs</span>
      </div>
      <div class="albums-scroll-row" id="artist-singles-row"></div>
    </div>
  `;

  document.getElementById("artist-back-btn").addEventListener("click", () => {
    loadHomeView();
  });

  try {
    const resp = await fetch(apiUrl(`api/artist/${browseId}`));
    const data = await resp.json();

    if (data.photo) document.getElementById("artist-avatar").src = data.photo;
    if (data.name) document.getElementById("artist-name").textContent = data.name;
    document.getElementById("artist-subs").textContent = data.subscribers ? `${data.subscribers} suscriptores` : "";

    const playAllBtn = document.getElementById("artist-play-all-btn");
    if (data.top_songs && data.top_songs.length > 0) {
      playAllBtn.style.display = "inline-flex";
      playAllBtn.onclick = () => {
        state.queue = [...data.top_songs];
        state.queueIndex = 0;
        playTrack(data.top_songs[0], true);
      };
    }

    // Top canciones
    const topContainer = document.getElementById("artist-top-songs");
    topContainer.innerHTML = "";
    (data.top_songs || []).forEach((t) => {
      topContainer.appendChild(createTrackRow(t));
    });

    // Álbumes
    if (data.albums && data.albums.length > 0) {
      document.getElementById("artist-albums-section").style.display = "block";
      const albRow = document.getElementById("artist-albums-row");
      albRow.innerHTML = "";
      data.albums.forEach((alb) => {
        const card = document.createElement("div");
        card.className = "album-card";
        card.innerHTML = `
          <img src="${alb.thumbnail}" class="album-cover" loading="lazy" alt="${alb.title}" />
          <div class="album-title">${alb.title}</div>
          <div class="album-subtitle">${alb.year || ''}</div>
        `;
        card.addEventListener("click", () => loadAlbumView(alb.browse_id));
        albRow.appendChild(card);
      });
    }

    // Sencillos
    if (data.singles && data.singles.length > 0) {
      document.getElementById("artist-singles-section").style.display = "block";
      const sngRow = document.getElementById("artist-singles-row");
      sngRow.innerHTML = "";
      data.singles.forEach((sng) => {
        const card = document.createElement("div");
        card.className = "album-card";
        card.innerHTML = `
          <img src="${sng.thumbnail}" class="album-cover" loading="lazy" alt="${sng.title}" />
          <div class="album-title">${sng.title}</div>
          <div class="album-subtitle">${sng.year || 'Sencillo'}</div>
        `;
        card.addEventListener("click", () => loadAlbumView(sng.browse_id));
        sngRow.appendChild(card);
      });
    }
  } catch (err) {
    console.error("Error al cargar artista:", err);
  }
}

// --- Vista: Página de Álbum (Desktop Parity) ---
async function loadAlbumView(browseId) {
  const container = document.getElementById("main-content");
  container.innerHTML = `
    <button class="icon-btn" id="album-back-btn" style="margin-bottom:16px;" title="Volver">
      <img src="icons/back.svg" alt="Volver">
    </button>

    <div class="artist-page-header">
      <img src="logo_cube.png" class="album-cover" id="album-cover-img" style="width:140px;height:140px;border-radius:16px;" />
      <div class="artist-header-info">
        <h1 class="artist-header-name" id="album-title">Cargando álbum...</h1>
        <div class="artist-header-subs" id="album-meta"></div>
        <div style="margin-top:10px;">
          <button class="pill-btn-accent" id="album-play-all-btn" style="display:none;">
            <img src="icons/play.svg" style="width:16px;height:16px;filter:brightness(0) invert(1);" alt="">
            <span>Reproducir álbum</span>
          </button>
        </div>
      </div>
    </div>

    <div class="tracks-list" id="album-tracks-list" style="margin-top:20px;">
      ${Array(6).fill('<div class="row-item skeleton"></div>').join("")}
    </div>
  `;

  document.getElementById("album-back-btn").addEventListener("click", () => {
    loadHomeView();
  });

  try {
    const resp = await fetch(apiUrl(`api/album/${browseId}`));
    const data = await resp.json();

    if (data.thumbnail) document.getElementById("album-cover-img").src = data.thumbnail;
    document.getElementById("album-title").textContent = data.title;
    document.getElementById("album-meta").textContent = `${data.artist || ''} · ${data.year || ''}`;

    const playAllBtn = document.getElementById("album-play-all-btn");
    if (data.tracks && data.tracks.length > 0) {
      playAllBtn.style.display = "inline-flex";
      playAllBtn.onclick = () => {
        state.queue = [...data.tracks];
        state.queueIndex = 0;
        playTrack(data.tracks[0], true);
      };
    }

    const list = document.getElementById("album-tracks-list");
    list.innerHTML = "";
    (data.tracks || []).forEach((t) => {
      list.appendChild(createTrackRow(t));
    });
  } catch (err) {
    console.error("Error al cargar álbum:", err);
  }
}

// --- Vista: Playlist Detalle ---
function loadPlaylistView(playlist) {
  const container = document.getElementById("main-content");
  container.innerHTML = `
    <button class="icon-btn" id="playlist-back-btn" style="margin-bottom:16px;" title="Volver">
      <img src="icons/back.svg" alt="Volver">
    </button>
    <div class="home-section-header">
      <div class="home-section-title-wrap">
        <h1 class="page-heading" style="margin-bottom:2px;">${playlist.name}</h1>
        <span class="home-section-subtitle">${(playlist.tracks || []).length} canciones</span>
      </div>
      <button class="pill-btn-accent" id="playlist-play-btn" ${(!playlist.tracks || playlist.tracks.length === 0) ? 'disabled' : ''}>
        <img src="icons/play.svg" style="width:16px;height:16px;filter:brightness(0) invert(1);" alt="">
        <span>Reproducir</span>
      </button>
    </div>
    <div class="tracks-list" id="playlist-tracks-list" style="margin-top:20px;"></div>
  `;

  document.getElementById("playlist-back-btn").addEventListener("click", () => {
    loadLibraryView("playlists");
  });

  const playBtn = document.getElementById("playlist-play-btn");
  if (playlist.tracks && playlist.tracks.length > 0) {
    playBtn.onclick = () => {
      state.queue = [...playlist.tracks];
      state.queueIndex = 0;
      playTrack(playlist.tracks[0], true);
    };
  }

  const list = document.getElementById("playlist-tracks-list");
  if (!playlist.tracks || playlist.tracks.length === 0) {
    list.innerHTML = `<div style="padding:40px;color:var(--text-muted);text-align:center;">Playlist vacía. Agrega canciones usando el menú de 3 puntos.</div>`;
    return;
  }

  playlist.tracks.forEach((t) => {
    list.appendChild(createTrackRow(t));
  });
}

// ==========================================================================
// UI del Reproductor Inferior
// ==========================================================================
function updatePlayerBarUI(track) {
  document.getElementById("player-thumb").src = track.thumbnail || "logo_cube.png";
  document.getElementById("player-title").textContent = track.title;
  document.getElementById("player-artist").textContent = `${track.artist || ''}${track.album ? ' · ' + track.album : ''}`;
  document.getElementById("total-time").textContent = formatDuration(track.duration);
  document.getElementById("video-dock-title").textContent = track.title;

  storage.isFavorite(track.id).then((isFav) => {
    document.getElementById("player-fav-btn").querySelector("img").src = isFav ? "icons/heart_filled.svg" : "icons/heart.svg";
  });

  highlightPlayingTracks();
}

function highlightPlayingTracks() {
  document.querySelectorAll(".row-item").forEach((el) => {
    el.classList.toggle("playing", state.currentTrack && el.getAttribute("data-track-id") === state.currentTrack.id);
  });
  document.querySelectorAll(".for-you-card").forEach((el) => {
    el.classList.toggle("playing", state.currentTrack && el.getAttribute("data-track-id") === state.currentTrack.id);
  });
}

function updatePlayPauseButton() {
  const icon = document.getElementById("play-pause-icon");
  icon.src = state.isPlaying ? "icons/pause.svg" : "icons/play.svg";
}

function updateProgress(current, duration) {
  document.getElementById("current-time").textContent = formatDuration(current);
  if (duration > 0) {
    document.getElementById("total-time").textContent = formatDuration(duration);
    const percent = Math.min(100, (current / duration) * 100);
    document.getElementById("progress-fill").style.width = `${percent}%`;
  }
}

function showOfflineIndicator(isOffline) {
  const badge = document.getElementById("playback-source-badge");
  if (isOffline) {
    badge.textContent = "⚡ Offline";
    badge.style.display = "inline-flex";
  } else {
    badge.style.display = "none";
  }
}

function renderQueue() {
  const list = document.getElementById("queue-body");
  list.innerHTML = "";

  if (state.queue.length === 0) {
    list.innerHTML = `<div style="color:var(--text-muted);text-align:center;padding:30px;">La cola está vacía</div>`;
    return;
  }

  state.queue.forEach((track, idx) => {
    const row = document.createElement("div");
    row.className = `row-item ${idx === state.queueIndex ? 'playing' : ''}`;
    row.style.height = "56px";
    row.style.padding = "6px 12px";
    row.innerHTML = `
      <div class="row-cover-wrap" style="width:40px;height:40px;min-width:40px;">
        <img src="${track.thumbnail}" class="row-cover" loading="lazy" />
      </div>
      <div class="row-details">
        <div class="row-title" style="font-size:13px;">${track.title}</div>
        <div class="row-subtitle" style="font-size:11px;">${track.artist || ''}</div>
      </div>
    `;
    row.addEventListener("click", () => {
      state.queueIndex = idx;
      playTrack(track, true);
    });
    list.appendChild(row);
  });
}

// ==========================================================================
// Menú Contextual de Pista
// ==========================================================================
function openTrackContextMenu(e, track) {
  state.contextMenuTrack = track;
  const menu = document.getElementById("track-context-menu");

  storage.isFavorite(track.id).then((isFav) => {
    document.getElementById("ctx-fav-icon").src = isFav ? "icons/heart_filled.svg" : "icons/heart.svg";
    document.getElementById("ctx-fav-text").textContent = isFav ? "Quitar de favoritas" : "Agregar a favoritas";
  });

  menu.style.display = "block";
  const x = Math.min(window.innerWidth - 260, e.clientX);
  const y = Math.min(window.innerHeight - 240, e.clientY);
  menu.style.left = `${x}px`;
  menu.style.top = `${y}px`;
}

function closeTrackContextMenu() {
  const menu = document.getElementById("track-context-menu");
  if (menu) menu.style.display = "none";
}

document.addEventListener("click", (e) => {
  if (!e.target.closest("#track-context-menu") && !e.target.closest(".more-btn")) {
    closeTrackContextMenu();
  }
});

// ==========================================================================
// Setup Listeners y Controles Globales
// ==========================================================================
function setupUIListeners() {
  // Toggle del Sidebar Flotante (Desktop Parity)
  const sidebarToggleBtn = document.getElementById("sidebar-toggle-btn");
  const sidebar = document.getElementById("app-sidebar");
  sidebarToggleBtn.addEventListener("click", () => {
    state.sidebarCollapsed = !state.sidebarCollapsed;
    sidebar.classList.toggle("collapsed", state.sidebarCollapsed);
    localStorage.setItem("musicbox_sidebar_collapsed", state.sidebarCollapsed ? "true" : "false");

    const toggleIcon = document.getElementById("sidebar-toggle-icon");
    if (state.sidebarCollapsed) {
      toggleIcon.src = "icons/sidebar_expand.svg";
      sidebarToggleBtn.title = "Expandir panel";
    } else {
      toggleIcon.src = "icons/sidebar_collapse.svg";
      sidebarToggleBtn.title = "Contraer panel";
    }
  });

  // Navegación Sidebar (Inicio, Mi música, Descargas, Historial)
  document.getElementById("nav-home").addEventListener("click", loadHomeView);
  document.getElementById("nav-library").addEventListener("click", () => loadLibraryView("songs"));
  document.getElementById("nav-downloads").addEventListener("click", loadDownloadsView);
  document.getElementById("nav-history").addEventListener("click", loadHistoryView);

  // Logo BrandMark click vuelve a inicio
  document.getElementById("brand-mark").addEventListener("click", loadHomeView);

  // Reproductor Controles
  document.getElementById("play-pause-btn").addEventListener("click", togglePlayPause);
  document.getElementById("next-btn").addEventListener("click", playNext);
  document.getElementById("prev-btn").addEventListener("click", playPrevious);

  // Barra de Progreso Seek
  const progressWrap = document.getElementById("progress-wrap");
  progressWrap.addEventListener("click", (e) => {
    const rect = progressWrap.getBoundingClientRect();
    const pos = (e.clientX - rect.left) / rect.width;
    let duration = state.currentTrack?.duration || 0;
    if (state.isOfflinePlayback && audio.duration) duration = audio.duration;
    else if (ytPlayer?.getDuration) duration = ytPlayer.getDuration() || duration;

    if (duration > 0) {
      seekTo(pos * duration);
    }
  });

  // Control de Volumen
  const volumeWrap = document.getElementById("volume-wrap");
  volumeWrap.addEventListener("click", (e) => {
    const rect = volumeWrap.getBoundingClientRect();
    const vol = Math.max(0, Math.min(1, (e.clientX - rect.left) / rect.width));
    state.volume = vol;
    state.isMuted = false;
    audio.volume = vol;
    if (ytReady && ytPlayer && ytPlayer.setVolume) ytPlayer.setVolume(vol * 100);
    document.getElementById("volume-fill").style.width = `${vol * 100}%`;
    document.getElementById("volume-icon").src = "icons/volume.svg";
  });

  document.getElementById("mute-btn").addEventListener("click", () => {
    state.isMuted = !state.isMuted;
    if (state.isMuted) {
      audio.volume = 0;
      if (ytReady && ytPlayer && ytPlayer.mute) ytPlayer.mute();
      document.getElementById("volume-fill").style.width = `0%`;
      document.getElementById("volume-icon").src = "icons/volume_muted.svg";
    } else {
      audio.volume = state.volume;
      if (ytReady && ytPlayer && ytPlayer.unMute) {
        ytPlayer.unMute();
        ytPlayer.setVolume(state.volume * 100);
      }
      document.getElementById("volume-fill").style.width = `${state.volume * 100}%`;
      document.getElementById("volume-icon").src = "icons/volume.svg";
    }
  });

  // Aleatorio y Repetición
  const shuffleBtn = document.getElementById("shuffle-btn");
  shuffleBtn.addEventListener("click", () => {
    state.isShuffle = !state.isShuffle;
    shuffleBtn.classList.toggle("active", state.isShuffle);
    showToast(state.isShuffle ? "Aleatorio activado" : "Aleatorio desactivado");
  });

  const repeatBtn = document.getElementById("repeat-btn");
  repeatBtn.addEventListener("click", () => {
    if (state.repeatMode === "off") {
      state.repeatMode = "all";
      repeatBtn.classList.add("active");
      document.getElementById("repeat-icon").src = "icons/repeat.svg";
      showToast("Repetir toda la cola");
    } else if (state.repeatMode === "all") {
      state.repeatMode = "one";
      repeatBtn.classList.add("active");
      document.getElementById("repeat-icon").src = "icons/repeat_one.svg";
      showToast("Repetir esta canción");
    } else {
      state.repeatMode = "off";
      repeatBtn.classList.remove("active");
      document.getElementById("repeat-icon").src = "icons/repeat.svg";
      showToast("Repetición desactivada");
    }
  });

  // Cajones de Letras y Cola
  const lyricsBtn = document.getElementById("lyrics-toggle-btn");
  const lyricsDrawer = document.getElementById("lyrics-drawer");
  lyricsBtn.addEventListener("click", () => {
    state.lyricsOpen = !state.lyricsOpen;
    lyricsDrawer.classList.toggle("open", state.lyricsOpen);
    lyricsBtn.classList.toggle("active", state.lyricsOpen);
    if (state.lyricsOpen) {
      document.getElementById("queue-drawer").classList.remove("open");
      state.queueOpen = false;
    }
  });

  document.getElementById("close-lyrics-btn").addEventListener("click", () => {
    state.lyricsOpen = false;
    lyricsDrawer.classList.remove("open");
    lyricsBtn.classList.remove("active");
  });

  const queueBtn = document.getElementById("queue-toggle-btn");
  const queueDrawer = document.getElementById("queue-drawer");
  queueBtn.addEventListener("click", () => {
    state.queueOpen = !state.queueOpen;
    queueDrawer.classList.toggle("open", state.queueOpen);
    if (state.queueOpen) {
      lyricsDrawer.classList.remove("open");
      state.lyricsOpen = false;
      renderQueue();
    }
  });

  document.getElementById("close-queue-btn").addEventListener("click", () => {
    state.queueOpen = false;
    queueDrawer.classList.remove("open");
  });

  document.getElementById("clear-queue-btn").addEventListener("click", () => {
    state.queue = state.currentTrack ? [state.currentTrack] : [];
    state.queueIndex = state.currentTrack ? 0 : -1;
    renderQueue();
    showToast("Cola limpiada");
  });

  // Favorito en Reproductor
  document.getElementById("player-fav-btn").addEventListener("click", async () => {
    if (!state.currentTrack) return;
    const isNowFav = await storage.toggleFavorite(state.currentTrack);
    document.getElementById("player-fav-btn").querySelector("img").src = isNowFav ? "icons/heart_filled.svg" : "icons/heart.svg";
    showToast(isNowFav ? "Guardado en Favoritos" : "Eliminado de Favoritos");
    highlightPlayingTracks();
  });

  // Configuración Modal
  setupSettingsModal();

  // Acciones de Menú Contextual
  setupContextMenuActions();
}

function updateActiveNavButton(viewName) {
  document.querySelectorAll(".nav-item").forEach((item) => {
    item.classList.toggle("active", item.getAttribute("data-view") === viewName);
  });
}

function setupContextMenuActions() {
  document.getElementById("ctx-play-next").addEventListener("click", () => {
    if (state.contextMenuTrack) {
      state.queue.splice(state.queueIndex + 1, 0, state.contextMenuTrack);
      showToast(`A continuación: ${state.contextMenuTrack.title}`);
      renderQueue();
    }
    closeTrackContextMenu();
  });

  document.getElementById("ctx-add-queue").addEventListener("click", () => {
    if (state.contextMenuTrack) {
      state.queue.push(state.contextMenuTrack);
      showToast(`Agregada a la cola: ${state.contextMenuTrack.title}`);
      renderQueue();
    }
    closeTrackContextMenu();
  });

  document.getElementById("ctx-toggle-fav").addEventListener("click", async () => {
    if (state.contextMenuTrack) {
      const isNowFav = await storage.toggleFavorite(state.contextMenuTrack);
      showToast(isNowFav ? "Guardado en Favoritos" : "Eliminado de Favoritos");
      if (state.activeView === "library") loadLibraryView("favorites");
    }
    closeTrackContextMenu();
  });

  document.getElementById("ctx-download").addEventListener("click", () => {
    if (state.contextMenuTrack) {
      downloadTrackForOffline(state.contextMenuTrack);
    }
    closeTrackContextMenu();
  });

  document.getElementById("ctx-add-playlist").addEventListener("click", () => {
    if (state.contextMenuTrack) {
      openAddToPlaylistModal(state.contextMenuTrack);
    }
    closeTrackContextMenu();
  });
}

// ==========================================================================
// Modal: Configuración (Desktop Main.qml:568-710 EXACTO)
// ==========================================================================
function setupSettingsModal() {
  const modal = document.getElementById("settings-modal");
  document.getElementById("settings-btn").addEventListener("click", () => {
    modal.style.display = "flex";
    checkCookiesStatus();
    updateOfflineStorageInfo();
    updateStatsSummary();
  });

  document.getElementById("close-settings-btn").addEventListener("click", () => {
    modal.style.display = "none";
  });

  modal.addEventListener("click", (e) => {
    if (e.target === modal) modal.style.display = "none";
  });

  // Tema Switch (Desktop Main.qml:597)
  const themeSwitch = document.getElementById("settings-dark-switch");
  themeSwitch.checked = state.theme === "dark";
  themeSwitch.onchange = () => {
    setDarkMode(themeSwitch.checked);
  };

function setDarkMode(enabled) {
  state.theme = enabled ? "dark" : "light";
  document.documentElement.setAttribute("data-theme", state.theme);
  localStorage.setItem("musicbox_theme", state.theme);
  const icon = document.getElementById("settings-theme-icon");
  if (icon) icon.src = state.theme === "dark" ? "icons/moon.svg" : "icons/sun.svg";
  const sw = document.getElementById("settings-dark-switch");
  if (sw) sw.checked = enabled;
}

function applyPrimaryColor(color) {
  state.accent = color === "orange" ? "orange" : "blue";
  if (state.accent === "orange") {
    document.documentElement.setAttribute("data-accent", "orange");
  } else {
    document.documentElement.removeAttribute("data-accent");
  }
  localStorage.setItem("musicbox_accent", state.accent);
  const blueBtn = document.getElementById("color-blue-btn");
  const orangeBtn = document.getElementById("color-orange-btn");
  if (blueBtn && orangeBtn) {
    if (state.accent === "orange") {
      orangeBtn.classList.add("active");
      blueBtn.classList.remove("active");
    } else {
      blueBtn.classList.add("active");
      orangeBtn.classList.remove("active");
    }
  }
  stopBrandAnimation();
  startBrandAnimation();
}

  // Color Primario: Azul / Naranjo (Desktop Main.qml:604)
  const blueBtn = document.getElementById("color-blue-btn");
  const orangeBtn = document.getElementById("color-orange-btn");

  if (state.accent === "orange") {
    orangeBtn.classList.add("active");
    blueBtn.classList.remove("active");
  } else {
    blueBtn.classList.add("active");
    orangeBtn.classList.remove("active");
  }

  blueBtn.onclick = () => applyPrimaryColor("blue");
  orangeBtn.onclick = () => applyPrimaryColor("orange");

  // Idioma (Desktop Main.qml:611)
  document.querySelectorAll(".segment-btn").forEach((btn) => {
    if (btn.getAttribute("data-lang") === state.language) {
      btn.classList.add("active");
    }
    btn.onclick = () => {
      document.querySelectorAll(".segment-btn").forEach((b) => b.classList.remove("active"));
      btn.classList.add("active");
      state.language = btn.getAttribute("data-lang");
      localStorage.setItem("musicbox_lang", state.language);
      showToast(`Idioma cambiado a: ${btn.textContent}`);
    };
  });

  // Subir cookies.txt
  const fileInput = document.getElementById("cookies-file-input");
  fileInput.addEventListener("change", async (e) => {
    const file = e.target.files[0];
    if (!file) return;
    const formData = new FormData();
    formData.append("file", file);

    showToast("Subiendo cookies al servidor...");
    try {
      const resp = await fetch(apiUrl("api/cookies/upload"), {
        method: "POST",
        body: formData
      });
      if (resp.ok) {
        showToast("Cookies de YouTube guardadas correctamente");
        checkCookiesStatus();
      } else {
        showToast("Error al guardar cookies");
      }
    } catch (err) {
      showToast("Error de conexión al subir cookies");
    }
  });

  // Borrar descargas offline
  document.getElementById("clear-offline-btn").addEventListener("click", async () => {
    if (confirm("¿Estás seguro de que deseas eliminar todas las descargas sin conexión?")) {
      await storage.clearAllOfflineTracks();
      updateOfflineStorageInfo();
      showToast("Descargas eliminadas");
      if (state.activeView === "downloads") loadDownloadsView();
    }
  });

  // Clic en Estadísticas
  document.getElementById("stats-block-btn").onclick = () => {
    modal.style.display = "none";
    openStatsModal();
  };
}

async function openStatsModal() {
  const modal = document.getElementById("stats-modal");
  if (!modal) return;
  modal.style.display = "flex";

  const closeBtn = document.getElementById("close-stats-btn");
  const close = () => { modal.style.display = "none"; };
  if (closeBtn) closeBtn.onclick = close;
  modal.onclick = (e) => {
    if (e.target === modal) close();
  };

  const timeVal = document.getElementById("stats-time-val");
  const playsVal = document.getElementById("stats-plays-val");
  const listEl = document.getElementById("stats-ranked-list");

  const hist = await storage.getRecentHistory(200);
  let totalMs = 0;
  let totalPlays = 0;
  hist.forEach((t) => {
    const plays = t.plays || 1;
    totalPlays += plays;
    totalMs += (t.duration || 180) * 1000 * plays;
  });

  const durationStr = (ms) => {
    const sec = Math.floor((ms || 0) / 1000);
    const min = Math.floor(sec / 60);
    if (min >= 60) {
      return `${Math.floor(min / 60)} h ${min % 60} min`;
    } else if (min > 0) {
      return `${min} min`;
    }
    return `${sec} s`;
  };

  if (timeVal) timeVal.textContent = durationStr(totalMs);
  if (playsVal) playsVal.textContent = String(totalPlays);

  if (listEl) {
    listEl.innerHTML = "";
    if (hist.length === 0) {
      listEl.innerHTML = `
        <div style="text-align:center;padding:36px 16px;color:var(--text-muted);font-size:14px;">
          Reproduce tu primera canción y empieza tu historia musical.
        </div>
      `;
      return;
    }

    const sorted = [...hist].sort((a, b) => (b.plays || 1) - (a.plays || 1));
    const maxPlays = Math.max(1, sorted[0].plays || 1);

    sorted.slice(0, 50).forEach((t, idx) => {
      const plays = t.plays || 1;
      const songMs = (t.duration || 180) * 1000 * plays;
      const percent = Math.max(2, Math.min(100, Math.round((plays / maxPlays) * 100)));

      const row = document.createElement("div");
      row.className = "row-item";
      row.style.cursor = "pointer";
      row.style.marginBottom = "6px";
      row.innerHTML = `
        <div style="width:24px;text-align:center;font-weight:700;color:var(--text-muted);font-size:13px;flex-shrink:0;">${idx + 1}</div>
        <div class="row-cover-wrap" style="width:42px;height:42px;flex-shrink:0;">
          <img src="${t.thumbnail || 'icons/music.svg'}" class="row-cover" onerror="this.src='icons/music.svg'" />
        </div>
        <div class="row-details" style="flex:1;min-width:0;">
          <div class="row-title" style="font-weight:600;font-size:14px;">${t.title}</div>
          <div class="row-subtitle" style="font-size:12px;color:var(--text-muted);margin-bottom:4px;">
            ${t.artist ? t.artist + ' &middot; ' : ''}${plays} reproducciones &middot; ${durationStr(songMs)}
          </div>
          <div style="width:100%;height:3px;background:var(--border-color);border-radius:2px;overflow:hidden;">
            <div style="width:${percent}%;height:100%;background:var(--accent-color);border-radius:2px;"></div>
          </div>
        </div>
      `;
      row.addEventListener("click", () => {
        close();
        playTrack(t, sorted);
      });
      listEl.appendChild(row);
    });
  }
}

async function updateStatsSummary() {
  const summaryEl = document.getElementById("stats-clicks-summary");
  if (!summaryEl) return;
  const hist = await storage.getRecentHistory(100);
  let totalPlays = 0;
  hist.forEach((t) => (totalPlays += t.plays || 1));
  summaryEl.textContent = `${totalPlays} reproducciones en ${hist.length} canciones`;
}

async function checkCookiesStatus() {
  const desc = document.getElementById("cookies-status-desc");
  try {
    const resp = await fetch(apiUrl("api/cookies/status"));
    const data = await resp.json();
    if (data.active) {
      desc.textContent = "Configuradas y activas en el servidor (descargas habilitadas)";
      desc.style.color = "var(--green)";
    } else {
      desc.textContent = "No configuradas. Sube cookies.txt si YouTube bloquea descargas directas.";
      desc.style.color = "var(--text-muted)";
    }
  } catch (err) {
    desc.textContent = "No se pudo verificar el estado del servidor";
  }
}

async function updateOfflineStorageInfo() {
  const desc = document.getElementById("offline-storage-desc");
  const tracks = await storage.getAllOfflineTracks();
  let totalBytes = 0;
  tracks.forEach((t) => (totalBytes += t.sizeBytes || 0));
  const mb = (totalBytes / (1024 * 1024)).toFixed(1);
  desc.textContent = `${tracks.length} canciones almacenadas localmente (${mb} MB)`;
}

// --- Crear y Añadir a Playlists ---
function openNewPlaylistModal() {
  const modal = document.getElementById("new-playlist-modal");
  const input = document.getElementById("new-playlist-name");
  input.value = "";
  modal.style.display = "flex";

  const close = () => (modal.style.display = "none");
  document.getElementById("close-new-playlist-btn").onclick = close;
  document.getElementById("cancel-new-playlist-btn").onclick = close;

  document.getElementById("confirm-new-playlist-btn").onclick = async () => {
    const name = input.value.trim();
    if (!name) return;
    const pl = {
      id: "pl_" + Date.now(),
      name,
      createdAt: Date.now(),
      tracks: []
    };
    await storage.savePlaylist(pl);
    showToast(`Playlist creada: ${name}`);
    close();
    if (state.activeView === "library") loadLibraryView("playlists");
  };
}

async function openAddToPlaylistModal(track) {
  const modal = document.getElementById("add-to-playlist-modal");
  const pickerList = document.getElementById("playlists-picker-list");
  modal.style.display = "flex";

  const close = () => (modal.style.display = "none");
  document.getElementById("close-add-playlist-btn").onclick = close;
  document.getElementById("cancel-add-playlist-btn").onclick = close;

  const playlists = await storage.getAllPlaylists();
  pickerList.innerHTML = "";

  if (playlists.length === 0) {
    pickerList.innerHTML = `<div style="color:var(--text-muted);text-align:center;padding:16px;">No tienes playlists. Crea una primero en Mi música.</div>`;
    return;
  }

  playlists.forEach((pl) => {
    const item = document.createElement("div");
    item.className = "context-item";
    item.innerHTML = `
      <img src="icons/playlist.svg" class="ctx-icon" />
      <span>${pl.name} (${(pl.tracks || []).length})</span>
    `;
    item.addEventListener("click", async () => {
      pl.tracks = pl.tracks || [];
      if (!pl.tracks.some((t) => t.id === track.id)) {
        pl.tracks.push(track);
        await storage.savePlaylist(pl);
        showToast(`Agregada a "${pl.name}"`);
      } else {
        showToast(`Ya está en "${pl.name}"`);
      }
      close();
    });
    pickerList.appendChild(item);
  });
}

function formatDuration(sec) {
  if (!sec || isNaN(sec)) return "0:00";
  const m = Math.floor(sec / 60);
  const s = Math.floor(sec % 60);
  return `${m}:${s < 10 ? "0" : ""}${s}`;
}

function showToast(message) {
  let container = document.getElementById("toast-container");
  if (!container) {
    container = document.createElement("div");
    container.id = "toast-container";
    container.className = "toast-container";
    document.body.appendChild(container);
  }

  const toast = document.createElement("div");
  toast.className = "toast";
  toast.textContent = message;
  container.appendChild(toast);

  setTimeout(() => {
    toast.style.opacity = "0";
    toast.style.transform = "translateY(10px)";
    toast.style.transition = "all 0.25s";
    setTimeout(() => toast.remove(), 250);
  }, 2800);
}