package cl.arichard.descargador.player;

import android.app.Application;
import cl.arichard.descargador.model.Song;
import cl.arichard.descargador.model.SearchResult;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class AutoplayRecoveryTest {
    private PlayerManager player;
    @Before public void setup() throws Exception {
        android.content.Context context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("player", 0).edit().clear().putBoolean("autoplay", true).commit();
        player = new PlayerManager();
        player.init(context);
        Song song = new Song(0, "Seed", "Artist", "YouTube", 0, "youtube:abcdefghijk", "YouTube", 0);
        set("queue", new ArrayList<>(Collections.singletonList(song)));
        set("originalQueue", new ArrayList<>(Collections.singletonList(song)));
        set("currentSong", song);
        set("currentIndex", 0);
        set("currentOnlineVideoId", "abcdefghijk");
        set("online", true);
        set("autoplayTriedFor", "abcdefghijk");
        set("autoplayLoading", true);
        set("autoplayAttempts", 3);
    }
    private void set(String name, Object value) throws Exception {
        Field field = PlayerManager.class.getDeclaredField(name);
        field.setAccessible(true); field.set(player, value);
    }
    private java.util.List<SearchResult> related() {
        return Collections.singletonList(new SearchResult("lmnopqrstuv", "Next", "Artist", 180, "", ""));
    }
    @Test public void failedLookupExposesRetryAndSuccessfulResponseClearsIt() {
        player.completeAutoplay("abcdefghijk", 0, null);
        assertFalse(player.isAutoplayLoading());
        assertFalse(player.getAutoplayError().isEmpty());
        assertEquals(1, player.getQueue().size());
        player.completeAutoplay("abcdefghijk", 0, related());
        assertEquals(2, player.getQueue().size());
        assertEquals("", player.getAutoplayError());
    }
    @Test public void disablingAutoplayDiscardsAnInflightResponse() {
        player.setAutoplay(false);
        player.completeAutoplay("abcdefghijk", 0, related());
        assertEquals(1, player.getQueue().size());
        assertFalse(player.isAutoplayLoading());
        assertEquals("", player.getAutoplayError());
    }

    @Test public void restrictedArtistQueueDoesNotFetchOrAcceptRelatedSongs() throws Exception {
        set("queueAllowsAutoplay", false);
        set("autoplayLoading", false);
        set("autoplayTriedFor", "");
        player.setAutoplay(true);
        assertTrue(player.isAutoplay());
        assertFalse(player.isAutoplayLoading());
        player.completeAutoplay("abcdefghijk", 0, related());
        assertEquals(1, player.getQueue().size());
        assertEquals("", player.getAutoplayError());
    }
    @Test public void responseFromAnOldQueueDoesNotFinishTheNewRequest() {
        player.completeAutoplay("abcdefghijk", -1, related());
        assertTrue(player.isAutoplayLoading());
        assertEquals(1, player.getQueue().size());
    }
    @Test public void releasingPlaybackAllowsTheSeedToBeRequestedAgain() throws Exception {
        player.stopAndRelease();
        Field field = PlayerManager.class.getDeclaredField("autoplayTriedFor");
        field.setAccessible(true);
        assertEquals("", field.get(player));
    }
    @Test public void transientFailureKeepsThePendingAdvanceWhileRetrying() throws Exception {
        set("autoplayAttempts", 1);
        set("advanceWhenReady", true);
        player.completeAutoplay("abcdefghijk", 0, null);
        Field field = PlayerManager.class.getDeclaredField("advanceWhenReady");
        field.setAccessible(true);
        assertEquals(true, field.get(player));
    }
    @Test public void clearingUpcomingDoesNotPermanentlyBlockTheCurrentSeed() throws Exception {
        player.clearUpcoming();
        Field field = PlayerManager.class.getDeclaredField("autoplayTriedFor");
        field.setAccessible(true);
        assertEquals("", field.get(player));
        assertEquals(1, player.getQueue().size());
    }
}
