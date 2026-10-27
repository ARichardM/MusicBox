package cl.arichard.descargador.importer;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lee listas de canciones pegadas como texto. Es un port de lectura_listas.py, importar_texto.py y
 * clean_queries de canciones.py de escritorio: no adivina títulos ni artistas que no estén en el texto.
 */
public final class TextImport {
    private TextImport() {}

    public static final int MAX_QUERIES = 500;
    private static final String B = SongMatcher.B, E = SongMatcher.E;
    private static final int CI = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;

    public static final Pattern DURATION = Pattern.compile("^(?:\\d{1,2}:)?\\d{1,3}:[0-5]\\d$");
    private static final Pattern VIDEO_TITLE = Pattern.compile("opening|ending|creditless|" + B + "PV" + E + "|TV\\s*アニメ", CI);
    private static final Pattern VIDEO_METADATA = Pattern.compile(
            B + "(?:OP|ED|OST|AMV|MAD|MV|HD|FPS|4K)" + E + "|music video|ノンクレジット|ノン.?クレジット|remake|extended theme", CI);
    private static final Pattern SEPARATOR = Pattern.compile("\\s*[·•●]\\s*");
    private static final Pattern CREDIT = Pattern.compile(B + "by\\s+([^|]+)", CI);
    private static final Pattern DATED = Pattern.compile("\\s-\\s([^|]+?)\\s*\\((?:19|20)\\d{2}\\)(?:\\s|$)");
    private static final Pattern CREDIT_TAIL = Pattern.compile("\\s*[\\[(](?:official" + E + "|lyrics?" + E + "|HD" + E + "|4K" + E + ").*", CI);
    private static final Pattern TRAILING_TIME = Pattern.compile("\\s+(?:\\d{1,2}:)?\\d{1,3}:[0-5]\\d$");
    private static final Pattern QUERY_TIME = Pattern.compile("\\s+\\d{1,2}:\\d{2}(?::\\d{2})?\\s*$");
    private static final Pattern LEADING_NUMBER = Pattern.compile("^\\d{1,3}[.)]\\s+");
    private static final Pattern BULLET = Pattern.compile("^\\s*(?:[•●▪]\\s*|\\d{1,3}[.)]\\s+)");
    private static final Set<String> NOT_CREDITS = new HashSet<>(Arrays.asList("me", "you", "us", "my side", "your side", "myself"));

    // --- Utilidades ---

    private static String collapse(String text) { return text.trim().isEmpty() ? "" : String.join(" ", text.trim().split("\\s+")); }

    private static boolean hasAlnum(String text) {
        for (int i = 0; i < text.length(); i++) if (Character.isLetterOrDigit(text.charAt(i))) return true;
        return false;
    }

    private static String strip(String text, String chars) {
        int start = 0, end = text.length();
        while (start < end && chars.indexOf(text.charAt(start)) >= 0) start++;
        while (end > start && chars.indexOf(text.charAt(end - 1)) >= 0) end--;
        return text.substring(start, end);
    }

    private static boolean endsWithAny(String text, String... endings) {
        for (String ending : endings) if (text.endsWith(ending)) return true;
        return false;
    }

    private static String casefold(String text) { return text.toLowerCase(Locale.ROOT).replace("ß", "ss"); }

    private static List<String> splitLines(String text) {
        return new ArrayList<>(Arrays.asList(text.split("\\r\\n|\\n|\\r|\\u000b|\\u000c|\\u001c|\\u001d|\\u001e|\\u0085|\\u2028|\\u2029", -1)));
    }

    // --- lectura_listas.py ---

    /** Crédito explícito de un video ("by X", "- X (2019)"); vacío si no hay. */
    public static String videoArtist(String title) {
        if (!(VIDEO_TITLE.matcher(title).find() || VIDEO_METADATA.matcher(title).find() || title.contains("|"))) return "";
        Matcher credit = CREDIT.matcher(title);
        if (credit.find()) {
            String artist = strip(credit.group(1), " \t\"“”「」");
            artist = strip(CREDIT_TAIL.matcher(artist).replaceFirst(""), " \t\n\r\f\u000b");
            if (!artist.isEmpty() && !NOT_CREDITS.contains(casefold(artist)) && !endsWithAny(artist, "…", "..."))
                return artist;
        }
        Matcher dated = DATED.matcher(title);
        return dated.find() ? dated.group(1).trim() : "";
    }

