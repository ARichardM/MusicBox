package cl.arichard.descargador;

import android.app.Application;

import cl.arichard.descargador.data.RecommendationEngine;
import cl.arichard.descargador.model.SearchResult;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.*;

/** Sugerencias para una playlist: semillas de la propia playlist y nada que ya tenga. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class, qualifiers = "es")
public class PlaylistRecommendationsTest {
    private final List<String> relatedCalls = new ArrayList<>();
    private long clock;
    private boolean broken;
    private int perSeed;

    private static SearchResult entry(String id) {
        return new SearchResult(id, "Canción " + id, "Canal", 200, null, null);
    }

    private final RecommendationEngine.Fetcher fetcher = new RecommendationEngine.Fetcher() {
        @Override public List<SearchResult> related(String videoId) throws Exception {
            relatedCalls.add(videoId);
            if (broken) throw new java.io.IOException("sin red");
            if (perSeed > 0) {
                List<SearchResult> many = new ArrayList<>();
                for (int i = 0; i < perSeed; i++) many.add(entry(videoId + "-" + i));
                return many;
            }
            if (videoId.equals("a")) return Arrays.asList(entry("own"), entry("x"), entry("y"));
            if (videoId.equals("b")) return Arrays.asList(entry("x"), entry("z"));
            return Collections.emptyList();
        }
        @Override public List<SearchResult> search(String query) { return Collections.emptyList(); }
    };

    private RecommendationEngine engine() { return new RecommendationEngine(fetcher, () -> clock, null); }

    private static List<String> ids(RecommendationEngine.Result result) {
        List<String> ids = new ArrayList<>();
        for (SearchResult song : result.songs) ids.add(song.getId());
        return ids;
    }

    private static List<String> seedIds(List<RecommendationEngine.PlaylistSeed> seeds) {
        List<String> ids = new ArrayList<>();
        for (RecommendationEngine.PlaylistSeed seed : seeds) ids.add(seed.videoId);
        return ids;
    }

    private static List<RecommendationEngine.PlaylistSeed> seeds(String... ids) {
        List<RecommendationEngine.PlaylistSeed> seeds = new ArrayList<>();
        for (String id : ids) seeds.add(new RecommendationEngine.PlaylistSeed(id, 1.0));
        return seeds;
    }

    @Before public void setUp() {
        relatedCalls.clear();
        clock = 1_000_000L;
        broken = false;
        perSeed = 0;
    }

    @Test public void seedsPreferMostPlayedThenLatestAdded() {
        java.util.Map<String, Integer> plays = new java.util.HashMap<>();
        plays.put("b", 5); plays.put("a", 2);
        List<RecommendationEngine.PlaylistSeed> seeds = RecommendationEngine.playlistSeeds(
                Arrays.asList("a", "b", "c", "d", "e", "f"), id -> plays.containsKey(id) ? plays.get(id) : 0, 4);
        assertEquals(Arrays.asList("b", "a", "f", "e"), seedIds(seeds));
    }

    @Test public void seedWeightsAreNormalisedAndUnplayedSongsStillCount() {
        List<RecommendationEngine.PlaylistSeed> seeds = RecommendationEngine.playlistSeeds(
                Arrays.asList("a", "b"), id -> id.equals("a") ? 4 : 0, 4);
        assertEquals(1.0, seeds.get(0).weight, 0.0001);
        assertTrue(seeds.get(1).weight > 0 && seeds.get(1).weight < 1.0);
    }

    @Test public void emptyPlaylistAndDuplicatesGiveCleanSeeds() {
        assertTrue(RecommendationEngine.playlistSeeds(Collections.emptyList(), id -> 0, 4).isEmpty());
        assertEquals(Arrays.asList("b", "a"),
                seedIds(RecommendationEngine.playlistSeeds(Arrays.asList("a", "a", "b"), id -> 0, 4)));
    }

    @Test public void excludesWhatThePlaylistAlreadyHasAndTheSeeds() {
        RecommendationEngine.Result result = engine().forPlaylist(seeds("a", "b"),
                new HashSet<>(Collections.singletonList("own")), Collections.singletonList("Canción own"), false);
        List<String> ids = ids(result);
        assertEquals("x", ids.get(0));
        assertFalse(ids.contains("own"));
        assertFalse(ids.contains("a"));
        assertEquals("playlist", result.mode);
    }

    @Test public void limitsWhatItShows() {
        perSeed = 40;
        assertEquals(RecommendationEngine.PLAYLIST_SONGS,
                engine().forPlaylist(seeds("a"), new HashSet<>(), Collections.emptyList(), false).songs.size());
    }

    @Test public void cachesPerSeedSetUntilFreshOrExpired() {
        RecommendationEngine engine = engine();
        engine.forPlaylist(seeds("a"), new HashSet<>(), Collections.emptyList(), false);
        engine.forPlaylist(seeds("a"), new HashSet<>(), Collections.emptyList(), false);
        assertEquals(Collections.singletonList("a"), relatedCalls);
        engine.forPlaylist(seeds("a"), new HashSet<>(), Collections.emptyList(), true);
        assertEquals(Arrays.asList("a", "a"), relatedCalls);
        engine.forPlaylist(seeds("b"), new HashSet<>(), Collections.emptyList(), false);
        assertEquals(Arrays.asList("a", "a", "b"), relatedCalls);
        clock += 7L * 3600 * 1000;
        engine.forPlaylist(seeds("b"), new HashSet<>(), Collections.emptyList(), false);
        assertEquals(4, relatedCalls.size());
    }

    @Test public void cachedResultIsFilteredAgainWithTheCurrentPlaylist() {
        RecommendationEngine engine = engine();
        engine.forPlaylist(seeds("a"), new HashSet<>(), Collections.emptyList(), false);
        RecommendationEngine.Result again = engine.forPlaylist(seeds("a"),
                new HashSet<>(Collections.singletonList("x")), Collections.emptyList(), false);
        assertFalse(ids(again).contains("x"));
        assertEquals(1, relatedCalls.size());
    }

    @Test public void doesNotEvictForYouCacheAndKeepsManyPlaylists() {
        RecommendationEngine engine = engine();
        for (char letter = 'a'; letter <= 'j'; letter++)
            engine.forPlaylist(seeds(String.valueOf(letter)), new HashSet<>(), Collections.emptyList(), false);
        assertEquals(10, relatedCalls.size());
        engine.forPlaylist(seeds("a"), new HashSet<>(), Collections.emptyList(), false);
        assertEquals(10, relatedCalls.size());
    }

    @Test public void offlineReturnsLastResultOrEmpty() {
        android.content.SharedPreferences prefs = RuntimeEnvironment.getApplication()
                .getSharedPreferences("playlist_recs_test", android.content.Context.MODE_PRIVATE);
        prefs.edit().clear().commit();
        new RecommendationEngine(fetcher, () -> clock, prefs)
                .forPlaylist(seeds("a"), new HashSet<>(), Collections.emptyList(), false);
        broken = true;
        RecommendationEngine offline = new RecommendationEngine(fetcher, () -> clock, prefs);
        assertFalse(offline.forPlaylist(seeds("a"), new HashSet<>(), Collections.emptyList(), true).songs.isEmpty());
        RecommendationEngine.Result none = offline.forPlaylist(seeds("zz"), new HashSet<>(), Collections.emptyList(), true);
        assertTrue(none.songs.isEmpty());
        assertEquals("offline", none.mode);
    }

    @Test public void noSeedsMeansEmptyWithoutNetwork() {
        RecommendationEngine.Result result = engine().forPlaylist(Collections.emptyList(), new HashSet<>(), Collections.emptyList(), false);
        assertTrue(result.songs.isEmpty());
        assertEquals("offline", result.mode);
        assertTrue(relatedCalls.isEmpty());
    }
}
