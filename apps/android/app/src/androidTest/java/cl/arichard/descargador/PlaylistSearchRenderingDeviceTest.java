package cl.arichard.descargador;

import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.view.View;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.Until;
import cl.arichard.descargador.adapter.SongAdapter;
import cl.arichard.descargador.data.PlaylistManager;
import cl.arichard.descargador.model.SearchResult;
import cl.arichard.descargador.util.YtMusicApi;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.json.JSONArray;
import org.json.JSONObject;
import static org.junit.Assert.*;

/** Datos ficticios, sin cuenta ni cambios en instalaciones de uso personal. */
@RunWith(AndroidJUnit4.class)
public class PlaylistSearchRenderingDeviceTest {
    private Context context;
    private <T> T main(Supplier<T> action) {
        AtomicReference<T> result = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> result.set(action.get()));
        return result.get();
    }
    private void invoke(MainActivity activity, String name, Class<?>[] types, Object... arguments) {
        try {
            Method method = MainActivity.class.getDeclaredMethod(name, types);
            method.setAccessible(true); method.invoke(activity, arguments);
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
    private void waitFor(Supplier<Boolean> condition) throws Exception {
        long deadline = System.currentTimeMillis() + 20000;
        while (!condition.get() && System.currentTimeMillis() < deadline) Thread.sleep(100);
        assertTrue("Expected verification UI state", condition.get());
    }
    private MainUiState state(MainActivity activity) {
        return new androidx.lifecycle.ViewModelProvider(activity).get(MainUiState.class);
    }
    private MainActivity launch(Intent intent) throws Exception {
        MainActivity activity = (MainActivity) InstrumentationRegistry.getInstrumentation().startActivitySync(
                intent.setClass(context, PreviewMainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        waitFor(() -> main(() -> state(activity).libraryLoaded));
        waitFor(() -> main(activity::hasWindowFocus));
        return activity;
    }
    @Before public void setup() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertEquals("cl.arichard.descargador.verification", context.getPackageName());
        context.getSharedPreferences("musicbox_prefs", 0).edit()
                .putBoolean("all_permissions_requested_v1", true).putString("library_sort", "RECENT:1").commit();
    }

    @Test public void sharedSearchAndLateDiscoveryRefreshCannotOverlapResults() throws Exception {
        context.getSharedPreferences("library_navigation", 0).edit().putBoolean("search_tab", false).commit();
        MainActivity activity = launch(new Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, "Way Too Self Aware Ian Asher"));
        try {
            main(() -> {
                MainUiState state = state(activity);
                assertEquals("Way Too Self Aware Ian Asher", state.searchQuery);
                // Sustituye la respuesta de red por una muestra determinista e invalida su callback.
                ++state.searchGeneration;
                state.results = new ArrayList<>();
                state.resultsVisible = true;
                state.searchLoading = true;
                state.changed();
                assertEquals(View.GONE, activity.findViewById(R.id.containerSearchDiscovery).getVisibility());
                assertEquals(View.VISIBLE, activity.findViewById(R.id.searchLoading).getVisibility());
                assertEquals(View.GONE, activity.findViewById(R.id.recyclerSearchResults).getVisibility());
                state.results = new ArrayList<>(Collections.singletonList(new SearchResult(
                        "abcdefghijk", "Way Too Self Aware", "Ian Asher", 157, "", "")));
                state.searchLoading = false;
                state.searchStatus = "1 resultado";
                state.changed();
                invoke(activity, "refreshDiscoveryContent", new Class<?>[0]);
                return true;
            });
            Thread.sleep(1800); // Incluye el refresco diferido del splash y de las secciones de inicio.
            main(() -> {
                assertTrue(activity.findViewById(R.id.tabSearch).isShown());
                assertEquals(View.GONE, activity.findViewById(R.id.containerSearchDiscovery).getVisibility());
                assertEquals(View.GONE, activity.findViewById(R.id.searchLoading).getVisibility());
                RecyclerView rows = activity.findViewById(R.id.recyclerSearchResults);
                assertEquals(View.VISIBLE, rows.getVisibility());
                assertEquals(1, rows.getAdapter().getItemCount());
                return true;
            });
            assertTrue(androidx.test.uiautomator.UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
                    .takeScreenshot(new java.io.File(context.getExternalFilesDir(null), "verification-search-rendering.png")));
        } finally { main(() -> { activity.finish(); return true; }); }
    }

    @Test public void newPlaylistSongUsesNewestOrderAndItsHighlightStaysAligned() throws Exception {
        String name = "Verificación de orden y realce";
        PlaylistManager manager = PlaylistManager.getInstance(context);
        manager.deletePlaylist(name);
        manager.createPlaylist(name);
        manager.addOnlineTrack(name, new SearchResult("aaaaaaaaaaa", "Alfa", "Muestra", 120, "", ""));
        manager.takeFreshlyAdded(name);
        MainActivity activity = launch(new Intent());
        try {
            main(() -> {
                invoke(activity, "switchTab", new Class<?>[]{boolean.class}, false);
                invoke(activity, "showPlaylistSongs", new Class<?>[]{String.class}, name);
                return true;
            });
            Thread.sleep(500);
            main(() -> {
                manager.addOnlineTrack(name, new SearchResult("bbbbbbbbbbb", "Zulu nueva", "Muestra", 120, "", ""));
                invoke(activity, "refreshPlaylistsView", new Class<?>[0]);
                invoke(activity, "showArrivals", new Class<?>[]{String.class}, name);
                return true;
            });
            RecyclerView rows = main(() -> activity.findViewById(R.id.recyclerLibrary));
            waitFor(() -> main(() -> rows.findViewHolderForAdapterPosition(0) != null));
            main(() -> {
                assertEquals("Zulu nueva", ((SongAdapter) rows.getAdapter()).getDisplayedList().get(0).getTitle());
                if (rows.getItemAnimator() != null) rows.getItemAnimator().endAnimations();
                return true;
            });
            for (int frame = 0; frame < 12; frame++) {
                main(() -> {
                    View row = rows.findViewHolderForAdapterPosition(0).itemView;
                    assertEquals(0f, row.getTranslationX(), .01f);
                    assertEquals(1f, row.getAlpha(), .01f);
                    return true;
                });
                Thread.sleep(100);
            }
            assertTrue(androidx.test.uiautomator.UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
                    .takeScreenshot(new java.io.File(context.getExternalFilesDir(null), "verification-playlist-arrival.png")));
        } finally {
            main(() -> { activity.finish(); manager.deletePlaylist(name); return true; });
        }
    }

    @Test public void scrollingLoadsTwoMorePagesAndReturnsToTheStart() throws Exception {
        Field transport = YtMusicApi.class.getDeclaredField("transport");
        transport.setAccessible(true);
        Object original = transport.get(null);
        List<String> pages = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch releaseSecondPage = new CountDownLatch(1);
        transport.set(null, (YtMusicApi.Transport) (endpoint, body) -> {
            if (!endpoint.equals("search")) return new JSONObject();
            String token = body.optString("continuation");
            if (token.isEmpty() && !body.optString("query").equals("MusicBox scroll verification")) return new JSONObject();
            int first = token.equals("second") ? 20 : token.equals("third") ? 40 : 0;
            String next = first == 0 ? "second" : first == 20 ? "third" : "";
            pages.add(token);
            if (token.equals("second") && !releaseSecondPage.await(45, TimeUnit.SECONDS))
                throw new java.io.IOException("Timed out waiting for the pagination footer");
            JSONArray results = new JSONArray();
            for (int i = first; i < first + 20; i++) {
                JSONObject title = new JSONObject().put("runs", new JSONArray().put(new JSONObject().put("text", "Result " + i)));
                JSONObject column = new JSONObject().put("musicResponsiveListItemFlexColumnRenderer",
                        new JSONObject().put("text", title));
                JSONObject row = new JSONObject().put("playlistItemData", new JSONObject().put("videoId", "result-" + i))
                        .put("flexColumns", new JSONArray().put(column));
                results.put(new JSONObject().put("musicResponsiveListItemRenderer", row));
            }
            return new JSONObject().put("contents", results)
                    .put("nextContinuationData", new JSONObject().put("continuation", next));
        });
        MainActivity activity = null;
        try {
            activity = launch(new Intent());
            DescargadorApp.IO.submit(() -> {}).get(45, TimeUnit.SECONDS);
            MainActivity current = activity;
            RecyclerView rows = main(() -> {
                invoke(current, "switchTab", new Class<?>[]{boolean.class}, true);
                android.widget.EditText input = current.findViewById(R.id.inputSearch);
                input.setText("MusicBox scroll verification");
                invoke(current, "triggerSearchOrDownload", new Class<?>[]{String.class}, "MusicBox scroll verification");
                return current.findViewById(R.id.recyclerSearchResults);
            });
            waitFor(() -> main(() -> rows.isShown() && rows.getAdapter().getItemCount() == 20));
            UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
            Rect bounds = device.wait(Until.findObject(By.res(context.getPackageName(), "recyclerSearchResults")), 5000)
                    .getVisibleBounds();
            int from = bounds.top + 2 * bounds.height() / 3;
            int to = bounds.top + bounds.height() / 10;
            for (int i = 0; i < 16 && !main(() -> rows.getAdapter().getItemCount() == 21); i++) {
                device.swipe(bounds.centerX(), from, bounds.centerX(), to, 20);
            }
            waitFor(() -> pages.contains("second"));
            main(() -> { rows.scrollToPosition(20); return true; });
            waitFor(() -> main(() -> {
                RecyclerView.ViewHolder footer = rows.findViewHolderForAdapterPosition(20);
                return footer != null && footer.itemView.findViewById(R.id.searchLoadingMoreRing) != null;
            }));
            releaseSecondPage.countDown();
            for (int i = 0; i < 24 && !main(() -> rows.getAdapter().getItemCount() == 60); i++) {
                device.swipe(bounds.centerX(), from, bounds.centerX(), to, 20);
            }
            waitFor(() -> main(() -> rows.getAdapter().getItemCount() == 60 && !state(current).hasMoreResults));
            assertEquals(java.util.Arrays.asList("", "second", "third"), new ArrayList<>(pages));
            for (int i = 0; i < 24 && main(() -> rows.canScrollVertically(-1)); i++) {
                device.swipe(bounds.centerX(), to, bounds.centerX(), from, 20);
            }
            waitFor(() -> main(() -> !rows.canScrollVertically(-1) && rows.findViewHolderForAdapterPosition(0) != null));
        } finally {
            releaseSecondPage.countDown();
            transport.set(null, original);
            MainActivity closing = activity;
            if (closing != null) main(() -> { closing.finish(); return true; });
        }
    }
}
