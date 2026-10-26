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
import java.util.Collections;

import static org.junit.Assert.*;

/** Comprueba lo que hacen las opciones del menú de cada canción cuando el archivo está unido a su ID de YouTube. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class, qualifiers = "es")
public class SongMenuActionsTest {
    private static Song local(String title) {
        return new Song(1, title, "Artista", "Album", 1000, "/music/" + title.replace(' ', '_') + ".mp3", "/music", 0);
    }

    private PlaylistManager fresh() {
        PlaylistManager manager = PlaylistManager.getInstance(RuntimeEnvironment.getApplication());
        manager.resetForAccount();
        return manager;
    }

    /** Un archivo descargado y su canción de YouTube, ya unidos, dentro de la playlist «Rock» y de Favoritos. */
    private Song linkedSong(PlaylistManager manager) {
        SearchResult track = new SearchResult("abc", "Luis Jara - No sabía (Video Oficial) HD", "LUISJARA", 200, "t", "u");
        manager.addOnlineTrack("Rock", track);
        manager.addOnlineTrack("Favoritos", track);
        Song file = local("Luis Jara - No sabía Video Oficial HD");
        manager.linkLocalSongs(Arrays.asList(file));
        assertEquals("youtube:abc", manager.canonicalKey(file));
        return file;
    }

    @Test public void heartInTheSongListIsFilledForAFavoriteThatIsStoredByYoutubeId() {
        PlaylistManager manager = fresh();
        Song file = linkedSong(manager);
        // La lista de canciones decide el corazón con isFavorite(ruta del archivo).
        assertTrue(manager.isFavorite(file.getPath()));
        assertTrue(manager.isFavoriteSong(file));
    }

    @Test public void removeFromPlaylistTakesOutALinkedSongOnlyFromThatPlaylist() {
        PlaylistManager manager = fresh();
        Song file = linkedSong(manager);
        assertTrue(manager.removeSongFromPlaylist("Rock", file.getPath()));
        assertTrue(manager.getSongPaths("Rock").isEmpty());
        assertEquals(Collections.singletonList("youtube:abc"), manager.getSongPaths("Favoritos"));
        assertTrue(manager.hasLocalCopy("abc"));
    }

    @Test public void removeFromPlaylistWorksForAnOnlineSong() {
        PlaylistManager manager = fresh();
        manager.addOnlineTrack("Rock", new SearchResult("zzz", "Otra", "Canal", 100, "t", "u"));
        assertTrue(manager.removeSongFromPlaylist("Rock", "youtube:zzz"));
        assertTrue(manager.getSongPaths("Rock").isEmpty());
    }

    @Test public void deletingTheFileRemovesTheSongFromEveryPlaylistIncludingItsYoutubeEntry() {
        PlaylistManager manager = fresh();
        Song file = linkedSong(manager);
        manager.removeSongEverywhere(file.getPath());
        assertTrue(manager.getSongPaths("Rock").isEmpty());
        assertTrue(manager.getSongPaths("Favoritos").isEmpty());
    }

    @Test public void deletingAnOnlineSongRemovesItFromEveryPlaylist() {
        PlaylistManager manager = fresh();
        manager.addOnlineTrack("Rock", new SearchResult("zzz", "Otra", "Canal", 100, "t", "u"));
        manager.addOnlineTrack("Favoritos", new SearchResult("zzz", "Otra", "Canal", 100, "t", "u"));
        manager.removeSongEverywhere("youtube:zzz");
        assertTrue(manager.getSongPaths("Rock").isEmpty());
        assertTrue(manager.getSongPaths("Favoritos").isEmpty());
    }

    @Test public void addingALinkedSongTwiceToAPlaylistNeverRepeatsIt() {
        PlaylistManager manager = fresh();
        Song file = linkedSong(manager);
        assertFalse(manager.addSongToPlaylist("Rock", manager.canonicalKey(file)));
        assertEquals(1, manager.getSongPaths("Rock").size());
    }
}
