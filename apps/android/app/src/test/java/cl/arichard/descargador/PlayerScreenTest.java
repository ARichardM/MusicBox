package cl.arichard.descargador;

import android.app.Application;
import android.app.Dialog;
import android.content.Context;
import android.widget.TextView;
import cl.arichard.descargador.model.Song;
import cl.arichard.descargador.data.PlaylistManager;
import cl.arichard.descargador.player.PlayerManager;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import org.json.JSONArray;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;
import java.io.File;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class, qualifiers = "es")
public class PlayerScreenTest {
    @Test public void fullPlayerShowsActionsAndOpensEditableQueueWithoutStartingPlayback() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        File file = new File(context.getFilesDir(), "Song.mp3"); file.createNewFile();
        Song song = new Song(0, "Canción", "Artista", "Álbum", 20000, file.getAbsolutePath(), "Música", 0);
        context.getSharedPreferences("player", 0).edit().clear().putString("queue", new JSONArray().put(song.toJson()).toString())
                .putString("current", song.getPath()).putInt("repeat", PlayerManager.REPEAT_OFF).commit();
        PlayerManager.getInstance().init(context);
        ActivityController<NowPlayingActivity> controller = Robolectric.buildActivity(NowPlayingActivity.class).setup();
        NowPlayingActivity activity = controller.get();
        assertNotNull(activity.findViewById(R.id.btnPlayerMore));
        assertNotNull(activity.findViewById(R.id.btnNowPlayingFavorite));
        activity.findViewById(R.id.btnNowPlayingFavorite).performClick();
        assertTrue(PlaylistManager.getInstance(context).isFavorite(song.getPath()));
        assertEquals(android.view.View.GONE, activity.findViewById(R.id.tabSelectorMp4).getVisibility());
        activity.findViewById(R.id.btnRepeat).performClick();
        assertEquals("Repetir lista", ((TextView) activity.findViewById(R.id.txtRepeatMode)).getText().toString());
        activity.findViewById(R.id.btnRepeat).performClick();
        assertEquals("Repetir canción", ((TextView) activity.findViewById(R.id.txtRepeatMode)).getText().toString());
        cl.arichard.descargador.player.QueueSheet.show(activity);
        Dialog dialog = ShadowDialog.getLatestDialog();
        assertTrue(dialog instanceof BottomSheetDialog); assertTrue(dialog.isShowing());
        assertFalse(PlayerManager.getInstance().isPlaying());
        dialog.dismiss(); controller.pause().stop().destroy();
    }
}
