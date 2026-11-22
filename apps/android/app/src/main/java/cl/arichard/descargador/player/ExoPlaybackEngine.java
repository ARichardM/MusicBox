package cl.arichard.descargador.player;

import android.annotation.SuppressLint;
import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.util.Log;
import android.view.SurfaceHolder;
import androidx.annotation.OptIn;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.common.VideoSize;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reproductor basado en Media3 ExoPlayer con Audio Offload por hardware y programación dinámica
 * para permitir que la CPU principal duerma durante la reproducción con pantalla apagada.
 */
@OptIn(markerClass = UnstableApi.class)
final class ExoPlaybackEngine implements PlaybackEngine {
    private static final String TAG = "MusicBoxPlayer";
    private final Context context;
    private final Handler handler;
    private final AudioManager audioManager;
    private ExoPlayer player;
    private Callback callback;
    private boolean preparedNotified;
    private boolean pendingSeek;
    private boolean released;
    private boolean sleepingForOffload;
    private int videoWidth;
    private int videoHeight;
    private int lastRoutedDeviceId = -1;
    private AudioManager.AudioPlaybackCallback playbackCallback;

    ExoPlaybackEngine(Context context, Handler handler) {
        this.context = context.getApplicationContext();
        this.handler = handler;
        this.audioManager = (AudioManager) this.context.getSystemService(Context.AUDIO_SERVICE);
    }

