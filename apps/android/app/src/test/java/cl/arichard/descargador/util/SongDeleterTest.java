package cl.arichard.descargador.util;

import android.app.Application;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class, qualifiers = "es")
public class SongDeleterTest {
    @Test public void bytesAreShownInTheRightUnit() {
        assertEquals("512 B", SongDeleter.formatBytes(512));
        assertEquals("820 KB", SongDeleter.formatBytes(820 * 1024));
        assertEquals("1,5 MB", SongDeleter.formatBytes((long) (1.5 * 1024 * 1024)));
        assertEquals("25 MB", SongDeleter.formatBytes(25L * 1024 * 1024));
        assertEquals("2,0 GB", SongDeleter.formatBytes(2L * 1024 * 1024 * 1024));
    }

    @Test public void onlyFilesInsideTheAppFoldersAreDeletedWithoutAsking() {
        List<String> dirs = Arrays.asList("/data/user/0/cl.app", "/storage/emulated/0/Android/data/cl.app");
        assertTrue(SongDeleter.isAppOwned("/storage/emulated/0/Android/data/cl.app/files/Music/a.mp3", dirs));
        assertTrue(SongDeleter.isAppOwned("/data/user/0/cl.app/files/x.mp3", dirs));
        assertFalse(SongDeleter.isAppOwned("/storage/emulated/0/Music/a.mp3", dirs));
        assertFalse(SongDeleter.isAppOwned("/storage/emulated/0/Android/data/cl.app2/a.mp3", dirs));
        assertFalse(SongDeleter.isAppOwned(null, dirs));
    }
}
