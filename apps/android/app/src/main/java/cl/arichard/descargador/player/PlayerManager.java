package cl.arichard.descargador.player;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.MediaDataSource;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.net.HttpURLConnection;
import java.net.URL;
import java.io.IOException;
import java.io.InputStream;

/** Single native player shared by the full screen and mini player. */
public class PlayerManager {
    private static final long STREAM_CACHE_MS = 10 * 60 * 1000L;
    private static final ExecutorService PLAYBACK_IO = Executors.newCachedThreadPool();
    private static final Map<String, CachedStream> STREAM_CACHE = new HashMap<>();
    private static PlayerManager instance;
    public static final int REPEAT_OFF = 0, REPEAT_ALL = 1, REPEAT_ONE = 2;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<PlaybackListener> listeners = new ArrayList<>();
    private Context context;
    private MediaPlayer mediaPlayer;
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

    /** Supplies MediaPlayer with ranged HTTP reads while preserving yt-dlp's headers. */
    private static final class HeaderHttpDataSource extends MediaDataSource {
        private final String source;
        private final Map<String, String> headers;
        private final long contentLength;
        private HttpURLConnection connection;
        private InputStream input;
        private long currentPosition = -1;
        private long rangeEnd = -1;
        private static final long READ_BLOCK_BYTES = 512L * 1024L;

        HeaderHttpDataSource(String source, Map<String, String> headers) {
            this.source = source;
            this.headers = new HashMap<>(headers);
            long parsedLength = -1;
            try {
                String value = Uri.parse(source).getQueryParameter("clen");
                if (value != null) parsedLength = Long.parseLong(value);
            } catch (Exception ignored) {}
            contentLength = parsedLength;
        }

        @Override public synchronized int readAt(long position, byte[] buffer, int offset, int size)
                throws IOException {
            if (position < 0 || size <= 0 || (contentLength >= 0 && position >= contentLength)) return -1;
            if (input == null || currentPosition != position || position > rangeEnd) openAt(position);
            int total = 0;
            while (total < size) {
                int read = input.read(buffer, offset + total, size - total);
                if (read < 0) break;
                total += read;
                currentPosition += read;
            }
            return total == 0 ? -1 : total;
        }

        private void openAt(long position) throws IOException {
            closeConnection();
            connection = (HttpURLConnection) new URL(source).openConnection();
            connection.setConnectTimeout(12000);
            connection.setReadTimeout(15000);
            connection.setInstanceFollowRedirects(true);
            for (Map.Entry<String, String> header : headers.entrySet())
                connection.setRequestProperty(header.getKey(), header.getValue());
            long requestedEnd = position + READ_BLOCK_BYTES - 1;
            if (contentLength >= 0) requestedEnd = Math.min(requestedEnd, contentLength - 1);
            connection.setRequestProperty("Range", "bytes=" + position + "-" + requestedEnd);
            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK && status != HttpURLConnection.HTTP_PARTIAL)
                throw new IOException("YouTube respondió HTTP " + status);
            input = connection.getInputStream();
            if (status == HttpURLConnection.HTTP_OK && position > 0) {
                long remaining = position;
                while (remaining > 0) {
                    long skipped = input.skip(remaining);
                    if (skipped <= 0) throw new IOException("No se pudo posicionar el audio");
                    remaining -= skipped;
                }
            }
            currentPosition = position;
            rangeEnd = requestedEnd;
        }

        @Override public long getSize() { return contentLength; }

        @Override public synchronized void close() { closeConnection(); }

