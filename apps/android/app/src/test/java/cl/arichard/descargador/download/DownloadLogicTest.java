package cl.arichard.descargador.download;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

/** Las reglas del descargador son las mismas que las de escritorio (descargador_gui.py y informe_canciones.py). */
public class DownloadLogicTest {
    private static String valueOf(List<String[]> args, String option) {
        for (String[] pair : args) if (pair[0].equals(option)) return pair.length > 1 ? pair[1] : "";
        return null;
    }

    private static boolean has(List<String[]> args, String option) { return valueOf(args, option) != null; }

    private static String errorOf(Runnable action) {
        try { action.run(); } catch (LinkParser.LinkError e) { return e.getMessage(); }
        return null;
    }

    // --- LinkParser ---

    @Test public void uniqueLinesTrimAndKeepOrder() {
        assertEquals(Arrays.asList("a", "b"), LinkParser.uniqueLines("a\n\n a \r\nb\na"));
        assertTrue(LinkParser.uniqueLines("  \n ").isEmpty());
    }

    @Test public void parseUrlsValidatesEachLink() {
        assertEquals("Pega al menos un enlace, uno por línea.", errorOf(() -> LinkParser.parseUrls("", false)));
        assertEquals("El enlace debe comenzar por https:// o http://", errorOf(() -> LinkParser.parseUrls("ftp://x.com/a", false)));
        assertEquals("El enlace debe comenzar por https:// o http://", errorOf(() -> LinkParser.parseUrls("youtube.com/watch?v=1", false)));
        assertEquals(Arrays.asList("https://youtu.be/abc", "https://youtu.be/def"),
                LinkParser.parseUrls("https://youtu.be/abc\nhttps://youtu.be/def\nhttps://youtu.be/abc", false));
    }

    @Test public void aPlaylistLinkNeedsPlaylistMode() {
        String playlist = "https://www.youtube.com/playlist?list=PL123";
        assertEquals("Este enlace es una playlist. Selecciona «Playlist completa / rango».",
                errorOf(() -> LinkParser.parseUrls(playlist, false)));
        assertEquals("Este enlace es una playlist. Selecciona «Playlist completa / rango».",
                errorOf(() -> LinkParser.parseUrls("https://m.youtube.com/playlist/?list=PL1", false)));
        assertEquals(1, LinkParser.parseUrls(playlist, true).size());
        // Un video con list= sigue siendo un video individual.
        assertEquals(1, LinkParser.parseUrls("https://www.youtube.com/watch?v=abc&list=PL1", false).size());
    }

    @Test public void playlistModeIsDetectedOnlyWhenEveryLinkIsAPlaylist() {
        assertTrue(LinkParser.detectPlaylistMode(Arrays.asList("https://youtube.com/playlist?list=A", "https://music.youtube.com/playlist?list=B")));
        assertFalse(LinkParser.detectPlaylistMode(Arrays.asList("https://youtube.com/playlist?list=A", "https://youtu.be/abc")));
        assertFalse(LinkParser.detectPlaylistMode(Arrays.asList("https://youtube.com/playlist?list=")));
        assertFalse(LinkParser.detectPlaylistMode(Arrays.asList("https://youtube.com/watch?v=1&list=A")));
        assertFalse(LinkParser.detectPlaylistMode(Arrays.<String>asList()));
    }

    @Test public void itemRangesAreValidated() {
        assertEquals("", LinkParser.validateItems(""));
        assertEquals("1-10", LinkParser.validateItems("1-10"));
        assertEquals("1,3,5-8", LinkParser.validateItems("1, 3,5-8"));
        assertEquals("El rango debe tener este formato: 1-10 o 1,3,5-8.", errorOf(() -> LinkParser.validateItems("0-3")));
        assertEquals("El rango debe tener este formato: 1-10 o 1,3,5-8.", errorOf(() -> LinkParser.validateItems("a")));
        assertEquals("El inicio del rango no puede ser mayor que el final.", errorOf(() -> LinkParser.validateItems("5-2")));
    }

    // --- DownloadOptions ---

