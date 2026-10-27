package cl.arichard.descargador.download;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Lo que se puede saber de un archivo descargado por su nombre: "NNN - Título [id].ext". */
public final class FileNames {
    private static final Pattern ID = Pattern.compile("\\[([A-Za-z0-9_-]{11})\\]\\.[A-Za-z0-9]+$");
    private static final Pattern PREFIX = Pattern.compile("^\\d{3} - ");

    private FileNames() {}

    public static String baseName(String path) {
        if (path == null) return "";
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    /** El id de YouTube entre corchetes al final del nombre, o null. */
    public static String videoId(String path) {
        Matcher m = ID.matcher(baseName(path));
        return m.find() ? m.group(1) : null;
    }

    /** El título sin número de orden, id ni extensión. */
    public static String title(String path) {
        String name = baseName(path);
        int dot = name.lastIndexOf('.');
        if (dot > 0) name = name.substring(0, dot);
        name = name.replaceAll("\\s*\\[[A-Za-z0-9_-]{11}\\]$", "");
        return PREFIX.matcher(name).replaceFirst("").trim();
    }
}
