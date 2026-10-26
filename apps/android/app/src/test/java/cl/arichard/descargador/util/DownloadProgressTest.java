package cl.arichard.descargador.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class DownloadProgressTest {
    @Test public void startsDeterminateNotAtZero() {
        assertTrue(new DownloadProgress().current() > 0f);
    }

    @Test public void downloadPercentFillsTheMiddleStretch() {
        DownloadProgress progress = new DownloadProgress();
        float early = progress.update(10f, "[download]  10.0% of 4.00MiB");
        float late = progress.update(90f, "[download]  90.0% of 4.00MiB");
        assertTrue(early > DownloadProgress.PREPARED);
        assertTrue(late > early && late < DownloadProgress.DOWNLOADED);
        assertEquals(DownloadProgress.DOWNLOADED, progress.update(100f, "[download] 100% of 4.00MiB"), 0.001f);
    }

    @Test public void postProcessingStagesKeepAdvancingAfterTheDownloadEnds() {
        DownloadProgress progress = new DownloadProgress();
        progress.update(100f, "[download] 100% of 4.00MiB");
        float converted = progress.update(100f, "[ExtractAudio] Destination: a.mp3");
        float tagged = progress.update(100f, "[Metadata] Adding metadata");
        float cover = progress.update(100f, "[EmbedThumbnail] ffmpeg: Adding thumbnail");
        assertTrue(converted > DownloadProgress.DOWNLOADED);
        assertTrue(tagged > converted);
        assertTrue(cover > tagged && cover < 1f);
    }

    @Test public void progressNeverGoesBackwardsAndNeverReachesOne() {
        DownloadProgress progress = new DownloadProgress();
        float high = progress.update(100f, "[EmbedThumbnail] ffmpeg");
        assertEquals(high, progress.update(5f, "[download]  5.0% of a thumbnail"), 0.0f);
        assertTrue(progress.update(100f, "[EmbedThumbnail] x") < 1f);
    }
}
