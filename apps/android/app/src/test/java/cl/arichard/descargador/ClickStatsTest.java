package cl.arichard.descargador;

import android.app.Application;

import cl.arichard.descargador.data.ClickStats;
import cl.arichard.descargador.model.Song;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class, qualifiers = "es")
public class ClickStatsTest {
    private static Song song(String title) {
        return new Song(1, title, "Artista", "Album", 1000, "/music/" + title + ".mp3", "/music", 0);
    }

    @Test public void titleKeyMatchesDesktopNormalization() {
        assertEquals("cancion nandu", ClickStats.normalize("Canción  Ñandú [HD]"));
        assertEquals(ClickStats.keyOf(song("Post Malone - Circles")), ClickStats.keyOf(song("post malone – CIRCLES (Official Video)")));
    }

    @Test public void clicksFromOtherDevicesAddUpAndMergeIsIdempotent() throws Exception {
        ClickStats stats = ClickStats.getInstance(RuntimeEnvironment.getApplication());
        stats.resetForAccount();
        Song a = song("Circles");
        stats.record(a);
        JSONObject remote = new JSONObject("{\"devices\":{\"pc\":{\"" + ClickStats.keyOf(a) + "\":3}},\"titles\":{}}");
        assertTrue(stats.merge(remote));
        assertFalse(stats.merge(remote));
        assertEquals(4, stats.count(a));
        assertEquals(4, stats.total());
        assertEquals(1, stats.playedSongs());
    }

    @Test public void staleRemoteDataNeverLowersACounter() throws Exception {
        ClickStats stats = ClickStats.getInstance(RuntimeEnvironment.getApplication());
        stats.resetForAccount();
        Song a = song("Stale");
        for (int i = 0; i < 5; i++) stats.record(a);
        JSONObject own = stats.exportJson();
        stats.record(a);
        stats.merge(own);
        assertEquals(6, stats.count(a));
    }
}
