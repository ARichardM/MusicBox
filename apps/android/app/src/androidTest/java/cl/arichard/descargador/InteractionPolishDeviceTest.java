package cl.arichard.descargador;

import android.app.Activity;
import android.app.Dialog;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.Until;
import cl.arichard.descargador.model.SearchResult;
import cl.arichard.descargador.model.Song;
import cl.arichard.descargador.player.PlayerManager;
import cl.arichard.descargador.util.LyricsFetcher;
import cl.arichard.descargador.util.PlaylistSuggestionsView;
import cl.arichard.descargador.util.Tr;
import cl.arichard.descargador.util.UpdateChecker;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class InteractionPolishDeviceTest {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final Context context = instrumentation.getTargetContext();
    private final UiDevice device = UiDevice.getInstance(instrumentation);

    @Before public void setUp() throws Exception {
        assertEquals("cl.arichard.descargador.verification", context.getPackageName());
        context.getSharedPreferences("musicbox_prefs", 0).edit().putBoolean("all_permissions_requested_v1", true).commit();
        device.wakeUp();
    }

    private <T> T main(Supplier<T> action) {
        AtomicReference<T> result = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> result.set(action.get()));
        return result.get();
    }

    private void waitFor(Supplier<Boolean> condition) throws Exception {
        long end = SystemClock.uptimeMillis() + 10000;
        while (!condition.get() && SystemClock.uptimeMillis() < end) Thread.sleep(40);
        assertTrue("Expected interaction state", condition.get());
    }

    private Activity launch(Class<? extends Activity> type, int flags) throws Exception {
        Activity activity = instrumentation.startActivitySync(new Intent(context, type).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | flags));
        waitFor(() -> main(activity::hasWindowFocus));
        return activity;
    }

    @Test public void updateNotesScrollBackToTheTopBeforeTheSheetMoves() throws Exception {
        Activity activity = launch(PreviewMainActivity.class, Intent.FLAG_ACTIVITY_CLEAR_TASK);
        UpdateChecker.Release release = new UpdateChecker.Release();
        release.version = "99.0.0";
        for (int index = 1; index <= 25; index++) {
            release.notes.add("Verification note " + index + ". "
                    + "Long release notes must be readable inside the update panel. ".repeat(4));
        }
        try {
            Method show = UpdateChecker.class.getDeclaredMethod("show", Activity.class, UpdateChecker.Release.class,
                    String.class, SharedPreferences.class);
            show.setAccessible(true);
            main(() -> {
                try {
                    show.invoke(null, activity, release, "2.4.2", context.getSharedPreferences("test_update", 0));
                } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
                return true;
            });
            device.wait(Until.findObject(By.text(Tr.t("Ver detalles"))), 5000).click();
            waitFor(() -> device.hasObject(By.text(Tr.t("Ver menos"))));
            instrumentation.waitForIdleSync();
            Thread.sleep(450); // La hoja cambia de altura al expandir las notas.
            String heading = Tr.t("¡Hay una versión nueva!");
            Rect sheetHeading = device.wait(Until.findObject(By.text(heading)), 5000).getVisibleBounds();
            Rect notes = device.wait(Until.findObject(By.clazz("android.widget.ScrollView").scrollable(true)), 5000).getVisibleBounds();
            assertTrue(device.swipe(notes.centerX(), notes.bottom - 30, notes.centerX(), notes.top + 30, 30));
            assertTrue(device.wait(Until.gone(By.textStartsWith("•  Verification note 1.")), 4000));
            assertTrue(device.swipe(notes.centerX(), notes.top + 30, notes.centerX(), notes.bottom - 30, 30));
            assertTrue(device.wait(Until.hasObject(By.textStartsWith("•  Verification note 1.")), 4000));
            assertEquals(sheetHeading.top, device.findObject(By.text(heading)).getVisibleBounds().top, 5);
            assertTrue(device.swipe(notes.centerX(), notes.top + 30, notes.centerX(), device.getDisplayHeight() - 30, 32));
            assertTrue(device.wait(Until.gone(By.text(heading)), 5000));
        } finally {
            main(() -> { activity.finish(); return true; });
        }
    }

    @Test public void suggestionRespondsToAPressAndHorizontalSwipeDoesNotPlayIt() throws Exception {
        Activity activity = launch(PreviewMainActivity.class, Intent.FLAG_ACTIVITY_CLEAR_TASK);
        AtomicInteger plays = new AtomicInteger();
        Dialog dialog = main(() -> {
            PlaylistSuggestionsView suggestions = new PlaylistSuggestionsView(activity);
            suggestions.setListener(new PlaylistSuggestionsView.Listener() {
                public void onPlay(SearchResult song) { plays.incrementAndGet(); }
                public void onAdd(SearchResult song) {}
                public void onRefresh() {}
                public void onCollapsedChanged(boolean collapsed) {}
            });
            suggestions.setSuggestions(Arrays.asList(
                    new SearchResult("abcdefghijk", "First", "Fixture", 120, "", ""),
                    new SearchResult("bbbbbbbbbbb", "Second", "Fixture", 130, "", "")));
            suggestions.setShown(true);
            Dialog host = new Dialog(activity);
            host.setContentView(suggestions);
            host.show();
            host.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            return host;
        });
        try {
            RecyclerView list = main(() -> findCarousel(dialog.getWindow().getDecorView()));
            assertNotNull(list);
            waitFor(() -> main(() -> list.hasWindowFocus() && list.findViewHolderForAdapterPosition(0) != null));
            Thread.sleep(450); // Deja terminar la entrada y el ajuste inicial del ancho.
            View card = main(() -> list.findViewHolderForAdapterPosition(0).itemView);
            Rect bounds = main(() -> {
                int[] location = new int[2];
                card.getLocationOnScreen(location);
                return new Rect(location[0], location[1], location[0] + card.getWidth(), location[1] + card.getHeight());
            });
            long down = SystemClock.uptimeMillis();
            main(() -> { pointer(card, down, MotionEvent.ACTION_DOWN); return true; });
            waitFor(() -> main(() -> card.getScaleX() < .99f));
            main(() -> { pointer(card, down, MotionEvent.ACTION_UP); return true; });
            waitFor(() -> plays.get() == 1 && main(() -> Math.abs(card.getScaleX() - 1f) < .005f));
            assertTrue(device.click(bounds.centerX(), bounds.centerY()));
            waitFor(() -> plays.get() == 2);
            assertTrue(device.swipe(bounds.right - bounds.width() / 4, bounds.centerY(),
                    bounds.left + 20, bounds.centerY(), 25));
            waitFor(() -> main(() -> list.computeHorizontalScrollOffset() > 0));
            assertEquals(2, plays.get());
        } finally {
            main(() -> { dialog.dismiss(); activity.finish(); return true; });
        }
    }

    private static RecyclerView findCarousel(View view) {
        if (view instanceof RecyclerView) return (RecyclerView) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                RecyclerView child = findCarousel(group.getChildAt(index));
                if (child != null) return child;
            }
        }
        return null;
    }

    private static void pointer(View card, long down, int action) {
        MotionEvent event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action,
                card.getWidth() / 2f, card.getHeight() / 2f, 0);
        card.dispatchTouchEvent(event);
        event.recycle();
    }

    @Test public void lyricsFollowTheNativePlayerThroughPauseResumeSeekAndTrackChange() throws Exception {
        Activity library = launch(PreviewMainActivity.class, Intent.FLAG_ACTIVITY_CLEAR_TASK);
        Song withLyrics = writeSample("timed");
        Song instrumental = writeSample("instrumental");
        File sidecar = new File(context.getCacheDir(), "timed.lrc");
        try (FileOutputStream output = new FileOutputStream(sidecar)) {
            output.write("[00:00.00] First\n[00:02.50] Second\n[00:04.00] Third\n[00:06.00] Last\n".getBytes(StandardCharsets.UTF_8));
        }
        Field transport = LyricsFetcher.class.getDeclaredField("httpTransport");
        transport.setAccessible(true);
        Object original = transport.get(null);
        transport.set(null, (LyricsFetcher.HttpTransport) url -> "[]");
        Field active = NowPlayingActivity.class.getDeclaredField("activeLyricsIndex");
        active.setAccessible(true);
        PlayerManager player = PlayerManager.getInstance();
        NowPlayingActivity screen = null;
        try {
            main(() -> { player.playSong(withLyrics, Arrays.asList(withLyrics, instrumental)); return true; });
            screen = (NowPlayingActivity) launch(NowPlayingActivity.class, 0);
            NowPlayingActivity current = screen;
            waitFor(() -> main(() -> current.findViewById(R.id.tabSelectorLyrics).isShown() && player.isPlaying()));
            main(() -> {
                assertFalse(current.findViewById(R.id.tabSelectorMp4).isShown());
                current.findViewById(R.id.tabSelectorLyrics).performClick();
                player.seekTo(900);
                player.pause();
                return true;
            });
            waitFor(() -> main(() -> Math.abs(player.getCurrentPosition() - 900) < 150 && activeLine(active, current) == 0));
            Thread.sleep(550);
            assertTrue(main(() -> !player.isPlaying() && activeLine(active, current) == 0));
            main(() -> { player.seekTo(4200); return true; });
            waitFor(() -> main(() -> !player.isPlaying() && activeLine(active, current) == 2));
            main(() -> { player.play(); return true; });
            waitFor(() -> main(() -> player.isPlaying() && activeLine(active, current) == 3));
            main(() -> { player.playQueueIndex(1); return true; });
            waitFor(() -> main(() -> !current.findViewById(R.id.playerSelectorPill).isShown()
                    && !current.findViewById(R.id.containerLyrics).isShown() && player.isPlaying()));
        } finally {
            transport.set(null, original);
            NowPlayingActivity closing = screen;
            main(() -> {
                if (closing != null) closing.finish();
                player.stopAndRelease();
                library.finish();
                return true;
            });
            sidecar.delete();
            new File(withLyrics.getPath()).delete();
            new File(instrumental.getPath()).delete();
        }
    }

    private static int activeLine(Field field, NowPlayingActivity activity) {
        try { return field.getInt(activity); }
        catch (IllegalAccessException error) { throw new AssertionError(error); }
    }

    private Song writeSample(String name) throws Exception {
        File file = new File(context.getCacheDir(), name + ".wav");
        int bytes = 16000 * 2 * 12;
        ByteBuffer wav = ByteBuffer.allocate(44 + bytes).order(ByteOrder.LITTLE_ENDIAN);
        wav.put(new byte[]{'R','I','F','F'}).putInt(36 + bytes).put(new byte[]{'W','A','V','E','f','m','t',' '});
        wav.putInt(16).putShort((short) 1).putShort((short) 1).putInt(16000).putInt(32000);
        wav.putShort((short) 2).putShort((short) 16).put(new byte[]{'d','a','t','a'}).putInt(bytes);
        try (FileOutputStream output = new FileOutputStream(file)) { output.write(wav.array()); }
        return new Song(0, name, "Fixture", "Synthetic", 12000, file.getPath(), "Fixture", 0);
    }
}
