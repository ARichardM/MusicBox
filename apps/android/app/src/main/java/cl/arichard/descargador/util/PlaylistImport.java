package cl.arichard.descargador.util;

import cl.arichard.descargador.VideoSearchSheet;
import cl.arichard.descargador.data.PlaylistManager;
import cl.arichard.descargador.importer.SongMatcher;
import cl.arichard.descargador.importer.TextImport;
import cl.arichard.descargador.model.SearchResult;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Importa una playlist completa desde un enlace público de Spotify, YouTube / YouTube Music o Deezer.
 * Las canciones de YouTube ya traen su video; las de Spotify y Deezer se buscan en YouTube y solo se
 * agregan cuando la coincidencia es segura, para no meter versiones equivocadas.
 */
public final class PlaylistImport {
    private PlaylistImport() {}

    public enum Platform {
        SPOTIFY("Spotify"), YOUTUBE("YouTube"), DEEZER("Deezer"), APPLE_MUSIC("Apple Music"), SOUNDCLOUD("SoundCloud");
        public final String label;
        Platform(String label) { this.label = label; }
    }

    /** Un enlace de playlist reconocido. */
    public static final class Link {
        public final Platform platform;
        public final String id, url;
        Link(Platform platform, String id, String url) { this.platform = platform; this.id = id; this.url = url; }
    }

    /** Una canción de la playlist de origen. [direct] viene con video de YouTube; si es null hay que buscarla. */
    public static final class Track {
        public final String title, artist;
        public final long seconds;
        public final SearchResult direct;
        public Track(String title, String artist, long seconds, SearchResult direct) {
            this.title = title == null ? "" : title.trim();
            this.artist = artist == null ? "" : artist.trim();
            this.seconds = Math.max(0, seconds);
            this.direct = direct;
        }
    }

    public static final class Playlist {
        public final Platform platform;
        public final String name;
        public final List<Track> tracks;
        public Playlist(Platform platform, String name, List<Track> tracks) {
            this.platform = platform; this.name = name == null || name.trim().isEmpty() ? platform.label + " · playlist" : name.trim();
            this.tracks = tracks;
        }
    }

    public static final class Outcome {
        public final String playlist;
        public final int added, skipped;
        Outcome(String playlist, int added, int skipped) { this.playlist = playlist; this.added = added; this.skipped = skipped; }
    }

    public interface Progress { void onProgress(int done, int total); }

    private static final Pattern SPOTIFY = Pattern.compile(
            "https?://open\\.spotify\\.com/(?:intl-[a-z]{2}/)?playlist/([A-Za-z0-9]{10,40})");
    private static final Pattern YOUTUBE = Pattern.compile(
            "https?://(?:www\\.|m\\.|music\\.)?youtube\\.com/playlist\\?(?:[^\\s]*&)?list=([A-Za-z0-9_-]{10,80})");
    private static final Pattern DEEZER = Pattern.compile(
            "https?://(?:www\\.)?deezer\\.com/(?:[a-z]{2}/)?playlist/(\\d{1,15})");
    private static final Pattern APPLE = Pattern.compile(
            "https?://music\\.apple\\.com/(?:[a-z]{2}/)?playlist/[^\\s/?#]+/pl\\.[A-Za-z0-9]+");
    private static final Pattern SOUNDCLOUD = Pattern.compile(
            "https?://(?:www\\.|m\\.)?soundcloud\\.com/[A-Za-z0-9_.-]+/sets/[A-Za-z0-9_.-]+(?:/s-[A-Za-z0-9]+)?");
    private static final Pattern NEXT_DATA = Pattern.compile(
            "<script id=\"__NEXT_DATA__\" type=\"application/json\">(.*?)</script>", Pattern.DOTALL);
    private static final Pattern APPLE_DATA = Pattern.compile(
            "<script[^>]*id=\"serialized-server-data\"[^>]*>(.*?)</script>", Pattern.DOTALL);
    private static final Pattern OG_TITLE = Pattern.compile("<meta property=\"og:title\" content=\"([^\"]*)\"");
    private static final int MAX_DEEZER_PAGES = 40;
    private static final String USER_AGENT = "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";

