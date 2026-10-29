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
import java.io.IOException;
import java.io.InputStream;
import cl.arichard.descargador.util.Tr;

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
    private int routedOutputId = -1;
    private boolean routedPersonalOutput;
    private boolean autoplayLoading, advanceWhenReady;
    private String autoplayTriedFor = "";
    private int autoplayGeneration;
    private int autoplayAttempts;
    private String autoplayError = "";
    private final Set<String> playbackFailures = new HashSet<>();
    private String recoveryPath = "";
    private int playbackRecoveryAttempts;

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
        private static final int CHUNK_BYTES = 512 * 1024;
        private static final int AHEAD_CHUNKS = 8;
        private static final int MAX_CHUNKS = 48;   // 24 MB of audio/video kept in memory at most
        private final Object lock = new Object();
        private final Map<Integer, byte[]> chunks = new HashMap<>();
        private Thread fetcher;
        private final List<Integer> heads = new ArrayList<>();
        private final Map<Integer, Long> lastUse = new HashMap<>();
        private long clock;
        private IOException fetchError;
        private boolean closed;
        private long lastReadEnd = -1;

        HeaderHttpDataSource(String source, Map<String, String> headers) {
            this.source = source;
            this.headers = new HashMap<>(headers);
            long parsedLength = -1;
            String probed = this.headers.remove(LENGTH_KEY);
            if (probed != null) {
                try { parsedLength = Long.parseLong(probed); } catch (NumberFormatException ignored) {}
            }
            if (parsedLength < 0) try {
                String value = Uri.parse(source).getQueryParameter("clen");
                if (value != null) parsedLength = Long.parseLong(value);
            } catch (Exception ignored) {}
            contentLength = parsedLength;
        }

        @Override public int readAt(long position, byte[] buffer, int offset, int size)
                throws IOException {
            if (position < 0 || size <= 0 || (contentLength >= 0 && position >= contentLength)) return -1;
            if (contentLength < 0) return readDirect(position, buffer, offset, size);
            if (lastReadEnd >= 0 && Math.abs(position - lastReadEnd) > 1024)
                Log.i("MusicBoxPlayer", "Player seeks: read at " + position + " after " + lastReadEnd);
            lastReadEnd = position + size;
            int total = 0;
            while (total < size && position + total < contentLength) {
                long absolute = position + total;
                int index = (int) (absolute / CHUNK_BYTES);
                byte[] chunk = awaitChunk(index);
                int inChunk = (int) (absolute - (long) index * CHUNK_BYTES);
                int count = Math.min(size - total, chunk.length - inChunk);
                if (count <= 0) break;
                System.arraycopy(chunk, inChunk, buffer, offset + total, count);
                total += count;
            }
            return total == 0 ? -1 : total;
        }

        /**
         * Blocks until the chunk is downloaded. MP4 files keep audio and video in far apart regions, so the
         * player alternates between several read positions; every recent position is a "head" that the
         * fetcher keeps filling, and nothing already downloaded is dropped until the memory budget is hit.
         */
        private byte[] awaitChunk(int index) throws IOException {
            synchronized (lock) {
                touch(index);
                if (fetcher == null) {
                    fetcher = new Thread(this::fetchLoop, "stream-prefetch");
                    fetcher.setDaemon(true);
                    fetcher.start();
                }
                lock.notifyAll();
                long waitStart = System.currentTimeMillis();
                long deadline = waitStart + 30000;
                while (true) {
                    if (closed) throw new IOException("Fuente cerrada");
                    byte[] chunk = chunks.get(index);
                    if (chunk != null) {
                        long waited = System.currentTimeMillis() - waitStart;
                        if (waited > 150) Log.w("MusicBoxPlayer", "Reader waited " + waited + " ms for chunk " + index + " (buffered " + chunks.size() + ")");
                        lastUse.put(index, ++clock);
                        return chunk;
                    }
                    if (fetchError != null) {
                        IOException error = fetchError;
                        fetchError = null;
                        throw error;
                    }
                    long left = deadline - System.currentTimeMillis();
                    if (left <= 0) throw new IOException("Tiempo agotado descargando el audio");
                    try { lock.wait(Math.min(left, 1000)); }
                    catch (InterruptedException e) { throw new IOException("Descarga interrumpida"); }
                }
            }
        }

        /** Remembers the newest read positions (at most three distinct chunks, most recent first). */
        private void touch(int index) {
            heads.remove(Integer.valueOf(index));
            heads.add(0, index);
            while (heads.size() > 3) heads.remove(heads.size() - 1);
        }

        /** Next chunk to download: the heads first, then what lies ahead of them, then the rest of the file. */
        private int nextWanted(int totalChunks) {
            for (int head : heads) if (head < totalChunks && !chunks.containsKey(head)) return head;
            for (int head : heads)
                for (int i = head + 1; i <= head + AHEAD_CHUNKS && i < totalChunks; i++)
                    if (!chunks.containsKey(i)) return i;
            if (totalChunks <= MAX_CHUNKS)
                for (int i = 0; i < totalChunks; i++) if (!chunks.containsKey(i)) return i;
            return -1;
        }

        private void evictIfNeeded() {
            while (chunks.size() > MAX_CHUNKS) {
                Integer oldest = null;
                long oldestUse = Long.MAX_VALUE;
                for (Integer key : chunks.keySet()) {
                    if (heads.contains(key)) continue;
                    long use = lastUse.containsKey(key) ? lastUse.get(key) : 0;
                    if (use < oldestUse) { oldestUse = use; oldest = key; }
                }
                if (oldest == null) return;
                chunks.remove(oldest);
                lastUse.remove(oldest);
            }
        }

        private void fetchLoop() {
            int totalChunks = (int) ((contentLength + CHUNK_BYTES - 1) / CHUNK_BYTES);
            while (true) {
                int next;
                synchronized (lock) {
                    while (true) {
                        if (closed) return;
                        next = nextWanted(totalChunks);
                        if (next >= 0) break;
                        try { lock.wait(); } catch (InterruptedException e) { return; }
                    }
                }
                try {
                    byte[] data = downloadChunk(next);
                    synchronized (lock) {
                        chunks.put(next, data);
                        lastUse.put(next, ++clock);
                        evictIfNeeded();
                        lock.notifyAll();
                    }
                } catch (IOException e) {
                    synchronized (lock) { fetchError = e; lock.notifyAll(); }
                    try { Thread.sleep(500); } catch (InterruptedException ie) { return; }
                }
            }
        }

        private byte[] downloadChunk(int index) throws IOException {
            long start = (long) index * CHUNK_BYTES;
            long end = Math.min(start + CHUNK_BYTES, contentLength) - 1;
            long began = System.currentTimeMillis();
            IOException last = null;
            for (int attempt = 0; attempt < 2; attempt++) {
                HttpURLConnection conn = null;
                try {
                    conn = (HttpURLConnection) new URL(source).openConnection();
                    conn.setConnectTimeout(12000);
                    conn.setReadTimeout(15000);
                    for (Map.Entry<String, String> header : headers.entrySet())
                        conn.setRequestProperty(header.getKey(), header.getValue());
                    conn.setRequestProperty("Range", "bytes=" + start + "-" + end);
                    int status = conn.getResponseCode();
                    if (status != HttpURLConnection.HTTP_PARTIAL && !(status == HttpURLConnection.HTTP_OK && start == 0))
                        throw new IOException("YouTube respondió HTTP " + status);
                    byte[] data = new byte[(int) (end - start + 1)];
                    try (InputStream in = conn.getInputStream()) {
                        int filled = 0;
                        while (filled < data.length) {
                            int read = in.read(data, filled, data.length - filled);
                            if (read < 0) throw new IOException("Descarga incompleta");
                            filled += read;
                        }
                    }
                    Log.i("MusicBoxPlayer", "Chunk " + index + " downloaded in " + (System.currentTimeMillis() - began) + " ms");
                    return data; // not disconnected: the keep-alive connection is reused by the next chunk
                } catch (IOException e) {
                    last = e;
                    if (conn != null) conn.disconnect();
                }
            }
            throw last;
        }

        private synchronized int readDirect(long position, byte[] buffer, int offset, int size)
                throws IOException {
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

        @Override public void close() {
            synchronized (lock) { closed = true; chunks.clear(); lastUse.clear(); lock.notifyAll(); }
            synchronized (this) { closeConnection(); }
        }

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
            sampleListening();
            int second = getCurrentPosition() / 1000;
            if (second % 5 == 0 && second != lastSavedSecond) { savePosition(); lastSavedSecond = second; }
            for (PlaybackListener l : new ArrayList<>(listeners))
                l.onProgressUpdate(getCurrentPosition(), getDuration());
            handler.postDelayed(this, 350);
        }
    };

    private Song listeningSong;
    private String listeningPlaylist = "";
    private long listeningClock;
    private int listeningPosition;
    private boolean listeningPlaying;
    private boolean listeningStarted;

    private void startListening() {
        listeningSong = currentSong;
        listeningPlaylist = "playlist".equals(sourceType) ? sourceName : "";
        listeningClock = android.os.SystemClock.elapsedRealtime();
        listeningPosition = getCurrentPosition();
        listeningPlaying = isPlaying();
        listeningStarted = listeningPlaying;
        if (listeningPlaying && context != null)
            cl.arichard.descargador.data.ClickStats.getInstance(context).recordListening(listeningSong, listeningPlaylist, 0, true);
    }

    private void sampleListening() {
        if (listeningSong == null || context == null || !prepared || mediaPlayer == null) return;
        long now = android.os.SystemClock.elapsedRealtime();
        int position = getCurrentPosition();
        long wall = now - listeningClock, delta = (long) position - listeningPosition;
        // Position jumps are seeks, while a stalled position is buffering. Neither is listening time.
        if (listeningPlaying && wall > 0 && delta > 0 && Math.abs(delta - wall) < 1200)
            cl.arichard.descargador.data.ClickStats.getInstance(context).recordListening(listeningSong, listeningPlaylist, Math.min(delta, wall), false);
        listeningClock = now;
        listeningPosition = position;
        listeningPlaying = isPlaying();
        if (listeningPlaying && !listeningStarted) {
            cl.arichard.descargador.data.ClickStats.getInstance(context).recordListening(listeningSong, listeningPlaylist, 0, true);
            listeningStarted = true;
        }
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

    private final Runnable notificationObserver = this::refreshNotification;

    /** Rebuilds the media notification (shuffle, repeat and favorite state follow the app). */
    private void refreshNotification() {
        if (currentSong != null && context != null) MusicService.updateNotification(context, currentSong, isPlaying());
    }

    public void init(Context ctx) {
        context = ctx.getApplicationContext();
        cl.arichard.descargador.data.PlaylistManager.getInstance(context).addObserver(notificationObserver);
        audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        android.content.SharedPreferences prefs = context.getSharedPreferences("player", 0);
        repeatMode = prefs.getInt("repeat", REPEAT_ALL);
        queueAllowsAutoplay = prefs.getBoolean("queue_allows_autoplay", true);
        sourceType = prefs.getString("source_type", "songs");
        sourceName = prefs.getString("source_name", "");
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
        playSong(song, newQueue, true);
    }

    private boolean queueAllowsAutoplay = true;

    public void playSong(Song song, List<Song> newQueue, boolean allowAutoplay) {
        if (song == null) return;
        playbackFailures.clear();
        recoveryPath = "";
        if (newQueue != null && !newQueue.isEmpty()) {
            queueAllowsAutoplay = allowAutoplay;
            autoplayGeneration++;
            autoplayLoading = advanceWhenReady = false;
            autoplayTriedFor = "";
            autoplayError = "";
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

    protected void startCurrent() {
        if (currentSong == null) return;
        if (!currentSong.getPath().equals(recoveryPath)) {
            recoveryPath = currentSong.getPath();
            playbackRecoveryAttempts = 0;
        }
        lastSavedSecond = -1;
        if (currentSong.isOnline()) startOnline(currentSong);
        else { online = false; autoplayError = ""; startLocal(); }
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
        registerNoisyReceiver();
        currentOnlineVideoId = song.getVideoId();
        currentOnlineUrl = "https://www.youtube.com/watch?v=" + currentOnlineVideoId;
        notifyTrack();
        notifyState();
        loading(true);
        advanceWhenReady = false;
        if (currentIndex >= queue.size() - 1) fetchAutoplay();
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
                        app.getInitError() == null ? cl.arichard.descargador.util.Tr.t("El reproductor todavía se está preparando") : app.getInitError());
                JSONObject info = resolveOnlineInfo(target);
                String stream = info.getString("url");
                Map<String, String> headers = new HashMap<>();
                JSONObject http = info.optJSONObject("http_headers");
                if (http != null) {
                    Iterator<String> keys = http.keys();
                    while (keys.hasNext()) { String key = keys.next(); headers.put(key, http.getString(key)); }
                }
                long length = info.optLong("content_length", -1);
                if (length > 0) headers.put(LENGTH_KEY, String.valueOf(length));
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
        String[] clients = {"android_vr,android;player_skip=webpage,configs", "android_vr,android", "web,android", "ios,web"};
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
                if (!stream.isEmpty()) {
                    long length = probeStream(stream, info.optJSONObject("http_headers"));
                    if (length >= 0) {
                        if (length > 0) info.put("content_length", length);
                        return info;
                    }
                }
            } catch (Exception error) {
                lastError = error;
                Log.w("MusicBoxPlayer", "Resolver failed with client " + client, error);
            }
        }
        throw lastError == null ? new IllegalStateException("YouTube no devolvió un enlace reproducible") : lastError;
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
        MediaPlayer player = new MediaPlayer();
        mediaPlayer = player;
        player.setWakeMode(context, PowerManager.PARTIAL_WAKE_LOCK);
        player.setAudioAttributes(new AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .setUsage(AudioAttributes.USAGE_MEDIA).build());
        // Let Android route media to the output selected by the user (Bluetooth, USB,
        // wired headphones or speaker), including switches during playback.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) player.setPreferredDevice(null);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) player.addOnRoutingChangedListener(
                routing -> { if (routing == mediaPlayer && prepared) rememberRoutedOutput(); }, handler);
        registerNoisyReceiver();
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
            startListening();
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
            if (mp != mediaPlayer || !prepared) return;
            if (repeatMode == REPEAT_ONE) {
                mp.seekTo(0); mp.start();
                SearchHistoryManager.getInstance(context).recordPlayedSong(currentSong);
                startListening();
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

    void fail(Exception error) {
        Log.e("MusicBoxPlayer", "Playback failed", error);
        int position = resumePosition;
        if (prepared && mediaPlayer != null) {
            try { position = mediaPlayer.getCurrentPosition(); } catch (IllegalStateException ignored) {}
        }
        // MediaPlayer is now in Error state: no transport/state queries are legal.
        prepared = false;
        releasePlayer();
        boolean offline = online && context instanceof DescargadorApp && !((DescargadorApp) context).hasNetwork();
        if (online && currentSong != null && playWhenReady && !offline && playbackRecoveryAttempts < 1) {
            playbackRecoveryAttempts++;
            resumePosition = position;
            synchronized (STREAM_CACHE) { STREAM_CACHE.remove(currentOnlineVideoId); }
            final int requestGeneration = generation;
            loading(true);
            registerNoisyReceiver();
            handler.postDelayed(() -> {
                if (requestGeneration != generation) return;
                if (playWhenReady) startCurrent();
                else loading(false);
            }, 500);
            return;
        }
        loading(false);
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
        switch (focusChange) {
            case AudioManager.AUDIOFOCUS_GAIN:
                if (mediaPlayer != null) mediaPlayer.setVolume(1f, 1f);
                if (resumeOnFocusGain && playWhenReady && prepared && mediaPlayer != null && !mediaPlayer.isPlaying()) {
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
                // Also cancel an upcoming start or a resume after temporary focus loss.
                pause();
            }
        }
    };

    private final AudioDeviceCallback audioDeviceCallback = new AudioDeviceCallback() {
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
        // Unknown route while preparing: prefer pausing over spilling audio to the speaker.
        if (sink && (id == routedOutputId || (routedOutputId < 0 && isPersonalOutput(type)))) pause();
    }

    private void rememberRoutedOutput() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P || !prepared || mediaPlayer == null) return;
        AudioDeviceInfo device = mediaPlayer.getRoutedDevice();
        if (device == null) return;
        if (routedOutputId >= 0 && routedPersonalOutput && routedOutputId != device.getId() && audioManager != null) {
            boolean stillConnected = false;
            for (AudioDeviceInfo available : audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS))
                if (available.getId() == routedOutputId) stillConnected = true;
            if (!stillConnected) pause();
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
        if (!prepared) { recoveryPath = ""; playbackFailures.clear(); playWhenReady = true; startCurrent(); return; }
        if (!mediaPlayer.isPlaying() && requestAudioFocus()) {
            playWhenReady = true;
            mediaPlayer.start();
            registerNoisyReceiver();
            notifyState();
        }
    }

    /**
     * Detiene todo y libera el reproductor (la app se cerró desde recientes). Recuerda la posición para retomar la próxima vez.
     */
    public void stopAndRelease() {
        autoplayGeneration++;
        autoplayLoading = advanceWhenReady = false;
        autoplayTriedFor = "";
        autoplayError = "";
        savePosition();
        playWhenReady = false;
        releasePlayer();
        detachOnlineWebView();
        notifyState();
    }

    /** Llamado por MediaSessionCompat.Callback desde MusicService */
    public void pause() {
        playWhenReady = false;
        advanceWhenReady = false;
        resumeOnFocusGain = false;
        if (isPlaying()) mediaPlayer.pause();
        abandonAudioFocus();
        unregisterNoisyReceiver();
        notifyState();
    }

    public void togglePlayPause() {
        if (currentSong == null) return;
        if (!prepared) {
            if (loading) playWhenReady = !playWhenReady;
            else { recoveryPath = ""; playbackFailures.clear(); startCurrent(); }
            return;
        }
        if (mediaPlayer.isPlaying()) {
            pause();
            return;
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
            if (repeatMode != REPEAT_ALL && autoplayNeeded(true)) { currentIndex = queue.size() - 1; advanceWhenReady = true; return; }
            if (repeatMode != REPEAT_ALL) { currentIndex = queue.size() - 1; playWhenReady = false; releasePlayer(); loading(false); notifyState(); return; }
            currentIndex = 0;
        }
        currentSong = queue.get(Math.max(0, currentIndex));
        startCurrent();
    }
    public boolean isAutoplay() { return context != null && context.getSharedPreferences("player", 0).getBoolean("autoplay", true); }
    public boolean isAutoplayLoading() { return autoplayLoading && online; }
    public String getAutoplayError() { return online ? autoplayError : ""; }
    public void retryAutoplay() {
        if (autoplayLoading || currentIndex < queue.size() - 1) return;
        autoplayTriedFor = "";
        fetchAutoplay();
    }

    public void setAutoplay(boolean enabled) {
        if (context != null) context.getSharedPreferences("player", 0).edit().putBoolean("autoplay", enabled).apply();
        if (enabled) retryAutoplay();
        else {
            autoplayGeneration++;
            autoplayLoading = advanceWhenReady = false;
            autoplayTriedFor = "";
            autoplayError = "";
            notifyQueue();
        }
    }

    /** Hay que buscar canciones relacionadas: sonando una de YouTube, al final de la cola y sin haberlo intentado ya. */
    private boolean autoplayNeeded(boolean atEnd) {
        if (!queueAllowsAutoplay || !isAutoplay() || !online || currentSong == null || currentOnlineVideoId == null || repeatMode == REPEAT_ONE) return false;
        if (autoplayLoading) return true;
        if (currentOnlineVideoId.equals(autoplayTriedFor) && (autoplayError.isEmpty() || autoplayAttempts >= 3)) return false;
        if (!atEnd && currentIndex < queue.size() - 1) return false;
        fetchAutoplay();
        return autoplayLoading;
    }

    /** Agrega al final de la cola canciones parecidas a la actual (radio de YouTube Music, solo canciones). */
    private void fetchAutoplay() {
        if (!queueAllowsAutoplay || autoplayLoading || !isAutoplay() || !online || currentOnlineVideoId == null || repeatMode == REPEAT_ONE) return;
        final String seed = currentOnlineVideoId;
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
            try { related = cl.arichard.descargador.util.YtMusicApi.related(seed, 25); }
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
        if (!online || currentSong == null) return;
        if (!seed.equals(currentOnlineVideoId)) {
            if (currentIndex >= queue.size() - 1) fetchAutoplay();
            return;
        }
        List<String> paths = new ArrayList<>();
        for (Song queued : queue) paths.add(queued.getPath());
        List<Song> more = isAutoplay() ? Autoplay.pick(found, paths) : new ArrayList<>();
        if (!more.isEmpty()) {
            queue.addAll(more); originalQueue.addAll(more);
            autoplayError = "";
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
        sampleListening();
        listeningPlaying = false;
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
        sampleListening();
        if (context != null && listeningSong != null) cl.arichard.descargador.data.ClickStats.getInstance(context).flushListening();
        savePosition();
        boolean playing = isPlaying();
        for (PlaybackListener l : new ArrayList<>(listeners)) l.onPlaybackStateChanged(playing);
        if (currentSong != null) MusicService.updateNotification(context, currentSong, playing);
    }
    private void loading(boolean value) { loading = value; for (PlaybackListener l : new ArrayList<>(listeners)) l.onPlaybackLoading(value); }
    private void releasePlayer() {
        sampleListening();
        if (context != null && listeningSong != null) cl.arichard.descargador.data.ClickStats.getInstance(context).flushListening();
        listeningSong = null;
        generation++;
        prepared = false;
        handler.removeCallbacks(progressUpdater);
        abandonAudioFocus();
        unregisterNoisyReceiver();
        if (mediaPlayer != null) { mediaPlayer.release(); mediaPlayer = null; }
    }
}
