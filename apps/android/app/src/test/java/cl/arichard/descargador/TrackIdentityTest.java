package cl.arichard.descargador;

import android.app.Application;

import cl.arichard.descargador.data.PlaylistManager;
import cl.arichard.descargador.model.SearchResult;
import cl.arichard.descargador.model.Song;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class, qualifiers = "es")
public class TrackIdentityTest {
    private static Song local(String title) {
        return new Song(1, title, "Artista", "Album", 1000, "/music/" + title.replace(' ', '_') + ".mp3", "/music", 0);
    }

    private PlaylistManager fresh() {
        PlaylistManager manager = PlaylistManager.getInstance(RuntimeEnvironment.getApplication());
        manager.resetForAccount();
        return manager;
    }

    @Test public void fileAndYoutubeEntryBecomeOneSongPlayedFromTheFile() {
        PlaylistManager manager = fresh();
        manager.addOnlineTrack("Favoritos", new SearchResult("abc", "Luis Jara - No sabía (Video Oficial) HD", "LUISJARA", 200, "t", "u"));
        Song file = local("Luis Jara - No sabía Video Oficial HD");
        manager.addSongToPlaylist("Favoritos", file.getPath());

        manager.linkLocalSongs(Arrays.asList(file));

        assertEquals(Arrays.asList("youtube:abc"), manager.getSongPaths("Favoritos"));
        List<Song> shown = manager.resolveSongs("Favoritos", Arrays.asList(file));
        assertEquals(1, shown.size());
        assertEquals(file.getPath(), shown.get(0).getPath());
        assertTrue(manager.hasLocalCopy("abc"));
    }

    @Test public void hearthOffRemovesEveryCopyAndTogglingAgainAddsOne() {
        PlaylistManager manager = fresh();
        manager.addOnlineTrack("Favoritos", new SearchResult("xyz", "Supermode - Tell Me Why (OFFICIAL VIDEO)", "Ultra", 170, "t", "u"));
        Song file = local("Supermode - Tell Me Why OFFICIAL VIDEO");
        manager.linkLocalSongs(Arrays.asList(file));
        assertTrue(manager.isFavoriteSong(file));
        assertFalse(manager.toggleFavoriteSong(file));
        assertTrue(manager.getSongPaths("Favoritos").isEmpty());
        assertTrue(manager.toggleFavoriteSong(file));
        assertEquals(Arrays.asList("youtube:xyz"), manager.getSongPaths("Favoritos"));
    }

    @Test public void songsWithoutKnownIdKeepTheirPath() {
        PlaylistManager manager = fresh();
        Song file = local("Cancion propia");
        manager.linkLocalSongs(Arrays.asList(file));
        assertEquals(file.getPath(), manager.canonicalKey(file));
        assertTrue(manager.toggleFavoriteSong(file));
        assertEquals(Arrays.asList(file.getPath()), manager.getSongPaths("Favoritos"));
    }
}
