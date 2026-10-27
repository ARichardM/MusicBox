package cl.arichard.descargador;

import android.app.Application;
import android.widget.EditText;
import android.widget.TextView;
import androidx.recyclerview.widget.RecyclerView;
import cl.arichard.descargador.model.SearchResult;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class, qualifiers = "es")
public class ImporterFiltersTest {
    private ActivityController<ImporterActivity> controller;
    private ImporterActivity activity;

    private Object call(String name, Class<?>[] types, Object... arguments) throws Exception {
        Method method = ImporterActivity.class.getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method.invoke(activity, arguments);
    }

    @Before public void setup() throws Exception {
        controller = Robolectric.buildActivity(ImporterActivity.class).setup();
        activity = controller.get();
        ((EditText) activity.findViewById(R.id.inputList)).setText("Elegida de prueba\nRevisar de prueba\nSin resultado de prueba\nEsperando de prueba");
        assertEquals(true, call("buildRows", new Class<?>[]{}));
        Field rowsField = ImporterActivity.class.getDeclaredField("rows");
        rowsField.setAccessible(true);
        List<?> rows = (List<?>) rowsField.get(activity);
        assertEquals(4, rows.size());
        String[] states = {"auto", "review", "none", "idle"};
        for (int index = 0; index < rows.size(); index++) {
            Object row = rows.get(index);
            set(row, "state", states[index]);
            if (index < 2) set(row, "found", Collections.singletonList(
                    new SearchResult("verification", "Resultado de prueba", "Artista", 100, "", "https://example.invalid/verification")));
            if (index == 0) set(row, "chosen", 0);
        }
        call("showStep", new Class<?>[]{int.class, boolean.class}, 2, false);
        activity.findViewById(R.id.matchFilters).findViewById(R.id.filterAll).performClick();
    }

    private void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    @After public void cleanup() { controller.pause().stop().destroy(); }

    @Test public void filterChipsSeparateReadyReviewAndPendingWithoutLosingRows() {
        int[] ids = {R.id.filterAll, R.id.filterReady, R.id.filterReview, R.id.filterPending, R.id.filterAll};
        int[] counts = {4, 1, 1, 2, 4};
        for (int index = 0; index < ids.length; index++) {
            activity.findViewById(R.id.matchFilters).findViewById(ids[index]).performClick();
            assertEquals(counts[index], ((RecyclerView) activity.findViewById(R.id.recyclerMatches)).getAdapter().getItemCount());
        }
    }

    @Test public void filteredReportDoesNotFilterTheExportOrTheFinalResult() throws Exception {
        call("showStep", new Class<?>[]{int.class, boolean.class}, 3, false);
        activity.findViewById(R.id.reportFilters).findViewById(R.id.filterPending).performClick();
        String shown = ((TextView) activity.findViewById(R.id.txtReport)).getText().toString();
        assertTrue(shown.contains("Sin resultado de prueba"));
        assertFalse(shown.contains("Elegida de prueba"));
        assertFalse(shown.contains("Revisar de prueba"));
        String exported = (String) call("reportText", new Class<?>[]{});
        assertTrue(exported.contains("Elegida de prueba"));
        assertTrue(exported.contains("Revisar de prueba"));
        assertEquals(1, ((java.util.Map<?, ?>) call("chosenByUrl", new Class<?>[]{})).size());
    }

    @Test public void reportReviewButtonOpensOnlyTheRowsNeedingAChoice() throws Exception {
        call("showStep", new Class<?>[]{int.class, boolean.class}, 3, false);
        activity.findViewById(R.id.btnReportReview).performClick();
        assertEquals(android.view.View.VISIBLE, activity.findViewById(R.id.pageMatches).getVisibility());
        assertEquals(1, ((RecyclerView) activity.findViewById(R.id.recyclerMatches)).getAdapter().getItemCount());
        assertTrue(activity.findViewById(R.id.matchFilters).findViewById(R.id.filterReview).isSelected());
    }
}
