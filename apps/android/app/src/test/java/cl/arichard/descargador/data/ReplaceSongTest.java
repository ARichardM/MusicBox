package cl.arichard.descargador.data;

import android.app.Application;
import android.content.Context;
import cl.arichard.descargador.model.SearchResult;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** "Ver alternativas": reemplazar una canción por otra versión en todas las playlists, en su misma posición. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class ReplaceSongTest {
    private static SearchResult video(String id) {
        return new SearchResult(id, "Versión " + id, "Canal", 200, "", "https://www.youtube.com/watch?v=" + id);
    }

    @Test public void replacesInPlaceAcrossPlaylistsAndKeepsOtherSongs() {
        Context context = RuntimeEnvironment.getApplication();
        PlaylistManager manager = PlaylistManager.getInstance(context);
        manager.createPlaylist("Reemplazo A");
        manager.createPlaylist("Reemplazo B");
        manager.addSongToPlaylist("Reemplazo A", "youtube:primera11111");
        manager.addSongToPlaylist("Reemplazo A", "youtube:original1111");
        manager.addSongToPlaylist("Reemplazo A", "youtube:ultima111111");
        manager.addSongToPlaylist("Reemplazo B", "youtube:original1111");
        assertEquals(Arrays.asList("Reemplazo A", "Reemplazo B"), manager.playlistsContaining("youtube:original1111"));

        int changed = manager.replaceEverywhere("youtube:original1111", video("nueva1111111"));

        assertEquals(2, changed);
        assertEquals(Arrays.asList("youtube:primera11111", "youtube:nueva1111111", "youtube:ultima111111"),
                manager.getSongPaths("Reemplazo A"));
        assertEquals(Collections.singletonList("youtube:nueva1111111"), manager.getSongPaths("Reemplazo B"));
        assertTrue(manager.playlistsContaining("youtube:original1111").isEmpty());
        assertNotNull(manager.getOnlineTrack("youtube:nueva1111111"));
    }

    @Test public void dropsTheOldEntryWhenThePlaylistAlreadyHasTheNewVersion() {
        Context context = RuntimeEnvironment.getApplication();
        PlaylistManager manager = PlaylistManager.getInstance(context);
        manager.createPlaylist("Reemplazo C");
        manager.addSongToPlaylist("Reemplazo C", "youtube:original2222");
        manager.addSongToPlaylist("Reemplazo C", "youtube:nueva2222222");

        assertEquals(1, manager.replaceEverywhere("youtube:original2222", video("nueva2222222")));
        assertEquals(Collections.singletonList("youtube:nueva2222222"), manager.getSongPaths("Reemplazo C"));
        assertEquals(0, manager.replaceEverywhere("youtube:noexiste1111", video("nueva2222222")));
    }
}
