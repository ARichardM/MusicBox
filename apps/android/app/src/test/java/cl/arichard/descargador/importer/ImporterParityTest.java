package cl.arichard.descargador.importer;

import android.app.Application;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

/** Generado por gen_parity.py con el Python real de escritorio: ambas plataformas deben decidir igual. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class, qualifiers = "es")
public class ImporterParityTest {
    private static String join(List<String> items) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < items.size(); i++) { if (i > 0) out.append('\n'); out.append(items.get(i).replace("\n", "\\n")); }
        return out.toString();
    }

    private static String songs(TextImport.ParsedPlaylist parsed) {
        if (parsed == null) return "NONE";
        List<String> rows = new ArrayList<>();
        for (TextImport.ImportedSong s : parsed.songs) rows.add(String.join("|", s.title.replace("\n", "\\n"), s.artist.replace("\n", "\\n"),
                s.album.replace("\n", "\\n"), s.duration, s.uploader.replace("\n", "\\n"), String.valueOf(s.structured),
                s.reviewReason, s.rawText.replace("\n", "\\n")));
        return parsed.timedBlocks + "#" + join(rows) + "#" + parsed.warnings.size();
    }

    private static SongMatcher.Candidate cand(String title, String channel, double duration) {
        return new SongMatcher.Candidate("id", title, channel, duration);
    }

    @Test public void normalizeMatchesPython() {
        assertEquals("cafe tacvba ingrata official video hd", SongMatcher.normalize("Caf\u00e9 Tacvba - Ingrata (Official Video) [HD]"));
        assertEquals("nandu exitos", SongMatcher.normalize("\u00d1and\u00fa  \u00c9XITOS"));
        assertEquals("\u304c\u3093\u3070\u308c \u30ac", SongMatcher.normalize("\u304c\u3093\u3070\u308c \u30ac"));
        assertEquals("strasse", SongMatcher.normalize("Stra\u00dfe"));
        assertEquals("", SongMatcher.normalize(""));
        assertEquals("", SongMatcher.normalize("  "));
        assertEquals("abc 123", SongMatcher.normalize("\uff21\uff22\uff23 \uff11\uff12\uff13"));
        assertEquals("motley crue kickstart my heart", SongMatcher.normalize("M\u00f6tley Cr\u00fce \u2013 Kickstart My Heart"));
        assertEquals("beyonce feat jay z", SongMatcher.normalize("Beyonc\u00e9 feat. Jay-Z"));
        assertEquals("100 real", SongMatcher.normalize("100% real!!"));
        assertEquals("bjork", SongMatcher.normalize("Bj\u00f6rk"));
    }

    @Test public void contentMatchesPython() {
        assertEquals("rick astley never gonna give you up", SongMatcher.content("Rick Astley - Never Gonna Give You Up (Official Video) 4K"));
        assertEquals("song name", SongMatcher.content("Song Name (Lyrics) HD"));
        assertEquals("cancion", SongMatcher.content("Video Oficial - Canci\u00f3n"));
        assertEquals("audio", SongMatcher.content("hq audio"));
    }

    @Test public void ratioMatchesDifflib() {
        assertEquals(0.75, SongMatcher.ratio("abcd", "bcde"), 1e-12);
        assertEquals(0.9523809523809523, SongMatcher.ratio("hello world", "hello wrld"), 1e-12);
        assertEquals(1.0, SongMatcher.ratio("", ""), 1e-12);
        assertEquals(0.6153846153846154, SongMatcher.ratio("kitten", "sitting"), 1e-12);
        assertEquals(0.5789473684210527, SongMatcher.ratio("tren al sur", "los prisioneros tren al sur"), 1e-12);
        assertEquals(1.0, SongMatcher.ratio("abc", "abc"), 1e-12);
        assertEquals(0.0, SongMatcher.ratio("a", "b"), 1e-12);
        assertEquals(0.8947368421052632, SongMatcher.ratio("the quick brown fox", "the quick brown dog"), 1e-12);
        assertEquals(0.6666666666666666, SongMatcher.ratio("aaaa", "aa"), 1e-12);
    }

    @Test public void secondsMatchesPython() {
        assertEquals(225, SongMatcher.seconds("3:45"), 1e-12);
        assertEquals(3723, SongMatcher.seconds("1:02:03"), 1e-12);
        assertNull(SongMatcher.seconds("abc"));
        assertNull(SongMatcher.seconds("0"));
        assertEquals(12.5, SongMatcher.seconds("12.5"), 1e-12);
        assertNull(SongMatcher.seconds("1:2:3:4"));
        assertNull(SongMatcher.seconds(""));
        assertEquals(5.0, SongMatcher.seconds("5"), 1e-12);
        assertEquals(600, SongMatcher.seconds("10:00"), 1e-12);
        assertNull(SongMatcher.seconds("-4"));
    }

    @Test public void scoringMatchesPython() {
        {
            SongMatcher.Match m = SongMatcher.score("Los Prisioneros - Tren Al Sur", cand("Los Prisioneros - Tren Al Sur (Video Oficial)", "Los Prisioneros", 215.0), "", "", "");
            assertEquals(0.8, m.score, 1e-9);
            assertEquals(true, m.exact);
            assertEquals(false, m.fullExact);
            assertEquals(true, m.autoSelect);
            assertEquals("", m.reviewReason);
        }
        {
            SongMatcher.Match m = SongMatcher.score("Tren al sur", cand("Los Prisioneros - Tren Al Sur", "EMI Chile", 215.0), "Tren al sur", "Los Prisioneros", "3:35");
            assertEquals(1.1, m.score, 1e-9);
            assertEquals(true, m.exact);
            assertEquals(false, m.fullExact);
            assertEquals(true, m.autoSelect);
            assertEquals("", m.reviewReason);
        }
        {
            SongMatcher.Match m = SongMatcher.score("Tren al sur", cand("Tren al sur (cover ac\u00fastico)", "Alguien", 230.0), "Tren al sur", "Los Prisioneros", "3:35");
            assertEquals(0.13186046511627914, m.score, 1e-9);
            assertEquals(false, m.exact);
            assertEquals(false, m.fullExact);
            assertEquals(false, m.autoSelect);
            assertEquals("No se pudo confirmar el artista de la canci\u00f3n.", m.reviewReason);
        }
        {
            SongMatcher.Match m = SongMatcher.score("Circles", cand("Post Malone - Circles (Live)", "PostMaloneVEVO", 220.0), "Circles", "Post Malone", "3:35");
            assertEquals(0.6900938392492861, m.score, 1e-9);
            assertEquals(false, m.exact);
            assertEquals(false, m.fullExact);
            assertEquals(false, m.autoSelect);
            assertEquals("El t\u00edtulo no coincide lo suficiente; la mejor alternativa podr\u00eda ser otra canci\u00f3n.", m.reviewReason);
        }
        {
            SongMatcher.Match m = SongMatcher.score("Circles", cand("Circles", "Post Malone - Topic", 215.0), "Circles", "Post Malone", "3:35");
            assertEquals(1.1, m.score, 1e-9);
            assertEquals(true, m.exact);
            assertEquals(true, m.fullExact);
            assertEquals(true, m.autoSelect);
            assertEquals("", m.reviewReason);
        }
        {
            SongMatcher.Match m = SongMatcher.score("Blinding Lights", cand("The Weeknd - Blinding Lights (Official Audio)", "TheWeekndVEVO", 202.0), "Blinding Lights", "The Weeknd", "3:22");
            assertEquals(1.1, m.score, 1e-9);
            assertEquals(true, m.exact);
            assertEquals(false, m.fullExact);
            assertEquals(true, m.autoSelect);
            assertEquals("", m.reviewReason);
        }
        {
            SongMatcher.Match m = SongMatcher.score("Blinding Lights", cand("Blinding Lights", "Otro", 600.0), "Blinding Lights", "The Weeknd", "3:22");
            assertEquals(0.55, m.score, 1e-9);
            assertEquals(true, m.exact);
            assertEquals(true, m.fullExact);
            assertEquals(false, m.autoSelect);
            assertEquals("La duraci\u00f3n difiere demasiado de la canci\u00f3n de la lista.", m.reviewReason);
        }
        {
            SongMatcher.Match m = SongMatcher.score("Shape of You", cand("Ed Sheeran - Shape of You [Karaoke Version]", "Karaoke", 235.0), "Shape of You", "Ed Sheeran", "");
            assertEquals(0.6200000000000001, m.score, 1e-9);
            assertEquals(false, m.exact);
            assertEquals(false, m.fullExact);
            assertEquals(false, m.autoSelect);
            assertEquals("El t\u00edtulo no coincide lo suficiente; la mejor alternativa podr\u00eda ser otra canci\u00f3n.", m.reviewReason);
        }
        {
            SongMatcher.Match m = SongMatcher.score("", cand("Algo", "x", 100.0), "", "", "");
            assertEquals(0.0, m.score, 1e-9);
            assertEquals(false, m.exact);
            assertEquals(false, m.fullExact);
            assertEquals(false, m.autoSelect);
            assertEquals("Faltan datos del t\u00edtulo para verificar la coincidencia.", m.reviewReason);
        }
        {
            SongMatcher.Match m = SongMatcher.score("Algo", cand("", "x", 100.0), "", "", "");
            assertEquals(0.0, m.score, 1e-9);
            assertEquals(false, m.exact);
            assertEquals(false, m.fullExact);
            assertEquals(false, m.autoSelect);
            assertEquals("Faltan datos del t\u00edtulo para verificar la coincidencia.", m.reviewReason);
        }
        {
            SongMatcher.Match m = SongMatcher.score("Hey Jude", cand("The Beatles - Hey Jude (Remastered 2015)", "TheBeatlesVEVO", 431.0), "Hey Jude", "The Beatles & Paul McCartney", "7:11");
            assertEquals(0.4833333333333333, m.score, 1e-9);
            assertEquals(false, m.exact);
            assertEquals(false, m.fullExact);
            assertEquals(false, m.autoSelect);
            assertEquals("No se pudo confirmar el artista de la canci\u00f3n.", m.reviewReason);
        }
        {
            SongMatcher.Match m = SongMatcher.score("\u304c\u3093\u3070\u308c", cand("\u304c\u3093\u3070\u308c - \u516c\u5f0f", "x", 200.0), "\u304c\u3093\u3070\u308c", "", "");
            assertEquals(0.5818181818181819, m.score, 1e-9);
            assertEquals(false, m.exact);
            assertEquals(false, m.fullExact);
            assertEquals(false, m.autoSelect);
            assertEquals("El t\u00edtulo no coincide lo suficiente; la mejor alternativa podr\u00eda ser otra canci\u00f3n.", m.reviewReason);
        }
        {
            SongMatcher.Match m = SongMatcher.score("Dua Lipa - Levitating feat. DaBaby", cand("Dua Lipa - Levitating Featuring DaBaby (Official Music Video)", "Dua Lipa", 203.0), "Levitating", "Dua Lipa, DaBaby", "3:23");
            assertEquals(1.0402985074626867, m.score, 1e-9);
            assertEquals(false, m.exact);
            assertEquals(false, m.fullExact);
            assertEquals(false, m.autoSelect);
            assertEquals("El t\u00edtulo no coincide lo suficiente; la mejor alternativa podr\u00eda ser otra canci\u00f3n.", m.reviewReason);
        }
    }

    @Test public void bestCandidateMatchesPython() {
        assertEquals(2, SongMatcher.best("Tren al sur", new ArrayList<>(Arrays.asList(cand("Tren al sur (cover)", "a", 200.0), cand("Los Prisioneros - Tren Al Sur", "b", 215.0), cand("Tren al sur", "c", 215.0))), "Tren al sur", "Los Prisioneros", "3:35"));
        assertEquals(1, SongMatcher.best("Imagine", new ArrayList<>(Arrays.asList(cand("Imagine Dragons - Believer", "a", 200.0), cand("John Lennon - Imagine", "b", 183.0))), "Imagine", "John Lennon", "3:03"));
        assertEquals(-1, SongMatcher.best("x", new ArrayList<SongMatcher.Candidate>(), "", "", ""));
    }

    @Test public void videoCreditsAndQueriesMatchPython() {
        assertEquals("Linked Horizon", TextImport.videoArtist("Attack on Titan Opening 1 by Linked Horizon"));
        assertEquals("Sound of Rain", TextImport.videoArtist("Naruto Ending - Sound of Rain (2008)"));
        assertEquals("Me Opening", TextImport.videoArtist("Stand by Me Opening"));
        assertEquals("", TextImport.videoArtist("Just a normal song"));
        assertEquals("", TextImport.videoArtist("My Anime OP | Artist Name"));
        assertEquals("Yui\"", TextImport.videoArtist("Creditless Opening by \"Yui\" [Official HD]"));
        assertEquals("YOASOBI", TextImport.videoArtist("Opening by YOASOBI | HD"));
        assertEquals("", TextImport.videoArtist("Ending by You"));
        assertEquals("Someone (2017)", TextImport.videoArtist("Kimi no Na wa AMV by Someone (2017)"));
        assertEquals("Los Prisioneros - Tren al sur", TextImport.songQuery("Tren al sur", "Los Prisioneros"));
        assertEquals("Tren al sur - ", TextImport.songQuery("", "Tren al sur"));
        assertEquals("Linked Horizon - Guren no Yumiya", TextImport.songQuery("Linked Horizon - Guren no Yumiya", "Linked Horizon"));
        assertEquals("x", TextImport.songQuery("x", ""));
        assertEquals("Canci\u00f3n de Beto y Ana", TextImport.songQuery("Canci\u00f3n de Beto y Ana", "Beto"));
    }

    @Test public void tidyLinesMatchPython() {
        assertEquals("Despacito", TextImport.tidyLine("1. Despacito 3:48"));
        assertEquals("", TextImport.tidyLine("3:45"));
        assertEquals("Hola mundo", TextImport.tidyLine("  \u2022 Hola mundo  "));
        assertEquals("", TextImport.tidyLine("- "));
        assertEquals("Artista - 3:45", TextImport.tidyLine("Artista - 3:45"));
        assertEquals("Canci\u00f3n", TextImport.tidyLine("12) Canci\u00f3n  "));
        assertEquals("", TextImport.tidyLine("A -"));
        assertEquals("", TextImport.tidyLine("---"));
        assertEquals("", TextImport.tidyLine("   "));
        assertEquals("Tema \u00b7 Artista", TextImport.tidyLine("Tema \u00b7 Artista 2:10"));
        assertEquals("TEXTO", TextImport.tidyLine("TEXTO 10:22:33"));
        assertEquals("\u00d1and\u00fa \u2014 4:01", TextImport.tidyLine("\u00d1and\u00fa \u2014 4:01"));
    }

    @Test public void organizedTextMatchesPython() {
        assertEquals("Los Prisioneros - Tren al sur\nLuis Fonsi - Despacito", join(TextImport.organizePlaylistText("1. Tren al sur\nLos Prisioneros \u00b7 \u00c1lbum\n3:35\n2. Despacito\nLuis Fonsi \u00b7 Vida\n3:48\n", null)));
        assertEquals("Attack on Titan Opening 1\nAnother Song", join(TextImport.organizePlaylistText("Attack on Titan Opening 1\nSome Channel\nAnother Song\n\nAnother Song\n", null)));
        assertEquals("Canci\u00f3n A\nCanci\u00f3n B", join(TextImport.organizePlaylistText("Canci\u00f3n A 3:00\nCanci\u00f3n B\nCanci\u00f3n a\n", null)));
    }

    @Test public void cleanedQueriesMatchPython() {
        assertEquals("Uno\nDos\nTres\nCuatro\nCinco - 4:00\nSeis: 1:02:03", join(TextImport.cleanQueries("1. Uno\n2) Dos\n\u2022 Tres 3:30\n\u25cfCuatro\nuno\n  \n---\n5. Cinco - 4:00\nSeis: 1:02:03\n", true)));
        assertEquals("", join(TextImport.cleanQueries("", true)));
        assertEquals("x\ny", join(TextImport.cleanQueries("x\ny\nX\n", true)));
        StringBuilder many = new StringBuilder();
        for (int i = 0; i < 501; i++) many.append("tema ").append(i).append('\n');
        try { TextImport.cleanQueries(many.toString(), true); fail(); } catch (IllegalArgumentException expected) {}
        assertEquals(501, TextImport.cleanQueries(many.toString(), false).size());
    }

    @Test public void exportedPlaylistsMatchPython() {
        assertEquals("2#Tren al sur|Los Prisioneros|Pateando Piedras|3:35||true||\nDespacito|Luis Fonsi, Daddy Yankee|Vida|3:48||true||#0", songs(TextImport.parseExportedPlaylist("Tren al sur\nLos Prisioneros\nPateando Piedras\n3:35\nDespacito\nLuis Fonsi\n,\nDaddy Yankee\nVida\n3:48\n")));
        assertEquals("2#Title A|Artist A||3:00||true||\nTitle B|Artist B and Artist C|Album B|4:10||true||\nTitle C|Artist D||||true||#1", songs(TextImport.parseExportedPlaylist("Title A\nArtist A\n3:00\nTitle B\nArtist B\nand\nArtist C\nAlbum B\n4:10\nTitle C\nArtist D\n")));
        assertEquals("NONE", songs(TextImport.parseExportedPlaylist("solo\nlineas\nsin tiempo\n")));
        assertEquals("2## Mi lista|Canci\u00f3n 1|Artista 1|3:21||true||\nCanci\u00f3n 2|Artista 2|Album 2|2:59||true||#0", songs(TextImport.parseExportedPlaylist("# Mi lista\nCanci\u00f3n 1\nArtista 1\n3:21\nCanci\u00f3n 2\nArtista 2\nAlbum 2\n2:59\n")));
        assertEquals("2#Opening 1 by Band|Band||1:30|Channel Name|true||\nOtra|Otro|Alb|2:00||true||#0", songs(TextImport.parseExportedPlaylist("Opening 1 by Band\nChannel Name\n1:30\nOtra\nOtro\nAlb\n2:00\n")));
        assertEquals("NONE", songs(TextImport.parseExportedPlaylist("\ufeffCanci\u00f3n X\nArtista X, Artista Y\n3:03\n\nCanci\u00f3n Z\nArtista Z\n4:04\n")));
        assertEquals("NONE", songs(TextImport.parseExportedPlaylist("A - B\nC - D\n3:00\n")));
        assertEquals("3#Primera|Banda|Disco|3:00||true||\nSegunda|||4:00||true|No se pudieron separar t\u00edtulo, artistas y \u00e1lbum. Revisa el texto original y corrige esta fila.|Segunda\nTercera|Banda 3||2:00||true||#1", songs(TextImport.parseExportedPlaylist("Primera\nBanda\nDisco\n3:00\nSegunda\n4:00\nTercera\nBanda 3\n2:00\n")));
    }
}
