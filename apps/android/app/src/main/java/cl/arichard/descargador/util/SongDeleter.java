package cl.arichard.descargador.util;

import android.app.Activity;
import android.app.RecoverableSecurityException;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.IntentSender;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.view.View;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

import cl.arichard.descargador.DescargadorApp;
import cl.arichard.descargador.data.PlaylistManager;
import cl.arichard.descargador.data.SearchHistoryManager;
import cl.arichard.descargador.model.Song;
import cl.arichard.descargador.player.PlayerManager;

/**
 * Elimina canciones del dispositivo con el permiso que Android exige. Los archivos de la propia app se borran sin más;
 * los demás (música del teléfono, descargas anteriores) piden la confirmación del sistema con
 * {@code MediaStore.createDeleteRequest}, de modo que no hace falta el acceso total a los archivos.
 */
public final class SongDeleter {
    private SongDeleter() {}

    /** Quien sabe lanzar la confirmación del sistema y avisar si el usuario la aceptó. */
    public interface SystemPrompt { void launch(IntentSender sender, Consumer<Boolean> accepted); }

    /** Registra el lanzador de la confirmación del sistema; llámalo al crear la pantalla (antes de que inicie). */
    public static SystemPrompt prompter(androidx.activity.ComponentActivity activity) {
        @SuppressWarnings("unchecked") final Consumer<Boolean>[] pending = new Consumer[1];
        final androidx.activity.result.ActivityResultLauncher<androidx.activity.result.IntentSenderRequest> launcher =
                activity.registerForActivityResult(new androidx.activity.result.contract.ActivityResultContracts.StartIntentSenderForResult(), result -> {
                    Consumer<Boolean> callback = pending[0];
                    pending[0] = null;
                    if (callback != null) callback.accept(result.getResultCode() == Activity.RESULT_OK);
                });
        return (sender, accepted) -> {
            pending[0] = accepted;
            launcher.launch(new androidx.activity.result.IntentSenderRequest.Builder(sender).build());
        };
    }

    /** "1,5 MB", "820 KB"… con el separador del idioma. */
    public static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format(Locale.getDefault(), "%.0f KB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return String.format(Locale.getDefault(), mb < 10 ? "%.1f MB" : "%.0f MB", mb);
        return String.format(Locale.getDefault(), "%.1f GB", mb / 1024.0);
    }

    /** True si el archivo vive en las carpetas de la propia app (se puede borrar sin permiso). */
    public static boolean isAppOwned(String path, List<String> appDirs) {
        if (path == null) return false;
        for (String dir : appDirs) {
            if (dir == null || dir.isEmpty()) continue;
            String base = dir.endsWith("/") ? dir : dir + "/";
            if (path.startsWith(base)) return true;
        }
        return false;
    }

    private static List<String> appDirs(Activity activity) {
        List<String> dirs = new ArrayList<>();
        dirs.add(activity.getFilesDir().getParent());
        File external = activity.getExternalFilesDir(null);
        if (external != null && external.getParentFile() != null) dirs.add(external.getParentFile().getAbsolutePath());
        File cache = activity.getExternalCacheDir();
        if (cache != null && cache.getParentFile() != null) dirs.add(cache.getParentFile().getAbsolutePath());
        return dirs;
    }

