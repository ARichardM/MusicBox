package cl.arichard.descargador.util;

import org.junit.Test;
import static org.junit.Assert.*;

public class SharedMusicLinkTest {
    @Test public void classifiesSharedItemsFromSupportedPlatforms() {
        assertEquals(SharedMusicLink.Kind.PLAYLIST, SharedMusicLink.kind("https://open.spotify.com/playlist/37i9dQZF1DX4WYpdgoIcn6"));
        assertEquals(SharedMusicLink.Kind.ARTIST, SharedMusicLink.kind("https://open.spotify.com/artist/1Xyo4u8uXC1ZmMpatF05PJ"));
        assertEquals(SharedMusicLink.Kind.TRACK, SharedMusicLink.kind("https://open.spotify.com/track/0VjIjW4GlUZAMYd2vXMi3b"));
        assertEquals(SharedMusicLink.Kind.ARTIST, SharedMusicLink.kind("https://music.youtube.com/channel/UC0WP5P-ufpRfjbNrmOWwLBQ"));
        assertEquals(SharedMusicLink.Kind.TRACK, SharedMusicLink.kind("https://music.youtube.com/watch?v=4NRXx6U8ABQ"));
        assertEquals(SharedMusicLink.Kind.ARTIST, SharedMusicLink.kind("https://www.deezer.com/artist/4050205"));
        assertEquals(SharedMusicLink.Kind.ARTIST, SharedMusicLink.kind("https://music.apple.com/us/artist/the-weeknd/479756766"));
        assertEquals(SharedMusicLink.Kind.ARTIST, SharedMusicLink.kind("https://soundcloud.com/theweeknd"));
    }

    @Test public void prefersTheSharedArtistNameAndUsesReadableSlugs() {
        assertEquals("The Weeknd", SharedMusicLink.artistQuery("The Weeknd en Spotify https://open.spotify.com/artist/1Xyo4u8uXC1ZmMpatF05PJ", null));
        assertEquals("billie eilish", SharedMusicLink.artistQuery("https://music.youtube.com/@billie-eilish", null));
        assertEquals("Rosalía", SharedMusicLink.artistQuery("https://www.deezer.com/artist/123", "Rosalía"));
    }

    @Test public void routesUnsupportedPlatformTracksToSearchAndKeepsReadableTitles() {
        String spotifyTrack = "Blinding Lights by The Weeknd on Spotify https://open.spotify.com/track/0VjIjW4GlUZAMYd2vXMi3b";
        assertTrue(SharedMusicLink.requiresTrackSearch(spotifyTrack));
        assertEquals("Blinding Lights by The Weeknd", SharedMusicLink.trackQuery(spotifyTrack, null));
        assertEquals("Midnight City", SharedMusicLink.trackQuery("https://open.spotify.com/track/0VjIjW4GlUZAMYd2vXMi3b", "Midnight City"));
        assertTrue(SharedMusicLink.requiresTrackSearch("https://music.apple.com/us/song/blinding-lights/1504339657"));
        assertFalse(SharedMusicLink.requiresTrackSearch("https://music.youtube.com/watch?v=4NRXx6U8ABQ"));
        assertEquals(SharedMusicLink.Kind.UNKNOWN, SharedMusicLink.kind("https://notspotify.com/artist/1Xyo4u8uXC1ZmMpatF05PJ"));
    }
}
