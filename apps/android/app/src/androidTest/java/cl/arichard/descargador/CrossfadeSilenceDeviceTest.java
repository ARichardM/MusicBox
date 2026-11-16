package cl.arichard.descargador;

import android.content.Context;
import android.content.Intent;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import cl.arichard.descargador.model.Song;
import cl.arichard.descargador.player.PlayerManager;
import cl.arichard.descargador.util.WaveformStore;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Songs with silence at the end/start: the mix must happen over sound, not over the silence. */
@RunWith(AndroidJUnit4.class)
public class CrossfadeSilenceDeviceTest {
    private <T> T main(Supplier<T> action) {
        AtomicReference<T> value = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> value.set(action.get()));
        return value.get();
    }

    /** [leadSilence] s of silence, then [sound] s of tone, then [tailSilence] s of silence. */
    private Song track(Context context, String name, int hz, int leadSilence, int sound, int tailSilence) throws Exception {
        int rate = 16000, seconds = leadSilence + sound + tailSilence, bytes = rate * 2 * seconds;
        ByteBuffer wav = ByteBuffer.allocate(44 + bytes).order(ByteOrder.LITTLE_ENDIAN);
        wav.put(new byte[]{'R', 'I', 'F', 'F'}).putInt(36 + bytes).put(new byte[]{'W', 'A', 'V', 'E', 'f', 'm', 't', ' '});
        wav.putInt(16).putShort((short) 1).putShort((short) 1).putInt(rate).putInt(rate * 2);
        wav.putShort((short) 2).putShort((short) 16).put(new byte[]{'d', 'a', 't', 'a'}).putInt(bytes);
        for (int i = 0; i < rate * seconds; i++) {
            boolean audible = i >= rate * leadSilence && i < rate * (leadSilence + sound);
            wav.putShort((short) (audible ? Math.sin(2 * Math.PI * hz * i / rate) * 9000 : 0));
        }
        File file = new File(context.getFilesDir(), "silence-" + name + ".wav");
        try (FileOutputStream output = new FileOutputStream(file)) { output.write(wav.array()); }
        return new Song(0, name, "Verification", "Synthetic", seconds * 1000, file.getPath(), "Verification", 0);
    }

    @Test public void theMixStartsOverSoundAndSkipsTheNextSongsSilence() throws Exception {
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        Context context = instrumentation.getTargetContext();
        context.getSharedPreferences("musicbox_prefs", 0).edit().putBoolean("all_permissions_requested_v1", true).commit();
        context.getSharedPreferences("player", 0).edit().putBoolean("autoplay", false).commit();
        instrumentation.startActivitySync(new Intent(context, PreviewMainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        PlayerManager player = PlayerManager.getInstance();
        Song first = track(context, "A", 440, 0, 18, 12), second = track(context, "B", 660, 5, 20, 0);
        List<Song> songs = Arrays.asList(first, second);
        try {
            main(() -> {
                while (player.getRepeatMode() != PlayerManager.REPEAT_OFF) player.toggleRepeat();
                player.setCrossfadeSeconds(4);
                player.playSong(first, songs, false);
                return true;
            });
            WaveformStore store = WaveformStore.get(context);
            long end = System.currentTimeMillis() + 30000;
            while ((store.peek(first) == null || store.peek(second) == null) && System.currentTimeMillis() < end) Thread.sleep(200);
            WaveformStore.Envelope a = store.peek(first), b = store.peek(second);
            assertNotNull("The first song was analysed", a);
            assertNotNull("The second song was analysed", b);
            assertEquals("The first song stops sounding at 18 s, not at 30 s", 18000, a.audibleEndMs, 500);
            assertEquals("The second song starts sounding at 5 s", 5000, b.audibleStartMs, 500);
            main(() -> { player.seekTo(10000); return true; });
            long fadeAtOutgoing = -1, incomingAtStart = -1;
            end = System.currentTimeMillis() + 20000;
            while (System.currentTimeMillis() < end) {
                boolean fading = main(() -> player.isCrossfading());
                if (fading) {
                    fadeAtOutgoing = main(() -> (long) player.getOutgoingPosition());
                    Thread.sleep(800);
                    incomingAtStart = main(() -> (long) player.getCurrentPosition());
                    break;
                }
                Thread.sleep(40);
            }
            assertTrue("A crossfade started", fadeAtOutgoing >= 0);
            assertTrue("It starts about 4 s before the sound ends (14 s), not at the end of the file: " + fadeAtOutgoing,
                    fadeAtOutgoing >= 13000 && fadeAtOutgoing <= 15500);
            assertTrue("The next song is already past its silence when it comes in: " + incomingAtStart, incomingAtStart >= 5000);
        } finally {
            main(() -> { player.setCrossfadeSeconds(0); player.pause(); return true; });
        }
    }
}