    private static Uri mediaUri(ContentResolver resolver, String path) {
        try (Cursor cursor = resolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, new String[]{MediaStore.Audio.Media._ID},
                MediaStore.Audio.Media.DATA + "=?", new String[]{path}, null)) {
            if (cursor != null && cursor.moveToFirst())
                return ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, cursor.getLong(0));
        } catch (Exception ignored) {}
        return null;
    }

    private static void notice(Activity activity, String text) {
        View anchor = activity.findViewById(android.R.id.content);
        if (anchor != null) AppNotice.info(anchor, text);
    }

    /**
     * Pide confirmación (con el espacio que se liberará) y elimina. {@code changed} se llama al terminar para que la
     * pantalla recargue la biblioteca.
     */
    public static void delete(Activity activity, List<Song> songs, SystemPrompt prompt, Runnable changed) {
        List<Song> local = new ArrayList<>();
        for (Song song : songs) if (!song.isOnline() && song.getPath() != null) local.add(song);
        if (local.isEmpty()) { notice(activity, Tr.t("Las canciones online no ocupan espacio en el dispositivo.")); return; }
        DescargadorApp.IO.execute(() -> {
            long bytes = 0;
            for (Song song : local) bytes += new File(song.getPath()).length();
            final long total = bytes;
            activity.runOnUiThread(() -> {
                if (activity.isFinishing() || activity.isDestroyed()) return;
                String title = local.size() == 1 ? Tr.t("Eliminar «") + local.get(0).getTitle() + "»"
                        : Tr.f("Eliminar %d canciones", local.size());
                String message = Tr.f("Se liberarán %s. Los archivos se eliminan del dispositivo y las canciones se quitan de tus playlists. No se puede deshacer.", formatBytes(total));
                new MaterialAlertDialogBuilder(activity).setTitle(title).setMessage(message)
                        .setNegativeButton(Tr.t("Cancelar"), null)
                        .setPositiveButton(Tr.t("Eliminar"), (d, which) -> run(activity, local, prompt, changed)).show();
            });
        });
    }

    private static void run(Activity activity, List<Song> songs, SystemPrompt prompt, Runnable changed) {
        DescargadorApp.IO.execute(() -> {
            List<String> dirs = appDirs(activity);
            ContentResolver resolver = activity.getContentResolver();
            List<Song> deleted = new ArrayList<>(), viaSystem = new ArrayList<>(), failed = new ArrayList<>();
            List<Uri> uris = new ArrayList<>();
            Map<String, Long> sizes = new HashMap<>();
            long freed = 0;
            for (Song song : songs) {
                File file = new File(song.getPath());
                long size = file.length();
                if (isAppOwned(song.getPath(), dirs) || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    if (file.isFile() && file.delete()) { deleted.add(song); freed += size; } else failed.add(song);
                    continue;
                }
                Uri uri = mediaUri(resolver, song.getPath());
                if (uri != null) { viaSystem.add(song); uris.add(uri); sizes.put(song.getPath(), size); }
                else if (file.isFile() && file.delete()) { deleted.add(song); freed += size; }
                else failed.add(song);
            }
            final long alreadyFreed = freed;
            if (viaSystem.isEmpty()) { finish(activity, deleted, failed, alreadyFreed, changed); return; }
            activity.runOnUiThread(() -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    try {
                        IntentSender sender = MediaStore.createDeleteRequest(resolver, uris).getIntentSender();
                        prompt.launch(sender, accepted -> DescargadorApp.IO.execute(
                                () -> settle(activity, viaSystem, sizes, deleted, failed, alreadyFreed, changed)));
                    } catch (Exception e) {
                        failed.addAll(viaSystem);
                        DescargadorApp.IO.execute(() -> finish(activity, deleted, failed, alreadyFreed, changed));
                    }
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    DescargadorApp.IO.execute(() -> sequential(activity, prompt, viaSystem, uris, 0, deleted, failed, alreadyFreed, changed));
                }
            });
        });
    }

    /** Tras la confirmación del sistema: lo que ya no existe se da por eliminado. */
    private static void settle(Activity activity, List<Song> viaSystem, Map<String, Long> sizes, List<Song> deleted, List<Song> failed,
                               long freed, Runnable changed) {
        long gained = freed;
        for (Song song : viaSystem) {
            File file = new File(song.getPath());
            if (!file.exists()) { deleted.add(song); Long size = sizes.get(song.getPath()); gained += size == null ? 0 : size; }
            else failed.add(song);
        }
        finish(activity, deleted, failed, gained, changed);
    }

    /** Android 10: cada archivo pide su propia confirmación cuando el sistema la exige. */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private static void sequential(Activity activity, SystemPrompt prompt, List<Song> songs, List<Uri> uris, int index,
                                   List<Song> deleted, List<Song> failed, long freed, Runnable changed) {
        if (index >= songs.size()) { finish(activity, deleted, failed, freed, changed); return; }
        Song song = songs.get(index);
        long size = new File(song.getPath()).length();
        try {
            activity.getContentResolver().delete(uris.get(index), null, null);
            deleted.add(song);
            sequential(activity, prompt, songs, uris, index + 1, deleted, failed, freed + size, changed);
        } catch (RecoverableSecurityException e) {
            activity.runOnUiThread(() -> prompt.launch(e.getUserAction().getActionIntent().getIntentSender(), accepted -> DescargadorApp.IO.execute(() -> {
                boolean gone = false;
                if (accepted) {
                    try { activity.getContentResolver().delete(uris.get(index), null, null); gone = true; } catch (Exception ignored) {}
                }
                long gained = gone ? size : 0;
                if (gone) deleted.add(song); else failed.add(song);
                sequential(activity, prompt, songs, uris, index + 1, deleted, failed, freed + gained, changed);
            })));
        } catch (Exception e) {
            failed.add(song);
            sequential(activity, prompt, songs, uris, index + 1, deleted, failed, freed, changed);
        }
    }

    /** Limpia playlists, historial y cola de lo eliminado y avisa del resultado. */
    private static void finish(Activity activity, List<Song> deleted, List<Song> failed, long freed, Runnable changed) {
        PlaylistManager playlists = PlaylistManager.getInstance(activity);
        SearchHistoryManager history = SearchHistoryManager.getInstance(activity);
        long gained = freed;
        for (Song song : deleted) {
            playlists.removeSongEverywhere(song.getPath());
            history.removeSong(song.getPath());
        }
        final long shown = gained;
        activity.runOnUiThread(() -> {
            for (Song song : deleted) PlayerManager.getInstance().removeSong(song.getPath());
            if (!deleted.isEmpty()) {
                String done = deleted.size() == 1 ? Tr.t("Canción eliminada") : Tr.f("%d canciones eliminadas", deleted.size());
                notice(activity, shown > 0 ? done + " · " + Tr.f("%s liberados", formatBytes(shown)) : done);
            }
            if (!failed.isEmpty()) notice(activity, failed.size() == 1 ? Tr.t("No se pudo eliminar 1 canción.") : Tr.f("No se pudieron eliminar %d canciones.", failed.size()));
            if (!deleted.isEmpty() && changed != null) changed.run();
        });
    }
}
