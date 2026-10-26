package cl.arichard.descargador;

import android.app.Application;
import android.os.Bundle;
import android.os.Looper;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import cl.arichard.descargador.data.PlaylistManager;
import cl.arichard.descargador.model.Song;
import cl.arichard.descargador.player.PlayerManager;
import cl.arichard.descargador.util.SongActions;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;
import java.io.File;
import java.nio.file.Files;
import java.util.Collections;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class, qualifiers = "es")
public class SongActionsTest {
    public static class Host extends AppCompatActivity {
        @Override protected void onCreate(Bundle state) { setTheme(R.style.Theme_MusicBox); super.onCreate(state); }
    }
    private ActivityController<Host> controller;
    private Host activity;
    private Song song;
    private File source;
    @Before public void setup() throws Exception {
        controller = Robolectric.buildActivity(Host.class).setup(); activity = controller.get();
        source = new File(activity.getFilesDir(), "sample.mp3");
        Files.write(source.toPath(), new byte[]{1, 2, 3, 4});
        song = new Song(1, "Sample", "Artist", "", 0, source.getAbsolutePath(), "Source", 0);
        PlayerManager.getInstance().init(activity);
        PlaylistManager.getInstance(activity).createPlaylist("Test collection");
        PlaylistManager.getInstance(activity).addSongToPlaylist("Test collection", song.getPath());
    }
    @After public void close() { controller.pause().stop().destroy(); }
    private void finishWork() throws Exception {
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        DescargadorApp.IO.submit(() -> {}).get(20, TimeUnit.SECONDS);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }
    private AlertDialog dialog() { return (AlertDialog) ShadowDialog.getLatestDialog(); }

    @Test public void successfulDeletionRequiresConfirmationAndRemovesMembership() throws Exception {
        AtomicBoolean changed = new AtomicBoolean();
        SongActions.delete(activity, song, () -> changed.set(true));
        assertTrue(source.exists()); assertFalse(changed.get());
        dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick(); finishWork();
        assertFalse(source.exists()); assertTrue(changed.get());
        assertFalse(PlaylistManager.getInstance(activity).getSongPaths("Test collection").contains(song.getPath()));
    }
    @Test public void failedDeletionKeepsPlaylistMembershipAndDoesNotReportSuccess() throws Exception {
        assertTrue(source.delete()); AtomicBoolean changed = new AtomicBoolean();
        SongActions.delete(activity, song, () -> changed.set(true));
        dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick(); finishWork();
        assertFalse(changed.get());
        assertTrue(PlaylistManager.getInstance(activity).getSongPaths("Test collection").contains(song.getPath()));
    }
}
