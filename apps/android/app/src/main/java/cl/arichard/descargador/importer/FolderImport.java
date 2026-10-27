package cl.arichard.descargador.importer;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Reglas puras para reconocer archivos de una carpeta. Port de folder_import.py de escritorio. */
public final class FolderImport {
    private FolderImport() {}

    public static final int MAX_FILES = 500;
    public static final Set<String> AUDIO_EXTENSIONS = new HashSet<>(Arrays.asList(
            ".mp3", ".m4a", ".aac", ".wav", ".flac", ".ogg", ".opus", ".wma"));
    private static final Pattern TRACK_PREFIX = Pattern.compile("^\\s*(?:\\d{1,3}\\s*[-–.)_]\\s*|\\(\\d{1,3}\\)\\s*)");
    private static final Pattern NAME_SPLIT = Pattern.compile("\\s+[-–—]\\s+");

    public static boolean isAudio(String fileName) {
        int dot = fileName == null ? -1 : fileName.lastIndexOf('.');
        return dot > 0 && AUDIO_EXTENSIONS.contains(fileName.substring(dot).toLowerCase(Locale.ROOT));
    }

    private static String collapse(String text) {
        String trimmed = text.trim();
        return trimmed.isEmpty() ? "" : String.join(" ", trimmed.split("\\s+"));
    }

    /** «03 - Artista - Canción_en_vivo» → «Artista - Canción en vivo». */
    public static String titleFromFilename(String stem) {
        return collapse(TRACK_PREFIX.matcher(stem.replace("_", " ")).replaceFirst(""));
    }

    /** {título, artista}: «Artista - Canción» se separa; sin separador todo es el título. */
    public static String[] splitName(String name) {
        String[] parts = NAME_SPLIT.split(name, 2);
        if (parts.length == 2 && !parts[0].trim().isEmpty() && !parts[1].trim().isEmpty()) return new String[]{parts[1], parts[0]};
        return new String[]{name, ""};
    }

    /** «artista - título», sin repetir el artista si ya está en el título. */
    public static String buildQuery(String title, String artist) {
        String cleanTitle = collapse(title), cleanArtist = collapse(artist);
        if (!cleanArtist.isEmpty() && !cleanTitle.toLowerCase(Locale.ROOT).contains(cleanArtist.toLowerCase(Locale.ROOT)))
            return cleanArtist + " - " + cleanTitle;
        return cleanTitle;
    }

    /** M:SS o H:MM:SS para la duración de un archivo, o vacío si no se conoce. */
    public static String clock(long seconds) {
        if (seconds <= 0) return "";
        long h = seconds / 3600, m = seconds % 3600 / 60, s = seconds % 60;
        return h > 0 ? String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) : String.format(Locale.ROOT, "%d:%02d", m, s);
    }

    /** Último tramo de una ruta. */
    public static String baseName(String path) {
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    /** Extrae el nombre sin extensión. */
    public static String stem(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

}