    public static String songQuery(String title, String artist) {
        if (artist != null && !artist.isEmpty() && !Pattern.compile(B + Pattern.quote(artist) + E, CI).matcher(title).find())
            return artist + " - " + title;
        return title;
    }

    /** Una línea de lista pegada sin numeración, tiempo final ni adornos; vacía si no queda nada útil. */
    public static String tidyLine(String raw) {
        String text = collapse(Normalizer.normalize(raw, Normalizer.Form.NFKC));
        if (DURATION.matcher(text).matches()) return "";
        Matcher duration = TRAILING_TIME.matcher(text);
        if (duration.find() && !endsWithAny(text.substring(0, duration.start()).replaceAll("\\s+$", ""), "-", "–", "—", ":"))
            text = text.substring(0, duration.start());
        text = LEADING_NUMBER.matcher(text).replaceFirst("");
        String original = text;
        text = strip(text, " \t-|–—·•");
        if (text.isEmpty() || !hasAlnum(text)) return "";
        if (text.length() == 1 && original.replaceAll("\\s+$", "").endsWith("-")) return "";
        return text;
    }

    private static String artistLine(String text) {
        String[] parts = SEPARATOR.split(text, 2);
        if (parts.length == 2) {
            String artist = strip(parts[0], " -");
            if (!artist.isEmpty()) return artist;
        }
        return null;
    }

    private static String key(String value) { return casefold(value).replaceAll("[^\\p{L}\\p{N}]+", ""); }

    private static String combineTitleArtist(String title, String metadata) {
        String artist = artistLine(metadata);
        if (artist == null) return title;
        return key(title).startsWith(key(artist)) ? title : artist + " - " + title;
    }

