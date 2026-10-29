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
    @Test public void verificationBuildDoesNotRequireARealAccount() {
        assertTrue(MainActivity.isVerificationPackage("cl.arichard.descargador.verification"));
        assertFalse(MainActivity.isVerificationPackage("cl.arichard.descargador.pruebas"));
    }
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

    @Test public void artistPickerFiltersShuffleAndRestoresSelectionWithoutEditingPlaylist() throws Exception {
        ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup();
        try {
            finishScan();
            MainActivity activity = controller.get();
            java.util.List<Song> songs = new java.util.ArrayList<>();
            PlaylistManager manager = PlaylistManager.getInstance(context);
            manager.createPlaylist("Artistas de prueba");
            for (int i = 0; i < 3; i++) {
                File file = new File(context.getFilesDir(), "artista-" + i + ".mp3");
                file.createNewFile();
                org.robolectric.shadows.ShadowMediaPlayer.addMediaInfo(
                        org.robolectric.shadows.util.DataSource.toDataSource(file.getAbsolutePath()),
                        new org.robolectric.shadows.ShadowMediaPlayer.MediaInfo(20000, 0));
                songs.add(new Song(i, "Tema " + i, i == 1 ? "Zeta" : "Ana", "", 20000,
                        file.getAbsolutePath(), "Prueba", i));
                manager.addSongToPlaylist("Artistas de prueba", file.getAbsolutePath());
            }
            java.lang.reflect.Field library = MainActivity.class.getDeclaredField("allSongs");
            library.setAccessible(true); library.set(activity, songs);
            java.lang.reflect.Method open = MainActivity.class.getDeclaredMethod("showPlaylistSongs", String.class);
            open.setAccessible(true); open.invoke(activity, "Artistas de prueba");
            TextView picker = activity.findViewById(R.id.btnPlaylistArtist);
            assertEquals(android.view.View.VISIBLE, picker.getVisibility());
            picker.performClick();
            androidx.appcompat.app.AlertDialog dialog = (androidx.appcompat.app.AlertDialog)
                    org.robolectric.shadows.ShadowDialog.getLatestDialog();
            EditText artistSearch = dialog.findViewById(R.id.inputArtistSearch);
            RecyclerView artistChoices = dialog.findViewById(R.id.recyclerArtistChoices);
            assertNotNull(artistSearch); assertNotNull(artistChoices);
            artistSearch.setText("an");
            assertEquals(2, artistChoices.getAdapter().getItemCount()); // Todos + Ana
            artistChoices.measure(android.view.View.MeasureSpec.makeMeasureSpec(400, android.view.View.MeasureSpec.EXACTLY),
                    android.view.View.MeasureSpec.makeMeasureSpec(360, android.view.View.MeasureSpec.EXACTLY));
            artistChoices.layout(0, 0, 400, 360);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertNotNull(artistChoices.findViewHolderForAdapterPosition(1));
            artistChoices.findViewHolderForAdapterPosition(1).itemView.performClick();
            RecyclerView rows = activity.findViewById(R.id.recyclerLibrary);
            assertEquals(2, rows.getAdapter().getItemCount());
            assertTrue(picker.getText().toString().contains("Ana"));
            assertTrue(picker.isSelected());
            assertEquals(android.view.View.VISIBLE, activity.findViewById(R.id.btnClearPlaylistArtist).getVisibility());
            activity.findViewById(R.id.btnShuffleAll).performClick();
            assertEquals(2, PlayerManager.getInstance().getQueue().size());
            for (Song queued : PlayerManager.getInstance().getQueue()) assertEquals("Ana", queued.getArtist());
            assertEquals(3, manager.resolveSongs("Artistas de prueba", songs).size());
            assertEquals("Ana", context.getSharedPreferences("library_navigation", 0).getString("artist", ""));
            java.lang.reflect.Method restore = MainActivity.class.getDeclaredMethod("restoreLibraryNavigation");
            restore.setAccessible(true); restore.invoke(activity);
            assertEquals(2, rows.getAdapter().getItemCount());
            activity.findViewById(R.id.btnClearPlaylistArtist).performClick();
            assertEquals(3, rows.getAdapter().getItemCount());
            assertFalse(picker.isSelected());
            assertEquals(android.view.View.GONE, activity.findViewById(R.id.btnClearPlaylistArtist).getVisibility());
            activity.findViewById(R.id.btnBackLibrary).performClick();
            assertEquals(android.view.View.GONE, picker.getVisibility());
            open.invoke(activity, "Artistas de prueba");
            assertEquals(3, rows.getAdapter().getItemCount());
        } finally {
            PlayerManager.getInstance().stopAndRelease();
            controller.pause().stop().destroy();
        }
    }

    @Test public void playlistsAndFoldersExposeTheirTypeAndAdjacentNavigation() throws Exception {
        PlaylistManager manager = PlaylistManager.getInstance(context);
        manager.createPlaylist("Segunda lista");
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            finishScan(); MainActivity activity = controller.get();
            java.lang.reflect.Method open = MainActivity.class.getDeclaredMethod("showPlaylistSongs", String.class);
            open.setAccessible(true); open.invoke(activity, "En camino");
            TextView contextLabel = activity.findViewById(R.id.txtLibraryContext);
            assertEquals("PLAYLIST", contextLabel.getText().toString());
            assertEquals(android.view.View.VISIBLE, contextLabel.getVisibility());
            android.view.ViewParent ancestor = contextLabel.getParent();
            while (ancestor != null && ancestor != activity.findViewById(R.id.libraryHeader)) ancestor = ancestor.getParent();
            assertSame("The animated header owns the collection title", activity.findViewById(R.id.libraryHeader), ancestor);
            java.lang.reflect.Method adjacent = MainActivity.class.getDeclaredMethod("adjacentCollection", int.class);
            adjacent.setAccessible(true);
            java.util.List<String> names = manager.getPlaylistNames();
            int current = names.indexOf("En camino");
            assertTrue(current >= 0 && current + 1 < names.size());
            assertEquals(names.get(current + 1), adjacent.invoke(activity, 1));
            java.lang.reflect.Method show = MainActivity.class.getDeclaredMethod("showCollection", int.class, String.class);
            String last = names.get(names.size() - 1);
            show.setAccessible(true); show.invoke(activity, 3, last);
            assertEquals(last, ((TextView) activity.findViewById(R.id.txtLibraryTitle)).getText().toString());
            assertNull(adjacent.invoke(activity, 1));
            java.lang.reflect.Method folder = MainActivity.class.getDeclaredMethod("showFolderSongs", String.class, java.util.List.class);
            folder.setAccessible(true); folder.invoke(activity, "Viajes", java.util.Collections.emptyList());
            assertEquals("CARPETA", contextLabel.getText().toString());
            assertEquals("Viajes", ((TextView) activity.findViewById(R.id.txtLibraryTitle)).getText().toString());
        }
    }

    @Test @Config(qualifiers = "es-w320dp-h640dp-port-mdpi")
    public void compactArtistToolbarFitsSmallLightScreen() throws Exception {
        assertCompactArtistToolbar(320, 1f);
    }

    @Test @Config(qualifiers = "es-w360dp-h740dp-port-night-mdpi")
    public void compactArtistToolbarFitsDarkScreenWithLargeText() throws Exception {
        assertCompactArtistToolbar(360, 1.5f);
    }

    private void assertCompactArtistToolbar(int width, float fontScale) throws Exception {
        android.content.res.Configuration config = context.getResources().getConfiguration();
        config.fontScale = fontScale;
        context.getResources().updateConfiguration(config, context.getResources().getDisplayMetrics());
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            finishScan();
            MainActivity activity = controller.get();
            java.lang.reflect.Method open = MainActivity.class.getDeclaredMethod("showPlaylistSongs", String.class);
            open.setAccessible(true); open.invoke(activity, "En camino");
            java.lang.reflect.Method filter = MainActivity.class.getDeclaredMethod("applyPlaylistArtistFilter", String.class);
            filter.setAccessible(true);
            TextView picker = activity.findViewById(R.id.btnPlaylistArtist);
            android.view.View toolbar = activity.findViewById(R.id.layoutLibraryTools);
            for (String artist : new String[]{"", "Un artista con un nombre muy largo que debe recortarse"}) {
                filter.invoke(activity, artist);
                toolbar.measure(android.view.View.MeasureSpec.makeMeasureSpec(width - 32, android.view.View.MeasureSpec.EXACTLY),
                        android.view.View.MeasureSpec.makeMeasureSpec(48, android.view.View.MeasureSpec.EXACTLY));
                toolbar.layout(0, 0, width - 32, 48);
                android.view.View sort = activity.findViewById(R.id.btnLibrarySort);
                android.view.View auto = activity.findViewById(R.id.layoutAutoDownloadPill);
                assertTrue("Artist follows sort", picker.getLeft() >= sort.getRight());
                assertTrue("Artist has usable width", picker.getWidth() >= 48);
                assertTrue("Artist does not overlap auto download", picker.getRight() <= auto.getLeft());
                assertTrue("Toolbar stays within screen", auto.getRight() <= toolbar.getWidth());
                assertEquals("Touch target keeps its height", 48, picker.getHeight());
                assertEquals(android.text.TextUtils.TruncateAt.END, picker.getEllipsize());
                assertEquals(!artist.isEmpty(), picker.isSelected());
                assertTrue(picker.getContentDescription().toString().contains(artist));
            }
        }
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
