package cl.arichard.descargador;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.appcompat.widget.AppCompatAutoCompleteTextView;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.Configurator;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;
import cl.arichard.descargador.data.ClickStats;
import cl.arichard.descargador.data.MusicScanner;
import cl.arichard.descargador.data.PlaylistManager;
import cl.arichard.descargador.data.SearchHistoryManager;
import cl.arichard.descargador.model.SearchResult;
import cl.arichard.descargador.model.Song;
import cl.arichard.descargador.player.PlayerManager;
import cl.arichard.descargador.util.PlaylistPicker;
import cl.arichard.descargador.util.Tr;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import static org.junit.Assume.*;

@RunWith(AndroidJUnit4.class)
public class HomePolishDeviceTest {
    private Context context;
    private UiDevice device;
    private String pkg;
    private List<Song> samples;
    private final PlayerManager player = PlayerManager.getInstance();
    private static final String SHORT = "Verificación breve";
    private static final String LONG = "Verificación de una playlist con un nombre bastante largo para ocupar varias líneas";

    private <T> T main(Supplier<T> action) {
        AtomicReference<T> result = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> result.set(action.get()));
        return result.get();
    }

    private void action(Runnable action) { main(() -> { action.run(); return true; }); }

    private void waitFor(Supplier<Boolean> condition) throws Exception {
        long deadline = System.currentTimeMillis() + 20000;
        while (!condition.get() && System.currentTimeMillis() < deadline) Thread.sleep(100);
        assertTrue("Expected polished interface state", condition.get());
    }

    private UiObject2 view(String id) {
        UiObject2 result = device.wait(Until.findObject(By.res(pkg, id)), 10000);
        assertNotNull("Visible: " + id, result);
        return result;
    }

    private Activity launch(Class<? extends Activity> type) throws Exception {
        Activity activity = InstrumentationRegistry.getInstrumentation().startActivitySync(
                new Intent(context, type).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        waitFor(() -> main(activity::hasWindowFocus));
        if (activity instanceof MainActivity) waitFor(() -> main(() -> {
            android.view.ViewGroup root = activity.findViewById(android.R.id.content);
            for (int index = 0; index < root.getChildCount(); index++)
                if (root.getChildAt(index) instanceof cl.arichard.descargador.util.AnimatedSplashView) return false;
            return true;
        }));
        return activity;
    }

    @Before public void setup() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        pkg = context.getPackageName();
        assumeTrue("Independent verification only", pkg.equals("cl.arichard.descargador.verification"));
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        Configurator.getInstance().setWaitForIdleTimeout(0).setWaitForSelectorTimeout(2000);
        device.wakeUp();
        device.pressHome();
        samples = new ArrayList<>();
        for (Song song : MusicScanner.scanAllMusic(context))
            if (song.getPath().contains("/" + pkg + "/files/Music/Verification/")) samples.add(song);
        assumeTrue("Synthetic verification samples exist", samples.size() >= 3);
        context.getSharedPreferences("musicbox_prefs", 0).edit().putBoolean("dark_mode", true).commit();
    }

    @After public void stopAudio() { action(player::stopAndRelease); }

    @Test public void topCollectionsOpenCorrectSongsAndKeepEqualCardHeights() throws Exception {
        PlaylistManager manager = PlaylistManager.getInstance(context);
        for (String name : new String[]{SHORT, LONG}) {
            manager.createPlaylist(name);
            manager.addSongToPlaylist(name, samples.get(0).getPath());
        }
        for (int count = 0; count < 20; count++) ClickStats.getInstance(context).record(samples.get(0));
        MainActivity activity = (MainActivity) launch(PreviewMainActivity.class);
        waitFor(() -> main(() -> collection(activity, SHORT) != null && collection(activity, LONG) != null));
        waitFor(() -> main(() -> collection(activity, SHORT).getHeight() > 0));
        assertEquals(main(() -> collection(activity, SHORT).getHeight()), main(() -> collection(activity, LONG).getHeight()));
        action(() -> collection(activity, LONG).performClick());
        waitFor(() -> main(() -> ((TextView) activity.findViewById(R.id.txtLibraryTitle)).getText().toString().equals(LONG)));
        assertTrue(main(() -> ((RecyclerView) activity.findViewById(R.id.recyclerLibrary)).getAdapter().getItemCount() > 0));
        action(() -> activity.findViewById(R.id.navTabSearch).performClick());
        waitFor(() -> main(() -> collection(activity, samples.get(0).getFolder()) != null));
        action(() -> collection(activity, samples.get(0).getFolder()).performClick());
        waitFor(() -> main(() -> ((TextView) activity.findViewById(R.id.txtLibraryTitle)).getText().toString().equals(samples.get(0).getFolder())));
        assertTrue(main(() -> ((RecyclerView) activity.findViewById(R.id.recyclerLibrary)).getAdapter().getItemCount() >= 3));
    }

    private View collection(MainActivity activity, String name) {
        LinearLayout cards = activity.findViewById(R.id.layoutTopCollections);
        for (int index = 0; index < cards.getChildCount(); index++) {
            View card = cards.getChildAt(index);
            if (name.equals(((TextView) card.findViewById(R.id.txtFolderName)).getText().toString())) return card;
        }
        return null;
    }

    @Test public void recentQueriesStayBelowTheSearchFieldWithKeyboardVisible() throws Exception {
        SearchHistoryManager history = SearchHistoryManager.getInstance(context);
        for (int index = 0; index < 12; index++) history.addSearchQuery("Verificación reciente " + index);
        MainActivity activity = (MainActivity) launch(PreviewMainActivity.class);
        action(() -> activity.findViewById(R.id.navTabSearch).performClick());
        waitFor(() -> main(() -> activity.findViewById(R.id.tabSearch).getTranslationX() == 0f));
        view("inputSearch").click();
        AppCompatAutoCompleteTextView input = activity.findViewById(R.id.inputSearch);
        waitFor(() -> main(input::isPopupShowing));
        waitFor(() -> main(() -> {
            Rect visible = new Rect();
            input.getWindowVisibleDisplayFrame(visible);
            return visible.bottom < device.getDisplayHeight() * .85;
        }));
        Thread.sleep(400);
        Rect field = view("searchField").getVisibleBounds();
        List<UiObject2> queries = device.findObjects(By.text(java.util.regex.Pattern.compile("Verificación reciente .*")));
        assertFalse("Recent query rows are visible", queries.isEmpty());
        Rect available = main(() -> { Rect frame = new Rect(); input.getWindowVisibleDisplayFrame(frame); return frame; });
        for (UiObject2 query : queries) {
            Rect row = query.getVisibleBounds();
            assertTrue("Dropdown stays below the search bar", row.top >= field.bottom);
            assertTrue("Dropdown stays above the keyboard", row.bottom <= available.bottom);
        }
        assertTrue(main(input::getDropDownHeight) <= Math.round(216 * context.getResources().getDisplayMetrics().density));
        action(() -> { input.dismissDropDown(); input.clearFocus(); });
        device.pressBack();
    }

    @Test public void artistKeepsMiniPlayerAndPlaybackControlsAcrossScreens() throws Exception {
        action(() -> player.playSong(samples.get(0), new ArrayList<>(samples.subList(0, 3))));
        waitFor(() -> main(() -> !player.isLoading()));
        ArtistActivity activity = (ArtistActivity) launch(ArtistActivity.class);
        view("miniPlayerLayout");
        assertEquals(samples.get(0).getTitle(), view("txtMiniTitle").getText());
        waitFor(() -> main(player::isPlaying));
        view("btnMiniPlayPause").click();
        waitFor(() -> main(() -> !player.isPlaying()));
        view("btnMiniNext").click();
        waitFor(() -> main(() -> samples.get(1).getPath().equals(player.getCurrentSong().getPath())));
        waitFor(() -> samples.get(1).getTitle().equals(view("txtMiniTitle").getText()));
        view("btnMiniQueue").click();
        assertNotNull(device.wait(Until.findObject(By.textContains(Tr.t("Cola · "))), 10000));
        device.pressBack();
        view("txtMiniTitle").click();
        view("seekBar");
        device.pressBack();
        view("miniPlayerLayout");
        assertFalse(activity.isFinishing());
    }

    @Test public void playlistPickerShowsCoversAndKeepsSelectionAndCreateSeparate() throws Exception {
        PlaylistManager manager = PlaylistManager.getInstance(context);
        String name = "Verificación portada sin reproducciones";
        manager.createPlaylist(name);
        manager.addSongToPlaylist(name, samples.get(2).getPath());
        MainActivity activity = (MainActivity) launch(PreviewMainActivity.class);
        AtomicReference<String> selected = new AtomicReference<>("unchanged");
        androidx.appcompat.app.AlertDialog dialog = main(() -> PlaylistPicker.show(activity, Tr.t("Agregar a playlist"), true, selected::set));
        int position = manager.getPlaylistNames().indexOf(name);
        action(() -> dialog.getListView().setSelection(position));
        waitFor(() -> main(() -> {
            View row = dialog.getListView().getChildAt(position - dialog.getListView().getFirstVisiblePosition());
            return row != null && ((android.view.ViewGroup) row.findViewById(R.id.playlistChoiceCover)).getChildCount() > 0
                    && ((android.view.ViewGroup) row.findViewById(R.id.playlistChoiceCover)).getChildAt(0) instanceof ImageView;
        }));
        action(() -> dialog.getListView().performItemClick(null, position, position));
        assertEquals(name, selected.get());
        assertFalse(main(dialog::isShowing));
        androidx.appcompat.app.AlertDialog create = main(() -> PlaylistPicker.show(activity, Tr.t("Agregar a playlist"), true, selected::set));
        int last = main(() -> create.getListView().getAdapter().getCount() - 1);
        action(() -> create.getListView().performItemClick(null, last, last));
        assertNull(selected.get());
    }

    @Test public void importerFiltersUpdateWhenChoosingAndReportCanReturnToPendingSongs() throws Exception {
        ImporterActivity activity = (ImporterActivity) launch(ImporterActivity.class);
        action(() -> ((EditText) activity.findViewById(R.id.inputList)).setText("Elegida de verificación\nRevisión de verificación\nSin resultado de verificación\nEsperando de verificación"));
        action(() -> invoke(activity, "buildRows", new Class<?>[]{}));
        Field rowsField = ImporterActivity.class.getDeclaredField("rows");
        rowsField.setAccessible(true);
        List<?> rows = (List<?>) rowsField.get(activity);
        String[] states = {"auto", "review", "none", "idle"};
        for (int index = 0; index < 4; index++) {
            Object row = rows.get(index);
            field(row, "state", states[index]);
            if (index < 2) field(row, "found", Collections.singletonList(new SearchResult("verification-" + index,
                    "Alternativa de verificación", "Artista de prueba", 100, "", "https://example.invalid/verification-" + index)));
            if (index == 0) field(row, "chosen", 0);
        }
        action(() -> invoke(activity, "showStep", new Class<?>[]{int.class, boolean.class}, 2, false));
        action(() -> activity.findViewById(R.id.matchFilters).findViewById(R.id.filterReview).performClick());
        assertEquals(1, main(() -> ((RecyclerView) activity.findViewById(R.id.recyclerMatches)).getAdapter().getItemCount()).intValue());
        view("txtCandidateTitle").click();
        waitFor(() -> main(() -> ((RecyclerView) activity.findViewById(R.id.recyclerMatches)).getAdapter().getItemCount() == 0));
        view("txtMatchesEmpty");
        action(() -> activity.findViewById(R.id.matchFilters).findViewById(R.id.filterReady).performClick());
        assertEquals(2, main(() -> ((RecyclerView) activity.findViewById(R.id.recyclerMatches)).getAdapter().getItemCount()).intValue());
        action(() -> invoke(activity, "showStep", new Class<?>[]{int.class, boolean.class}, 3, false));
        action(() -> activity.findViewById(R.id.reportFilters).findViewById(R.id.filterPending).performClick());
        assertFalse(view("txtReport").getText().contains("Elegida de verificación"));
        view("btnReportReview").click();
        waitFor(() -> main(() -> activity.findViewById(R.id.matchFilters).findViewById(R.id.filterPending).isSelected()));
        assertEquals(2, main(() -> ((RecyclerView) activity.findViewById(R.id.recyclerMatches)).getAdapter().getItemCount()).intValue());
    }

    private void field(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private Object invoke(Object target, String name, Class<?>[] types, Object... arguments) {
        try {
            Method method = target.getClass().getDeclaredMethod(name, types);
            method.setAccessible(true);
            return method.invoke(target, arguments);
        } catch (Exception exception) { throw new AssertionError(exception); }
    }
}
