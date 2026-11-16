package cl.arichard.descargador;

import android.content.Context;
import android.content.Intent;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.UiDevice;
import cl.arichard.descargador.model.Song;
import cl.arichard.descargador.player.PlayerManager;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Real MediaPlayer overlap between two private synthetic tones. */
@RunWith(AndroidJUnit4.class)
public class CrossfadeDeviceTest {
    private <T> T main(Supplier<T> action) {
        AtomicReference<T> value = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> value.set(action.get()));
        return value.get();
    }
    private Song tone(Context context, String name, int hz) throws Exception {
        int rate = 16000, bytes = rate * 2 * 12;
        ByteBuffer wav = ByteBuffer.allocate(44 + bytes).order(ByteOrder.LITTLE_ENDIAN);
        wav.put(new byte[]{'R','I','F','F'}).putInt(36 + bytes).put(new byte[]{'W','A','V','E','f','m','t',' '});
        wav.putInt(16).putShort((short) 1).putShort((short) 1).putInt(rate).putInt(rate * 2);
        wav.putShort((short) 2).putShort((short) 16).put(new byte[]{'d','a','t','a'}).putInt(bytes);
        for (int i = 0; i < rate * 12; i++) wav.putShort((short) (Math.sin(2 * Math.PI * hz * i / rate) * 600));
        File file = new File(context.getFilesDir(), "crossfade-" + name + ".wav");
        try (FileOutputStream output = new FileOutputStream(file)) { output.write(wav.array()); }
        return new Song(0, name, "Verification", "Synthetic", 12000, file.getPath(), "Verification", 0);
    }
    private static Object field(PlayerManager player, String name) throws Exception {
        Field field = PlayerManager.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(player);
    }
    @Test public void realPlayersOverlapBeforeTheFirstTrackEndsAndReleaseAfterTheFade() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertEquals("cl.arichard.descargador.verification", context.getPackageName());
        context.getSharedPreferences("musicbox_prefs", 0).edit().putBoolean("all_permissions_requested_v1", true).commit();
        context.getSharedPreferences("player", 0).edit().putBoolean("autoplay", false).commit();
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).wakeUp();
        InstrumentationRegistry.getInstrumentation().startActivitySync(
                new Intent(context, PreviewMainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        PlayerManager player = PlayerManager.getInstance();
        List<Song> songs = Arrays.asList(tone(context, "first", 440), tone(context, "second", 660));
        try {
            main(() -> {
                while (player.getRepeatMode() != PlayerManager.REPEAT_OFF) player.toggleRepeat();
                player.setCrossfadeSeconds(3);
                player.playSong(songs.get(0), songs, false);
                return true;
            });
            long end = System.currentTimeMillis() + 15000;
            while (!main(() -> player.isPlaying()) && System.currentTimeMillis() < end) Thread.sleep(50);
            assertTrue("First tone must start", main(() -> player.isPlaying()));
            main(() -> { player.seekTo(6000); return true; });
            boolean overlapped = false, switchedBeforeEnd = false;
            end = System.currentTimeMillis() + 12000;
            while (System.currentTimeMillis() < end) {
                Object fading = main(() -> { try { return field(player, "fadingPlayer"); } catch (Exception e) { return null; } });
                if (fading != null) {
                    overlapped = true;
                    switchedBeforeEnd = main(() -> player.getCurrentSong().getPath().equals(songs.get(1).getPath()));
                    break;
                }
                Thread.sleep(40);
            }
            assertTrue("Two real players must overlap while crossfading", overlapped);
            assertTrue("The next song becomes current when the fade starts", switchedBeforeEnd);
            end = System.currentTimeMillis() + 8000;
            while (main(() -> { try { return field(player, "fadingPlayer"); } catch (Exception e) { return null; } }) != null
                    && System.currentTimeMillis() < end) Thread.sleep(50);
            assertNull("The outgoing player is released after the fade", main(() -> { try { return field(player, "fadingPlayer"); } catch (Exception e) { return "err"; } }));
            assertTrue("The next song keeps playing", main(() -> player.isPlaying()));
        } finally {
            main(() -> { player.setCrossfadeSeconds(0); player.pause(); return true; });
        }
    }

    @Test public void realLibrarySongsFromTheDeviceCrossfadeToo() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        context.getSharedPreferences("musicbox_prefs", 0).edit().putBoolean("all_permissions_requested_v1", true).commit();
        context.getSharedPreferences("player", 0).edit().putBoolean("autoplay", false).commit();
        List<Song> library = cl.arichard.descargador.data.MusicScanner.scanAllMusic(context);
        List<Song> songs = new java.util.ArrayList<>();
        for (Song song : library) if (song.getDuration() > 20000 && song.getDuration() < 600000 && new File(song.getPath()).isFile()) songs.add(song);
        org.junit.Assume.assumeTrue("Needs two readable library songs", songs.size() >= 2);
        songs = songs.subList(0, 2);
        final List<Song> queue = songs;
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).wakeUp();
        InstrumentationRegistry.getInstrumentation().startActivitySync(
                new Intent(context, PreviewMainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        PlayerManager player = PlayerManager.getInstance();
        try {
            main(() -> {
                while (player.getRepeatMode() != PlayerManager.REPEAT_OFF) player.toggleRepeat();
                player.setCrossfadeSeconds(5);
                player.playSong(queue.get(0), queue, false);
                return true;
            });
            long end = System.currentTimeMillis() + 15000;
            while (!main(() -> player.isPlaying()) && System.currentTimeMillis() < end) Thread.sleep(50);
            assertTrue("First library song must start", main(() -> player.isPlaying()));
            main(() -> { player.seekTo((int) Math.max(0, queue.get(0).getDuration() - 9000)); return true; });
            boolean overlapped = false;
            end = System.currentTimeMillis() + 15000;
            while (System.currentTimeMillis() < end && !overlapped) {
                overlapped = main(() -> { try { return field(player, "fadingPlayer") != null; } catch (Exception e) { return false; } });
                Thread.sleep(40);
            }
            assertTrue("Library songs must overlap while crossfading", overlapped);
        } finally {
            main(() -> { player.setCrossfadeSeconds(0); player.pause(); return true; });
        }
    }

    private static android.view.View find(android.view.View root, String text) {
        if (root instanceof android.widget.TextView && text.contentEquals(((android.widget.TextView) root).getText())) return root;
        if (root instanceof android.view.ViewGroup) for (int i = 0; i < ((android.view.ViewGroup) root).getChildCount(); i++) {
            android.view.View found = find(((android.view.ViewGroup) root).getChildAt(i), text);
            if (found != null) return found;
        }
        return null;
    }
    @Test public void settingsSwitchEnablesAndDisablesCrossfade() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        context.getSharedPreferences("player", 0).edit().putInt("crossfade_seconds", 0).commit();
        android.app.Activity activity = InstrumentationRegistry.getInstrumentation().startActivitySync(
                new Intent(context, SettingsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        try {
            android.view.View toggle = main(() -> find(activity.getWindow().getDecorView(), "Crossfade"));
            assertNotNull("Settings must expose the Crossfade switch", toggle);
            main(() -> { toggle.performClick(); return true; });
            assertTrue("Turning it on stores a duration", context.getSharedPreferences("player", 0).getInt("crossfade_seconds", 0) > 0);
            assertTrue(PlayerManager.getInstance().getCrossfadeSeconds() > 0);
            main(() -> { toggle.performClick(); return true; });
            assertEquals("Turning it off disables it", 0, context.getSharedPreferences("player", 0).getInt("crossfade_seconds", -1));
        } finally { main(() -> { activity.finish(); return true; }); }
    }

    @Test public void streamedSongsCrossfadeWhenTheNextOneIsPrepared() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        context.getSharedPreferences("musicbox_prefs", 0).edit().putBoolean("all_permissions_requested_v1", true).commit();
        context.getSharedPreferences("player", 0).edit().putBoolean("autoplay", false).commit();
        List<Song> queue = Arrays.asList(
                new Song(0, "First", "Artist", "YouTube", 212000, "youtube:dQw4w9WgXcQ", "YouTube", 0),
                new Song(1, "Me at the zoo", "jawed", "YouTube", 19000, "youtube:jNQXAC9IVRw", "YouTube", 0));
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).wakeUp();
        InstrumentationRegistry.getInstrumentation().startActivitySync(
                new Intent(context, PreviewMainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        PlayerManager player = PlayerManager.getInstance();
        try {
            main(() -> {
                while (player.getRepeatMode() != PlayerManager.REPEAT_OFF) player.toggleRepeat();
                player.setCrossfadeSeconds(3);
                player.playSong(queue.get(0), queue, false);
                return true;
            });
            long end = System.currentTimeMillis() + 60000;
            while (!main(() -> player.isPlaying()) && System.currentTimeMillis() < end) Thread.sleep(100);
            assertTrue("Streamed song must start", main(() -> player.isPlaying()));
            // Like a normal listen: the next stream is resolved in the background while the first one plays.
            Field cacheField = PlayerManager.class.getDeclaredField("STREAM_CACHE");
            cacheField.setAccessible(true);
            java.util.Map<?, ?> cache = (java.util.Map<?, ?>) cacheField.get(null);
            end = System.currentTimeMillis() + 90000;
            while (System.currentTimeMillis() < end) {
                boolean ready; synchronized (cache) { ready = cache.containsKey("jNQXAC9IVRw"); }
                if (ready) break;
                Thread.sleep(200);
            }
            main(() -> { player.seekTo(212000 - 9000); return true; });
            boolean overlapped = false;
            end = System.currentTimeMillis() + 40000;
            while (System.currentTimeMillis() < end && !overlapped) {
                overlapped = main(() -> { try { return field(player, "fadingPlayer") != null; } catch (Exception e) { return false; } });
                Thread.sleep(40);
            }
            assertTrue("Streamed songs must overlap while crossfading", overlapped);
            assertEquals(queue.get(1).getPath(), main(() -> player.getCurrentSong().getPath()));
        } finally {
            main(() -> { player.setCrossfadeSeconds(0); player.pause(); return true; });
        }
    }
}
