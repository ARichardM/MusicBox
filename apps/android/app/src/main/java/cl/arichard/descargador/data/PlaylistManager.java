package cl.arichard.descargador.data;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import cl.arichard.descargador.model.SearchResult;

public class PlaylistManager {
    private static PlaylistManager instance;
    private final File storageFile;
    private final android.content.SharedPreferences localIds;
    /** Downloaded file on THIS device -> its YouTube video id (paths are device specific, so this is never synced). */
    private final Map<String, String> pathToId = new HashMap<>();
    private final Map<String, List<String>> playlists = new HashMap<>();
    private final Map<String, SearchResult> onlineTracks = new HashMap<>();
    private final Map<String, String> descriptions = new HashMap<>();
    private final Set<String> autoDownloadPlaylists = new HashSet<>();
    private Runnable changeListener;
    private final List<Runnable> observers = new ArrayList<>();
    private static final android.os.Handler MAIN = new android.os.Handler(android.os.Looper.getMainLooper());
    public interface AutoDownloadListener { void onTrackAdded(String playlistName, SearchResult track); }
    private AutoDownloadListener autoDownloadListener;

    private PlaylistManager(Context context) {
        this.storageFile = new File(context.getFilesDir(), "playlists.json");
        this.localIds = context.getSharedPreferences("local_video_ids", Context.MODE_PRIVATE);
        for (Map.Entry<String, ?> entry : localIds.getAll().entrySet())
            if (entry.getValue() instanceof String) pathToId.put(entry.getKey(), (String) entry.getValue());
        load();
        if (!playlists.containsKey("Favoritos")) {
            playlists.put("Favoritos", new ArrayList<>());
            save();
        }
    }

    public static synchronized PlaylistManager getInstance(Context context) {
        if (instance == null) {
            instance = new PlaylistManager(context.getApplicationContext());
        }
        return instance;
    }

