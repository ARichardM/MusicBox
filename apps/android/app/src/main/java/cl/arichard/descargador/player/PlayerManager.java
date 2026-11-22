package cl.arichard.descargador.player;

import android.content.Context;
import android.media.AudioAttributes;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.ViewGroup;
import android.view.Gravity;
import android.widget.FrameLayout;
import cl.arichard.descargador.DescargadorApp;
import cl.arichard.descargador.data.SearchHistoryManager;
import cl.arichard.descargador.model.Song;
import cl.arichard.descargador.service.MusicService;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.os.Build;
import com.yausername.youtubedl_android.YoutubeDL;
import com.yausername.youtubedl_android.YoutubeDLRequest;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.net.HttpURLConnection;
import java.net.URL;
import cl.arichard.descargador.util.Tr;

/** Single native player shared by the full screen and mini player. */
public class PlayerManager {
    private static final long STREAM_CACHE_MS = 10 * 60 * 1000L;
    private static final long AUDIO_ROUTE_CHANGE_GRACE_MS = 1000;
    private static final ExecutorService PLAYBACK_IO = Executors.newCachedThreadPool();
    private static final Map<String, CachedStream> STREAM_CACHE = new HashMap<>();
    /** Videos cuyo enlace se está resolviendo por adelantado (protegido por STREAM_CACHE). */
    private static final Set<String> PREFETCHING = new HashSet<>();
    /** Videos de los que YouTube no ofrece un MP4 con imagen y sonido en un solo archivo. */
    private String failedVideoId = "";
    private final Set<String> checkingVideoIds = new HashSet<>();
    private long videoCheckRetryAt;
    /** El usuario eligio ver el video (pestana MP4): el flujo en linea trae imagen ademas de audio. */
    private boolean videoMode;

    private static String streamKey(String videoId, boolean video) { return video ? videoId + "#v" : videoId; }
    private static PlayerManager instance;
    public static final int REPEAT_OFF = 0, REPEAT_ALL = 1, REPEAT_ONE = 2;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<PlaybackListener> listeners = new ArrayList<>();
    private Context context;
    private PlaybackEngine activePlayer;
    private PlaybackEngine fadingPlayer;
    private static final int CROSSFADE_STREAM_LEAD_MS = 4000;
    private boolean crossfadeAdvance;
    private long fadeStarted;
    private boolean fadeLive;
    private int fadeEndAtMs;
    private int fadeDuration;

    private PlaybackEngine createEngine() {
        if ("robolectric".equals(Build.FINGERPRINT)) return new MediaPlayerPlaybackEngine(context, handler);
        return new ExoPlaybackEngine(context, handler);
    }

    public boolean isUsingExoPlayer() { return activePlayer instanceof ExoPlaybackEngine; }
    public boolean isSleepingForOffload() { return prepared && activePlayer != null && activePlayer.isSleepingForOffload(); }
    public AudioDeviceInfo getRoutedAudioDevice() { return prepared && activePlayer != null ? activePlayer.getRoutedDevice() : null; }
    public boolean shouldKeepServiceForeground() { return isPlaying() || (playWhenReady && (loading || advanceWhenReady)); }

    public int getCrossfadeSeconds() {
        return context == null ? 0 : Math.max(0, Math.min(12, context.getSharedPreferences("player", 0).getInt("crossfade_seconds", 0)));
    }
    public void setCrossfadeSeconds(int seconds) {
        stopCrossfade();
        if (context != null && seconds > 0) context.getSharedPreferences("player", 0).edit()
                .putInt("crossfade_last_seconds", Math.max(1, Math.min(12, seconds))).apply();
        if (context != null) context.getSharedPreferences("player", 0).edit().putInt("crossfade_seconds", Math.max(0, Math.min(12, seconds))).apply();
        if (shouldPollProgress()) scheduleProgress();
    }
    public void setCrossfadeEnabled(boolean enabled) {
        setCrossfadeSeconds(enabled && context != null
                ? context.getSharedPreferences("player", 0).getInt("crossfade_last_seconds", 5) : 0);
    }
    private void stopCrossfade() {
        handler.removeCallbacks(fadeTick);
        fadeLive = false;
        if (fadingPlayer != null) { fadingPlayer.release(); fadingPlayer = null; }
        if (activePlayer != null && prepared) activePlayer.setVolume(1f);
    }
    private final Runnable fadeTick = new Runnable() {
        @Override public void run() {
            if (fadingPlayer == null || activePlayer == null || !prepared) return;
            if (!isPlaying()) { stopCrossfade(); return; }
            float progress = Math.min(1f, (android.os.SystemClock.elapsedRealtime() - fadeStarted) / (float)Math.max(1, fadeDuration));
            // Potencia constante: la que sale sigue el coseno y la que entra el seno, sin bajón de volumen a mitad.
            float out = (float) Math.cos(progress * Math.PI / 2), in = (float) Math.sin(progress * Math.PI / 2);
            fadingPlayer.setVolume(out);
            activePlayer.setVolume(in);
            if (progress >= 1f) stopCrossfade(); else handler.postDelayed(this, 25);
        }
    };
    /** Dirección ya resuelta de una canción en streaming, para leer su audio sin pedirla de nuevo; null si aún no se conoce. */
    public static final class StreamSource {
        public final String url;
        public final Map<String, String> headers;
        StreamSource(String url, Map<String, String> headers) { this.url = url; this.headers = headers; }
    }
    public StreamSource streamSourceFor(String videoId) {
        CachedStream cached;
        synchronized (STREAM_CACHE) { cached = STREAM_CACHE.get(streamKey(videoId, false)); }
        if (cached == null || !cached.isFresh() || cached.headers.containsKey("X-MusicBox-HLS")) return null;
        Map<String, String> headers = new HashMap<>(cached.headers);
        headers.remove(LENGTH_KEY);
        return new StreamSource(cached.url, headers);
    }
    /** Hay una mezcla en curso (la canción que sale aún suena mientras entra la siguiente). */
    public boolean isCrossfading() { return fadingPlayer != null; }
    /** Posición y duración de la canción que sale, o -1 si no hay mezcla: la barra la sigue hasta que termina. */
    public int getOutgoingPosition() {
        return fadingPlayer == null ? -1 : fadingPlayer.getCurrentPosition();
    }
    public int getOutgoingDuration() {
        return fadingPlayer == null ? -1 : fadingPlayer.getDuration();
    }
    /** Milisegundos que le quedan a la mezcla en curso, o 0 si no hay una sonando. */
    public long crossfadeRemainingMs() {
        if (!fadeLive || fadingPlayer == null) return 0;
        return Math.max(0, fadeDuration - (android.os.SystemClock.elapsedRealtime() - fadeStarted));
    }
    public long crossfadeTotalMs() { return fadeLive ? fadeDuration : 0; }
    private cl.arichard.descargador.util.WaveformStore.Envelope envelopeOf(Song song) {
        return context == null || song == null ? null : cl.arichard.descargador.util.WaveformStore.get(context).peek(song);
    }
    /** Instante (ms) en que la canción actual deja de sonar de verdad: hay pistas con segundos de silencio al final. */
    public int crossfadeEndMs() {
        cl.arichard.descargador.util.WaveformStore.Envelope envelope = envelopeOf(currentSong);
        int duration = getDuration();
        // Si el silencio se llevara más de las tres cuartas partes de la pista, la medición no es de fiar: se ignora.
        return envelope != null && envelope.audibleEndMs > duration / 4 ? Math.min(duration, envelope.audibleEndMs) : duration;
    }
    /** Duración de la mezcla que se aplicará al final de la canción actual, o 0 si no habrá (apagado, repetir una, sin siguiente). */
    public int crossfadeZoneMs() {
        int seconds = getCrossfadeSeconds();
        if (seconds == 0 || videoMode || repeatMode == REPEAT_ONE || currentIndex < 0 || queue.size() < 2) return 0;
        if (currentIndex + 1 >= queue.size() && repeatMode != REPEAT_ALL) return 0;
        return crossfadeEndMs() >= seconds * 2000 ? seconds * 1000 : 0;
    }
    /**
     * Mide dónde suena de verdad la canción actual y la siguiente para el crossfade.
     * Con pantalla apagada solo carga relieves ya en caché en disco (cero decodificación CPU/red).
     */
    private void warmWaveforms() {
        if (context == null || videoMode || getCrossfadeSeconds() == 0) return;
        cl.arichard.descargador.util.WaveformStore store = cl.arichard.descargador.util.WaveformStore.get(context);
        boolean screenVisible = !listeners.isEmpty();
        if (screenVisible) store.request(currentSong, null);
        else store.warmFromCache(currentSong);
        int next = currentIndex + 1;
        if (next >= queue.size()) next = repeatMode == REPEAT_ALL ? 0 : -1;
        if (next >= 0 && next < queue.size()) {
            if (screenVisible) store.request(queue.get(next), null);
            else store.warmFromCache(queue.get(next));
        }
    }
    private boolean maybeCrossfade() {
        int seconds = getCrossfadeSeconds();
        if (seconds == 0 || videoMode || fadingPlayer != null || repeatMode == REPEAT_ONE
                || currentIndex < 0 || queue.size() < 2) return false;
        int endAt = crossfadeEndMs();
        int remaining = endAt - getCurrentPosition();
        if (remaining <= 0 || endAt < seconds * 2000) return false;
        int nextIndex = currentIndex + 1;
        if (nextIndex >= queue.size()) {
            if (repeatMode != REPEAT_ALL) return false;
            nextIndex = 0;
        }
        Song next = queue.get(nextIndex);
        // A streamed next song needs a moment to open, so it starts early enough for the whole fade to be heard.
        if (remaining > seconds * 1000 + (next.isOnline() ? CROSSFADE_STREAM_LEAD_MS : 0)) return false;
        if (next.isOnline()) {
            CachedStream cached;
            synchronized (STREAM_CACHE) { cached = STREAM_CACHE.get(streamKey(next.getVideoId(), false)); }
            if (cached == null || !cached.isFresh()) return false;
        } else if (!new java.io.File(next.getPath()).isFile()) return false;
        sampleListening();
        fadingPlayer = activePlayer;
        activePlayer = null;
        fadeDuration = seconds * 1000;
        fadeEndAtMs = endAt;
        crossfadeAdvance = true;
        try { playNext(); } finally { crossfadeAdvance = false; }
        return true;
    }
    private boolean prepared, playWhenReady = true, online, shuffle, loading;
    private int repeatMode = REPEAT_ALL, currentIndex = -1, generation;
    private List<Song> queue = new ArrayList<>(), originalQueue = new ArrayList<>();
    private Song currentSong;
    private String currentOnlineVideoId, currentOnlineUrl;
    private SurfaceView videoView;
    private int resumePosition, savedDuration, lastSavedSecond = -1;
    private AudioManager audioManager;
    private AudioFocusRequest focusRequest;
    private boolean hasAudioFocus = false;
    private boolean resumeOnFocusGain = false;
    private boolean noisyReceiverRegistered = false;
    private int routedOutputId = -1;
    private boolean routedPersonalOutput;
    private boolean audioRouteChangePending;
    private boolean personalOutputAddedDuringRouteChange;
    private final Runnable confirmAudioRouteChange = () -> {
        audioRouteChangePending = false;
        if (hasPersonalOutputAfterRouteChange()) {
            personalOutputAddedDuringRouteChange = false;
            resumePlaybackAfterRouteChange();
            return;
        }
        pause();
    };
    private boolean autoplayLoading, advanceWhenReady;
    private String autoplayTriedFor = "";
    private int autoplayGeneration;
    private int autoplayAttempts;
    private String autoplayError = "";
    private final Set<String> playbackFailures = new HashSet<>();
    private String recoveryPath = "";
    private int playbackRecoveryAttempts;
    private int recoveryStartPosition;
    /** Cortes de red seguidos que se reintentan (con enlace nuevo) antes de rendirse con la canción. */
    private static final int MAX_RECOVERY_ATTEMPTS = 4;
    /** Tras recuperarse, esta cantidad de audio sin fallos devuelve el contador de reintentos a cero. */
    private static final int RECOVERY_RESET_MS = 15000;

