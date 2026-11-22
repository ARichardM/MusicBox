package cl.arichard.descargador.player;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.view.SurfaceHolder;
import java.util.HashMap;
import java.util.Map;

/**
 * Backend basado en android.media.MediaPlayer, usado en pruebas unitarias con Robolectric
 * (ShadowMediaPlayer) donde ExoPlayer nativo no tiene pistas de audio de hardware.
 */
final class MediaPlayerPlaybackEngine implements PlaybackEngine {
    private final Context context;
    private final Handler handler;
    private MediaPlayer player;
    private boolean released;

    MediaPlayerPlaybackEngine(Context context, Handler handler) {
        this.context = context.getApplicationContext();
        this.handler = handler;
    }

    @Override
    public void prepare(String source, Map<String, String> headers, boolean videoMode, boolean allowOffload,
                        SurfaceHolder display, Callback callback) throws Exception {
        released = false;
        MediaPlayer mp = new MediaPlayer();
        this.player = mp;
        mp.setAudioAttributes(new AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .setUsage(AudioAttributes.USAGE_MEDIA).build());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            mp.setPreferredDevice(null);
            mp.addOnRoutingChangedListener(routing -> {
                if (!released && routing == player && callback != null) callback.onRoutingChanged();
            }, handler);
        }
        mp.setOnPreparedListener(ready -> {
            if (!released && ready == player && callback != null) callback.onPrepared();
        });
        mp.setOnErrorListener((err, what, extra) -> {
            if (!released && err == player && callback != null)
                callback.onError(new IllegalStateException("Error del reproductor " + what + "/" + extra));
            return true;
        });
        mp.setOnSeekCompleteListener(done -> {
            if (!released && done == player && callback != null) callback.onSeekComplete();
        });
        mp.setOnVideoSizeChangedListener((sized, w, h) -> {
            if (!released && sized == player && callback != null) callback.onVideoSizeChanged(w, h);
        });
        mp.setOnCompletionListener(done -> {
            if (!released && done == player && callback != null) callback.onCompletion();
        });
        if (display != null && display.getSurface() != null && display.getSurface().isValid()) {
            mp.setDisplay(display);
        }
        if (source.startsWith("http")) {
            Map<String, String> http = headers == null ? new HashMap<>() : new HashMap<>(headers);
            http.remove("X-MusicBox-HLS");
            http.remove("X-MusicBox-Length");
            mp.setDataSource(context, Uri.parse(source), http);
        } else {
            mp.setDataSource(source);
        }
        mp.prepareAsync();
    }

    @Override public void play() {
        if (!released && player != null) player.start();
    }

    @Override public void pause() {
        if (!released && player != null && player.isPlaying()) player.pause();
    }

    @Override public void seekTo(int positionMs) {
        if (released || player == null) return;
        int target = Math.max(0, positionMs);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) player.seekTo(target, MediaPlayer.SEEK_CLOSEST);
        else player.seekTo(target);
    }

    @Override public void setVolume(float volume) {
        if (!released && player != null) player.setVolume(volume, volume);
    }

    @Override public void setDisplay(SurfaceHolder holder) {
        if (!released && player != null) player.setDisplay(holder);
    }

    @Override public boolean isPlaying() {
        try { return !released && player != null && player.isPlaying(); }
        catch (IllegalStateException ignored) { return false; }
    }

    @Override public int getCurrentPosition() {
        try { return !released && player != null ? player.getCurrentPosition() : 0; }
        catch (IllegalStateException ignored) { return 0; }
    }

    @Override public int getDuration() {
        try { return !released && player != null ? player.getDuration() : 0; }
        catch (IllegalStateException ignored) { return 0; }
    }

    @Override public int getVideoWidth() {
        try { return !released && player != null ? player.getVideoWidth() : 0; }
        catch (IllegalStateException ignored) { return 0; }
    }

    @Override public int getVideoHeight() {
        try { return !released && player != null ? player.getVideoHeight() : 0; }
        catch (IllegalStateException ignored) { return 0; }
    }

    @Override public boolean isSleepingForOffload() { return false; }

    @Override public AudioDeviceInfo getRoutedDevice() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P || released || player == null) return null;
        try { return player.getRoutedDevice(); }
        catch (IllegalStateException ignored) { return null; }
    }

    @Override public void release() {
        released = true;
        if (player != null) {
            try { player.release(); } catch (Exception ignored) {}
            player = null;
        }
    }
}
