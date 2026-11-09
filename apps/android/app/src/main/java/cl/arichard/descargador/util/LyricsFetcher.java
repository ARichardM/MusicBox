package cl.arichard.descargador.util;

import cl.arichard.descargador.model.Song;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Motor de letras para Android: analiza formato LRC sincronizado y texto plano,
 * calcula el verso activo según la posición del reproductor y consulta archivos
 * locales (.lrc), LRCLIB y YouTube Music con caché en memoria.
 */
public final class LyricsFetcher {
    private static final String LRCLIB_BASE = "https://lrclib.net/api";
    private static final Pattern LRC_TIME_RE = Pattern.compile("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?]");
    /** Marcas de tiempo por palabra del LRC extendido («<00:22.82>You <00:23.23>know»): se quitan del texto visible. */
    private static final Pattern LRC_WORD_TIME_RE = Pattern.compile("<\\d{1,3}:\\d{2}(?:[.:]\\d{1,3})?>");
    private static final Pattern LRC_OFFSET_RE = Pattern.compile("(?i)^\\[offset:\\s*([+-]?\\d+)\\s*]$");
    private static final Pattern LRC_META_RE = Pattern.compile("^\\[[a-zA-Z]{2,12}:.*]$");
    private static final Pattern NOISE_BRACKETS_RE = Pattern.compile(
            "(?i)[(\\[][^)\\]]*\\b(official|oficial|video|audio|lyric|lyrics|letra|visualizer|hd|4k|hq|remaster(?:ed)?|live|en\\s+vivo|mv|clip|sub\\s*espa[nñ]ol|karaoke|instrumental|version|versi[oó]n)[^)\\]]*[)\\]]");
    private static final Pattern FEAT_RE = Pattern.compile("(?i)\\s+(?:feat\\.?|ft\\.?|con|with)\\s+.*$");
    private static final Pattern TOPIC_RE = Pattern.compile("(?i)\\s*-\\s*topic$");
    private static final Pattern MULTI_SPACE_RE = Pattern.compile("\\s{2,}");

    public static final class Line {
        public final long timeMs;
        public final String text;

        public Line(long timeMs, String text) {
            this.timeMs = timeMs;
            this.text = text == null ? "" : text;
        }
    }

    public static final class Result {
        public final List<Line> lines;
        public final boolean synced;
        public final String source;
        public final boolean instrumental;

        public Result(List<Line> lines, boolean synced, String source, boolean instrumental) {
            this.lines = lines == null ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList<>(lines));
            this.synced = synced;
            this.source = source == null ? "" : source;
            this.instrumental = instrumental;
        }

        public boolean isEmpty() {
            return lines.isEmpty() && !instrumental;
        }