    /** Reserved key that carries the stream length next to the headers; it is never sent over HTTP. */
    private static final String LENGTH_KEY = "X-MusicBox-Length";

    private static final class CachedStream {
        final String url;
        final Map<String, String> headers;
        final long createdAt;
        CachedStream(String url, Map<String, String> headers) {
            this.url = url;
            this.headers = new HashMap<>(headers);
            this.createdAt = System.currentTimeMillis();
        }
        boolean isFresh() { return System.currentTimeMillis() - createdAt < STREAM_CACHE_MS; }
    }

    public interface PlaybackListener {
        void onTrackChanged(Song song);
        void onPlaybackStateChanged(boolean playing);
        void onProgressUpdate(int position, int duration);
        default void onPlaybackError(String message) {}
        default void onPlaybackLoading(boolean loading) {}
        default void onQueueChanged() {}
        default void onVideoAvailabilityChanged(boolean available) {}
    }

    private final Runnable progressUpdater = new Runnable() {
        @Override public void run() {
            if (!shouldPollProgress()) return;
            sampleListening();
            if (isPlaying() && maybeCrossfade()) return;
            int position = getCurrentPosition();
            if (playbackRecoveryAttempts > 0 && position - recoveryStartPosition > RECOVERY_RESET_MS)
                playbackRecoveryAttempts = 0;
            if (!listeners.isEmpty()) {
                int second = position / 1000;
                if (second % 15 == 0 && second != lastSavedSecond) { savePosition(); lastSavedSecond = second; }
                int duration = getDuration();
                for (PlaybackListener l : new ArrayList<>(listeners)) l.onProgressUpdate(position, duration);
            }
            scheduleProgress();
        }
    };

    private boolean shouldPollProgress() {
        return prepared && activePlayer != null && playWhenReady && !resumeOnFocusGain;
    }

    private void scheduleProgress() {
        handler.removeCallbacks(progressUpdater);
        if (!shouldPollProgress()) return;
        long delay = nextProgressDelayMs();
        if (delay >= 0) handler.postDelayed(progressUpdater, delay);
    }

    /**
     * Con pantalla abierta actualiza la barra cada 350 ms. Con pantalla apagada (sin listeners)
     * elimina el sondeo periódico para dejar dormir la CPU y aprovechar Audio Offload: programa a lo
     * sumo un aviso para contar la reproducción a los 30 s y, si hay crossfade, uno cerca del final.
     */
    long nextProgressDelayMs() {
        if (!listeners.isEmpty()) return 350;
        long delay = Long.MAX_VALUE;
        if (listeningSong != null && !listeningPlayCounted) {
            long leftToQualify = 30_000L - listeningQualifiedMillis;
            delay = Math.min(delay, Math.max(250L, leftToQualify + 150L));
        }
        int seconds = getCrossfadeSeconds();
        if (seconds > 0 && !videoMode && fadingPlayer == null && repeatMode != REPEAT_ONE
                && currentIndex >= 0 && queue.size() >= 2) {
            int nextIndex = currentIndex + 1;
            if (nextIndex >= queue.size()) nextIndex = repeatMode == REPEAT_ALL ? 0 : -1;
            if (nextIndex >= 0) {
                int endAt = crossfadeEndMs();
                if (endAt >= seconds * 2000) {
                    int lead = queue.get(nextIndex).isOnline() ? CROSSFADE_STREAM_LEAD_MS : 0;
                    int triggerAt = endAt - (seconds * 1000 + lead);
                    int remainingToTrigger = triggerAt - getCurrentPosition();
                    long crossfadeDelay = remainingToTrigger > 500 ? remainingToTrigger - 300L : 350L;
                    delay = Math.min(delay, Math.max(250L, crossfadeDelay));
                }
            }
        }
        return delay == Long.MAX_VALUE ? -1L : delay;
    }

    private Song listeningSong;
    private String listeningPlaylist = "";
    private long listeningClock;
    private int listeningPosition;
    private boolean listeningPlaying;
    private boolean listeningStarted;
    private long listeningQualifiedMillis;
    private boolean listeningPlayCounted;
    private String listeningTrackPath = "";

    private void startListening() {
        listeningSong = currentSong;
        listeningPlaylist = "playlist".equals(sourceType) ? sourceName : "";
        listeningClock = android.os.SystemClock.elapsedRealtime();
        listeningPosition = getCurrentPosition();
        listeningPlaying = isPlaying();
        listeningStarted = false;
        if (!currentSong.getPath().equals(listeningTrackPath)) {
            listeningTrackPath = currentSong.getPath();
            listeningQualifiedMillis = 0;
            listeningPlayCounted = false;
        }
    }

    private void sampleListening() {
        if (listeningSong == null || context == null || !prepared || activePlayer == null) return;
        long now = android.os.SystemClock.elapsedRealtime();
        int position = getCurrentPosition();
        long wall = now - listeningClock, delta = (long) position - listeningPosition;
        // Forward jumps beyond wall time are seeks; zero progress is buffering. Brief rebuffering
        // during a long sleep still preserves the actual played delta <= wall.
        if (listeningPlaying && wall > 0 && delta > 0 && delta <= wall + 1200) {
            long played = Math.min(delta, wall);
            listeningQualifiedMillis += played;
            cl.arichard.descargador.data.ClickStats.getInstance(context).recordListening(listeningSong, listeningPlaylist, played, false);
            if (!listeningPlayCounted && listeningQualifiedMillis >= 30_000) {
                SearchHistoryManager.getInstance(context).recordPlayedSong(listeningSong);
                cl.arichard.descargador.data.ClickStats.getInstance(context).recordQualifiedPlay(listeningSong, listeningPlaylist);
                listeningPlayCounted = true;
            }
        }
        listeningClock = now;
        listeningPosition = position;
        listeningPlaying = isPlaying();
        if (listeningPlaying) listeningStarted = true;
    }

    public static synchronized PlayerManager getInstance() {
        if (instance == null) instance = new PlayerManager();
        return instance;
    }
    private String sourceType = "songs", sourceName = "";

    /** Where the current queue comes from: "songs", "folder", "playlist" or "search" (name = folder or playlist). */
    public void setPlaybackSource(String type, String name) {
        sourceType = type == null ? "songs" : type;
        sourceName = name == null ? "" : name;
        if (context != null) context.getSharedPreferences("player", 0).edit()
                .putString("source_type", sourceType).putString("source_name", sourceName).apply();
    }
    /** "Carpeta · X", "Playlist · X", "Canciones" or "Búsqueda de YouTube", ready to show. */
    public String playingFromLabel() {
        if ("folder".equals(sourceType) && !sourceName.isEmpty()) return cl.arichard.descargador.util.Tr.t("Carpeta") + " · " + sourceName;
        if ("playlist".equals(sourceType) && !sourceName.isEmpty()) return cl.arichard.descargador.util.Tr.t("Playlist") + " · " + cl.arichard.descargador.util.Tr.name(sourceName);
        if ("search".equals(sourceType)) return cl.arichard.descargador.util.Tr.t("Búsqueda de YouTube");
        if ("artist".equals(sourceType) && !sourceName.isEmpty()) return cl.arichard.descargador.util.Tr.t("Artista") + " · " + sourceName;
        return cl.arichard.descargador.util.Tr.t("Canciones");
    }
    public String getSourceType() { return sourceType; }
    public String getSourceName() { return sourceName; }

    private android.content.SharedPreferences playbackPreferences;
    private final android.content.SharedPreferences.OnSharedPreferenceChangeListener autoplayPreferenceListener =
            (prefs, key) -> { if ("autoplay".equals(key)) onAutoplayPreferenceChanged(); };

    private final Runnable notificationObserver = this::refreshNotification;

    /** Rebuilds the media notification (shuffle, repeat and favorite state follow the app). */
    private void refreshNotification() {
        if (currentSong != null && context != null) {
            MusicService.updateNotification(context, currentSong, isPlaying(), shouldKeepServiceForeground());
        }
    }

