package cl.arichard.descargador;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.View;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import cl.arichard.descargador.data.MusicScanner;
import cl.arichard.descargador.model.Song;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import static org.junit.Assume.*;

@RunWith(AndroidJUnit4.class)
public class LibraryLoadingDeviceTest {
    private Context context;
    private <T> T main(Supplier<T> action) {
        AtomicReference<T> result = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> result.set(action.get()));
        return result.get();
    }
    @Before public void setup() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assumeTrue(context.getPackageName().equals("cl.arichard.descargador.verification"));
    }
    @Test public void loadingOverlaysTheListInsteadOfReservingAnotherHalfScreen() throws Exception {
        Activity activity = InstrumentationRegistry.getInstrumentation().startActivitySync(
                new Intent(context, PreviewMainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        try {
            main(() -> { activity.findViewById(R.id.navTabLibrary).performClick(); return true; });
            Thread.sleep(2000);
            View list = main(() -> activity.findViewById(R.id.recyclerLibrary));
            View skeleton = main(() -> activity.findViewById(R.id.libraryLoading));
            assertSame(main(list::getParent), main(skeleton::getParent));
            main(() -> { list.setVisibility(View.GONE); skeleton.setVisibility(View.VISIBLE); return true; });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            Rect area = main(() -> { Rect r = new Rect(); skeleton.getGlobalVisibleRect(r); return r; });
            assertTrue("Skeleton visible", area.height() > 0);
            assertEquals(0, (int) main(skeleton::getTop));
            assertEquals(main(() -> ((View) skeleton.getParent()).getHeight()).intValue(), main(skeleton::getHeight).intValue());
            main(() -> { skeleton.setVisibility(View.GONE); list.setVisibility(View.VISIBLE); return true; });
        } finally { main(() -> { activity.finish(); return true; }); }
    }
    @Test public void repeatedScansPreserveAllMetadataAndUseTheCache() {
        // Only derived metadata is cleared, and only in the isolated verification package.
        context.getSharedPreferences("music_metadata_v1", 0).edit().clear().commit();
        long started = SystemClock.elapsedRealtime();
        List<Song> first = MusicScanner.scanAllMusic(context);
        long cold = SystemClock.elapsedRealtime() - started;
        started = SystemClock.elapsedRealtime();
        List<Song> second = MusicScanner.scanAllMusic(context);
        long warm = SystemClock.elapsedRealtime() - started;
        assertFalse("Device fixtures must be available", first.isEmpty());
        assertEquals(first.size(), second.size());
        for (int i = 0; i < first.size(); i++) assertEquals(first.get(i).toJson().toString(), second.get(i).toJson().toString());
        assertTrue(context.getSharedPreferences("music_metadata_v1", 0).getAll().size() >= 9);
        android.util.Log.i("MusicBoxLoadingTest", "songs=" + first.size() + " coldMs=" + cold + " cachedMs=" + warm);
    }
    @Test public void timeoutNoticeDoesNotExposeExtractorWarnings() throws Exception {
        Method describe = cl.arichard.descargador.download.DownloadManager.class.getDeclaredMethod("describe", String.class);
        describe.setAccessible(true);
        String notice = (String) describe.invoke(null, "WARNING: [youtube] formats skipped\nERROR: read timed out");
        assertFalse(notice.contains("WARNING"));
        assertFalse(notice.contains("[youtube]"));
        assertTrue(notice.length() < 120);
    }
    @Test public void libraryLoadsEvenWhenTheExtractorExecutorIsBusy() throws Exception {
        java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        DescargadorApp.IO.execute(() -> {
            entered.countDown();
            try { release.await(20, java.util.concurrent.TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        Activity activity = null;
        try {
            assertTrue("Extractor executor test gate", entered.await(30, java.util.concurrent.TimeUnit.SECONDS));
            activity = InstrumentationRegistry.getInstrumentation().startActivitySync(
                    new Intent(context, PreviewMainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            Activity opened = activity;
            long deadline = SystemClock.elapsedRealtime() + 10000;
            boolean loaded;
            do {
                loaded = main(() -> new androidx.lifecycle.ViewModelProvider((PreviewMainActivity) opened).get(MainUiState.class).libraryLoaded);
                if (!loaded) Thread.sleep(100);
            } while (!loaded && SystemClock.elapsedRealtime() < deadline);
            assertTrue("Local library must not wait for network/extractor tasks", loaded);
            assertEquals(1, release.getCount());
        } finally {
            release.countDown();
            if (activity != null) { Activity opened = activity; main(() -> { opened.finish(); return true; }); }
        }
    }
}
