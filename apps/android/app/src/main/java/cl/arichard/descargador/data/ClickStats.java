package cl.arichard.descargador.data;

import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;

import org.json.JSONObject;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

import cl.arichard.descargador.model.Song;

/**
 * Clics (reproducciones) compartidos entre Windows y Android.
 * Cada dispositivo escribe solo su propio contador por canción y el total es la suma de todos;
 * al mezclar se conserva el máximo por dispositivo y canción, por lo que sincronizar varias veces
 * o en cualquier orden nunca duplica ni pierde clics. Equivale a click_stats.py en escritorio.
 */
public class ClickStats {
    private static final String PREFS = "clicks";
    private static final String KEY_DATA = "data";
    private static final Pattern BRACKETS = Pattern.compile("[(\\[][^)\\]]*[)\\]]");
    private static final Pattern SEPARATORS = Pattern.compile("[^\\p{L}\\p{N}]+");
    private static final Pattern MARKS = Pattern.compile("\\p{Mn}");
    private static ClickStats instance;

    private final Context appContext;
    private final SharedPreferences prefs;
    private final String deviceId;
    private final Map<String, Map<String, Integer>> devices = new HashMap<>();
    private final Map<String, String> titles = new HashMap<>();
    private Runnable changeListener;

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

    public synchronized void record(Song song) {
        String key = primaryKey(song);
        if (key.isEmpty()) return;
        Map<String, Integer> own = bucket(deviceId);
        Integer current = own.get(key);
        own.put(key, (current == null ? 0 : current) + 1);
        if (song.getTitle() != null) titles.put(key, song.getTitle());
        persistAndNotify();
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
        if (changed) prefs.edit().putString(KEY_DATA, exportJson().toString()).apply();
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
            root.put("v", 1); root.put("devices", all); root.put("titles", names);
        } catch (Exception ignored) {}
        return root;
    }

    public synchronized void resetForAccount() {
        devices.clear(); titles.clear();
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
    }
}
