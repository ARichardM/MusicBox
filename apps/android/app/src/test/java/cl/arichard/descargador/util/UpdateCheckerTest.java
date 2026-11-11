package cl.arichard.descargador.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.app.Activity;
import android.view.View;
import android.widget.FrameLayout;
import androidx.coordinatorlayout.widget.CoordinatorLayout;
import androidx.core.view.ViewCompat;
import androidx.core.widget.NestedScrollView;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import java.lang.reflect.Constructor;
import java.util.Arrays;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.android.controller.ActivityController;
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

    @Test public void downwardDragIsHandedToTheSheetOnlyAtTheTopOfTheNotes() throws Exception {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            Class<?> type = Class.forName(UpdateChecker.class.getName() + "$CappedScrollView");
            Constructor<?> constructor = type.getDeclaredConstructor(Activity.class, int.class);
            constructor.setAccessible(true);
            NestedScrollView notes = (NestedScrollView) constructor.newInstance(activity, 200);
            View paragraphs = new View(activity);
            paragraphs.setMinimumHeight(600);
            notes.addView(paragraphs, new FrameLayout.LayoutParams(-1, 600));
            FrameLayout sheet = new FrameLayout(activity);
            sheet.addView(notes, new FrameLayout.LayoutParams(-1, -2));
            CoordinatorLayout coordinator = new CoordinatorLayout(activity);
            BottomSheetBehavior<FrameLayout> behavior = new BottomSheetBehavior<>();
            CoordinatorLayout.LayoutParams params = new CoordinatorLayout.LayoutParams(-1, 400);
            params.setBehavior(behavior);
            coordinator.addView(sheet, params);
            activity.setContentView(coordinator);
            behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
            coordinator.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY));
            coordinator.layout(0, 0, 400, 800);

            assertEquals(200, notes.getHeight());
            notes.scrollTo(0, 100);
            assertTrue(notes.canScrollVertically(-1));
            int top = sheet.getTop();
            behavior.onStartNestedScroll(coordinator, sheet, notes, notes, ViewCompat.SCROLL_AXIS_VERTICAL, ViewCompat.TYPE_TOUCH);
            behavior.onNestedPreScroll(coordinator, sheet, notes, 0, -40, new int[2], ViewCompat.TYPE_TOUCH);
            assertEquals(top, sheet.getTop());

            notes.scrollTo(0, 0);
            behavior.onNestedPreScroll(coordinator, sheet, notes, 0, -40, new int[2], ViewCompat.TYPE_TOUCH);
            assertTrue(sheet.getTop() > top);
        }
    }
}
