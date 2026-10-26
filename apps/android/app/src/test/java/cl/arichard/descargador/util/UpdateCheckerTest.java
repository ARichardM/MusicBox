package cl.arichard.descargador.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import java.util.Arrays;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class, qualifiers = "es")
public class UpdateCheckerTest {
    private static final String BODY = "\n### Windows\n\n- **Importar carpeta.** Lee tus archivos.\n- **Versión portable.** Un ZIP.\n\n"
            + "### Android\n\n- El corazón reconoce los favoritos.\n- **Eliminar** funciona con archivos unidos.\n\n"
            + "### Novedades en Windows y Android\n\n- **Clics.** Contador compartido.\n";

    @Test public void androidSeesItsOwnAndSharedNotesButNotWindowsOnes() {
        assertEquals(Arrays.asList("El corazón reconoce los favoritos.", "Eliminar funciona con archivos unidos.",
                "Clics. Contador compartido."), UpdateChecker.notesFor(BODY));
    }

    @Test public void notesWithoutHeadingsAreAllKept() {
        assertEquals(Arrays.asList("Uno", "Dos"), UpdateChecker.notesFor("- Uno\n- Dos\n"));
    }

    @Test public void headlineKeepsOnlyTheFirstSentence() {
        assertEquals("Importar una carpeta \u2014 En Descargas lee tus archivos.",
                UpdateChecker.headline("Importar una carpeta. En Descargas lee tus archivos. Y mucho más."));
        assertEquals("Este es un titular largo que ya dice todo por sí solo.",
                UpdateChecker.headline("Este es un titular largo que ya dice todo por sí solo. Más texto."));
        assertEquals("Sin punto intermedio", UpdateChecker.headline("Sin punto intermedio"));
    }

    @Test public void longHeadlinesCutAtAClauseOrAtAWordNeverMidWord() {
        String clause = "Quitar de esta playlist y Eliminar del dispositivo funcionan también con archivos unidos a su canción de YouTube, y la eliminación saca la canción de todas tus playlists.";
        assertEquals("Quitar de esta playlist y Eliminar del dispositivo funcionan también con archivos unidos a su canción de YouTube.",
                UpdateChecker.headline(clause));
        String noCommas = "Una novedad con una frase muy larga que no termina nunca y sigue y sigue hasta pasar con mucho el límite permitido por la hoja";
        String shortened = UpdateChecker.headline(noCommas);
        assertTrue(shortened.length() <= 121);
        assertTrue(shortened.endsWith("…"));
        assertTrue(noCommas.startsWith(shortened.substring(0, shortened.length() - 1)));
        assertFalse(shortened.contains("  "));
    }

    @Test public void newerVersionsAreDetectedIgnoringTestSuffixes() {
        assertTrue(UpdateChecker.isNewer("2.3.1", "2.3.0"));
        assertTrue(UpdateChecker.isNewer("2.10.0", "2.9.9"));
        assertFalse(UpdateChecker.isNewer("2.3.1", "2.3.1-pruebas"));
        assertFalse(UpdateChecker.isNewer("2.3.0", "2.3.1"));
    }
}