    public void init(Context ctx) {
        context = ctx.getApplicationContext();
        cl.arichard.descargador.data.PlaylistManager.getInstance(context).addObserver(notificationObserver);
        audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        android.content.SharedPreferences prefs = context.getSharedPreferences("player", 0);
        if (prefs != playbackPreferences) {
            if (playbackPreferences != null)
                playbackPreferences.unregisterOnSharedPreferenceChangeListener(autoplayPreferenceListener);
            playbackPreferences = prefs;
            prefs.registerOnSharedPreferenceChangeListener(autoplayPreferenceListener);
        }
        repeatMode = prefs.getInt("repeat", REPEAT_ALL);
        queueAllowsAutoplay = prefs.getBoolean("queue_allows_autoplay", true);
        sourceType = prefs.getString("source_type", "songs");
        sourceName = prefs.getString("source_name", "");
        try {
            org.json.JSONArray songs = new org.json.JSONArray(prefs.getString("queue", "[]"));
            List<Song> restored = new ArrayList<>();
            String path = prefs.getString("current", "");
            currentSong = null;
            for (int i = 0; i < songs.length(); i++) {
                Song song = Song.fromJson(songs.getJSONObject(i));
                if (song.isOnline() || new java.io.File(song.getPath()).isFile()) {
                    restored.add(song);
                    if (path.equals(song.getPath())) currentSong = song;
                }
            }
            queue = uniqueQueue(restored, currentSong);
            originalQueue = new ArrayList<>(queue);
            currentIndex = indexOf(currentSong);
            online = currentSong != null && currentSong.isOnline();
            if (online) {
                currentOnlineVideoId = currentSong.getVideoId();
                currentOnlineUrl = "https://www.youtube.com/watch?v=" + currentOnlineVideoId;
            }
            resumePosition = prefs.getInt("position", 0); savedDuration = prefs.getInt("duration", 0);
            playWhenReady = false;
        } catch (Exception ignored) { queue.clear(); originalQueue.clear(); currentSong = null; currentIndex = -1; }
    }
    public void savePosition() {
        if (context == null) return;
        context.getSharedPreferences("player", 0).edit().putInt("position", getCurrentPosition())
                .putInt("duration", getDuration()).apply();
    }
    private void saveSession() {
        if (context == null) return;
        org.json.JSONArray songs = new org.json.JSONArray();
        for (Song song : queue) songs.put(song.toJson());
        context.getSharedPreferences("player", 0).edit().putString("queue", songs.toString())
                .putBoolean("queue_allows_autoplay", queueAllowsAutoplay)
                .putString("current", currentSong == null ? "" : currentSong.getPath()).apply();
        savePosition();
    }
    public void setMainActivityHost(ViewGroup host) { /* Native audio needs no hidden WebView. */ }

