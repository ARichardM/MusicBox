package cl.arichard.descargador.download;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Qué y cómo descargar. {@link #args} entrega las opciones de yt-dlp con las mismas reglas que
 * build_options y build_output_template de escritorio, adaptadas a la línea de comandos.
 */
public final class DownloadOptions {
    public static final List<String> AUDIO_QUALITY = Arrays.asList("192 kbps", "128 kbps", "256 kbps", "320 kbps");
    public static final List<String> VIDEO_QUALITY = Arrays.asList("1080p", "720p", "480p", "360p", "Mejor disponible");

    public final boolean mp3;
    /** Una de {@link #AUDIO_QUALITY} o de {@link #VIDEO_QUALITY}, según el formato. */
    public final String quality;
    public final boolean playlist;
    /** Rango de la playlist (1-10 o 1,3,5-8); vacío para toda la lista. */
    public final String items;
    /** Ruta de un cookies.txt en formato Netscape, o vacío. */
    public final String cookies;
    /** Carpeta de destino, sin barra final. */
    public final String destination;

    public DownloadOptions(boolean mp3, String quality, boolean playlist, String items, String cookies, String destination) {
        this.mp3 = mp3;
        this.quality = quality == null || quality.isEmpty() ? (mp3 ? AUDIO_QUALITY.get(0) : VIDEO_QUALITY.get(0)) : quality;
        this.playlist = playlist;
        this.items = items == null ? "" : items;
        this.cookies = cookies == null ? "" : cookies;
        this.destination = destination.endsWith("/") ? destination.substring(0, destination.length() - 1) : destination;
    }

    public String format() { return mp3 ? "MP3" : "MP4"; }

    /** Plantilla de nombre. En lotes de enlaces sueltos se antepone la posición para conservar el orden. */
    public String outputTemplate(int queuePosition, int linkCount) {
        if (playlist) return destination + "/%(playlist_title|Sin playlist).100B/%(playlist_index|0)03d - %(title).140B [%(id)s].%(ext)s";
        String prefix = linkCount > 1 ? String.format(Locale.ROOT, "%03d - ", queuePosition) : "";
        return destination + "/" + prefix + "%(title).150B [%(id)s].%(ext)s";
    }

    /** Opciones de yt-dlp como pares {opción} o {opción, valor}. */
    public List<String[]> args(int queuePosition, int linkCount) {
        List<String[]> args = new ArrayList<>();
        args.add(new String[]{"--no-update"});
        args.add(new String[]{"--extractor-args", "youtube:player_client=android,web"});
        args.add(new String[]{"--no-mtime"});
        args.add(new String[]{"--windows-filenames"});
        args.add(new String[]{"--ignore-errors"});
        args.add(new String[]{"--continue"});
        args.add(new String[]{"--no-overwrites"});
        args.add(new String[]{"--socket-timeout", "20"});
        args.add(new String[]{"--retries", "3"});
        args.add(new String[]{"--fragment-retries", "3"});
        args.add(new String[]{"-o", outputTemplate(queuePosition, linkCount)});
        if (playlist) {
            args.add(new String[]{"--yes-playlist"});
            String range = LinkParser.validateItems(items);
            if (!range.isEmpty()) args.add(new String[]{"--playlist-items", range});
        } else {
            args.add(new String[]{"--no-playlist"});
        }
        if (!cookies.isEmpty()) args.add(new String[]{"--cookies", cookies});
        if (mp3) {
            args.add(new String[]{"-x"});
            args.add(new String[]{"--audio-format", "mp3"});
            args.add(new String[]{"--audio-quality", quality.split(" ")[0] + "K"});
            args.add(new String[]{"--add-metadata"});
            args.add(new String[]{"--embed-thumbnail"});
            // Sin portadas sueltas de la playlist: la imagen solo se incrusta.
            args.add(new String[]{"-o", "pl_thumbnail:"});
        } else {
            String cap = VIDEO_QUALITY.get(4).equals(quality) ? "" : "[height<=?" + quality.replace("p", "") + "]";
            args.add(new String[]{"-f", "bestvideo[ext=mp4]" + cap + "+bestaudio[ext=m4a]/best[ext=mp4]" + cap});
            args.add(new String[]{"--merge-output-format", "mp4"});
            args.add(new String[]{"--remux-video", "mp4"});
        }
        return args;
    }
}
