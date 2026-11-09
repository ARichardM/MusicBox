package cl.arichard.descargador.util;

import android.app.Application;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class, qualifiers = "es")
public class LyricsFetcherTest {

    @Before
    public void setUp() {
        LyricsFetcher.clearCache();
    }

    @Test
    public void parsesLrcAndTracksActiveLine() {
        String raw = "[ar:Los Prisioneros]\n"
                + "[ti:Tren al Sur]\n"
                + "[00:05.00] Siete y media en la mañana\n"
                + "[00:09.50][00:25.00] Mi asiento toca la ventana\n"
                + "[00:14.20] Estación Central, segundo carro\n";
        List<LyricsFetcher.Line> lines = LyricsFetcher.parseLrc(raw);
        assertEquals(4, lines.size());
        assertEquals(5000L, lines.get(0).timeMs);
        assertEquals("Siete y media en la mañana", lines.get(0).text);
        assertEquals(9500L, lines.get(1).timeMs);
        assertEquals(14200L, lines.get(2).timeMs);
        assertEquals(25000L, lines.get(3).timeMs);

        assertEquals(-1, LyricsFetcher.activeLineIndex(lines, 1000L));
        assertEquals(0, LyricsFetcher.activeLineIndex(lines, 5000L));
        assertEquals(1, LyricsFetcher.activeLineIndex(lines, 10000L));
        assertEquals(2, LyricsFetcher.activeLineIndex(lines, 15000L));
        assertEquals(3, LyricsFetcher.activeLineIndex(lines, 30000L));
    }

    @Test
    public void wordTimestampsOfExtendedLrcAreNotShownInTheLyrics() {
        // LRCLIB entrega a veces el LRC extendido, con una marca de tiempo antes de cada palabra.
        String raw = "[00:22.82] <00:22.82>You <00:23.23>know <00:23.48>the <00:23.76>rules\n"
                + "[00:24.58]<00:24.58>and <00:24.83>so <00:25.34>do <00:25.61>I\n";
        List<LyricsFetcher.Line> lines = LyricsFetcher.parseLrc(raw);
        assertEquals(2, lines.size());
        assertEquals("You know the rules", lines.get(0).text);
        assertEquals("and so do I", lines.get(1).text);
        assertEquals(22820L, lines.get(0).timeMs);
        List<LyricsFetcher.Line> plain = LyricsFetcher.plainLines("<00:22.82>You <00:23.23>know");
        assertEquals("You know", plain.get(0).text);
    }

    @Test
    public void cleansNoisyTitlesAndArtists() {
        String[] pair = LyricsFetcher.cleanQuery(
                "Los Prisioneros - Tren Al Sur (Video Oficial) [HD]",
                "Artista desconocido");
        assertEquals("Tren Al Sur", pair[0]);
        assertEquals("Los Prisioneros", pair[1]);
        assertEquals("Soda Stereo", LyricsFetcher.cleanArtist("Soda Stereo - Topic"));
    }

    @Test
    public void readsLocalLrcSidecarBeforeNetwork() throws Exception {
        File dir = Files.createTempDirectory("lyrics_test").toFile();
        File mp3 = new File(dir, "cancion.mp3");
        File lrc = new File(dir, "cancion.lrc");
        try (FileOutputStream out = new FileOutputStream(mp3)) {
            out.write(new byte[] {0, 1, 2});
        }
        String content = "[00:01.00] Primera línea local\n[00:04.00] Segunda línea local\n";
        try (FileOutputStream out = new FileOutputStream(lrc)) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
        }

        LyricsFetcher.Result res = LyricsFetcher.find("Canción", "Artista", "", 180, "", mp3.getAbsolutePath());
        assertTrue(res.synced);
        assertEquals("Archivo LRC", res.source);
        assertEquals(2, res.lines.size());
        assertEquals("Primera línea local", res.lines.get(0).text);
    }

    @Test
    public void fetchesSyncedLyricsFromLrclib() {
        LyricsFetcher.httpTransport = url -> new JSONObject()
                .put("trackName", "Tren al Sur")
                .put("artistName", "Los Prisioneros")
                .put("syncedLyrics", "[00:02.00] Uno\n[00:05.50] Dos")
                .toString();

        LyricsFetcher.Result res = LyricsFetcher.find("Tren al Sur", "Los Prisioneros", "Corazones", 337, "", "");
        assertTrue(res.synced);
        assertEquals("LRCLIB", res.source);
        assertEquals(2, res.lines.size());
        assertEquals("Dos", res.lines.get(1).text);
    }

    @Test
    public void fallsBackToYtMusicWhenLrclibHasNoMatch() {
        LyricsFetcher.httpTransport = url -> "[]";
        YtMusicApi.transport = (endpoint, body) -> {
            if ("next".equals(endpoint)) {
                return new JSONObject().put("tabRenderer", new JSONObject()
                        .put("endpoint", new JSONObject().put("browseEndpoint",
                                new JSONObject().put("browseId", "MPLYt_test"))));
            }
            return new JSONObject().put("musicDescriptionShelfRenderer", new JSONObject()
                    .put("description", new JSONObject().put("runs", new JSONArray()
                            .put(new JSONObject().put("text", "Verso uno\nVerso dos"))))
                    .put("footer", new JSONObject().put("runs", new JSONArray()
                            .put(new JSONObject().put("text", "Fuente: Musixmatch")))));
        };

        LyricsFetcher.Result res = LyricsFetcher.find("Canción Rara", "Artista", "", 200, "vid123", "");
        assertFalse(res.synced);
        assertEquals("Fuente: Musixmatch", res.source);
        assertEquals(2, res.lines.size());
        assertEquals("Verso uno", res.lines.get(0).text);
    }
}
