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
    @Test public void prefersYtDlpThenTheLinkParameterAndIgnoresTheApproximateSize() throws Exception {
        String link = "https://r1.googlevideo.com/videoplayback?id=a&clen=5000&mime=audio";
        assertEquals(7000, PlayerManager.knownLength(new JSONObject().put("filesize", 7000), link));
        // filesize_approx es una estimación: si el enlace trae el tamaño exacto manda este.
        assertEquals(5000, PlayerManager.knownLength(new JSONObject().put("filesize_approx", 6000), link));
        assertEquals(5000, PlayerManager.knownLength(new JSONObject(), link));
        // Sin tamaño exacto no se usa la estimación: devuelve 0 para que el reproductor lo compruebe con el servidor.
        assertEquals(0, PlayerManager.knownLength(new JSONObject().put("filesize_approx", 6000), "https://x/y?id=a"));
    }

    @Test public void aTinyFileForALongSongIsADecoyAndIsRejected() {
        assertTrue(PlayerManager.isImpossiblySmall(99_810, 185));       // el caso real: 97 KB para 3 minutos
        assertFalse(PlayerManager.isImpossiblySmall(2_998_000, 185));   // audio normal (~2,9 MB)
        assertFalse(PlayerManager.isImpossiblySmall(99_810, 20));       // un clip corto sí puede ser pequeño
        assertFalse(PlayerManager.isImpossiblySmall(0, 185));           // tamaño desconocido: no se descarta
    }

    @Test public void unknownSizeReturnsZeroSoTheCallerCanProbe() throws Exception {
        assertEquals(0, PlayerManager.knownLength(new JSONObject(), "https://r1.googlevideo.com/videoplayback?id=a"));
        assertEquals(0, PlayerManager.knownLength(new JSONObject(), "https://x/y?clen=abc"));
    }
}
