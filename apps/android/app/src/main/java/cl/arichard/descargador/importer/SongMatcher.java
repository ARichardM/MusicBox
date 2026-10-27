package cl.arichard.descargador.importer;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Compara una canción pedida con lo que devolvió YouTube. Es un port de coincidencias.py de escritorio: las
 * puntuaciones no son probabilidades, y una elección automática exige título, artista, versión y duración.
 */
public final class SongMatcher {
    private SongMatcher() {}

    // Los límites de palabra se escriben como lookarounds para que den lo mismo en Android y en pruebas de JVM.
    static final String B = "(?<![\\p{L}\\p{N}_])";
    static final String E = "(?![\\p{L}\\p{N}_])";
    private static final int CI = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;

    private static final Pattern DECORATIONS = Pattern.compile(
            B + "(?:official\\s+(?:music\\s+)?(?:video|audio)|(?:video|audio)\\s+oficial|music\\s+video|official|lyrics?|lyric\\s+video|hd|hq|4k|1080p|720p)" + E);
    private static final Pattern[] VERSIONS = {
            Pattern.compile(B + "live" + E + "|" + B + "en vivo" + E), Pattern.compile(B + "cover" + E),
            Pattern.compile(B + "karaoke" + E + "|" + B + "instrumental" + E), Pattern.compile(B + "remix" + E),
            Pattern.compile(B + "slowed" + E + "|" + B + "reverb" + E), Pattern.compile(B + "nightcore" + E + "|" + B + "sped up" + E),
            Pattern.compile(B + "acoustic" + E + "|" + B + "acustic[oa]" + E), Pattern.compile(B + "reaction" + E + "|" + B + "tutorial" + E)};
    private static final Pattern ARTIST_SPLIT = Pattern.compile(
            "\\s*(?:[,，、&]|" + B + "and" + E + "|" + B + "y" + E + "|" + B + "feat\\.?\\s|" + B + "ft\\.?\\s)\\s*", CI);

    /** Un resultado de búsqueda reducido a lo que se compara. */
    public static final class Candidate {
        public final String id, title, channel;
        public final double durationSeconds;
        public Candidate(String id, String title, String channel, double durationSeconds) {
            this.id = id; this.title = title == null ? "" : title; this.channel = channel == null ? "" : channel;
            this.durationSeconds = durationSeconds;
        }
    }

    public static final class Match {
        public final boolean exact, fullExact, autoSelect;
        public final double score;
        public final String reviewReason;
        Match(boolean exact, double score, boolean fullExact, boolean autoSelect, String reviewReason) {
            this.exact = exact; this.score = score; this.fullExact = fullExact; this.autoSelect = autoSelect; this.reviewReason = reviewReason;
        }
    }

    private static String casefold(String text) { return text.toLowerCase(Locale.ROOT).replace("ß", "ss"); }

    private static boolean combining(int type) {
        return type == Character.NON_SPACING_MARK || type == Character.COMBINING_SPACING_MARK || type == Character.ENCLOSING_MARK;
    }

