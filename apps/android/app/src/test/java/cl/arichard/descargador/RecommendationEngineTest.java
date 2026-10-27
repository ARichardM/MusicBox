package cl.arichard.descargador;

import android.app.Application;

import cl.arichard.descargador.data.ClickStats;
import cl.arichard.descargador.data.RecommendationEngine;
import cl.arichard.descargador.model.SearchResult;
import cl.arichard.descargador.model.Song;
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

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class, qualifiers = "es")
public class RecommendationEngineTest {
    private ClickStats stats;
    private final List<String> relatedCalls = new ArrayList<>();
    private final List<String> searchCalls = new ArrayList<>();
    private long clock;
    private boolean broken;

    private static SearchResult entry(String id) { return entry(id, "Canción " + id, "Canal", 200); }

    private static SearchResult entry(String id, String title, String channel, long seconds) {
        return new SearchResult(id, title, channel, seconds, null, null);
    }

    private final RecommendationEngine.Fetcher fetcher = new RecommendationEngine.Fetcher() {
        @Override public List<SearchResult> related(String videoId) throws Exception {
            relatedCalls.add(videoId);
            if (broken) throw new java.io.IOException("sin red");
            return Arrays.asList(entry(videoId + "-1"), entry(videoId + "-2"));
        }
        @Override public List<SearchResult> search(String query) throws Exception {
            searchCalls.add(query);
            if (broken) throw new java.io.IOException("sin red");
            return Arrays.asList(entry("s1"), entry("s2"));
        }
    };

    private RecommendationEngine engine() { return new RecommendationEngine(fetcher, () -> clock, null); }

    private void play(String id, int times, long now) {
        Song song = new Song(1, "Semilla " + id, "Canal", "YouTube", 1000, "youtube:" + id, "YouTube", 0);
        for (int i = 0; i < times; i++) stats.record(song, now);
    }

    @Before public void setUp() {
        stats = ClickStats.getInstance(RuntimeEnvironment.getApplication());
        stats.resetForAccount();
        clock = System.currentTimeMillis();
        broken = false;
    }

    @Test public void combineRaisesSongsThatAppearInSeveralLists() {
        List<RecommendationEngine.Weighted> lists = Arrays.asList(
                new RecommendationEngine.Weighted(1, Arrays.asList(entry("a"), entry("shared"))),
                new RecommendationEngine.Weighted(1, Arrays.asList(entry("b"), entry("shared"))),
                new RecommendationEngine.Weighted(1, Arrays.asList(entry("c"), entry("shared"))));
        RecommendationEngine.Result result = RecommendationEngine.combine(lists, new HashSet<>(), Collections.emptyList(), 20, "seeds");
        List<String> ids = new ArrayList<>();
        for (SearchResult song : result.songs) ids.add(song.getId());
        assertEquals(1, Collections.frequency(ids, "shared"));
        assertTrue(ids.indexOf("shared") < ids.indexOf("b"));
    }

    @Test public void combineFiltersKnownDuplicatesAndLongVideos() {
        List<RecommendationEngine.Weighted> lists = Collections.singletonList(new RecommendationEngine.Weighted(1, Arrays.asList(
                entry("seed"), entry("own"), entry("x", "Mismo Título", "Canal", 200),
                entry("y", "mismo título (Official)", "Canal", 200), entry("mix", "Mix", "Canal", 3600), entry("ok"))));
        Set<String> exclude = new HashSet<>(Collections.singletonList("seed"));
        RecommendationEngine.Result result = RecommendationEngine.combine(lists, exclude,
                Collections.singletonList("Canción own"), 20, "seeds");
        List<String> ids = new ArrayList<>();
        for (SearchResult song : result.songs) ids.add(song.getId());
        assertEquals(Arrays.asList("x", "ok"), ids);
    }

    @Test public void combineLimitsSongsAndStripsTopicFromArtists() {
        List<SearchResult> many = new ArrayList<>();
        for (int i = 0; i < 40; i++) many.add(entry("v" + i, "Tema " + i, i % 2 == 1 ? "Ana - Topic" : "Beto", 200));
        RecommendationEngine.Result result = RecommendationEngine.combine(
                Collections.singletonList(new RecommendationEngine.Weighted(1, many)), new HashSet<>(), Collections.emptyList(),
                RecommendationEngine.MAX_SONGS, "seeds");
        assertEquals(RecommendationEngine.MAX_SONGS, result.songs.size());
        assertTrue(result.artists.contains("Ana"));
        assertFalse(result.artists.contains("Ana - Topic"));
    }

    @Test public void artistsAreSplitAndCountedIndividually() {
        assertEquals(Arrays.asList("Samuel Kim", "Lorien"), RecommendationEngine.splitArtists("Samuel Kim, Lorien"));
        assertEquals(Arrays.asList("Bad Bunny", "Jhay Cortez"), RecommendationEngine.splitArtists("Bad Bunny & Jhay Cortez"));
        assertEquals(Arrays.asList("Duncan Dhu"), RecommendationEngine.splitArtists("Duncan Dhu"));
        assertEquals(Arrays.asList("Marcin Przybylowicz", "P.T. Adamczyk"), RecommendationEngine.splitArtists("Marcin Przybylowicz y P.T. Adamczyk"));
        RecommendationEngine.Result result = RecommendationEngine.combine(Collections.singletonList(new RecommendationEngine.Weighted(1,
                Arrays.asList(entry("a", "Tema A", "Ana, Beto", 200), entry("b", "Tema B", "Ana", 200)))),
                new HashSet<>(), Collections.emptyList(), 20, "seeds");
        assertEquals("Ana", result.artists.get(0));
        assertTrue(result.artists.contains("Beto"));
        assertFalse(result.artists.contains("Ana, Beto"));
    }