    @Test public void mp3UsesTheChosenBitrateAndEmbedsTagsAndCover() {
        DownloadOptions options = new DownloadOptions(true, "320 kbps", false, "", "", "/music/");
        List<String[]> args = options.args(1, 1);
        assertEquals("320K", valueOf(args, "--audio-quality"));
        assertEquals("mp3", valueOf(args, "--audio-format"));
        assertTrue(has(args, "-x") && has(args, "--add-metadata") && has(args, "--embed-thumbnail"));
        assertTrue(has(args, "--no-playlist") && !has(args, "--yes-playlist"));
        assertEquals("/music/%(title).150B [%(id)s].%(ext)s", options.outputTemplate(1, 1));
    }

    @Test public void batchesAreNumberedToKeepTheirOrder() {
        DownloadOptions options = new DownloadOptions(true, "192 kbps", false, "", "", "/music");
        assertEquals("/music/002 - %(title).150B [%(id)s].%(ext)s", options.outputTemplate(2, 3));
    }

    @Test public void playlistsGoToTheirOwnFolderWithARange() {
        DownloadOptions options = new DownloadOptions(true, "192 kbps", true, "1-5", "", "/music");
        List<String[]> args = options.args(1, 1);
        assertTrue(has(args, "--yes-playlist"));
        assertEquals("1-5", valueOf(args, "--playlist-items"));
        assertTrue(options.outputTemplate(1, 1).contains("%(playlist_title|Sin playlist).100B"));
        assertTrue(options.outputTemplate(1, 4).contains("%(playlist_index|0)03d - "));
        assertFalse(has(new DownloadOptions(true, "192 kbps", true, "", "", "/m").args(1, 1), "--playlist-items"));
    }

    @Test public void mp4CapsTheHeightExceptForBestAvailable() {
        String capped = valueOf(new DownloadOptions(false, "720p", false, "", "", "/m").args(1, 1), "-f");
        assertEquals("bestvideo[ext=mp4][height<=?720]+bestaudio[ext=m4a]/best[ext=mp4][height<=?720]", capped);
        String best = valueOf(new DownloadOptions(false, "Mejor disponible", false, "", "", "/m").args(1, 1), "-f");
        assertEquals("bestvideo[ext=mp4]+bestaudio[ext=m4a]/best[ext=mp4]", best);
        assertEquals("mp4", valueOf(new DownloadOptions(false, "1080p", false, "", "", "/m").args(1, 1), "--merge-output-format"));
    }

    @Test public void cookiesAndRetriesAreForwarded() {
        List<String[]> args = new DownloadOptions(true, "192 kbps", false, "", "/data/cookies.txt", "/m").args(1, 1);
        assertEquals("/data/cookies.txt", valueOf(args, "--cookies"));
        assertEquals("3", valueOf(args, "--retries"));
        assertEquals("3", valueOf(args, "--fragment-retries"));
        assertEquals("20", valueOf(args, "--socket-timeout"));
        assertTrue(has(args, "--ignore-errors") && has(args, "--no-overwrites") && has(args, "--continue"));
        assertFalse(has(new DownloadOptions(true, "192 kbps", false, "", "", "/m").args(1, 1), "--cookies"));
    }

    @Test public void anInvalidRangeFailsBeforeDownloading() {
        DownloadOptions options = new DownloadOptions(true, "192 kbps", true, "9-2", "", "/m");
        assertEquals("El inicio del rango no puede ser mayor que el final.", errorOf(() -> options.args(1, 1)));
    }

    // --- ErrorReason ---

    @Test public void errorsAreExplainedInPlainWords() {
        assertTrue(ErrorReason.reason("HTTP Error 429: Too Many Requests").startsWith("YouTube limitó las consultas."));
        assertTrue(ErrorReason.reason("The read operation timed out").startsWith("Se agotó el tiempo de espera"));
        assertTrue(ErrorReason.reason("Sign in to confirm you're not a bot").contains("configurar cookies"));
        assertTrue(ErrorReason.needsCookies("Sign in to confirm you're not a bot"));
        assertFalse(ErrorReason.needsCookies("HTTP Error 404"));
        assertEquals("No se pudo completar la consulta. Detalle: boom", ErrorReason.reason("boom"));
    }

