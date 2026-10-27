package cl.arichard.descargador.data;

import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import cl.arichard.descargador.model.SearchResult;

/**
 * Recomendaciones "Para ti". Equivale a recommendations.py en escritorio.
 * Se eligen como semillas las canciones de YouTube con más puntaje reciente (ClickStats.seeds), se pide a
 * YouTube las relacionadas con cada una y se mezclan las listas: una canción que aparece cerca del inicio de
 * varias listas sube. La red se inyecta con {@link Fetcher}, así que la lógica se prueba sin conexión.
 */
public class RecommendationEngine {
    public static final int SEED_COUNT = 4;
    public static final int PER_SEED = 15;
    public static final int MAX_SONGS = 20;
    public static final int MAX_ARTISTS = 8;
    public static final int MAX_SECONDS = 600;
    public static final int CACHE_ENTRIES = 3;
    public static final long CACHE_MS = 6L * 3600 * 1000;
    public static final String GENERIC_QUERY = "éxitos música";
    private static final String PREF_CACHE = "cache";
    /** Súbela cuando cambie cómo se arma el resultado, para no mostrar uno guardado con el formato anterior. */
    private static final String CACHE_VERSION = "v2:";
    private static final Pattern TOPIC = Pattern.compile("(?i)\\s*-\\s*topic$");

    public interface Fetcher {
        List<SearchResult> related(String videoId) throws Exception;
        List<SearchResult> search(String query) throws Exception;
    }

    public interface Clock { long now(); }

    /** Lista de relacionadas con el peso de la semilla que la originó. */
    public static final class Weighted {
        final double weight;
        final List<SearchResult> entries;
        public Weighted(double weight, List<SearchResult> entries) { this.weight = weight; this.entries = entries; }
    }

    public static final class Result {
        public final List<SearchResult> songs;
        public final List<String> artists;
        /** seeds, queries, trending u offline. */
        public final String mode;
        public Result(List<SearchResult> songs, List<String> artists, String mode) {
            this.songs = songs; this.artists = artists; this.mode = mode;
        }
        public boolean isEmpty() { return songs.isEmpty(); }
    }

    private static final class Cached {
        final long at;
        final Result result;
        Cached(long at, Result result) { this.at = at; this.result = result; }
    }

    private final Fetcher fetcher;
    private final Clock clock;
    private final SharedPreferences prefs;
    private final Map<String, Cached> cache = new LinkedHashMap<>();

    public RecommendationEngine(Fetcher fetcher, Clock clock, SharedPreferences prefs) {
        this.fetcher = fetcher;
        this.clock = clock;
        this.prefs = prefs;
        load();
    }

