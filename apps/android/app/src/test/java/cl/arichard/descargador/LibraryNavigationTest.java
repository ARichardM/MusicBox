package cl.arichard.descargador;

import android.app.Application;
import android.content.Context;
import android.os.Looper;
import android.widget.EditText;
import android.widget.TextView;
import androidx.recyclerview.widget.RecyclerView;
import cl.arichard.descargador.data.PlaylistManager;
import cl.arichard.descargador.model.Song;
import cl.arichard.descargador.player.PlayerManager;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import java.io.File;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class, qualifiers = "es")
public class LibraryNavigationTest {
    private Context context;
    @Before public void setup() throws Exception {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("musicbox_prefs", 0).edit().putBoolean("all_permissions_requested_v1", true).commit();
        context.getSharedPreferences("player", 0).edit().clear().commit();
        PlayerManager.getInstance().init(context);
        File folder = new File(context.getExternalFilesDir(android.os.Environment.DIRECTORY_MUSIC), "Viajes");
        folder.mkdirs();
        File songFile = new File(folder, "Recuerdo.mp3"); songFile.createNewFile();
        PlaylistManager manager = PlaylistManager.getInstance(context);
        manager.createPlaylist("En camino"); manager.addSongToPlaylist("En camino", songFile.getAbsolutePath());
    }
    private void finishScan() throws Exception {
        for (int i = 0; i < 3; i++) {
            DescargadorApp.IO.submit(() -> {}).get(20, TimeUnit.SECONDS);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
        }
    }
    @Test public void reopeningKeepsPlaylistAndSearchInsteadOfReturningToAllSongs() throws Exception {
        context.getSharedPreferences("library_navigation", 0).edit().putInt("mode", 3)
                .putString("sublist", "En camino").putString("query", "Recuerdo").putBoolean("search_tab", false).commit();
        ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup();
        finishScan();
        MainActivity activity = controller.get();
        assertEquals("En camino", ((TextView) activity.findViewById(R.id.txtLibraryTitle)).getText().toString());
        assertEquals("Recuerdo", ((EditText) activity.findViewById(R.id.inputSearchLibrary)).getText().toString());
        assertEquals(1, ((RecyclerView) activity.findViewById(R.id.recyclerLibrary)).getAdapter().getItemCount());
        controller.pause().stop().destroy();
        assertEquals("En camino", context.getSharedPreferences("library_navigation", 0).getString("sublist", ""));
        ActivityController<MainActivity> reopened = Robolectric.buildActivity(MainActivity.class).setup();
        finishScan();
        assertEquals("En camino", ((TextView) reopened.get().findViewById(R.id.txtLibraryTitle)).getText().toString());
        reopened.pause().stop().destroy();
    }
    @Test public void returningFromFolderKeepsFolderMode() throws Exception {
        context.getSharedPreferences("library_navigation", 0).edit().putInt("mode", 2)
                .putString("sublist", "Viajes").putBoolean("search_tab", false).commit();
        ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup();
        finishScan(); MainActivity activity = controller.get();
        assertEquals("Viajes", ((TextView) activity.findViewById(R.id.txtLibraryTitle)).getText().toString());
        activity.findViewById(R.id.btnBackLibrary).performClick();
        assertTrue(((RecyclerView) activity.findViewById(R.id.recyclerLibrary)).getAdapter()
                instanceof cl.arichard.descargador.adapter.FolderAdapter);
        controller.pause().stop().destroy();
        assertEquals("", context.getSharedPreferences("library_navigation", 0).getString("sublist", ""));
        assertEquals(2, context.getSharedPreferences("library_navigation", 0).getInt("mode", -1));
    }

    @Test public void mostPlayedPlaylistShortcutOpensItsSongsAndKeepsPlaylistMode() throws Exception {
        assertCollectionShortcut("En camino", 3);
    }

    @Test public void mostPlayedFolderShortcutOpensItsSongsAndKeepsFolderMode() throws Exception {
        assertCollectionShortcut("Viajes", 2);
    }

    private void assertCollectionShortcut(String name, int expectedMode) throws Exception {
        Song song = cl.arichard.descargador.data.MusicScanner.scanAllMusic(context).get(0);
        cl.arichard.descargador.data.ClickStats.getInstance(context).record(song);
        ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup();
        try {
            finishScan();
            MainActivity activity = controller.get();
            java.lang.reflect.Method populate = MainActivity.class.getDeclaredMethod("populateTopCollections");
            populate.setAccessible(true);
            populate.invoke(activity);
            android.widget.LinearLayout cards = activity.findViewById(R.id.layoutTopCollections);
            android.view.View target = null;
            for (int index = 0; index < cards.getChildCount(); index++) {
                android.view.View card = cards.getChildAt(index);
                if (name.equals(((TextView) card.findViewById(R.id.txtFolderName)).getText().toString())) target = card;
            }
            assertNotNull("Collection shortcut is visible", target);
            target.performClick();
            assertEquals(name, ((TextView) activity.findViewById(R.id.txtLibraryTitle)).getText().toString());
            assertEquals(1, ((RecyclerView) activity.findViewById(R.id.recyclerLibrary)).getAdapter().getItemCount());
            java.lang.reflect.Field mode = MainActivity.class.getDeclaredField("currentLibraryMode");
            mode.setAccessible(true);
            assertEquals(expectedMode, mode.getInt(activity));
        } finally {
            controller.pause().stop().destroy();
        }
    }
}