    /** Primer enlace de playlist soportado dentro de un texto (un mensaje compartido, un portapapeles…), o null. */
    public static Link find(CharSequence text) {
        if (text == null) return null;
        String value = text.toString();
        Matcher m = SPOTIFY.matcher(value);
        if (m.find()) return new Link(Platform.SPOTIFY, m.group(1), m.group());
        m = YOUTUBE.matcher(value);
        if (m.find()) return new Link(Platform.YOUTUBE, m.group(1), m.group());
        m = DEEZER.matcher(value);
        if (m.find()) return new Link(Platform.DEEZER, m.group(1), m.group());
        m = APPLE.matcher(value);
        if (m.find()) return new Link(Platform.APPLE_MUSIC, m.group(), m.group());
        m = SOUNDCLOUD.matcher(value);
        if (m.find()) return new Link(Platform.SOUNDCLOUD, m.group(), m.group());
        return null;
    }

    /** Lee la playlist completa. Es bloqueante: llamar siempre fuera del hilo de interfaz. */
    public static Playlist fetch(Link link) throws Exception {
        switch (link.platform) {
            case SPOTIFY: return parseSpotifyEmbed(get("https://open.spotify.com/embed/playlist/" + link.id));
            case DEEZER: return fetchDeezer(link.id);
            case APPLE_MUSIC: return parseAppleMusic(get(link.url));
            case SOUNDCLOUD: return parseSoundCloud(get(link.url));
            case YOUTUBE:
            default: return fetchYouTube(link.id);
        }
    }

    /** Lee una playlist de YouTube con el cliente de YouTube Music: los videos llegan con su ID, sin buscar. */
    private static Playlist fetchYouTube(String id) throws Exception {
        List<SearchResult> songs = YtMusicApi.playlist("VL" + id, "");
        if (songs.isEmpty()) throw new IllegalStateException("La playlist de YouTube está vacía, es privada o no está disponible.");
        List<Track> tracks = new ArrayList<>();
        for (SearchResult song : songs) {
            tracks.add(new Track(song.getTitle(), song.getChannel(), song.getDurationSeconds(), song));
        }
        return new Playlist(Platform.YOUTUBE, youtubeTitle(id), tracks);
    }

    /** Lee el JSON que la página pública de embed de Spotify incluye en __NEXT_DATA__. */
    static Playlist parseSpotifyEmbed(String html) throws Exception {
        Matcher m = NEXT_DATA.matcher(html == null ? "" : html);
        if (!m.find()) throw new IllegalStateException("No se pudo leer la playlist de Spotify.");
        JSONObject entity = new JSONObject(m.group(1)).getJSONObject("props").getJSONObject("pageProps")
                .getJSONObject("state").getJSONObject("data").getJSONObject("entity");
        List<Track> tracks = new ArrayList<>();
        JSONArray list = entity.optJSONArray("trackList");
        if (list != null) for (int i = 0; i < list.length(); i++) {
            JSONObject item = list.optJSONObject(i);
            if (item == null || !"track".equals(item.optString("entityType", "track"))) continue;
            String title = item.optString("title", "");
            if (title.isEmpty()) continue;
            tracks.add(new Track(title, item.optString("subtitle", ""), item.optLong("duration", 0) / 1000, null));
        }
        if (tracks.isEmpty()) throw new IllegalStateException("La playlist de Spotify no tiene canciones visibles.");
        return new Playlist(Platform.SPOTIFY, entity.optString("name", ""), tracks);
    }

