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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import cl.arichard.descargador.model.SearchResult;
import cl.arichard.descargador.model.Song;

public class PlaylistManager {
    private static PlaylistManager instance;
    private final File storageFile;
    private final android.content.SharedPreferences localIds;
    private final android.content.SharedPreferences backups;
    /** Downloaded file on THIS device -> its YouTube video id (paths are device specific, so this is never synced). */
    private final Map<String, String> pathToId = new HashMap<>();
    private final Map<String, List<String>> playlists = new HashMap<>();
    private JSONObject entryAddedAt = new JSONObject();
    private final Map<String, SearchResult> onlineTracks = new HashMap<>();
    private final Map<String, String> descriptions = new HashMap<>();
    private final List<String> playlistOrder = new ArrayList<>();
    private final Set<String> pinnedPlaylists = new java.util.LinkedHashSet<>();
    private final Set<String> autoDownloadPlaylists = new HashSet<>();
    private Runnable changeListener;
    private final List<Runnable> observers = new ArrayList<>();
    private static final android.os.Handler MAIN = new android.os.Handler(android.os.Looper.getMainLooper());
    public interface AutoDownloadListener { void onTrackAdded(String playlistName, SearchResult track); }
    private AutoDownloadListener autoDownloadListener;

    private PlaylistManager(Context context) {
        this.storageFile = new File(context.getFilesDir(), "playlists.json");
        this.localIds = context.getSharedPreferences("local_video_ids", Context.MODE_PRIVATE);
        this.backups = context.getSharedPreferences("playlist_backups", Context.MODE_PRIVATE);
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
        playlistOrder.clear(); pinnedPlaylists.clear();
        autoDownloadPlaylists.clear();
        if (!storageFile.exists() && !new File(storageFile.getPath() + ".bak").exists()) return;
        try {
            byte[] data = new android.util.AtomicFile(storageFile).readFully();
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
            entryAddedAt = root.optJSONObject("entryAddedAt");
            if (entryAddedAt == null) entryAddedAt = new JSONObject();
            for (Map.Entry<String, List<String>> list : playlists.entrySet()) {
                JSONObject dates = entryAddedAt.optJSONObject(list.getKey());
                if (dates == null) dates = new JSONObject();
                for (int i = 0; i < list.getValue().size(); i++) {
                    String entry = list.getValue().get(i);
                    if (dates.optLong(entry) <= 0) dates.put(entry, i + 1);
                }
                entryAddedAt.put(list.getKey(), dates);
            }
            JSONObject tracks = root.optJSONObject("onlineTracks");
            if (tracks != null) {
                JSONArray ids = tracks.names();
                if (ids != null) for (int i = 0; i < ids.length(); i++) {
                    String id = ids.getString(i);
                    JSONObject item = tracks.getJSONObject(id);
                    if (id.startsWith("youtube:")) id = id.substring(8);
                    onlineTracks.put(id, new SearchResult(id, item.optString("title"),
                            item.optString("channel"), item.optLong("duration"),
                            item.optString("thumbnail"), item.optString("url")));
                }
            }
            JSONArray auto = root.optJSONArray("autoDownload");
            JSONArray order = root.optJSONArray("playlistOrder");
            if (order != null) for (int i = 0; i < order.length(); i++) {
                String name = order.optString(i);
                if (playlists.containsKey(name) && !playlistOrder.contains(name)) playlistOrder.add(name);
            }
            JSONArray pinned = root.optJSONArray("pinnedPlaylists");
            if (pinned != null) for (int i = 0; i < pinned.length(); i++) {
                String name = pinned.optString(i);
                if (playlists.containsKey(name)) pinnedPlaylists.add(name);
            }
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
        JSONObject cleaned = new JSONObject();
        try {
            for (Map.Entry<String, List<String>> list : playlists.entrySet()) {
                JSONObject old = entryAddedAt.optJSONObject(list.getKey()), dates = new JSONObject();
                for (String key : list.getValue()) dates.put(key, old != null && old.has(key) ? old.optLong(key) : System.currentTimeMillis());
                cleaned.put(list.getKey(), dates);
            }
        } catch (org.json.JSONException ignored) {}
        entryAddedAt = cleaned;
        if (!persist(exportJson())) return;
        if (changeListener != null) changeListener.run();
        for (Runnable observer : new ArrayList<>(observers)) MAIN.post(observer);
    }

    public synchronized void setChangeListener(Runnable listener) { changeListener = listener; }
    /** Extra listeners (for example the media notification) that learn about every change on the main thread. */
    public synchronized void addObserver(Runnable observer) { if (!observers.contains(observer)) observers.add(observer); }
    public synchronized void removeObserver(Runnable observer) { observers.remove(observer); }
    public synchronized void setAutoDownloadListener(AutoDownloadListener listener) { autoDownloadListener = listener; }

    public synchronized JSONObject exportJson() {
        JSONObject root = new JSONObject();
        try {
            JSONObject lists = new JSONObject();
            for (Map.Entry<String, List<String>> entry : playlists.entrySet())
                lists.put(entry.getKey(), new JSONArray(entry.getValue()));
            root.put("playlists", lists);
            root.put("entryAddedAt", new JSONObject(entryAddedAt.toString()));
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
            root.put("playlistOrder", new JSONArray(getPlaylistNames()));
            root.put("pinnedPlaylists", new JSONArray(pinnedPlaylists));
        } catch (Exception ignored) {}
        return root;
    }

    public synchronized void importJson(JSONObject root) {
        if (root == null) return;
        if (!persist(root)) return;
        load();
        if (!playlists.containsKey("Favoritos")) playlists.put("Favoritos", new ArrayList<>());
    }

    /** Respalda la copia anterior y reemplaza el archivo de forma atómica, incluso si se cierra el proceso. */
    private boolean persist(JSONObject root) {
        android.util.AtomicFile atomic = new android.util.AtomicFile(storageFile);
        FileOutputStream output = null;
        try {
            if (storageFile.isFile()) {
                byte[] bytes = atomic.readFully();
                JSONObject previous;
                try { previous = new JSONObject(new String(bytes, StandardCharsets.UTF_8)); }
                catch (Exception corruptedPrevious) {
                    previous = new JSONObject().put("unparsed", new String(bytes, StandardCharsets.UTF_8));
                }
                if (!PlaylistSync.same(previous, root)) {
                    JSONArray history;
                    try { history = new JSONArray(backups.getString("snapshots", "[]")); }
                    catch (Exception corruptedHistory) { history = new JSONArray(); }
                    JSONArray bounded = new JSONArray();
                    for (int i = Math.max(0, history.length() - 9); i < history.length(); i++) bounded.put(history.get(i));
                    bounded.put(new JSONObject().put("at", System.currentTimeMillis()).put("state", previous));
                    backups.edit().putString("snapshots", bounded.toString()).commit();
                }
            }
            output = atomic.startWrite();
            output.write(root.toString().getBytes(StandardCharsets.UTF_8));
            atomic.finishWrite(output);
            return true;
        } catch (Exception error) {
            if (output != null) atomic.failWrite(output);
            android.util.Log.e("MusicBoxPlaylists", "No se pudo guardar la playlist; se conserva la copia anterior", error);
            return false;
        }
    }

    public synchronized void resetForAccount() {
        playlists.clear(); onlineTracks.clear(); autoDownloadPlaylists.clear();
        descriptions.clear(); playlistOrder.clear(); pinnedPlaylists.clear(); freshlyAdded.clear(); entryAddedAt = new JSONObject();
        playlists.put("Favoritos", new ArrayList<>()); save();
    }

    public synchronized List<String> getPlaylistNames() {
        List<String> list = new ArrayList<>();
        for (String name : playlistOrder) if (playlists.containsKey(name) && !list.contains(name)) list.add(name);
        if (!list.contains("Favoritos")) list.add(0, "Favoritos");
        for (String name : playlists.keySet()) {
            if (!list.contains(name)) {
                list.add(name);
            }
        }
        return list;
    }

    public synchronized List<String> getVisiblePlaylistNames(List<cl.arichard.descargador.model.Song> library) {
        List<String> names = getPlaylistNames();
        if (resolveSongs("Favoritos", library).isEmpty()) names.remove("Favoritos");
        return names;
    }

    /** Reordena solo las entradas indicadas: las ocultas o filtradas conservan su lugar. */
    public synchronized void setPlaylistOrder(List<String> requested) {
        if (requested == null) return;
        List<String> chosen = new ArrayList<>();
        for (String name : requested) if (playlists.containsKey(name) && !chosen.contains(name)) chosen.add(name);
        List<String> ordered = getPlaylistNames();
        int next = 0;
        for (int i = 0; i < ordered.size(); i++) if (chosen.contains(ordered.get(i))) ordered.set(i, chosen.get(next++));
        if (ordered.equals(getPlaylistNames())) return;
        playlistOrder.clear(); playlistOrder.addAll(ordered); save();
    }

    public synchronized boolean isPinned(String name) { return pinnedPlaylists.contains(name); }

    public synchronized void setPinned(String name, boolean pinned) {
        if (!playlists.containsKey(name)) return;
        boolean changed = pinned ? pinnedPlaylists.add(name) : pinnedPlaylists.remove(name);
        if (changed) save();
    }

    public synchronized List<String> getPinnedPlaylistNames(List<cl.arichard.descargador.model.Song> library) {
        List<String> names = getVisiblePlaylistNames(library);
        names.retainAll(pinnedPlaylists);
        return names;
    }

    public synchronized List<String> getSongPaths(String playlistName) {
        List<String> list = playlists.get(playlistName);
        return list != null ? new ArrayList<>(list) : new ArrayList<>();
    }

    public synchronized boolean createPlaylist(String name) {
        if (name == null || name.trim().isEmpty() || playlists.containsKey(name.trim())) {
            return false;
        }
        List<String> previousOrder = getPlaylistNames();
        playlists.put(name.trim(), new ArrayList<>());
        playlistOrder.clear(); playlistOrder.addAll(previousOrder); playlistOrder.add(name.trim());
        save();
        return true;
    }

    public synchronized boolean deletePlaylist(String name) {
        if ("Favoritos".equalsIgnoreCase(name)) return false;
        if (playlists.remove(name) != null) {
            autoDownloadPlaylists.remove(name);
            descriptions.remove(name);
            playlistOrder.remove(name); pinnedPlaylists.remove(name); freshlyAdded.remove(name);
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
            List<String> previousOrder = getPlaylistNames();
            List<String> songs = playlists.remove(oldName);
            playlists.put(cleanName, songs);
            try { Object dates=entryAddedAt.remove(oldName);if(dates!=null)entryAddedAt.put(cleanName,dates); } catch(Exception ignored) {}
            playlistOrder.clear();
            for (String name : previousOrder) playlistOrder.add(oldName.equals(name) ? cleanName : name);
            if (pinnedPlaylists.remove(oldName)) pinnedPlaylists.add(cleanName);
            Set<String> fresh = freshlyAdded.remove(oldName);
            if (fresh != null) freshlyAdded.put(cleanName, fresh);
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

    public synchronized boolean addSongToPlaylist(String name, Song song) {
        if (isInPlaylist(name, song)) return false;
        String key = songIdentity(song);
        if (!key.startsWith("youtube:")) return addSongToPlaylist(name, key);
        String id = key.substring(8);
        SearchResult metadata = onlineTracks.get(id);
        if (!song.isOnline()) {
            if (metadata == null) onlineTracks.put(id, trackMetadata(id, song));
            return addSongToPlaylist(name, key);
        }
        return addOnlineTrack(name, metadata == null ? trackMetadata(id, song) : metadata);
    }

    private static SearchResult trackMetadata(String id, Song song) {
        return new SearchResult(id, song.getTitle(), song.getArtist(), Math.max(0, song.getDuration() / 1000),
                song.isOnline() ? song.getCoverPath() : "https://i.ytimg.com/vi/" + id + "/hqdefault.jpg",
                "https://www.youtube.com/watch?v=" + id);
    }

    public synchronized String videoIdFor(String path) { return path == null ? null : pathToId.get(path); }

    public synchronized String localVideoFor(String videoId) {
        if (videoId == null) return null;
        for (Map.Entry<String, String> entry : pathToId.entrySet()) {
            String path = entry.getKey();
            if (videoId.equals(entry.getValue()) && path.toLowerCase(java.util.Locale.ROOT).endsWith(".mp4")
                    && new java.io.File(path).isFile()) return path;
        }
        return null;
    }

    public synchronized boolean hasLocalCopy(String videoId) { return pathToId.containsValue(videoId); }

    /** ¿La canción ya está en alguna playlist (en línea o como archivo local vinculado)? Las recomendaciones no la repiten. */
    public synchronized boolean isInAnyPlaylist(String videoId) {
        if (videoId == null || videoId.isEmpty()) return false;
        String online = "youtube:" + videoId;
        for (List<String> entries : playlists.values()) for (String entry : entries) {
            if (online.equals(entry)) return true;
            if (pathToId.containsKey(entry) && videoId.equals(pathToId.get(entry))) return true;
        }
        return false;
    }

    /** Declara que un archivo local es la copia de un video de YouTube (importar carpeta). */
    public synchronized void linkLocalFile(String path, String videoId) {
        if (path == null || path.isEmpty() || videoId == null || videoId.isEmpty()) return;
        pathToId.put(path, videoId);
        localIds.edit().putString(path, videoId).apply();
    }

    /** The key a song is stored under in playlists: its YouTube id whenever known, so every device agrees. */
    public synchronized String canonicalKey(cl.arichard.descargador.model.Song song) {
        if (song == null) return "";
        if (song.isOnline()) return song.getPath();
        String id = pathToId.get(song.getPath());
        return id != null && onlineTracks.containsKey(id) ? "youtube:" + id : song.getPath();
    }

    private String portableVideoId(String entry) {
        if (entry.startsWith("youtube:")) return entry.length() == 8 ? null : entry.substring(8);
        return onlineTracks.containsKey(entry) ? entry : null;
    }

    private String entryVideoId(String entry) {
        String id = portableVideoId(entry);
        return id != null ? id : pathToId.get(entry);
    }

    public synchronized String songIdentity(Song song) {
        String id = song.isOnline() ? song.getVideoId() : pathToId.get(song.getPath());
        return id == null ? song.getPath() : "youtube:" + id;
    }

    public synchronized boolean hasLegacyFileEntries() {
        for (List<String> entries : playlists.values()) {
            for (String entry : entries) if (portableVideoId(entry) == null) return true;
        }
        return false;
    }

    public synchronized List<Song> preferLocalCopies(List<Song> queue, List<Song> library) {
        Map<String, Song> byVideoId = new HashMap<>();
        for (Song candidate : library) {
            String id = pathToId.get(candidate.getPath());
            if (id != null && new File(candidate.getPath()).isFile()) byVideoId.putIfAbsent(id, candidate);
        }
        List<Song> result = new ArrayList<>(queue.size());
        for (Song entry : queue) {
            Song local = entry.isOnline() ? byVideoId.get(entry.getVideoId()) : null;
            result.add(local == null ? entry : new Song(local.getId(), entry.getTitle(), entry.getArtist(),
                    local.getAlbum(), local.getDuration(), local.getPath(), local.getFolder(), local.getDateAdded()));
        }
        return result;
    }

    public synchronized boolean isInPlaylist(String playlistName, cl.arichard.descargador.model.Song song) {
        List<String> list = playlists.get(playlistName);
        return list != null && song != null && list.stream().anyMatch(entry -> matchesPath(entry, song.getPath()));
    }

    /**
     * Links downloaded files to the YouTube tracks of the playlists (by title, once) and merges the entries that
     * pointed to both the file and the online track, so a song can no longer be in a playlist twice.
     */
    public synchronized void linkLocalSongs(List<cl.arichard.descargador.model.Song> library) {
        Map<String, Song> byPath = new HashMap<>();
        Map<String, Set<String>> byTitle = new HashMap<>();
        for (Map.Entry<String, SearchResult> track : onlineTracks.entrySet()) {
            for (String title : new String[]{ClickStats.normalize(track.getValue().getTitle()),
                    ClickStats.normalizeLoose(track.getValue().getTitle())}) {
                if (!title.isEmpty()) byTitle.computeIfAbsent(title, ignored -> new HashSet<>()).add(track.getKey());
            }
        }
        android.content.SharedPreferences.Editor editor = localIds.edit();
        boolean linked = false;
        for (cl.arichard.descargador.model.Song song : library) {
            String path = song.getPath();
            if (!song.isOnline() && path != null) byPath.put(path, song);
            if (song.isOnline() || path == null || pathToId.containsKey(path)) continue;
            Set<String> matches = byTitle.get(ClickStats.normalizeLoose(song.getTitle()));
            if (matches == null) matches = byTitle.get(ClickStats.normalize(song.getTitle()));
            if (matches == null || matches.size() != 1) continue;
            String id = matches.iterator().next();
            pathToId.put(path, id); editor.putString(path, id); linked = true;
        }
        if (linked) editor.apply();
        boolean changed = false;
        for (Map.Entry<String, List<String>> playlist : playlists.entrySet()) {
            List<String> cleaned = new ArrayList<>();
            java.util.Set<String> seen = new java.util.HashSet<>();
            JSONObject dates = entryAddedAt.optJSONObject(playlist.getKey());
            for (String entry : playlist.getValue()) {
                String id = entryVideoId(entry);
                Song file = byPath.get(entry);
                if (id != null && file != null && !onlineTracks.containsKey(id)) {
                    onlineTracks.put(id, trackMetadata(id, file));
                    changed = true;
                }
                String key = id != null && onlineTracks.containsKey(id) ? "youtube:" + id : entry;
                if (!key.equals(entry) && dates != null) {
                    long old = dates.optLong(entry), canonical = dates.optLong(key);
                    if (old > 0 && (canonical <= 0 || old < canonical)) {
                        try { dates.put(key, old); } catch (org.json.JSONException impossible) { throw new IllegalStateException(impossible); }
                    }
                }
                if (!seen.add(key)) { changed = true; continue; }
                if (!key.equals(entry)) changed = true;
                cleaned.add(key);
            }
            if (!cleaned.equals(playlist.getValue())) playlist.setValue(cleaned);
        }
        if (changed) save();
    }

    public synchronized SearchResult getOnlineTrack(String entry) {
        return entry == null ? null : onlineTracks.get(entryVideoId(entry));
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

    public synchronized String autoPlaylistFor(String videoId) {
        for (String name : autoDownloadPlaylists) if (isOnlineTrackInPlaylist(name, videoId)) return name;
        return "";
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
            Set<String> fresh = freshlyAdded.get(playlistName);
            if (fresh == null) { fresh = new java.util.LinkedHashSet<>(); freshlyAdded.put(playlistName, fresh); }
            fresh.add(songPath);
            save();
            return true;
        }
        return false;
    }

    /** Canciones agregadas a cada playlist que aún no se han visto al abrirla. */
    private final Map<String, Set<String>> freshlyAdded = new HashMap<>();

    /** Devuelve las canciones recién agregadas a la playlist y las olvida: el realce se muestra una sola vez. */
    public synchronized Set<String> takeFreshlyAdded(String playlistName) {
        Set<String> fresh = freshlyAdded.remove(playlistName);
        return fresh == null ? new java.util.LinkedHashSet<>() : fresh;
    }

    /** Devuelve a la lista de pendientes las canciones nuevas que todavía no se pudieron mostrar. */
    public synchronized void keepFreshlyAdded(String playlistName, java.util.Collection<String> pending) {
        if (pending == null || pending.isEmpty()) return;
        Set<String> fresh = freshlyAdded.get(playlistName);
        if (fresh == null) { fresh = new java.util.LinkedHashSet<>(); freshlyAdded.put(playlistName, fresh); }
        fresh.addAll(pending);
    }

    public synchronized boolean removeSongFromPlaylist(String playlistName, String songPath) {
        List<String> list = playlists.get(playlistName);
        if (list == null) return false;
        boolean removed = list.removeIf(entry -> matchesPath(entry, songPath));
        if (removed) save();
        return removed;
    }

    /** Mueve una entrada sin borrar archivos ni sus metadatos online. */
    public synchronized boolean moveSongToPlaylist(String source, String destination, String path) {
        if (source == null || source.equals(destination) || !playlists.containsKey(destination)) return false;
        List<String> origin = playlists.get(source);
        if (origin == null || path == null) return false;
        String key = null;
        for (String entry : origin) if (matchesPath(entry, path)) { key = entry; break; }
        if (key == null) return false;
        List<String> target = playlists.get(destination);
        boolean alreadyPresent = false;
        for (String entry : target) if (matchesPath(entry, path)) { alreadyPresent = true; break; }
        if (!alreadyPresent) {
            target.add(key);
            freshlyAdded.computeIfAbsent(destination, ignored -> new java.util.LinkedHashSet<>()).add(key);
        }
        origin.removeIf(entry -> matchesPath(entry, path));
        Set<String> fresh = freshlyAdded.get(source);
        if (fresh != null) fresh.removeIf(entry -> matchesPath(entry, path));
        save();
        return true;
    }

    /** Nombres de las playlists que contienen esta canción. */
    public synchronized List<String> playlistsContaining(String path) {
        List<String> names = new ArrayList<>();
        if (path == null) return names;
        for (Map.Entry<String, List<String>> playlist : playlists.entrySet())
            for (String entry : playlist.getValue()) if (matchesPath(entry, path)) { names.add(playlist.getKey()); break; }
        return names;
    }

    /**
     * Cambia la canción por [replacement] en cada playlist, en la misma posición. Si la playlist ya tenía la
     * nueva versión, solo se quita la anterior. No borra ningún archivo. Devuelve cuántas entradas cambió.
     */
    public synchronized int replaceEverywhere(String path, SearchResult replacement) {
        if (path == null || replacement == null) return 0;
        String key = "youtube:" + replacement.getId();
        int changed = 0;
        for (List<String> entries : playlists.values()) {
            boolean hasNew = entries.contains(key);
            for (int i = 0; i < entries.size(); i++) {
                if (!matchesPath(entries.get(i), path) || entries.get(i).equals(key)) continue;
                if (hasNew) entries.remove(i--); else { entries.set(i, key); hasNew = true; }
                changed++;
            }
        }
        if (changed > 0) { onlineTracks.put(replacement.getId(), replacement); save(); }
        return changed;
    }

    /** ¿La entrada de la playlist corresponde a esta ruta, directamente o por su ID de YouTube? */
    private boolean matchesPath(String entry, String path) {
        if (entry.equals(path)) return true;
        String id = path == null ? null : entryVideoId(path);
        return id != null && id.equals(entryVideoId(entry));
    }

    public synchronized boolean isFavorite(String songPath) {
        List<String> favs = playlists.get("Favoritos");
        if (favs == null) return false;
        for (String entry : favs) if (matchesPath(entry, songPath)) return true;
        return false;
    }

    public synchronized void removeSongEverywhere(String path) {
        for (List<String> entries : playlists.values()) {
            for (java.util.Iterator<String> it = entries.iterator(); it.hasNext(); )
                if (matchesPath(it.next(), path)) it.remove();
        }
        if (path.startsWith("youtube:")) onlineTracks.remove(path.substring(8));
        if (pathToId.remove(path) != null) localIds.edit().remove(path).apply();
        save();
    }

    public synchronized List<Song> accountSongs(List<Song> local, boolean signedIn) {
        Map<String, Song> rows = new LinkedHashMap<>();
        if (!signedIn) for (Song song : local) rows.putIfAbsent(songIdentity(song), song);
        List<String> names = new ArrayList<>(playlists.keySet());
        java.util.Collections.sort(names);
        List<String> entries = new ArrayList<>();
        for (String name : names) for (String entry : playlists.get(name)) {
            if (!signedIn || portableVideoId(entry) != null) entries.add(entry);
        }
        for (Song song : resolveEntries(entries, local)) rows.putIfAbsent(songIdentity(song), song);
        return new ArrayList<>(rows.values());
    }

    public synchronized Map<String, Long> libraryDates() {
        Map<String, Long> result=new HashMap<>();
        for (Map.Entry<String, List<String>> playlist : playlists.entrySet()) {
            for (Map.Entry<String, Long> entry : membershipDates(playlist.getKey(), false).entrySet()) {
                result.merge(entry.getKey(), entry.getValue(), Math::min);
            }
        }
        return result;
    }

    public synchronized Map<String, Long> membershipDates(String playlist) {
        return membershipDates(playlist, true);
    }

    private Map<String, Long> membershipDates(String playlist, boolean includeFileLinks) {
        Map<String, Long> result = new HashMap<>();
        JSONObject dates = entryAddedAt.optJSONObject(playlist);
        List<String> entries = playlists.get(playlist);
        if (entries == null) return result;
        for (int i = 0; i < entries.size(); i++) {
            String entry = entries.get(i);
            String id = includeFileLinks ? entryVideoId(entry) : portableVideoId(entry);
            long date = dates == null ? 0 : dates.optLong(entry);
            result.merge(id == null ? entry : "youtube:" + id, date > 0 ? date : i + 1L, Math::min);
        }
        return result;
    }

    public synchronized List<cl.arichard.descargador.model.Song> resolveSongs(String name,
            List<cl.arichard.descargador.model.Song> library) {
        return resolveEntries(getSongPaths(name), library);
    }

    private List<Song> resolveEntries(List<String> entries, List<Song> library) {
        List<cl.arichard.descargador.model.Song> songs = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        Map<String, cl.arichard.descargador.model.Song> localById = new HashMap<>();
        Map<String, cl.arichard.descargador.model.Song> localByPath = new HashMap<>();
        for (cl.arichard.descargador.model.Song song : library) {
            localByPath.put(song.getPath(), song);
            String id = pathToId.get(song.getPath());
            if (id != null) localById.putIfAbsent(id, song);
        }
        for (String entry : entries) {
            String id = entryVideoId(entry);
            if (!seen.add(id != null ? id : entry)) continue;
            // A track already downloaded on this device plays from its file; otherwise it streams.
            cl.arichard.descargador.model.Song resolved = id != null ? localById.get(id) : null;
            if (resolved == null) resolved = localByPath.get(entry);
            if (id != null && (entry.startsWith("youtube:") || onlineTracks.containsKey(id))) {
                SearchResult metadata = onlineTracks.get(id);
                if (metadata != null) Song.rememberArt(id, metadata.getThumbnailUrl());
                String title = metadata == null || metadata.getTitle().isEmpty() ? id : metadata.getTitle();
                String artist = metadata == null || metadata.getChannel().isEmpty()
                        ? "YouTube" : metadata.getChannel().split(" · ", 2)[0].trim();
                long duration = metadata == null ? 0 : metadata.getDurationSeconds() * 1000L;
                if (resolved != null) {
                    resolved = new Song(resolved.getId(), title, artist, resolved.getAlbum(), duration,
                            resolved.getPath(), resolved.getFolder(), resolved.getDateAdded());
                } else if (entry.startsWith("youtube:") || metadata != null) {
                    resolved = new Song(0, title, artist, "YouTube", duration, "youtube:" + id, "YouTube", 0);
                }
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
        if (isFavoriteSong(song)) {
            removeSongFromPlaylist("Favoritos", song.getPath());
            return false;
        }
        return addSongToPlaylist("Favoritos", song);
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