    // --- OutputParser ---

    @Test public void readsProgressSpeedAndStages() {
        OutputParser parser = new OutputParser(true);
        parser.feed("[download]  45.3% of 3.52MiB at  1.20MiB/s ETA 00:02");
        assertEquals("1.20MiB/s", parser.speed());
        assertEquals("3.52MiB", parser.size());
        assertFalse(parser.processing());
        parser.feed("[ExtractAudio] Destination: /m/Tema [abc].mp3");
        assertTrue(parser.processing());
    }

    @Test public void anMp3IsFinishedWhenTheAudioIsExtracted() {
        OutputParser parser = new OutputParser(true);
        parser.feed("[download] Destination: /m/Tema [abc].webm");
        assertTrue(parser.drain().isEmpty());
        parser.feed("[ExtractAudio] Destination: /m/Tema [abc].mp3");
        List<OutputParser.Finished> done = parser.drain();
        assertEquals(1, done.size());
        assertEquals("/m/Tema [abc].mp3", done.get(0).path);
        assertFalse(done.get(0).existing);
    }

    @Test public void anMp4IsFinishedByTheMergerNotByItsPieces() {
        OutputParser parser = new OutputParser(false);
        parser.feed("[download] Destination: /m/Video [abc].f137.mp4");
        parser.feed("[download] Destination: /m/Video [abc].f140.m4a");
        parser.feed("[Merger] Merging formats into \"/m/Video [abc].mp4\"");
        List<OutputParser.Finished> done = parser.end();
        assertEquals(1, done.size());
        assertEquals("/m/Video [abc].mp4", done.get(0).path);
    }

    @Test public void anMp4ThatCameInOnePieceIsFinishedAtTheEnd() {
        OutputParser parser = new OutputParser(false);
        parser.feed("[download] Destination: /m/Video [abc].mp4");
        assertTrue(parser.drain().isEmpty());
        List<OutputParser.Finished> done = parser.end();
        assertEquals("/m/Video [abc].mp4", done.get(0).path);
    }

    @Test public void alreadyDownloadedFilesAreKeptAndReported() {
        OutputParser parser = new OutputParser(true);
        parser.feed("[download] /m/Tema [abc].mp3 has already been downloaded");
        List<OutputParser.Finished> done = parser.end();
        assertEquals(1, done.size());
        assertTrue(done.get(0).existing);
        assertEquals("/m/Tema [abc].mp3", done.get(0).path);
    }

    @Test public void anExistingMp3IsNotCountedAgainWhenYtDlpSaysItIsNotConverted() {
        OutputParser parser = new OutputParser(true);
        parser.feed("[download] /m/Tema [abc].mp3 has already been downloaded");
        assertEquals(1, parser.drain().size());
        parser.feed("[ExtractAudio] Not converting audio /m/Tema [abc].mp3; file is already in target format mp3");
        assertTrue(parser.end().isEmpty());
    }

    @Test public void aFileIsOnlyCountedOnceEvenIfTheLiveLinesAreDrainedBetweenSteps() {
        OutputParser parser = new OutputParser(true);
        parser.feed("[ExtractAudio] Destination: /m/Tema [abc].mp3");
        assertEquals(1, parser.drain().size());
        parser.feed("[ExtractAudio] Not converting audio /m/Tema [abc].mp3; file is already in target format mp3");
        assertTrue(parser.end().isEmpty());
    }

    @Test public void playlistItemsAreCountedAndEachFileIsReported() {
        OutputParser parser = new OutputParser(false);
        parser.feed("[download] Downloading item 1 of 2");
        parser.feed("[download] Destination: /m/A [a].mp4");
        parser.feed("[download] Downloading item 2 of 2");
        assertEquals(2, parser.itemIndex());
        assertEquals(2, parser.itemCount());
        assertEquals("/m/A [a].mp4", parser.drain().get(0).path);
        parser.feed("[download] Destination: /m/B [b].mp4");
        assertEquals("/m/B [b].mp4", parser.end().get(0).path);
    }
}
