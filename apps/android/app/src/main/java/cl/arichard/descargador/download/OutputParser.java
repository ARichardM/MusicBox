package cl.arichard.descargador.download;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lee la salida de yt-dlp línea a línea: avance, velocidad, número de elemento de una playlist y los
 * archivos que ya quedaron terminados (incluidos los que ya existían).
 */
public final class OutputParser {
    private static final Pattern PROGRESS = Pattern.compile(
            "\\[download\\]\\s+([\\d.]+)% of\\s+~?\\s*([\\d.]+\\S*B)(?:\\s+at\\s+(\\S+))?(?:\\s+ETA\\s+(\\S+))?");
    private static final Pattern ITEM = Pattern.compile("\\[download\\] Downloading item (\\d+) of (\\d+)");
    private static final Pattern DESTINATION = Pattern.compile("\\[download\\] Destination: (.+)");
    private static final Pattern EXTRACT = Pattern.compile("\\[ExtractAudio\\] Destination: (.+)");
    private static final Pattern NOT_CONVERTING = Pattern.compile("\\[ExtractAudio\\] Not converting audio (.+?); file is already in target format");
    private static final Pattern MERGER = Pattern.compile("\\[Merger\\] Merging formats into \"(.+)\"");
    private static final Pattern REMUX = Pattern.compile("\\[VideoRemuxer\\] Remuxing video from \\w+ to \\w+; Destination: (.+)");
    private static final Pattern EXISTING = Pattern.compile("\\[download\\] (.+) has already been downloaded");
    private static final String[] STAGES = {"[ExtractAudio]", "[Metadata]", "[EmbedThumbnail]", "[ThumbnailsConvertor]", "[Merger]", "[VideoRemuxer]", "[FixupM4a]"};

    /** Un archivo terminado; {@code existing} indica que ya estaba en la carpeta y se conservó. */
    public static final class Finished {
        public final String path;
        public final boolean existing;
        Finished(String path, boolean existing) { this.path = path; this.existing = existing; }
    }

    private final boolean mp3;
    private final List<Finished> finished = new ArrayList<>();
    private String pendingDownload, lastDestination = "", lastFinishedPath = "";
    private int itemIndex = 1, itemCount = 1;
    private String speed = "", size = "";
    private boolean processing;

    public OutputParser(boolean mp3) { this.mp3 = mp3; }

    public int itemIndex() { return itemIndex; }
    public int itemCount() { return itemCount; }
    public String speed() { return speed; }
    public String size() { return size; }
    /** El último archivo que yt-dlp empezó a escribir; sirve para mostrar el título mientras baja. */
    public String lastDestination() { return lastDestination; }
    /** True mientras yt-dlp convierte, incrusta etiquetas o une audio y video. */
    public boolean processing() { return processing; }

    public void feed(String line) {
        if (line == null) return;
        String text = line.trim();
        Matcher m = ITEM.matcher(text);
        if (m.find()) {
            flushPending();
            itemIndex = Integer.parseInt(m.group(1)); itemCount = Integer.parseInt(m.group(2));
            processing = false; speed = ""; size = "";
            return;
        }
        m = PROGRESS.matcher(text);
        if (m.find()) {
            processing = false;
            size = m.group(2);
            speed = m.group(3) == null || m.group(3).startsWith("Unknown") ? "" : m.group(3);
            return;
        }
        m = EXISTING.matcher(text);
        if (m.find()) { finished.add(new Finished(m.group(1), true)); lastFinishedPath = m.group(1); pendingDownload = null; return; }
        m = DESTINATION.matcher(text);
        if (m.find()) { pendingDownload = m.group(1); lastDestination = m.group(1); return; }
        m = EXTRACT.matcher(text);
        if (m.find()) { finish(m.group(1)); processing = true; return; }
        m = NOT_CONVERTING.matcher(text);
        if (m.find()) { finish(m.group(1)); return; }
        m = MERGER.matcher(text);
        if (m.find()) { finish(m.group(1)); processing = true; return; }
        m = REMUX.matcher(text);
        if (m.find()) { finish(m.group(1)); processing = true; return; }
        for (String stage : STAGES) if (text.startsWith(stage)) { processing = true; return; }
    }

    private void finish(String path) {
        // yt-dlp repite el archivo en varias líneas (existente y luego "no se convierte"): se cuenta una sola vez.
        if (path.equals(lastFinishedPath)) { pendingDownload = null; return; }
        finished.add(new Finished(path, false));
        lastFinishedPath = path;
        pendingDownload = null;
    }

    /** Si en MP4 el archivo bajó ya fusionado, no hubo unión que lo marque: se toma el último destino. */
    private void flushPending() {
        if (!mp3 && pendingDownload != null && !pendingDownload.equals(lastFinishedPath)) {
            finished.add(new Finished(pendingDownload, false));
            lastFinishedPath = pendingDownload;
        }
        pendingDownload = null;
    }

    /** Cierra la lectura y devuelve (y vacía) los archivos terminados hasta ahora. */
    public List<Finished> end() { flushPending(); return drain(); }

    /** Los archivos que terminaron desde la última llamada. */
    public List<Finished> drain() {
        List<Finished> result = new ArrayList<>(finished);
        finished.clear();
        return result;
    }
}