    private void load() {
        playlists.clear();
        onlineTracks.clear();
        descriptions.clear();
        autoDownloadPlaylists.clear();
        if (!storageFile.exists()) return;
        try (FileInputStream fis = new FileInputStream(storageFile)) {
            byte[] data = new byte[(int) storageFile.length()];
            fis.read(data);
            String jsonStr = new String(data, StandardCharsets.UTF_8);
            JSONObject root = new JSONObject(jsonStr);
            JSONObject listsRoot = root.optJSONObject("playlists");
            if (listsRoot == null) listsRoot = root; // Compatibilidad con v2.0
            JSONArray names = listsRoot.names();
            if (names != null) {
                for (int i = 0; i < names.length(); i++) {
                    String name = names.getString(i);
                    if ("onlineTracks".equals(name) || "autoDownload".equals(name)) continue;
                    JSONArray arr = listsRoot.optJSONArray(name);
                    if (arr == null) continue;
                    List<String> paths = new ArrayList<>();
                    for (int j = 0; j < arr.length(); j++) {
                        paths.add(arr.getString(j));
                    }
                    playlists.put(name, paths);
                }
            }
            JSONObject tracks = root.optJSONObject("onlineTracks");
            if (tracks != null) {
                JSONArray ids = tracks.names();
                if (ids != null) for (int i = 0; i < ids.length(); i++) {
                    String id = ids.getString(i);
                    JSONObject item = tracks.getJSONObject(id);
                    onlineTracks.put(id, new SearchResult(id, item.optString("title"),
                            item.optString("channel"), item.optLong("duration"),
                            item.optString("thumbnail"), item.optString("url")));
                }
            }
            JSONArray auto = root.optJSONArray("autoDownload");
            if (auto != null) for (int i = 0; i < auto.length(); i++) autoDownloadPlaylists.add(auto.getString(i));
            JSONObject descriptionsRoot = root.optJSONObject("descriptions");
            if (descriptionsRoot != null) {
                JSONArray descriptionNames = descriptionsRoot.names();
                if (descriptionNames != null) for (int i = 0; i < descriptionNames.length(); i++) {
                    String name = descriptionNames.getString(i);
                    descriptions.put(name, descriptionsRoot.optString(name, ""));
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void save() {
        try (FileOutputStream fos = new FileOutputStream(storageFile)) {
            JSONObject root = exportJson();
            fos.write(root.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            e.printStackTrace();
        }
        if (changeListener != null) changeListener.run();
        for (Runnable observer : new ArrayList<>(observers)) MAIN.post(observer);
    }

    public synchronized void setChangeListener(Runnable listener) { changeListener = listener; }
    /** Extra listeners (for example the media notification) that learn about every change on the main thread. */
    public synchronized void addObserver(Runnable observer) { if (!observers.contains(observer)) observers.add(observer); }
    public synchronized void setAutoDownloadListener(AutoDownloadListener listener) { autoDownloadListener = listener; }

    public synchronized JSONObject exportJson() {
        JSONObject root = new JSONObject();
        try {
            JSONObject lists = new JSONObject();
            for (Map.Entry<String, List<String>> entry : playlists.entrySet())
                lists.put(entry.getKey(), new JSONArray(entry.getValue()));
            root.put("playlists", lists);
            JSONObject tracks = new JSONObject();
            for (Map.Entry<String, SearchResult> entry : onlineTracks.entrySet()) {
                SearchResult r = entry.getValue(); JSONObject item = new JSONObject();
                item.put("title", r.getTitle()); item.put("channel", r.getChannel());
                item.put("duration", r.getDurationSeconds()); item.put("thumbnail", r.getThumbnailUrl());
                item.put("url", r.getUrl()); tracks.put(entry.getKey(), item);
            }
            root.put("onlineTracks", tracks);
            root.put("autoDownload", new JSONArray(autoDownloadPlaylists));
            root.put("descriptions", new JSONObject(descriptions));
        } catch (Exception ignored) {}
        return root;
    }

    public synchronized void importJson(JSONObject root) {
        if (root == null) return;
        try (FileOutputStream fos = new FileOutputStream(storageFile)) {
            fos.write(root.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) { return; }
        load();
        if (!playlists.containsKey("Favoritos")) playlists.put("Favoritos", new ArrayList<>());
    }

    public synchronized void resetForAccount() {
        playlists.clear(); onlineTracks.clear(); autoDownloadPlaylists.clear();
        playlists.put("Favoritos", new ArrayList<>()); save();
    }

    public synchronized List<String> getPlaylistNames() {
        List<String> list = new ArrayList<>();
        list.add("Favoritos");
        for (String name : playlists.keySet()) {
            if (!"Favoritos".equalsIgnoreCase(name)) {
                list.add(name);
            }
        }
        return list;
    }

    public synchronized List<String> getSongPaths(String playlistName) {
        List<String> list = playlists.get(playlistName);
        return list != null ? new ArrayList<>(list) : new ArrayList<>();
    }

    public synchronized boolean createPlaylist(String name) {
        if (name == null || name.trim().isEmpty() || playlists.containsKey(name.trim())) {
            return false;
        }
        playlists.put(name.trim(), new ArrayList<>());
        save();
        return true;
    }

    public synchronized boolean deletePlaylist(String name) {
        if ("Favoritos".equalsIgnoreCase(name)) return false;
        if (playlists.remove(name) != null) {
            autoDownloadPlaylists.remove(name);
            descriptions.remove(name);
            save();
            return true;
        }
        return false;
    }

    public synchronized String getDescription(String name) { return descriptions.getOrDefault(name, ""); }

    public synchronized boolean updatePlaylistDetails(String oldName, String newName, String description) {
        if (oldName == null || !playlists.containsKey(oldName)) return false;
        String cleanName = newName == null ? "" : newName.trim();
        if ("Favoritos".equals(oldName)) cleanName = oldName;
        if (cleanName.isEmpty() || (!oldName.equals(cleanName) && playlists.containsKey(cleanName))) return false;
        if (!oldName.equals(cleanName)) {
            List<String> songs = playlists.remove(oldName);
            playlists.put(cleanName, songs);
            if (autoDownloadPlaylists.remove(oldName)) autoDownloadPlaylists.add(cleanName);
            String previousDescription = descriptions.remove(oldName);
            if (previousDescription != null && !previousDescription.isEmpty()) descriptions.put(cleanName, previousDescription);
        }
        String cleanDescription = description == null ? "" : description.trim();
        if (cleanDescription.isEmpty()) descriptions.remove(cleanName); else descriptions.put(cleanName, cleanDescription);
        save();
        return true;
    }

    public synchronized boolean addOnlineTrack(String playlistName, SearchResult result) {
        if (result == null) return false;
        onlineTracks.put(result.getId(), result);
        boolean added = addSongToPlaylist(playlistName, "youtube:" + result.getId());
        save();
        if (added && autoDownloadPlaylists.contains(playlistName) && autoDownloadListener != null)
            autoDownloadListener.onTrackAdded(playlistName, result);
        return added;
    }

    public synchronized String videoIdFor(String path) { return path == null ? null : pathToId.get(path); }

    public synchronized boolean hasLocalCopy(String videoId) { return pathToId.containsValue(videoId); }

    /** The key a song is stored under in playlists: its YouTube id whenever known, so every device agrees. */
    public synchronized String canonicalKey(cl.arichard.descargador.model.Song song) {
        if (song == null) return "";
        if (song.isOnline()) return song.getPath();
        String id = pathToId.get(song.getPath());
        return id != null && onlineTracks.containsKey(id) ? "youtube:" + id : song.getPath();
    }

    public synchronized boolean isInPlaylist(String playlistName, cl.arichard.descargador.model.Song song) {
        List<String> list = playlists.get(playlistName);
        return list != null && song != null && (list.contains(canonicalKey(song)) || list.contains(song.getPath()));
    }

    /**
     * Links downloaded files to the YouTube tracks of the playlists (by title, once) and merges the entries that
     * pointed to both the file and the online track, so a song can no longer be in a playlist twice.
     */
    public synchronized void linkLocalSongs(List<cl.arichard.descargador.model.Song> library) {
        Map<String, String> byTitle = new HashMap<>();
        for (Map.Entry<String, SearchResult> track : onlineTracks.entrySet()) {
            byTitle.putIfAbsent(ClickStats.normalize(track.getValue().getTitle()), track.getKey());
            byTitle.putIfAbsent(ClickStats.normalizeLoose(track.getValue().getTitle()), track.getKey());
        }
        android.content.SharedPreferences.Editor editor = localIds.edit();
        boolean linked = false;
        for (cl.arichard.descargador.model.Song song : library) {
            String path = song.getPath();
            if (song.isOnline() || path == null || pathToId.containsKey(path)) continue;
            String id = byTitle.get(ClickStats.normalize(song.getTitle()));
            if (id == null) id = byTitle.get(ClickStats.normalizeLoose(song.getTitle()));
            if (id == null) continue;
            pathToId.put(path, id); editor.putString(path, id); linked = true;
        }
        if (linked) editor.apply();
        boolean changed = false;
        for (Map.Entry<String, List<String>> playlist : playlists.entrySet()) {
            List<String> cleaned = new ArrayList<>();
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (String entry : playlist.getValue()) {
                String id = entry.startsWith("youtube:") ? entry.substring(8) : pathToId.get(entry);
                String key = id != null && onlineTracks.containsKey(id) ? "youtube:" + id : entry;
                if (!seen.add(key)) { changed = true; continue; }
                if (!key.equals(entry)) changed = true;
                cleaned.add(key);
            }
            if (!cleaned.equals(playlist.getValue())) playlist.setValue(cleaned);
        }
        if (changed) save();
    }

    public synchronized SearchResult getOnlineTrack(String entry) {
        if (entry == null || !entry.startsWith("youtube:")) return null;
        return onlineTracks.get(entry.substring("youtube:".length()));
    }

    public synchronized boolean isOnlineTrackInPlaylist(String playlistName, String videoId) {
        List<String> list = playlists.get(playlistName);
        return list != null && list.contains("youtube:" + videoId);
    }

    /** Online tracks of every playlist with auto-download on, without repeats, in playlist order. */
    public synchronized List<SearchResult> autoDownloadTracks() {
        Map<String, SearchResult> unique = new java.util.LinkedHashMap<>();
        for (String name : autoDownloadPlaylists) {
            List<String> list = playlists.get(name);
            if (list == null) continue;
            for (String entry : list) {
                SearchResult track = getOnlineTrack(entry);
                if (track != null) unique.putIfAbsent(track.getId(), track);
            }
        }
        return new ArrayList<>(unique.values());
    }

    public synchronized boolean isAutoDownloadEnabled(String playlistName) {
        return autoDownloadPlaylists.contains(playlistName);
    }

    public synchronized void setAutoDownloadEnabled(String playlistName, boolean enabled) {
        if (enabled) autoDownloadPlaylists.add(playlistName); else autoDownloadPlaylists.remove(playlistName);
        save();
    }

    public synchronized boolean addSongToPlaylist(String playlistName, String songPath) {
        List<String> list = playlists.get(playlistName);
        if (list == null) {
            list = new ArrayList<>();
            playlists.put(playlistName, list);
        }
        if (!list.contains(songPath)) {
            list.add(songPath);
            save();
            return true;
        }
        return false;
    }

    public synchronized boolean removeSongFromPlaylist(String playlistName, String songPath) {
        List<String> list = playlists.get(playlistName);
        if (list != null && list.remove(songPath)) {
            save();
            return true;
        }
        return false;
    }

    public synchronized boolean isFavorite(String songPath) {
        List<String> favs = playlists.get("Favoritos");
        return favs != null && favs.contains(songPath);
    }

    public synchronized void removeSongEverywhere(String path) {
        for (List<String> entries : playlists.values()) entries.remove(path);
        if (path.startsWith("youtube:")) onlineTracks.remove(path.substring(8));
        save();
    }

    public synchronized List<cl.arichard.descargador.model.Song> resolveSongs(String name,
            List<cl.arichard.descargador.model.Song> library) {
        List<cl.arichard.descargador.model.Song> songs = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        Map<String, cl.arichard.descargador.model.Song> localById = new HashMap<>();
        Map<String, cl.arichard.descargador.model.Song> localByPath = new HashMap<>();
        for (cl.arichard.descargador.model.Song song : library) {
            localByPath.put(song.getPath(), song);
            String id = pathToId.get(song.getPath());
            if (id != null) localById.putIfAbsent(id, song);
        }
        for (String entry : getSongPaths(name)) {
            String id = entry.startsWith("youtube:") ? entry.substring(8) : pathToId.get(entry);
            if (!seen.add(id != null ? id : entry)) continue;
            // A track already downloaded on this device plays from its file; otherwise it streams.
            cl.arichard.descargador.model.Song resolved = id != null ? localById.get(id) : null;
            if (resolved == null) resolved = localByPath.get(entry);
            if (resolved == null) {
                SearchResult result = getOnlineTrack(entry);
                if (result != null) resolved = new cl.arichard.descargador.model.Song(0, result.getTitle(), result.getChannel(),
                        "YouTube", result.getDurationSeconds() * 1000L, "youtube:" + result.getId(), "YouTube", 0);
            }
            if (resolved != null) songs.add(resolved);
        }
        return songs;
    }

    public synchronized boolean isFavoriteSong(cl.arichard.descargador.model.Song song) {
        if (song == null) return false;
        return isInPlaylist("Favoritos", song);
    }

    /** Adds or removes a song, local or from YouTube, to Favoritos. Returns the new state. */
    public synchronized boolean toggleFavoriteSong(cl.arichard.descargador.model.Song song) {
        if (song == null) return false;
        String videoId = song.getVideoId();
        if (isFavoriteSong(song)) {
            removeSongFromPlaylist("Favoritos", canonicalKey(song));
            removeSongFromPlaylist("Favoritos", song.getPath());
            removeEquivalentFavorites(song);
            return false;
        }
        if (!song.isOnline()) {
            String key = canonicalKey(song);
            if (key.startsWith("youtube:") && !onlineTracks.containsKey(key.substring(8))) key = song.getPath();
            addSongToPlaylist("Favoritos", key);
            return true;
        }
        addOnlineTrack("Favoritos", new SearchResult(videoId, song.getTitle(), song.getArtist(),
                Math.max(0, song.getDuration() / 1000), song.getCoverPath(), "https://www.youtube.com/watch?v=" + videoId));
        return true;
    }

    /** Removes the other copies of the same song (e.g. its YouTube entry) so an empty heart really means "not a favorite". */
    private void removeEquivalentFavorites(cl.arichard.descargador.model.Song song) {
        List<String> favorites = playlists.get("Favoritos");
        if (favorites == null) return;
        String wanted = ClickStats.normalize(song.getTitle());
        boolean changed = false;
        for (java.util.Iterator<String> it = favorites.iterator(); it.hasNext(); ) {
            SearchResult track = getOnlineTrack(it.next());
            if (track != null && wanted.equals(ClickStats.normalize(track.getTitle()))) { it.remove(); changed = true; }
        }
        if (changed) save();
    }

    public synchronized boolean toggleFavorite(String songPath) {
        if (isFavorite(songPath)) {
            removeSongFromPlaylist("Favoritos", songPath);
            return false;
        } else {
            addSongToPlaylist("Favoritos", songPath);
            return true;
        }
    }
}