    @Test public void usesTheTopSeedsAndRanksTheMostListenedFirst() {
        play("fav", 5, clock);
        play("meh", 1, clock);
        RecommendationEngine.Result result = engine().compute(stats, id -> false, Collections.emptyList(), Collections.emptyList(), clock);
        assertEquals("seeds", result.mode);
        assertEquals(new HashSet<>(Arrays.asList("fav", "meh")), new HashSet<>(relatedCalls));
        assertEquals("fav-1", result.songs.get(0).getId());
        for (SearchResult song : result.songs) assertNotEquals("fav", song.getId());
    }

    @Test public void resultIsCachedUntilTheSeedsChangeAndThenExpires() {
        RecommendationEngine engine = engine();
        play("fav", 3, clock);
        engine.compute(stats, id -> false, Collections.emptyList(), Collections.emptyList(), clock);
        engine.compute(stats, id -> false, Collections.emptyList(), Collections.emptyList(), clock);
        assertEquals(Collections.singletonList("fav"), relatedCalls);
        clock += RecommendationEngine.CACHE_MS + 1;
        engine.compute(stats, id -> false, Collections.emptyList(), Collections.emptyList(), clock);
        assertEquals(Arrays.asList("fav", "fav"), relatedCalls);
    }

    @Test public void freshIgnoresAValidCacheButAFailureKeepsTheLastResult() {
        RecommendationEngine engine = engine();
        play("fav", 2, clock);
        RecommendationEngine.Result first = engine.compute(stats, id -> false, Collections.emptyList(), Collections.emptyList(), clock);
        engine.compute(stats, id -> false, Collections.emptyList(), Collections.emptyList(), clock);
        assertEquals(Collections.singletonList("fav"), relatedCalls);
        engine.compute(stats, id -> false, Collections.emptyList(), Collections.emptyList(), clock, true);
        assertEquals(Arrays.asList("fav", "fav"), relatedCalls);
        broken = true;
        RecommendationEngine.Result offline = engine.compute(stats, id -> false, Collections.emptyList(), Collections.emptyList(), clock, true);
        assertEquals(first.songs.size(), offline.songs.size());
    }

    @Test public void alreadyKnownSongsAreNotRecommended() {
        play("fav", 2, clock);
        RecommendationEngine.Result result = engine().compute(stats, id -> id.equals("fav-1"), Collections.emptyList(),
                Collections.emptyList(), clock);
        assertEquals(1, result.songs.size());
        assertEquals("fav-2", result.songs.get(0).getId());
    }

    @Test public void newUserFallsBackToRecentQueriesThenGeneric() {
        RecommendationEngine engine = engine();
        assertEquals("queries", engine.compute(stats, id -> false, Collections.emptyList(), Arrays.asList("rock", "jazz", "x"), clock).mode);
        assertEquals(Arrays.asList("rock", "jazz"), searchCalls);
        assertEquals("trending", engine.compute(stats, id -> false, Collections.emptyList(), Collections.emptyList(), clock).mode);
    }

    @Test public void offlineReturnsTheLastResultOrAnEmptyOne() {
        play("fav", 2, clock);
        broken = true;
        RecommendationEngine.Result empty = engine().compute(stats, id -> false, Collections.emptyList(), Collections.emptyList(), clock);
        assertEquals("offline", empty.mode);
        assertTrue(empty.isEmpty());

        broken = false;
        RecommendationEngine engine = engine();
        RecommendationEngine.Result good = engine.compute(stats, id -> false, Collections.emptyList(), Collections.emptyList(), clock);
        broken = true;
        clock += RecommendationEngine.CACHE_MS + 1;
        RecommendationEngine.Result stale = engine.compute(stats, id -> false, Collections.emptyList(), Collections.emptyList(), clock);
        assertEquals(good.songs.size(), stale.songs.size());
    }

    @Test public void cacheSurvivesARestart() {
        android.content.SharedPreferences prefs = RuntimeEnvironment.getApplication().getSharedPreferences("recs-test", 0);
        prefs.edit().clear().commit();
        play("fav", 2, clock);
        new RecommendationEngine(fetcher, () -> clock, prefs).compute(stats, id -> false, Collections.emptyList(), Collections.emptyList(), clock);
        int calls = relatedCalls.size();
        RecommendationEngine reopened = new RecommendationEngine(fetcher, () -> clock, prefs);
        assertNotNull(reopened.lastKnown());
        reopened.compute(stats, id -> false, Collections.emptyList(), Collections.emptyList(), clock);
        assertEquals(calls, relatedCalls.size());
    }
}
