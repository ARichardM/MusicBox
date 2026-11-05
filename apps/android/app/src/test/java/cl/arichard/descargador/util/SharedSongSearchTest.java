package cl.arichard.descargador.util;

import android.app.Application;
import android.content.Intent;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class SharedSongSearchTest {
    @Test public void screenshotShareUsesTitleAndArtistWithoutUrl() {
        assertEquals("Freakin' Out (Oops All Drop) Dexter and The Moonrocks",
                SharedSongSearch.query("Freakin' Out (Oops All Drop) by Dexter and The Moonrocks\nhttps://www.shazam.com/track/873158167/freakin-out-oops-all-drop?referrer=share", null));
    }
    @Test public void keepsUnicodeAndRemovesShareBoilerplate() {
        assertEquals("心海 Eve", SharedSongSearch.query("I used Shazam to discover 心海 by Eve on Shazam. https://shazam.com/track/123/shinkai", null));
    }
    @Test public void linkOnlyUsesSlugOrSubjectAndMissingTitleStaysEmpty() {
        assertEquals("freakin out", SharedSongSearch.query("https://shazam.com/track/123/freakin-out", null));
        assertEquals("Tema Artista", SharedSongSearch.query("https://shazam.com/track/123", "Tema by Artista"));
        assertEquals("", SharedSongSearch.query("https://shazam.com/track/123", null));
    }
    @Test public void otherSharesKeepTheirExistingDestination() {
        for (String text : new String[]{"https://youtube.com/watch?v=abc", "https://open.spotify.com/playlist/abc", "Tema por Artista", "https://shazam.com.evil.test/track/123/title"})
            assertNull(SharedSongSearch.query(text, null));
    }
    @Test public void routingOpensMainSearchAndSupportsAnAlreadyOpenActivity() {
        Intent shared = new Intent(Intent.ACTION_SEND).setType("text/plain");
        Intent routed = SharedSongSearch.searchIntent(RuntimeEnvironment.getApplication(), shared,
                "Tema by Artista https://shazam.com/track/123/tema");
        assertNotNull(routed);
        assertEquals("cl.arichard.descargador.MainActivity", routed.getComponent().getClassName());
        assertEquals("Tema Artista", routed.getStringExtra(Intent.EXTRA_TEXT));
        assertTrue(routed.getBooleanExtra(SharedSongSearch.EXTRA_SEARCH, false));
        assertTrue((routed.getFlags() & Intent.FLAG_ACTIVITY_SINGLE_TOP) != 0);
    }
}
