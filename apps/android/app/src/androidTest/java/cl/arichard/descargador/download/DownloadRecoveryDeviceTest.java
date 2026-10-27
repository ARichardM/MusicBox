package cl.arichard.descargador.download;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import cl.arichard.descargador.DownloaderActivity;
import cl.arichard.descargador.PreviewMainActivity;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.function.BooleanSupplier;
import static org.junit.Assert.*;
import static org.junit.Assume.*;

@RunWith(AndroidJUnit4.class)
public class DownloadRecoveryDeviceTest {
    private Context context;
    private SharedPreferences prefs;

    @Before public void setup() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assumeTrue(context.getPackageName().equals("cl.arichard.descargador.verification"));
        assumeTrue(InstrumentationRegistry.getArguments().containsKey("recoverySource"));
        prefs = context.getSharedPreferences("download_recovery_verification", 0);
    }

    private void waitFor(BooleanSupplier condition, long timeout) throws Exception {
        long deadline = System.currentTimeMillis() + timeout;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) Thread.sleep(100);
        assertTrue("Expected download recovery state", condition.getAsBoolean());
    }

    @Test public void prepareInterruptedDownloads() throws Exception {
        DownloadManager manager = DownloadManager.get(context);
        assertFalse("No other verification downloads may be running", manager.isBusy());
        String source = InstrumentationRegistry.getArguments().getString("recoverySource");
        File destination = new File(context.getCacheDir(), "recovery-" + java.util.UUID.randomUUID());
        assertTrue(destination.mkdir());
        String first = source + "/recovery-first.wav", second = source + "/recovery-second.wav";
        String cancelled = source + "/recovery-cancelled.wav";
        prefs.edit().clear().putString("destination", destination.getAbsolutePath())
                .putString("first", first).putString("second", second).putString("cancelled", cancelled).commit();
        assertEquals(3, manager.enqueue(Arrays.asList(first, second, cancelled),
                new DownloadOptions(true, "192 kbps", false, "", "", destination.getAbsolutePath())));
        DownloadJob cancelledJob = manager.jobs().stream().filter(job -> job.url.equals(cancelled)).findFirst().get();
        manager.cancel(cancelledJob.id);
        waitFor(() -> manager.jobs().stream().anyMatch(job -> job.url.equals(first)
                && job.status == DownloadJob.Status.DOWNLOADING), 120000);
        List<DownloadJob> stored = new DownloadQueueStore(context.getFilesDir()).load();
        assertEquals(2, stored.size());
        assertEquals(first, stored.get(0).url);
        assertEquals(second, stored.get(1).url);
        assertEquals(1, stored.get(0).position);
        assertEquals(3, stored.get(0).batchSize);
        assertTrue(Arrays.stream(destination.listFiles()).anyMatch(file -> file.getName().endsWith(".part")));
    }

    @Test public void reopeningResumesOnlyPendingDownloads() throws Exception {
        DownloadManager manager = DownloadManager.get(context);
        assertEquals(2, manager.jobs().size());
        for (DownloadJob job : manager.jobs()) assertEquals(DownloadJob.Status.WAITING, job.status);
        Class<? extends android.app.Activity> screen = "main".equals(
                InstrumentationRegistry.getArguments().getString("recoveryActivity"))
                ? PreviewMainActivity.class : DownloaderActivity.class;
        android.app.Activity activity = InstrumentationRegistry.getInstrumentation().startActivitySync(
                new Intent(context, screen).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            waitFor(() -> !manager.isBusy(), 180000);
            List<DownloadJob> jobs = manager.jobs();
            assertEquals(2, jobs.size());
            for (DownloadJob job : jobs) {
                assertEquals(job.error, DownloadJob.Status.DONE, job.status);
                assertEquals(1, job.filesDone + job.filesExisting);
            }
            File destination = new File(prefs.getString("destination", ""));
            File[] completed = destination.listFiles(file -> file.getName().endsWith(".mp3"));
            assertNotNull(completed);
            assertEquals(2, completed.length);
            for (File file : completed) {
                assertTrue(file.length() > 0);
                assertTrue(file.getName().startsWith("001 - ") || file.getName().startsWith("002 - "));
                prefs.edit().putLong(file.getName(), file.lastModified()).commit();
            }
            assertEquals(2, DownloadHistoryDb.get(context).count(destination.getAbsolutePath()));
            assertTrue(new DownloadQueueStore(context.getFilesDir()).load().isEmpty());
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(activity::finish);
        }
    }

    @Test public void anotherLaunchDoesNotRepeatCompletedOrCancelledJobs() throws Exception {
        DownloadManager manager = DownloadManager.get(context);
        assertTrue(manager.jobs().isEmpty());
        assertFalse(manager.isBusy());
        manager.resumePending();
        assertFalse(manager.isBusy());
        File destination = new File(prefs.getString("destination", ""));
        assertEquals(2, DownloadHistoryDb.get(context).count(destination.getAbsolutePath()));
        File[] completed = destination.listFiles(file -> file.getName().endsWith(".mp3"));
        assertNotNull(completed);
        assertEquals(2, completed.length);
        for (File file : completed) assertEquals(prefs.getLong(file.getName(), -1), file.lastModified());
    }
}