    /** Minúsculas sin acentos latinos (conserva el dakuten japonés), solo letras y números, espacios simples. */
    public static String normalize(String text) {
        String folded = casefold(Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFKC));
        StringBuilder latin = new StringBuilder();
        for (int i = 0; i < folded.length(); ) {
            int cp = folded.codePointAt(i);
            i += Character.charCount(cp);
            String ch = new String(Character.toChars(cp));
            if (Character.UnicodeScript.of(cp) == Character.UnicodeScript.LATIN) {
                String decomposed = Normalizer.normalize(ch, Normalizer.Form.NFD);
                for (int j = 0; j < decomposed.length(); ) {
                    int part = decomposed.codePointAt(j);
                    j += Character.charCount(part);
                    if (!combining(Character.getType(part))) latin.appendCodePoint(part);
                }
            } else {
                latin.append(ch);
            }
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < latin.length(); ) {
            int cp = latin.codePointAt(i);
            i += Character.charCount(cp);
            if (combining(Character.getType(cp))) continue;
            out.appendCodePoint(Character.isLetterOrDigit(cp) ? cp : ' ');
        }
        return String.join(" ", out.toString().trim().split("\\s+")).trim();
    }

    static String content(String text) {
        return String.join(" ", DECORATIONS.matcher(normalize(text)).replaceAll(" ").trim().split("\\s+")).trim();
    }

    // --- difflib.SequenceMatcher(autojunk=False).ratio() ---

    private static int[] longest(String a, String b, Map<Character, List<Integer>> b2j, int alo, int ahi, int blo, int bhi) {
        int besti = alo, bestj = blo, bestsize = 0;
        Map<Integer, Integer> j2len = new HashMap<>();
        for (int i = alo; i < ahi; i++) {
            Map<Integer, Integer> newj2len = new HashMap<>();
            List<Integer> positions = b2j.get(a.charAt(i));
            if (positions != null) for (int j : positions) {
                if (j < blo) continue;
                if (j >= bhi) break;
                Integer previous = j2len.get(j - 1);
                int k = (previous == null ? 0 : previous) + 1;
                newj2len.put(j, k);
                if (k > bestsize) { besti = i - k + 1; bestj = j - k + 1; bestsize = k; }
            }
            j2len = newj2len;
        }
        return new int[]{besti, bestj, bestsize};
    }

    private static int matching(String a, String b, Map<Character, List<Integer>> b2j, int alo, int ahi, int blo, int bhi) {
        if (alo >= ahi || blo >= bhi) return 0;
        int[] best = longest(a, b, b2j, alo, ahi, blo, bhi);
        int i = best[0], j = best[1], k = best[2];
        if (k == 0) return 0;
        return k + matching(a, b, b2j, alo, i, blo, j) + matching(a, b, b2j, i + k, ahi, j + k, bhi);
    }

    /** La misma razón 2·M/T que difflib. */
    public static double ratio(String a, String b) {
        int total = a.length() + b.length();
        if (total == 0) return 1.0;
        Map<Character, List<Integer>> b2j = new HashMap<>();
        for (int j = 0; j < b.length(); j++) b2j.computeIfAbsent(b.charAt(j), key -> new ArrayList<>()).add(j);
        return 2.0 * matching(a, b, b2j, 0, a.length(), 0, b.length()) / total;
    }

    static double similarity(String wanted, String found) {
        if (wanted.isEmpty() || found.isEmpty()) return 0.0;
        double sequence = ratio(wanted, found);
        Set<String> left = new HashSet<>(List.of(wanted.split(" "))), right = new HashSet<>(List.of(found.split(" ")));
        Set<String> common = new HashSet<>(left);
        common.retainAll(right);
        double token = 2.0 * common.size() / (left.size() + right.size());
        return Math.max(sequence, token);
    }

    /** Segundos de "3:45", "1:02:03" o un número; null si no es válido o no es positivo. */
    public static Double seconds(String value) {
        if (value == null) return null;
        try {
            double total;
            if (value.contains(":")) {
                String[] parts = value.split(":", -1);
                if (parts.length != 2 && parts.length != 3) return null;
                total = 0;
                for (String part : parts) total = total * 60 + Integer.parseInt(part.trim());
            } else {
                total = Double.parseDouble(value.trim());
            }
            return Double.isFinite(total) && total > 0 ? total : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean has(Pattern pattern, String text) { return pattern.matcher(text).find(); }

    private static boolean containsWord(String text, String part) {
        return Pattern.compile(B + Pattern.quote(part) + E).matcher(text).find();
    }

    public static Match score(String query, Candidate candidate, String title, String artist, String duration) {
        String wantedFull = normalize(query);
        String foundFull = normalize(candidate.title);
        String wantedTitle = content(title == null || title.isEmpty() ? query : title);
        String foundTitle = content(candidate.title);
        String wantedArtist = normalize(artist);
        String channel = normalize(candidate.channel);
        List<String> artistParts = new ArrayList<>();
        for (String part : ARTIST_SPLIT.split(artist == null ? "" : artist, -1)) {
            String normalized = normalize(part);
            if (!normalized.isEmpty()) artistParts.add(normalized);
        }
        for (String part : artistParts)
            foundTitle = String.join(" ", Pattern.compile(B + Pattern.quote(part) + E).matcher(foundTitle).replaceAll(" ").trim().split("\\s+")).trim();
        boolean artistMatch = !artistParts.isEmpty();
        for (String part : artistParts) if (!containsWord(foundFull + " " + channel, part)) artistMatch = false;
        double titleScore = similarity(wantedTitle, foundTitle);
        double nameScore = Math.max(titleScore, similarity(content(query), content(candidate.title)));
        double score = nameScore * .80;
        if (!wantedArtist.isEmpty()) score += artistMatch ? .20 : -.15;
        Double wantedSeconds = seconds(duration);
        Double foundSeconds = candidate.durationSeconds > 0 ? Double.valueOf(candidate.durationSeconds) : null;
        if (wantedSeconds != null && foundSeconds != null) {
            double difference = Math.abs(wantedSeconds - foundSeconds);
            score += .10 * Math.max(-1, 1 - difference / Math.max(12, wantedSeconds * .12));
        }
        for (Pattern variant : VERSIONS) if (has(variant, foundFull) && !has(variant, wantedFull)) score -= .18;
        boolean fullExact = !wantedFull.isEmpty() && wantedFull.equals(foundFull);
        boolean titleExact = !wantedTitle.isEmpty() && wantedTitle.equals(foundTitle);
        String reason = "";
        boolean versionDiffers = false;
        for (Pattern variant : VERSIONS) if (has(variant, wantedFull) != has(variant, foundFull)) versionDiffers = true;
        if (wantedTitle.isEmpty() || foundFull.isEmpty()) {
            reason = "Faltan datos del título para verificar la coincidencia.";
        } else if (!wantedArtist.isEmpty() && !artistMatch && !fullExact) {
            reason = "No se pudo confirmar el artista de la canción.";
        } else if (!fullExact && titleScore < (!wantedArtist.isEmpty() ? .82 : .86)) {
            reason = "El título no coincide lo suficiente; la mejor alternativa podría ser otra canción.";
        } else if (versionDiffers) {
            reason = "La versión difiere: directo, cover, remix, velocidad u otro arreglo.";
        } else if (wantedSeconds != null && foundSeconds != null && Math.abs(wantedSeconds - foundSeconds) > Math.max(25, wantedSeconds * .18)) {
            reason = "La duración difiere demasiado de la canción de la lista.";
        }
        return new Match(fullExact || (titleExact && (wantedArtist.isEmpty() || artistMatch)), score, fullExact, reason.isEmpty(), reason);
    }

    /** Índice de la mejor opción; los empates conservan el orden de búsqueda. -1 si no hay candidatos. */
    public static int best(String query, List<Candidate> candidates, String title, String artist, String duration) {
        int index = -1;
        Match best = null;
        for (int i = 0; i < candidates.size(); i++) {
            Match match = score(query, candidates.get(i), title, artist, duration);
            if (best == null || (match.fullExact ? 1 : 0) > (best.fullExact ? 1 : 0)
                    || ((match.fullExact ? 1 : 0) == (best.fullExact ? 1 : 0) && match.score > best.score)) { index = i; best = match; }
        }
        return index;
    }
}
