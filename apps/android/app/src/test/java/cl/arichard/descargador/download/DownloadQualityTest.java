package cl.arichard.descargador.download;

import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

/** La calidad elegida en Ajustes llega a yt-dlp tal cual. */
public class DownloadQualityTest {
    private static String value(List<String[]> args, String option) {
        for (String[] pair : args) if (pair[0].equals(option) && pair.length > 1) return pair[1];
        return null;
    }

    @Test public void mp3BitrateFollowsTheSetting() {
        for (String kbps : new String[] {"128", "192", "320"}) {
            List<String[]> args = new DownloadOptions(true, kbps + " kbps", false, "", "", "/m").args(1, 1);
            assertEquals(kbps + "K", value(args, "--audio-quality"));
            assertEquals("mp3", value(args, "--audio-format"));
        }
    }

    @Test public void mp4HeightCapFollowsTheSetting() {
        for (String height : new String[] {"480", "720", "1080"}) {
            List<String[]> args = new DownloadOptions(false, height + "p", false, "", "", "/m").args(1, 1);
            assertTrue(value(args, "-f").contains("[height<=?" + height + "]"));
            assertEquals("mp4", value(args, "--merge-output-format"));
        }
    }
}