    private static final Pattern ARTIST_SPLIT = Pattern.compile(
            "\\s*(?:,|&|(?<![\\p{L}\\p{N}_])and(?![\\p{L}\\p{N}_])|(?<![\\p{L}\\p{N}_])y(?![\\p{L}\\p{N}_])"
                    + "|(?<![\\p{L}\\p{N}_])feat\\.?\\s|(?<![\\p{L}\\p{N}_])ft\\.?\\s)\\s*", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /** "Bad Bunny & Jhay Cortez" → ["Bad Bunny", "Jhay Cortez"]; cada uno cuenta por separado. */
    public static List<String> splitArtists(String text) {
        List<String> artists = new ArrayList<>();
        for (String part : ARTIST_SPLIT.split(text == null ? "" : text)) if (!part.trim().isEmpty()) artists.add(part.trim());
        return artists;
    }

    public static String artistName(SearchResult entry) {
        return TOPIC.matcher(entry.getChannel() == null ? "" : entry.getChannel()).replaceAll("").trim();
    }

    /** Mezcla listas de relacionadas; descarta lo conocido, los duplicados y los videos demasiado largos. */
    public static Result combine(List<Weighted> lists, Set<String> excludeIds, Collection<String> excludeTitles, int limit, String mode) {
        Map<String, Double> scores = new HashMap<>();
        Map<String, SearchResult> items = new HashMap<>();
        for (Weighted list : lists) {
            for (int rank = 0; rank < list.entries.size(); rank++) {
                SearchResult entry = list.entries.get(rank);
                String id = entry.getId();
                if (id.isEmpty() || excludeIds.contains(id)) continue;
                if (entry.getDurationSeconds() > MAX_SECONDS) continue;
                Double current = scores.get(id);
                scores.put(id, (current == null ? 0 : current) + list.weight / (1 + rank));
                if (!items.containsKey(id)) items.put(id, entry);
            }
        }
        List<String> ranked = new ArrayList<>(scores.keySet());
        ranked.sort((a, b) -> {
            int byScore = Double.compare(scores.get(b), scores.get(a));
            return byScore != 0 ? byScore : a.compareTo(b);
        });
        Set<String> seenTitles = new HashSet<>();
        for (String title : excludeTitles) seenTitles.add(ClickStats.normalize(title));
        List<SearchResult> songs = new ArrayList<>();
        Map<String, Double> artistWeight = new HashMap<>();
        for (String id : ranked) {
            SearchResult song = items.get(id);
            if (!seenTitles.add(ClickStats.normalize(song.getTitle()))) continue;
            songs.add(song);
            for (String artist : splitArtists(artistName(song))) {
                Double weight = artistWeight.get(artist);
                artistWeight.put(artist, (weight == null ? 0 : weight) + scores.get(id));
            }
            if (songs.size() >= limit) break;
        }
        List<String> artists = new ArrayList<>(artistWeight.keySet());
        artists.sort((a, b) -> {
            int byWeight = Double.compare(artistWeight.get(b), artistWeight.get(a));
            return byWeight != 0 ? byWeight : a.compareToIgnoreCase(b);
        });
        if (artists.size() > MAX_ARTISTS) artists = new ArrayList<>(artists.subList(0, MAX_ARTISTS));
        return new Result(songs, artists, mode);
    }

    /** Calcula "Para ti" (bloqueante: llamar fuera del hilo principal). Usa la caché de 6 horas por semillas. */
    public synchronized Result compute(ClickStats stats, Predicate<String> knownId, Collection<String> knownTitles,
                                       List<String> recentQueries, long nowMillis) {
        return compute(stats, knownId, knownTitles, recentQueries, nowMillis, false);
    }

    /**
     * Con {@code fresh} no se usa la caché vigente (el usuario pidió actualizar o abrió la app); si no hay conexión
     * se sigue mostrando lo último guardado.
     */
    public synchronized Result compute(ClickStats stats, Predicate<String> knownId, Collection<String> knownTitles,
                                       List<String> recentQueries, long nowMillis, boolean fresh) {
        List<ClickStats.Seed> seeds = stats.seeds(SEED_COUNT, nowMillis);
        String mode, signature;
        if (!seeds.isEmpty()) {
            StringBuilder keys = new StringBuilder(CACHE_VERSION + "seeds:");
            for (ClickStats.Seed seed : seeds) keys.append(seed.key).append(',');
            mode = "seeds"; signature = keys.toString();
        } else if (!recentQueries.isEmpty()) {
            mode = "queries";
            signature = CACHE_VERSION + "queries:" + recentQueries.subList(0, Math.min(2, recentQueries.size()));
        } else {
            mode = "trending"; signature = CACHE_VERSION + "trending";
        }
        Cached cached = cache.get(signature);
        if (cached != null && !fresh && clock.now() - cached.at < CACHE_MS) return cached.result;

        List<Weighted> lists = new ArrayList<>();
        Set<String> excludeIds = new HashSet<>();
        List<String> excludeTitles = new ArrayList<>(knownTitles);
        if (mode.equals("seeds")) {
            double top = seeds.get(0).score > 0 ? seeds.get(0).score : 1.0;
            for (ClickStats.Seed seed : seeds) {
                excludeIds.add(seed.videoId());
                excludeTitles.add(seed.title);
                List<SearchResult> entries = safeRelated(seed.videoId());
                if (!entries.isEmpty()) lists.add(new Weighted(seed.score / top, entries));
            }
        } else {
            List<String> queries = mode.equals("queries")
                    ? recentQueries.subList(0, Math.min(2, recentQueries.size())) : java.util.Collections.singletonList(GENERIC_QUERY);
            for (String query : queries) {
                List<SearchResult> entries = safeSearch(query);
                if (!entries.isEmpty()) lists.add(new Weighted(1.0, entries));
            }
        }
        if (lists.isEmpty()) {
            if (cached != null) return cached.result;
            return new Result(new ArrayList<>(), new ArrayList<>(), "offline");
        }
        // Lo que ya está en la biblioteca no se recomienda.
        Set<String> known = new HashSet<>();
        for (Weighted list : lists) for (SearchResult entry : list.entries) if (knownId.test(entry.getId())) known.add(entry.getId());
        excludeIds.addAll(known);
        Result result = combine(lists, excludeIds, excludeTitles, MAX_SONGS, mode);
        cache.put(signature, new Cached(clock.now(), result));
        trim();
        save();
        return result;
    }

    private List<SearchResult> safeRelated(String videoId) {
        try { List<SearchResult> list = fetcher.related(videoId); return list == null ? new ArrayList<>() : list; }
        catch (Exception e) { return new ArrayList<>(); }
    }

    private List<SearchResult> safeSearch(String query) {
        try { List<SearchResult> list = fetcher.search(query); return list == null ? new ArrayList<>() : list; }
        catch (Exception e) { return new ArrayList<>(); }
    }

    private void trim() {
        while (cache.size() > CACHE_ENTRIES) {
            String oldest = null;
            for (Map.Entry<String, Cached> e : cache.entrySet())
                if (oldest == null || e.getValue().at < cache.get(oldest).at) oldest = e.getKey();
            cache.remove(oldest);
        }
    }

    /** El último resultado guardado, para mostrar algo al abrir mientras llega la red. */
    public synchronized Result lastKnown() {
        Cached latest = null;
        for (Cached cached : cache.values()) if (latest == null || cached.at > latest.at) latest = cached;
        return latest == null ? null : latest.result;
    }

    private void save() {
        if (prefs == null) return;
        try {
            JSONObject root = new JSONObject();
            for (Map.Entry<String, Cached> e : cache.entrySet()) {
                Result result = e.getValue().result;
                JSONArray songs = new JSONArray();
                for (SearchResult song : result.songs)
                    songs.put(new JSONObject().put("id", song.getId()).put("title", song.getTitle())
                            .put("channel", song.getChannel()).put("seconds", song.getDurationSeconds()));
                root.put(e.getKey(), new JSONObject().put("at", e.getValue().at).put("mode", result.mode)
                        .put("artists", new JSONArray(result.artists)).put("songs", songs));
            }
            prefs.edit().putString(PREF_CACHE, root.toString()).apply();
        } catch (Exception ignored) {}
    }

    private void load() {
        if (prefs == null) return;
        try {
            JSONObject root = new JSONObject(prefs.getString(PREF_CACHE, "{}"));
            java.util.Iterator<String> keys = root.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                JSONObject entry = root.getJSONObject(key);
                List<SearchResult> songs = new ArrayList<>();
                JSONArray array = entry.getJSONArray("songs");
                for (int i = 0; i < array.length(); i++) {
                    JSONObject song = array.getJSONObject(i);
                    songs.add(new SearchResult(song.getString("id"), song.getString("title"), song.optString("channel"),
                            song.optLong("seconds"), null, null));
                }
                List<String> artists = new ArrayList<>();
                JSONArray names = entry.optJSONArray("artists");
                if (names != null) for (int i = 0; i < names.length(); i++) artists.add(names.getString(i));
                cache.put(key, new Cached(entry.getLong("at"), new Result(songs, artists, entry.optString("mode", "seeds"))));
            }
        } catch (Exception ignored) {
            cache.clear();
        }
    }
}