    private static List<String> uniqueLines(List<String> lines) {
        List<String> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String line : lines) if (!line.isEmpty() && seen.add(casefold(line))) result.add(line);
        return result;
    }

    /** Limpieza explícita del texto pegado: tiempos, título/subtítulo y canales de openings. */
    public static List<String> organizePlaylistText(String text, Map<String, String> uploaders) {
        List<String> lines = new ArrayList<>();
        for (String line : splitLines(text)) {
            String cleaned = tidyLine(line);
            if (!cleaned.isEmpty()) lines.add(cleaned);
        }
        List<String> result = new ArrayList<>();
        int index = 0;
        while (index < lines.size()) {
            String title = lines.get(index);
            if (index + 1 < lines.size() && artistLine(title) == null) {
                String subtitle = lines.get(index + 1);
                if (artistLine(subtitle) != null) {
                    result.add(combineTitleArtist(title, subtitle));
                    index += 2;
                    continue;
                }
                if (VIDEO_TITLE.matcher(title).find() && !VIDEO_TITLE.matcher(subtitle).find()
                        && !subtitle.contains(" - ") && subtitle.length() <= 50) {
                    if (uploaders != null) uploaders.put(casefold(title), subtitle);
                    result.add(title);
                    index += 2;
                    continue;
                }
            }
            result.add(title);
            index++;
        }
        return uniqueLines(result);
    }

    // --- canciones.py: clean_queries ---

    /** Una consulta por línea: sin viñetas ni numeración ni tiempos, sin repetir; hasta {@link #MAX_QUERIES}. */
    public static List<String> cleanQueries(String text, boolean enforceLimit) {
        List<String> queries = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String raw : splitLines(text)) {
            String line = BULLET.matcher(raw).replaceFirst("");
            Matcher duration = QUERY_TIME.matcher(line);
            if (duration.find() && !endsWithAny(line.substring(0, duration.start()).replaceAll("\\s+$", ""), "-", "–", "—", ":"))
                line = line.substring(0, duration.start());
            line = strip(collapse(line), " \t|•");
            if (line.isEmpty() || !hasAlnum(line)) continue;
            if (seen.add(casefold(line))) queries.add(line);
        }
        if (enforceLimit && queries.size() > MAX_QUERIES)
            throw new IllegalArgumentException("Busca hasta " + MAX_QUERIES + " canciones por tanda.");
        return queries;
    }

    // --- importar_texto.py ---

    public static final class ImportedSong {
        public String title, artist = "", album = "", duration = "", uploader = "", reviewReason = "", rawText = "";
        public boolean structured = true;
        public ImportedSong(String title) { this.title = title; }
        public String query() { return songQuery(title, artist); }
    }

    public static final class ParsedPlaylist {
        public final List<ImportedSong> songs;
        public final int timedBlocks;
        public final List<String> warnings;
        ParsedPlaylist(List<ImportedSong> songs, int timedBlocks, List<String> warnings) {
            this.songs = songs; this.timedBlocks = timedBlocks; this.warnings = warnings;
        }
    }

    private static final Pattern JOINER = Pattern.compile("(?:[,，、&]|(?:and|y|feat\\.?|ft\\.?)(?=\\s|$))", CI);
    private static final Pattern SYMBOL_START = Pattern.compile("^[,，、&]\\s*\\S");
    private static final Pattern DANGLING = Pattern.compile("(?:[,，、&]|\\s(?:and|y|feat\\.?|ft\\.?))$", CI);

    /** {título, artistas, álbum} o null si las líneas no encajan en un registro de canción. */
    static String[] splitSongFields(List<String> body) {
        if (body.size() < 2) return null;
        if (body.size() == 2 && body.get(0).contains(" - ") && body.get(1).contains(" - ")) return null;
        String title = body.get(0), performer = body.get(1);
        if (!hasAlnum(performer)) return null;
        int cursor = 2;
        while (cursor < body.size()) {
            String line = body.get(cursor);
            if (JOINER.matcher(line).matches()) {
                if (cursor + 1 >= body.size() || !hasAlnum(body.get(cursor + 1))) return null;
                boolean comma = line.equals(",") || line.equals("，") || line.equals("、");
                performer += (comma ? ", " : " " + line + " ") + body.get(cursor + 1);
                cursor += 2;
            } else if (SYMBOL_START.matcher(line).find()) {
                boolean comma = line.startsWith(",") || line.startsWith("，") || line.startsWith("、");
                performer += (comma ? "" : " ") + line;
                cursor++;
            } else if (DANGLING.matcher(performer).find()) {
                performer += " " + line;
                cursor++;
            } else {
                break;
            }
        }
        if (DANGLING.matcher(performer).find()) return null;
        if (body.size() - cursor > 1) return null;
        return new String[]{title, performer, cursor < body.size() ? body.get(cursor) : ""};
    }

    private static boolean plausible(List<String> body) { return splitSongFields(body) != null; }

    private static List<String> flat(List<List<String>> parts) {
        List<String> all = new ArrayList<>();
        for (List<String> part : parts) all.addAll(part);
        return all;
    }

    private static boolean allDash(List<String> lines) {
        for (String line : lines) if (!line.contains(" - ")) return false;
        return true;
    }

    private static boolean isHeading(List<String> part) {
        return part.size() == 1 && (part.get(0).startsWith("#") || VIDEO_TITLE.matcher(part.get(0)).find()
                || VIDEO_METADATA.matcher(part.get(0)).find());
    }

    private static boolean startsWithHash(List<String> part) { return part.size() == 1 && part.get(0).startsWith("#"); }

    /** Devuelve null si no hay evidencia suficiente del formato de lista exportada (título, artista(s), álbum, tiempo). */
    public static ParsedPlaylist parseExportedPlaylist(String text) {
        List<Object[]> frames = new ArrayList<>();   // {List<List<String>> paragraphs, String duration}
        List<List<String>> paragraphs = new ArrayList<>();
        List<String> paragraph = new ArrayList<>();
        for (String rawLine : splitLines(text)) {
            String line = collapse(rawLine.replaceFirst("^\uFEFF+", ""));
            if (line.isEmpty()) {
                if (!paragraph.isEmpty()) { paragraphs.add(paragraph); paragraph = new ArrayList<>(); }
            } else if (DURATION.matcher(line).matches()) {
                if (!paragraph.isEmpty()) { paragraphs.add(paragraph); paragraph = new ArrayList<>(); }
                if (!paragraphs.isEmpty()) { frames.add(new Object[]{paragraphs, line}); paragraphs = new ArrayList<>(); }
            } else {
                paragraph.add(line);
            }
        }
        if (!paragraph.isEmpty()) paragraphs.add(paragraph);

        if (frames.isEmpty()) return null;
        @SuppressWarnings("unchecked") List<List<String>> first = (List<List<String>>) frames.get(0)[0];
        List<String> firstFlat = flat(first);
        boolean recognizable = plausible(firstFlat) || plausible(first.get(first.size() - 1))
                || (firstFlat.size() >= 4 && !allDash(firstFlat));
        if (!recognizable) return null;

        List<ImportedSong> songs = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        for (Object[] frame : frames) {
            @SuppressWarnings("unchecked") List<List<String>> parts = (List<List<String>>) frame[0];
            String duration = (String) frame[1];
            // split_parts: un título pegado antes de la lista no se fusiona con la primera canción.
            List<String> flatParts = flat(parts);
            List<List<String>> prefixes; List<String> body;
            boolean explicitHeading = parts.size() > 1;
            if (explicitHeading) for (List<String> part : parts.subList(0, parts.size() - 1)) if (!startsWithHash(part)) explicitHeading = false;
            if (plausible(flatParts) && !explicitHeading) {
                prefixes = new ArrayList<>(); body = flatParts;
            } else {
                boolean headings = true;
                if (!plausible(flatParts) && parts.size() > 1) {
                    for (List<String> part : parts.subList(0, parts.size() - 1)) if (!isHeading(part)) headings = false;
                    if (!headings) { prefixes = new ArrayList<>(); body = flatParts; }
                    else { prefixes = new ArrayList<>(parts.subList(0, parts.size() - 1)); body = parts.get(parts.size() - 1); }
                } else {
                    prefixes = new ArrayList<>(parts.subList(0, parts.size() - 1)); body = parts.get(parts.size() - 1);
                }
            }
            if (!plausible(body)) { keepAmbiguous(songs, warnings, flatParts, duration); continue; }
            for (List<String> prefix : prefixes) keepExtra(songs, prefix);
            songs.add(makeSong(body, duration));
        }
        for (List<String> trailing : paragraphs) {
            if (plausible(trailing)) songs.add(makeSong(trailing, ""));
            else keepAmbiguous(songs, warnings, trailing, "");
            warnings.add("Se conservó texto final sin duración para revisión.");
        }
        return new ParsedPlaylist(songs, frames.size(), warnings);
    }

    private static ImportedSong makeSong(List<String> body, String duration) {
        String[] fields = splitSongFields(body);
        boolean isVideo = fields[2].isEmpty() && (VIDEO_TITLE.matcher(fields[0]).find() || VIDEO_METADATA.matcher(fields[0]).find());
        ImportedSong song = new ImportedSong(fields[0]);
        song.artist = isVideo ? videoArtist(fields[0]) : fields[1];
        song.album = fields[2];
        song.duration = duration;
        song.uploader = isVideo ? fields[1] : "";
        return song;
    }

    private static void keepExtra(List<ImportedSong> songs, List<String> lines) {
        for (String line : lines) {
            String cleaned = line.replaceFirst("^#{1,6}\\s+", "").trim();
            if (!cleaned.isEmpty()) {
                ImportedSong song = new ImportedSong(cleaned);
                song.structured = false;
                songs.add(song);
            }
        }
    }

    private static void keepAmbiguous(List<ImportedSong> songs, List<String> warnings, List<String> lines, String duration) {
        String reason = "No se pudieron separar título, artistas y álbum. Revisa el texto original y corrige esta fila.";
        ImportedSong song = new ImportedSong(lines.get(0));
        song.duration = duration; song.reviewReason = reason; song.rawText = String.join("\n", lines);
        songs.add(song);
        warnings.add(reason);
    }
}
