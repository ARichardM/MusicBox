package cl.arichard.descargador.player;

import android.app.Application;
import android.media.AudioDeviceInfo;
import android.view.SurfaceHolder;
import cl.arichard.descargador.model.Song;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowSystemClock;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class BatteryOptimizationTest {
    private static class FakeEngine implements PlaybackEngine {
        boolean playing = true;
        int position;
        int duration = 180_000;
        @Override public void prepare(String source, Map<String, String> headers, boolean videoMode,
                                      boolean allowOffload, SurfaceHolder display, Callback callback) {}
        @Override public void play() { playing = true; }
        @Override public void pause() { playing = false; }
        @Override public void seekTo(int positionMs) { position = positionMs; }
        @Override public void setVolume(float volume) {}
        @Override public void setDisplay(SurfaceHolder holder) {}
        @Override public boolean isPlaying() { return playing; }
        @Override public int getCurrentPosition() { return position; }
        @Override public int getDuration() { return duration; }
        @Override public int getVideoWidth() { return 0; }
        @Override public int getVideoHeight() { return 0; }
        @Override public boolean isSleepingForOffload() { return false; }
        @Override public AudioDeviceInfo getRoutedDevice() { return null; }
        @Override public void release() { playing = false; }
    }

    private static class TestPlayer extends PlayerManager {
        @Override protected void startCurrent() {}
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = PlayerManager.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static void call(Object target, String name) throws Exception {
        Method method = PlayerManager.class.getDeclaredMethod(name);
        method.setAccessible(true);
        method.invoke(target);
    }

    @Test
    public void backgroundPollingSleepsUntilQualificationThenZeroWakeupsWithoutCrossfade() throws Exception {
        var context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("player", 0).edit().clear().putBoolean("autoplay", false).commit();
        TestPlayer player = new TestPlayer();
        player.init(context);
        player.setCrossfadeSeconds(0);
        Song song = new Song(0, "Long Track", "Artist", "Album", 180_000, "youtube:battery0001", "", 0);
        FakeEngine engine = new FakeEngine();
        try {
            player.playSong(song, Collections.singletonList(song), false);
            set(player, "activePlayer", engine);
            set(player, "prepared", true);
            call(player, "startListening");

            long initialDelay = player.nextProgressDelayMs();
            assertTrue("Background should schedule one wakeup near 30s, got " + initialDelay,
                    initialDelay >= 29_500L && initialDelay <= 31_000L);

            engine.position = 30_000;
            ShadowSystemClock.advanceBy(Duration.ofSeconds(30));
            call(player, "sampleListening");

            assertEquals("Zero background polling after 30s play count when crossfade is off",
                    -1L, player.nextProgressDelayMs());

            PlayerManager.PlaybackListener uiListener = new PlayerManager.PlaybackListener() {
                @Override public void onTrackChanged(Song s) {}
                @Override public void onPlaybackStateChanged(boolean playing) {}
                @Override public void onProgressUpdate(int pos, int dur) {}
            };
            player.addListener(uiListener);
            assertEquals("Foreground UI gets 350ms progress updates", 350L, player.nextProgressDelayMs());
            player.removeListener(uiListener);
            assertEquals("Detaching UI returns to zero background polling", -1L, player.nextProgressDelayMs());
        } finally {
            player.stopAndRelease();
        }
    }

    @Test
    public void backgroundPollingSchedulesSingleWakeupNearCrossfadeStart() throws Exception {
        var context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("player", 0).edit().clear().putBoolean("autoplay", false).commit();
        TestPlayer player = new TestPlayer();
        player.init(context);
        player.setCrossfadeSeconds(5);
        File file = File.createTempFile("cf-bg", ".wav", context.getCacheDir());
        File nextFile = new File(file.getPath() + "-next");
        assertTrue(nextFile.createNewFile());
        Song first = new Song(0, "First", "Artist", "Album", 180_000, file.getPath(), "", 0);
        Song next = new Song(1, "Next", "Artist", "Album", 180_000, nextFile.getPath(), "", 0);
        FakeEngine engine = new FakeEngine();
        try {
            player.playSong(first, new ArrayList<>(Arrays.asList(first, next)), false);
            set(player, "activePlayer", engine);
            set(player, "prepared", true);
            call(player, "startListening");
            engine.position = 30_000;
            ShadowSystemClock.advanceBy(Duration.ofSeconds(30));
            call(player, "sampleListening");

            long delay = player.nextProgressDelayMs();
            assertTrue("Expected single wakeup ~144.7s before 5s crossfade, got " + delay,
                    delay >= 143_000L && delay <= 146_000L);
        } finally {
            player.stopAndRelease();
            file.delete();
            nextFile.delete();
        }
    }

    @Test
    public void serviceForegroundRequirementReleasesOnPause() throws Exception {
        var context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("player", 0).edit().clear().putBoolean("autoplay", false).commit();
        TestPlayer player = new TestPlayer();
        player.init(context);
        Song song = new Song(0, "Track", "Artist", "Album", 120_000, "youtube:fgtest00001", "", 0);
        FakeEngine engine = new FakeEngine();
        try {
            player.playSong(song, Collections.singletonList(song), false);
            set(player, "activePlayer", engine);
            set(player, "prepared", true);
            assertTrue("Playing state requires foreground service", player.shouldKeepServiceForeground());
            player.pause();
            assertFalse("Paused state must detach foreground service", player.shouldKeepServiceForeground());
        } finally {
            player.stopAndRelease();
        }
    }
}
