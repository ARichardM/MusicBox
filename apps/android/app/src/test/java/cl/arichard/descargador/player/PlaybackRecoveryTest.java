package cl.arichard.descargador.player;

import android.app.Application;
import android.os.Looper;
import cl.arichard.descargador.model.Song;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class PlaybackRecoveryTest {
    public static class OfflineApplication extends cl.arichard.descargador.DescargadorApp {
        @Override public void onCreate() {}
        @Override public boolean hasNetwork() { return false; }
    }
    private static class RecordingPlayer extends PlayerManager {
        final List<String> starts = new ArrayList<>();
        @Override protected void startCurrent() { starts.add(getCurrentSong().getPath()); }
    }
    private RecordingPlayer player;
    private Song first, next;
    @Before public void setup() throws Exception {
        android.content.Context context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("player", 0).edit().clear().putBoolean("autoplay", false).commit();
        player = new RecordingPlayer(); player.init(context);
        first = song("abcdefghijk"); next = song("lmnopqrstuv");
        set("queue", new ArrayList<>(Arrays.asList(first, next)));
        set("originalQueue", new ArrayList<>(Arrays.asList(first, next)));
        set("currentSong", first); set("currentIndex", 0);
        set("recoveryPath", first.getPath());
        set("currentOnlineVideoId", first.getVideoId());
        set("online", true); set("playWhenReady", true);
        set("repeatMode", PlayerManager.REPEAT_OFF);
    }
    private Song song(String id) { return new Song(0, id, "Artist", "YouTube", 0, "youtube:" + id, "YouTube", 0); }
    private void set(String name, Object value) throws Exception {
        Field field = PlayerManager.class.getDeclaredField(name); field.setAccessible(true); field.set(player, value);
    }
    private void idle() { Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600)); }
    @Test public void transientErrorRetriesOnceAndKeepsPositionAndQueue() throws Exception {
        set("resumePosition", 4250);
        player.fail(new IllegalStateException("expired stream"));
        assertTrue(player.isLoading()); idle();
        assertEquals(Arrays.asList(first.getPath()), player.starts);
        assertEquals(4250, player.getCurrentPosition());
        assertEquals(2, player.getQueue().size());
        player.fail(new IllegalStateException("malformed audio")); idle();
        assertEquals(Arrays.asList(first.getPath(), next.getPath()), player.starts);
        assertEquals(next.getPath(), player.getCurrentSong().getPath());
        assertEquals(1, player.getCurrentIndex());
        assertEquals(2, player.getQueue().size());
        player.retryQueuedSong(first);
        assertEquals(first.getPath(), player.getCurrentSong().getPath());
        assertEquals(0, player.getCurrentIndex());
        assertEquals(2, player.getQueue().size());
    }
    @Test public void stoppingOrPausingCancelsPendingRecovery() {
        player.fail(new IllegalStateException("temporary error"));
        player.pause(); idle();
        assertTrue(player.starts.isEmpty()); assertFalse(player.isLoading());
        player.stopAndRelease(); idle();
        assertTrue(player.starts.isEmpty());
    }
    @Test public void repeatAllDoesNotLoopWhenEverySongIsUnavailable() throws Exception {
        set("online", false); set("repeatMode", PlayerManager.REPEAT_ALL);
        player.fail(new IllegalStateException("bad local file")); idle();
        assertEquals(Arrays.asList(next.getPath()), player.starts);
        player.fail(new IllegalStateException("another bad file")); idle();
        assertEquals(1, player.starts.size());
        assertFalse(player.isPlaying()); assertFalse(player.isLoading());
    }
    @Test public void repeatOneStillSkipsAnUnplayableSong() throws Exception {
        set("online", false); set("repeatMode", PlayerManager.REPEAT_ONE);
        player.fail(new IllegalStateException("bad file")); idle();
        assertEquals(next.getPath(), player.getCurrentSong().getPath());
    }
    @Test @Config(application = OfflineApplication.class)
    public void offlineLocalPlaybackStillSkipsCorruptFiles() throws Exception {
        set("online", false);
        player.fail(new IllegalStateException("bad local file")); idle();
        assertEquals(Arrays.asList(next.getPath()), player.starts);
    }
    @Test @Config(application = OfflineApplication.class)
    public void offlineStreamingStopsWithoutDiscardingTheQueue() {
        player.fail(new IllegalStateException("no network")); idle();
        assertTrue(player.starts.isEmpty());
        assertFalse(player.isLoading());
        assertEquals(2, player.getQueue().size());
        assertEquals(first.getPath(), player.getCurrentSong().getPath());
    }
}
