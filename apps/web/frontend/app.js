/**
 * MusicBox Web - interfaz compartida con MusicBox Desktop
 * Motor de Reproducción Híbrido (Online YouTube IFrame + Offline IndexedDB)
 */

// --- Base de Datos IndexedDB (MusicBoxDB) ---
class MusicBoxStorage {
  constructor() {
    this.dbName = new URLSearchParams(location.search).has("verification") ? "MusicBoxVerification" : "MusicBoxDB";
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
        folder: track.folder || '',
        audioBlob: audioBlob,
        sizeBytes: audioBlob ? audioBlob.size : 0,
        lyrics: lyricsData || null,
        savedAt: Date.now()
      };
      const req = store.put(data);
      tx.oncomplete = () => resolve(data);
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
      } else {
        store.put({ ...track, addedAt: Date.now() });
      }
      tx.oncomplete = () => { scheduleCloudSave(); resolve(!isFav); };
      tx.onerror = () => reject(tx.error);
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
      tx.oncomplete = () => { resolve(playlist); scheduleCloudSave(); };
      req.onerror = (e) => reject(e);
    });
  }
}

function readSavedUser() {
  if (new URLSearchParams(location.search).has('verification')) return { name: 'Pruebas', authenticated: false };
  try { return JSON.parse(localStorage.getItem('musicbox_user') || 'null'); } catch { return null; }
}
// --- Estado Global ---
const state = {
  user: readSavedUser(),
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
const escapeHTML = MusicBoxCore.escape;
let cloudBase = null;
let cloudSaveTimer = null;
let cloudSaving = false;
let cloudSaveAgain = false;
const dirtyPreferences = {};
state.downloadFormat = localStorage.getItem('musicbox_download_format') || 'mp3';
state.downloadQuality = Number(localStorage.getItem('musicbox_download_quality') || 192);
let revocationUnsubscribe = null;
const webDeviceId = localStorage.getItem('musicbox_web_device') || ('web_' + crypto.randomUUID().replaceAll('-', ''));
localStorage.setItem('musicbox_web_device', webDeviceId);
function sessionStartedAt(user) { return state.user?.uid === user.uid ? state.user.signedInAt || Date.now() : Date.now(); }
let applyingCloud = false;
let playbackGeneration = 0;
let searchGeneration = 0;
let currentBlobUrl = null;
let activePlaylist = null;
let recordedGeneration = -1;
const listeningClock = new MusicBoxCore.ListeningClock();
let fallbackGeneration = -1;
function recordPlayback() {
  if (!state.currentTrack || listeningClock.elapsed < 30000 || recordedGeneration === playbackGeneration) return;
  recordedGeneration = playbackGeneration;
  storage.recordHistory(state.currentTrack);
  recordWebStats(state.currentTrack,0,true);
}
function scheduleCloudSave() {
  if (state.user?.authenticated && state.user.uid) localStorage.setItem('musicbox_pending_prefs:' + state.user.uid, JSON.stringify(dirtyPreferences));
  if (applyingCloud || !cloudBase || !state.user?.authenticated) return;
  clearTimeout(cloudSaveTimer);
  cloudSaveTimer = setTimeout(pushStateToFirestore, 600);
}
let audio = document.createElement('video'); // Current native engine; crossfade temporarily retains its predecessor.
audio.id = 'local-media-player';
audio.playsInline = true;
audio.controls = true;
audio.preload = 'metadata';
audio.style.display = 'none';
let ytPlayer = null; // Motor para streaming online
let ytReady = false;
let syncInterval = null;

// Helper para URLs de la API relativas
function apiUrl(endpoint) {
  const url = new URL(endpoint.replace(/^\//, ""), new URL('./', window.location.href));
  return url.pathname + url.search;
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
          if (state.isOfflinePlayback) return;
          // YT.PlayerState: 1 (playing), 2 (paused), 0 (ended), 3 (buffering)
          if (event.data === 1) {
            recordPlayback();
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
        onError: async (e) => {
          if (state.isOfflinePlayback) return;
          state.isPlaying = false;
          updatePlayPauseButton();
          console.warn("[MusicBox] YouTube player warning/error:", e.data);
          const generation = playbackGeneration;
          if ((e.data === 150 || e.data === 101) && state.currentTrack && fallbackGeneration !== generation) {
            fallbackGeneration = generation;
            const originalId = state.currentTrack.id;
            try {
              const response = await fetch(apiUrl(`api/video-source/${originalId}`));
              const source = await response.json();
              if (generation !== playbackGeneration || state.isOfflinePlayback) return;
              if (source.id) { ytPlayer.loadVideoById(source.id); ytPlayer.playVideo(); return; }
            } catch {}
          }
          if (generation === playbackGeneration) showToast('YouTube no permite reproducir esta grabación aquí. Elige otra desde Más opciones → Ver alternativas.');
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
  if (!verificationMode && "serviceWorker" in navigator) {
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
const verificationMode = new URLSearchParams(location.search).has('verification');
const firebaseConfig = window.MUSICBOX_FIREBASE_CONFIG;


let fbApp = null;
let fbAuth = null;
let fbDb = null;

function initFirebase() {
  if (!verificationMode && firebaseConfig && window.firebase && !fbApp) {
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
        signedInAt: sessionStartedAt(fbUser)
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
        signedInAt: sessionStartedAt(fbUser)
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
      if (avatarWrap) avatarWrap.innerHTML = `<img src="${escapeHTML(state.user.photo)}" style="width:100%;height:100%;border-radius:50%;object-fit:cover;" alt="" />`;
      if (modalAvatarWrap) modalAvatarWrap.innerHTML = `<img src="${escapeHTML(state.user.photo)}" style="width:100%;height:100%;border-radius:50%;object-fit:cover;" alt="" />`;
    } else {
      if (avatarWrap) avatarWrap.innerHTML = `<span>${escapeHTML(initial)}</span>`;
      if (modalAvatarWrap) modalAvatarWrap.innerHTML = `<span>${escapeHTML(initial)}</span>`;
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

      const user = {
        uid: fbUser.uid,
        name: fbUser.displayName || fbUser.email,
        email: fbUser.email,
        photo: fbUser.photoURL || "",
        authenticated: true,
        signedInAt: sessionStartedAt(fbUser)
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
          errorText.textContent = "Este dominio no está autorizado para iniciar sesión. Revisa los dominios autorizados en Firebase Authentication.";
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
    if (state.user?.uid && cloudBase) {
      const section = MusicBoxCore.section(await storage.getAllPlaylists(), await storage.getAllFavorites(), cloudBase);
      localStorage.setItem('musicbox_cloud_local:' + state.user.uid, JSON.stringify(section));
    }
    if (fbAuth && state.user && state.user.authenticated) {
      try {
        await fbAuth.signOut();
      } catch (e) {
        console.warn("SignOut error:", e);
      }
    }
    state.user = null;
    cloudBase = null;
    for (const key of Object.keys(dirtyPreferences)) delete dirtyPreferences[key];
    revocationUnsubscribe?.(); revocationUnsubscribe = null;
    clearTimeout(cloudSaveTimer);
    audio.pause();
    ytPlayer?.pauseVideo?.();
    localStorage.removeItem("musicbox_user");
    await applyCloudSection({playlists:{Favoritos:[]}});
    localStorage.removeItem('musicbox_library_owner');
    document.getElementById("settings-modal").style.display = "none";
    loginOverlay.style.display = "flex";
    showToast("Sesión cerrada");
  };
}

// Sincronización con Firestore (Paridad total con Desktop y Android)
async function applyCloudSection(section) {
  applyingCloud = true;
  try {
    const online = section.onlineTracks || {};
    const all = section.playlists || {};
    const tx = storage.db.transaction(["favorites", "playlists"], "readwrite");
    tx.objectStore("favorites").clear();
    tx.objectStore("playlists").clear();
    for (const entry of all.Favoritos || []) {
      const item = MusicBoxCore.track(entry, online);
      if (item?.id) tx.objectStore("favorites").put(item);
    }
    const order = section.playlistOrder || Object.keys(all);
    for (const [name, entries] of Object.entries(all)) {
      if (name === "Favoritos" || !Array.isArray(entries)) continue;
      const tracks = entries.map(e => MusicBoxCore.track(e, online)).filter(Boolean);
      tx.objectStore("playlists").put({ id: name, name, tracks,
        unavailableEntries: entries.filter(e => !MusicBoxCore.track(e, online)),
        description: section.descriptions?.[name] || '', pinned: (section.pinnedPlaylists || []).includes(name),
        autoDownload: (section.autoDownload || []).includes(name),
        order: order.indexOf(name) });
    }
    await new Promise((resolve, reject) => { tx.oncomplete = resolve; tx.onerror = reject; tx.onabort = reject; });
  } finally { applyingCloud = false; }
}

async function syncWithFirestore(uid, reportErrors=false) {
  initFirebase();
  if (!fbDb || !uid) return;
  try {
    const snap = await fbDb.collection("users").doc(uid).collection("state").doc("musicbox").get();
    if (state.user?.uid !== uid) return;
    const remote = snap.exists ? JSON.parse(snap.data().payload || '{}') : {};
    const section = remote.playlists || { playlists: { Favoritos: [] } };
    // Merge only a previously known copy of this account. A guest or another account is never uploaded.
    let incoming = section;
    const savedBase = localStorage.getItem('musicbox_cloud_base:' + uid);
    if (savedBase) {
      const base = JSON.parse(savedBase);
      const savedLocal = localStorage.getItem('musicbox_cloud_local:' + uid);
      const local = localStorage.getItem('musicbox_library_owner') === uid
        ? MusicBoxCore.section(await storage.getAllPlaylists(), await storage.getAllFavorites(), base)
        : savedLocal ? JSON.parse(savedLocal) : base;
      incoming = MusicBoxCore.merge(base, local, section);
    }
    await applyCloudSection(incoming);
    localStorage.setItem('musicbox_library_owner', uid);
    localStorage.setItem('musicbox_cloud_base:' + uid, JSON.stringify(section));
    localStorage.setItem('musicbox_cloud_local:' + uid, JSON.stringify(incoming));
    cloudBase = JSON.parse(JSON.stringify(section));
    await registerWebDevice(uid);
    const prefs = remote.preferences || {};
    mergeWebStats(remote.clicks);
    for (const key of Object.keys(dirtyPreferences)) delete dirtyPreferences[key];
    Object.assign(dirtyPreferences, JSON.parse(localStorage.getItem('musicbox_pending_prefs:' + uid) || '{}'));
    setDarkMode(dirtyPreferences.darkMode ?? prefs.darkMode ?? remote.darkMode ?? (state.theme === 'dark'), false);
    applyPrimaryColor(dirtyPreferences.accentColor || prefs.accentColor || prefs.primaryColor || state.accent, false);
    if (dirtyPreferences.language || prefs.language) { state.language = dirtyPreferences.language || prefs.language; localStorage.setItem('musicbox_lang', state.language); applyLanguage(); }
    const remoteFormat = String(dirtyPreferences.autoFormat || prefs.autoFormat || state.downloadFormat).toLowerCase();
    if (['mp3','mp4'].includes(remoteFormat)) state.downloadFormat=remoteFormat;
    const qualityKey=state.downloadFormat==='mp3'?'quality_mp3':'quality_mp4';
    const quality=Number.parseInt(dirtyPreferences[qualityKey] || prefs[qualityKey] || state.downloadQuality,10);
    const allowed=state.downloadFormat==='mp3'?[128,192,256,320]:[360,480,720,1080];
    state.downloadQuality=allowed.includes(quality)?quality:(state.downloadFormat==='mp3'?192:720);
    localStorage.setItem('musicbox_download_format',state.downloadFormat);localStorage.setItem('musicbox_download_quality',state.downloadQuality);
    if (state.activeView === 'library') await loadLibraryView();
    showToast("Biblioteca sincronizada con tu cuenta");
    if (!verificationMode) runAutoDownloads();
    if (JSON.stringify(incoming) !== JSON.stringify(section) || Object.keys(dirtyPreferences).length) scheduleCloudSave();
  } catch (err) {
    cloudBase = null;
    showToast("No se pudo sincronizar. Tus cambios se conservan en este navegador; vuelve a iniciar sesión para reintentar.");
    console.warn("[MusicBox] No se pudo leer la biblioteca", err.code || err.message);
    if(reportErrors)throw err;
  }
}

async function registerWebDevice(uid) {
  const ref = fbDb.collection('users').doc(uid).collection('state').doc('musicbox');
  await fbDb.runTransaction(async transaction => {
    const snap = await transaction.get(ref);
    if (state.user?.uid !== uid) return;
    const remote = snap.exists ? JSON.parse(snap.data().payload || '{}') : {};
    const payload = { ...remote, devices: { ...remote.devices, [webDeviceId]: {name:'MusicBox Web',platform:'Web',lastSeen:Date.now()} } };
    transaction.set(ref,{payload:JSON.stringify(payload)},{merge:true});
  });
  revocationUnsubscribe?.();
  revocationUnsubscribe = fbDb.collection('users').doc(uid).collection('state').doc('revocations').onSnapshot(snapshot => {
    if (state.user?.uid === uid && Number(snapshot.data()?.['d_' + webDeviceId] || 0) > state.user.signedInAt) document.getElementById('logout-button').click();
  }, () => showToast('No se pudo comprobar el estado de la sesión. Revisa tu conexión.'));
}

async function pushStateToFirestore() {
  if (!fbDb || !cloudBase || !state.user?.authenticated || !state.user.uid) return;
  if (cloudSaving) { cloudSaveAgain = true; return; }
  cloudSaving = true;
  const uid = state.user.uid;
  const base = JSON.parse(JSON.stringify(cloudBase));
  const local = MusicBoxCore.section(await storage.getAllPlaylists(), await storage.getAllFavorites(), base);
  localStorage.setItem('musicbox_cloud_local:' + uid, JSON.stringify(local));
  const preferences = { ...dirtyPreferences };
  try {
    const ref = fbDb.collection('users').doc(uid).collection('state').doc('musicbox');
    const synced = await fbDb.runTransaction(async transaction => {
      const snap = await transaction.get(ref);
      if (state.user?.uid !== uid) throw new Error('Session changed');
      const remote = snap.exists ? JSON.parse(snap.data().payload || '{}') : {};
      const merged = MusicBoxCore.merge(base, local, remote.playlists || {});
      const payload = { ...remote, schema: 1, playlists: merged, favorites: merged.playlists?.Favoritos || [],
        clicks: MusicBoxCore.mergeStats(remote.clicks || {},getWebStats()),
        devices: { ...remote.devices, [webDeviceId]: {name:'MusicBox Web',platform:'Web',lastSeen:Date.now()} },
        preferences: { ...remote.preferences, ...preferences } };
      if ('darkMode' in preferences) payload.darkMode = preferences.darkMode;
      transaction.set(ref, { payload: JSON.stringify(payload) }, { merge: true });
      return merged;
    });
    if (state.user?.uid === uid) {
      const current = MusicBoxCore.section(await storage.getAllPlaylists(), await storage.getAllFavorites(), base);
      const reconciled = MusicBoxCore.merge(local, current, synced);
      await applyCloudSection(reconciled);
      cloudBase = synced;
      localStorage.setItem('musicbox_cloud_base:' + uid, JSON.stringify(synced));
      localStorage.setItem('musicbox_cloud_local:' + uid, JSON.stringify(reconciled));
      for (const [key, value] of Object.entries(preferences)) if (dirtyPreferences[key] === value) delete dirtyPreferences[key];
      localStorage.setItem('musicbox_pending_prefs:' + uid, JSON.stringify(dirtyPreferences));
      if (JSON.stringify(reconciled) !== JSON.stringify(synced)) cloudSaveAgain = true;
    }
  } catch (err) { showToast('No se pudieron guardar los cambios en la nube. Tu copia local se conserva.'); }
  finally { cloudSaving = false; if (cloudSaveAgain) { cloudSaveAgain = false; scheduleCloudSave(); } }
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

  const palette = getComputedStyle(document.documentElement);
  ctx.strokeStyle = palette.getPropertyValue("--cyan").trim();
  ctx.lineWidth = Math.max(2, 9 * s);
  ctx.lineCap = "round";
  ctx.lineJoin = "round";
  ctx.shadowColor = palette.getPropertyValue("--accent").trim();
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
  document.getElementById('yt-player-container').append(audio);

  minimizeBtn.onclick = () => {
    dock.classList.toggle("minimized");
  };

  hideBtn.onclick = () => {
    dock.classList.add("dock-hidden");
    state.videoDockOpen = false;
    videoToggleBtn.classList.remove("active");
  };

  videoToggleBtn.onclick = () => {
    if (state.isOfflinePlayback && !audio.videoWidth) { showToast('Esta canción descargada solo tiene audio.'); return; }
    state.videoDockOpen = !state.videoDockOpen;
    dock.classList.toggle("dock-hidden", !state.videoDockOpen);
    videoToggleBtn.classList.toggle("active", state.videoDockOpen);
    if (state.isOfflinePlayback) audio.style.display = state.videoDockOpen ? 'block' : 'none';
    showToast(state.videoDockOpen ? "Video visible" : "Video minimizado");
  };
}

function setupAudioListeners() {
  audio.volume = state.volume;

  audio.addEventListener("play", event => {
    if (event.currentTarget !== audio) return;
    if (state.isOfflinePlayback) {
      recordPlayback();
      state.isPlaying = true;
      updatePlayPauseButton();
      setBrandMarkAnimated(true);
      if ("mediaSession" in navigator) navigator.mediaSession.playbackState = "playing";
    }
  });

  audio.addEventListener("pause", event => {
    if (event.currentTarget !== audio) return;
    // Audio may be paused by a headset while animation frames are suspended in a background tab.
    if (typeof cancelWebFade === 'function') cancelWebFade();
    if (state.isOfflinePlayback) {
      state.isPlaying = false;
      updatePlayPauseButton();
      setBrandMarkAnimated(false);
      if ("mediaSession" in navigator) navigator.mediaSession.playbackState = "paused";
    }
  });

  audio.addEventListener("ended", event => {
    if (event.currentTarget !== audio) return;
    if (state.isOfflinePlayback) {
      handleTrackEnded();
    }
  });
  audio.addEventListener('error', event => {
    if (event.currentTarget !== audio) return;
    if (!state.isOfflinePlayback) return;
    state.isPlaying = false;
    updatePlayPauseButton();
    showToast('No se pudo abrir el audio descargado. Elimínalo y vuelve a descargarlo.');
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

    if (state.currentTrack) {
      const sample=listeningClock.sample(playbackGeneration,current*1000,state.isPlaying,performance.now());
      if (sample.millis) recordWebStats(state.currentTrack,sample.millis);
      if (sample.qualified) recordPlayback();
    }
    updateProgress(current, duration);
    updateActiveLyric(current * 1000);
  }, 250);
}

function setupMediaSession() {
  if (!("mediaSession" in navigator)) return;

  navigator.mediaSession.setActionHandler("play", () => { if (!state.isPlaying) togglePlayPause(); });
  navigator.mediaSession.setActionHandler("pause", () => { if (state.isPlaying) togglePlayPause(); });
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
  if (!track?.id) return;
  const generation = ++playbackGeneration;
  listeningClock.reset();
  pendingVideoId = null;
  audio.pause();
  ytPlayer?.stopVideo?.();
  state.isPlaying = false;
  updatePlayPauseButton();
  if (currentBlobUrl) { URL.revokeObjectURL(currentBlobUrl); currentBlobUrl = null; }

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

  // 1. Verificar si está en descargas locales (Modo Offline)
  const offlineTrack = await storage.getOfflineTrack(track.id);
  if (generation !== playbackGeneration) return;

  if (offlineTrack?.audioBlob || state.crossfadeSeconds > 0 && !state.videoDockOpen && navigator.onLine) {
    state.isOfflinePlayback = true;
    showOfflineIndicator(!!offlineTrack?.audioBlob);
    document.querySelector('#yt-player-container iframe')?.style.setProperty('display', 'none');
    document.getElementById('video-dock').classList.add('dock-hidden');
    state.videoDockOpen = false;
    audio.style.display = 'none';

    if (ytReady && ytPlayer && ytPlayer.stopVideo) ytPlayer.stopVideo();

    currentBlobUrl = offlineTrack?.audioBlob ? URL.createObjectURL(offlineTrack.audioBlob) : null;
    audio.src = currentBlobUrl || apiUrl(`api/stream/${track.id}`);
    audio.volume = state.isMuted ? 0 : state.volume;
    audio.play().catch(() => showToast('Pulsa Reproducir para iniciar el audio.'));

    if (offlineTrack?.lyrics) {
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
    audio.style.display = 'none';
    document.querySelector('#yt-player-container iframe')?.style.setProperty('display', 'block');
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

  renderQueue();
  checkAutoplayQueue(track.id, generation);
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
    const candidates = state.queue.map((_, i) => i).filter(i => i !== state.queueIndex);
    const nextIdx = candidates.length ? candidates[Math.floor(Math.random() * candidates.length)] : 0;
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
  } else {
    state.isPlaying = false;
    updatePlayPauseButton();
    setBrandMarkAnimated(false);
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
    playTrack(state.currentTrack,true);
  } else {
    playNext();
  }
}

async function checkAutoplayQueue(videoId, generation = playbackGeneration) {
  if (state.queueIndex >= state.queue.length - 3 && navigator.onLine) {
    try {
      const resp = await fetch(apiUrl(`api/related/${videoId}`));
      const relatedTracks = await resp.json();
      if (generation !== playbackGeneration || state.currentTrack?.id !== videoId) return;
      if (Array.isArray(relatedTracks)) {
        const currentIds = new Set(state.queue.map((t) => t.id));
        const newTracks = relatedTracks.filter((t) => !currentIds.has(t.id) && t.kind !== 'video' && (t.duration || 0) <= 600);
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
  if (!track?.id || track.id.startsWith('local:')) return;
  if (downloadJobs.has(track.id)) { showToast('Esta canción ya se está descargando.'); return; }
  if (!navigator.onLine) {
    showToast("Necesitas conexión para descargar");
    return;
  }

  showToast(`Descargando "${track.title}"...`);
  const job = { track, format:state.downloadFormat, quality:state.downloadQuality, progress: 0, controller: new AbortController() };
  downloadJobs.set(track.id, job);
  renderDownloadJobs();
  if (btnElement) {
    btnElement.style.opacity = "0.5";
    btnElement.style.pointerEvents = "none";
  }

  try {
    const streamResp = await fetch(apiUrl(`api/download/${track.id}?format=${state.downloadFormat}&quality=${state.downloadQuality}`), { signal: job.controller.signal });
    if (!streamResp.ok) {
      const error = await streamResp.json().catch(() => ({}));
      throw new Error(error.detail || 'No se pudo descargar. Revisa tu conexión e inténtalo de nuevo.');
    }
    if (!(streamResp.headers.get('content-type') || '').match(/^(audio|video)\//)) throw new Error('El servidor no devolvió un archivo de audio válido.');
    const reader = streamResp.body.getReader();
    const total = Number(streamResp.headers.get('content-length'));
    const chunks = []; let received = 0;
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      chunks.push(value); received += value.length;
      job.progress = total ? Math.min(99, Math.round(received / total * 100)) : null;
      renderDownloadJobs();
    }
    const audioBlob = new Blob(chunks, { type: streamResp.headers.get('content-type') });
    if (!audioBlob.size) throw new Error('El audio está vacío. Reintenta la descarga.');

    let lyricsData = null;
    try {
      const lyricsResp = await fetch(apiUrl(`api/lyrics/${track.id}?title=${encodeURIComponent(track.title)}&artist=${encodeURIComponent(track.artist)}`));
      lyricsData = await lyricsResp.json();
    } catch (err) {}

    await storage.saveOfflineTrack(track, audioBlob, lyricsData);
    recordDownload(job,'done');
    showToast(`Guardada offline: ${track.title}`);

    if (btnElement) {
      btnElement.innerHTML = `<img src="icons/downloaded.svg" class="nav-icon" style="filter: invert(65%) sepia(85%) saturate(400%) hue-rotate(85deg);" />`;
      btnElement.style.opacity = "1";
      btnElement.style.pointerEvents = "auto";
    }

    if (state.activeView === "downloads") {
      loadDownloadsView();
    }
    return true;
  } catch (err) {
    console.error("Error al descargar:", err);
    recordDownload(job,err.name === 'AbortError' ? 'cancelled' : 'failed',err.message);
    showToast(err.name === 'AbortError' ? 'Descarga cancelada' : err.message);
    if (btnElement) {
      btnElement.style.opacity = "1";
      btnElement.style.pointerEvents = "auto";
    }
    return false;
  } finally { downloadJobs.delete(track.id); renderDownloadJobs(); }
}

// ==========================================================================
// Letras Sincronizadas
// ==========================================================================
async function loadLyrics(track) {
  state.lyrics = [];
  const container = document.getElementById("lyrics-body");
  container.innerHTML = `<div class="lyrics-empty">Buscando letra...</div>`;

  try {
    const url = apiUrl(`api/lyrics/${track.id}?title=${encodeURIComponent(track.title)}&artist=${encodeURIComponent(track.artist)}&duration=${track.duration || 0}`);
    const resp = await fetch(url);
    const data = await resp.json();
    if (state.currentTrack?.id !== track.id) return;
    renderLyrics(data);
  } catch (err) {
    if (state.currentTrack?.id !== track.id) return;
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
    const activeEl = document.getElementById(`lyric-${activeIndex}`);
    if (activeEl) {
      if (activeEl.dataset.highlighted === 'true') return;
      document.querySelectorAll('.lyric-line.active').forEach(el => el.classList.remove('active'));
      document.querySelectorAll('.lyric-line').forEach(el => delete el.dataset.highlighted);
      activeEl.dataset.highlighted = 'true';
      activeEl.classList.add("active");
      const body = document.getElementById('lyrics-body');
      const offset = activeEl.getBoundingClientRect().top - body.getBoundingClientRect().top;
      body.scrollTo({ top: Math.max(0, body.scrollTop + offset - body.clientHeight / 2), behavior: matchMedia('(prefers-reduced-motion:reduce)').matches ? 'instant' : 'smooth' });
    }
  }
}

// ==========================================================================
// Vistas Principales (Inicio, Mi Música, Descargas, Historial)
// ==========================================================================

// --- Vista: Inicio (Desktop Calque) ---
let homeRecommendationRetries = 0;
async function loadHomeView() {
  homeRecommendationRetries = 0;
  state.activeView = "home";
  updateActiveNavButton("home");

  const container = document.getElementById("main-content");
  container.innerHTML = `
    <h1 class="page-heading">Inicio</h1>

    <!-- Gran Tarjeta de Búsqueda Desktop -->
    <div class="home-search-card" id="home-search-card">
      <img src="icons/search.svg" class="home-search-icon" alt="">
      <input type="text" id="home-search-input" class="home-search-input" placeholder="Canción, artista o enlace" autocomplete="off" spellcheck="false" value="${escapeHTML(state.searchQuery || '')}">
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
  const homeContent = document.getElementById('home-dynamic-content');
  const playlists = await storage.getAllPlaylists();
  if (homeContent?.isConnected && !state.searchQuery) {
    homeContent.querySelector('.pinned-playlists')?.remove();
    const pinned = document.createElement('div'); pinned.className = 'pinned-playlists';
    playlists.filter(p => p.pinned).forEach(pl => {
      const button = actionButton(pl.name, '', () => loadPlaylistView(pl), 'pill-btn');
      button.innerHTML = `<img src="${escapeHTML(pl.tracks?.[0]?.thumbnail || 'icons/playlist.svg')}" alt=""><span>${escapeHTML(pl.name)}</span>`;
      pinned.append(button);
    });
    if (pinned.children.length) homeContent.prepend(pinned);
  }
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
    if (!resp.ok) throw new Error('Recommendations unavailable');
    const data = await resp.json();
    if (!homeContent?.isConnected || state.searchQuery) return;
    const seeds = recentTracks.filter(t => /^[A-Za-z0-9_-]{11}$/.test(t.id || '')).slice(0, 2);
    if (seeds.length && navigator.onLine) {
      const batches = await Promise.all(seeds.map(t => fetch(apiUrl(`api/related/${t.id}?count=15`)).then(r => r.ok ? r.json() : []).catch(() => [])));
      const seen = new Set(seeds.map(t => t.id));
      const personal = batches.flat().filter(t => {
        if (!t?.id || seen.has(t.id) || t.kind === 'video' || (t.duration || 0) > 600) return false;
        seen.add(t.id); return true;
      }).slice(0, 20);
      if (personal.length) {
        data.songs = personal;
        const subtitle = document.querySelector('#section-for-you .home-section-subtitle');
        if (subtitle) subtitle.textContent = 'Según lo que escuchas';
      }
    }
    if (!homeContent?.isConnected || state.searchQuery) return;
    // Las recomendaciones no repiten canciones que ya están en alguna playlist de la cuenta.
    const owned = new Set();
    try { for (const playlist of await storage.getAllPlaylists()) for (const item of playlist.tracks || []) if (item?.id) owned.add(item.id); } catch {}
    if (Array.isArray(data.songs)) data.songs = data.songs.filter((song) => !owned.has(song?.id));
    if (!homeContent?.isConnected || state.searchQuery) return;
    state.cachedRecommendations = data;
    // Un resultado casi vacío suele ser una respuesta incompleta (p. ej. justo tras iniciar sesión): se reintenta solo.
    if ((data.songs || []).length < 6 && homeRecommendationRetries < 2 && navigator.onLine) {
      homeRecommendationRetries++;
      setTimeout(() => { if (homeContent?.isConnected && !state.searchQuery) loadHomeData(); }, 3500);
    }

    renderHomeArtists(data.artists || []);
    renderHomeForYou(data.songs || data || []);
    return true;
  } catch (err) {
    console.error("Error al cargar recomendaciones:", err);
    const grid = document.getElementById('for-you-grid');
    if (grid) {
      grid.replaceChildren(document.createTextNode('No se pudieron cargar las recomendaciones. '), actionButton('Reintentar', '', loadHomeData, 'pill-btn'));
      document.getElementById('artists-row')?.replaceChildren();
    }
    return false;
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
          ? `<img src="${escapeHTML(art.photo)}" class="artist-bubble-img" alt="${escapeHTML(art.name)}" loading="lazy" />`
          : `<div class="artist-bubble-fallback">${escapeHTML((art.name || "?").charAt(0).toUpperCase())}</div>`
        }
      </div>
      <div class="artist-bubble-name">${escapeHTML(art.name)}</div>
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
      <img src="${escapeHTML(track.thumbnail)}" class="for-you-cover" loading="lazy" alt="${escapeHTML(track.title)}" />
      <div class="for-you-play-overlay">
        <img src="icons/play.svg" class="for-you-play-icon" alt="Play" />
      </div>
    </div>
    <div class="for-you-title">${escapeHTML(track.title)}</div>
    <div class="for-you-artist">${escapeHTML(track.artist || track.subtitle || '')}</div>
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
    <img src="${escapeHTML(track.thumbnail)}" class="album-cover" style="width:124px;height:124px;" loading="lazy" />
    <div class="album-title">${escapeHTML(track.title)}</div>
    <div class="album-subtitle">${escapeHTML(track.artist)}</div>
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
    refreshBtn.addEventListener("click", () => runRefresh(refreshBtn, loadHomeData));
  }
}

async function performSearch(query, filter = "all") {
  const generation = ++searchGeneration;
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
    if (!resp.ok) throw new Error('Search unavailable');
    const results = await resp.json();
    if (generation !== searchGeneration || !contentArea.isConnected) return;

    contentArea.innerHTML = "";

    if (!Array.isArray(results) || results.length === 0) {
      contentArea.innerHTML = `<div style="padding:40px;color:var(--text-muted);text-align:center;">No se encontraron resultados para "${escapeHTML(query)}"</div>`;
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
              ? `<img src="${escapeHTML(art.photo)}" class="artist-bubble-img" alt="${escapeHTML(art.name)}" loading="lazy" />`
              : `<div class="artist-bubble-fallback">${escapeHTML((art.name || "?").charAt(0).toUpperCase())}</div>`
            }
          </div>
          <div class="artist-bubble-name" style="font-size:14px;">${escapeHTML(art.name)}</div>
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
    if (generation !== searchGeneration || !contentArea.isConnected) return;
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
      <img src="${escapeHTML(track.thumbnail)}" class="row-cover" loading="lazy" alt="${escapeHTML(track.title)}" />
    </div>
    <div class="row-details">
      <div class="row-title">${escapeHTML(track.title)}</div>
      <div class="row-subtitle">${escapeHTML([track.artist || track.subtitle || '', track.album || '', isOfflineView ? (track.format || 'MP3').toString().toUpperCase() : ''].filter(Boolean).join(' · '))}</div>
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
  activePlaylist = null;
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
    const sort = document.getElementById('library-sort')?.value || 'default';
    items = [...items];
    const order = (a, b) => String(a).localeCompare(String(b), document.documentElement.lang, { numeric: true, sensitivity: 'base' });
    if (sort === 'title') items.sort((a,b) => order(a.title || a.name, b.title || b.name));
    if (sort === 'artist') items.sort((a,b) => order(a.artist || '', b.artist || ''));
    if (sort === 'duration') items.sort((a,b) => (a.duration || 0) - (b.duration || 0));
    if (sort === 'recent') items.sort((a,b) => (b.addedAt || b.savedAt || b.createdAt || 0) - (a.addedAt || a.savedAt || a.createdAt || 0));
    if (sort === 'plays') items.sort((a,b) => (b.plays || 0) - (a.plays || 0));
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
          <div class="for-you-cover-wrap" style="display:flex;align-items:center;justify-content:center;background:var(--cover-bg);">
            <img src="icons/playlist.svg" style="width:40px;height:40px;opacity:0.7;" alt="">
          </div>
          <div class="for-you-title">${escapeHTML(pl.name)}</div>
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
    const [favs, hist, dls, playlists] = await Promise.all([
      storage.getAllFavorites(),
      storage.getRecentHistory(50),
      storage.getAllOfflineTracks(), storage.getAllPlaylists()
    ]);
    const seen = new Set();
    const songs = [];
    [...dls, ...favs, ...playlists.flatMap(pl => pl.tracks || []), ...hist].forEach((t) => {
      if (t && t.id && !seen.has(t.id)) {
        seen.add(t.id);
        songs.push(t);
      }
    });
    allLoadedItems = songs;
  }

  renderItems(allLoadedItems);
  const toolbar = document.createElement('div'); toolbar.className = 'library-toolbar';
  toolbar.innerHTML = `<label for="library-sort">Ordenar</label><select id="library-sort"><option value="default">Orden original</option><option value="title">Título A–Z</option><option value="artist">Artista A–Z</option><option value="recent">Más recientes</option><option value="plays">Más escuchadas</option><option value="duration">Más cortas</option></select>`;
  listContainer.before(toolbar);
  toolbar.querySelector('select').onchange = () => searchInput.dispatchEvent(new Event('input'));
  toolbar.append(actionButton('Importar canciones', 'plus', importLocalFiles, 'pill-btn'));
  toolbar.append(actionButton('Importar carpeta', 'folder', () => importLocalFiles(true), 'pill-btn'));
  toolbar.append(actionButton('Importar playlist', 'link', importPlaylistDialog, 'pill-btn'));
  const folders = [...new Set(allLoadedItems.map(t => t.folder).filter(Boolean))];
  if (folders.length) {
    const select = document.createElement('select'); select.setAttribute('aria-label', 'Carpeta'); select.id = 'library-folder';
    select.add(new Option('Todas las carpetas', ''));
    folders.forEach(name => select.add(new Option(name, name)));
    select.onchange = () => searchInput.dispatchEvent(new Event('input')); toolbar.append(select);
  }

  // Filtrado en vivo de Mi Música
  searchInput.addEventListener("input", () => {
    const q = searchInput.value.trim().toLowerCase();
    searchClear.style.display = q ? "flex" : "none";
    if (!q) {
      const folder = document.getElementById('library-folder')?.value;
      renderItems(allLoadedItems.filter(t => !folder || t.folder === folder));
    } else {
      const filtered = allLoadedItems.filter((item) => {
        const title = (item.title || item.name || "").toLowerCase();
        const artist = (item.artist || item.subtitle || "").toLowerCase();
        const folder = document.getElementById('library-folder')?.value;
        return (!folder || item.folder === folder) && (title.includes(q) || artist.includes(q));
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
  renderDownloadJobs();
  const options = document.createElement('div'); options.className = 'library-toolbar';
  options.innerHTML = `<label for="download-format">Formato</label><select id="download-format"><option value="mp3">MP3</option><option value="mp4">MP4</option></select><label for="download-quality">Calidad</label><select id="download-quality"></select>`;
  document.getElementById('downloads-content-list').before(options);
  const formatSelect = options.querySelector('#download-format'), qualitySelect = options.querySelector('#download-quality');
  const updateQuality = () => {
    const values = formatSelect.value === 'mp3' ? [128,192,256,320] : [360,480,720,1080];
    if (!values.includes(state.downloadQuality)) state.downloadQuality = formatSelect.value === 'mp3' ? 192 : 720;
    qualitySelect.replaceChildren(...values.map(n => new Option(n + (formatSelect.value === 'mp3' ? ' kbps' : 'p'), n)));
    qualitySelect.value = state.downloadQuality;
  };
  formatSelect.value = state.downloadFormat; updateQuality();
  const saveDownloadPreference = () => {
    localStorage.setItem('musicbox_download_format',state.downloadFormat);localStorage.setItem('musicbox_download_quality',state.downloadQuality);
    dirtyPreferences.autoFormat=state.downloadFormat.toUpperCase();dirtyPreferences[state.downloadFormat==='mp3'?'quality_mp3':'quality_mp4']=state.downloadQuality+(state.downloadFormat==='mp3'?' kbps':'p');scheduleCloudSave();
  };
  formatSelect.onchange = () => { state.downloadFormat = formatSelect.value; updateQuality(); saveDownloadPreference(); };
  qualitySelect.onchange = () => { state.downloadQuality = Number(qualitySelect.value); saveDownloadPreference(); };

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
    if (await confirmAction('Limpiar historial', '¿Seguro que deseas borrar el historial de reproducción?')) {
      await storage.clearHistory();
      loadHistoryView('plays');
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
      <img src="${escapeHTML(photo || 'logo_complete.png')}" class="artist-header-avatar" id="artist-avatar" alt="${escapeHTML(name)}" />
      <div class="artist-header-info">
        <h1 class="artist-header-name" id="artist-name">${escapeHTML(name)}</h1>
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
    if (data.songs_playlist) {
      const all = actionButton('Todas las canciones', '', async () => {
        all.disabled = true;
        try {
          const response = await fetch(apiUrl(`api/playlist/${encodeURIComponent(data.songs_playlist)}`));
          if (!response.ok) throw new Error();
          const tracks = await response.json();
          const list = document.getElementById('artist-top-songs');
          if (!list) return;
          list.replaceChildren(...tracks.map(t => createTrackRow(t)));
        } catch { showToast('No se pudieron cargar las canciones. Reintenta.'); }
        finally { all.disabled = false; }
      }, 'pill-btn');
      playAllBtn.after(all);
    }
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
          <img src="${escapeHTML(alb.thumbnail)}" class="album-cover" loading="lazy" alt="${escapeHTML(alb.title)}" />
          <div class="album-title">${escapeHTML(alb.title)}</div>
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
          <img src="${escapeHTML(sng.thumbnail)}" class="album-cover" loading="lazy" alt="${escapeHTML(sng.title)}" />
          <div class="album-title">${escapeHTML(sng.title)}</div>
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
      <img src="logo_complete.png" class="album-cover" id="album-cover-img" style="width:140px;height:140px;border-radius:16px;" />
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
        <h1 class="page-heading" style="margin-bottom:2px;">${escapeHTML(playlist.name)}</h1>
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
  document.getElementById("player-thumb").src = track.thumbnail || "logo_complete.png";
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
  document.getElementById('play-pause-btn').setAttribute('aria-label', state.isPlaying ? 'Pausar' : 'Reproducir');
}

function updateProgress(current, duration) {
  // En plena mezcla los tiempos y la barra siguen a la canción que sale hasta que termina.
  if (typeof outgoingAudio !== 'undefined' && outgoingAudio && Number.isFinite(outgoingAudio.duration)) {
    current = outgoingAudio.currentTime; duration = outgoingAudio.duration;
  }
  document.getElementById("current-time").textContent = formatDuration(current);
  document.getElementById('progress-wrap').setAttribute('aria-valuemax', duration || 0);
  document.getElementById('progress-wrap').setAttribute('aria-valuenow', Math.round(current));
  if (duration > 0) {
    document.getElementById("total-time").textContent = formatDuration(duration);
    const percent = Math.min(100, (current / duration) * 100);
    document.getElementById("progress-fill").style.width = `${percent}%`;
  } else document.getElementById('progress-fill').style.width = '0%';
}

/** La insignia "Offline" se retiró del reproductor: la fuente de reproducción no es información que el usuario necesite. */
function showOfflineIndicator() {}

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
        <img src="${escapeHTML(track.thumbnail)}" class="row-cover" loading="lazy" />
      </div>
      <div class="row-details">
        <div class="row-title" style="font-size:13px;">${escapeHTML(track.title)}</div>
        <div class="row-subtitle" style="font-size:11px;">${escapeHTML(track.artist || '')}</div>
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
  const rect = menu.getBoundingClientRect();
  const x = Math.max(12, Math.min(window.innerWidth - rect.width - 12, e.clientX));
  const y = Math.max(12, Math.min(window.innerHeight - rect.height - 12, e.clientY));
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
  if (localStorage.getItem("musicbox_lyrics_open") === "1") {
    state.lyricsOpen = true;
    lyricsDrawer.classList.add("open");
    lyricsBtn.classList.add("active");
  }
  lyricsBtn.addEventListener("click", () => {
    state.lyricsOpen = !state.lyricsOpen;
    localStorage.setItem("musicbox_lyrics_open", state.lyricsOpen ? "1" : "0");
    lyricsDrawer.classList.toggle("open", state.lyricsOpen);
    lyricsBtn.classList.toggle("active", state.lyricsOpen);
    document.body.classList.toggle("lyrics-open", state.lyricsOpen && !!state.lyrics?.length);
    if (state.lyricsOpen) {
      document.getElementById("queue-drawer").classList.remove("open");
      state.queueOpen = false;
    }
  });

  document.getElementById("close-lyrics-btn").addEventListener("click", () => {
    state.lyricsOpen = false;
    localStorage.setItem("musicbox_lyrics_open", "0");
    lyricsDrawer.classList.remove("open");
    lyricsBtn.classList.remove("active");
    document.body.classList.remove("lyrics-open");
  });

  const queueBtn = document.getElementById("queue-toggle-btn");
  const queueDrawer = document.getElementById("queue-drawer");
  queueBtn.addEventListener("click", () => {
    state.queueOpen = !state.queueOpen;
    queueDrawer.classList.toggle("open", state.queueOpen);
    if (state.queueOpen) {
      lyricsDrawer.classList.remove("open");
      state.lyricsOpen = false;
      document.body.classList.remove("lyrics-open");
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
function setDarkMode(enabled, save = true) {
  state.theme = enabled ? "dark" : "light";
  document.documentElement.setAttribute("data-theme", state.theme);
  localStorage.setItem("musicbox_theme", state.theme);
  if (save) { dirtyPreferences.darkMode = enabled; scheduleCloudSave(); }
  const icon = document.getElementById("settings-theme-icon");
  if (icon) icon.src = state.theme === "dark" ? "icons/moon.svg" : "icons/sun.svg";
  const sw = document.getElementById("settings-dark-switch");
  if (sw) sw.checked = enabled;
  stopBrandAnimation();
  if (brandIsAnimated || brandIsHovered) startBrandAnimation();
}

function applyPrimaryColor(color, save = true) {
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
  if (save) { dirtyPreferences.accentColor = state.accent; scheduleCloudSave(); }
  stopBrandAnimation();
  if (brandIsAnimated || brandIsHovered) startBrandAnimation();
}


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
      dirtyPreferences.language = state.language;
      localStorage.setItem("musicbox_lang", state.language);
      scheduleCloudSave();
      applyLanguage();
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
    if (await confirmAction('Borrar descargas', '¿Eliminar todas las canciones guardadas en este navegador?')) {
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

  const hist = webStatsRows();
  let totalMs = 0;
  let totalPlays = 0;
  hist.forEach((t) => {
    const plays = t.plays || 1;
    totalPlays += plays;
    totalMs += t.listenedMs || 0;
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
      const songMs = t.listenedMs || 0;
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
          <div class="row-title" style="font-weight:600;font-size:14px;">${escapeHTML(t.title)}</div>
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
  const hist = webStatsRows();
  let totalPlays = 0;
  hist.forEach((t) => (totalPlays += t.plays || 1));
  summaryEl.textContent = `${totalPlays} reproducciones en ${hist.length} canciones`;
}

async function checkCookiesStatus() {
  const desc = document.getElementById("cookies-status-desc");
  try {
    const resp = await fetch(apiUrl("api/cookies/status"));
    const data = await resp.json();
    const upload = document.querySelector('label[for="cookies-file-input"]');
    if (upload) upload.style.display = 'none';
    if (data.active) {
      desc.textContent = "Cookies configuradas por el administrador del servidor";
      desc.style.color = "var(--green)";
    } else {
      desc.textContent = "El acceso de YouTube del servidor lo configura el administrador. La reproducción en línea sigue disponible.";
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
    if (name === 'Favoritos' || (await storage.getAllPlaylists()).some(pl => pl.name.toLocaleLowerCase() === name.toLocaleLowerCase())) {
      showToast('Ya existe una playlist con ese nombre.'); input.focus(); return;
    }
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
      <img src="${escapeHTML(pl.thumbnail || (pl.tracks || []).find(row => row.thumbnail)?.thumbnail || 'icons/playlist.svg')}" class="playlist-picker-cover" alt="" />
      <span>${escapeHTML(pl.name)} (${(pl.tracks || []).length})</span>
    `;
    item.querySelector('img').onerror = event => { event.currentTarget.onerror=null; event.currentTarget.src='icons/playlist.svg'; };
    item.addEventListener("click", async () => {
      pl.tracks = pl.tracks || [];
      if (!pl.tracks.some((t) => t.id === track.id)) {
        pl.tracks.push(track);
        await storage.savePlaylist(pl);
        if (pl.autoDownload) downloadTrackForOffline(track);
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
  container.replaceChildren();
  toast.className = "toast";
  toast.setAttribute('role', 'status');
  toast.textContent = message;
  container.appendChild(toast);

  setTimeout(() => {
    toast.style.opacity = "0";
    toast.style.transform = "translateY(10px)";
    toast.style.transition = "all 0.25s";
    setTimeout(() => toast.remove(), 250);
  }, 2800);
}
