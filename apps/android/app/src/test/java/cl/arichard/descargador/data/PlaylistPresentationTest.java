package cl.arichard.descargador.data;

import android.app.Application;
import cl.arichard.descargador.model.SearchResult;
import cl.arichard.descargador.model.Song;
import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
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

    @Test public void localPlaybackUsesIdsAndKeepsDistinctJapaneseTracksInOrder() throws Exception {
        File file = new File(RuntimeEnvironment.getApplication().getFilesDir(), "local-japanese.mp3");
        assertTrue(file.createNewFile());
        Song local = new Song(1, "好敵手", "Yuki", "", 1000, file.getPath(), "", 0);
        manager.linkLocalFile(file.getPath(), "japanese001");
        manager.addOnlineTrack("Chill", new SearchResult("japanese001", "好敵手", "Yuki", 1, "", ""));
        manager.addOnlineTrack("Chill", new SearchResult("japanese002", "涙のスイマー", "Lavt", 1, "", ""));
        manager.addOnlineTrack("Chill", new SearchResult("japanese003", "オノガミ", "MEGATERA", 1, "", ""));
        List<Song> queue = manager.preferLocalCopies(manager.resolveSongs("Chill", Collections.emptyList()), List.of(local));
        assertEquals(List.of(file.getPath(), "youtube:japanese002", "youtube:japanese003"),
                queue.stream().map(Song::getPath).collect(java.util.stream.Collectors.toList()));
    }

    @Test public void linkingALegacyDownloadPreservesItsPlaylistDateAndSharedMembership() throws Exception {
        manager.importJson(new JSONObject("{\"playlists\":{\"Chill\":[\"/song.mp3\",\"youtube:japanese002\",\"youtube:japanese003\"]},"
                + "\"entryAddedAt\":{\"Chill\":{\"/song.mp3\":42,\"youtube:japanese002\":1000}},"
                + "\"onlineTracks\":{\"japanese001\":{\"title\":\"好敵手\"}}}"));
        Song local = new Song(1, "好敵手", "Yuki", "", 0, "/song.mp3", "", 999999);
        Song folderOnly = new Song(2, "Solo local", "", "", 0, "/folder-only.mp3", "", 999999);
        manager.linkLocalSongs(List.of(local));
        assertEquals(42, manager.exportJson().getJSONObject("entryAddedAt").getJSONObject("Chill").getLong("youtube:japanese001"));
        assertEquals(3L, (long) manager.membershipDates("Chill").get("youtube:japanese003"));
        List<Song> phone = manager.accountSongs(List.of(local, folderOnly), true);
        List<Song> tablet = manager.accountSongs(Collections.emptyList(), true);
        assertEquals(List.of("youtube:japanese001", "youtube:japanese002", "youtube:japanese003"),
                phone.stream().map(manager::songIdentity).collect(java.util.stream.Collectors.toList()));
        assertEquals(phone.stream().map(manager::songIdentity).collect(java.util.stream.Collectors.toList()),
                tablet.stream().map(manager::songIdentity).collect(java.util.stream.Collectors.toList()));
    }

    @Test public void aDownloadWithAKnownIdCanBeSharedWithoutExistingOnlineMetadata() throws Exception {
        Song download = new Song(1, "涙のスイマー", "Lavt", "", 1000, "/music/download.mp3", "", 0);
        manager.addSongToPlaylist("Chill", download.getPath());
        manager.linkLocalFile(download.getPath(), "japanese002");
        long originalDate = manager.exportJson().getJSONObject("entryAddedAt").getJSONObject("Chill").getLong(download.getPath());
        manager.linkLocalSongs(List.of(download));
        assertEquals(List.of("youtube:japanese002"), manager.getSongPaths("Chill"));
        assertEquals(originalDate, (long) manager.membershipDates("Chill").get("youtube:japanese002"));
        assertEquals("涙のスイマー", manager.resolveSongs("Chill", Collections.emptyList()).get(0).getTitle());
        assertTrue(manager.addSongToPlaylist("Driving", download));
        assertEquals(List.of("youtube:japanese002"), manager.getSongPaths("Driving"));
    }

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
