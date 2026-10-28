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
    @Test public void listeningTimeAndPlaylistSourceMergeWithoutDuplicates() throws Exception {
        ClickStats stats = ClickStats.getInstance(RuntimeEnvironment.getApplication());
        stats.resetForAccount();
        Song track = song("Listening");
        stats.record(track);
        stats.recordListening(track, "Mi playlist", 65000, true);
        stats.recordListening(track, "", 5000, false);
        assertEquals(70000, stats.listeningRows(false).get(0).getLong("ms"));
        assertEquals(65000, stats.listeningRows(true).get(0).getLong("ms"));
        assertEquals(1, stats.listeningRows(true).get(0).getLong("plays"));
        JSONObject own = stats.exportJson();
        assertFalse(stats.merge(own));
        JSONObject remote = new JSONObject().put("listening", new JSONObject().put("pc", new JSONObject()
                .put(ClickStats.keyOf(track), new JSONObject().put("title", track.getTitle()).put("ms", 30000).put("plays", 1))));
        assertTrue(stats.merge(remote));
        assertFalse(stats.merge(remote));
        assertEquals(100000, stats.listeningRows(false).get(0).getLong("ms"));
        stats.resetForAccount();
        assertTrue(stats.listeningRows(false).isEmpty());
    }

    @Test public void topLibraryOnlyIncludesPlayedSongsAndReflectsNewCounts() {
        Application context = RuntimeEnvironment.getApplication();
        ClickStats stats = ClickStats.getInstance(context);
        stats.resetForAccount();
        Song first = song("Primera"), second = song("Segunda"), unplayed = song("Sin escuchar");
        stats.record(first);
        stats.record(second);
        stats.record(second);
        cl.arichard.descargador.data.SearchHistoryManager history = cl.arichard.descargador.data.SearchHistoryManager.getInstance(context);
        java.util.List<Song> library = java.util.Arrays.asList(first, second, unplayed);
        java.util.List<Song> top = history.getTopPlayed(library, 5);
        assertEquals(2, top.size());
        assertEquals(second.getPath(), top.get(0).getPath());
        assertEquals(2, stats.count(top.get(0)));
        stats.record(second);
        assertEquals(3, stats.count(history.getTopPlayed(library, 5).get(0)));
    }

    private static Song song(String title) {
        return new Song(1, title, "Artista", "Album", 1000, "/music/" + title + ".mp3", "/music", 0);
    }

    @Test public void topLibraryGroupsThreeCopiesWithoutChangingFilesOrCounters() throws Exception {
        Application context = RuntimeEnvironment.getApplication();
        ClickStats stats = ClickStats.getInstance(context); stats.resetForAccount();
        Song raw = new Song(1, "Anly - Karanokokoro [K8kjo96CJmg]", null, "", 273072,
                "/Music/MiMusica/Anly - Karanokokoro [K8kjo96CJmg].mp3", "MiMusica", 3);
        Song tagged = new Song(2, "Anly - Karanokokoro", "Anly Official YouTube Channel", "", 273072,
                "/Music/Chillv2/013 - Anly - Karanokokoro [K8kjo96CJmg].mp3", "Chillv2", 2);
        Song privateCopy = new Song(3, tagged.getTitle(), tagged.getArtist(), "", 273072,
                "/Android/data/verification/files/Music/Anly - Karanokokoro [K8kjo96CJmg].mp3", "Music", 1);
        stats.record(tagged);
        java.util.List<Song> library = java.util.Arrays.asList(raw, tagged, privateCopy);
        String before = stats.exportJson().toString();
        java.util.List<Song> top = cl.arichard.descargador.data.SearchHistoryManager.getInstance(context).getTopPlayed(library, 5);
        assertEquals(1, top.size());
        assertSame(tagged, top.get(0));
        assertEquals(3, library.size());
        assertEquals(before, stats.exportJson().toString());
        assertEquals(1, stats.count(tagged));
    }

    @Test public void topLibraryDoesNotMergeDifferentArtistsOrExplicitVideoIds() {
        Application context = RuntimeEnvironment.getApplication();
        ClickStats stats = ClickStats.getInstance(context); stats.resetForAccount();
        Song a = new Song(1, "Tema", "Artista", "", 1000, "/a [abcdefghijk].mp3", "", 0);
        Song b = new Song(2, "Tema", "Artista", "", 1000, "/b [12345678901].mp3", "", 0);
        Song c = new Song(3, "Tema", "Otro artista", "", 1000, "/c.mp3", "", 0);
        stats.record(a); stats.record(b); stats.record(c);
        assertEquals(3, cl.arichard.descargador.data.SearchHistoryManager.getInstance(context)
                .getTopPlayed(java.util.Arrays.asList(a, b, c), 5).size());
    }

    @Test public void topLibraryGroupsLinkedCopiesAndAppliesLimitAfterGrouping() {
        Application context = RuntimeEnvironment.getApplication();
        ClickStats stats = ClickStats.getInstance(context); stats.resetForAccount();
        Song a = song("Linked"), b = new Song(2, "Linked", "Artista", "", 1000, "/copies/linked.mp3", "", 0);
        cl.arichard.descargador.data.PlaylistManager manager = cl.arichard.descargador.data.PlaylistManager.getInstance(context);
        manager.linkLocalFile(a.getPath(), "linked-test"); manager.linkLocalFile(b.getPath(), "linked-test");
        Song other = song("Other"); stats.record(a); stats.record(a); stats.record(other);
        cl.arichard.descargador.data.SearchHistoryManager history = cl.arichard.descargador.data.SearchHistoryManager.getInstance(context);
        assertEquals(2, history.getTopPlayed(java.util.Arrays.asList(a, b, other), 2).size());
        assertTrue(history.getTopPlayed(java.util.Arrays.asList(a, b), 0).isEmpty());
    }

    @Test public void topLibraryKeepsUnknownFilesAndDifferentVersionsSeparate() {
        Application context = RuntimeEnvironment.getApplication();
        ClickStats stats = ClickStats.getInstance(context); stats.resetForAccount();
        Song studio = new Song(1, "Tema", "Artista", "", 10000, "/studio.mp3", "", 0);
        Song live = new Song(2, "Tema [Live]", "Artista", "", 10000, "/live.mp3", "", 0);
        Song unknownA = new Song(3, "Tema", null, "", 10000, "/unknown-a.mp3", "", 0);
        Song unknownB = new Song(4, "Tema", null, "", 10000, "/unknown-b.mp3", "", 0);
        stats.record(studio);
        assertEquals(4, cl.arichard.descargador.data.SearchHistoryManager.getInstance(context)
                .getTopPlayed(java.util.Arrays.asList(studio, live, unknownA, unknownB), 5).size());
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
    private static Song online(String id, String title) {
        return new Song(1, title, "Canal", "YouTube", 1000, "youtube:" + id, "YouTube", 0);
    }

    private static final long DAY = 86400000L;

    @Test public void dailyCountsMergeWithMaxAndAreIdempotent() throws Exception {
        ClickStats stats = ClickStats.getInstance(RuntimeEnvironment.getApplication());
        stats.resetForAccount();
        long now = System.currentTimeMillis();
        stats.record(online("a", "A"), now);
        JSONObject own = stats.exportJson();
        assertEquals(1, own.getJSONObject("daily").length());
        assertFalse(stats.merge(own));
        JSONObject stale = new JSONObject(own.toString());
        assertFalse(stats.merge(stale));
    }

    @Test public void recentPlaysOutweighOldOnes() {
        ClickStats stats = ClickStats.getInstance(RuntimeEnvironment.getApplication());
        stats.resetForAccount();
        long now = System.currentTimeMillis();
        for (int i = 0; i < 10; i++) stats.record(online("old", "Old"), now - 60 * DAY);
        for (int i = 0; i < 3; i++) stats.record(online("new", "New"), now - DAY);
        assertTrue(stats.score("yt:new", now) > stats.score("yt:old", now));
        assertEquals("yt:new", stats.seeds(4, now).get(0).key);
    }

    @Test public void daysOutsideTheWindowAreDroppedAndNotReintroduced() throws Exception {
        ClickStats stats = ClickStats.getInstance(RuntimeEnvironment.getApplication());
        stats.resetForAccount();
        long now = System.currentTimeMillis();
        stats.record(online("a", "A"), now - 200 * DAY);
        stats.record(online("a", "A"), now);
        assertEquals(1, stats.exportJson().getJSONObject("daily").getJSONObject(
                stats.exportJson().getJSONObject("daily").keys().next()).getJSONObject("yt:a").length());
        assertEquals(2, stats.count("yt:a"));
    }

    @Test public void oldClientsWithoutDailyStillMergeAndUnknownFieldsSurvive() throws Exception {
        ClickStats stats = ClickStats.getInstance(RuntimeEnvironment.getApplication());
        stats.resetForAccount();
        assertTrue(stats.merge(new JSONObject("{\"v\":1,\"devices\":{\"pc\":{\"yt:a\":3}},\"titles\":{},\"future\":{\"x\":1}}")));
        assertEquals(3, stats.count("yt:a"));
        assertEquals(1, stats.exportJson().getJSONObject("future").getInt("x"));
    }

    @Test public void seedsOnlyIncludeYoutubeSongs() {
        ClickStats stats = ClickStats.getInstance(RuntimeEnvironment.getApplication());
        stats.resetForAccount();
        long now = System.currentTimeMillis();
        stats.record(song("Solo local"), now);
        stats.record(online("b", "B"), now);
        assertEquals(1, stats.seeds(4, now).size());
        assertEquals("b", stats.seeds(4, now).get(0).videoId());
    }
}
