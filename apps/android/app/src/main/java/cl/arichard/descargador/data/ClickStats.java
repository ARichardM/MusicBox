package cl.arichard.descargador.data;

import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;

import org.json.JSONObject;

import java.text.Normalizer;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.TreeMap;
import java.util.regex.Pattern;

import cl.arichard.descargador.model.Song;

/**
 * Clics (reproducciones) compartidos entre Windows y Android.
 * Cada dispositivo escribe solo su propio contador por canción y el total es la suma de todos;
 * al mezclar se conserva el máximo por dispositivo y canción, por lo que sincronizar varias veces
 * o en cualquier orden nunca duplica ni pierde clics. Equivale a click_stats.py en escritorio.
 * También guarda las reproducciones por día (UTC, últimos DAILY_DAYS días) con la misma regla de mezcla,
 * para que las recomendaciones den más peso a lo reciente.
 */
public class ClickStats {
    private static final String PREFS = "clicks";
    private static final String KEY_DATA = "data";
    public static final int DAILY_DAYS = 90;
    private static final double HALF_LIFE_DAYS = 14.0;
    private static final double ALL_TIME_WEIGHT = 0.1;
    private static final long DAY_MS = 86400000L;
    private static final Set<String> KNOWN = new HashSet<>(java.util.Arrays.asList("v", "devices", "titles", "daily", "listening"));
    private final JSONObject listening = new JSONObject();
    private long lastListeningSave;
    private static final Pattern BRACKETS = Pattern.compile("[(\\[][^)\\]]*[)\\]]");
    private static final Pattern SEPARATORS = Pattern.compile("[^\\p{L}\\p{N}]+");
    private static final Pattern MARKS = Pattern.compile("\\p{Mn}");
    private static ClickStats instance;

    private final Context appContext;
    private final SharedPreferences prefs;
    private final String deviceId;
    private final Map<String, Map<String, Integer>> devices = new HashMap<>();
    private final Map<String, String> titles = new HashMap<>();
    /** dispositivo -> clave -> día (AAAA-MM-DD, UTC) -> reproducciones. */
    private final Map<String, Map<String, Map<String, Integer>>> daily = new HashMap<>();
    /** Campos de otras versiones que se conservan al volver a subir. */
    private final Map<String, Object> extra = new HashMap<>();
    private Runnable changeListener;
    /** Quienes quieren enterarse de cualquier cambio (propio o llegado de la nube), además del listener de sincronización. */
    private final java.util.List<Runnable> observers = new java.util.concurrent.CopyOnWriteArrayList<>();

    /** Una canción de YouTube que le gusta al usuario ahora, con su puntaje. */
    public static final class Seed {
        public final String key;
        public final String title;
        public final double score;
        Seed(String key, String title, double score) { this.key = key; this.title = title; this.score = score; }
        public String videoId() { return key.substring(3); }
    }

    public static synchronized ClickStats getInstance(Context context) {
        if (instance == null) instance = new ClickStats(context.getApplicationContext());
        return instance;
    }

    private ClickStats(Context context) {
        appContext = context;
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String id = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
        deviceId = id == null ? "android" : id;
        try { mergeLocked(new JSONObject(prefs.getString(KEY_DATA, "{}"))); } catch (Exception ignored) {}
    }

    /** Same key on every platform: yt:<id> for YouTube, t:<normalized title> otherwise. */
    public static String keyOf(Song song) {
        if (song == null) return "";
        String path = song.getPath();
        if (path != null && path.startsWith("youtube:")) return "yt:" + path.substring(8);
        return "t:" + normalize(song.getTitle());
    }

