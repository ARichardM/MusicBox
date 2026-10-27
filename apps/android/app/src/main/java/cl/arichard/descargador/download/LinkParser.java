package cl.arichard.descargador.download;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Lectura y validación de los enlaces pegados. Equivale a parse_urls, validate_items y detect_playlist_mode de escritorio. */
public final class LinkParser {
    private static final Set<String> PLAYLIST_HOSTS = new HashSet<>(Arrays.asList(
            "youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com"));
    private static final Pattern ITEMS = Pattern.compile("[1-9]\\d*(?:-[1-9]\\d*)?(?:,[1-9]\\d*(?:-[1-9]\\d*)?)*");

    private LinkParser() {}

    /** Error de entrada con un mensaje en español, que la interfaz traduce con Tr.t. */
    public static final class LinkError extends IllegalArgumentException {
        private final String url;
        public LinkError(String message, String url) { super(message); this.url = url; }
        /** El enlace que falló, o una cadena vacía si el error no es de un enlace concreto. */
        public String getUrl() { return url; }
    }

    /** Líneas no vacías, sin espacios alrededor y sin repetir, en el orden en que se pegaron. */
    public static List<String> uniqueLines(String text) {
        Set<String> lines = new LinkedHashSet<>();
        if (text != null) for (String line : text.split("\\r?\\n")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) lines.add(trimmed);
        }
        return new ArrayList<>(lines);
    }

    private static URI parse(String url) {
        try { return new URI(url); } catch (Exception e) { return null; }
    }

    private static String host(URI uri) {
        String host = uri == null ? null : uri.getHost();
        return host == null ? null : host.toLowerCase(Locale.ROOT);
    }

    public static List<String> parseUrls(String text, boolean playlist) {
        List<String> urls = uniqueLines(text);
        if (urls.isEmpty()) throw new LinkError("Pega al menos un enlace, uno por línea.", "");
        for (String url : urls) {
            URI uri = parse(url);
            String scheme = uri == null || uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            String host = host(uri);
            if (!(scheme.equals("http") || scheme.equals("https")) || host == null || host.isEmpty())
                throw new LinkError("El enlace debe comenzar por https:// o http://", url);
            String bare = host.startsWith("www.") ? host.substring(4) : host;
            if (bare.startsWith("m.")) bare = bare.substring(2);
            if (!playlist && (bare.equals("youtube.com") || bare.equals("music.youtube.com"))
                    && stripTrailingSlashes(uri.getPath()).equals("/playlist"))
                throw new LinkError("Este enlace es una playlist. Selecciona «Playlist completa / rango».", url);
        }
        return urls;
    }

    private static String stripTrailingSlashes(String path) {
        String value = path == null ? "" : path;
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        return value;
    }

    private static boolean hasListParameter(String query) {
        if (query == null) return false;
        for (String pair : query.split("&")) {
            int equals = pair.indexOf('=');
            if (equals > 0 && pair.substring(0, equals).equals("list") && equals < pair.length() - 1) return true;
        }
        return false;
    }

    /** True si todos los enlaces son listas de reproducción explícitas de YouTube. */
    public static boolean detectPlaylistMode(List<String> lines) {
        if (lines == null || lines.isEmpty()) return false;
        for (String line : lines) {
            URI uri = parse(line);
            String host = host(uri);
            if (host == null || !PLAYLIST_HOSTS.contains(host)
                    || !stripTrailingSlashes(uri.getPath()).equals("/playlist")
                    || !hasListParameter(uri.getRawQuery())) return false;
        }
        return true;
    }

    /** Valida el rango de una playlist (1-10 o 1,3,5-8) y lo devuelve sin espacios. */
    public static String validateItems(String items) {
        String clean = items == null ? "" : items.replace(" ", "");
        if (!clean.isEmpty() && !ITEMS.matcher(clean).matches())
            throw new LinkError("El rango debe tener este formato: 1-10 o 1,3,5-8.", "");
        if (!clean.isEmpty()) for (String item : clean.split(",")) {
            int dash = item.indexOf('-');
            if (dash > 0 && Long.parseLong(item.substring(0, dash)) > Long.parseLong(item.substring(dash + 1)))
                throw new LinkError("El inicio del rango no puede ser mayor que el final.", "");
        }
        return clean;
    }
}
