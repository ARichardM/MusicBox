package cl.arichard.descargador.data;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Environment;
import android.provider.DocumentsContract;
import org.json.JSONArray;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Folders this device reads music from. The list is local to the device and never synced, because every
 * device keeps its own files. An empty list means "all music on the device".
 */
public final class LibraryFolders {
    private static final String PREFS = "musicbox_prefs";
    private static final String KEY = "library_roots";

    private LibraryFolders() {}

    public static List<String> get(Context context) {
        List<String> folders = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(prefs(context).getString(KEY, "[]"));
            for (int i = 0; i < array.length(); i++) {
                String path = array.optString(i, "");
                if (!path.isEmpty()) folders.add(path);
            }
        } catch (Exception ignored) {}
        return folders;
    }

    public static boolean add(Context context, String path) {
        if (path == null || path.isEmpty()) return false;
        List<String> folders = get(context);
        if (folders.contains(path)) return false;
        folders.add(path);
        save(context, folders);
        return true;
    }

    public static void remove(Context context, String path) {
        List<String> folders = get(context);
        if (folders.remove(path)) save(context, folders);
    }

    public static void reset(Context context) { save(context, new ArrayList<>()); }

    /** True when the file lies inside one of the chosen folders (or no folder was chosen). */
    public static boolean accepts(List<String> folders, String filePath) {
        if (folders.isEmpty()) return true;
        if (filePath == null) return false;
        for (String folder : folders) {
            String base = folder.endsWith("/") ? folder : folder + "/";
            if (filePath.startsWith(base)) return true;
        }
        return false;
    }

    /** Converts a folder picked with ACTION_OPEN_DOCUMENT_TREE into a plain filesystem path. */
    public static String pathFromTree(Uri tree) {
        try {
            String id = DocumentsContract.getTreeDocumentId(tree);          // "primary:Music/Chill"
            int colon = id.indexOf(':');
            String volume = colon < 0 ? id : id.substring(0, colon);
            String relative = colon < 0 ? "" : id.substring(colon + 1);
            File base = "primary".equals(volume) ? Environment.getExternalStorageDirectory() : new File("/storage/" + volume);
            File folder = relative.isEmpty() ? base : new File(base, relative);
            return folder.getAbsolutePath();
        } catch (Exception e) {
            return null;
        }
    }

    private static void save(Context context, List<String> folders) {
        prefs(context).edit().putString(KEY, new JSONArray(folders).toString()).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
