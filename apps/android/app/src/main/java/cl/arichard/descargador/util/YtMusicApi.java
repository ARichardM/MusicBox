package cl.arichard.descargador.util;

import cl.arichard.descargador.model.SearchResult;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Cliente mínimo de YouTube Music, sin cuenta. Equivale a ytmusic.py de escritorio.
 *
 * YouTube Music separa lo que YouTube mezcla: cada elemento trae un {@code musicVideoType}. {@code ATV} es la pista de
 * audio oficial (una canción); {@code OMV} es el videoclip oficial y {@code UGC} un video subido por cualquiera. Aquí solo
 * se aceptan canciones, ya con su artista, que es lo que usan "Para ti" y la página del artista.
 */
public final class YtMusicApi {
    private static final String BASE = "https://music.youtube.com/youtubei/v1/";
    private static final String VERSION = "1.20250101.01.00";
    private static final String ARTISTS = "EgWKAQIgAWoKEAkQChAFEAMQBA%3D%3D";
    private static final Pattern DURATION = Pattern.compile("^\\d{1,2}(:\\d{2}){1,2}$");
    private static final Pattern YEAR = Pattern.compile("^(19|20)\\d{2}$");
    private static final String SEPARATOR = "•";
    private static final int MAX_PLAYLIST = 300;

    private YtMusicApi() {}

    public static final class Artist {
        public final String name, browseId, photo;
        public Artist(String name, String browseId, String photo) { this.name = name; this.browseId = browseId; this.photo = photo; }
    }

    public static final class Album {
        public final String title, browseId, thumbnail, year, kind;
        public Album(String title, String browseId, String thumbnail, String year, String kind) {
            this.title = title; this.browseId = browseId; this.thumbnail = thumbnail; this.year = year; this.kind = kind;
        }
    }

    public static final class ArtistPage {
        public String name = "", photo = "", subscribers = "", songsPlaylist = "";
        public final List<SearchResult> topSongs = new ArrayList<>();
        public final List<Album> albums = new ArrayList<>(), singles = new ArrayList<>();
    }

    public static final class AlbumPage {
        public String title = "", artist = "", thumbnail = "", year = "";
        public final List<SearchResult> tracks = new ArrayList<>();
    }

    /** Punto de entrada de red; las pruebas lo sustituyen por respuestas guardadas. */
    public interface Transport { JSONObject post(String endpoint, JSONObject body) throws Exception; }

    static Transport transport = YtMusicApi::networkPost;

    static JSONObject post(String endpoint, JSONObject body) throws Exception { return transport.post(endpoint, body); }

