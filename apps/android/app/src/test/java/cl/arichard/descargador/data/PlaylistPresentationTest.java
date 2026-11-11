package cl.arichard.descargador.data;

import android.app.Application;
import cl.arichard.descargador.model.SearchResult;
import java.util.Arrays;
import java.util.Collections;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class PlaylistPresentationTest {
    private PlaylistManager manager;
    @Before public void setup() {
        manager = PlaylistManager.getInstance(RuntimeEnvironment.getApplication());
        manager.resetForAccount();
        manager.createPlaylist("Driving"); manager.createPlaylist("Chill"); manager.createPlaylist("Gym");
    }
    private SearchResult track() { return new SearchResult("abcdefghijk", "Muestra", "Artista", 120, "", ""); }

    @Test public void emptyFavoritesStayAvailableForAddingButHideUntilTheyContainSongs() {
        assertTrue(manager.getPlaylistNames().contains("Favoritos"));
        assertFalse(manager.getVisiblePlaylistNames(Collections.emptyList()).contains("Favoritos"));
        manager.setPinned("Favoritos", true);
        assertTrue(manager.getPinnedPlaylistNames(Collections.emptyList()).isEmpty());
        manager.addOnlineTrack("Favoritos", track());
        assertTrue(manager.getVisiblePlaylistNames(Collections.emptyList()).contains("Favoritos"));
        assertEquals(Collections.singletonList("Favoritos"), manager.getPinnedPlaylistNames(Collections.emptyList()));
        manager.removeSongFromPlaylist("Favoritos", "youtube:abcdefghijk");
        assertFalse(manager.getVisiblePlaylistNames(Collections.emptyList()).contains("Favoritos"));
    }

    @Test public void orderAndPinsSurviveReloadRenameAndDeleteWithoutChangingMembership() throws Exception {
        manager.addOnlineTrack("Driving", track());
        manager.setPinned("Driving", true); manager.setPinned("Chill", true);
        manager.setPlaylistOrder(Arrays.asList("Gym", "Chill", "Driving"));
        assertEquals(Arrays.asList("Favoritos", "Gym", "Chill", "Driving"), manager.getPlaylistNames());
        assertEquals(Arrays.asList("Chill", "Driving"), manager.getPinnedPlaylistNames(Collections.emptyList()));
        JSONObject saved = manager.exportJson();
        manager.importJson(new JSONObject(saved.toString()));
        assertEquals(saved.getJSONArray("playlistOrder").toString(), manager.exportJson().getJSONArray("playlistOrder").toString());
        assertEquals(Collections.singletonList("youtube:abcdefghijk"), manager.getSongPaths("Driving"));
        assertTrue(manager.updatePlaylistDetails("Driving", "Viajes", "Descripción"));
        assertTrue(manager.isPinned("Viajes")); assertFalse(manager.isPinned("Driving"));
        assertEquals("Viajes", manager.getPlaylistNames().get(3));
        manager.deletePlaylist("Chill");
        assertEquals(Collections.singletonList("Viajes"), manager.getPinnedPlaylistNames(Collections.emptyList()));
        manager.resetForAccount();
        assertTrue(manager.getPinnedPlaylistNames(Collections.emptyList()).isEmpty());
        assertEquals(Collections.singletonList("Favoritos"), manager.getPlaylistNames());
    }

    @Test public void reorderingAFilteredSubsetKeepsOtherPlaylistsAndLegacyImports() throws Exception {
        manager.setPlaylistOrder(Arrays.asList("Gym", "Driving"));
        assertEquals(Arrays.asList("Favoritos", "Gym", "Chill", "Driving"), manager.getPlaylistNames());
        manager.setPlaylistOrder(Arrays.asList("Gym", "Gym", "No existe"));
        assertEquals(4, manager.getPlaylistNames().size());
        manager.importJson(new JSONObject("{\"playlists\":{\"Favoritos\":[],\"Antigua\":[]}}"));
        assertTrue(manager.getPlaylistNames().contains("Antigua"));
        assertTrue(manager.getPinnedPlaylistNames(Collections.emptyList()).isEmpty());
    }
}
