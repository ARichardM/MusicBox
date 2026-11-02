package cl.arichard.descargador.util;

import android.app.Application;
import cl.arichard.descargador.model.Song;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** La ficha de «Información de la canción» muestra lo de la app y su origen. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class SongInfoTest {
    private static String valueOf(List<SongInfo.Row> rows, String label) {
        for (SongInfo.Row row : rows) if (!row.header && row.label.equals(Tr.t(label))) return row.value;
        return null;
    }

    @Test public void onlineSongShowsItsYouTubeOriginAndNoFile() {
        Song song = new Song(0, "Tren Al Sur", "Los Prisioneros", "YouTube", 336000, "youtube:abcdefghijk", "YouTube", 0);
        List<SongInfo.Row> rows = SongInfo.rows(RuntimeEnvironment.getApplication(), song);
        assertEquals("Tren Al Sur", valueOf(rows, "Título"));
        assertEquals("abcdefghijk", valueOf(rows, "ID del video"));
        assertEquals("https://www.youtube.com/watch?v=abcdefghijk", valueOf(rows, "Enlace"));
        assertNull("Una canción en línea no tiene archivo", valueOf(rows, "Tamaño"));
        assertEquals("https://www.youtube.com/watch?v=abcdefghijk", SongInfo.link(RuntimeEnvironment.getApplication(), song));
    }

    @Test public void localSongWithoutYouTubeLinkHasNoOriginSection() {
        Song song = new Song(1, "Tema", "Artista", "Álbum", 120000, "/no/existe/tema.mp3", "musica", 0);
        List<SongInfo.Row> rows = SongInfo.rows(RuntimeEnvironment.getApplication(), song);
        assertEquals("Álbum", valueOf(rows, "Álbum"));
        assertNull(valueOf(rows, "ID del video"));
        assertEquals("", SongInfo.link(RuntimeEnvironment.getApplication(), song));
    }

    @Test public void formatHelpers() {
        assertEquals("MP3", SongInfo.extension("a.b.mp3"));
        assertEquals("", SongInfo.extension("sin_extension"));
        assertEquals("192 kbps", SongInfo.kbps("192000"));
        assertEquals("", SongInfo.kbps("x"));
    }
}
