package cl.arichard.descargador;

import android.content.Context;
import android.content.Intent;
import android.widget.SeekBar;
import android.widget.TextView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import cl.arichard.descargador.model.Song;
import cl.arichard.descargador.player.PlayerManager;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** The progress bar follows the outgoing song through the whole crossfade; the title changes halfway. */
@RunWith(AndroidJUnit4.class)
public class CrossfadeBarDeviceTest {
    private Song tone(Context context, String name, int hz) throws Exception {
        int rate = 16000, seconds = 30, bytes = rate * 2 * seconds;
        ByteBuffer wav = ByteBuffer.allocate(44 + bytes).order(ByteOrder.LITTLE_ENDIAN);
        wav.put(new byte[]{'R', 'I', 'F', 'F'}).putInt(36 + bytes).put(new byte[]{'W', 'A', 'V', 'E', 'f', 'm', 't', ' '});
        wav.putInt(16).putShort((short) 1).putShort((short) 1).putInt(rate).putInt(rate * 2);
        wav.putShort((short) 2).putShort((short) 16).put(new byte[]{'d', 'a', 't', 'a'}).putInt(bytes);
        for (int i = 0; i < rate * seconds; i++) wav.putShort((short) (Math.sin(2 * Math.PI * hz * i / rate) * 3000));
        File file = new File(context.getFilesDir(), "bar-" + name + ".wav");
        try (FileOutputStream output = new FileOutputStream(file)) { output.write(wav.array()); }
        return new Song(0, name, "Verification", "Synthetic", seconds * 1000, file.getPath(), "Verification", 0);
    }

    @Test public void barFollowsTheOutgoingSongAndTheTitleChangesHalfway() throws Exception {
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        Context context = instrumentation.getTargetContext();
        context.getSharedPreferences("player", 0).edit().putBoolean("autoplay", false).putBoolean("crossfade_show_zone", true).commit();
        List<Song> songs = Arrays.asList(tone(context, "Primera", 440), tone(context, "Segunda", 660));
        PlayerManager player = PlayerManager.getInstance();
        instrumentation.runOnMainSync(() -> {
            player.init(context);
            while (player.getRepeatMode() != PlayerManager.REPEAT_OFF) player.toggleRepeat();
            player.setCrossfadeSeconds(6);
            player.playSong(songs.get(0), songs, false);
        });
        NowPlayingActivity now = (NowPlayingActivity) instrumentation.startActivitySync(
                new Intent(context, NowPlayingActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        try {
            Thread.sleep(2500);
            SeekBar bar = now.findViewById(R.id.seekBar);
            TextView title = now.findViewById(R.id.txtNowPlayingTitle);
            instrumentation.runOnMainSync(() -> player.seekTo(30000 - 8000));
            List<String> log = new ArrayList<>();
            int lastProgress = -1, minDuringFade = Integer.MAX_VALUE;
            boolean sawFade = false, titleChangedWhileBarOnOutgoing = false, titleSwapped = false;
            long end = System.currentTimeMillis() + 20000, fadeStart = 0, swapAt = 0;
            while (System.currentTimeMillis() < end) {
                boolean[] fading = new boolean[1]; int[] progress = new int[2]; String[] text = new String[1];
                instrumentation.runOnMainSync(() -> {
                    fading[0] = player.isCrossfading(); progress[0] = bar.getProgress(); progress[1] = bar.getMax(); text[0] = title.getText().toString();
                });
                if (fading[0]) {
                    if (!sawFade) { sawFade = true; fadeStart = System.currentTimeMillis(); }
                    minDuringFade = Math.min(minDuringFade, progress[0]);
                    if (text[0].equals("Segunda") && !titleSwapped) { titleSwapped = true; swapAt = System.currentTimeMillis() - fadeStart; }
                    log.add((System.currentTimeMillis() - fadeStart) + "ms bar=" + progress[0] + "/" + progress[1] + " title=" + text[0]);
                    assertTrue("The bar never jumps back to the new song while mixing. " + log, progress[0] >= lastProgress - 400);
                    lastProgress = progress[0];
                } else if (sawFade) break;
                Thread.sleep(100);
            }
            assertTrue("A crossfade happened. " + log, sawFade);
            assertTrue("The bar stays on the outgoing song (>= 22 s) throughout the mix. " + log, minDuringFade >= 22000);
            assertTrue("The title changed to the next song during the mix, not before. " + log, titleSwapped && swapAt > 1200);
            Thread.sleep(800);
            instrumentation.runOnMainSync(() -> assertEquals("Segunda", title.getText().toString()));
        } finally {
            instrumentation.runOnMainSync(() -> { now.finish(); player.setCrossfadeSeconds(0); player.stopAndRelease(); });
        }
    }
}
