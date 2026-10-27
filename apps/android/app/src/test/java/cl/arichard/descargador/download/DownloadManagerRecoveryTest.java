package cl.arichard.descargador.download;

import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.lang.reflect.Constructor;
import java.util.Arrays;
import java.util.Collections;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class DownloadManagerRecoveryTest {
    private Context context;
    private DownloadQueueStore store;

    @Before public void setup() throws Exception {
        context = RuntimeEnvironment.getApplication();
        store = new DownloadQueueStore(context.getFilesDir());
        store.save(Arrays.asList(job("first"), job("second")));
    }

    private DownloadJob job(String id) {
        return new DownloadJob(id, "https://youtu.be/" + id,
                new DownloadOptions(true, "192 kbps", false, "", "", context.getCacheDir().getAbsolutePath()), 1, 1);
    }

    private DownloadManager reopen() throws Exception {
        Constructor<DownloadManager> constructor = DownloadManager.class.getDeclaredConstructor(Context.class);
        constructor.setAccessible(true);
        return constructor.newInstance(context);
    }

    @Test public void loadingPendingWorkDoesNotStartItInTheBackground() throws Exception {
        DownloadManager manager = reopen();
        assertTrue(manager.isBusy());
        assertEquals(2, manager.jobs().size());
        for (DownloadJob job : manager.jobs()) assertEquals(DownloadJob.Status.WAITING, job.status);
    }

    @Test public void cancellingOneRestoredJobIsDurable() throws Exception {
        DownloadManager manager = reopen();
        manager.cancel("first");
        DownloadManager restored = reopen();
        assertEquals(1, restored.jobs().size());
        assertEquals("second", restored.jobs().get(0).id);
    }

    @Test public void cancellingAllRestoredJobsPreventsAutomaticRestart() throws Exception {
        DownloadManager manager = reopen();
        manager.cancelAll();
        DownloadManager restored = reopen();
        assertTrue(restored.jobs().isEmpty());
        assertFalse(restored.isBusy());
        restored.resumePending();
        assertFalse(restored.isBusy());
    }

    @Test public void completedDownloadsDoNotReturnAfterClearingTheList() throws Exception {
        DownloadJob completed = job("completed");
        completed.status = DownloadJob.Status.DONE;
        store.save(Collections.singletonList(completed));
        DownloadManager manager = reopen();
        manager.clearFinished();
        assertTrue(reopen().jobs().isEmpty());
    }

    @Test public void unwritableQueueDoesNotAcceptWorkThatWouldBeLost() throws Exception {
        java.io.File blocker = new java.io.File(context.getCacheDir(), "queue-blocker");
        assertTrue(blocker.createNewFile());
        context = new ContextWrapper(context) {
            @Override public java.io.File getFilesDir() { return new java.io.File(blocker, "files"); }
        };
        DownloadManager manager = reopen();
        assertEquals(0, manager.enqueue(Collections.singletonList("https://youtu.be/not-started"), job("options").options));
        assertFalse(manager.isBusy());
        assertTrue(manager.jobs().isEmpty());
        assertTrue(manager.log().stream().anyMatch(line -> line.level.equals("error")));
    }
}
