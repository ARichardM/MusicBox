package cl.arichard.descargador.player;

import android.app.Application;
import android.content.Context;
import android.os.Looper;
import cl.arichard.descargador.data.SearchHistoryManager;
import cl.arichard.descargador.model.Song;
import org.json.JSONArray;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowMediaPlayer;
import org.robolectric.shadows.util.DataSource;
import java.io.File;
import java.util.Arrays;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class, qualifiers = "es")
public class PlayerManagerTest {
    private Context context;
    private PlayerManager player;
    private Song a, b, c;

    @Before public void setup() throws Exception {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("player", 0).edit().clear().commit();
        context.getSharedPreferences("musicbox_discovery", 0).edit().clear().commit();
        a = local("a"); b = local("b"); c = local("c");
        JSONArray queue = new JSONArray();
        queue.put(a.toJson()); queue.put(b.toJson()); queue.put(c.toJson());
        context.getSharedPreferences("player", 0).edit().putString("queue", queue.toString())
                .putString("current", b.getPath()).putInt("position", 4200).putInt("duration", 20000).commit();
        player = new PlayerManager(); player.init(context);
    }
    private Song local(String name) throws Exception {
        File file = new File(context.getFilesDir(), name + ".mp3");
        file.createNewFile();
        ShadowMediaPlayer.addMediaInfo(DataSource.toDataSource(file.getAbsolutePath()), new ShadowMediaPlayer.MediaInfo(20000, 0));
        return new Song(0, name, "Artista", "Álbum", 20000, file.getAbsolutePath(), "Música", 0);
    }
    private void prepared() { Shadows.shadowOf(Looper.getMainLooper()).idle(); }

    @Test public void restoredSessionIsPausedAndKeepsPosition() {
        assertEquals(b.getPath(), player.getCurrentSong().getPath());
        assertEquals(4200, player.getCurrentPosition()); assertFalse(player.isPlaying());
        player.togglePlayPause(); prepared();
        assertTrue(player.isPlaying()); assertEquals(4200, player.getCurrentPosition());
    }
    @Test public void manualNextStillAdvancesWhenRepeatingOneSong() {
        assertEquals(PlayerManager.REPEAT_ONE, player.toggleRepeat());
        cl.arichard.descargador.data.ClickStats.getInstance(context).resetForAccount();
        player.playNext(); prepared();
        assertEquals(c.getPath(), player.getCurrentSong().getPath());
        assertEquals(0, player.getCurrentPosition());
    }
    @Test public void swipePreviousChoosesPreviousTrackInsteadOfRestarting() {
        player.togglePlayPause(); prepared();
        player.skipPrevious(); prepared();
        assertEquals(a.getPath(), player.getCurrentSong().getPath());
    }
    @Test public void movingAcrossCurrentTrackPreservesWhatIsPlaying() {
        player.moveQueueItem(0, 2);
        assertEquals(0, player.getCurrentIndex()); assertEquals(b.getPath(), player.getCurrentSong().getPath());
        player.moveQueueItem(0, 2);
        assertEquals(2, player.getCurrentIndex()); assertEquals(b.getPath(), player.getCurrentSong().getPath());
        assertEquals(4200, player.getCurrentPosition());
    }
    @Test public void enqueueNextRelocatesExistingSongWithoutDuplicates() {
        player.enqueue(a, true);
        assertEquals(3, player.getQueue().size()); assertEquals(0, player.getCurrentIndex());
        assertEquals(a.getPath(), player.getQueue().get(1).getPath());
        Song online = new Song(0, "Online", "Artista", "YouTube", 0, "youtube:abcdefghijk", "YouTube", 0);
        player.enqueue(online, false);
        assertEquals(online.getPath(), player.getQueue().get(3).getPath());
        assertTrue(player.getQueue().get(3).isOnline());
    }
    @Test public void removingEarlierEntryPreservesCurrentAndClearingKeepsOnlyCurrent() {
        player.removeQueueItem(0); assertEquals(0, player.getCurrentIndex());
        assertEquals(b.getPath(), player.getCurrentSong().getPath());
        player.clearUpcoming(); assertEquals(1, player.getQueue().size());
        assertEquals(b.getPath(), player.getQueue().get(0).getPath());
        player.removeQueueItem(0); assertNull(player.getCurrentSong()); assertTrue(player.getQueue().isEmpty());
    }
    @Test public void changedQueueSurvivesNewPlayerInstance() {
        player.moveQueueItem(0, 2); player.removeQueueItem(1);
        PlayerManager restored = new PlayerManager(); restored.init(context);
        assertEquals(2, restored.getQueue().size());
        assertEquals(b.getPath(), restored.getQueue().get(0).getPath());
        assertEquals(a.getPath(), restored.getQueue().get(1).getPath());
        assertFalse(restored.isPlaying()); assertEquals(4200, restored.getCurrentPosition());
    }
    @Test public void automaticAdvanceCountsTowardsCollectionCollages() {
        cl.arichard.descargador.data.ClickStats.getInstance(context).resetForAccount();
        player.playNext(); prepared();
        SearchHistoryManager history = SearchHistoryManager.getInstance(context);
        assertEquals(1, cl.arichard.descargador.data.ClickStats.getInstance(context).count(c));
        assertEquals(c.getPath(), history.getTopPlayed(Arrays.asList(a, b, c), 1).get(0).getPath());
    }
    @Test public void oldOnlineHistoryUsesTheSameIdentityAsPlaylistTracks() {
        Song old = new Song(0, "Canción", "Artista", "YouTube", 0,
                "https://i.ytimg.com/vi/abcdefghijk/hqdefault.jpg", "abcdefghijk", 0);
        assertEquals("youtube:abcdefghijk", old.getPath());
        assertEquals("https://i.ytimg.com/vi/abcdefghijk/hqdefault.jpg", old.getCoverPath());
    }
}
