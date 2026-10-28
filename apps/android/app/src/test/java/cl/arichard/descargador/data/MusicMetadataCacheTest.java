package cl.arichard.descargador.data;

import android.app.Application;
import cl.arichard.descargador.model.Song;
import java.io.File;
import java.util.Locale;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class MusicMetadataCacheTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private JSONObject entry(File file) throws Exception {
        return new Song(1, "Tema", "Artista", "Disco", 12345, file.getAbsolutePath(), "Music", 0)
                .toJson().put("fileSize", file.length()).put("fileModified", file.lastModified())
                .put("locale", Locale.getDefault().toLanguageTag());
    }
    @Test public void unchangedFileReusesMetadata() throws Exception {
        File file = temporary.newFile("sample.mp3");
        Song song = MusicScanner.cachedSong(file, entry(file).toString());
        assertNotNull(song);
        assertEquals(12345, song.getDuration());
        assertEquals("Tema", song.getTitle());
    }
    @Test public void changedDeletedOrCorruptEntriesAreNotReused() throws Exception {
        File file = temporary.newFile("sample.mp3");
        JSONObject entry = entry(file);
        assertNull(MusicScanner.cachedSong(file, entry.toString().replace("sample.mp3", "other.mp3")));
        assertNull(MusicScanner.cachedSong(file, "broken"));
        assertNull(MusicScanner.cachedSong(file, entry.put("fileSize", 10).toString()));
        entry = entry(file);
        assertNull(MusicScanner.cachedSong(file, entry.put("fileModified", 1).toString()));
        entry = entry(file);
        assertNull(MusicScanner.cachedSong(file, entry.put("locale", "invalid").toString()));
        String serialized = entry(file).toString();
        assertTrue(file.delete());
        assertNull(MusicScanner.cachedSong(file, serialized));
    }
}
