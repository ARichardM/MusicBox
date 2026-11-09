package cl.arichard.descargador.util;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import android.util.Log;
import org.json.JSONObject;

/** Clasifica enlaces musicales compartidos y obtiene nombres para abrir la búsqueda adecuada. */
public final class SharedMusicLink {
    public enum Kind { PLAYLIST, TRACK, ARTIST, UNKNOWN }
    private static final Pattern URL = Pattern.compile("https?://[^\\s<>]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern OG_TITLE = Pattern.compile("<meta[^>]+property=[\\\"']og:title[\\\"'][^>]+content=[\\\"']([^\\\"']+)", Pattern.CASE_INSENSITIVE);
    private SharedMusicLink() { }

    public static Kind kind(CharSequence shared) {
        if (shared == null) return Kind.UNKNOWN;
        if (PlaylistImport.find(shared) != null) return Kind.PLAYLIST;
        URI uri = firstUri(shared.toString());
        if (uri == null || uri.getHost() == null) return Kind.UNKNOWN;
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        String path = uri.getPath() == null ? "" : uri.getPath();
        if (isDomain(host, "spotify.com") || isDomain(host, "deezer.com") || isDomain(host, "music.apple.com")) {
            if (path.matches(".*/artist/[^/]+.*")) return Kind.ARTIST;
            if (path.matches(".*/(?:track|song|album)/[^/]+.*")) return Kind.TRACK;
        }
        if (isDomain(host, "youtube.com") || host.equals("youtu.be")) {
            if (path.startsWith("/@") || path.startsWith("/channel/") || path.startsWith("/c/") || path.startsWith("/user/")) return Kind.ARTIST;
            if (host.equals("youtu.be") || path.equals("/watch") || path.startsWith("/shorts/")) return Kind.TRACK;
        }
        if (isDomain(host, "soundcloud.com")) {
            String[] parts = path.replaceAll("^/+|/+$", "").split("/");
            if (parts.length == 1 && !parts[0].isEmpty()) return Kind.ARTIST;
            if (parts.length >= 2) return Kind.TRACK;
        }
        return Kind.UNKNOWN;
    }

    public static boolean requiresTrackSearch(CharSequence shared) {
        if (kind(shared) != Kind.TRACK) return false;
        URI uri = firstUri(shared.toString());
        if (uri == null || uri.getHost() == null) return false;
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        return isDomain(host, "spotify.com") || isDomain(host, "deezer.com")
                || isDomain(host, "music.apple.com");
    }

    public static String trackQuery(CharSequence shared, CharSequence subject) {
        String title = readableTrackText(shared);
        if (!title.isEmpty()) return title;
        return readableTrackText(subject);
    }

    public static String resolveTrackQuery(CharSequence shared, CharSequence subject) {
        String immediate = trackQuery(shared, subject);
        if (!immediate.isEmpty()) return immediate;
        URI uri = firstUri(shared == null ? "" : shared.toString());
        if (uri == null || uri.getHost() == null) return "";
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        String address;
        if (isDomain(host, "spotify.com"))
            address = "https://open.spotify.com/oembed?url=" + android.net.Uri.encode(uri.toString());
        else if (isDomain(host, "deezer.com")) {
            Matcher id = Pattern.compile("/track/(\\d+)").matcher(uri.getPath());
            address = id.find() ? "https://api.deezer.com/track/" + id.group(1) : "";
        } else if (isDomain(host, "music.apple.com")) {
            address = uri.toString();
        } else {
            return "";
        }
        if (address.isEmpty()) return "";
        try {
            String body = get(address);
            if (isDomain(host, "deezer.com")) {
                JSONObject track = new JSONObject(body);
                String title = track.optString("title", "").trim();
                String artist = track.optJSONObject("artist") == null ? ""
                        : track.optJSONObject("artist").optString("name", "").trim();
                return join(title, artist);
            }
            if (isDomain(host, "spotify.com")) {
                JSONObject track = new JSONObject(body);
                String title = track.optString("title", "").trim();
                String artist = track.optString("author_name", "").trim();
                if (artist.equalsIgnoreCase("Spotify")) artist = "";
                return join(title, artist);
            }
            Matcher title = OG_TITLE.matcher(body);
            if (title.find()) return title.group(1).replaceAll("(?iu)\\s+(?:on Apple Music|YouTube)$", "").trim();
        } catch (Exception error) {
            Log.w("SharedMusicLink", "Could not resolve shared track title", error);
        }
        return "";
    }

    /** Primero usa el texto que acompaña al enlace; si no existe consulta metadatos públicos. */
    public static String artistQuery(CharSequence shared, CharSequence subject) {
        String text = shared == null ? "" : shared.toString();
        String withoutUrl = URL.matcher(text).replaceAll(" ").replaceAll("(?iu)\\b(?:escucha|listen to|open)\\b", " ")
                .replaceAll("(?iu)\\b(?:en|on)\\s+(?:spotify|youtube music|youtube|deezer|apple music|soundcloud)\\b.*$", " ")
                .replaceAll("\\s+", " ").trim();
        if (!withoutUrl.isEmpty()) return withoutUrl;
        if (subject != null && !subject.toString().trim().isEmpty()) return subject.toString().trim();
        return fallbackSlug(text);
    }

    public static String resolveArtistQuery(CharSequence shared, CharSequence subject) {
        String immediate = artistQuery(shared, subject);
        URI uri = firstUri(shared == null ? "" : shared.toString());
        if (uri == null) return immediate;
        // Un slug legible ya es mejor y más rápido que depender de la red.
        if (!immediate.isEmpty() && !immediate.matches("[A-Za-z0-9]{18,}")) return immediate;
        try {
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            String address;
            if (isDomain(host, "spotify.com"))
                address = "https://open.spotify.com/oembed?url=" + URLEncoder.encode(uri.toString(), "UTF-8");
            else if (isDomain(host, "soundcloud.com"))
                address = "https://soundcloud.com/oembed?format=json&url=" + URLEncoder.encode(uri.toString(), "UTF-8");
            else if (isDomain(host, "deezer.com")) {
                Matcher id = Pattern.compile("/artist/(\\d+)").matcher(uri.getPath());
                address = id.find() ? "https://api.deezer.com/artist/" + id.group(1) : "";
            } else address = uri.toString();
            if (!address.isEmpty()) {
                String body = get(address);
                if (address.contains("spotify.com/oembed") || address.contains("soundcloud.com/oembed")) {
                    String title = new JSONObject(body).optString("title", "").replaceAll("(?iu)\\s+(?:on Spotify|on SoundCloud)$", "").trim();
                    if (!title.isEmpty()) return title;
                } else if (address.contains("api.deezer.com")) {
                    String name = new JSONObject(body).optString("name", "").trim();
                    if (!name.isEmpty()) return name;
                } else {
                    Matcher title = OG_TITLE.matcher(body);
                    if (title.find()) return title.group(1).replaceAll("(?iu)\\s+(?:on Apple Music|YouTube)$", "").trim();
                }
            }
        } catch (Exception ignored) { }
        return immediate;
    }

    private static String readableTrackText(CharSequence text) {
        if (text == null) return "";
        String value = URL.matcher(text.toString()).replaceAll(" ")
                .replaceAll("(?iu)\\b(?:escucha|listen to|open)\\b", " ")
                .replaceAll("(?iu)\\b(?:en|on)\\s+(?:spotify|youtube music|youtube|deezer|apple music|soundcloud)\\b.*$", " ")
                .replaceAll("\\s+", " ").trim();
        if (value.matches("(?iu)(?:spotify|youtube music|youtube|deezer|apple music|soundcloud)")) return "";
        return isOpaque(value) ? "" : value;
    }

    private static boolean isOpaque(String value) {
        return value.matches("\\d+|[A-Za-z0-9]{18,}");
    }

    private static String join(String first, String second) {
        if (first.isEmpty()) return second;
        if (second.isEmpty() || first.equalsIgnoreCase(second)) return first;
        return first + " " + second;
    }

    private static boolean isDomain(String host, String domain) {
        return host.equals(domain) || host.endsWith("." + domain);
    }

    private static String fallbackSlug(String text) {
        URI uri = firstUri(text);
        if (uri == null) return "";
        String[] parts = (uri.getPath() == null ? "" : uri.getPath()).replaceAll("^/+|/+$", "").split("/");
        if (parts.length == 0) return "";
        String value = parts[parts.length - 1];
        if (value.matches("\\d+") || value.matches("[A-Za-z0-9]{18,}")) return value;
        return value.replace('-', ' ').replace('_', ' ').replaceFirst("^@", "").trim();
    }

    private static URI firstUri(String text) {
        Matcher matcher = URL.matcher(text);
        if (!matcher.find()) return null;
        try { return new URI(matcher.group().replaceAll("[).,]+$", "")); }
        catch (Exception ignored) { return null; }
    }

    private static String get(String address) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        try {
            connection.setConnectTimeout(8000); connection.setReadTimeout(10000);
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 MusicBox/Android");
            try (InputStream in = connection.getInputStream()) {
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                byte[] buffer = new byte[8192]; int read;
                while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
                return new String(out.toByteArray(), StandardCharsets.UTF_8);
            }
        } finally { connection.disconnect(); }
    }
}
