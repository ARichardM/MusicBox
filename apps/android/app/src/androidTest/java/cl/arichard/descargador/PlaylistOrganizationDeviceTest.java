package cl.arichard.descargador;

import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.view.View;
import android.widget.LinearLayout;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.Until;
import cl.arichard.descargador.adapter.PlaylistAdapter;
import cl.arichard.descargador.data.PlaylistManager;
import cl.arichard.descargador.model.SearchResult;
import cl.arichard.descargador.util.Tr;
import java.io.File;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Gestos reales con playlists ficticias, exclusivamente en el paquete aislado. */
@RunWith(AndroidJUnit4.class)
public class PlaylistOrganizationDeviceTest {
    private static final String VIAJES = "Demo Viajes", CHILL = "Demo Chill", GYM = "Demo Gym";
    private <T> T main(Supplier<T> action) {
        AtomicReference<T> result = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> result.set(action.get()));
        return result.get();
    }
    private void call(MainActivity activity, String name, Class<?> type, Object argument) {
        try {
            Method method = MainActivity.class.getDeclaredMethod(name, type);
            method.setAccessible(true); method.invoke(activity, argument);
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
    private void waitFor(Supplier<Boolean> condition) throws Exception {
        long deadline = System.currentTimeMillis() + 20000;
        while (!condition.get() && System.currentTimeMillis() < deadline) Thread.sleep(100);
        assertTrue("Expected playlist organization state", condition.get());
    }
    @Test public void lightPlaylistsCanBeReorderedAndPinnedWithoutHidingRecommendations() throws Exception { verify(false); }
    @Test public void darkPlaylistsCanBeReorderedAndPinnedWithoutHidingRecommendations() throws Exception { verify(true); }

    private void verify(boolean dark) throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String pkg = context.getPackageName();
        assertEquals("cl.arichard.descargador.verification", pkg);
        PlaylistManager manager = PlaylistManager.getInstance(context);
        JSONObject previous = new JSONObject(manager.exportJson().toString());
        manager.importJson(new JSONObject("{\"playlists\":{\"Favoritos\":[]}}"));
        for (String name : Arrays.asList(VIAJES, CHILL, GYM)) {
            manager.createPlaylist(name);
            manager.addOnlineTrack(name, new SearchResult("abcdefghijk", "Canción de muestra", "Demo", 120, "", ""));
            manager.takeFreshlyAdded(name);
        }
        context.getSharedPreferences("musicbox_prefs", 0).edit().putBoolean("all_permissions_requested_v1", true)
                .putBoolean("dark_mode", dark).commit();
        main(() -> { androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(dark
                ? androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES : androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO); return true; });
        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        androidx.test.uiautomator.Configurator.getInstance().setWaitForIdleTimeout(0);
        device.wakeUp(); device.pressHome();
        MainActivity activity = (MainActivity) InstrumentationRegistry.getInstrumentation().startActivitySync(
                new Intent(context, PreviewMainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        try {
            waitFor(() -> main(() -> activity.hasWindowFocus()
                    && new androidx.lifecycle.ViewModelProvider(activity).get(MainUiState.class).libraryLoaded));
            Thread.sleep(2000);
            main(() -> { call(activity, "setLibraryMode", int.class, 3); call(activity, "switchTab", boolean.class, false); return true; });
            RecyclerView rows = main(() -> activity.findViewById(R.id.recyclerLibrary));
            waitFor(() -> main(() -> rows.getAdapter() instanceof PlaylistAdapter && rows.getAdapter().getItemCount() == 3));
            main(() -> { manager.addOnlineTrack("Favoritos", new SearchResult("bbbbbbbbbbb", "Favorita demo", "Demo", 120, "", "")); return true; });
            waitFor(() -> main(() -> rows.getAdapter().getItemCount() == 4));
            main(() -> { manager.removeSongFromPlaylist("Favoritos", "youtube:bbbbbbbbbbb"); return true; });
            waitFor(() -> main(() -> rows.getAdapter().getItemCount() == 3));
            device.findObjects(By.res(pkg, "btnPlaylistMore")).get(0).click();
            assertNotNull(device.wait(Until.findObject(By.text(Tr.t("Fijar en Inicio"))), 5000));
            device.findObject(By.text(Tr.t("Fijar en Inicio"))).click();
            waitFor(() -> manager.isPinned(VIAJES));
            main(() -> { manager.setPinned(CHILL, true); return true; });
            Thread.sleep(400); // Espera la actualización de tarjetas antes de iniciar el gesto.
            Rect first = device.wait(Until.findObject(By.res(pkg, "txtPlaylistName").text(VIAJES)), 5000).getVisibleBounds();
            Rect last = device.findObject(By.res(pkg, "txtPlaylistName").text(GYM)).getVisibleBounds();
            assertTrue(device.drag(first.centerX(), first.centerY(), first.centerX(), last.bottom + last.height(), 50));
            waitFor(() -> manager.getVisiblePlaylistNames(Collections.emptyList()).equals(Arrays.asList(CHILL, GYM, VIAJES)));
            assertTrue(main(() -> rows.getAdapter() instanceof PlaylistAdapter));
            assertEquals(Collections.singletonList("youtube:abcdefghijk"), manager.getSongPaths(VIAJES));
            device.findObject(By.res(pkg, "navTabSearch")).click();
            waitFor(() -> main(() -> activity.findViewById(R.id.tabSearch).isShown()));
            main(() -> {
                MainUiState state = new androidx.lifecycle.ViewModelProvider(activity).get(MainUiState.class);
                state.forYouLoading = true;
                try {
                    Method refresh = MainActivity.class.getDeclaredMethod("refreshDiscoveryContent");
                    refresh.setAccessible(true); refresh.invoke(activity);
                } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
                return true;
            });
            LinearLayout pinned = main(() -> activity.findViewById(R.id.layoutPinnedPlaylists));
            waitFor(() -> main(() -> pinned.getChildCount() == 2 && pinned.isShown()
                    && activity.findViewById(R.id.tabLibrary).getVisibility() == View.GONE));
            main(() -> {
                assertEquals(CHILL, pinned.getChildAt(0).getTag());
                assertEquals(VIAJES, pinned.getChildAt(1).getTag());
                View scroll = activity.findViewById(R.id.pinnedPlaylistsScroll);
                assertEquals(Math.round(56 * context.getResources().getDisplayMetrics().density), scroll.getHeight());
                if (pinned.getWidth() == scroll.getWidth()) {
                    assertEquals(pinned.getChildAt(0).getLeft(), pinned.getWidth() - pinned.getChildAt(1).getRight(), 1);
                }
                assertTrue(activity.findViewById(R.id.sectionForYou).getTop() >= scroll.getBottom());
                return true;
            });
            assertTrue(device.takeScreenshot(new File(context.getExternalFilesDir(null), "pinned-home-" + (dark ? "dark" : "light") + ".png")));
            main(() -> { pinned.getChildAt(0).performClick(); return true; });
            waitFor(() -> main(() -> ((android.widget.TextView) activity.findViewById(R.id.txtLibraryTitle)).getText().toString().equals(CHILL)));
            main(() -> { manager.setPinned(CHILL, false); manager.setPinned(VIAJES, false); return true; });
            waitFor(() -> main(() -> activity.findViewById(R.id.pinnedPlaylistsScroll).getVisibility() == View.GONE));
        } finally {
            main(() -> { activity.finish(); manager.importJson(previous); return true; });
        }
    }
}