    // Activity compatibility: these methods now attach a native video surface.
    public void attachOnlineWebView(ViewGroup host) {
        detachOnlineWebView();
        if (host == null) return;
        videoView = new SurfaceView(host.getContext());
        host.addView(videoView, new FrameLayout.LayoutParams(-1, -1));
        videoView.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override public void surfaceCreated(SurfaceHolder holder) {
                if (activePlayer != null) activePlayer.setDisplay(holder);
            }
            @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
                if (activePlayer != null) activePlayer.setDisplay(holder);
            }
            @Override public void surfaceDestroyed(SurfaceHolder holder) {
                if (activePlayer != null) activePlayer.setDisplay(null);
            }
        });
        // El recuadro del video cambia de tamaño (pantalla completa, rotación): la imagen se vuelve a ajustar cada vez.
        if (videoHost != null && videoHostListener != null) videoHost.removeOnLayoutChangeListener(videoHostListener);
        videoHost = host;
        videoHostListener = (v, l, t, r, b, ol, ot, or, ob) -> {
            if (r - l != or - ol || b - t != ob - ot) fitVideo();
        };
        host.addOnLayoutChangeListener(videoHostListener);
        videoView.post(this::fitVideo);
    }
    public void detachOnlineWebView() {
        if (activePlayer != null) activePlayer.setDisplay(null);
        if (videoView != null && videoView.getParent() instanceof ViewGroup)
            ((ViewGroup) videoView.getParent()).removeView(videoView);
        if (videoHost != null && videoHostListener != null) videoHost.removeOnLayoutChangeListener(videoHostListener);
        videoHost = null;
        videoHostListener = null;
        videoView = null;
    }

    private ViewGroup videoHost;
    private android.view.View.OnLayoutChangeListener videoHostListener;
    /** Con true la imagen llena todo el recuadro recortando lo que sobre; con false se ve entera, con franjas. */
    private boolean videoFill;

    public void setVideoFill(boolean fill) {
        if (videoFill == fill) return;
        videoFill = fill;
        fitVideo();
    }

    /** Proporción ancho/alto del video que suena; 16:9 mientras no se sepa. */
    public float getVideoAspect() {
        if (prepared && activePlayer != null && activePlayer.getVideoWidth() > 0 && activePlayer.getVideoHeight() > 0)
            return (float) activePlayer.getVideoWidth() / activePlayer.getVideoHeight();
        return 16f / 9f;
    }

    private void fitVideo() {
        if (videoView == null || !prepared || activePlayer == null) return;
        ViewGroup host = (ViewGroup) videoView.getParent();
        int width = activePlayer.getVideoWidth(), height = activePlayer.getVideoHeight();
        if (host == null || width <= 0 || height <= 0 || host.getWidth() <= 0 || host.getHeight() <= 0) return;
        float fit = (float) host.getWidth() / width, fitHeight = (float) host.getHeight() / height;
        float scale = videoFill ? Math.max(fit, fitHeight) : Math.min(fit, fitHeight);
        int w = Math.round(width * scale), h = Math.round(height * scale);
        ViewGroup.LayoutParams current = videoView.getLayoutParams();
        if (current != null && current.width == w && current.height == h) return;
        videoView.setLayoutParams(new FrameLayout.LayoutParams(w, h, Gravity.CENTER));
    }

    public void playSong(Song song, List<Song> newQueue) {
        playSong(song, newQueue, true);
    }

    private boolean queueAllowsAutoplay = true;

    public void playSong(Song song, List<Song> newQueue, boolean allowAutoplay) {
        if (song == null) return;
        listeningTrackPath = "";
        playbackFailures.clear();
        recoveryPath = "";
        if (newQueue != null && !newQueue.isEmpty()) {
            queueAllowsAutoplay = allowAutoplay || "playlist".equals(sourceType) || "folder".equals(sourceType);
            autoplayGeneration++;
            autoplayLoading = advanceWhenReady = false;
            autoplayTriedFor = "";
            autoplayError = "";
            originalQueue = uniqueQueue(newQueue, song);
            queue = new ArrayList<>(originalQueue);
            if (shuffle) Collections.shuffle(queue);
        }
        resumePosition = 0; savedDuration = 0;
        currentSong = song;
        currentIndex = indexOf(song);
        if (currentIndex < 0) { queue.add(0, song); originalQueue.add(0, song); currentIndex = 0; }
        else queue.set(currentIndex, song);
        startCurrent();
        notifyQueue();
    }

    private int indexOf(Song song) {
        if (song == null) return -1;
        String key = queueIdentity(song);
        for (int i = 0; i < queue.size(); i++)
            if (key.equals(queueIdentity(queue.get(i)))) return i;
        return -1;
    }

    private String queueIdentity(Song song) {
        return context == null ? song.getPath()
                : cl.arichard.descargador.data.PlaylistManager.getInstance(context).songIdentity(song);
    }

    private List<Song> uniqueQueue(List<Song> songs, Song preferred) {
        String active = preferred == null ? null : queueIdentity(preferred);
        Set<String> seen = new HashSet<>();
        List<Song> result = new ArrayList<>();
        for (Song song : songs) {
            String key = queueIdentity(song);
            if (seen.add(key)) result.add(key.equals(active) ? preferred : song);
        }
        return result;
    }

    protected void startCurrent() {
        if (!crossfadeAdvance) stopCrossfade();
        if (currentSong == null) return;
        if (!currentSong.getPath().equals(recoveryPath)) {
            recoveryPath = currentSong.getPath();
            playbackRecoveryAttempts = 0;
        }
        lastSavedSecond = -1;
        String id = playbackVideoId();
        String localVideo = videoMode && id != null
                ? cl.arichard.descargador.data.PlaylistManager.getInstance(context).localVideoFor(id) : null;
        if (localVideo != null) {
            online = false;
            startLocal(localVideo);
        } else if (currentSong.isOnline() || (videoMode && id != null)) {
            startOnline(id);
        } else {
            online = false;
            autoplayError = "";
            startLocal(currentSong.getPath());
        }
    }

    private String playbackVideoId() {
        if (currentSong == null) return null;
        return currentSong.isOnline() ? currentSong.getVideoId() : context == null ? null :
                cl.arichard.descargador.data.PlaylistManager.getInstance(context).videoIdFor(currentSong.getPath());
    }

    private void startLocal(String path) {
        if (currentSong == null) return;
        releasePlayer();
        playWhenReady = true;
        notifyTrack();
        try { prepareSource(path, Collections.emptyMap()); }
        catch (Exception e) { fail(e); }
    }

    public void playOnline(String videoId, String title, String channel, String thumbnail, String url) {
        Song song = new Song(0, title, channel, "YouTube", 0, "youtube:" + videoId, "YouTube", 0);
        playSong(song, Collections.singletonList(song));
    }

    /** ¿Está esta canción en la cola actual? */
    public boolean queueContains(String path) {
        if (path == null) return false;
        for (Song song : queue) if (path.equals(song.getPath())) return true;
        return false;
    }

    /** Cambia en la cola todas las apariciones de [path] por [replacement]; si sonaba, suena la nueva. */
    public boolean replaceQueueSong(String path, Song replacement) {
        if (path == null || replacement == null) return false;
        boolean changed = false, wasCurrent = false;
        for (int i = 0; i < queue.size(); i++) {
            if (!path.equals(queue.get(i).getPath())) continue;
            queue.set(i, replacement);
            changed = true;
            if (i == currentIndex) { currentSong = replacement; wasCurrent = true; }
        }
        if (!changed) return false;
        originalQueue = new ArrayList<>(queue);
        if (wasCurrent) {
            recoveryPath = ""; playbackFailures.clear();
            resumePosition = 0; savedDuration = 0;
            startCurrent();
        }
        notifyQueue();
        return true;
    }

    public static final int PREVIEW_LOADING = 0, PREVIEW_PLAYING = 1, PREVIEW_PAUSED = 2, PREVIEW_ENDED = 3, PREVIEW_ERROR = 4;

    public interface PreviewListener { void onPreview(String videoId, int state, int position, int duration); }

    /**
     * Escucha breve de una canción en línea con un reproductor aparte: no toca la cola ni la canción actual.
     * Pausa la reproducción principal mientras se escucha y puede reanudarla al terminar.
     */
    public final class Preview {
        private final PreviewListener listener;
        private PlaybackEngine media;
        private String videoId = "";
        private int token;
        private boolean prepared, pausedMain;
        private final Runnable tick = new Runnable() {
            @Override public void run() {
                if (media == null || !prepared) return;
                boolean playing = media.isPlaying();
                listener.onPreview(videoId, playing ? PREVIEW_PLAYING : PREVIEW_PAUSED, media.getCurrentPosition(), media.getDuration());
                if (playing) handler.postDelayed(this, 250);
            }
        };

        Preview(PreviewListener listener) { this.listener = listener; }

        public String current() { return videoId; }

        /** Reproduce [id]; si ya suena, la pausa o reanuda. */
        public void toggle(String id) {
            if (id.equals(videoId) && media != null) {
                if (!prepared) return;
                if (media.isPlaying()) {
                    media.pause();
                    handler.removeCallbacks(tick);
                    listener.onPreview(id, PREVIEW_PAUSED, media.getCurrentPosition(), media.getDuration());
                } else {
                    media.play();
                    handler.removeCallbacks(tick);
                    handler.post(tick);
                }
                return;
            }
            start(id);
        }

        private void start(String id) {
            String previous = videoId;
            token++;
            closeMedia();
            if (!previous.isEmpty()) listener.onPreview(previous, PREVIEW_ENDED, 0, 0);
            if (!pausedMain && isPlaying()) { pausedMain = true; pause(); }
            videoId = id;
            final int mine = token;
            listener.onPreview(id, PREVIEW_LOADING, 0, 0);
            PLAYBACK_IO.execute(() -> {
                try {
                    CachedStream stream;
                    synchronized (STREAM_CACHE) { stream = STREAM_CACHE.get(id); }
                    if (stream == null || !stream.isFresh()) stream = resolveToCache(id, "https://www.youtube.com/watch?v=" + id);
                    final CachedStream ready = stream;
                    handler.post(() -> { if (mine == token) open(ready, id, mine); });
                } catch (Exception e) {
                    handler.post(() -> { if (mine == token) fail(id); });
                }
            });
        }

        private void open(CachedStream stream, String id, int mine) {
            try {
                PlaybackEngine player = createEngine();
                media = player;
                player.prepare(stream.url, stream.headers, false, false, null, new PlaybackEngine.Callback() {
                    @Override public void onPrepared() {
                        if (mine != token || media != player) return;
                        prepared = true;
                        player.play();
                        handler.removeCallbacks(tick);
                        handler.post(tick);
                    }
                    @Override public void onCompletion() {
                        if (mine == token && media == player) {
                            closeMedia();
                            videoId = "";
                            listener.onPreview(id, PREVIEW_ENDED, 0, 0);
                        }
                    }
                    @Override public void onSeekComplete() {}
                    @Override public void onVideoSizeChanged(int width, int height) {}
                    @Override public void onError(Exception error) {
                        if (mine == token && media == player) fail(id);
                    }
                    @Override public void onRoutingChanged() {}
                });
            } catch (Exception e) {
                fail(id);
            }
        }

        private void fail(String id) {
            closeMedia();
            videoId = "";
            listener.onPreview(id, PREVIEW_ERROR, 0, 0);
        }

        private void closeMedia() {
            handler.removeCallbacks(tick);
            prepared = false;
            if (media != null) { try { media.release(); } catch (Exception ignored) {} media = null; }
        }

        /** Detiene la escucha; con [resumeMain] reanuda lo que sonaba antes. */
        public void release(boolean resumeMain) {
            token++;
            closeMedia();
            videoId = "";
            if (resumeMain && pausedMain) play();
            pausedMain = false;
        }
    }

    public Preview newPreview(PreviewListener listener) { return new Preview(listener); }

    private void startOnline(String sourceVideoId) {
        releasePlayer();
        final int requestGeneration = generation;
        online = true;
        playWhenReady = true;
        registerNoisyReceiver();
        currentOnlineVideoId = sourceVideoId;
        currentOnlineUrl = "https://www.youtube.com/watch?v=" + currentOnlineVideoId;
        notifyTrack();
        notifyState();
        loading(true);
        advanceWhenReady = false;
        if (currentIndex >= queue.size() - 1) fetchAutoplay();
        final String videoId = currentOnlineVideoId;
        final boolean wantVideo = videoMode;
        // En modo video una pista de audio de YouTube Music (portada fija) se reemplaza por su videoclip, si lo tiene.
        final String knownSource = wantVideo ? VIDEO_COUNTERPARTS.get(videoId) : videoId;
        if (knownSource != null && !knownSource.isEmpty()) {
            CachedStream cached;
            synchronized (STREAM_CACHE) { cached = STREAM_CACHE.get(streamKey(knownSource, wantVideo)); }
            if (cached != null && cached.isFresh()) {
                try { prepareSource(cached.url, cached.headers); } catch (Exception e) { fail(e); }
                return;
            }
        }
        PLAYBACK_IO.execute(() -> {
            try {
                final String source = wantVideo ? resolveVideoSource(videoId) : videoId;
                if (source == null) throw new IllegalStateException("No hay videoclip para esta pista");
                final String key = streamKey(source, wantVideo);
                final String target = "https://www.youtube.com/watch?v=" + source;
                CachedStream resolved = awaitPrefetch(key);
                if (resolved == null) resolved = resolveToCache(key, target, wantVideo);
                final CachedStream ready = resolved;
                handler.post(() -> {
                    if (generation != requestGeneration) return;
                    try { prepareSource(ready.url, ready.headers); } catch (Exception e) { fail(e); }
                });
            } catch (Exception e) {
                if (wantVideo) {
                    // Sin MP4 con sonido para este video: se vuelve al audio y la pantalla lo avisa.
                    handler.post(() -> { if (generation == requestGeneration) videoUnavailable(videoId); });
                    return;
                }
                handler.post(() -> { if (generation == requestGeneration) fail(e); });
            }
        });
    }

    /** Videoclip de cada pista de audio ya consultada; "" si no tiene (se consulta una sola vez por canción). */
    private static final Map<String, String> VIDEO_COUNTERPARTS = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * ID del videoclip. Una pista con portada fija y sin videoclip guarda un resultado vacío.
     */
    private static String resolveVideoSource(String videoId) throws Exception {
        if (videoId == null || videoId.isEmpty()) return null;
        String known = VIDEO_COUNTERPARTS.get(videoId);
        if (known == null) {
            String clip = cl.arichard.descargador.util.YtMusicApi.playableVideoSource(videoId);
            known = clip == null ? "" : clip;
            VIDEO_COUNTERPARTS.put(videoId, known);
        }
        return known.isEmpty() ? null : known;
    }

    /** Resuelve el enlace de audio de un video y lo deja en la caché de enlaces. */
    private CachedStream resolveToCache(String videoId, String target) throws Exception {
        return resolveToCache(videoId, target, false);
    }

    /** [key] es la clave de la cache; con [video] se pide un MP4 con imagen y sonido en vez de solo audio. */
    private CachedStream resolveToCache(String key, String target, boolean video) throws Exception {
        final long startedAt = System.currentTimeMillis();
        DescargadorApp app = (DescargadorApp) context;
        if (!app.awaitReady(20)) throw new IllegalStateException(
                app.getInitError() == null ? cl.arichard.descargador.util.Tr.t("El reproductor todavía se está preparando") : app.getInitError());
        JSONObject info = resolveOnlineInfo(target, video);
        String stream = info.getString("url");
        Map<String, String> headers = new HashMap<>();
        JSONObject http = info.optJSONObject("http_headers");
        if (http != null) {
            Iterator<String> keys = http.keys();
            while (keys.hasNext()) { String name = keys.next(); headers.put(name, http.getString(name)); }
        }
        long length = info.optLong("content_length", -1);
        if (info.optString("protocol").startsWith("m3u8")) headers.put("X-MusicBox-HLS", "1");
        if (length > 0) headers.put(LENGTH_KEY, String.valueOf(length));
        CachedStream resolved = new CachedStream(stream, headers);
        synchronized (STREAM_CACHE) { STREAM_CACHE.put(key, resolved); }
        Log.i("MusicBoxPlayer", "Stream resolved in " + (System.currentTimeMillis() - startedAt) + " ms");
        return resolved;
    }

    /** Índice de la canción que sonará después de la actual, o -1 si no hay. */
    static int followingIndex(int current, int size, int repeatMode) {
        if (size <= 0 || current < 0 || current >= size || repeatMode == REPEAT_ONE) return -1;
        int next = current + 1;
        if (next >= size) next = repeatMode == REPEAT_ALL ? 0 : -1;
        return next == current ? -1 : next;
    }

    /** Resuelve por adelantado la siguiente canción online para que empiece sin esperar a YouTube. */
    void prefetchNextStream() {
        int next = followingIndex(currentIndex, queue.size(), repeatMode);
        if (next < 0) return;
        Song song = queue.get(next);
        if (song == null || !song.isOnline()) return;
        final String nextId = song.getVideoId();
        if (!videoMode) { prefetchStream(nextId, false); return; }
        // En modo video se precarga el videoclip de la siguiente canción, que puede ser otro ID.
        PLAYBACK_IO.execute(() -> {
            try { prefetchStream(resolveVideoSource(nextId), true); }
            catch (Exception error) { Log.d("MusicBoxPlayer", "No se pudo precargar el video", error); }
        });
    }

    /** Si ya hay una resolución anticipada en curso para este video, espera a que termine y devuelve su enlace. */
    private CachedStream awaitPrefetch(String videoId) {
        synchronized (STREAM_CACHE) {
            long deadline = System.currentTimeMillis() + 30000;
            while (PREFETCHING.contains(videoId) && System.currentTimeMillis() < deadline) {
                try { STREAM_CACHE.wait(250); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            }
            CachedStream cached = STREAM_CACHE.get(videoId);
            return cached != null && cached.isFresh() ? cached : null;
        }
    }

    /** Resuelve en segundo plano el enlace de un video, p. ej. los primeros resultados de una búsqueda. */
    public void prefetchVideo(String videoId) { prefetchStream(videoId, false); }

    private void prefetchStream(String videoId, boolean video) {
        if (videoId == null || videoId.isEmpty()) return;
        final String key = streamKey(videoId, video);
        synchronized (STREAM_CACHE) {
            CachedStream cached = STREAM_CACHE.get(key);
            if ((cached != null && cached.isFresh()) || !PREFETCHING.add(key)) return;
        }
        PLAYBACK_IO.execute(() -> {
            try { resolveToCache(key, "https://www.youtube.com/watch?v=" + videoId, video); }
            catch (Exception e) { Log.w("MusicBoxPlayer", "Precarga sin enlace para " + videoId, e); }
            finally { synchronized (STREAM_CACHE) { PREFETCHING.remove(key); STREAM_CACHE.notifyAll(); } }
        });
    }

    private JSONObject resolveOnlineInfo(String target) throws Exception { return resolveOnlineInfo(target, false); }

    private JSONObject resolveOnlineInfo(String target, boolean video) throws Exception {
        // El audio sale rápido con android_vr, pero el MP4 con imagen y sonido que da ese cliente responde 403: para video
        // se prueban primero los que sí entregan un enlace abierto, en vez de perder medio minuto en clientes que fallan.
        String[] clients = video
                ? new String[]{"android", "web,android", "android_vr,android;player_skip=webpage,configs", "ios,web", ""}
                : new String[]{"android_vr,android;player_skip=webpage,configs", "android_vr,android", "web,android", "ios,web", ""};
        // "" = clientes por defecto de yt-dlp: a veces son los únicos que entregan el audio real de un video.
        Exception lastError = null;
        for (String client : clients) {
            final long clientStart = System.currentTimeMillis();
            try {
                YoutubeDLRequest request = new YoutubeDLRequest(target);
                request.addOption("--no-playlist");
                request.addOption("--no-update");
                request.addOption("--no-warnings");
                request.addOption("--dump-single-json");
                request.addOption("--skip-download");
                request.addOption("--socket-timeout", "12");
                request.addOption("--retries", "2");
                request.addOption("--extractor-retries", "2");
                if (!client.isEmpty()) request.addOption("--extractor-args", "youtube:player_client=" + client);
                // Con video: un solo archivo con imagen y sonido, porque el reproductor no puede unir pistas separadas.
                request.addOption("-f", video
                        ? "best[ext=mp4][height<=720][protocol^=http]/best[protocol^=m3u8]/best[ext=mp4][protocol^=http]/best[protocol^=http]"
                        : "bestaudio[ext=m4a][protocol^=http]/bestaudio[protocol^=http]/best[ext=mp4][protocol^=http]/best[protocol^=http]");
                String output = YoutubeDL.getInstance().execute(request).getOut();
                final long extracted = System.currentTimeMillis() - clientStart;
                JSONObject info = new JSONObject(output);
                String stream = info.optString("url", "");
                if (video && ("none".equals(info.optString("vcodec")) || "none".equals(info.optString("acodec"))))
                    continue;
                if (!stream.isEmpty()) {
                    // El tamaño ya viene en la respuesta de yt-dlp o en el propio enlace: así se evita una petición
                    // de comprobación (unos 2,7 s en el celular). Si el enlace está roto, el reproductor reintenta.
                    if (video && info.optString("protocol").startsWith("m3u8")) return info;
                        long length = knownLength(info, stream);
                    if (length <= 0) length = probeStream(stream, info.optJSONObject("http_headers"));
                    Log.i("MusicBoxTiming", "client=" + client + " yt-dlp=" + extracted + "ms probe="
                            + (System.currentTimeMillis() - clientStart - extracted) + "ms length=" + length);
                    if (length >= 0) {
                        // Un archivo de unos pocos KB para una canción de minutos es un señuelo de YouTube: no se puede reproducir.
                        if (isImpossiblySmall(length, info.optDouble("duration", 0))) {
                            Log.w("MusicBoxPlayer", "Stream descartado con client " + client + ": " + length + " bytes para "
                                    + info.optDouble("duration", 0) + " s");
                            lastError = new IllegalStateException("YouTube entregó un archivo incompleto");
                            continue;
                        }
                        if (length > 0) info.put("content_length", length);
                        return info;
                    }
                }
            } catch (Exception error) {
                lastError = error;
                Log.w("MusicBoxTiming", "client=" + client + " FAILED after " + (System.currentTimeMillis() - clientStart) + "ms");
                Log.w("MusicBoxPlayer", "Resolver failed with client " + client, error);
            }
        }
        throw lastError == null ? new IllegalStateException("YouTube no devolvió un enlace reproducible") : lastError;
    }

    /** Menos de ~3 KB por segundo (24 kbps) en un video de más de 30 s no puede ser el audio real. */
    static boolean isImpossiblySmall(long length, double durationSeconds) {
        return length > 0 && durationSeconds > 30 && length < durationSeconds * 3000;
    }

    /** Tamaño del archivo según yt-dlp o el parámetro clen del enlace; 0 si no se conoce. */
    static long knownLength(JSONObject info, String stream) {
        long size = info.optLong("filesize", 0);
        if (size <= 0) {
            try {
                String clen = Uri.parse(stream).getQueryParameter("clen");
                if (clen != null) size = Long.parseLong(clen);
            } catch (Exception ignored) {}
        }
        // filesize_approx es una estimación: solo sirve si no hay nada exacto, y se verifica con el probe.
        Log.i("MusicBoxTiming", "sizes filesize=" + info.optLong("filesize", 0) + " approx=" + info.optLong("filesize_approx", 0) + " chosen=" + size);
        return Math.max(0, size);
    }

    /** Returns the file size (0 when the server does not say) or -1 when the stream is not reachable. */
    private long probeStream(String stream, JSONObject jsonHeaders) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(stream).openConnection();
            connection.setConnectTimeout(7000);
            connection.setReadTimeout(7000);
            connection.setRequestProperty("Range", "bytes=0-1");
            if (jsonHeaders != null) {
                Iterator<String> keys = jsonHeaders.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    connection.setRequestProperty(key, jsonHeaders.optString(key, ""));
                }
            }
            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK && status != HttpURLConnection.HTTP_PARTIAL) return -1;
            String range = connection.getHeaderField("Content-Range");   // "bytes 0-1/7234567"
            if (range != null && range.contains("/")) {
                try { return Long.parseLong(range.substring(range.indexOf('/') + 1).trim()); }
                catch (NumberFormatException ignored) {}
            }
            return 0;
        } catch (Exception error) {
            Log.w("MusicBoxPlayer", "Resolved stream was not reachable", error);
            return -1;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private void prepareSource(String source, Map<String, String> headers) throws Exception {
        PlaybackEngine engine = createEngine();
        activePlayer = engine;
        registerNoisyReceiver();
        SurfaceHolder display = videoView != null && videoView.getHolder().getSurface().isValid()
                ? videoView.getHolder() : null;
        loading(true);
        engine.prepare(source, headers, videoMode, !videoMode, display, new PlaybackEngine.Callback() {
            @Override public void onPrepared() {
                if (engine != activePlayer) return;
                prepared = true;
                fitVideo();
                notifyVideo();
                int resumed = resumePosition;
                if (resumePosition > 0) {
                    int duration = engine.getDuration();
                    engine.seekTo(duration > 0 ? Math.min(resumePosition, Math.max(0, duration - 1)) : resumePosition);
                    resumePosition = 0;
                }
                loading(false);
                if (playWhenReady && !audioRouteChangePending) {
                    if (requestAudioFocus()) {
                        if (fadingPlayer != null) {
                            engine.setVolume(0f);
                            // La siguiente entra donde empieza a sonar, no en su silencio inicial.
                            cl.arichard.descargador.util.WaveformStore.Envelope entering = envelopeOf(currentSong);
                            if (entering != null && entering.audibleStartMs > 300 && resumed == 0) engine.seekTo(entering.audibleStartMs);
                        }
                        engine.play();
                        if (fadingPlayer != null) {
                            int left = fadeEndAtMs - fadingPlayer.getCurrentPosition();
                            fadeDuration = Math.max(300, Math.min(fadeDuration, left));
                            fadeStarted = android.os.SystemClock.elapsedRealtime();
                            fadeLive = true;
                            handler.post(fadeTick);
                        }
                        registerNoisyReceiver();
                    } else {
                        playWhenReady = false;
                    }
                }
                startListening();
                notifyState();
                prefetchNextStream();
                warmWaveforms();
                autoplayNeeded(false);
                recoveryStartPosition = engine.getCurrentPosition();
                Log.i("MusicBoxPlayer", "Prepared (" + (engine instanceof ExoPlaybackEngine ? "ExoPlayer" : "MediaPlayer")
                        + "): duration=" + engine.getDuration() + " video=" + engine.getVideoWidth() + "x" + engine.getVideoHeight());
            }

            @Override public void onError(Exception error) {
                if (engine == activePlayer) fail(error);
            }

            @Override public void onSeekComplete() {
                if (engine != activePlayer || !prepared) return;
                int position = engine.getCurrentPosition();
                int duration = engine.getDuration();
                for (PlaybackListener listener : new ArrayList<>(listeners)) listener.onProgressUpdate(position, duration);
                savePosition();
                scheduleProgress();
            }

            @Override public void onVideoSizeChanged(int width, int height) {
                if (engine != activePlayer) return;
                fitVideo();
                notifyVideo();
            }

            @Override public void onCompletion() {
                if (engine != activePlayer || !prepared) return;
                if (repeatMode == REPEAT_ONE) {
                    sampleListening();
                    engine.seekTo(0);
                    engine.play();
                    listeningTrackPath = "";
                    startListening();
                    scheduleProgress();
                } else {
                    playNext();
                }
            }

            @Override public void onRoutingChanged() {
                if (engine == activePlayer && prepared) rememberRoutedOutput();
            }
        });
    }

    void fail(Exception error) {
        stopCrossfade();
        Log.e("MusicBoxPlayer", "Playback failed", error);
        int position = resumePosition;
        if (prepared && activePlayer != null) {
            position = activePlayer.getCurrentPosition();
        }
        if (playbackRecoveryAttempts > 0 && position - recoveryStartPosition > RECOVERY_RESET_MS) {
            playbackRecoveryAttempts = 0;
        }
        prepared = false;
        releasePlayer();
        boolean offline = online && context instanceof DescargadorApp && !((DescargadorApp) context).hasNetwork();
        if (online && currentSong != null && playWhenReady && !offline && playbackRecoveryAttempts < MAX_RECOVERY_ATTEMPTS) {
            playbackRecoveryAttempts++;
            final int backoff = 500 * playbackRecoveryAttempts;
            resumePosition = position;
            String failedSource = videoMode ? VIDEO_COUNTERPARTS.get(currentOnlineVideoId) : currentOnlineVideoId;
            synchronized (STREAM_CACHE) { STREAM_CACHE.remove(streamKey(failedSource == null ? currentOnlineVideoId : failedSource, videoMode)); }
            final int requestGeneration = generation;
            loading(true);
            registerNoisyReceiver();
            handler.postDelayed(() -> {
                if (requestGeneration != generation) return;
                if (playWhenReady) startCurrent();
                else loading(false);
            }, backoff);
            return;
        }
        loading(false);
        if (videoMode && online && !offline) {
            resumePosition = position;
            videoUnavailable(currentOnlineVideoId);
            return;
        }
        String message;
        if (!online) message = cl.arichard.descargador.util.Tr.t("No se pudo abrir el archivo de música.");
        else if (offline)
            message = cl.arichard.descargador.util.Tr.t("Sin conexión a internet. Revisa Wi-Fi o datos móviles.");
        else message = cl.arichard.descargador.util.Tr.t("No pudimos reproducir esta canción. YouTube rechazó temporalmente el audio.");
        for (PlaybackListener l : new ArrayList<>(listeners)) l.onPlaybackError(message);
        notifyState();
        if (currentSong != null) playbackFailures.add(currentSong.getPath());
        if (offline) playWhenReady = false;
        continueAfterPlaybackFailure();
    }

    private void continueAfterPlaybackFailure() {
        if (!playWhenReady) return;
        for (int offset = 1; offset < queue.size(); offset++) {
            int index = currentIndex + offset;
            if (index >= queue.size()) {
                if (repeatMode != REPEAT_ALL) break;
                index %= queue.size();
            }
            if (playbackFailures.contains(queue.get(index).getPath())) continue;
            currentIndex = index;
            currentSong = queue.get(index);
            resumePosition = 0; savedDuration = 0;
            final int requestGeneration = generation;
            handler.post(() -> {
                if (requestGeneration == generation && playWhenReady) startCurrent();
            });
            notifyQueue();
            return;
        }
        if (online && autoplayLoading && queueAllowsAutoplay) {
            advanceWhenReady = true;
            loading(true);
        } else {
            playWhenReady = false;
            notifyState();
        }
    }
    // ── AudioFocus ─────────────────────────────────────────────────────────
    private final AudioManager.OnAudioFocusChangeListener audioFocusListener = focusChange -> {
        if (focusChange != AudioManager.AUDIOFOCUS_GAIN) stopCrossfade();
        switch (focusChange) {
            case AudioManager.AUDIOFOCUS_GAIN:
                if (activePlayer != null) activePlayer.setVolume(1f);
                if (resumeOnFocusGain && playWhenReady && prepared && activePlayer != null && !activePlayer.isPlaying()) {
                    resumeOnFocusGain = false;
                    activePlayer.play();
                    notifyState();
                }
                resumeOnFocusGain = false;
                break;
            case AudioManager.AUDIOFOCUS_LOSS:
                resumeOnFocusGain = false;
                if (isPlaying()) { playWhenReady = false; activePlayer.pause(); notifyState(); }
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                resumeOnFocusGain = isPlaying();
                if (isPlaying()) { activePlayer.pause(); notifyState(); }
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                if (activePlayer != null) activePlayer.setVolume(0.2f);
                break;
        }
    };

    private boolean requestAudioFocus() {
        if (audioManager == null) return true;
        if (hasAudioFocus) return true;
        int result;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build())
                    .setAcceptsDelayedFocusGain(true)
                    .setOnAudioFocusChangeListener(audioFocusListener, handler)
                    .build();
            result = audioManager.requestAudioFocus(focusRequest);
        } else {
            //noinspection deprecation
            result = audioManager.requestAudioFocus(audioFocusListener,
                    AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
        }
        hasAudioFocus = (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
        return hasAudioFocus;
    }

    private void abandonAudioFocus() {
        if (audioManager == null || !hasAudioFocus) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && focusRequest != null) {
            audioManager.abandonAudioFocusRequest(focusRequest);
        } else {
            //noinspection deprecation
            audioManager.abandonAudioFocus(audioFocusListener);
        }
        hasAudioFocus = false;
    }

    // Receiver para audífonos desconectados
    private final BroadcastReceiver noisyReceiver = new BroadcastReceiver() {
        @Override public void onReceive(android.content.Context ctx, Intent intent) {
            if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(intent.getAction())) {
                scheduleAudioRouteChangeCheck();
            }
        }
    };

    private final AudioDeviceCallback audioDeviceCallback = new AudioDeviceCallback() {
        @Override public void onAudioDevicesAdded(AudioDeviceInfo[] devices) {
            for (AudioDeviceInfo device : devices)
                onAudioOutputAdded(device.getType(), device.isSink());
            rememberRoutedOutput();
        }

        @Override public void onAudioDevicesRemoved(AudioDeviceInfo[] devices) {
            for (AudioDeviceInfo device : devices)
                onAudioOutputRemoved(device.getId(), device.getType(), device.isSink());
        }
    };

    // These are integer comparisons; older Android versions never report the newer types.
    @android.annotation.SuppressLint("InlinedApi")
    private static boolean isPersonalOutput(int type) {
        return type == AudioDeviceInfo.TYPE_WIRED_HEADSET || type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
                || type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                || type == AudioDeviceInfo.TYPE_USB_HEADSET || type == AudioDeviceInfo.TYPE_USB_DEVICE
                || type == AudioDeviceInfo.TYPE_BLE_HEADSET;
    }

    void onAudioOutputRemoved(int id, int type, boolean sink) {
        if (sink && (id == routedOutputId || (routedOutputId < 0 && isPersonalOutput(type))))
            scheduleAudioRouteChangeCheck();
    }

    void onAudioOutputAdded(int type, boolean sink) {
        if (audioRouteChangePending && sink && isPersonalOutput(type))
            personalOutputAddedDuringRouteChange = true;
    }

    private void scheduleAudioRouteChangeCheck() {
        if (!audioRouteChangePending) personalOutputAddedDuringRouteChange = false;
        resumeOnFocusGain = false;
        advanceWhenReady = false;
        audioRouteChangePending = true;
        handler.removeCallbacks(confirmAudioRouteChange);
        handler.postDelayed(confirmAudioRouteChange, AUDIO_ROUTE_CHANGE_GRACE_MS);
    }

    private boolean hasPersonalOutputAfterRouteChange() {
        if (prepared && activePlayer != null) {
            AudioDeviceInfo device = activePlayer.getRoutedDevice();
            if (device != null) {
                if (!isPersonalOutput(device.getType())) return false;
                return isAudioOutputConnected(device.getId());
            }
        }
        if (personalOutputAddedDuringRouteChange) return true;
        if (audioManager == null) return false;
        for (AudioDeviceInfo available : audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS))
            if (available.isSink() && isPersonalOutput(available.getType())) return true;
        return false;
    }

    private boolean isAudioOutputConnected(int id) {
        if (audioManager == null) return true;
        for (AudioDeviceInfo available : audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS))
            if (available.getId() == id) return true;
        return false;
    }

    private void resumePlaybackAfterRouteChange() {
        if (!playWhenReady || !prepared || activePlayer == null || activePlayer.isPlaying()) return;
        if (requestAudioFocus()) {
            activePlayer.play();
            registerNoisyReceiver();
        } else {
            playWhenReady = false;
        }
        notifyState();
    }

    private void rememberRoutedOutput() {
        if (!prepared || activePlayer == null) return;
        AudioDeviceInfo device = activePlayer.getRoutedDevice();
        if (device == null) return;
        if (routedOutputId >= 0 && routedPersonalOutput && routedOutputId != device.getId() && audioManager != null) {
            boolean stillConnected = false;
            for (AudioDeviceInfo available : audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS))
                if (available.getId() == routedOutputId) stillConnected = true;
            if (!stillConnected) {
                if (isPersonalOutput(device.getType())) {
                    handler.removeCallbacks(confirmAudioRouteChange);
                    audioRouteChangePending = false;
                    personalOutputAddedDuringRouteChange = false;
                } else {
                    scheduleAudioRouteChangeCheck();
                }
            }
        }
        routedOutputId = device.getId();
        routedPersonalOutput = isPersonalOutput(device.getType());
    }

    private void registerNoisyReceiver() {
        if (noisyReceiverRegistered) { rememberRoutedOutput(); return; }
        IntentFilter filter = new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
        context.registerReceiver(noisyReceiver, filter);
        noisyReceiverRegistered = true;
        if (audioManager != null) audioManager.registerAudioDeviceCallback(audioDeviceCallback, handler);
        rememberRoutedOutput();
    }

    private void unregisterNoisyReceiver() {
        handler.removeCallbacks(confirmAudioRouteChange);
        audioRouteChangePending = false;
        personalOutputAddedDuringRouteChange = false;
        if (!noisyReceiverRegistered) return;
        try { context.unregisterReceiver(noisyReceiver); } catch (Exception ignored) {}
        if (audioManager != null) audioManager.unregisterAudioDeviceCallback(audioDeviceCallback);
        noisyReceiverRegistered = false;
        routedOutputId = -1;
        routedPersonalOutput = false;
    }
    // ── Fin AudioFocus ─────────────────────────────────────────────────────

    /** Llamado por MediaSessionCompat.Callback desde MusicService (audífonos, asistente, etc.) */
    public void play() {
        if (currentSong == null) return;
        if (!prepared || activePlayer == null) { recoveryPath = ""; playbackFailures.clear(); playWhenReady = true; startCurrent(); return; }
        if (!activePlayer.isPlaying() && requestAudioFocus()) {
            playWhenReady = true;
            activePlayer.play();
            registerNoisyReceiver();
            notifyState();
        }
    }

    /**
     * Detiene todo y libera el reproductor (la app se cerró desde recientes). Recuerda la posición para retomar la próxima vez.
     */
    public void stopAndRelease() {
        stopCrossfade();
        autoplayGeneration++;
        autoplayLoading = advanceWhenReady = false;
        autoplayTriedFor = "";
        autoplayError = "";
        saveSession();
        playWhenReady = false;
        releasePlayer();
        detachOnlineWebView();
        notifyState();
    }

    /** Llamado por MediaSessionCompat.Callback desde MusicService */
    public void pause() {
        stopCrossfade();
        playWhenReady = false;
        advanceWhenReady = false;
        resumeOnFocusGain = false;
        if (isPlaying()) activePlayer.pause();
        abandonAudioFocus();
        unregisterNoisyReceiver();
        notifyState();
    }

    public void togglePlayPause() {
        if (currentSong == null) return;
        if (!prepared || activePlayer == null) {
            if (loading) playWhenReady = !playWhenReady;
            else { recoveryPath = ""; playbackFailures.clear(); startCurrent(); }
            return;
        }
        if (activePlayer.isPlaying()) {
            pause();
            return;
        } else {
            if (requestAudioFocus()) {
                playWhenReady = true;
                activePlayer.play();
                registerNoisyReceiver();
            }
        }
        notifyState();
    }
    public void playNext() {
        if (queue.isEmpty()) return;
        resumePosition = 0; savedDuration = 0;
        currentIndex++;
        if (currentIndex >= queue.size()) {
            if (repeatMode != REPEAT_ALL && autoplayNeeded(true)) { currentIndex = queue.size() - 1; advanceWhenReady = true; return; }
            if (repeatMode != REPEAT_ALL) { currentIndex = queue.size() - 1; playWhenReady = false; releasePlayer(); loading(false); notifyState(); return; }
            currentIndex = 0;
        }
        currentSong = queue.get(Math.max(0, currentIndex));
        listeningTrackPath = "";
        startCurrent();
    }
    public boolean isAutoplay() { return context != null && context.getSharedPreferences("player", 0).getBoolean("autoplay", true); }
    public boolean isAutoplayLoading() { return autoplayLoading; }
    public String getAutoplayError() { return autoplayError; }
    public void retryAutoplay() {
        if (autoplayLoading || currentIndex < queue.size() - 1) return;
        autoplayTriedFor = "";
        fetchAutoplay();
    }

    public void setAutoplay(boolean enabled) {
        if (playbackPreferences != null) playbackPreferences.edit().putBoolean("autoplay", enabled).apply();
    }

    private void onAutoplayPreferenceChanged() {
        if (isAutoplay()) {
            if (isPlaying()) retryAutoplay();
        } else {
            autoplayGeneration++;
            autoplayLoading = advanceWhenReady = false;
            autoplayTriedFor = "";
            autoplayError = "";
        }
        for (PlaybackListener listener : new ArrayList<>(listeners)) listener.onQueueChanged();
    }

    /** Hay que buscar canciones relacionadas: sonando una de YouTube, al final de la cola y sin haberlo intentado ya. */
    private boolean autoplayNeeded(boolean atEnd) {
        String seed = playbackVideoId();
        if (!queueAllowsAutoplay || !isAutoplay() || currentSong == null || seed == null || repeatMode == REPEAT_ONE) return false;
        if (autoplayLoading) return true;
        if (seed.equals(autoplayTriedFor) && (autoplayError.isEmpty() || autoplayAttempts >= 3)) return false;
        if (!atEnd && currentIndex < queue.size() - 1) return false;
        fetchAutoplay();
        return autoplayLoading;
    }

    /** Agrega al final de la cola canciones parecidas a la actual (radio de YouTube Music, solo canciones). */
    private void fetchAutoplay() {
        final String seed = playbackVideoId();
        if (!queueAllowsAutoplay || autoplayLoading || !isAutoplay() || seed == null || repeatMode == REPEAT_ONE) return;
        final List<Song> snapshot = new ArrayList<>(originalQueue);
        final int requestGeneration = autoplayGeneration;
        if (seed.equals(autoplayTriedFor) && (autoplayError.isEmpty() || autoplayAttempts >= 3)) return;
        if (!seed.equals(autoplayTriedFor)) autoplayAttempts = 0;
        autoplayAttempts++;
        autoplayTriedFor = seed;
        autoplayError = "";
        autoplayLoading = true;
        notifyQueue();
        PLAYBACK_IO.execute(() -> {
            List<cl.arichard.descargador.model.SearchResult> related = null;
            try {
                if (snapshot.size() > 1 && ("playlist".equals(sourceType) || "folder".equals(sourceType))) {
                    List<String> ids = new ArrayList<>(), titles = new ArrayList<>();
                    for (Song song : snapshot) {
                        String id = song.isOnline() ? song.getVideoId() : cl.arichard.descargador.data.PlaylistManager.getInstance(context).videoIdFor(song.getPath());
                        if (id != null && !id.isEmpty()) ids.add(id);
                        titles.add(song.getTitle());
                    }
                    var seeds = cl.arichard.descargador.data.RecommendationEngine.playlistSeeds(ids,
                            id -> cl.arichard.descargador.data.ClickStats.getInstance(context).count("yt:" + id), 4);
                    var lists = new ArrayList<cl.arichard.descargador.data.RecommendationEngine.Weighted>();
                    for (var entry : seeds) {
                        try { lists.add(new cl.arichard.descargador.data.RecommendationEngine.Weighted(entry.weight,
                                cl.arichard.descargador.util.YtMusicApi.related(entry.videoId, 25))); }
                        catch (Exception ignored) {}
                    }
                    related = cl.arichard.descargador.data.RecommendationEngine.combine(lists,
                            new HashSet<>(ids), titles, 15, "playlist").songs;
                } else related = cl.arichard.descargador.util.YtMusicApi.related(seed, 25);
            }
            catch (Exception e) { Log.w("MusicBoxPlayer", "Autoplay sin resultados", e); }
            final List<cl.arichard.descargador.model.SearchResult> found = related;
            Log.i("MusicBoxAutoplay", "Radio result: seed=" + seed + ", attempt=" + autoplayAttempts
                    + ", tracks=" + (found == null ? -1 : found.size()));
            handler.post(() -> completeAutoplay(seed, requestGeneration, found));
        });
    }

    void completeAutoplay(String seed, int requestGeneration, List<cl.arichard.descargador.model.SearchResult> found) {
        if (requestGeneration != autoplayGeneration || !queueAllowsAutoplay) return;
        autoplayLoading = false;
        if (currentSong == null) return;
        if (!seed.equals(playbackVideoId())) {
            if (currentIndex >= queue.size() - 1) fetchAutoplay();
            return;
        }
        List<String> paths = new ArrayList<>();
        for (Song queued : queue) paths.add(queueIdentity(queued));
        List<Song> more = isAutoplay() ? Autoplay.pick(found, paths) : new ArrayList<>();
        if (!more.isEmpty()) {
            queue.addAll(more); originalQueue.addAll(more);
            autoplayError = "";
            if (prepared) prefetchNextStream();
        } else if (isAutoplay()) {
            autoplayError = Tr.t("No se pudieron agregar canciones similares. Toca para reintentar.");
            if (autoplayAttempts < 3) handler.postDelayed(() -> {
                if (requestGeneration == autoplayGeneration && seed.equals(currentOnlineVideoId)
                        && !autoplayLoading && !autoplayError.isEmpty()) fetchAutoplay();
            }, 3000);
        }
        notifyQueue();
        if (more.isEmpty() && isAutoplay() && autoplayAttempts < 3) return;
        if (advanceWhenReady) {
            advanceWhenReady = false;
            if (!more.isEmpty() && currentIndex == queue.size() - more.size() - 1) playNext();
            else { playWhenReady = false; releasePlayer(); loading(false); notifyState(); }
        }
    }

    public void playPrevious() {
        if (getCurrentPosition() > 3000) { seekTo(0); return; }
        skipPrevious();
    }
    public void skipPrevious() {
        if (queue.isEmpty()) return;
        resumePosition = 0; savedDuration = 0;
        currentIndex = currentIndex > 0 ? currentIndex - 1 : (repeatMode == REPEAT_ALL ? queue.size() - 1 : 0);
        currentSong = queue.get(currentIndex); startCurrent();
    }
    public void seekTo(int ms) {
        stopCrossfade();
        sampleListening();
        if (prepared && activePlayer != null) {
            int duration = getDuration();
            int target = Math.max(0, duration > 0 ? Math.min(ms, duration) : ms);
            activePlayer.seekTo(target);
            listeningClock = android.os.SystemClock.elapsedRealtime();
            listeningPosition = target;
            listeningPlaying = isPlaying();
        } else {
            listeningPlaying = false;
            resumePosition = Math.max(0, ms);
        }
        savePosition();
        if (shouldPollProgress()) scheduleProgress();
    }
    public boolean toggleShuffle() {
        shuffle = !shuffle;
        queue = new ArrayList<>(originalQueue);
        if (shuffle) Collections.shuffle(queue);
        currentIndex = indexOf(currentSong);
        notifyQueue();
        refreshNotification();
        return shuffle;
    }
    public int toggleRepeat() {
        repeatMode = (repeatMode + 1) % 3;
        context.getSharedPreferences("player", 0).edit().putInt("repeat", repeatMode).apply();
        refreshNotification();
        return repeatMode;
    }
    public void removeSong(String path) {
        boolean current = currentSong != null && path.equals(currentSong.getPath());
        queue.removeIf(s -> path.equals(s.getPath()));
        originalQueue.removeIf(s -> path.equals(s.getPath()));
        if (current) {
            releasePlayer(); loading(false); currentSong = null; currentIndex = -1; resumePosition = 0; savedDuration = 0;
            notifyTrack(); notifyState();
            context.stopService(new android.content.Intent(context, MusicService.class));
        } else currentIndex = indexOf(currentSong);
        notifyQueue();
    }

    public List<Song> getQueue() { return new ArrayList<>(queue); }
    public int getCurrentIndex() { return currentIndex; }
    public void retryQueuedSong(Song song) {
        int index = indexOf(song);
        if (index >= 0) playQueueIndex(index);
    }
    public void playQueueIndex(int index) {
        if (index < 0 || index >= queue.size()) return;
        listeningTrackPath = "";
        recoveryPath = ""; playbackFailures.clear();
        resumePosition = 0; savedDuration = 0;
        currentIndex = index; currentSong = queue.get(index); startCurrent();
    }
    public void enqueue(Song song, boolean playNext) {
        if (song == null) return;
        if (currentSong == null) { playSong(song, Collections.singletonList(song)); return; }
        int existing = indexOf(song);
        if (existing == currentIndex) return;
        if (existing >= 0) {
            queue.remove(existing);
            if (existing < currentIndex) currentIndex--;
        }
        queue.add(playNext ? Math.min(currentIndex + 1, queue.size()) : queue.size(), song);
        originalQueue = new ArrayList<>(queue);
        notifyQueue();
    }
    public void moveQueueItem(int from, int to) {
        if (from < 0 || to < 0 || from >= queue.size() || to >= queue.size() || from == to) return;
        Song song = queue.remove(from); queue.add(to, song);
        if (currentIndex == from) currentIndex = to;
        else if (from < currentIndex && to >= currentIndex) currentIndex--;
        else if (from > currentIndex && to <= currentIndex) currentIndex++;
        originalQueue = new ArrayList<>(queue); notifyQueue();
    }
    public void removeQueueItem(int index) {
        if (index < 0 || index >= queue.size()) return;
        boolean active = index == currentIndex;
        queue.remove(index); originalQueue = new ArrayList<>(queue);
        if (index < currentIndex) currentIndex--;
        if (active) {
            if (queue.isEmpty()) {
                releasePlayer(); loading(false); currentSong = null; currentIndex = -1; resumePosition = 0; savedDuration = 0;
                notifyTrack(); notifyState();
                context.stopService(new android.content.Intent(context, MusicService.class));
            } else {
                resumePosition = 0; savedDuration = 0;
                currentIndex = Math.min(index, queue.size() - 1);
                currentSong = queue.get(currentIndex); startCurrent();
            }
        }
        notifyQueue();
    }
    public void clearUpcoming() {
        autoplayGeneration++;
        autoplayLoading = advanceWhenReady = false;
        autoplayTriedFor = "";
        autoplayError = "";
        if (currentSong == null) { queue.clear(); originalQueue.clear(); currentIndex = -1; }
        else { queue = new ArrayList<>(Collections.singletonList(currentSong)); originalQueue = new ArrayList<>(queue); currentIndex = 0; }
        notifyQueue();
    }
    private void notifyQueue() { saveSession(); for (PlaybackListener l : new ArrayList<>(listeners)) l.onQueueChanged(); }
    public boolean isPlaying() { return prepared && activePlayer != null && activePlayer.isPlaying(); }
    public boolean isLoading() { return loading; }
    /** El selector se habilita cuando la canción tiene video local o un flujo verificado. */
    public boolean canShowVideo() {
        if (currentSong == null) return false;
        if (hasVideo() && (!online || videoMode)) return true;
        String id = playbackVideoId();
        if (id == null || id.isEmpty() || id.equals(failedVideoId)) return false;
        if (cl.arichard.descargador.data.PlaylistManager.getInstance(context).localVideoFor(id) != null) return true;
        String source = VIDEO_COUNTERPARTS.get(id);
        if (source == null || source.isEmpty()) return false;
        synchronized (STREAM_CACHE) {
            CachedStream stream = STREAM_CACHE.get(streamKey(source, true));
            return stream != null && stream.isFresh();
        }
    }

    public void checkVideoAvailability() {
        String id = playbackVideoId();
        if (id == null || id.isEmpty() || canShowVideo() || checkingVideoIds.contains(id)
                || "".equals(VIDEO_COUNTERPARTS.get(id))) return;
        if (id.equals(failedVideoId) && android.os.SystemClock.elapsedRealtime() < videoCheckRetryAt) return;
        if (id.equals(failedVideoId)) failedVideoId = "";
        checkingVideoIds.add(id);
        PLAYBACK_IO.execute(() -> {
            boolean verified = false;
            try {
                String source = resolveVideoSource(id);
                if (source != null) {
                    String key = streamKey(source, true);
                    if (awaitPrefetch(key) == null)
                        resolveToCache(key, "https://www.youtube.com/watch?v=" + source, true);
                    verified = true;
                }
            } catch (Exception error) {
                Log.d("MusicBoxPlayer", "No se pudo comprobar el video", error);
            }
            final boolean available = verified;
            handler.post(() -> {
                checkingVideoIds.remove(id);
                if (!id.equals(playbackVideoId())) return;
                if (!available) markVideoUnavailable(id);
                notifyVideo();
            });
        });
    }

    public boolean isVideoMode() { return videoMode; }

    /**
     * Activa o desactiva la imagen. En linea cambia el flujo (MP4 o solo audio) y sigue desde el mismo segundo,
     * con el mismo estado de reproduccion o pausa.
     */
    public void setVideoMode(boolean value) {
        if (videoMode == value) return;
        if (value && !canShowVideo()) return;
        videoMode = value;
        if (currentSong == null || playbackVideoId() == null) return;
        if (value && hasVideo()) {
            // El flujo actual ya trae imagen. Si es una pista de audio de YouTube Music, esa imagen es solo la portada
            // fija: se busca su videoclip y, si existe, se cambia a él; si no, se deja lo que suena.
            if (!currentSong.isOnline()) return;
            final String id = playbackVideoId();
            String source = VIDEO_COUNTERPARTS.get(id);
            if (source != null && !source.isEmpty() && !id.equals(source)) restartKeepingPosition();
            return;
        }
        restartKeepingPosition();
    }

    private void restartKeepingPosition() {
        boolean resume = playWhenReady || isPlaying();
        int position = getCurrentPosition();
        playbackRecoveryAttempts = 0;
        startCurrent();
        resumePosition = position;
        playWhenReady = resume;
    }

    /** Sale del modo video sin tocar lo que suena: las proximas canciones vuelven a pedir solo audio. */
    public void leaveVideoMode() { videoMode = false; }

    private void videoUnavailable(String videoId) {
        markVideoUnavailable(videoId);
        videoMode = false;
        int position = resumePosition;
        boolean resume = playWhenReady;
        notifyVideo();
        startCurrent();
        resumePosition = position;
        playWhenReady = resume;
    }

    private void markVideoUnavailable(String videoId) {
        failedVideoId = videoId;
        videoCheckRetryAt = android.os.SystemClock.elapsedRealtime() + 60000L;
    }

    public boolean hasVideo() {
        return prepared && activePlayer != null && activePlayer.getVideoWidth() > 0 && activePlayer.getVideoHeight() > 0;
    }
    private void notifyVideo() {
        boolean available = canShowVideo();
        for (PlaybackListener listener : new ArrayList<>(listeners)) listener.onVideoAvailabilityChanged(available);
    }
    public int getCurrentPosition() { return prepared && activePlayer != null ? activePlayer.getCurrentPosition() : resumePosition; }
    public int getDuration() { return prepared && activePlayer != null ? activePlayer.getDuration() : savedDuration; }
    public Song getCurrentSong() { return currentSong; }
    public boolean isOnlineTrack() { return online; }
    public String getCurrentOnlineVideoId() { return currentOnlineVideoId; }
    public String getCurrentOnlineUrl() { return currentOnlineUrl; }
    public boolean isShuffle() { return shuffle; }
    public int getRepeatMode() { return repeatMode; }
    public void addListener(PlaybackListener l) {
        if (l == null || listeners.contains(l)) return;
        boolean wasEmpty = listeners.isEmpty();
        listeners.add(l);
        if (wasEmpty && shouldPollProgress()) {
            handler.removeCallbacks(progressUpdater);
            handler.post(progressUpdater);
            if (isPlaying()) warmWaveforms();
        }
    }
    public void removeListener(PlaybackListener l) {
        boolean wasEmpty = listeners.isEmpty();
        listeners.remove(l);
        if (!wasEmpty && listeners.isEmpty() && shouldPollProgress()) scheduleProgress();
    }
    private void notifyTrack() { saveSession(); for (PlaybackListener l : new ArrayList<>(listeners)) l.onTrackChanged(currentSong); }
    private void notifyState() {
        sampleListening();
        if (context != null && listeningSong != null) cl.arichard.descargador.data.ClickStats.getInstance(context).flushListening();
        savePosition();
        boolean playing = isPlaying();
        handler.removeCallbacks(progressUpdater);
        if (shouldPollProgress()) {
            if (!listeners.isEmpty()) handler.post(progressUpdater);
            else scheduleProgress();
        }
        for (PlaybackListener l : new ArrayList<>(listeners)) l.onPlaybackStateChanged(playing);
        if (currentSong != null && context != null) {
            MusicService.updateNotification(context, currentSong, playing, shouldKeepServiceForeground());
        }
    }
    private void loading(boolean value) { loading = value; for (PlaybackListener l : new ArrayList<>(listeners)) l.onPlaybackLoading(value); }
    private void releasePlayer() {
        sampleListening();
        if (context != null && listeningSong != null) cl.arichard.descargador.data.ClickStats.getInstance(context).flushListening();
        listeningSong = null;
        generation++;
        prepared = false;
        resumeOnFocusGain = false;
        handler.removeCallbacks(progressUpdater);
        abandonAudioFocus();
        unregisterNoisyReceiver();
        if (activePlayer != null) { activePlayer.release(); activePlayer = null; }
    }
}