    /** Título de la playlist por oEmbed, el servicio público de YouTube; vacío si no responde. */
    private static String youtubeTitle(String id) {
        try {
            String url = java.net.URLEncoder.encode("https://www.youtube.com/playlist?list=" + id, "UTF-8");
            return new JSONObject(get("https://www.youtube.com/oembed?format=json&url=" + url)).optString("title", "");
        } catch (Exception ignored) { return ""; }
    }

    /** Lee los datos que la página pública de una playlist de Apple Music incluye en serialized-server-data. */
    static Playlist parseAppleMusic(String html) throws Exception {
        String page = html == null ? "" : html;
        Matcher m = APPLE_DATA.matcher(page);
        if (!m.find()) throw new IllegalStateException("No se pudo leer la playlist de Apple Music.");
        List<Track> tracks = new ArrayList<>();
        collectApple(new JSONObject("{\"root\":" + m.group(1) + "}").get("root"), tracks);
        if (tracks.isEmpty()) throw new IllegalStateException("La playlist de Apple Music no tiene canciones visibles.");
        Matcher title = OG_TITLE.matcher(page);
        String name = title.find() ? title.group(1).replace("‎", "").replaceAll("\\s+on Apple Music$", "") : "";
        return new Playlist(Platform.APPLE_MUSIC, name, tracks);
    }

    private static void collectApple(Object node, List<Track> out) {
        if (node instanceof JSONObject) {
            JSONObject object = (JSONObject) node;
            if (object.has("trackNumber") && object.has("artistName") && object.has("title")) {
                out.add(new Track(object.optString("title", ""), object.optString("artistName", ""), object.optLong("duration", 0) / 1000, null));
                return;
            }
            java.util.Iterator<String> keys = object.keys();
            while (keys.hasNext()) collectApple(object.opt(keys.next()), out);
        } else if (node instanceof JSONArray) {
            JSONArray array = (JSONArray) node;
            for (int i = 0; i < array.length(); i++) collectApple(array.opt(i), out);
        }
    }

    /** Lee las canciones que la página pública de un set de SoundCloud incluye en __NEXT_DATA__. */
    static Playlist parseSoundCloud(String html) throws Exception {
        Matcher m = NEXT_DATA.matcher(html == null ? "" : html);
        if (!m.find()) throw new IllegalStateException("No se pudo leer la playlist de SoundCloud.");
        List<Track> tracks = new ArrayList<>();
        String[] name = {""};
        collectSoundCloud(new JSONObject("{\"root\":" + m.group(1) + "}").get("root"), tracks, name);
        if (tracks.isEmpty()) throw new IllegalStateException("La playlist de SoundCloud no tiene canciones visibles.");
        return new Playlist(Platform.SOUNDCLOUD, name[0], tracks);
    }

    private static void collectSoundCloud(Object node, List<Track> out, String[] name) {
        if (node instanceof JSONObject) {
            JSONObject object = (JSONObject) node;
            String urn = object.optString("urn", "");
            if (urn.startsWith("soundcloud:playlists:") && name[0].isEmpty()) name[0] = object.optString("title", "");
            if (urn.startsWith("soundcloud:tracks:") && object.has("title") && object.has("duration")) {
                JSONObject meta = object.optJSONObject("publisher_metadata");
                out.add(new Track(object.optString("title", ""), meta == null ? "" : meta.optString("artist", ""),
                        object.optLong("duration", 0) / 1000, null));
                return;
            }
            java.util.Iterator<String> keys = object.keys();
            while (keys.hasNext()) collectSoundCloud(object.opt(keys.next()), out, name);
        } else if (node instanceof JSONArray) {
            JSONArray array = (JSONArray) node;
            for (int i = 0; i < array.length(); i++) collectSoundCloud(array.opt(i), out, name);
        }
    }

