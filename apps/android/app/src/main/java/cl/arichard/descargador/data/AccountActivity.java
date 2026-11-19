package cl.arichard.descargador.data;

import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

/** Account history shared by Android, Windows and Web. No filesystem paths. */
public final class AccountActivity {
    private AccountActivity() {}
    private static long at(Object value) {
        return value instanceof JSONObject ? ((JSONObject) value).optLong("at")
                : value instanceof Number ? ((Number) value).longValue() : 0;
    }
    public static JSONObject merge(JSONObject left, JSONObject right) {
        if (left == null) left = new JSONObject();
        if (right == null) right = new JSONObject();
        JSONObject result = new JSONObject();
        try {
            for (String kind : new String[]{"queries", "played"}) {
                long cleared = Math.max(left.optLong(kind + "ClearedAt"), right.optLong(kind + "ClearedAt"));
                JSONObject rows = new JSONObject();
                for (JSONObject root : new JSONObject[]{left, right}) {
                    JSONObject source = root.optJSONObject(kind);
                    if (source == null) continue;
                    java.util.Iterator<String> keys = source.keys();
                    while (keys.hasNext()) {
                        String key = keys.next(); Object value = source.opt(key);
                        if (at(value) > cleared && at(value) >= at(rows.opt(key))) rows.put(key, value);
                    }
                }
                List<String> keys = new ArrayList<>(); rows.keys().forEachRemaining(keys::add);
                keys.sort((a,b) -> { int order = Long.compare(at(rows.opt(b)), at(rows.opt(a))); return order != 0 ? order : a.compareTo(b); });
                JSONObject bounded = new JSONObject();
                for (int i = 0; i < Math.min(keys.size(), kind.equals("queries") ? 8 : 50); i++) bounded.put(keys.get(i), rows.get(keys.get(i)));
                result.put(kind, bounded).put(kind + "ClearedAt", cleared);
            }
        } catch (org.json.JSONException ignored) {}
        return result;
    }
}
