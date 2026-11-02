package cl.arichard.descargador.player;

import android.app.Application;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** El tamaño del audio se toma de la respuesta de yt-dlp o del enlace, sin una petición extra de comprobación. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class KnownLengthTest {
    @Test public void prefersYtDlpThenApproximateThenTheLinkParameter() throws Exception {
        String link = "https://r1.googlevideo.com/videoplayback?id=a&clen=5000&mime=audio";
        assertEquals(7000, PlayerManager.knownLength(new JSONObject().put("filesize", 7000), link));
        assertEquals(6000, PlayerManager.knownLength(new JSONObject().put("filesize_approx", 6000), link));
        assertEquals(5000, PlayerManager.knownLength(new JSONObject(), link));
    }

    @Test public void unknownSizeReturnsZeroSoTheCallerCanProbe() throws Exception {
        assertEquals(0, PlayerManager.knownLength(new JSONObject(), "https://r1.googlevideo.com/videoplayback?id=a"));
        assertEquals(0, PlayerManager.knownLength(new JSONObject(), "https://x/y?clen=abc"));
    }
}
