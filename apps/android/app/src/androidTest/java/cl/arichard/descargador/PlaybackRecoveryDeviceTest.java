package cl.arichard.descargador;

import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.UiDevice;
import cl.arichard.descargador.model.Song;
import cl.arichard.descargador.player.PlayerManager;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Real platform decoder and natural completion, exclusively with private synthetic audio. */
@RunWith(AndroidJUnit4.class)
public class PlaybackRecoveryDeviceTest {
    private <T> T main(Supplier<T> action) {
        AtomicReference<T> value = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> value.set(action.get()));
        return value.get();
    }
    private void waitFor(Supplier<Boolean> condition) throws Exception {
        long end = System.currentTimeMillis() + 20000;
        while (!condition.get() && System.currentTimeMillis() < end) Thread.sleep(50);
        assertTrue("Playback must recover and continue its queue", condition.get());
    }
    private Song sample(Context context, String name, boolean valid) throws Exception {
        File file = new File(context.getFilesDir(), "playback-recovery-" + name + ".wav");
        int bytes = valid ? 16000 * 2 * 3 : 0;
        ByteBuffer wav = ByteBuffer.allocate(valid ? 44 + bytes : 4).order(ByteOrder.LITTLE_ENDIAN);
        if (valid) {
            wav.put(new byte[]{'R','I','F','F'}).putInt(36 + bytes).put(new byte[]{'W','A','V','E','f','m','t',' '});
            wav.putInt(16).putShort((short) 1).putShort((short) 1).putInt(16000).putInt(32000);
            wav.putShort((short) 2).putShort((short) 16).put(new byte[]{'d','a','t','a'}).putInt(bytes);
        } else wav.putInt(0);
        try (FileOutputStream output = new FileOutputStream(file)) { output.write(wav.array()); }
        return new Song(0, name, "Verification", "Synthetic", 3000, file.getPath(), "Verification", 0);
    }
    @Test public void malformedAudioIsSkippedAndNativeCompletionContinuesTheQueue() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertEquals("cl.arichard.descargador.verification", context.getPackageName());
        context.getSharedPreferences("musicbox_prefs", 0).edit().putBoolean("all_permissions_requested_v1", true).commit();
        context.getSharedPreferences("player", 0).edit().putBoolean("autoplay", false).commit();
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).wakeUp();
        MainActivity activity = (MainActivity) InstrumentationRegistry.getInstrumentation().startActivitySync(
                new Intent(context, PreviewMainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        PlayerManager player = PlayerManager.getInstance();
        List<Song> samples = Arrays.asList(sample(context, "broken", false), sample(context, "first", true), sample(context, "second", true));
        List<String> played = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger errors = new AtomicInteger();
        PlayerManager.PlaybackListener listener = new PlayerManager.PlaybackListener() {
            public void onTrackChanged(Song song) {}
            public void onProgressUpdate(int position, int duration) {}
            public void onPlaybackStateChanged(boolean playing) {
                if (playing) played.add(player.getCurrentSong().getPath());
            }
            public void onPlaybackError(String message) { errors.incrementAndGet(); }
        };
        try {
            assertEquals(AudioManager.STREAM_MUSIC, main(activity::getVolumeControlStream).intValue());
            main(() -> {
                player.addListener(listener);
                while (player.getRepeatMode() != PlayerManager.REPEAT_OFF) player.toggleRepeat();
                player.playSong(samples.get(0), samples, false);
                return true;
            });
            waitFor(() -> played.contains(samples.get(2).getPath()));
            assertTrue(played.contains(samples.get(1).getPath()));
            assertEquals(1, errors.get());
            assertEquals(3, main(() -> player.getQueue().size()).intValue());
            PlayerActivity full = (PlayerActivity) InstrumentationRegistry.getInstrumentation().startActivitySync(
                    new Intent(context, PlayerActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            assertEquals(AudioManager.STREAM_MUSIC, main(full::getVolumeControlStream).intValue());
            main(() -> { full.finish(); return true; });
            NowPlayingActivity now = (NowPlayingActivity) InstrumentationRegistry.getInstrumentation().startActivitySync(
                    new Intent(context, NowPlayingActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            assertEquals(AudioManager.STREAM_MUSIC, main(now::getVolumeControlStream).intValue());
            main(() -> { now.finish(); return true; });
        } finally {
            main(() -> { player.removeListener(listener); player.stopAndRelease(); activity.finish(); return true; });
            for (Song song : samples) new File(song.getPath()).delete();
        }
    }
}