        public static Result empty() {
            return new Result(Collections.emptyList(), false, "", false);
        }
    }

    public interface HttpTransport {
        String get(String url) throws Exception;
    }

    static HttpTransport httpTransport = LyricsFetcher::networkGet;

    private static final int MAX_CACHE = 96;
    private static final Map<String, Result> CACHE = new LinkedHashMap<String, Result>(32, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Result> eldest) {
            return size() > MAX_CACHE;
        }
    };

    private LyricsFetcher() {}

    public static synchronized void clearCache() {
        CACHE.clear();
    }

    private static String networkGet(String urlString) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlString).openConnection();
        try {
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(8000);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("User-Agent", "MusicBox/1.0 (https://github.com/ARichardM/MusicBox)");
            int status = conn.getResponseCode();
            if (status < 200 || status >= 300) throw new java.io.IOException("LRCLIB HTTP " + status);
            StringBuilder out = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) out.append(line).append('\n');
            }
            return out.toString();
        } finally {
            conn.disconnect();
        }
    }

    /** Convierte texto en formato LRC en una lista ordenada de líneas sincronizadas. */
    public static List<Line> parseLrc(String raw) {
        if (raw == null || raw.isEmpty()) return Collections.emptyList();
        long offsetMs = 0;
        List<Line> entries = new ArrayList<>();
        String[] rawLines = raw.split("\\r?\\n");
        for (String rawLine : rawLines) {
            String stripped = rawLine.trim();
            if (stripped.isEmpty()) continue;
            Matcher offsetMatch = LRC_OFFSET_RE.matcher(stripped);
            if (offsetMatch.matches()) {
                try {
                    offsetMs = Long.parseLong(offsetMatch.group(1));
                } catch (NumberFormatException ignored) {}
                continue;
            }
            Matcher matcher = LRC_TIME_RE.matcher(stripped);
            List<Long> stamps = new ArrayList<>();
            int lastEnd = 0;
            while (matcher.find()) {
                int minutes = Integer.parseInt(matcher.group(1));
                int seconds = Integer.parseInt(matcher.group(2));
                String frac = matcher.group(3);
                int ms = 0;
                if (frac != null && !frac.isEmpty()) {
                    if (frac.length() == 1) ms = Integer.parseInt(frac) * 100;
                    else if (frac.length() == 2) ms = Integer.parseInt(frac) * 10;
                    else ms = Integer.parseInt(frac.substring(0, 3));
                }
                stamps.add((minutes * 60L + seconds) * 1000L + ms);
                lastEnd = matcher.end();
            }
            if (stamps.isEmpty()) {
                if (LRC_META_RE.matcher(stripped).matches()) continue;
                continue;
            }
            String text = stripped.substring(lastEnd).trim();
            text = LRC_TIME_RE.matcher(text).replaceAll("").trim();
            text = MULTI_SPACE_RE.matcher(LRC_WORD_TIME_RE.matcher(text).replaceAll("")).replaceAll(" ").trim();
            for (long stamp : stamps) {
                long adjusted = Math.max(0L, stamp - offsetMs);
                entries.add(new Line(adjusted, text));
            }
        }
        Collections.sort(entries, new Comparator<Line>() {
            @Override
            public int compare(Line a, Line b) {
                return Long.compare(a.timeMs, b.timeMs);
            }
        });
        List<Line> cleaned = new ArrayList<>();
        for (Line item : entries) {
            if (item.text.isEmpty() && (cleaned.isEmpty() || cleaned.get(cleaned.size() - 1).text.isEmpty())) {
                continue;
            }
            cleaned.add(item);
        }
        while (!cleaned.isEmpty() && cleaned.get(cleaned.size() - 1).text.isEmpty()) {
            cleaned.remove(cleaned.size() - 1);
        }
        boolean hasText = false;
        for (Line item : cleaned) {
            if (!item.text.isEmpty()) {
                hasText = true;
                break;
            }
        }
        return hasText ? cleaned : Collections.<Line>emptyList();
    }

    /** Convierte texto plano sin marcas de tiempo en líneas legibles. */
    public static List<Line> plainLines(String raw) {
        if (raw == null || raw.trim().isEmpty()) return Collections.emptyList();
        List<Line> out = new ArrayList<>();
        boolean prevBlank = true;
        for (String line : raw.split("\\r?\\n")) {
            String text = line.trim();
            if (LRC_META_RE.matcher(text).matches()) continue;
            text = LRC_TIME_RE.matcher(text).replaceAll("").trim();
            text = MULTI_SPACE_RE.matcher(LRC_WORD_TIME_RE.matcher(text).replaceAll("")).replaceAll(" ").trim();
            if (text.isEmpty()) {
                if (!prevBlank) {
                    out.add(new Line(-1L, ""));
                    prevBlank = true;
                }
                continue;
            }
            out.add(new Line(-1L, text));
            prevBlank = false;
        }
        while (!out.isEmpty() && out.get(out.size() - 1).text.isEmpty()) {
            out.remove(out.size() - 1);
        }
        return out;
    }

    /** Devuelve el índice del verso activo para positionMs, o -1 si aún no comienza. */
    public static int activeLineIndex(List<Line> lines, long positionMs) {
        if (lines == null || lines.isEmpty() || positionMs < 0) return -1;
        if (lines.get(0).timeMs < 0) return -1;
        long target = positionMs + 120L;
        int lo = 0, hi = lines.size() - 1, best = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            long t = lines.get(mid).timeMs;
            if (t >= 0 && t <= target) {
                best = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        while (best > 0 && lines.get(best).text.isEmpty()) {
            best--;
        }
        return best;
    }

    public static String cleanTitle(String title) {
        String text = (title == null ? "" : title).trim();
        if (text.isEmpty()) return "";
        text = NOISE_BRACKETS_RE.matcher(text).replaceAll(" ");
        text = FEAT_RE.matcher(text).replaceAll("");
        text = text.replace('“', '"').replace('”', '"').replace("‘", "'").replace("’", "'");
        text = MULTI_SPACE_RE.matcher(text).replaceAll(" ").trim();
        while (!text.isEmpty() && (text.startsWith("-") || text.startsWith("–") || text.startsWith("—") || text.startsWith("|"))) {
            text = text.substring(1).trim();
        }
        while (!text.isEmpty() && (text.endsWith("-") || text.endsWith("–") || text.endsWith("—") || text.endsWith("|"))) {
            text = text.substring(0, text.length() - 1).trim();
        }
        return text;
    }

    public static String cleanArtist(String artist) {
        String text = (artist == null ? "" : artist).trim();
        if (text.isEmpty()) return "";
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.equals("artista desconocido") || lower.equals("unknown artist")
                || lower.equals("youtube") || lower.equals("musicbox")) {
            return "";
        }
        text = TOPIC_RE.matcher(text).replaceAll("");
        text = NOISE_BRACKETS_RE.matcher(text).replaceAll(" ");
        text = MULTI_SPACE_RE.matcher(text).replaceAll(" ").trim();
        return text;
    }

    public static String[] cleanQuery(String title, String artist) {
        String cTitle = cleanTitle(title);
        String cArtist = cleanArtist(artist);
        String[] separators = new String[] {" - ", " – ", " — "};
        for (String sep : separators) {
            int idx = cTitle.indexOf(sep);
            if (idx > 0) {
                String left = cTitle.substring(0, idx).trim();
                String right = cTitle.substring(idx + sep.length()).trim();
                if (!left.isEmpty() && !right.isEmpty()) {
                    if (cArtist.isEmpty() || cArtist.equalsIgnoreCase(left)) {
                        cArtist = cleanArtist(left);
                        cTitle = cleanTitle(right);
                    } else if (cArtist.equalsIgnoreCase(right)) {
                        cTitle = cleanTitle(left);
                    }
                }
                break;
            }
        }
        return new String[] {cTitle, cArtist};
    }

    /** Lee un archivo .lrc junto al archivo de audio local si existe. */
    public static Result readLocal(String filePath) {
        if (filePath == null || filePath.trim().isEmpty() || filePath.startsWith("http")) return null;
        File audioFile = new File(filePath);
        File parent = audioFile.getParentFile();
        if (parent == null) return null;
        String name = audioFile.getName();
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        File[] candidates = new File[] {
                new File(parent, stem + ".lrc"),
                new File(parent, stem + ".LRC")
        };
        for (File lrc : candidates) {
            if (!lrc.isFile() || !lrc.canRead()) continue;
            try {
                byte[] bytes = new byte[(int) Math.min(lrc.length(), 256 * 1024L)];
                try (FileInputStream in = new FileInputStream(lrc)) {
                    int read = in.read(bytes);
                    if (read <= 0) continue;
                    String raw = new String(bytes, 0, read, StandardCharsets.UTF_8);
                    List<Line> synced = parseLrc(raw);
                    if (!synced.isEmpty()) return new Result(synced, true, "Archivo LRC", false);
                    List<Line> plain = plainLines(raw);
                    if (!plain.isEmpty()) return new Result(plain, false, "Archivo LRC", false);
                }
            } catch (Exception ignored) {}
        }
        return null;
    }

    private static Result resultFromLrclibRecord(JSONObject item) {
        if (item == null) return null;
        if (item.optBoolean("instrumental", false)) {
            return new Result(Collections.<Line>emptyList(), false, "LRCLIB", true);
        }
        String syncedRaw = item.optString("syncedLyrics", "").trim();
        if (!syncedRaw.isEmpty()) {
            List<Line> parsed = parseLrc(syncedRaw);
            if (!parsed.isEmpty()) return new Result(parsed, true, "LRCLIB", false);
        }
        String plainRaw = item.optString("plainLyrics", "").trim();
        if (!plainRaw.isEmpty()) {
            List<Line> parsed = plainLines(plainRaw);
            if (!parsed.isEmpty()) return new Result(parsed, false, "LRCLIB", false);
        }
        return null;
    }

    private static String norm(String s) {
        if (s == null) return "";
        return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9áéíóúñü]+", " ").trim();
    }

    /** Busca letras sincronizadas o planas en LRCLIB. */
    public static Result fetchLrclib(String title, String artist, String album, int durationSec) {
        String[] cleaned = cleanQuery(title, artist);
        String cTitle = cleaned[0];
        String cArtist = cleaned[1];
        if (cTitle.isEmpty()) return null;

        if (!cArtist.isEmpty()) {
            try {
                StringBuilder url = new StringBuilder(LRCLIB_BASE).append("/get?track_name=")
                        .append(URLEncoder.encode(cTitle, "UTF-8"))
                        .append("&artist_name=").append(URLEncoder.encode(cArtist, "UTF-8"));
                if (album != null && !album.trim().isEmpty()
                        && !album.equalsIgnoreCase("Álbum desconocido")
                        && !album.equalsIgnoreCase("Unknown album")
                        && !album.equalsIgnoreCase("YouTube")) {
                    url.append("&album_name=").append(URLEncoder.encode(album.trim(), "UTF-8"));
                }
                if (durationSec > 0) {
                    url.append("&duration=").append(durationSec);
                }
                JSONObject direct = new JSONObject(httpTransport.get(url.toString()));
                Result directRes = resultFromLrclibRecord(direct);
                if (directRes != null) return directRes;
            } catch (Exception ignored) {}
        }

        try {
            String query = !cArtist.isEmpty() ? (cTitle + " " + cArtist) : cTitle;
            String searchUrl = LRCLIB_BASE + "/search?q=" + URLEncoder.encode(query, "UTF-8");
            JSONArray array = new JSONArray(httpTransport.get(searchUrl));
            String wantedTitle = norm(cTitle);
            String wantedArtist = norm(cArtist);
            JSONObject bestItem = null;
            double bestScore = -999.0;
            int limit = Math.min(array.length(), 15);
            for (int i = 0; i < limit; i++) {
                JSONObject item = array.optJSONObject(i);
                if (item == null) continue;
                String candTitle = norm(cleanTitle(item.optString("trackName", item.optString("name", ""))));
                String candArtist = norm(cleanArtist(item.optString("artistName", "")));
                if (candTitle.isEmpty()) continue;
                boolean titleMatch = candTitle.equals(wantedTitle)
                        || candTitle.contains(wantedTitle)
                        || wantedTitle.contains(candTitle);
                if (!titleMatch) continue;
                double score = candTitle.equals(wantedTitle) ? 3.0 : 1.5;
                if (!wantedArtist.isEmpty()) {
                    if (candArtist.equals(wantedArtist)) score += 3.0;
                    else if (candArtist.contains(wantedArtist) || wantedArtist.contains(candArtist)) score += 1.8;
                    else score -= 1.2;
                }
                int candDur = item.optInt("duration", 0);
                if (durationSec > 0 && candDur > 0) {
                    int diff = Math.abs(candDur - durationSec);
                    if (diff <= 3) score += 2.0;
                    else if (diff <= 10) score += 1.0;
                    else if (diff > 25) score -= 1.5;
                }
                if (!item.optString("syncedLyrics", "").trim().isEmpty()) score += 1.2;
                else if (!item.optString("plainLyrics", "").trim().isEmpty()) score += 0.4;
                if (score > bestScore) {
                    bestScore = score;
                    bestItem = item;
                }
            }
            if (bestItem != null && bestScore >= 1.5) {
                return resultFromLrclibRecord(bestItem);
            }
        } catch (Exception ignored) {}
        return null;
    }

    /** Busca la letra de una canción combinando archivo local .lrc, LRCLIB y YouTube Music. */
    public static Result find(Song song) {
        if (song == null) return Result.empty();
        int durationSec = song.getDuration() > 0 ? (int) Math.round(song.getDuration() / 1000.0) : 0;
        return find(song.getTitle(), song.getArtist(), song.getAlbum(), durationSec, song.getVideoId(), song.getPath());
    }

    public static Result find(String title, String artist, String album, int durationSec, String videoId, String filePath) {
        String key = ((videoId == null ? "" : videoId.trim()) + "|"
                + (filePath == null ? "" : filePath.trim()) + "|"
                + cleanTitle(title).toLowerCase(Locale.ROOT) + "|"
                + cleanArtist(artist).toLowerCase(Locale.ROOT) + "|"
                + durationSec);
        synchronized (LyricsFetcher.class) {
            Result cached = CACHE.get(key);
            if (cached != null) return cached;
        }

        Result local = readLocal(filePath);
        if (local != null && local.synced) {
            synchronized (LyricsFetcher.class) { CACHE.put(key, local); }
            return local;
        }

        Result remote = fetchLrclib(title, artist, album, durationSec);
        if (remote != null && (remote.synced || remote.instrumental)) {
            synchronized (LyricsFetcher.class) { CACHE.put(key, remote); }
            return remote;
        }

        if (local != null && !local.isEmpty()) {
            synchronized (LyricsFetcher.class) { CACHE.put(key, local); }
            return local;
        }

        if (remote != null && !remote.isEmpty()) {
            synchronized (LyricsFetcher.class) { CACHE.put(key, remote); }
            return remote;
        }

        String vid = videoId == null ? "" : videoId.trim();
        if (!vid.isEmpty()) {
            try {
                YtMusicApi.Lyrics yt = YtMusicApi.lyrics(vid);
                if (yt != null && !yt.text.trim().isEmpty()) {
                    List<Line> lines = plainLines(yt.text);
                    if (!lines.isEmpty()) {
                        Result ytResult = new Result(lines, false, yt.source, false);
                        synchronized (LyricsFetcher.class) { CACHE.put(key, ytResult); }
                        return ytResult;
                    }
                }
            } catch (Exception ignored) {}
        }

        return Result.empty();
    }
}
