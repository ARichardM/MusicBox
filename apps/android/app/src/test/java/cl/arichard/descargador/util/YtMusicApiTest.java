package cl.arichard.descargador.util;

import android.app.Application;

import cl.arichard.descargador.model.SearchResult;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;

import static org.junit.Assert.*;

/** El cliente de YouTube Music entiende respuestas reales (recortadas) y separa canciones de videos. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class, qualifiers = "es")
public class YtMusicApiTest {
    private final Map<String, JSONObject> responses = new HashMap<>();
    private final List<String> calls = new ArrayList<>();

    private static JSONObject fixture(String name) throws Exception {
        try (InputStream in = YtMusicApiTest.class.getClassLoader().getResourceAsStream("ytmusic/" + name + ".json");
             Scanner scanner = new Scanner(in, "UTF-8").useDelimiter("\\A")) {
            return new JSONObject(scanner.next());
        }
    }

    @Before public void setUp() {
        YtMusicApi.transport = (endpoint, body) -> {
            String key = body.has("browseId") ? body.optString("browseId") : body.has("continuation") ? "continuation" : endpoint;
            calls.add(endpoint + ":" + key);
            JSONObject response = responses.containsKey(key) ? responses.get(key) : responses.get(endpoint);
            if (response == null) throw new java.io.IOException("sin respuesta para " + key);
            return response;
        };
    }

    private static JSONObject row(String id, String title) throws Exception {
        return new JSONObject().put("musicResponsiveListItemRenderer", new JSONObject()
                .put("playlistItemData", new JSONObject().put("videoId", id))
                .put("flexColumns", new JSONArray().put(new JSONObject().put("musicResponsiveListItemFlexColumnRenderer",
                        new JSONObject().put("text", new JSONObject().put("runs", new JSONArray().put(new JSONObject().put("text", title))))))));
    }

    private static JSONObject more(String token) throws Exception {
        return new JSONObject().put("continuationItemRenderer", new JSONObject().put("continuationEndpoint",
                new JSONObject().put("continuationCommand", new JSONObject().put("token", token))));
    }

    @Test public void radioReturnsOnlySongsWithArtistAndAlbum() throws Exception {
        responses.put("next", fixture("next"));
        List<SearchResult> songs = YtMusicApi.related("NgEJ9gMxP90", 25);
        assertFalse(songs.isEmpty());
        for (SearchResult song : songs) {
            assertNotEquals("NgEJ9gMxP90", song.getId());
            assertFalse(song.getChannel().isEmpty());
            assertTrue(song.getDurationSeconds() > 0);
        }
        assertTrue(calls.get(0).startsWith("next:"));
    }

    @Test public void radioDropsVideosMixedIntoTheList() throws Exception {
        JSONObject data = new JSONObject().put("contents", new JSONArray()
                .put(panel("song1", "MUSIC_VIDEO_TYPE_ATV")).put(panel("clip", "MUSIC_VIDEO_TYPE_UGC")).put(panel("song2", "MUSIC_VIDEO_TYPE_ATV")));
        responses.put("next", data);
        List<SearchResult> songs = YtMusicApi.related("seed", 25);
        assertEquals(2, songs.size());
        assertEquals("song1", songs.get(0).getId());
        assertEquals("song2", songs.get(1).getId());
    }

    private static JSONObject panel(String id, String type) throws Exception {
        return new JSONObject().put("playlistPanelVideoRenderer", new JSONObject().put("videoId", id)
                .put("title", new JSONObject().put("runs", new JSONArray().put(new JSONObject().put("text", id))))
                .put("longBylineText", new JSONObject().put("runs", new JSONArray().put(new JSONObject().put("text", "Artista"))))
                .put("lengthText", new JSONObject().put("runs", new JSONArray().put(new JSONObject().put("text", "3:00"))))
                .put("navigationEndpoint", new JSONObject().put("watchEndpoint", new JSONObject().put("watchEndpointMusicSupportedConfigs",
                        new JSONObject().put("watchEndpointMusicConfig", new JSONObject().put("musicVideoType", type))))));
    }

    @Test public void artistSearchGivesAPhotoAndAnId() throws Exception {
        responses.put("search", fixture("search_artists"));
        List<YtMusicApi.Artist> artists = YtMusicApi.searchArtists("Los Prisioneros");
        assertFalse(artists.isEmpty());
        assertTrue(artists.get(0).browseId.startsWith("UC"));
        assertTrue(artists.get(0).photo.startsWith("https://"));
        assertEquals("Los Prisioneros", YtMusicApi.findArtist("los prisioneros").name);
    }

    @Test public void artistPageHasPhotoTopSongsAlbumsAndSingles() throws Exception {
        responses.put("UCTDZPojhkqe4pfN9X-9ZnBw", fixture("artist"));
        YtMusicApi.ArtistPage page = YtMusicApi.artistPage("UCTDZPojhkqe4pfN9X-9ZnBw");
        assertEquals("Los Prisioneros", page.name);
        assertTrue(page.photo.startsWith("https://"));
        assertFalse(page.subscribers.isEmpty());
        assertEquals("Tren Al Sur", page.topSongs.get(0).getTitle());
        assertTrue(page.songsPlaylist.startsWith("VL"));
        assertFalse(page.albums.isEmpty());
        assertFalse(page.singles.isEmpty());
        assertTrue(page.albums.get(0).browseId.startsWith("MPRE"));
        assertTrue(page.albums.get(0).year.matches("(19|20)\\d{2}"));
        for (YtMusicApi.Album single : page.singles) assertEquals("single", single.kind);
        for (YtMusicApi.Album album : page.albums) assertEquals("album", album.kind);
    }

    @Test public void playlistFollowsContinuationsWithoutRepeating() throws Exception {
        responses.put("VLP", new JSONObject().put("contents", new JSONObject().put("musicPlaylistShelfRenderer",
                new JSONObject().put("contents", new JSONArray().put(row("a", "A")).put(row("b", "B")).put(more("t1"))))));
        responses.put("continuation", new JSONObject().put("onResponseReceivedActions", new JSONArray().put(new JSONObject()
                .put("appendContinuationItemsAction", new JSONObject().put("continuationItems", new JSONArray().put(row("b", "B")).put(row("c", "C")))))));
        List<SearchResult> songs = YtMusicApi.playlist("VLP", "Artista");
        assertEquals(3, songs.size());
        assertEquals("c", songs.get(2).getId());
        assertEquals(2, calls.size());
    }

    @Test public void playlistFixtureRowsBecomeSongs() throws Exception {
        responses.put("VLX", fixture("playlist"));
        List<SearchResult> songs = YtMusicApi.playlist("VLX", "Los Prisioneros");
        assertEquals("Tren Al Sur", songs.get(0).getTitle());
        assertEquals(337, songs.get(0).getDurationSeconds());
    }

    @Test public void albumPageHasTitleYearCoverAndTracks() throws Exception {
        responses.put("MPREb_x", fixture("album"));
        YtMusicApi.AlbumPage album = YtMusicApi.albumPage("MPREb_x");
        assertTrue(album.title.startsWith("La Voz de los"));
        assertEquals("2014", album.year);
        assertEquals("Los Prisioneros", album.artist);
        assertTrue(album.thumbnail.startsWith("https://"));
        assertFalse(album.tracks.isEmpty());
        assertTrue(album.tracks.get(0).getDurationSeconds() > 0);
        assertFalse(album.tracks.get(0).getChannel().isEmpty());
    }

    @Test public void photosAreRequestedSquare() {
        assertEquals("https://yt3.ggpht.com/abc=w544-h544-l90-rj", YtMusicApi.square("https://yt3.ggpht.com/abc=w1200-h500-l90-rj", 544));
        assertEquals("", YtMusicApi.square(null, 544));
    }
}
