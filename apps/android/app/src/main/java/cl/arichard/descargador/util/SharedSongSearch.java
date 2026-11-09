package cl.arichard.descargador.util;

import android.content.Context;
import android.content.Intent;
import cl.arichard.descargador.MainActivity;
import java.net.URI;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Convierte lo compartido por Shazam en una búsqueda, nunca en una descarga. */
public final class SharedSongSearch {
    private SharedSongSearch() {}
    public static final String EXTRA_SEARCH = "musicbox.sharedSongSearch";
    private static final Pattern URL = Pattern.compile("https?://[^\\s<>]+", Pattern.CASE_INSENSITIVE);

    /** null: otro tipo de contenido; vacío: Shazam sin título disponible. */
    public static String query(CharSequence shared, CharSequence subject) {
        String text = shared == null ? "" : shared.toString();
        Matcher urls = URL.matcher(text);
        String slug = null;
        while (urls.find()) {
            try {
                URI uri = new URI(urls.group().replaceAll("[).,]+$", ""));
                String host = uri.getHost();
                if (host == null || !(host.equalsIgnoreCase("shazam.com") || host.toLowerCase(java.util.Locale.ROOT).endsWith(".shazam.com"))) continue;
                String path = uri.getPath();
                if (path == null || !path.matches("/(?:[a-zA-Z-]+/)?track/[0-9]+(?:/.*)?")) continue;
                String[] parts = path.split("/track/[0-9]+/?", 2);
                slug = parts.length > 1 ? parts[1].replace('-', ' ').replace('/', ' ').trim() : "";
                break;
            } catch (Exception ignored) { }
        }
        if (slug == null) return null;
        String title = clean(URL.matcher(text).replaceAll(" "));
        if (title.isEmpty()) title = clean(subject == null ? "" : subject.toString());
        return title.isEmpty() ? slug : title;
    }

    private static String clean(String text) {
        return text.replaceFirst("(?iu)^\\s*I (?:just )?used Shazam to discover\\s+", "")
                .replaceFirst("(?iu)^\\s*(?:He usado|Usé|Acabo de usar) Shazam para (?:descubrir|identificar)\\s+", "")
                .replaceAll("(?iu)\\s+(?:on|en) Shazam[.!]?\\s*$", "")
                .replaceAll("(?iu)\\s+by\\s+", " ")
                .replaceAll("\\s+", " ").trim();
    }

    public static Intent searchIntent(Context context, Intent original, CharSequence shared) {
        String query = query(shared, original.getCharSequenceExtra(Intent.EXTRA_SUBJECT));
        if (query == null) return null;
        return searchTarget(context).setAction(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, query).putExtra(EXTRA_SEARCH, true)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
    }

    /** Abre directamente la búsqueda para enlaces musicales ya identificados. */
    public static Intent searchIntent(Context context, String query) {
        return searchTarget(context).setAction(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, query == null ? "" : query).putExtra(EXTRA_SEARCH, true)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
    }

    private static Intent searchTarget(Context context) {
        if (context.getPackageName().endsWith(".verification"))
            return new Intent().setClassName(context, "cl.arichard.descargador.PreviewMainActivity");
        return new Intent(context, MainActivity.class);
    }
}
