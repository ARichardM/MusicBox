package cl.arichard.descargador.data;

import android.app.Application;
import cl.arichard.descargador.model.Song;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class ClickStatsSyncThrottleTest {
    @Test
    public void listeningSamplesDoNotTriggerCloudSyncUntilFlushOrQualifiedPlay() {
        var context = RuntimeEnvironment.getApplication();
        ClickStats stats = ClickStats.getInstance(context);
        AtomicInteger cloudUploads = new AtomicInteger();
        stats.setChangeListener(cloudUploads::incrementAndGet);
        Song song = new Song(0, "Throttle Test", "Artist", "Album", 180_000, "youtube:throttle123", "", 0);
        try {
            // Drain any prior dirty state from previous tests sharing singleton.
            stats.flushListening();
            cloudUploads.set(0);

            for (int i = 0; i < 10; i++) {
                stats.recordListening(song, "", 5_000L, false);
            }
            assertEquals("Periodic listening samples must not fire Firestore syncs", 0, cloudUploads.get());

            stats.flushListening();
            assertEquals("Flushing dirty listening state notifies cloud sync once", 1, cloudUploads.get());

            stats.flushListening();
            assertEquals("Flushing clean listening state is a no-op", 1, cloudUploads.get());

            stats.recordQualifiedPlay(song, "");
            assertEquals("Qualified 30s play triggers cloud sync", 2, cloudUploads.get());
        } finally {
            stats.setChangeListener(null);
        }
    }
}
