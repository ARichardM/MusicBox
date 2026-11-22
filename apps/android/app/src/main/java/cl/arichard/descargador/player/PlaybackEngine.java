package cl.arichard.descargador.player;

import android.media.AudioDeviceInfo;
import android.view.SurfaceHolder;
import java.util.Map;

/**
 * Backend de reproducción desacoplado de PlayerManager.
 * En dispositivos Android reales se usa ExoPlayer (Media3) con Audio Offload por hardware;
 * en pruebas unitarias sobre Robolectric se usa el backend compatible con ShadowMediaPlayer.
 */
interface PlaybackEngine {
    interface Callback {
        void onPrepared();
        void onCompletion();
        void onSeekComplete();
        void onVideoSizeChanged(int width, int height);
        void onError(Exception error);
        void onRoutingChanged();
    }

    void prepare(String source, Map<String, String> headers, boolean videoMode, boolean allowOffload,
                 SurfaceHolder display, Callback callback) throws Exception;
    void play();
    void pause();
    void seekTo(int positionMs);
    void setVolume(float volume);
    void setDisplay(SurfaceHolder holder);
    boolean isPlaying();
    int getCurrentPosition();
    int getDuration();
    int getVideoWidth();
    int getVideoHeight();
    boolean isSleepingForOffload();
    AudioDeviceInfo getRoutedDevice();
    void release();
}