    private static Playlist fetchDeezer(String id) throws Exception {
        JSONObject playlist = new JSONObject(get("https://api.deezer.com/playlist/" + id));
        if (playlist.has("error")) throw new IllegalStateException("Deezer no encontró esta playlist.");
        List<Track> tracks = new ArrayList<>();
        JSONObject page = playlist.optJSONObject("tracks");
        for (int pages = 0; page != null && pages < MAX_DEEZER_PAGES; pages++) {
            addDeezerTracks(page.optJSONArray("data"), tracks);
            String next = page.optString("next", "");
            if (next.isEmpty()) break;
            page = new JSONObject(get(next));
        }
        if (tracks.isEmpty()) throw new IllegalStateException("La playlist de Deezer no tiene canciones.");
        return new Playlist(Platform.DEEZER, playlist.optString("title", ""), tracks);
    }

    static void addDeezerTracks(JSONArray data, List<Track> out) {
        if (data == null) return;
        for (int i = 0; i < data.length(); i++) {
            JSONObject item = data.optJSONObject(i);
            if (item == null) continue;
            String title = item.optString("title", "");
            if (title.isEmpty()) continue;
            JSONObject artist = item.optJSONObject("artist");
            out.add(new Track(title, artist == null ? "" : artist.optString("name", ""), item.optLong("duration", 0), null));
        }
    }

    /**
     * Busca cada canción que no viene con video y devuelve el video de la mejor coincidencia segura.
     * Null si no hay ninguna que sea segura: en ese caso la canción no se agrega.
     */
    static SearchResult match(Track track) throws Exception {
        String query = TextImport.songQuery(track.title, track.artist);
        List<SearchResult> usable = VideoSearchSheet.usable(YoutubeApi.search(query, 5));
        if (usable.isEmpty()) return null;
        List<SongMatcher.Candidate> candidates = new ArrayList<>();
        for (SearchResult r : usable) candidates.add(new SongMatcher.Candidate(r.getId(), r.getTitle(), r.getChannel(), r.getDurationSeconds()));
        String duration = track.seconds > 0 ? String.format(Locale.ROOT, "%d:%02d", track.seconds / 60, track.seconds % 60) : "";
        int best = SongMatcher.best(query, candidates, track.title, track.artist, duration);
        SongMatcher.Match score = SongMatcher.score(query, candidates.get(best), track.title, track.artist, duration);
        return score.autoSelect ? usable.get(best) : null;
    }

    /** Crea la playlist (o agrega a una existente con ese nombre) y devuelve cuántas canciones entraron. */
    public static Outcome importInto(PlaylistManager playlists, Playlist playlist, String name, Progress progress,
                                     java.util.function.BooleanSupplier cancelled) throws Exception {
        String target = name == null || name.trim().isEmpty() ? playlist.name : name.trim();
        if (!playlists.getPlaylistNames().contains(target)) playlists.createPlaylist(target);
        int added = 0, skipped = 0, total = playlist.tracks.size();
        for (int i = 0; i < total; i++) {
            if (cancelled != null && cancelled.getAsBoolean()) break;
            Track track = playlist.tracks.get(i);
            SearchResult video = track.direct;
            if (video == null) {
                try { video = match(track); } catch (Exception network) { video = null; }
            }
            if (video == null) skipped++;
            else if (playlists.addOnlineTrack(target, video)) added++;
            if (progress != null) progress.onProgress(i + 1, total);
        }
        return new Outcome(target, added, skipped);
    }

    static String get(String address) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        try {
            connection.setConnectTimeout(12000);
            connection.setReadTimeout(20000);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent", USER_AGENT);
            connection.setRequestProperty("Accept", "text/html,application/json;q=0.9,*/*;q=0.8");
            int code = connection.getResponseCode();
            if (code != 200) throw new IllegalStateException("El servicio respondió " + code + ".");
            try (InputStream in = connection.getInputStream()) {
                java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
                byte[] chunk = new byte[16 * 1024];
                int read;
                while ((read = in.read(chunk)) != -1) bytes.write(chunk, 0, read);
                return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
            }
        } finally {
            connection.disconnect();
        }
    }
}
