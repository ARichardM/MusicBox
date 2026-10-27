package cl.arichard.descargador;

import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.Configurator;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;
import cl.arichard.descargador.data.MusicScanner;
import cl.arichard.descargador.model.Song;
import cl.arichard.descargador.player.PlayerManager;
import cl.arichard.descargador.util.Tr;
import org.junit.Before;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import static org.junit.Assert.*;
import static org.junit.Assume.*;

/** Runs against the separate preview app and never deletes or downloads user files. */
@RunWith(AndroidJUnit4.class)
public class DeviceGestureTest {
    private UiDevice device;
    private Context context;
    private String pkg;
    private List<Song> songs;
    private final PlayerManager player = PlayerManager.getInstance();

    private <T> T main(Supplier<T> read) {
        AtomicReference<T> value = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> value.set(read.get()));
        return value.get();
    }
    private void action(Runnable action) { main(() -> { action.run(); return true; }); }
    private UiObject2 view(String id) {
        UiObject2 object = device.wait(Until.findObject(By.res(pkg, id)), 12000);
        assertNotNull("Visible view: " + id, object); return object;
    }
    private android.app.Activity resumed() {
        return main(() -> androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).iterator().next());
    }
    private void page(boolean search) throws Exception {
        view(search ? "navTabSearch" : "navTabLibrary").click();
        waitFor(() -> device.hasObject(By.res(pkg, search ? "navTabSearch" : "navTabLibrary").selected(true)));
        Thread.sleep(320);
    }
    @After public void pauseAudio() { action(() -> { if (player.isPlaying()) player.togglePlayPause(); }); }
    private void waitFor(Supplier<Boolean> condition) throws Exception {
        long deadline = System.currentTimeMillis() + 8000;
        while (!condition.get() && System.currentTimeMillis() < deadline) Thread.sleep(100);
        assertTrue("State changed after gesture", condition.get());
    }
    private void swipe(Rect area, boolean left) {
        int start = area.left + area.width() * 4 / 5, end = area.left + area.width() / 5;
        device.swipe(left ? start : end, area.centerY(), left ? end : start, area.centerY() + 3, 25);
    }
    @Before public void start() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext(); pkg = context.getPackageName();
        assumeTrue("Use the independent verification app", pkg.endsWith(".verification"));
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        Configurator.getInstance().setWaitForIdleTimeout(0).setWaitForSelectorTimeout(3000);
        songs = MusicScanner.scanAllMusic(context); assumeTrue("At least 6 local songs", songs.size() >= 6);
        Intent intent = new Intent(context, PreviewMainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        android.app.Activity launched = InstrumentationRegistry.getInstrumentation().startActivitySync(intent);
        waitFor(() -> main(launched::hasWindowFocus)); view("navTabSearch");
        action(() -> { if (player.isShuffle()) player.toggleShuffle(); player.playSong(songs.get(0), new ArrayList<>(songs.subList(0, 5))); });
        waitFor(() -> main(() -> !player.isLoading()));
        action(() -> { if (player.isPlaying()) player.togglePlayPause(); });
        page(true);
    }

    @Test public void pageSwipesWorkWhileCarouselsFiltersAndVerticalListsKeepTheirGestures() throws Exception {
        // Starting a local song makes the recent carousel visible in this independent app.
        action(() -> { player.playNext(); }); waitFor(() -> main(() -> !player.isLoading()));
        action(() -> { if (player.isPlaying()) player.togglePlayPause(); });
        // Refresh discovery after recording the play.
        page(false); view("chipSongs").click(); page(true);
        Rect carousel = view("recentPlaysScroll").getVisibleBounds();
        for (int i = 0; i < 3; i++) swipe(carousel, true);
        assertTrue("Carousel must not change page", device.hasObject(By.res(pkg, "navTabSearch").selected(true)));
        swipe(carousel, false); view("inputSearch");
        // A horizontal swipe on the ordinary vertical song list changes page.
        UiObject2 row = view("txtSongTitle");
        swipe(row.getVisibleBounds(), true);
        waitFor(() -> device.hasObject(By.res(pkg, "navTabLibrary").selected(true))); Thread.sleep(300);
        view("txtLibraryTitle");
        UiObject2 list = view("recyclerLibrary"); Rect bounds = list.getVisibleBounds();
        device.swipe(bounds.centerX(), bounds.top + bounds.height() * 3 / 4,
                bounds.centerX() + 8, bounds.top + bounds.height() / 4, 30);
        view("txtLibraryTitle");
        android.app.Activity libraryActivity = resumed();
        waitFor(() -> main(() -> ((androidx.recyclerview.widget.RecyclerView) libraryActivity.findViewById(R.id.recyclerLibrary))
                .getScrollState() == androidx.recyclerview.widget.RecyclerView.SCROLL_STATE_IDLE));
        // Accessibility can report a clipped first row above the list's viewport.
        // Use a fully visible title after the fling settles, so the swipe starts on content.
        row = null;
        for (UiObject2 candidate : device.findObjects(By.res(pkg, "txtSongTitle"))) {
            Rect visible = candidate.getVisibleBounds();
            if (visible.top >= bounds.top && visible.bottom <= bounds.bottom && visible.height() > 0) { row = candidate; break; }
        }
        assertNotNull("A fully visible song title", row);
        // Start on the title, outside the deliberately protected favorite/menu strip.
        Rect titleBounds = row.getVisibleBounds();
        String owners = main(() -> describePoint(libraryActivity.findViewById(R.id.mainGestureRoot), titleBounds.left + titleBounds.width() / 5, titleBounds.centerY()));
        swipe(titleBounds, false);
        try { waitFor(() -> device.hasObject(By.res(pkg, "navTabSearch").selected(true))); }
        catch (AssertionError failure) { throw new AssertionError("Page swipe at " + titleBounds + "; owners=" + owners, failure); }
        Thread.sleep(300);
        view("inputSearch");
    }

    @Test public void navbarSupportsHoldingAndDraggingTheSelector() throws Exception {
        Rect search = view("navTabSearch").getVisibleBounds(), library = view("navTabLibrary").getVisibleBounds();
        device.drag(search.centerX(), search.centerY(), library.centerX(), library.centerY(), 35);
        waitFor(() -> device.hasObject(By.res(pkg, "navTabLibrary").selected(true)));
        Thread.sleep(300);
        device.drag(library.centerX(), library.centerY(), search.centerX(), search.centerY(), 35);
        waitFor(() -> device.hasObject(By.res(pkg, "navTabSearch").selected(true)));
    }

    @Test public void themeChangeKeepsLibraryResultsAndQueue() throws Exception {
        MainActivity activity = (MainActivity) resumed();
        MainUiState state = main(() -> new androidx.lifecycle.ViewModelProvider(activity).get(MainUiState.class));
        waitFor(() -> main(() -> state.libraryLoaded));
        int count = main(() -> state.library.size());
        String path = main(() -> player.getCurrentSong().getPath());
        action(() -> {
            state.searchQuery = "Prueba de tema";
            state.results = new ArrayList<>(java.util.Collections.singletonList(new cl.arichard.descargador.model.SearchResult(
                    "abcdefghijk", "Resultado de prueba", "Artista", 120, "", "")));
            state.resultsVisible = true; state.searchStatus = "1 resultado"; state.changed();
        });
        assertNotNull(device.wait(Until.findObject(By.text("Resultado de prueba")), 5000));
        for (int i = 0; i < 2; i++) {
            final int mode = i % 2 == 0 ? androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO : androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES;
            action(() -> androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(mode)); Thread.sleep(1000);
            assertNotNull(device.wait(Until.findObject(By.text("Resultado de prueba")), 5000));
            MainActivity themed = (MainActivity) resumed();
            MainUiState retained = main(() -> new androidx.lifecycle.ViewModelProvider(themed).get(MainUiState.class));
            assertEquals(count, main(() -> retained.library.size()).intValue());
            assertEquals(path, main(() -> player.getCurrentSong().getPath()));
        }
        action(() -> { state.resultsVisible = false; state.results = new ArrayList<>(); state.searchQuery = ""; state.searchStatus = ""; state.changed(); });
        view("sectionTopLibrary");
    }

    @Test public void actionStripIgnoresAccidentalDrags() throws Exception {
        page(false); view("chipSongs").click();
        int favorites = main(() -> cl.arichard.descargador.data.PlaylistManager.getInstance(context).getSongPaths("Favoritos").size());
        Rect button = view("btnFavorite").getVisibleBounds();
        device.swipe(button.centerX(), button.centerY(), button.centerX() + button.width() / 3, button.centerY(), 20);
        assertEquals(favorites, main(() -> cl.arichard.descargador.data.PlaylistManager.getInstance(context).getSongPaths("Favoritos").size()).intValue());
        assertTrue(device.hasObject(By.res(pkg, "navTabLibrary").selected(true)));
        button = view("btnSongMenu").getVisibleBounds();
        device.swipe(button.centerX(), button.centerY(), button.centerX(), button.centerY() - 400, 30);
        assertFalse(device.hasObject(By.text(Tr.t("Eliminar del dispositivo"))));
        assertTrue(device.hasObject(By.res(pkg, "navTabLibrary").selected(true)));
    }

    @Test public void upwardPlayerDragFollowsFingerAndCanBeCancelled() throws Exception {
        Rect mini = view("miniPlayerLayout").getVisibleBounds();
        long down = android.os.SystemClock.uptimeMillis();
        pointer(down, android.view.MotionEvent.ACTION_DOWN, mini.centerX(), mini.centerY());
        pointer(down, android.view.MotionEvent.ACTION_MOVE, mini.centerX(), mini.centerY() - 120);
        Thread.sleep(100);
        MainActivity activity = (MainActivity) resumed();
        float first = main(() -> activity.findViewById(R.id.nowPlayingCoordinator).getTranslationY());
        pointer(down, android.view.MotionEvent.ACTION_MOVE, mini.centerX(), mini.centerY() - 360);
        Thread.sleep(80);
        float second = main(() -> activity.findViewById(R.id.nowPlayingCoordinator).getTranslationY());
        assertTrue("Player follows the finger: " + first + " -> " + second, second < first && second > 0);
        pointer(down, android.view.MotionEvent.ACTION_CANCEL, mini.centerX(), mini.centerY() - 360);
        waitFor(() -> main(() -> activity.findViewById(R.id.nowPlayingRoot) == null));
        down = android.os.SystemClock.uptimeMillis();
        pointer(down, android.view.MotionEvent.ACTION_DOWN, mini.centerX(), mini.centerY());
        pointer(down, android.view.MotionEvent.ACTION_MOVE, mini.centerX(), mini.centerY() - 800);
        pointer(down, android.view.MotionEvent.ACTION_UP, mini.centerX(), mini.centerY() - 800);
        waitFor(() -> resumed() instanceof NowPlayingActivity);
        view("btnPlayerMore");
    }
    private void pointer(long down, int action, int x, int y) {
        android.view.MotionEvent event = android.view.MotionEvent.obtain(down, android.os.SystemClock.uptimeMillis(), action, x, y, 0);
        InstrumentationRegistry.getInstrumentation().sendPointerSync(event); event.recycle();
    }

    @Test public void miniPlayerSwipesWorkAcrossTitleAndButtonsAndDontChangeTabs() throws Exception {
        String initial = main(() -> player.getCurrentSong().getPath());
        Rect mini = view("miniPlayerLayout").getVisibleBounds();
        swipe(mini, true); waitFor(() -> main(() -> !initial.equals(player.getCurrentSong().getPath())));
        view("inputSearch");
        swipe(view("miniPlayerLayout").getVisibleBounds(), false);
        waitFor(() -> main(() -> initial.equals(player.getCurrentSong().getPath()))); view("inputSearch");
        // A diagonal movement is ignored instead of opening the full player.
        mini = view("miniPlayerLayout").getVisibleBounds();
        device.swipe(mini.centerX(), mini.centerY(), mini.centerX() + 100, mini.centerY() - 100, 25);
        view("inputSearch"); assertFalse(device.hasObject(By.res(pkg, "seekBar")));
        action(() -> { if (player.isPlaying()) player.togglePlayPause(); });
        Rect navigation = view("navIsland").getVisibleBounds();
        assertTrue("Navigation sits above mini player", navigation.bottom <= view("miniPlayerLayout").getVisibleBounds().top);
        int previousTop = navigation.top;
        // Removing queue entries changes playback state only, never the files on the device.
        action(() -> { player.clearUpcoming(); player.removeQueueItem(0); });
        waitFor(() -> !device.hasObject(By.res(pkg, "miniPlayerLayout"))
                && view("navIsland").getVisibleBounds().top > previousTop);
    }

    @Test public void seekingAndQueueReorderDoNotDismissThePlayer() throws Exception {
        view("miniPlayerLayout").click(); view("seekBar");
        Rect seek = view("seekBar").getVisibleBounds(); swipe(seek, false);
        view("seekBar");
        Rect art = view("playerArtworkArea").getVisibleBounds();
        device.swipe(art.centerX(), art.centerY(), art.centerX() + 150, art.centerY() + 150, 25);
        view("seekBar");
        assertFalse(device.hasObject(By.res(pkg, "tabSelectorMp4")));
        view("btnPlayerMore").click();
        UiObject2 delete = device.wait(Until.findObject(By.text(Tr.t("Eliminar del dispositivo"))), 5000);
        assertNotNull(delete); delete.click();
        UiObject2 cancel = device.wait(Until.findObject(By.res("android", "button2")), 5000);
        assertNotNull(cancel); cancel.click(); view("seekBar");
        view("btnPlayerMore").click();
        device.wait(Until.findObject(By.text(Tr.t("Cola de reproducci\u00f3n"))), 5000).click();
        UiObject2 heading = device.wait(Until.findObject(By.textStartsWith(Tr.t("Cola · "))), 5000); assertNotNull(heading);
        // Wait for the sheet's entrance animation before taking screen coordinates.
        Thread.sleep(450);
        List<UiObject2> handles = device.findObjects(By.desc(Tr.t("Arrastrar para reordenar"))); assertTrue(handles.size() >= 3);
        String moved = main(() -> player.getQueue().get(2).getPath());
        Rect from = handles.get(2).getVisibleBounds(), to = handles.get(1).getVisibleBounds();
        long dragDown = android.os.SystemClock.uptimeMillis();
        pointer(dragDown, android.view.MotionEvent.ACTION_DOWN, from.centerX(), from.centerY());
        Thread.sleep(550);
        for (int step = 1; step <= 20; step++) {
            pointer(dragDown, android.view.MotionEvent.ACTION_MOVE, from.centerX(), from.centerY() + (to.centerY() - from.centerY()) * step / 20);
            Thread.sleep(20);
        }
        pointer(dragDown, android.view.MotionEvent.ACTION_UP, to.centerX(), to.centerY());
        try { waitFor(() -> main(() -> moved.equals(player.getQueue().get(1).getPath()))); }
        catch (AssertionError failure) { throw new AssertionError("Queue drag " + from + " -> " + to, failure); }
        assertTrue(device.hasObject(By.textStartsWith(Tr.t("Cola · "))));
        Rect outside = view("btnBackNowPlaying").getVisibleBounds();
        device.click(outside.centerX(), outside.centerY());
        Thread.sleep(450); view("seekBar");
        // Only a clear downward drag starting in the artwork dismisses.
        art = view("playerArtworkArea").getVisibleBounds();
        device.swipe(art.centerX(), art.top + art.height() / 4,
                art.centerX(), Math.min(device.getDisplayHeight() - 200, art.bottom + 200), 30);
        view("miniPlayerLayout"); assertFalse(device.hasObject(By.res(pkg, "seekBar")));
        action(() -> { if (player.isPlaying()) player.togglePlayPause(); });
    }
    private String describePoint(android.view.View view, int x, int y) {
        Rect bounds = new Rect();
        if (view.getVisibility() != android.view.View.VISIBLE || !view.getGlobalVisibleRect(bounds) || !bounds.contains(x, y)) return "";
        String result = view.getClass().getSimpleName() + "#" + view.getId() + ":tag=" + view.getTag(R.id.protectedAction)
                + ",horizontal=" + (view.canScrollHorizontally(-1) || view.canScrollHorizontally(1)) + ";";
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) result += describePoint(group.getChildAt(i), x, y);
        }
        return result;
    }
}
