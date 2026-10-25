package cl.arichard.descargador.data;

import android.content.Context;
import android.content.SharedPreferences;
import cl.arichard.descargador.model.Song;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SearchHistoryManager {
    private static final String PREF_NAME = "musicbox_discovery";
    private static final String KEY_RECENT_QUERIES = "recent_queries";
    private static final String KEY_RECENT_SONGS = "recent_songs";
    private static final String KEY_PLAY_COUNTS = "play_counts";

    private static SearchHistoryManager instance;
    private final SharedPreferences prefs;
    private Runnable changeListener;

    private final Context appContext;

    private SearchHistoryManager(Context context) {
        this.appContext = context.getApplicationContext();
        this.prefs = context.getApplicationContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    public static synchronized SearchHistoryManager getInstance(Context context) {
        if (instance == null) {
            instance = new SearchHistoryManager(context);
        }
        return instance;
    }

    // 1. Recent Search Queries
    public void addSearchQuery(String query) {
        if (query == null || query.trim().isEmpty()) return;
        query = query.trim();
        List<String> list = getRecentQueries();
        list.remove(query);
        list.add(0, query);
        if (list.size() > 8) list = list.subList(0, 8);

        JSONArray arr = new JSONArray();
        for (String q : list) arr.put(q);
        prefs.edit().putString(KEY_RECENT_QUERIES, arr.toString()).apply();
        changed();
    }

    public List<String> getRecentQueries() {
        List<String> list = new ArrayList<>();
        String json = prefs.getString(KEY_RECENT_QUERIES, "[]");
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                list.add(arr.getString(i));
            }
        } catch (Exception ignored) {}
        return list;
    }

    public void clearRecentQueries() {
        prefs.edit().remove(KEY_RECENT_QUERIES).apply();
        changed();
    }

    // 2. Recently Played Songs
    public void recordPlayedSong(Song song) {
        if (song == null) return;
        List<Song> recents = getRecentPlayedSongs();
        for (int i = 0; i < recents.size(); i++) {
            if (recents.get(i).getPath().equals(song.getPath())) {
                recents.remove(i);
                break;
            }
        }
        recents.add(0, song);
        if (recents.size() > 10) recents = recents.subList(0, 10);

        JSONArray arr = new JSONArray();
        for (Song s : recents) arr.put(s.toJson());
        prefs.edit().putString(KEY_RECENT_SONGS, arr.toString()).apply();

        ClickStats.getInstance(appContext).record(song);
        changed();
    }

    public List<Song> getRecentPlayedSongs() {
        List<Song> list = new ArrayList<>();
        String json = prefs.getString(KEY_RECENT_SONGS, "[]");
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                list.add(Song.fromJson(arr.getJSONObject(i)));
            }
        } catch (Exception ignored) {}
        return list;
    }

    private void incrementPlayCount(String key) {
        if (key == null) return;
        int count = prefs.getInt(KEY_PLAY_COUNTS + "_" + key.hashCode(), 0);
        prefs.edit().putInt(KEY_PLAY_COUNTS + "_" + key.hashCode(), count + 1).apply();
    }

    public int getPlayCount(String key) {
        if (key == null) return 0;
        int count = prefs.getInt(KEY_PLAY_COUNTS + "_" + key.hashCode(), 0);
        if (key.startsWith("youtube:")) {
            String id = key.substring(8);
            for (String size : new String[]{"hqdefault", "mqdefault"})
                count += prefs.getInt(KEY_PLAY_COUNTS + "_" + ("https://i.ytimg.com/vi/" + id + "/" + size + ".jpg").hashCode(), 0);
        }
        return count;
    }

    public void removeSong(String path) {
        JSONArray songs = new JSONArray();
        for (Song song : getRecentPlayedSongs()) if (!path.equals(song.getPath())) songs.put(song.toJson());
        prefs.edit().putString(KEY_RECENT_SONGS, songs.toString()).apply();
        changed();
    }

    public void setChangeListener(Runnable listener) { changeListener = listener; }
    private void changed() { if (changeListener != null) changeListener.run(); }

    public JSONObject exportJson() {
        JSONObject root = new JSONObject();
        try {
            for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) root.put(entry.getKey(), entry.getValue());
        } catch (Exception ignored) {}
        return root;
    }

    public void importJson(JSONObject root) {
        if (root == null) return;
        SharedPreferences.Editor editor = prefs.edit();
        java.util.Iterator<String> keys = root.keys();
        while (keys.hasNext()) {
            String key = keys.next(); Object value = root.opt(key);
            if (value instanceof Number) editor.putInt(key, ((Number) value).intValue());
            else if (value instanceof Boolean) editor.putBoolean(key, (Boolean) value);
            else editor.putString(key, String.valueOf(value));
        }
        editor.apply();
    }

    public void resetForAccount() { prefs.edit().clear().apply(); }

    // 3. Top Most Played Songs from library
    public List<Song> getTopPlayed(List<Song> allSongs, int limit) {
        if (allSongs == null || allSongs.isEmpty()) return new ArrayList<>();
        List<Song> copy = new ArrayList<>(allSongs);

        // Sort by play count descending; if tie, sort by dateAdded descending
        Collections.sort(copy, (a, b) -> {
            ClickStats stats = ClickStats.getInstance(appContext);
            int countA = stats.count(a);
            int countB = stats.count(b);
            if (countA != countB) {
                return Integer.compare(countB, countA);
            }
            return Long.compare(b.getDateAdded(), a.getDateAdded());
        });

        if (copy.size() > limit) {
            return new ArrayList<>(copy.subList(0, limit));
        }
        return copy;
    }
}
