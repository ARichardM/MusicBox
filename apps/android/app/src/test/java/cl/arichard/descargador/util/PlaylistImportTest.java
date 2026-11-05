package cl.arichard.descargador.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.json.JSONArray;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = android.app.Application.class)
public class PlaylistImportTest {

    @Test public void recognizesSpotifyPlaylistLinksInSharedText() {
        PlaylistImport.Link link = PlaylistImport.find(
                "Mira esta lista https://open.spotify.com/intl-es/playlist/37i9dQZF1DXcBWIGoYBM5M?si=abc123 de verdad");
        assertNotNull(link);
        assertEquals(PlaylistImport.Platform.SPOTIFY, link.platform);
        assertEquals("37i9dQZF1DXcBWIGoYBM5M", link.id);
    }

    @Test public void recognizesYouTubeAndYtMusicPlaylistsButNotVideosInsideOne() {
        PlaylistImport.Link music = PlaylistImport.find("https://music.youtube.com/playlist?list=PLabcdefghij123&si=x");
        assertNotNull(music);
        assertEquals(PlaylistImport.Platform.YOUTUBE, music.platform);
        assertEquals("PLabcdefghij123", music.id);

        PlaylistImport.Link plain = PlaylistImport.find("https://www.youtube.com/playlist?feature=share&list=PLXYZ_987654");
        assertNotNull(plain);
        assertEquals("PLXYZ_987654", plain.id);

        // Un video suelto dentro de una lista no es la playlist: se deja a la descarga normal.
        assertNull(PlaylistImport.find("https://www.youtube.com/watch?v=dQw4w9WgXcQ&list=PLabcdefghij123"));
    }

    @Test public void recognizesDeezerPlaylistsAndIgnoresOtherLinks() {
        PlaylistImport.Link link = PlaylistImport.find("https://www.deezer.com/en/playlist/1116190041");
        assertNotNull(link);
        assertEquals(PlaylistImport.Platform.DEEZER, link.platform);
        assertEquals("1116190041", link.id);

        assertNull(PlaylistImport.find("https://open.spotify.com/track/7bxaFZ1O3cHkgLKMsdC3xR"));
        assertNull(PlaylistImport.find("solo texto sin enlaces"));
        assertNull(PlaylistImport.find(null));
    }

    @Test public void readsTracksFromSpotifyEmbedData() throws Exception {
        String json = "{\"props\":{\"pageProps\":{\"state\":{\"data\":{\"entity\":{"
                + "\"name\":\"Mi lista\",\"trackList\":["
                + "{\"title\":\"Loser\",\"subtitle\":\"Tame Impala\",\"duration\":223069,\"entityType\":\"track\"},"
                + "{\"title\":\"\",\"subtitle\":\"Sin título\",\"duration\":1000},"
                + "{\"title\":\"Episodio\",\"subtitle\":\"Podcast\",\"duration\":60000,\"entityType\":\"episode\"}"
                + "]}}}}}}";
        String html = "<html><script id=\"__NEXT_DATA__\" type=\"application/json\">" + json + "</script></html>";
        PlaylistImport.Playlist playlist = PlaylistImport.parseSpotifyEmbed(html);
        assertEquals("Mi lista", playlist.name);
        assertEquals(1, playlist.tracks.size());
        PlaylistImport.Track track = playlist.tracks.get(0);
        assertEquals("Loser", track.title);
        assertEquals("Tame Impala", track.artist);
        assertEquals(223, track.seconds);
        assertNull(track.direct);
    }

    @Test public void readsDeezerPagesIncludingMissingArtists() throws Exception {
        JSONArray data = new JSONArray(
                "[{\"title\":\"Hey Jude\",\"duration\":429,\"artist\":{\"name\":\"The Beatles\"}},"
                + "{\"title\":\"Sin artista\",\"duration\":120},"
                + "{\"title\":\"\",\"duration\":10,\"artist\":{\"name\":\"Nadie\"}}]");
        List<PlaylistImport.Track> tracks = new ArrayList<>();
        PlaylistImport.addDeezerTracks(data, tracks);
        assertEquals(2, tracks.size());
        assertEquals("The Beatles", tracks.get(0).artist);
        assertEquals(429, tracks.get(0).seconds);
        assertEquals("", tracks.get(1).artist);
    }

    @Test public void recognizesAppleMusicAndSoundCloudPlaylists() {
        PlaylistImport.Link apple = PlaylistImport.find("https://music.apple.com/us/playlist/todays-hits/pl.f4d106fed2bd41149aaacabb233eb5eb?l=es");
        assertNotNull(apple);
        assertEquals(PlaylistImport.Platform.APPLE_MUSIC, apple.platform);
        PlaylistImport.Link cloud = PlaylistImport.find("Mira https://soundcloud.com/forss/sets/soulhack?si=x");
        assertNotNull(cloud);
        assertEquals(PlaylistImport.Platform.SOUNDCLOUD, cloud.platform);
        assertEquals("https://soundcloud.com/forss/sets/soulhack", cloud.url);
        assertNull(PlaylistImport.find("https://soundcloud.com/forss/soulhack"));
        assertNull(PlaylistImport.find("https://music.apple.com/us/album/solar-eclipse/6818209777"));
    }

    @Test public void readsAppleMusicTracksFromServerData() throws Exception {
        String data = "{\"data\":[{\"items\":[{\"title\":\"Solar Eclipse\",\"trackNumber\":null,\"artistName\":\"Drake & Don Toliver\",\"duration\":218389},"
                + "{\"title\":\"Otra\",\"trackNumber\":2,\"artistName\":\"Alguien\",\"duration\":1000}]}]}";
        String html = "<meta property=\"og:title\" content=\"Today's Hits on Apple Music\"><script type=\"application/json\" id=\"serialized-server-data\">" + data + "</script>";
        PlaylistImport.Playlist playlist = PlaylistImport.parseAppleMusic(html);
        assertEquals("Today's Hits", playlist.name);
        assertEquals(2, playlist.tracks.size());
        assertEquals("Solar Eclipse", playlist.tracks.get(0).title);
        assertEquals("Drake & Don Toliver", playlist.tracks.get(0).artist);
        assertEquals(218, playlist.tracks.get(0).seconds);
    }

    @Test public void readsSoundCloudTracksAndSetName() throws Exception {
        String json = "{\"props\":{\"pageProps\":{\"playlist\":{\"urn\":\"soundcloud:playlists:18\",\"title\":\"Soulhack\",\"duration\":3247119},"
                + "\"tracks\":[{\"urn\":\"soundcloud:tracks:290\",\"title\":\"City Ports\",\"duration\":142233,\"publisher_metadata\":{\"artist\":\"Forss\"}},"
                + "{\"urn\":\"soundcloud:tracks:291\",\"title\":\"Sin metadatos\",\"duration\":274459}]}}}";
        String html = "<script id=\"__NEXT_DATA__\" type=\"application/json\">" + json + "</script>";
        PlaylistImport.Playlist playlist = PlaylistImport.parseSoundCloud(html);
        assertEquals("Soulhack", playlist.name);
        assertEquals(2, playlist.tracks.size());
        assertEquals("Forss", playlist.tracks.get(0).artist);
        assertEquals("", playlist.tracks.get(1).artist);
        assertEquals(142, playlist.tracks.get(0).seconds);
    }
}
