package cl.arichard.descargador.util;

import android.app.Activity;
import android.widget.EditText;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import cl.arichard.descargador.download.DownloadManager;
import cl.arichard.descargador.download.DownloadOptions;
import cl.arichard.descargador.data.PlaylistManager;
import cl.arichard.descargador.model.SearchResult;
import cl.arichard.descargador.model.Song;
import cl.arichard.descargador.player.PlayerManager;
import java.io.File;
import java.util.List;

/** Shared actions for the player and library. Every callback runs on the UI thread. */
public final class SongActions {
    private SongActions() {}

    private static void message(Activity activity, String text) {
        Toast.makeText(activity, text, Toast.LENGTH_LONG).show();
    }

    public static void addToPlaylist(Activity activity, Song song, Runnable changed) {
        addMultipleToPlaylist(activity, java.util.Collections.singletonList(song), changed);
    }

    /** Muestra el selector de playlist para agregar varias canciones de golpe. */
    public static void addMultipleToPlaylist(Activity activity, List<Song> songs, Runnable changed) {
        if (songs == null || songs.isEmpty()) return;
        PlaylistManager manager = PlaylistManager.getInstance(activity);
        PlaylistPicker.show(activity, Tr.f(songs.size() > 1 ? "Agregar %d canciones a playlist" : "Agregar %d canción a playlist", songs.size()), true,
                name -> {
                    if (name == null) {
                        EditText input = new EditText(activity);
                        input.setHint(Tr.t("Nombre de la playlist"));
                        input.setSingleLine(true);
                        AlertDialog dialog = new MaterialAlertDialogBuilder(activity).setTitle(Tr.t("Nueva playlist"))
                                .setView(input).setPositiveButton(Tr.t("Crear y agregar"), null)
                                .setNegativeButton(Tr.t("Cancelar"), null).create();
                        dialog.setOnShowListener(v -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button -> {
                            String createdName = input.getText().toString().trim();
                            if (!manager.createPlaylist(createdName)) { input.setError(Tr.t("Escribe un nombre nuevo")); return; }
                            addBulk(activity, manager, createdName, songs, changed);
                            dialog.dismiss();
                        }));
                        dialog.show();
                    } else addBulk(activity, manager, name, songs, changed);
                });
    }

    private static void addBulk(Activity activity, PlaylistManager manager, String name, List<Song> songs, Runnable changed) {
        int added = 0;
        for (Song song : songs) {
            boolean ok;
            if (song.isOnline()) {
                ok = manager.addOnlineTrack(name, new SearchResult(song.getVideoId(), song.getTitle(),
                        song.getArtist(), song.getDuration() / 1000, song.getCoverPath(),
                        "https://www.youtube.com/watch?v=" + song.getVideoId()));
            } else ok = manager.addSongToPlaylist(name, manager.canonicalKey(song));
            if (ok) added++;
        }
        int finalAdded = added;
        activity.runOnUiThread(() -> {
            message(activity, finalAdded == songs.size()
                    ? Tr.f(finalAdded > 1 ? "%d canciones agregadas a %s" : "%d canción agregada a %s", finalAdded, name)
                    : Tr.f(finalAdded > 1 ? "%d agregadas (%d ya estaban en %s)" : "%d agregada (%d ya estaba en %s)",
                            finalAdded, songs.size() - finalAdded, name));
            changed.run();
        });
    }

    private static void add(Activity activity, PlaylistManager manager, String name, Song song, Runnable changed) {
        boolean added;
        if (song.isOnline()) {
            added = manager.addOnlineTrack(name, new SearchResult(song.getVideoId(), song.getTitle(),
                    song.getArtist(), song.getDuration() / 1000, song.getCoverPath(),
                    "https://www.youtube.com/watch?v=" + song.getVideoId()));
        } else added = manager.addSongToPlaylist(name, manager.canonicalKey(song));
        message(activity, added ? Tr.t("Agregada a ") + name : Tr.t("La canción ya está en ") + name);
        changed.run();
        if (added && song.isOnline() && manager.isAutoDownloadEnabled(name))
            copyToFolder(activity, song, StorageUtil.getDownloadDir(activity), changed);
    }

    /** Descarga una canción online en la carpeta de descargas de MusicBox. */
    public static void download(Activity activity, Song song, Runnable changed) {
        if (song.isOnline()) copyToFolder(activity, song, StorageUtil.getDownloadDir(activity), changed);
    }

    private static void copyToFolder(Activity activity, Song song, File folder, Runnable changed) {
        int added = DownloadManager.get(activity).enqueue(java.util.Collections.singletonList(
                "https://www.youtube.com/watch?v=" + song.getVideoId()),
                new DownloadOptions(true, "192 kbps", false, "", DownloadManager.cookiesPath(activity), folder.getAbsolutePath()));
        message(activity, added > 0 ? Tr.t("Descarga en cola: ") + song.getTitle() : Tr.t("Descarga en curso"));
        changed.run();
    }

    /** Elimina la canción del dispositivo y de todas las playlists (con la confirmación del sistema cuando hace falta). */
    public static void delete(Activity activity, Song song, SongDeleter.SystemPrompt prompt, Runnable changed) {
        SongDeleter.delete(activity, java.util.Collections.singletonList(song), prompt, changed);
    }

    /** Versión sin confirmación del sistema: solo borra lo que la app puede borrar por sí sola. */
    public static void delete(Activity activity, Song song, Runnable changed) {
        delete(activity, song, (sender, accepted) -> accepted.accept(false), changed);
    }
}
