package cl.arichard.descargador.util;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

import cl.arichard.descargador.model.SearchResult;

/** Consultas rápidas al endpoint web interno de YouTube (~0.5 s, frente a varios segundos con yt-dlp). */
public final class YoutubeApi {
    private static final String BASE = "https://www.youtube.com/youtubei/v1/";
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36";

    private YoutubeApi() {}

    public static final class SearchPage {
        public final List<SearchResult> videos = new ArrayList<>();
        public String continuation = "";
    }

    private static void collect(Object node, String key, List<JSONObject> out) {
        if (node instanceof JSONObject) {
            JSONObject object = (JSONObject) node;
            if (object.has(key) && object.opt(key) instanceof JSONObject) out.add(object.optJSONObject(key));
            JSONArray names = object.names();
            for (int i = 0; names != null && i < names.length(); i++) collect(object.opt(names.optString(i)), key, out);
        } else if (node instanceof JSONArray) {
            JSONArray array = (JSONArray) node;
            for (int i = 0; i < array.length(); i++) collect(array.opt(i), key, out);
        }
    }

    private static JSONObject post(String endpoint, JSONObject fields) throws Exception {
        JSONObject client = new JSONObject().put("clientName", "WEB").put("clientVersion", "2.20250101.00.00")
                .put("hl", "es").put("gl", "CL");
        fields.put("context", new JSONObject().put("client", client));
        HttpURLConnection conn = (HttpURLConnection) new URL(BASE + endpoint + "?prettyPrint=false").openConnection();
        try {
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(8000);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("User-Agent", USER_AGENT);
            conn.getOutputStream().write(fields.toString().getBytes("UTF-8"));
            if (conn.getResponseCode() != 200) throw new IOException("HTTP " + conn.getResponseCode());
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            try (InputStream in = conn.getInputStream()) {
                byte[] chunk = new byte[8192]; int n;
                while ((n = in.read(chunk)) > 0) buf.write(chunk, 0, n);
            }
            return new JSONObject(buf.toString("UTF-8"));
        } finally {
            conn.disconnect();
        }
    }

    private static long seconds(JSONObject length) {
        if (length == null) return 0;
        long seconds = 0;
        try {
            for (String part : length.optString("simpleText", "0").split(":")) seconds = seconds * 60 + Long.parseLong(part.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
        return seconds;
    }

    private static String firstRun(JSONObject text, String fallback) {
        if (text == null) return fallback;
        JSONArray runs = text.optJSONArray("runs");
        if (runs != null && runs.length() > 0) return runs.optJSONObject(0).optString("text", fallback);
        return text.optString("simpleText", fallback);
    }

    /** Búsqueda de videos (solo videos), hasta {@code limit} resultados. */
    public static List<SearchResult> search(String query, int limit) throws Exception {
        return searchPage(query, "", limit).videos;
    }

    public static SearchPage searchPage(String query, String continuation, int limit) throws Exception {
        JSONObject response = continuation == null || continuation.isEmpty()
                ? post("search", new JSONObject().put("query", query).put("params", "EgIQAQ%3D%3D"))
                : post("search", new JSONObject().put("continuation", continuation));
        SearchPage page = new SearchPage();
        List<JSONObject> videos = new ArrayList<>(); collect(response, "videoRenderer", videos);
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (JSONObject video : videos) {
            if (page.videos.size() >= limit) break;
            String id = video.optString("videoId", "");
            if (id.isEmpty() || !seen.add(id)) continue;
            page.videos.add(new SearchResult(id, firstRun(video.optJSONObject("title"), ""), firstRun(video.optJSONObject("ownerText"), "YouTube"),
                    seconds(video.optJSONObject("lengthText")), "https://i.ytimg.com/vi/" + id + "/mqdefault.jpg",
                    "https://www.youtube.com/watch?v=" + id));
        }
        List<JSONObject> tokens = new ArrayList<>(); collect(response, "nextContinuationData", tokens);
        if (!tokens.isEmpty()) page.continuation = tokens.get(0).optString("continuation", "");
        if (page.continuation.isEmpty()) {
            tokens.clear(); collect(response, "continuationCommand", tokens);
            if (!tokens.isEmpty()) page.continuation = tokens.get(0).optString("token", "");
        }
        return page;
    }

    /** Canciones relacionadas: la lista Mix de YouTube ({@code RD<id>}) que arranca en ese video, sin incluirlo. */
    public static List<SearchResult> related(String videoId, int limit) throws Exception {
        JSONObject response = post("next", new JSONObject().put("videoId", videoId).put("playlistId", "RD" + videoId));
        JSONArray items = response.getJSONObject("contents").getJSONObject("twoColumnWatchNextResults")
                .getJSONObject("playlist").getJSONObject("playlist").getJSONArray("contents");
        List<SearchResult> found = new ArrayList<>();
        for (int i = 0; i < items.length() && found.size() < limit; i++) {
            JSONObject v = items.getJSONObject(i).optJSONObject("playlistPanelVideoRenderer");
            if (v == null) continue;
            String id = v.optString("videoId", "");
            if (id.isEmpty() || id.equals(videoId)) continue;
            found.add(new SearchResult(id, firstRun(v.optJSONObject("title"), ""), firstRun(v.optJSONObject("longBylineText"), "YouTube"),
                    seconds(v.optJSONObject("lengthText")), "https://i.ytimg.com/vi/" + id + "/mqdefault.jpg",
                    "https://www.youtube.com/watch?v=" + id));
        }
        return found;
    }
}