    @Override
    public void prepare(String source, Map<String, String> headers, boolean videoMode, boolean allowOffload,
                        SurfaceHolder display, Callback callback) {
        this.callback = callback;
        this.preparedNotified = false;
        this.pendingSeek = false;
        this.released = false;
        this.sleepingForOffload = false;
        this.videoWidth = 0;
        this.videoHeight = 0;

        boolean isHttp = source.startsWith("http://") || source.startsWith("https://");
        boolean isHls = headers != null && headers.containsKey("X-MusicBox-HLS");

        Map<String, String> httpHeaders = new HashMap<>();
        if (headers != null) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                String key = entry.getKey();
                if ("X-MusicBox-HLS".equals(key) || "X-MusicBox-Length".equals(key)) continue;
                if (entry.getValue() != null) httpHeaders.put(key, entry.getValue());
            }
        }

        DefaultHttpDataSource.Factory httpFactory = new DefaultHttpDataSource.Factory()
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(12_000)
                .setReadTimeoutMs(15_000);
        if (!httpHeaders.isEmpty()) httpFactory.setDefaultRequestProperties(httpHeaders);
        DefaultDataSource.Factory dataSourceFactory = new DefaultDataSource.Factory(context, httpFactory);

        // En modo solo audio se arranca en ~1 s pero se almacena un búfer amplio en ráfaga para que
        // la radio Wi-Fi/móvil pueda volver a dormir entre cargas.
        DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                        videoMode ? 15_000 : 30_000,
                        videoMode ? 50_000 : 120_000,
                        videoMode ? 1_500 : 1_000,
                        videoMode ? 3_000 : 2_000)
                .build();

        AudioAttributes audioAttributes = new AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build();

        ExoPlayer exo = new ExoPlayer.Builder(context)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(dataSourceFactory))
                .setLoadControl(loadControl)
                .setAudioAttributes(audioAttributes, false)
                .setWakeMode(isHttp ? C.WAKE_MODE_NETWORK : C.WAKE_MODE_LOCAL)
                .experimentalSetDynamicSchedulingEnabled(true)
                .build();
        this.player = exo;

        if (allowOffload && !videoMode) {
            TrackSelectionParameters.AudioOffloadPreferences offload =
                    new TrackSelectionParameters.AudioOffloadPreferences.Builder()
                            .setAudioOffloadMode(TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED)
                            .setIsGaplessSupportRequired(false)
                            .setIsSpeedChangeSupportRequired(false)
                            .build();
            exo.setTrackSelectionParameters(
                    exo.getTrackSelectionParameters().buildUpon()
                            .setAudioOffloadPreferences(offload)
                            .build());
        }

        exo.setPreferredAudioDevice(null);

        exo.addAudioOffloadListener(new ExoPlayer.AudioOffloadListener() {
            @Override public void onSleepingForOffloadChanged(boolean sleeping) {
                if (released || player != exo) return;
                sleepingForOffload = sleeping;
                Log.d(TAG, "Audio offload sleeping=" + sleeping);
            }
        });

        exo.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int state) {
                if (released || player != exo) return;
                if (state == Player.STATE_READY) {
                    if (!preparedNotified) {
                        preparedNotified = true;
                        registerPlaybackCallback();
                        if (callback != null) callback.onPrepared();
                    } else if (pendingSeek) {
                        pendingSeek = false;
                        if (callback != null) callback.onSeekComplete();
                    }
                } else if (state == Player.STATE_ENDED) {
                    if (preparedNotified && callback != null) callback.onCompletion();
                }
            }

            @Override public void onPositionDiscontinuity(Player.PositionInfo oldPosition,
                                                          Player.PositionInfo newPosition, int reason) {
                if (released || player != exo) return;
                if (reason == Player.DISCONTINUITY_REASON_SEEK && preparedNotified && pendingSeek
                        && exo.getPlaybackState() == Player.STATE_READY) {
                    pendingSeek = false;
                    if (callback != null) callback.onSeekComplete();
                }
            }

            @Override public void onVideoSizeChanged(VideoSize size) {
                if (released || player != exo) return;
                videoWidth = size.width;
                videoHeight = size.height;
                if (callback != null) callback.onVideoSizeChanged(size.width, size.height);
            }

            @Override public void onPlayerError(PlaybackException error) {
                if (released || player != exo) return;
                if (callback != null) callback.onError(error);
            }
        });

        if (display != null && display.getSurface() != null && display.getSurface().isValid()) {
            exo.setVideoSurfaceHolder(display);
        }

        Uri uri = (isHttp || source.startsWith("content:") || source.startsWith("file:"))
                ? Uri.parse(source) : Uri.fromFile(new File(source));
        MediaItem.Builder item = new MediaItem.Builder().setUri(uri);
        if (isHls) item.setMimeType(MimeTypes.APPLICATION_M3U8);
        exo.setPlayWhenReady(false);
        exo.setMediaItem(item.build());
        exo.prepare();
    }

    private void registerPlaybackCallback() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || audioManager == null || playbackCallback != null) return;
        playbackCallback = new AudioManager.AudioPlaybackCallback() {
            @Override public void onPlaybackConfigChanged(List<AudioPlaybackConfiguration> configs) {
                if (released) return;
                AudioDeviceInfo routed = extractRoutedDevice(configs);
                int id = routed == null ? -1 : routed.getId();
                if (id != lastRoutedDeviceId) {
                    lastRoutedDeviceId = id;
                    if (callback != null) callback.onRoutingChanged();
                }
            }
        };
        try { audioManager.registerAudioPlaybackCallback(playbackCallback, handler); }
        catch (Exception ignored) { playbackCallback = null; }
    }

    @SuppressLint("NewApi")
    private AudioDeviceInfo extractRoutedDevice(List<AudioPlaybackConfiguration> configs) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || configs == null) return null;
        for (AudioPlaybackConfiguration config : configs) {
            android.media.AudioAttributes attrs = config.getAudioAttributes();
            if (attrs != null && attrs.getUsage() == android.media.AudioAttributes.USAGE_MEDIA) {
                AudioDeviceInfo info = config.getAudioDeviceInfo();
                if (info != null) return info;
            }
        }
        return null;
    }

    @Override public void play() {
        if (released || player == null) return;
        player.setPlayWhenReady(true);
        player.play();
    }

    @Override public void pause() {
        if (released || player == null) return;
        player.setPlayWhenReady(false);
        player.pause();
    }

    @Override public void seekTo(int positionMs) {
        if (released || player == null) return;
        pendingSeek = true;
        player.seekTo(Math.max(0, positionMs));
    }

    @Override public void setVolume(float volume) {
        if (released || player == null) return;
        player.setVolume(Math.max(0f, Math.min(1f, volume)));
    }

    @Override public void setDisplay(SurfaceHolder holder) {
        if (released || player == null) return;
        if (holder == null) player.clearVideoSurface();
        else player.setVideoSurfaceHolder(holder);
    }

    @Override public boolean isPlaying() {
        if (released || player == null) return false;
        int state = player.getPlaybackState();
        return player.getPlayWhenReady() && state != Player.STATE_IDLE && state != Player.STATE_ENDED;
    }

    @Override public int getCurrentPosition() {
        if (released || player == null) return 0;
        return (int) Math.max(0L, player.getCurrentPosition());
    }

    @Override public int getDuration() {
        if (released || player == null) return 0;
        long duration = player.getDuration();
        return duration > 0 && duration != C.TIME_UNSET ? (int) duration : 0;
    }

    @Override public int getVideoWidth() { return videoWidth; }
    @Override public int getVideoHeight() { return videoHeight; }
    @Override public boolean isSleepingForOffload() {
        return !released && player != null && (sleepingForOffload || player.isSleepingForOffload());
    }

    @Override public AudioDeviceInfo getRoutedDevice() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || audioManager == null || released) return null;
        try { return extractRoutedDevice(audioManager.getActivePlaybackConfigurations()); }
        catch (Exception ignored) { return null; }
    }

    @Override public void release() {
        released = true;
        sleepingForOffload = false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && audioManager != null && playbackCallback != null) {
            try { audioManager.unregisterAudioPlaybackCallback(playbackCallback); } catch (Exception ignored) {}
            playbackCallback = null;
        }
        if (player != null) {
            try { player.release(); } catch (Exception ignored) {}
            player = null;
        }
    }
}