        private void closeConnection() {
            if (input != null) try { input.close(); } catch (IOException ignored) {}
            if (connection != null) connection.disconnect();
            input = null;
            connection = null;
            currentPosition = -1;
            rangeEnd = -1;
        }
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
            if (!prepared || mediaPlayer == null) return;
            int second = getCurrentPosition() / 1000;
            if (second % 5 == 0 && second != lastSavedSecond) { savePosition(); lastSavedSecond = second; }
            for (PlaybackListener l : new ArrayList<>(listeners))
                l.onProgressUpdate(getCurrentPosition(), getDuration());
            handler.postDelayed(this, 350);
        }
    };

    public static synchronized PlayerManager getInstance() {
        if (instance == null) instance = new PlayerManager();
        return instance;
    }
    public void init(Context ctx) {
        context = ctx.getApplicationContext();
        audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        android.content.SharedPreferences prefs = context.getSharedPreferences("player", 0);
        repeatMode = prefs.getInt("repeat", REPEAT_ALL);
        try {
            org.json.JSONArray songs = new org.json.JSONArray(prefs.getString("queue", "[]"));
            queue.clear();
            for (int i = 0; i < songs.length(); i++) {
                Song song = Song.fromJson(songs.getJSONObject(i));
                if (song.isOnline() || new java.io.File(song.getPath()).isFile()) queue.add(song);
            }
            originalQueue = new ArrayList<>(queue);
            String path = prefs.getString("current", "");
            for (Song song : queue) if (path.equals(song.getPath())) { currentSong = song; break; }
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
                if (mediaPlayer != null) mediaPlayer.setDisplay(holder);
            }
            @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
                if (mediaPlayer != null) mediaPlayer.setDisplay(holder);
            }
            @Override public void surfaceDestroyed(SurfaceHolder holder) {
                if (mediaPlayer != null) mediaPlayer.setDisplay(null);
            }
        });
        videoView.post(this::fitVideo);
    }
    public void detachOnlineWebView() {
        if (mediaPlayer != null) mediaPlayer.setDisplay(null);
        if (videoView != null && videoView.getParent() instanceof ViewGroup)
            ((ViewGroup) videoView.getParent()).removeView(videoView);
        videoView = null;
    }

    private void fitVideo() {
        if (videoView == null || !prepared || mediaPlayer == null) return;
        ViewGroup host = (ViewGroup) videoView.getParent();
        int width = mediaPlayer.getVideoWidth(), height = mediaPlayer.getVideoHeight();
        if (host == null || width <= 0 || height <= 0 || host.getWidth() <= 0 || host.getHeight() <= 0) return;
        float scale = Math.min((float) host.getWidth() / width, (float) host.getHeight() / height);
        videoView.setLayoutParams(new FrameLayout.LayoutParams(Math.round(width * scale), Math.round(height * scale), Gravity.CENTER));
    }

    public void playSong(Song song, List<Song> newQueue) {
        if (song == null) return;
        if (newQueue != null && !newQueue.isEmpty()) {
            originalQueue = new ArrayList<>(newQueue);
            queue = new ArrayList<>(newQueue);
            if (shuffle) Collections.shuffle(queue);
        }
        resumePosition = 0; savedDuration = 0;
        currentSong = song;
        currentIndex = indexOf(song);
        if (currentIndex < 0 && song != null) { queue.add(0, song); currentIndex = 0; }
        startCurrent();
        notifyQueue();
    }

    private int indexOf(Song song) {
        for (int i = 0; i < queue.size(); i++)
            if (song != null && song.getPath().equals(queue.get(i).getPath())) return i;
        return -1;
    }

    private void startCurrent() {
        if (currentSong == null) return;
        lastSavedSecond = -1;
        if (currentSong.isOnline()) startOnline(currentSong);
        else { online = false; startLocal(); }
    }

    private void startLocal() {
        if (currentSong == null) return;
        releasePlayer();
        playWhenReady = true;
        notifyTrack();
        try { prepareSource(currentSong.getPath(), Collections.emptyMap()); }
        catch (Exception e) { fail(e); }
    }

    public void playOnline(String videoId, String title, String channel, String thumbnail, String url) {
        Song song = new Song(0, title, channel, "YouTube", 0, "youtube:" + videoId, "YouTube", 0);
        playSong(song, Collections.singletonList(song));
    }

    private void startOnline(Song song) {
        releasePlayer();
        final int requestGeneration = generation;
        online = true;
        playWhenReady = true;
        currentOnlineVideoId = song.getVideoId();
        currentOnlineUrl = "https://www.youtube.com/watch?v=" + currentOnlineVideoId;
        notifyTrack();
        notifyState();
        loading(true);
        final String target = currentOnlineUrl;
        final String videoId = currentOnlineVideoId;
        CachedStream cached;
        synchronized (STREAM_CACHE) { cached = STREAM_CACHE.get(videoId); }
        if (cached != null && cached.isFresh()) {
            try { prepareSource(cached.url, cached.headers); } catch (Exception e) { fail(e); }
            return;
        }
        final long startedAt = System.currentTimeMillis();
        PLAYBACK_IO.execute(() -> {
            try {
                DescargadorApp app = (DescargadorApp) context;
                if (!app.awaitReady(20)) throw new IllegalStateException(
                        app.getInitError() == null ? "El reproductor todavía se está preparando" : app.getInitError());
                JSONObject info = resolveOnlineInfo(target);
                String stream = info.getString("url");
                Map<String, String> headers = new HashMap<>();
                JSONObject http = info.optJSONObject("http_headers");
                if (http != null) {
                    Iterator<String> keys = http.keys();
                    while (keys.hasNext()) { String key = keys.next(); headers.put(key, http.getString(key)); }
                }
                synchronized (STREAM_CACHE) { STREAM_CACHE.put(videoId, new CachedStream(stream, headers)); }
                Log.i("MusicBoxPlayer", "Stream resolved in " + (System.currentTimeMillis() - startedAt) + " ms");
                handler.post(() -> {
                    if (generation != requestGeneration) return;
                    try { prepareSource(stream, headers); } catch (Exception e) { fail(e); }
                });
            } catch (Exception e) {
                handler.post(() -> { if (generation == requestGeneration) fail(e); });
            }
        });
    }

    private JSONObject resolveOnlineInfo(String target) throws Exception {
        String[] clients = {"android_vr,android", "web,android", "ios,web"};
        Exception lastError = null;
        for (String client : clients) {
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
                request.addOption("--extractor-args", "youtube:player_client=" + client);
                request.addOption("-f", "bestaudio[ext=m4a][protocol^=http]/bestaudio[protocol^=http]/best[ext=mp4][protocol^=http]/best[protocol^=http]");
                String output = YoutubeDL.getInstance().execute(request).getOut();
                JSONObject info = new JSONObject(output);
                String stream = info.optString("url", "");
                if (!stream.isEmpty() && streamIsReachable(stream, info.optJSONObject("http_headers"))) return info;
            } catch (Exception error) {
                lastError = error;
                Log.w("MusicBoxPlayer", "Resolver failed with client " + client, error);
            }
        }
        throw lastError == null ? new IllegalStateException("YouTube no devolvió un enlace reproducible") : lastError;
    }

    private boolean streamIsReachable(String stream, JSONObject jsonHeaders) {
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
            return status == HttpURLConnection.HTTP_OK || status == HttpURLConnection.HTTP_PARTIAL;
        } catch (Exception error) {
            Log.w("MusicBoxPlayer", "Resolved stream was not reachable", error);
            return false;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private void prepareSource(String source, Map<String, String> headers) throws Exception {
        MediaPlayer player = new MediaPlayer();
        mediaPlayer = player;
        player.setWakeMode(context, PowerManager.PARTIAL_WAKE_LOCK);
        player.setAudioAttributes(new AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .setUsage(AudioAttributes.USAGE_MEDIA).build());
        player.setOnPreparedListener(mp -> {
            if (mp != mediaPlayer) return;
            prepared = true;
            fitVideo();
            notifyVideo();
            if (resumePosition > 0) { mp.seekTo(Math.min(resumePosition, Math.max(0, mp.getDuration() - 1))); resumePosition = 0; }
            loading(false);
            if (playWhenReady) {
                if (requestAudioFocus()) {
                    mp.start();
                    registerNoisyReceiver();
                } else {
                    playWhenReady = false;
                }
            }
            SearchHistoryManager.getInstance(context).recordPlayedSong(currentSong);
            notifyState();
            handler.post(progressUpdater);
            Log.i("MusicBoxPlayer", "Prepared: duration=" + mp.getDuration() + " video=" + mp.getVideoWidth() + "x" + mp.getVideoHeight());
        });
        player.setOnErrorListener((mp, what, extra) -> {
            if (mp == mediaPlayer) fail(new IllegalStateException("Error del reproductor " + what + "/" + extra));
            return true;
        });
        player.setOnVideoSizeChangedListener((mp, width, height) -> { fitVideo(); notifyVideo(); });
        player.setOnCompletionListener(mp -> {
            if (repeatMode == REPEAT_ONE) {
                mp.seekTo(0); mp.start();
                SearchHistoryManager.getInstance(context).recordPlayedSong(currentSong);
            }
            else playNext();
        });
        if (videoView != null && videoView.getHolder().getSurface().isValid()) player.setDisplay(videoView.getHolder());
        // Samsung's MediaPlayer drops request headers from the Context/Uri overload and gets HTTP 403.
        if (source.startsWith("http")) player.setDataSource(new HeaderHttpDataSource(source, headers));
        else player.setDataSource(source);
        loading(true);
        player.prepareAsync();
    }

    private void fail(Exception error) {
        Log.e("MusicBoxPlayer", "Playback failed", error);
        releasePlayer();
        loading(false);
        String message;
        if (!online) message = "No se pudo abrir el archivo de música.";
        else if (context instanceof DescargadorApp && !((DescargadorApp) context).hasNetwork())
            message = "Sin conexión a internet. Revisa Wi-Fi o datos móviles.";
        else message = "No pudimos reproducir esta canción. YouTube rechazó temporalmente el audio.";
        for (PlaybackListener l : new ArrayList<>(listeners)) l.onPlaybackError(message);
        notifyState();
    }
    // ── AudioFocus ─────────────────────────────────────────────────────────
    private final AudioManager.OnAudioFocusChangeListener audioFocusListener = focusChange -> {
        switch (focusChange) {
            case AudioManager.AUDIOFOCUS_GAIN:
                if (mediaPlayer != null) mediaPlayer.setVolume(1f, 1f);
                if (resumeOnFocusGain && prepared && !mediaPlayer.isPlaying()) {
                    mediaPlayer.start();
                    notifyState();
                }
                resumeOnFocusGain = false;
                break;
            case AudioManager.AUDIOFOCUS_LOSS:
                resumeOnFocusGain = false;
                if (isPlaying()) { playWhenReady = false; mediaPlayer.pause(); notifyState(); }
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                resumeOnFocusGain = isPlaying();
                if (isPlaying()) { mediaPlayer.pause(); notifyState(); }
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                if (mediaPlayer != null) mediaPlayer.setVolume(0.2f, 0.2f);
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
                if (isPlaying()) { playWhenReady = false; mediaPlayer.pause(); notifyState(); }
            }
        }
    };

    private void registerNoisyReceiver() {
        if (noisyReceiverRegistered) return;
        IntentFilter filter = new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
        context.registerReceiver(noisyReceiver, filter);
        noisyReceiverRegistered = true;
    }

    private void unregisterNoisyReceiver() {
        if (!noisyReceiverRegistered) return;
        try { context.unregisterReceiver(noisyReceiver); } catch (Exception ignored) {}
        noisyReceiverRegistered = false;
    }
    // ── Fin AudioFocus ─────────────────────────────────────────────────────

    /** Llamado por MediaSessionCompat.Callback desde MusicService (audífonos, asistente, etc.) */
    public void play() {
        if (currentSong == null) return;
        if (!prepared) { playWhenReady = true; startCurrent(); return; }
        if (!mediaPlayer.isPlaying() && requestAudioFocus()) {
            playWhenReady = true;
            mediaPlayer.start();
            registerNoisyReceiver();
            notifyState();
        }
    }

    /** Llamado por MediaSessionCompat.Callback desde MusicService */
    public void pause() {
        if (!prepared || mediaPlayer == null || !mediaPlayer.isPlaying()) return;
        playWhenReady = false;
        mediaPlayer.pause();
        abandonAudioFocus();
        unregisterNoisyReceiver();
        notifyState();
    }

    public void togglePlayPause() {
        if (currentSong == null) return;
        if (!prepared) {
            if (loading) playWhenReady = !playWhenReady;
            else startCurrent();
            return;
        }
        if (mediaPlayer.isPlaying()) {
            playWhenReady = false;
            mediaPlayer.pause();
            abandonAudioFocus();
            unregisterNoisyReceiver();
        } else {
            if (requestAudioFocus()) {
                playWhenReady = true;
                mediaPlayer.start();
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
            if (repeatMode != REPEAT_ALL) { currentIndex = queue.size() - 1; playWhenReady = false; releasePlayer(); loading(false); notifyState(); return; }
            currentIndex = 0;
        }
        currentSong = queue.get(Math.max(0, currentIndex));
        startCurrent();
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
        if (prepared && mediaPlayer != null) mediaPlayer.seekTo(Math.max(0, ms));
        else resumePosition = Math.max(0, ms);
        savePosition();
    }
    public boolean toggleShuffle() {
        shuffle = !shuffle;
        queue = new ArrayList<>(originalQueue);
        if (shuffle) Collections.shuffle(queue);
        currentIndex = indexOf(currentSong);
        notifyQueue();
        return shuffle;
    }
    public int toggleRepeat() {
        repeatMode = (repeatMode + 1) % 3;
        context.getSharedPreferences("player", 0).edit().putInt("repeat", repeatMode).apply();
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
    public void playQueueIndex(int index) {
        if (index < 0 || index >= queue.size()) return;
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
        if (currentSong == null) { queue.clear(); originalQueue.clear(); currentIndex = -1; }
        else { queue = new ArrayList<>(Collections.singletonList(currentSong)); originalQueue = new ArrayList<>(queue); currentIndex = 0; }
        notifyQueue();
    }
    private void notifyQueue() { saveSession(); for (PlaybackListener l : new ArrayList<>(listeners)) l.onQueueChanged(); }
    public boolean isPlaying() { return prepared && mediaPlayer != null && mediaPlayer.isPlaying(); }
    public boolean isLoading() { return loading; }
    public boolean hasVideo() {
        return prepared && mediaPlayer != null && mediaPlayer.getVideoWidth() > 0 && mediaPlayer.getVideoHeight() > 0;
    }
    private void notifyVideo() {
        boolean available = hasVideo();
        for (PlaybackListener listener : new ArrayList<>(listeners)) listener.onVideoAvailabilityChanged(available);
    }
    public int getCurrentPosition() { return prepared && mediaPlayer != null ? mediaPlayer.getCurrentPosition() : resumePosition; }
    public int getDuration() { return prepared && mediaPlayer != null ? mediaPlayer.getDuration() : savedDuration; }
    public Song getCurrentSong() { return currentSong; }
    public boolean isOnlineTrack() { return online; }
    public String getCurrentOnlineVideoId() { return currentOnlineVideoId; }
    public String getCurrentOnlineUrl() { return currentOnlineUrl; }
    public boolean isShuffle() { return shuffle; }
    public int getRepeatMode() { return repeatMode; }
    public void addListener(PlaybackListener l) { if (l != null && !listeners.contains(l)) listeners.add(l); }
    public void removeListener(PlaybackListener l) { listeners.remove(l); }
    private void notifyTrack() { saveSession(); for (PlaybackListener l : new ArrayList<>(listeners)) l.onTrackChanged(currentSong); }
    private void notifyState() {
        savePosition();
        boolean playing = isPlaying();
        for (PlaybackListener l : new ArrayList<>(listeners)) l.onPlaybackStateChanged(playing);
        if (currentSong != null) MusicService.updateNotification(context, currentSong, playing);
    }
    private void loading(boolean value) { loading = value; for (PlaybackListener l : new ArrayList<>(listeners)) l.onPlaybackLoading(value); }
    private void releasePlayer() {
        generation++;
        prepared = false;
        handler.removeCallbacks(progressUpdater);
        abandonAudioFocus();
        unregisterNoisyReceiver();
        if (mediaPlayer != null) { mediaPlayer.release(); mediaPlayer = null; }
    }
}