    private static final Map<String, String> NORMALIZED = new java.util.LinkedHashMap<String, String>(512, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, String> eldest) { return size() > 2048; }
    };

    /** Normalizing runs for every row drawn, so recent results are cached. */
    public static String normalize(String title) {
        String key = title == null ? "" : title;
        synchronized (NORMALIZED) {
            String cached = NORMALIZED.get(key);
            if (cached != null) return cached;
        }
        String value = normalizeUncached(key);
        synchronized (NORMALIZED) { NORMALIZED.put(key, value); }
        return value;
    }

    /** Like normalize() but keeping what is inside brackets: downloaded file names usually include it. */
    public static String normalizeLoose(String title) {
        String text = MARKS.matcher(Normalizer.normalize(title == null ? "" : title, Normalizer.Form.NFKD))
                .replaceAll("").toLowerCase(Locale.ROOT);
        return SEPARATORS.matcher(text).replaceAll(" ").trim();
    }

    private static String normalizeUncached(String title) {
        String text = MARKS.matcher(Normalizer.normalize(title == null ? "" : title, Normalizer.Form.NFKD))
                .replaceAll("").toLowerCase(Locale.ROOT);
        String cleaned = SEPARATORS.matcher(BRACKETS.matcher(text).replaceAll(" ")).replaceAll(" ").trim();
        return cleaned.isEmpty() ? SEPARATORS.matcher(text).replaceAll(" ").trim() : cleaned;
    }

    public synchronized void setChangeListener(Runnable listener) { changeListener = listener; }

    public void addObserver(Runnable observer) { observers.add(observer); }

    public void removeObserver(Runnable observer) { observers.remove(observer); }

    /** Key used for new plays: the YouTube id when the song is linked to one, else its title. */
    private String primaryKey(Song song) {
        String id = song == null || song.getPath() == null ? null : PlaylistManager.getInstance(appContext).videoIdFor(song.getPath());
        return id == null ? keyOf(song) : "yt:" + id;
    }

    /** Every key a song's plays may be stored under (older plays were stored by title). */
    private Set<String> keysOf(Song song) {
        Set<String> keys = new HashSet<>();
        keys.add(primaryKey(song));
        keys.add(keyOf(song));
        return keys;
    }

    public synchronized void record(Song song) { record(song, System.currentTimeMillis()); }

    public synchronized void record(Song song, long nowMillis) {
        String key = primaryKey(song);
        if (key.isEmpty()) return;
        Map<String, Integer> own = bucket(deviceId);
        Integer current = own.get(key);
        own.put(key, (current == null ? 0 : current) + 1);
        Map<String, Integer> days = dayBucket(deviceId, key);
        String today = dayOf(nowMillis);
        Integer plays = days.get(today);
        days.put(today, (plays == null ? 0 : plays) + 1);
        prune(nowMillis);
        if (song.getTitle() != null) titles.put(key, song.getTitle());
        persistAndNotify();
    }

    private static String dayOf(long millis) {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new java.util.Date(millis));
    }

    /** Milisegundos (UTC) del inicio del día AAAA-MM-DD, o -1 si no es una fecha válida. */
    private static long dayStart(String day) {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        format.setLenient(false);
        try { return format.parse(day).getTime(); } catch (ParseException | NullPointerException e) { return -1; }
    }

    private Map<String, Integer> dayBucket(String device, String key) {
        Map<String, Map<String, Integer>> keys = daily.get(device);
        if (keys == null) { keys = new HashMap<>(); daily.put(device, keys); }
        Map<String, Integer> days = keys.get(key);
        if (days == null) { days = new HashMap<>(); keys.put(key, days); }
        return days;
    }

    /** Descarta los días fuera de la ventana; el corte es determinista para no reintroducirlos al mezclar. */
    private void prune(long nowMillis) {
        String cutoff = dayOf(nowMillis - DAILY_DAYS * DAY_MS);
        for (Iterator<Map.Entry<String, Map<String, Map<String, Integer>>>> d = daily.entrySet().iterator(); d.hasNext(); ) {
            Map<String, Map<String, Integer>> keys = d.next().getValue();
            for (Iterator<Map.Entry<String, Map<String, Integer>>> k = keys.entrySet().iterator(); k.hasNext(); ) {
                Map<String, Integer> days = k.next().getValue();
                days.keySet().removeIf(day -> day.compareTo(cutoff) < 0);
                if (days.isEmpty()) k.remove();
            }
            if (keys.isEmpty()) d.remove();
        }
    }

    /** Puntaje de gusto: lo reciente pesa más (vida media de 14 días) más un poco del total histórico. */
    public synchronized double score(String key, long nowMillis) {
        long today = dayStart(dayOf(nowMillis));
        double weighted = 0;
        for (Map<String, Map<String, Integer>> keys : daily.values()) {
            Map<String, Integer> days = keys.get(key);
            if (days == null) continue;
            for (Map.Entry<String, Integer> e : days.entrySet()) {
                long start = dayStart(e.getKey());
                if (start < 0) continue;
                long age = Math.max(0, (today - start) / DAY_MS);
                weighted += e.getValue() * Math.pow(0.5, age / HALF_LIFE_DAYS);
            }
        }
        return weighted + ALL_TIME_WEIGHT * Math.log1p(count(key));
    }

    /** Las canciones de YouTube que más le gustan ahora, de mayor a menor puntaje. */
    public synchronized List<Seed> seeds(int limit, long nowMillis) {
        Set<String> keys = new HashSet<>();
        for (Map<String, Integer> bucket : devices.values())
            for (Map.Entry<String, Integer> e : bucket.entrySet())
                if (e.getKey().startsWith("yt:") && e.getValue() > 0) keys.add(e.getKey());
        List<Seed> ranked = new ArrayList<>();
        for (String key : keys) {
            String title = titles.get(key);
            ranked.add(new Seed(key, title == null ? key : title, score(key, nowMillis)));
        }
        ranked.sort((a, b) -> a.score != b.score ? Double.compare(b.score, a.score) : a.key.compareTo(b.key));
        return new ArrayList<>(ranked.subList(0, Math.min(limit, ranked.size())));
    }

    public synchronized int count(Song song) {
        int total = 0;
        for (String key : keysOf(song)) total += count(key);
        return total;
    }

    public synchronized int count(String key) {
        int total = 0;
        for (Map<String, Integer> bucket : devices.values()) {
            Integer value = bucket.get(key);
            if (value != null) total += value;
        }
        return total;
    }

    /** Clicks of a group of songs (folder, playlist); repeated titles are counted once. */
    public synchronized int count(List<Song> songs) {
        Set<String> keys = new HashSet<>();
        if (songs != null) for (Song song : songs) keys.addAll(keysOf(song));
        int total = 0;
        for (String key : keys) total += count(key);
        return total;
    }

    public synchronized int total() {
        int total = 0;
        for (Map<String, Integer> bucket : devices.values()) for (int value : bucket.values()) total += value;
        return total;
    }

    /** Real listening time, per device: merging takes maxima, never sums the same device twice. */
    public synchronized void recordListening(Song song, String playlist, long millis, boolean start) {
        if (song == null || millis < 0) return;
        addListening(primaryKey(song), song.getTitle(), song.getArtist(), song.getCoverPath(), millis, start);
        if (playlist != null && !playlist.isEmpty())
            addListening("playlist:" + playlist, playlist, "", song.getCoverPath(), millis, start);
        if (start || android.os.SystemClock.elapsedRealtime() - lastListeningSave >= 5000) flushListening();
    }

    private void addListening(String key, String title, String artist, String cover, long millis, boolean start) {
        try {
            JSONObject device = listening.optJSONObject(deviceId);
            if (device == null) { device = new JSONObject(); listening.put(deviceId, device); }
            JSONObject row = device.optJSONObject(key);
            if (row == null) { row = new JSONObject(); device.put(key, row); }
            row.put("ms", row.optLong("ms") + millis);
            row.put("plays", row.optLong("plays") + (start ? 1 : 0));
            row.put("title", title == null ? "" : title);
            row.put("artist", artist == null ? "" : artist);
            row.put("thumbnail", cover == null ? "" : cover);
        } catch (Exception ignored) {}
    }

    public synchronized void flushListening() {
        lastListeningSave = android.os.SystemClock.elapsedRealtime();
        persistAndNotify();
    }

    public synchronized List<JSONObject> listeningRows(boolean playlists) {
        Map<String, JSONObject> result = new HashMap<>();
        Iterator<String> ids = listening.keys();
        while (ids.hasNext()) {
            JSONObject device = listening.optJSONObject(ids.next());
            Iterator<String> keys = device.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                if (key.startsWith("playlist:") != playlists) continue;
                JSONObject source = device.optJSONObject(key);
                JSONObject row = result.get(key);
                try {
                    if (row == null) { row = new JSONObject(source.toString()); row.put("key", key); row.put("ms", 0); row.put("plays", 0); result.put(key, row); }
                    row.put("ms", row.optLong("ms") + source.optLong("ms"));
                    row.put("plays", row.optLong("plays") + source.optLong("plays"));
                } catch (Exception ignored) {}
            }
        }
        if (!playlists) {
            Set<String> keys = new HashSet<>();
            for (Map<String, Integer> bucket : devices.values()) keys.addAll(bucket.keySet());
            for (String key : keys) try {
                JSONObject row = result.get(key);
                if (row == null) { row = new JSONObject().put("key", key).put("title", titles.getOrDefault(key, key)).put("ms", 0); result.put(key, row); }
                row.put("plays", count(key));
            } catch (Exception ignored) {}
        }
        List<JSONObject> rows = new ArrayList<>(result.values());
        rows.removeIf(row -> row.optLong("plays") <= 0 && row.optLong("ms") <= 0);
        rows.sort((a,b) -> { int c = Long.compare(b.optLong("plays"), a.optLong("plays")); return c != 0 ? c : Long.compare(b.optLong("ms"), a.optLong("ms")); });
        return rows;
    }

    private boolean mergeListening(JSONObject remote) {
        if (remote == null) return false;
        boolean changed = false;
        Iterator<String> ids = remote.keys();
        while (ids.hasNext()) { String id = ids.next(); JSONObject incoming = remote.optJSONObject(id); if (incoming == null) continue;
            try {
                JSONObject mine = listening.optJSONObject(id);
                if (mine == null) { mine = new JSONObject(); listening.put(id, mine); }
                Iterator<String> keys = incoming.keys();
                while (keys.hasNext()) { String key = keys.next(); JSONObject source = incoming.optJSONObject(key); if (source == null) continue;
                    JSONObject row = mine.optJSONObject(key);
                    if (row == null) { row = new JSONObject(source.toString()); mine.put(key, row); changed = true; }
                    else for (String field : new String[]{"ms", "plays"}) if (source.optLong(field) > row.optLong(field)) { row.put(field, source.optLong(field)); changed = true; }
                }
            } catch (Exception ignored) {}
        }
        return changed;
    }

    public synchronized int playedSongs() {
        Set<String> keys = new HashSet<>();
        for (Map<String, Integer> bucket : devices.values())
            for (Map.Entry<String, Integer> e : bucket.entrySet()) if (e.getValue() > 0) keys.add(e.getKey());
        return keys.size();
    }

    /** Most played songs of the whole account as {title, clicks} pairs. */
    public synchronized List<Object[]> top(int limit) {
        Map<String, Integer> totals = new HashMap<>();
        for (Map<String, Integer> bucket : devices.values())
            for (Map.Entry<String, Integer> e : bucket.entrySet()) totals.merge(e.getKey(), e.getValue(), Integer::sum);
        List<Map.Entry<String, Integer>> ranked = new ArrayList<>(totals.entrySet());
        ranked.sort((a, b) -> !a.getValue().equals(b.getValue()) ? b.getValue() - a.getValue() : a.getKey().compareTo(b.getKey()));
        List<Object[]> result = new ArrayList<>();
        for (Map.Entry<String, Integer> e : ranked) {
            if (result.size() >= limit || e.getValue() <= 0) break;
            String title = titles.get(e.getKey());
            result.add(new Object[]{title == null ? e.getKey() : title, e.getValue()});
        }
        return result;
    }

    /** Moves the old per-path counters to the shared key; safe to run repeatedly. */
    public synchronized void migrateLegacy(SearchHistoryManager history, List<Song> library) {
        boolean changed = false;
        Map<String, Integer> own = bucket(deviceId);
        for (Song song : library) {
            if (song.getPath() == null) continue;
            int legacy = history.getPlayCount(song.getPath());
            String key = keyOf(song);
            Integer current = own.get(key);
            if (legacy > 0 && (current == null || current < legacy)) {
                own.put(key, legacy);
                if (song.getTitle() != null && !titles.containsKey(key)) titles.put(key, song.getTitle());
                changed = true;
            }
        }
        if (changed) persistAndNotify();
    }

    /** Merges data downloaded from the account. Returns true when something new was learned. */
    public synchronized boolean merge(JSONObject remote) {
        boolean changed = mergeLocked(remote);
        if (changed) {
            prefs.edit().putString(KEY_DATA, exportJson().toString()).apply();
            for (Runnable observer : observers) observer.run();
        }
        return changed;
    }

    public synchronized JSONObject exportJson() {
        JSONObject root = new JSONObject();
        try {
            JSONObject all = new JSONObject();
            for (Map.Entry<String, Map<String, Integer>> device : new TreeMap<>(devices).entrySet()) {
                JSONObject bucket = new JSONObject();
                for (Map.Entry<String, Integer> e : device.getValue().entrySet()) bucket.put(e.getKey(), e.getValue());
                all.put(device.getKey(), bucket);
            }
            JSONObject names = new JSONObject();
            for (Map.Entry<String, String> e : titles.entrySet()) names.put(e.getKey(), e.getValue());
            prune(System.currentTimeMillis());
            JSONObject perDay = new JSONObject();
            for (Map.Entry<String, Map<String, Map<String, Integer>>> device : new TreeMap<>(daily).entrySet()) {
                JSONObject keys = new JSONObject();
                for (Map.Entry<String, Map<String, Integer>> key : device.getValue().entrySet())
                    keys.put(key.getKey(), new JSONObject(new TreeMap<>(key.getValue())));
                perDay.put(device.getKey(), keys);
            }
            for (Map.Entry<String, Object> e : extra.entrySet()) root.put(e.getKey(), e.getValue());
            root.put("v", 1); root.put("devices", all); root.put("titles", names); root.put("daily", perDay);
            root.put("listening", new JSONObject(listening.toString()));
        } catch (Exception ignored) {}
        return root;
    }

    public synchronized void resetForAccount() {
        devices.clear(); titles.clear(); daily.clear(); extra.clear();
        Iterator<String> listeningKeys = listening.keys();
        while (listeningKeys.hasNext()) { listeningKeys.next(); listeningKeys.remove(); }
        prefs.edit().remove(KEY_DATA).apply();
    }

    private boolean mergeLocked(JSONObject remote) {
        if (remote == null) return false;
        boolean changed = false;
        JSONObject remoteDevices = remote.optJSONObject("devices");
        if (remoteDevices != null) {
            Iterator<String> ids = remoteDevices.keys();
            while (ids.hasNext()) {
                String id = ids.next();
                JSONObject remoteBucket = remoteDevices.optJSONObject(id);
                if (remoteBucket == null) continue;
                Map<String, Integer> bucket = bucket(id);
                Iterator<String> keys = remoteBucket.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    int value = remoteBucket.optInt(key, 0);
                    Integer current = bucket.get(key);
                    if (value > (current == null ? 0 : current)) { bucket.put(key, value); changed = true; }
                }
            }
        }
        JSONObject remoteTitles = remote.optJSONObject("titles");
        if (remoteTitles != null) {
            Iterator<String> keys = remoteTitles.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                String title = remoteTitles.optString(key, "");
                if (!titles.containsKey(key) && !title.isEmpty()) { titles.put(key, title); changed = true; }
            }
        }
        if (mergeDaily(remote.optJSONObject("daily"))) changed = true;
        if (mergeListening(remote.optJSONObject("listening"))) changed = true;
        Iterator<String> names = remote.keys();
        while (names.hasNext()) {
            String name = names.next();
            if (!KNOWN.contains(name) && !extra.containsKey(name)) extra.put(name, remote.opt(name));
        }
        return changed;
    }

    private boolean mergeDaily(JSONObject remote) {
        if (remote == null) return false;
        String cutoff = dayOf(System.currentTimeMillis() - DAILY_DAYS * DAY_MS);
        boolean changed = false;
        Iterator<String> devicesIt = remote.keys();
        while (devicesIt.hasNext()) {
            String device = devicesIt.next();
            JSONObject keys = remote.optJSONObject(device);
            if (keys == null) continue;
            Iterator<String> keysIt = keys.keys();
            while (keysIt.hasNext()) {
                String key = keysIt.next();
                JSONObject days = keys.optJSONObject(key);
                if (days == null) continue;
                Iterator<String> daysIt = days.keys();
                while (daysIt.hasNext()) {
                    String day = daysIt.next();
                    int value = days.optInt(day, 0);
                    if (value <= 0 || dayStart(day) < 0 || day.compareTo(cutoff) < 0) continue;
                    Map<String, Integer> bucket = dayBucket(device, key);
                    Integer current = bucket.get(day);
                    if (value > (current == null ? 0 : current)) { bucket.put(day, value); changed = true; }
                }
            }
        }
        return changed;
    }

    private Map<String, Integer> bucket(String id) {
        Map<String, Integer> bucket = devices.get(id);
        if (bucket == null) { bucket = new HashMap<>(); devices.put(id, bucket); }
        return bucket;
    }

    private void persistAndNotify() {
        prefs.edit().putString(KEY_DATA, exportJson().toString()).apply();
        if (changeListener != null) changeListener.run();
        for (Runnable observer : observers) observer.run();
    }
}
