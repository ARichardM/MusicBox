package cl.arichard.descargador.util;

import static org.junit.Assert.assertEquals;

import cl.arichard.descargador.model.SearchResult;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

/** "Ver alternativas" solo muestra otras versiones de la misma cancion, no otras canciones del artista. */
public class SongAlternativesTest {
    private static SearchResult video(String id, String title) {
        return new SearchResult(id, title, "Canal", 200, "", "https://www.youtube.com/watch?v=" + id);
    }

    @Test public void keepsVersionsOfTheSameSongAndDropsOtherSongs() {
        List<SearchResult> ranked = SongAlternativesSheet.rankSimilar(Arrays.asList(
                video("a", "El Baile De Los Que Sobran"),
                video("b", "Estrechez De Corazón (En vivo)"),
                video("c", "Amiga Mía"),
                video("d", "Estrechez De Corazón")), "Estrechez De Corazón");
        assertEquals(2, ranked.size());
        assertEquals("b", ranked.get(0).getId());
        assertEquals("d", ranked.get(1).getId());
    }

    @Test public void returnsEverythingWhenNothingLooksLikeTheSong() {
        List<SearchResult> all = Arrays.asList(video("a", "Otra cosa"), video("b", "Algo distinto"));
        assertEquals(all, SongAlternativesSheet.rankSimilar(all, "Estrechez De Corazón"));
    }
}
