package cl.arichard.descargador.download;

import android.app.Application;
import org.junit.Before;
import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class DownloadQueueStoreTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private DownloadQueueStore store;

    @Before public void setup() {
        store = new DownloadQueueStore(temporary.getRoot());
    }

    private DownloadJob job(String id, DownloadJob.Status status) {
        DownloadJob job = new DownloadJob(id, "https://youtube.com/playlist?list=" + id,
                new DownloadOptions(true, "320 kbps", true, "1,3-5", "/private/cookies.txt", "/music/Álbum"), 2, 4);
        job.status = status;
        job.title = "Canción " + id;
        return job;
    }

    @Test public void emptyQueueNeedsNoExistingFile() throws Exception {
        assertTrue(store.load().isEmpty());
    }

    @Test public void interruptedJobsRestoreInOrderWithOriginalOptionsAndNumbering() throws Exception {
        DownloadJob active = job("active", DownloadJob.Status.PROCESSING);
        active.progress = .75f;
        active.speed = "1 MB/s";
        active.filesDone = 2;
        store.save(Arrays.asList(active, job("waiting", DownloadJob.Status.WAITING)));
        List<DownloadJob> restored = new DownloadQueueStore(temporary.getRoot()).load();
        assertEquals(2, restored.size());
        DownloadJob recovered = restored.get(0);
        assertEquals("active", recovered.id);
        assertEquals("waiting", restored.get(1).id);
        assertEquals(active.url, recovered.url);
        assertEquals(active.title, recovered.title);
        assertEquals(DownloadJob.Status.WAITING, recovered.status);
        assertEquals(0f, recovered.progress, 0f);
        assertEquals("", recovered.speed);
        assertEquals(0, recovered.filesDone);
        assertEquals(active.options.outputTemplate(2, 4), recovered.options.outputTemplate(recovered.position, recovered.batchSize));
        assertTrue(recovered.options.mp3);
        assertTrue(recovered.options.playlist);
        assertEquals("320 kbps", recovered.options.quality);
        assertEquals("1,3-5", recovered.options.items);
        assertEquals("/private/cookies.txt", recovered.options.cookies);
    }

    @Test public void completedFailedAndCancelledJobsNeverRestart() throws Exception {
        List<DownloadJob> jobs = new java.util.ArrayList<>();
        for (DownloadJob.Status status : DownloadJob.Status.values()) jobs.add(job(status.name(), status));
        DownloadJob cancelling = job("cancelRequested", DownloadJob.Status.DOWNLOADING);
        cancelling.cancelRequested = true;
        jobs.add(cancelling);
        store.save(jobs);
        List<DownloadJob> restored = store.load();
        assertEquals(4, restored.size());
        for (DownloadJob job : restored) assertTrue(Arrays.asList("WAITING", "PREPARING", "DOWNLOADING", "PROCESSING").contains(job.id));
    }

    @Test public void clearingPendingJobsPreventsTheirReturnOnNextLaunch() throws Exception {
        store.save(Collections.singletonList(job("old", DownloadJob.Status.WAITING)));
        store.save(Collections.emptyList());
        assertTrue(new DownloadQueueStore(temporary.getRoot()).load().isEmpty());
    }

    @Test public void interruptedAtomicWriteRecoversPreviousQueue() throws Exception {
        store.save(Collections.singletonList(job("original", DownloadJob.Status.WAITING)));
        File base = new File(temporary.getRoot(), "download-queue.json");
        Files.move(base.toPath(), new File(base + ".bak").toPath());
        Files.write(base.toPath(), "{incomplete".getBytes(StandardCharsets.UTF_8));
        assertEquals("original", store.load().get(0).id);
    }

    @Test public void mp4AndIndividualLinksKeepTheirOwnSettings() throws Exception {
        DownloadOptions options = new DownloadOptions(false, "720p", false, "", "", "/videos");
        store.save(Collections.singletonList(new DownloadJob("video", "https://youtu.be/example", options, 1, 1)));
        DownloadJob restored = store.load().get(0);
        assertFalse(restored.options.mp3);
        assertFalse(restored.options.playlist);
        assertEquals("720p", restored.options.quality);
        assertEquals(options.outputTemplate(1, 1), restored.options.outputTemplate(restored.position, restored.batchSize));
    }

    @Test(expected = IOException.class) public void corruptQueueIsReportedWithoutOverwritingIt() throws Exception {
        File base = new File(temporary.getRoot(), "download-queue.json");
        Files.write(base.toPath(), "broken".getBytes(StandardCharsets.UTF_8));
        store.load();
    }

    @Test(expected = IOException.class) public void unsupportedVersionIsReported() throws Exception {
        Files.write(new File(temporary.getRoot(), "download-queue.json").toPath(),
                "{\"version\":2,\"jobs\":[]}".getBytes(StandardCharsets.UTF_8));
        store.load();
    }
}
