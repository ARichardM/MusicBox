package cl.arichard.descargador.importer;

import org.junit.Test;

import static org.junit.Assert.*;

/** Generado por gen_folder_parity.py con el Python real de escritorio (folder_import.py). */
public class FolderParityTest {
    @Test public void titlesFromFilenamesMatchPython() {
        assertEquals("Artista - Canci\u00f3n en vivo", FolderImport.titleFromFilename("03 - Artista - Canci\u00f3n_en_vivo"));
        assertEquals("Tema", FolderImport.titleFromFilename("01. Tema"));
        assertEquals("Otro tema", FolderImport.titleFromFilename("(12) Otro tema"));
        assertEquals("Sin numero", FolderImport.titleFromFilename("Sin numero"));
        assertEquals("Tema pegado", FolderImport.titleFromFilename("7)Tema_pegado"));
        assertEquals("Solo", FolderImport.titleFromFilename("  042 - Solo  "));
        assertEquals("1999 - A\u00f1o", FolderImport.titleFromFilename("1999 - A\u00f1o"));
        assertEquals("", FolderImport.titleFromFilename("_ _"));
        assertEquals("Artista \u2013 Canci\u00f3n \u2013 Extra", FolderImport.titleFromFilename("Artista \u2013 Canci\u00f3n \u2013 Extra"));
    }

    @Test public void nameSplittingMatchesPython() {
        assertArrayEquals(new String[]{"Canci\u00f3n", "Artista"}, FolderImport.splitName("Artista - Canci\u00f3n"));
        assertArrayEquals(new String[]{"Solo t\u00edtulo", ""}, FolderImport.splitName("Solo t\u00edtulo"));
        assertArrayEquals(new String[]{"B \u2013 C", "A"}, FolderImport.splitName("A \u2013 B \u2013 C"));
        assertArrayEquals(new String[]{"- vac\u00edo", ""}, FolderImport.splitName("- vac\u00edo"));
        assertArrayEquals(new String[]{"Artista -  ", ""}, FolderImport.splitName("Artista -  "));
        assertArrayEquals(new String[]{"Tren al sur - En vivo", "Los Prisioneros"}, FolderImport.splitName("Los Prisioneros - Tren al sur - En vivo"));
        assertArrayEquals(new String[]{"Canci\u00f3n", "\u00d1and\u00fa"}, FolderImport.splitName("\u00d1and\u00fa \u2014 Canci\u00f3n"));
    }

    @Test public void queriesMatchPython() {
        assertEquals("Los Prisioneros - Tren al sur", FolderImport.buildQuery("Tren al sur", "Los Prisioneros"));
        assertEquals("Los Prisioneros - Tren al sur", FolderImport.buildQuery("Los Prisioneros - Tren al sur", "Los Prisioneros"));
        assertEquals("Tema", FolderImport.buildQuery("  Tema  ", ""));
        assertEquals("Tema", FolderImport.buildQuery("Tema", "  "));
        assertEquals("CANCI\u00d3N de \u00d1and\u00fa", FolderImport.buildQuery("CANCI\u00d3N de \u00d1and\u00fa", "\u00f1and\u00fa"));
        assertEquals("Artista Largo - Solo", FolderImport.buildQuery("Solo", "Artista Largo"));
    }

    @Test public void audioFilesAreRecognizedByExtension() {
        assertTrue(FolderImport.isAudio("a.mp3"));
        assertTrue(FolderImport.isAudio("b.FLAC"));
        assertFalse(FolderImport.isAudio("c.txt"));
        assertFalse(FolderImport.isAudio("d"));
        assertTrue(FolderImport.isAudio("e.m4a"));
        assertTrue(FolderImport.isAudio("f.opus"));
        assertFalse(FolderImport.isAudio(".mp3"));
        assertFalse(FolderImport.isAudio("g.mp3.part"));
    }

    @Test public void clockLabelsAndPaths() {
        assertEquals("", FolderImport.clock(0));
        assertEquals("3:35", FolderImport.clock(215));
        assertEquals("1:02:03", FolderImport.clock(3723));
        assertEquals("a.mp3", FolderImport.baseName("/x/y/a.mp3"));
        assertEquals("a", FolderImport.stem("a.mp3"));
    }
}
