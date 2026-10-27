package cl.arichard.descargador.data;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.LinkedHashSet;
import java.util.Set;

/** Mezcla de tres vías equivalente a playlist_sync.py; una copia antigua no borra adiciones que nunca vio. */
public final class PlaylistSync {
    private PlaylistSync() {}
    private static final Object MISSING = new Object();

    private static Set<String> keys(JSONObject object) {
        Set<String> result = new LinkedHashSet<>();
        if (object != null) object.keys().forEachRemaining(result::add);
        return result;
    }

    private static Set<String> entries(JSONArray array) {
        Set<String> result = new LinkedHashSet<>();
        if (array != null) for (int i = 0; i < array.length(); i++) result.add(array.optString(i));
        return result;
    }

    public static boolean same(Object left, Object right) {
        if (left == right) return true;
        if (left == MISSING || right == MISSING || left == null || right == null) return false;
        if (left instanceof JSONObject && right instanceof JSONObject) {
            JSONObject a = (JSONObject) left, b = (JSONObject) right;
            if (!keys(a).equals(keys(b))) return false;
            for (String key : keys(a)) if (!same(a.opt(key), b.opt(key))) return false;
            return true;
        }
        if (left instanceof JSONArray && right instanceof JSONArray) {
            JSONArray a = (JSONArray) left, b = (JSONArray) right;
            if (a.length() != b.length()) return false;
            for (int i = 0; i < a.length(); i++) if (!same(a.opt(i), b.opt(i))) return false;
            return true;
        }
        return left.equals(right);
    }

    private static Object value(JSONObject object, String key) { return object != null && object.has(key) ? object.opt(key) : MISSING; }

    private static Object mergeValue(Object base, Object local, Object remote) throws Exception {
        if (same(local, base)) return remote;
        if (same(remote, base)) return local;
        if (local == MISSING) return remote;
        if (remote == MISSING) return local;
        if (local instanceof JSONObject && remote instanceof JSONObject) {
            JSONObject before = base instanceof JSONObject ? (JSONObject) base : new JSONObject();
            JSONObject mine = (JSONObject) local, theirs = (JSONObject) remote, result = new JSONObject();
            Set<String> names = keys(theirs); names.addAll(keys(mine)); names.addAll(keys(before));
            for (String name : names) {
                Object merged = mergeValue(value(before, name), value(mine, name), value(theirs, name));
                if (merged != MISSING) result.put(name, merged);
            }
            return result;
        }
        if (local instanceof JSONArray && remote instanceof JSONArray) {
            Set<String> before = entries(base instanceof JSONArray ? (JSONArray) base : null);
            Set<String> mine = entries((JSONArray) local), theirs = entries((JSONArray) remote);
            Set<String> removed = new LinkedHashSet<>();
            for (String entry : before) if (!mine.contains(entry) || !theirs.contains(entry)) removed.add(entry);
            Set<String> result = new LinkedHashSet<>(mine); result.addAll(theirs); result.removeAll(removed);
            return new JSONArray(result);
        }
        return local;
    }

    public static JSONObject merge(JSONObject base, JSONObject local, JSONObject remote) throws Exception {
        Object result = mergeValue(base == null ? new JSONObject() : base,
                local == null ? new JSONObject() : local, remote == null ? new JSONObject() : remote);
        return new JSONObject(result.toString());
    }

    public static void appendAudit(JSONObject root, JSONObject previous, JSONObject current,
                                   String device, long now) throws Exception {
        JSONObject before = previous == null ? null : previous.optJSONObject("playlists");
        JSONObject after = current == null ? null : current.optJSONObject("playlists");
        Set<String> names = keys(before); names.addAll(keys(after));
        JSONArray changes = new JSONArray();
        for (String name : names) {
            Object oldValue = value(before, name), newValue = value(after, name);
            if (same(oldValue, newValue)) continue;
            Set<String> old = entries(before == null ? null : before.optJSONArray(name));
            Set<String> next = entries(after == null ? null : after.optJSONArray(name));
            Set<String> added = new LinkedHashSet<>(next); added.removeAll(old);
            Set<String> removed = new LinkedHashSet<>(old); removed.removeAll(next);
            changes.put(new JSONObject().put("name", name).put("before", old.size()).put("after", next.size())
                    .put("added", added.size()).put("removed", removed.size()));
        }
        if (changes.length() == 0) return;
        JSONArray history = root.optJSONArray("playlistAudit");
        JSONArray bounded = new JSONArray();
        if (history != null) for (int i = Math.max(0, history.length() - 29); i < history.length(); i++) bounded.put(history.get(i));
        bounded.put(new JSONObject().put("at", now).put("device", device).put("platform", "Android").put("changes", changes));
        root.put("playlistAudit", bounded);
    }
}