    private static JSONObject networkPost(String endpoint, JSONObject body) throws Exception {
        body.put("context", new JSONObject().put("client", new JSONObject().put("clientName", "WEB_REMIX")
                .put("clientVersion", VERSION).put("hl", "es").put("gl", "CL")));
        HttpURLConnection connection = (HttpURLConnection) new URL(BASE + endpoint + "?prettyPrint=false").openConnection();
        try {
            connection.setConnectTimeout(12000);
            connection.setReadTimeout(18000);
            connection.setDoOutput(true);
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Origin", "https://music.youtube.com");
            connection.setRequestProperty("Referer", "https://music.youtube.com/");
            connection.setRequestProperty("X-YouTube-Client-Name", "67");
            connection.setRequestProperty("X-YouTube-Client-Version", VERSION);
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36");
            try (OutputStream out = connection.getOutputStream()) { out.write(body.toString().getBytes(StandardCharsets.UTF_8)); }
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) throw new java.io.IOException("YouTube Music HTTP " + status);
            StringBuilder text = new StringBuilder();
            try (BufferedReader in = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) text.append(line);
            }
            return new JSONObject(text.toString());
        } finally {
            connection.disconnect();
        }
    }

    // --- Recorrido del JSON: la respuesta cambia de anidación según la pantalla, así que se busca por nombre ---

    private static void collect(Object node, String key, List<Object> out) {
        if (node instanceof JSONObject) {
            JSONObject object = (JSONObject) node;
            JSONArray names = object.names();
            if (names == null) return;
            for (int i = 0; i < names.length(); i++) {
                String name = names.optString(i);
                Object value = object.opt(name);
                if (key.equals(name)) out.add(value);
                collect(value, key, out);
            }
        } else if (node instanceof JSONArray) {
            JSONArray array = (JSONArray) node;
            for (int i = 0; i < array.length(); i++) collect(array.opt(i), key, out);
        }
    }

    private static List<JSONObject> all(Object node, String key) {
        List<Object> found = new ArrayList<>();
        collect(node, key, found);
        List<JSONObject> objects = new ArrayList<>();
        for (Object value : found) if (value instanceof JSONObject) objects.add((JSONObject) value);
        return objects;
    }

    private static Object firstValue(Object node, String key) {
        List<Object> found = new ArrayList<>();
        collect(node, key, found);
        return found.isEmpty() ? null : found.get(0);
    }

    private static String firstString(Object node, String key) {
        List<Object> found = new ArrayList<>();
        collect(node, key, found);
        for (Object value : found) if (value instanceof String && !((String) value).isEmpty()) return (String) value;
        return "";
    }

    private static JSONArray runs(JSONObject textNode) {
        JSONArray runs = textNode == null ? null : textNode.optJSONArray("runs");
        return runs == null ? new JSONArray() : runs;
    }

    private static String text(JSONObject textNode) {
        StringBuilder out = new StringBuilder();
        JSONArray runs = runs(textNode);
        for (int i = 0; i < runs.length(); i++) out.append(runs.optJSONObject(i) == null ? "" : runs.optJSONObject(i).optString("text"));
        return out.toString();
    }

    /** Las partes de "Artista • Álbum • 3:45"; cada una es la lista de sus tramos (que pueden traer un enlace). */
    private static List<List<JSONObject>> segments(JSONObject textNode) {
        List<List<JSONObject>> parts = new ArrayList<>();
        List<JSONObject> current = new ArrayList<>();
        JSONArray runs = runs(textNode);
        for (int i = 0; i < runs.length(); i++) {
            JSONObject run = runs.optJSONObject(i);
            if (run == null) continue;
            if (SEPARATOR.equals(run.optString("text").trim())) { parts.add(current); current = new ArrayList<>(); }
            else current.add(run);
        }
        if (!current.isEmpty()) parts.add(current);
        return parts;
    }

    private static String join(List<JSONObject> part) {
        StringBuilder out = new StringBuilder();
        for (JSONObject run : part) out.append(run.optString("text"));
        return out.toString().trim();
    }

    private static String bestThumbnail(Object node) {
        Object thumbs = firstValue(node, "thumbnails");
        if (thumbs instanceof JSONArray && ((JSONArray) thumbs).length() > 0) {
            JSONArray array = (JSONArray) thumbs;
            return array.optJSONObject(array.length() - 1).optString("url");
        }
        return "";
    }

    /** Pide a YouTube la foto cuadrada del tamaño justo (las de artista vienen como banner de 1200x500). */
    static String square(String url, int size) {
        return url == null ? "" : url.replaceFirst("=w\\d+-h\\d+", "=w" + size + "-h" + size);
    }

    private static long seconds(String value) {
        if (value == null || !DURATION.matcher(value.trim()).matches()) return 0;
        long total = 0;
        for (String part : value.trim().split(":")) total = total * 60 + Long.parseLong(part);
        return total;
    }

    /** "song" (pista de audio oficial), "video" (clip oficial o subido por alguien) o vacío si no se sabe. */
    private static String kindOf(Object node) {
        String type = firstString(node, "musicVideoType");
        if ("MUSIC_VIDEO_TYPE_ATV".equals(type)) return "song";
        return "MUSIC_VIDEO_TYPE_OMV".equals(type) || "MUSIC_VIDEO_TYPE_UGC".equals(type) ? "video" : "";
    }

    private static boolean isSong(Object node) { return !"video".equals(kindOf(node)); }

    // --- Filas de canciones ---

    /** Una fila de lista (búsqueda, página del artista, álbum o playlist). Null si no es una canción de audio. */
    private static SearchResult trackFromRow(JSONObject row, String fallbackArtist, String fallbackThumb) {
        if (row == null || !isSong(row)) return null;
        String id = firstString(row.optJSONObject("playlistItemData"), "videoId");
        if (id.isEmpty()) id = firstString(row, "videoId");
        JSONArray columns = row.optJSONArray("flexColumns");
        if (id.isEmpty() || columns == null || columns.length() == 0) return null;
        String title = text(flexText(columns.optJSONObject(0)));
        if (title.isEmpty()) return null;
        String artist = "";
        long duration = 0;
        for (int i = 1; i < columns.length(); i++) {
            for (List<JSONObject> part : segments(flexText(columns.optJSONObject(i)))) {
                String text = join(part);
                String browse = part.isEmpty() ? "" : firstString(part.get(0), "browseId");
                if (text.isEmpty()) continue;
                if (DURATION.matcher(text).matches()) { if (duration == 0) duration = seconds(text); }
                else if (browse.startsWith("MPRE")) { /* álbum */ }
                else if (browse.startsWith("UC")) artist = artist.isEmpty() ? text : artist + ", " + text;
                else if (i == 1 && artist.isEmpty() && !text.matches(".*(reproducciones|views|plays)$")) artist = text;
            }
        }
        JSONArray fixed = row.optJSONArray("fixedColumns");
        for (int i = 0; fixed != null && i < fixed.length() && duration == 0; i++) {
            JSONObject column = fixed.optJSONObject(i);
            JSONObject renderer = column == null ? null : column.optJSONObject("musicResponsiveListItemFixedColumnRenderer");
            duration = seconds(text(renderer == null ? null : renderer.optJSONObject("text")));
        }
        String thumb = bestThumbnail(row.optJSONObject("thumbnail"));
        return new SearchResult(id, title, artist.isEmpty() ? fallbackArtist : artist, duration,
                thumb.isEmpty() ? fallbackThumb : thumb, "https://www.youtube.com/watch?v=" + id);
    }

    private static JSONObject flexText(JSONObject column) {
        JSONObject renderer = column == null ? null : column.optJSONObject("musicResponsiveListItemFlexColumnRenderer");
        return renderer == null ? null : renderer.optJSONObject("text");
    }

    // --- Artistas ---

    public static List<Artist> searchArtists(String query) throws Exception {
        JSONObject data = post("search", new JSONObject().put("query", query).put("params", ARTISTS));
        List<Artist> artists = new ArrayList<>();
        for (JSONObject row : all(data, "musicResponsiveListItemRenderer")) {
            String id = firstString(row.optJSONObject("navigationEndpoint"), "browseId");
            JSONArray columns = row.optJSONArray("flexColumns");
            if (!id.startsWith("UC") || columns == null || columns.length() == 0) continue;
            String name = text(flexText(columns.optJSONObject(0)));
            if (!name.isEmpty()) artists.add(new Artist(name, id, square(bestThumbnail(row), 226)));
        }
        return artists;
    }

    private static String normalize(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
    }

    /** El artista con ese nombre exacto (ignorando mayúsculas y signos); si no, el primer resultado. */
    public static Artist findArtist(String name) throws Exception {
        List<Artist> artists = searchArtists(name);
        String wanted = normalize(name);
        for (Artist artist : artists) if (normalize(artist.name).equals(wanted)) return artist;
        return artists.isEmpty() ? null : artists.get(0);
    }

    /** Foto, canciones más escuchadas y discografía de un artista. */
    public static ArtistPage artistPage(String id) throws Exception {
        JSONObject data = post("browse", new JSONObject().put("browseId", id));
        ArtistPage page = new ArtistPage();
        List<JSONObject> headers = all(data, "musicImmersiveHeaderRenderer");
        if (headers.isEmpty()) headers = all(data, "musicVisualHeaderRenderer");
        if (!headers.isEmpty()) {
            JSONObject header = headers.get(0);
            page.name = text(header.optJSONObject("title"));
            page.photo = square(bestThumbnail(header.optJSONObject("thumbnail")), 544);
            List<JSONObject> counts = all(header, "subscriberCountText");
            if (!counts.isEmpty()) page.subscribers = text(counts.get(0));
        }
        for (JSONObject section : all(data, "sectionListRenderer")) {
            JSONArray blocks = section.optJSONArray("contents");
            for (int b = 0; blocks != null && b < blocks.length(); b++) {
                JSONObject block = blocks.optJSONObject(b);
                if (block == null) continue;
                JSONObject shelf = block.optJSONObject("musicShelfRenderer");
                if (shelf != null && page.topSongs.isEmpty()) readTopSongs(shelf, page);
                JSONObject carousel = block.optJSONObject("musicCarouselShelfRenderer");
                if (carousel != null) readCarousel(carousel, page);
            }
        }
        return page;
    }

    private static void readTopSongs(JSONObject shelf, ArtistPage page) {
        page.songsPlaylist = firstString(shelf.optJSONObject("bottomEndpoint"), "browseId");
        JSONArray items = shelf.optJSONArray("contents");
        for (int i = 0; items != null && i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            SearchResult song = trackFromRow(item == null ? null : item.optJSONObject("musicResponsiveListItemRenderer"), page.name, "");
            if (song != null) page.topSongs.add(song);
        }
    }

    private static void readCarousel(JSONObject carousel, ArtistPage page) {
        JSONObject header = carousel.optJSONObject("header");
        JSONObject basic = header == null ? null : header.optJSONObject("musicCarouselShelfBasicHeaderRenderer");
        String heading = text(basic == null ? null : basic.optJSONObject("title")).toLowerCase(Locale.ROOT);
        String kind = heading.contains("sencill") || heading.contains("single") || heading.matches(".*\\bep\\b.*") ? "single"
                : heading.contains("álbum") || heading.contains("album") ? "album" : "";
        JSONArray items = carousel.optJSONArray("contents");
        if (kind.isEmpty() || items == null) return;
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            JSONObject row = item == null ? null : item.optJSONObject("musicTwoRowItemRenderer");
            if (row == null) continue;
            String browse = firstString(row.optJSONObject("navigationEndpoint"), "browseId");
            if (!browse.startsWith("MPRE")) continue;
            String year = "";
            JSONArray subtitle = runs(row.optJSONObject("subtitle"));
            for (int r = 0; r < subtitle.length(); r++) {
                String piece = subtitle.optJSONObject(r).optString("text").trim();
                if (YEAR.matcher(piece).matches()) { year = piece; break; }
            }
            Album album = new Album(text(row.optJSONObject("title")), browse, bestThumbnail(row), year, kind);
            ("single".equals(kind) ? page.singles : page.albums).add(album);
        }
    }

    /** Todas las canciones de una lista (p. ej. "todas las canciones" de un artista), con paginación. */
    public static List<SearchResult> playlist(String id, String artist) throws Exception {
        JSONObject data = post("browse", new JSONObject().put("browseId", id));
        List<SearchResult> songs = new ArrayList<>();
        Set<String> seen = new HashSet<>(), tokens = new HashSet<>();
        JSONArray items = null;
        List<JSONObject> shelves = all(data, "musicPlaylistShelfRenderer");
        if (!shelves.isEmpty()) items = shelves.get(0).optJSONArray("contents");
        while (items != null) {
            String token = "";
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i);
                if (item == null) continue;
                SearchResult song = trackFromRow(item.optJSONObject("musicResponsiveListItemRenderer"), artist, "");
                if (song != null && seen.add(song.getId())) songs.add(song);
                if (token.isEmpty()) token = firstString(item.optJSONObject("continuationItemRenderer"), "token");
            }
            if (token.isEmpty() || !tokens.add(token) || songs.size() >= MAX_PLAYLIST) break;
            Object next = firstValue(post("browse", new JSONObject().put("continuation", token)), "continuationItems");
            items = next instanceof JSONArray ? (JSONArray) next : null;
        }
        return songs.size() > MAX_PLAYLIST ? new ArrayList<>(songs.subList(0, MAX_PLAYLIST)) : songs;
    }

    /** Radio de YouTube Music: solo pistas de audio (ATV), nunca videoclips OMV ni videos UGC, sin la propia semilla. */
    public static List<SearchResult> related(String videoId, int limit) throws Exception {
        if (limit <= 0) return new ArrayList<>();
        JSONObject data = post("next", new JSONObject().put("videoId", videoId).put("playlistId", "RDAMVM" + videoId));
        List<SearchResult> songs = radioTracks(data, videoId, "", limit);
        if (!songs.isEmpty()) return songs;
        // A music video can produce an OMV-only radio. Repeating that request cannot
        // recover audio tracks: find the same recording in Music's songs catalogue.
        for (JSONObject seed : all(data, "playlistPanelVideoRenderer")) {
            if (!videoId.equals(seed.optString("videoId"))) continue;
            String title = text(seed.optJSONObject("title"));
            List<List<JSONObject>> parts = segments(seed.optJSONObject("longBylineText"));
            String artist = parts.isEmpty() ? "" : join(parts.get(0));
            if (title.isEmpty() || artist.isEmpty()) break;
            JSONObject search = post("search", new JSONObject().put("query", artist + " " + title)
                    .put("params", "EgWKAQIIAWoKEAkQBRAKEAMQBA%3D%3D"));
            for (JSONObject row : all(search, "musicResponsiveListItemRenderer")) {
                SearchResult candidate = trackFromRow(row, "", "");
                if (candidate == null || candidate.getId().equals(videoId)
                        || !sameRecording(title, artist, candidate)) continue;
                String audioId = candidate.getId();
                JSONObject radio = post("next", new JSONObject().put("videoId", audioId).put("playlistId", "RDAMVM" + audioId));
                songs = radioTracks(radio, videoId, audioId, limit);
                android.util.Log.i("MusicBoxAutoplay", "Audio radio fallback: " + videoId + " -> " + audioId + ", tracks=" + songs.size());
                return songs; // Bounded: one search and one alternate radio, never a query per result.
            }
            break;
        }
        android.util.Log.w("MusicBoxAutoplay", "No audio radio tracks for " + videoId);
        return songs;
    }

    private static boolean sameRecording(String title, String artist, SearchResult candidate) {
        String wantedTitle = normalize(title), actualTitle = normalize(candidate.getTitle());
        String wantedArtist = normalize(artist), actualArtist = normalize(candidate.getChannel());
        return !actualTitle.isEmpty() && !actualArtist.isEmpty()
                && (wantedTitle.equals(actualTitle) || (" " + wantedTitle + " ").contains(" " + actualTitle + " "))
                && (wantedArtist.equals(actualArtist) || (" " + wantedArtist + " ").contains(" " + actualArtist + " "));
    }

    private static List<SearchResult> radioTracks(JSONObject data, String videoId, String audioId, int limit) {
        List<SearchResult> songs = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (JSONObject item : all(data, "playlistPanelVideoRenderer")) {
            String id = item.optString("videoId");
            if (id.isEmpty() || id.equals(videoId) || id.equals(audioId) || !isSong(item) || !seen.add(id)) continue;
            List<List<JSONObject>> parts = segments(item.optJSONObject("longBylineText"));
            String artist = parts.isEmpty() ? "" : join(parts.get(0));
            songs.add(new SearchResult(id, text(item.optJSONObject("title")), artist, seconds(text(item.optJSONObject("lengthText"))),
                    bestThumbnail(item.optJSONObject("thumbnail")), "https://www.youtube.com/watch?v=" + id));
            if (songs.size() >= limit) break;
        }
        return songs;
    }

    /** Título, año, portada y canciones de un álbum. */
    public static AlbumPage albumPage(String id) throws Exception {
        JSONObject data = post("browse", new JSONObject().put("browseId", id));
        AlbumPage page = new AlbumPage();
        List<JSONObject> headers = all(data, "musicResponsiveHeaderRenderer");
        if (headers.isEmpty()) headers = all(data, "musicDetailHeaderRenderer");
        if (!headers.isEmpty()) {
            JSONObject header = headers.get(0);
            page.title = text(header.optJSONObject("title"));
            page.thumbnail = bestThumbnail(header.optJSONObject("thumbnail"));
            List<String> subtitle = new ArrayList<>();
            JSONArray runs = runs(header.optJSONObject("subtitle"));
            for (int i = 0; i < runs.length(); i++) {
                String piece = runs.optJSONObject(i).optString("text").trim();
                if (!piece.isEmpty() && !SEPARATOR.equals(piece)) subtitle.add(piece);
            }
            for (String piece : subtitle) if (YEAR.matcher(piece).matches()) { page.year = piece; break; }
            List<JSONObject> strap = all(header, "straplineTextOne");
            page.artist = !strap.isEmpty() ? text(strap.get(0)) : subtitle.size() > 1 && !YEAR.matcher(subtitle.get(1)).matches() ? subtitle.get(1) : "";
        }
        for (JSONObject shelf : all(data, "musicShelfRenderer")) {
            JSONArray items = shelf.optJSONArray("contents");
            for (int i = 0; items != null && i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i);
                SearchResult song = trackFromRow(item == null ? null : item.optJSONObject("musicResponsiveListItemRenderer"), page.artist, page.thumbnail);
                if (song != null) page.tracks.add(song);
            }
        }
        return page;
    }
}
