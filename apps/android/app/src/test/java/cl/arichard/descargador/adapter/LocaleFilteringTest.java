package cl.arichard.descargador.adapter;

import android.app.Application;
import cl.arichard.descargador.model.SearchResult;
import cl.arichard.descargador.model.Song;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.util.Collections;
import java.util.Locale;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class LocaleFilteringTest {
    private Locale previous;

    @Before public void rememberLocale() { previous = Locale.getDefault(); }
    @After public void restoreLocale() { Locale.setDefault(previous); }

    @Test public void filtersKeepMatchingWhenTheDeviceLocaleChanges() {
        Locale.setDefault(Locale.US);
        Song song = new Song(1, "INDIE", "Artist", "Album", 65000, "/music/test.mp3", "INDIE", 0);
        SongAdapter songs = new SongAdapter(RuntimeEnvironment.getApplication(), Collections.singletonList(song), null);
        FolderAdapter folders = new FolderAdapter(RuntimeEnvironment.getApplication(),
                Collections.singletonMap("INDIE", Collections.singletonList(song)), null);
        PlaylistAdapter playlists = new PlaylistAdapter(RuntimeEnvironment.getApplication(),
                Collections.singletonList("INDIE"), null);
        songs.filter("indie");
        Locale.setDefault(Locale.forLanguageTag("tr-TR"));
        songs.filter("indie");
        folders.filter("indie");
        playlists.filter("indie");
        assertEquals(1, songs.getItemCount());
        assertEquals(1, folders.getItemCount());
        assertEquals(1, playlists.getItemCount());
    }

    @Test public void durationsKeepAsciiDigitsInArabicLocale() {
        Locale.setDefault(Locale.forLanguageTag("ar-EG"));
        Song song = new Song(1, "Song", "Artist", "Album", 65000, "/test.mp3", "", 0);
        SearchResult result = new SearchResult("id", "Song", "Artist", 65, "", "");
        assertEquals("01:05", song.getFormattedDuration());
        assertEquals("01:05", result.getFormattedDuration());
    }
}
