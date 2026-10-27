package cl.arichard.descargador.util;

import android.app.Activity;
import android.media.MediaScannerConnection;
import android.widget.EditText;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import cl.arichard.descargador.DescargadorApp;
import cl.arichard.descargador.data.PlaylistManager;
import cl.arichard.descargador.model.SearchResult;
import cl.arichard.descargador.model.Song;
import cl.arichard.descargador.player.PlayerManager;
import com.yausername.youtubedl_android.YoutubeDL;
import com.yausername.youtubedl_android.YoutubeDLRequest;
import java.io.File;
import java.util.ArrayList;
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
        List<String> names = manager.getPlaylistNames();
        List<String> options = new ArrayList<>(names);
        options.add(Tr.t("＋ Nueva playlist"));
        new MaterialAlertDialogBuilder(activity).setTitle(Tr.f(songs.size() > 1 ? "Agregar %d canciones a playlist" : "Agregar %d canción a playlist", songs.size()))
                .setItems(options.toArray(new String[0]), (d, which) -> {
                    if (which == names.size()) {
                        EditText input = new EditText(activity);
                        input.setHint(Tr.t("Nombre de la playlist"));
                        input.setSingleLine(true);
                        AlertDialog dialog = new MaterialAlertDialogBuilder(activity).setTitle(Tr.t("Nueva playlist"))
                                .setView(input).setPositiveButton(Tr.t("Crear y agregar"), null)
                                .setNegativeButton(Tr.t("Cancelar"), null).create();
                        dialog.setOnShowListener(v -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button -> {
                            String name = input.getText().toString().trim();
                            if (!manager.createPlaylist(name)) { input.setError(Tr.t("Escribe un nombre nuevo")); return; }
                            addBulk(activity, manager, name, songs, changed);
                            dialog.dismiss();
                        }));
                        dialog.show();
                    } else addBulk(activity, manager, names.get(which), songs, changed);
                }).setNegativeButton(Tr.t("Cancelar"), null).show();
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
        message(activity, Tr.t("Descarga en cola: ") + song.getTitle());
        DescargadorApp.IO.execute(() -> {
            try {
                if (!folder.isDirectory() && !folder.mkdirs()) throw new Exception(Tr.t("No se pudo crear la carpeta"));
                YoutubeDLRequest request = new YoutubeDLRequest("https://www.youtube.com/watch?v=" + song.getVideoId());
                request.addOption("--no-playlist"); request.addOption("--no-overwrites");
                request.addOption("-x"); request.addOption("--audio-format", "mp3");
                request.addOption("--audio-quality", "192K");
                request.addOption("--embed-thumbnail"); request.addOption("--add-metadata");
                request.addOption("-o", new File(folder, "%(title).160s [%(id)s].%(ext)s").getAbsolutePath());
                YoutubeDL.getInstance().execute(request);
                File[] files = folder.listFiles((dir, name) -> name.endsWith("[" + song.getVideoId() + "].mp3"));
                if (files == null || files.length == 0) throw new Exception(Tr.t("No se encontró el archivo descargado"));
                File target = files[0];
                StorageUtil.scanFile(activity, target);
                activity.runOnUiThread(() -> {
                    activity.getSharedPreferences("downloaded_videos", 0).edit()
                            .putBoolean(song.getVideoId(), true).apply();
                    message(activity, Tr.t("Agregada a ") + folder.getName()); changed.run();
                });
            } catch (Exception e) {
                activity.runOnUiThread(() -> message(activity, e.getMessage() != null ? e.getMessage() : Tr.t("No se pudo agregar a la carpeta")));
            }
        });
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
