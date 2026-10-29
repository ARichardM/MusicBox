package cl.arichard.descargador;

import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;
import android.widget.TextView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.Until;
import cl.arichard.descargador.data.PlaylistManager;
import cl.arichard.descargador.model.Song;
import cl.arichard.descargador.player.PlayerManager;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Prueba táctil offline con audio sintético; exclusivamente en verification. */
@RunWith(AndroidJUnit4.class)
public class PlaylistArtistDeviceTest {
    private static final String PLAYLIST = "Verificación del filtro de artista";
    private static final String PLAYLIST_NEXT = "Verificación navegación lateral";
    private <T> T main(Supplier<T> action) {
        AtomicReference<T> result = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> result.set(action.get()));
        return result.get();
    }
    private void waitFor(Supplier<Boolean> condition) throws Exception {
        long end = System.currentTimeMillis() + 20000;
        while (!condition.get() && System.currentTimeMillis() < end) Thread.sleep(100);
        assertTrue("Expected artist filter state", condition.get());
    }
    private static Song sample(Context context, int n, String title, String artist) throws Exception {
        File file = new File(context.getFilesDir(), "artist-verification-" + n + ".wav");
        int bytes = 16000 * 2 * 20;
        ByteBuffer wav = ByteBuffer.allocate(44 + bytes).order(ByteOrder.LITTLE_ENDIAN);
        wav.put(new byte[]{'R','I','F','F'}).putInt(36 + bytes).put(new byte[]{'W','A','V','E','f','m','t',' '});
        wav.putInt(16).putShort((short) 1).putShort((short) 1).putInt(16000).putInt(32000);
        wav.putShort((short) 2).putShort((short) 16).put(new byte[]{'d','a','t','a'}).putInt(bytes);
        try (FileOutputStream output = new FileOutputStream(file)) { output.write(wav.array()); }
        return new Song(n, title, artist, "Demo", 20000, file.getPath(), "Verificación", n);
    }
    @Test public void lightArtistFilterControlsOnlyItsPlaylistQueue() throws Exception { verify(false); }
    @Test public void darkArtistFilterControlsOnlyItsPlaylistQueue() throws Exception { verify(true); }

    private void chooseArtist(UiDevice device, String pkg, String artist) {
        device.findObject(By.res(pkg, "btnPlaylistArtist")).click();
        assertNotNull("Artist search is visible", device.wait(Until.findObject(By.res(pkg, "inputArtistSearch")), 10000));
        device.findObject(By.res(pkg, "inputArtistSearch")).setText(artist.substring(0, Math.min(3, artist.length())));
        androidx.test.uiautomator.BySelector choice = By.res(pkg, "txtArtistChoice").text(artist);
        assertNotNull("Artist choice is visible", device.wait(Until.findObject(choice), 10000));
        device.findObject(choice).click();
    }

    @SuppressWarnings("unchecked") private Bitmap avatar(Context context, String artist, int color) throws Exception {
        Bitmap photo = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(photo);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        canvas.drawColor(color); paint.setColor(android.graphics.Color.WHITE);
        canvas.drawCircle(24, 17, 8, paint); canvas.drawOval(9, 28, 39, 54, paint);
        String url = "https://example.invalid/verification-avatar-" + artist;
        context.getSharedPreferences("artist_avatar_urls", 0).edit().putString(artist, url).commit();
        Field cache = cl.arichard.descargador.util.ImageLoader.class.getDeclaredField("memoryCache");
        cache.setAccessible(true);
        ((android.util.LruCache<String, Bitmap>) cache.get(cl.arichard.descargador.util.ImageLoader.getInstance())).put(url, photo);
        return photo;
    }

    private boolean hasAvatar(MainActivity activity, Bitmap photo) {
        android.graphics.drawable.Drawable drawable = ((TextView) activity.findViewById(R.id.btnPlaylistArtist)).getCompoundDrawablesRelative()[0];
        return drawable instanceof androidx.core.graphics.drawable.RoundedBitmapDrawable
                && ((androidx.core.graphics.drawable.RoundedBitmapDrawable) drawable).getBitmap() == photo;
    }

    @SuppressWarnings("unchecked") private void verify(boolean dark) throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertEquals("Isolated package required", "cl.arichard.descargador.verification", context.getPackageName());
        Bitmap anaPhoto = avatar(context, "ana", 0xff8855bb);
        Bitmap zetaPhoto = avatar(context, "zeta", 0xff008877);
        context.getSharedPreferences("musicbox_prefs", 0).edit().putBoolean("dark_mode", dark)
                .putBoolean("all_permissions_requested_v1", true).commit();
        main(() -> { androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(dark
                ? androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES : androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO); return true; });
        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        androidx.test.uiautomator.Configurator.getInstance().setWaitForIdleTimeout(0).setWaitForSelectorTimeout(2000);
        device.wakeUp(); device.pressHome();
        MainActivity activity = (MainActivity) InstrumentationRegistry.getInstrumentation().startActivitySync(
                new Intent(context, PreviewMainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        PlaylistManager playlists = PlaylistManager.getInstance(context);
        try {
            waitFor(() -> main(() -> new androidx.lifecycle.ViewModelProvider(activity).get(MainUiState.class).libraryLoaded));
            Thread.sleep(2500);
            List<Song> samples = Arrays.asList(sample(context, 1, "Primera", "Ana"),
                    sample(context, 2, "Otra", "Zeta"), sample(context, 3, "Segunda", "Ana"));
            playlists.createPlaylist(PLAYLIST);
            playlists.createPlaylist(PLAYLIST_NEXT);
            for (Song song : samples) playlists.addSongToPlaylist(PLAYLIST, song.getPath());
            playlists.addSongToPlaylist(PLAYLIST_NEXT, samples.get(1).getPath());
            Field library = MainActivity.class.getDeclaredField("allSongs"); library.setAccessible(true);
            Method open = MainActivity.class.getDeclaredMethod("showPlaylistSongs", String.class); open.setAccessible(true);
            main(() -> { try {
                library.set(activity, new ArrayList<>(samples));
                activity.findViewById(R.id.navTabLibrary).performClick(); open.invoke(activity, PLAYLIST);
            } catch (Exception error) { throw new AssertionError(error); } return true; });
            String pkg = context.getPackageName();
            assertNotNull(device.wait(Until.findObject(By.res(pkg, "btnPlaylistArtist")), 10000));
            chooseArtist(device, pkg, "Ana");
            waitFor(() -> main(() -> ((TextView) activity.findViewById(R.id.btnPlaylistArtist)).isSelected()));
            waitFor(() -> main(() -> hasAvatar(activity, anaPhoto)));
            chooseArtist(device, pkg, "Zeta");
            waitFor(() -> main(() -> hasAvatar(activity, zetaPhoto)));
            chooseArtist(device, pkg, "Ana");
            waitFor(() -> main(() -> hasAvatar(activity, anaPhoto)));
            Rect sort = new Rect(), artist = new Rect();
            main(() -> { activity.findViewById(R.id.btnLibrarySort).getGlobalVisibleRect(sort);
                activity.findViewById(R.id.btnPlaylistArtist).getGlobalVisibleRect(artist); return true; });
            assertTrue("Filter is right of sort", artist.left >= sort.right);
            assertTrue(artist.width() > 0);
            main(() -> {
                androidx.core.view.WindowInsetsCompat insets = androidx.core.view.ViewCompat.getRootWindowInsets(activity.findViewById(R.id.mainGestureRoot));
                Rect shuffle = new Rect(); activity.findViewById(R.id.btnShuffleAll).getGlobalVisibleRect(shuffle);
                assertNotNull(insets);
                assertTrue("Shuffle remains below status bar", shuffle.top >= insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.statusBars()).top);
                return true;
            });
            device.findObject(By.res(pkg, "btnShuffleAll")).click();
            waitFor(() -> main(() -> PlayerManager.getInstance().getQueue().size() == 2));
            for (Song queued : main(() -> PlayerManager.getInstance().getQueue())) assertEquals("Ana", queued.getArtist());
            waitFor(() -> main(() -> PlayerManager.getInstance().isPlaying()));
            assertEquals(3, playlists.resolveSongs(PLAYLIST, samples).size());
            assertTrue(device.takeScreenshot(new File(context.getExternalFilesDir(null), "artist-" + (dark ? "dark" : "light") + ".png")));
            device.findObject(By.res(pkg, "btnClearPlaylistArtist")).click();
            waitFor(() -> main(() -> !activity.findViewById(R.id.btnPlaylistArtist).isSelected()));
            assertFalse(main(() -> hasAvatar(activity, anaPhoto)));
            assertEquals(2, (int) main(() -> PlayerManager.getInstance().getQueue().size()));

            List<String> names = playlists.getPlaylistNames();
            int current = names.indexOf(PLAYLIST);
            int direction = current + 1 < names.size() ? 1 : -1;
            String destination = names.get(current + direction);
            android.graphics.Rect page = device.findObject(By.res(pkg, "libraryContentSwipe")).getVisibleBounds();
            int startX = direction > 0 ? page.left + page.width() * 4 / 5 : page.left + page.width() / 5;
            int endX = direction > 0 ? page.left + page.width() / 5 : page.left + page.width() * 4 / 5;
            assertTrue("Collection swipe is injected", device.swipe(startX, page.centerY(), endX, page.centerY(), 18));
            assertNotNull("Adjacent playlist opens", device.wait(Until.findObject(
                    By.text(cl.arichard.descargador.util.Tr.name(destination))), 10000));
            assertEquals(cl.arichard.descargador.util.Tr.t("Playlist").toUpperCase(java.util.Locale.getDefault()),
                    main(() -> ((TextView) activity.findViewById(R.id.txtLibraryContext)).getText().toString()));
        } catch (AssertionError error) {
            device.takeScreenshot(new File(context.getExternalFilesDir(null), "artist-failure-" + (dark ? "dark" : "light") + ".png"));
            throw error;
        } finally {
            main(() -> { PlayerManager.getInstance().stopAndRelease(); activity.finish(); return true; });
            playlists.deletePlaylist(PLAYLIST);
            playlists.deletePlaylist(PLAYLIST_NEXT);
            context.getSharedPreferences("artist_avatar_urls", 0).edit().remove("ana").remove("zeta").apply();
        }
    }
}
