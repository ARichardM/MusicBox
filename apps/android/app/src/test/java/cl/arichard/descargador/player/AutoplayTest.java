package cl.arichard.descargador.player;

import cl.arichard.descargador.model.SearchResult;
import cl.arichard.descargador.model.Song;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = android.app.Application.class)
public class AutoplayTest {
    private static SearchResult result(String id, long seconds) {
        return new SearchResult(id, "T" + id, "A", seconds, "", "https://www.youtube.com/watch?v=" + id);
    }

    @Test public void skipsSongsAlreadyQueuedAndLongMixes() {
        List<Song> picked = Autoplay.pick(Arrays.asList(result("a", 200), result("b", 200), result("mix", 3600), result("c", 200)),
                Collections.singletonList("youtube:b"));
        assertEquals(2, picked.size());
        assertEquals("youtube:a", picked.get(0).getPath());
        assertEquals("youtube:c", picked.get(1).getPath());
    }

    @Test public void repeatedResultsAppearOnce() {
        assertEquals(1, Autoplay.pick(Arrays.asList(result("a", 100), result("a", 100)), new ArrayList<String>()).size());
    }

    @Test public void neverAddsMoreThanOneBatch() {
        List<SearchResult> many = new ArrayList<>();
        for (int i = 0; i < 40; i++) many.add(result("v" + i, 180));
        assertEquals(Autoplay.BATCH, Autoplay.pick(many, new ArrayList<String>()).size());
    }

    @Test public void nothingRelatedMeansNothingAdded() {
        assertTrue(Autoplay.pick(null, new ArrayList<String>()).isEmpty());
    }
}
