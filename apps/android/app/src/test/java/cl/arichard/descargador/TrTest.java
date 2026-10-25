package cl.arichard.descargador;

import static org.junit.Assert.assertEquals;

import android.app.Application;
import cl.arichard.descargador.util.Tr;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class, qualifiers = "en")
public class TrTest {
    @Test public void englishTranslatesKnownTextsAndKeepsUnknownOnes() {
        assertEquals("Add to queue", Tr.t("Agregar a la cola"));
        assertEquals("Texto nuevo sin traducir", Tr.t("Texto nuevo sin traducir"));
        assertEquals("3 songs added to Rock", Tr.f("%d canciones agregadas a %s", 3, "Rock"));
        assertEquals("Favorites", Tr.name("Favoritos"));
        assertEquals("Mi lista", Tr.name("Mi lista"));
    }

    @Test @Config(qualifiers = "es") public void spanishReturnsTheSourceText() {
        assertEquals("Agregar a la cola", Tr.t("Agregar a la cola"));
        assertEquals("Favoritos", Tr.name("Favoritos"));
    }
}
