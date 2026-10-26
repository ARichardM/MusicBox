package cl.arichard.descargador.util;

/**
 * Progreso real de una descarga como fracción de 0 a 1 que solo avanza.
 *
 * yt-dlp informa porcentaje únicamente mientras baja el archivo; antes (conexión y preparación) y después
 * (conversión a MP3, etiquetas y portada) no hay porcentaje. Para que el anillo avance de verdad desde el
 * principio, cada etapa ocupa un tramo y el porcentaje de la descarga rellena el tramo central.
 */
public final class DownloadProgress {
    static final float START = 0.03f;
    static final float PREPARED = 0.10f;
    static final float DOWNLOADED = 0.82f;
    static final float CONVERTED = 0.88f;
    static final float TAGGED = 0.93f;
    static final float COVER = 0.97f;
    static final float LIMIT = 0.99f;

    private float overall = START;

    public float current() {
        return overall;
    }

    /** Actualiza con lo último que informó yt-dlp y devuelve el progreso total. */
    public float update(float percent, String line) {
        String text = line == null ? "" : line;
        float candidate = overall;
        if (text.contains("[youtube]") || text.contains("[info]")) candidate = Math.max(candidate, PREPARED * 0.7f);
        if (percent > 0f) {
            float download = Math.min(100f, percent) / 100f;
            candidate = Math.max(candidate, PREPARED + (DOWNLOADED - PREPARED) * download);
        }
        if (text.contains("[ExtractAudio]") || text.contains("[VideoConvertor]") || text.contains("[Merger]")) {
            candidate = Math.max(candidate, CONVERTED);
        } else if (text.contains("[Metadata]")) {
            candidate = Math.max(candidate, TAGGED);
        } else if (text.contains("[EmbedThumbnail]") || text.contains("[ThumbnailsConvertor]")) {
            candidate = Math.max(candidate, COVER);
        } else if (text.contains("Deleting original file")) {
            candidate = Math.max(candidate, TAGGED);
        }
        overall = Math.max(overall, Math.min(candidate, LIMIT));
        return overall;
    }
}
