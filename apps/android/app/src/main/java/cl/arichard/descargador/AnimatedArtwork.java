package cl.arichard.descargador;

import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.graphics.SurfaceTexture;
import android.util.LruCache;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.FrameLayout;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import cl.arichard.descargador.model.Song;
import cl.arichard.descargador.util.Tr;

final class AnimatedArtwork implements TextureView.SurfaceTextureListener {
    private static final ExecutorService LOOKUPS = Executors.newSingleThreadExecutor();
    private static final LruCache<String, String> CACHE = new LruCache<>(256);
    private static final Pattern BANDWIDTH = Pattern.compile("[:,]BANDWIDTH=(\\d+)");
    private final Handler main = new Handler(Looper.getMainLooper());
    private final TextureView view;
    private String key = "";
    private String stream = "";
    private Future<?> lookup;
    private Runnable pending;
    private MediaPlayer player;

    AnimatedArtwork(FrameLayout container) {
        view = new TextureView(container.getContext());
        view.setAlpha(0);
        view.setVisibility(View.INVISIBLE);
        view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        view.setSurfaceTextureListener(this);
        container.addView(view, new FrameLayout.LayoutParams(-1, -1));
    }

    void show(Song song) {
        if (song == null || song.getTitle() == null || song.getTitle().trim().isEmpty()
                || song.getArtist() == null || song.getArtist().trim().isEmpty()
                || song.getArtist().equals(Tr.t("Artista desconocido"))) {
            clear();
            return;
        }
        Uri.Builder uri = Uri.parse("https://artwork.boidu.dev/").buildUpon()
                .appendQueryParameter("s", song.getTitle())
                .appendQueryParameter("a", song.getArtist());
        String album = song.getAlbum();
        if (album != null && !album.isEmpty() && !"YouTube".equals(album)
                && !Tr.t("Álbum desconocido").equals(album)) {
            uri.appendQueryParameter("al", album);
        }
        long seconds = Math.round(song.getDuration() / 1000.0);
        if (seconds > 0) uri.appendQueryParameter("d", Long.toString(seconds));
        String request = uri.build().toString();
        if (request.equals(key)) return;
        clear();
        key = request;
        pending = () -> lookup = LOOKUPS.submit(() -> {
            String cacheKey = request + "|" + (android.os.SystemClock.elapsedRealtime() / 3600000L);
            String result = CACHE.get(cacheKey);
            if (result == null) {
                try {
                    result = resolve(request);
                    CACHE.put(cacheKey, result);
                } catch (Exception ignored) {
                    return;
                }
            }
            String url = result;
            main.post(() -> {
                if (!request.equals(key) || url.isEmpty()) return;
                stream = url;
                view.setVisibility(View.VISIBLE);
                startIfReady();
            });
        });
        main.postDelayed(pending, 120);
    }

    void clear() {
        key = "";
        stream = "";
        if (pending != null) main.removeCallbacks(pending);
        if (lookup != null) lookup.cancel(true);
        release();
        view.setVisibility(View.INVISIBLE);
    }

    private void startIfReady() {
        if (stream.isEmpty() || !view.isAvailable() || player != null) return;
        player = new MediaPlayer();
        Surface surface = new Surface(view.getSurfaceTexture());
        try {
            player.setSurface(surface);
            player.setDataSource(stream);
            player.setVolume(0, 0);
            player.setLooping(true);
            player.setOnPreparedListener(media -> {
                if (player == media) media.start();
            });
            player.setOnInfoListener((media, what, extra) -> {
                if (player == media && what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                    view.setAlpha(1);
                }
                return false;
            });
            player.setOnErrorListener((media, what, extra) -> {
                if (player == media) release();
                return true;
            });
            player.prepareAsync();
        } catch (IOException | RuntimeException ignored) {
            release();
        } finally {
            surface.release();
        }
    }

    private void release() {
        view.setAlpha(0);
        if (player != null) {
            player.release();
            player = null;
        }
    }

    private static boolean applePlaylist(URL url) {
        return "https".equals(url.getProtocol()) && "mvod.itunes.apple.com".equals(url.getHost())
                && url.getPath().endsWith(".m3u8");
    }

    private static String resolve(String request) throws Exception {
        JSONObject artwork = new JSONObject(read(new URL(request)));
        String animated = artwork.optString("animated", "");
        if (animated.isEmpty() || "null".equals(animated)) return "";
        URL master = new URL(animated);
        if (!applePlaylist(master)) return "";
        String[] lines = read(master).split("\\r?\\n");
        int lowest = Integer.MAX_VALUE;
        String selected = "";
        for (int i = 0; i + 1 < lines.length; i++) {
            String line = lines[i];
            String uri = lines[i + 1].trim();
            if (!line.startsWith("#EXT-X-STREAM-INF:") || !line.contains("CODECS=\"avc1.")
                    || uri.isEmpty() || uri.startsWith("#")) continue;
            Matcher bandwidth = BANDWIDTH.matcher(line);
            if (!bandwidth.find()) continue;
            int rate = Integer.parseInt(bandwidth.group(1));
            URL variant = new URL(master, uri);
            if (rate < lowest && applePlaylist(variant)) {
                lowest = rate;
                selected = variant.toString();
            }
        }
        if (selected.isEmpty()) return "";
        URL playlist = new URL(selected);
        // Apple uses byte ranges of a single fragmented MP4 for this variant.
        // The native Android HLS player cannot decode that playlist.
        String media = "";
        for (String entry : read(playlist).split("\\r?\\n")) {
            String segment = entry.trim();
            if (segment.isEmpty() || segment.startsWith("#")) continue;
            if (!media.isEmpty() && !media.equals(segment)) return "";
            media = segment;
        }
        if (media.isEmpty()) return "";
        URL video = new URL(playlist, media);
        return "https".equals(video.getProtocol()) && "mvod.itunes.apple.com".equals(video.getHost())
                && video.getPath().endsWith(".mp4") ? video.toString() : "";
    }

    private static String read(URL url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(4000);
        connection.setReadTimeout(4000);
        try (InputStream input = connection.getInputStream()) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
                if (output.size() > 65536) throw new IOException("Artwork response too large");
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            connection.disconnect();
        }
    }

    @Override public void onSurfaceTextureAvailable(SurfaceTexture texture, int width, int height) {
        startIfReady();
    }

    @Override public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int width, int height) {}
    @Override public void onSurfaceTextureUpdated(SurfaceTexture texture) {}

    @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
        release();
        return true;
    }
}
