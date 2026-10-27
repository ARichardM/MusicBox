package cl.arichard.descargador.util;

import android.app.Activity;
import android.content.Intent;
import android.view.View;
import android.widget.PopupMenu;
import android.widget.Toast;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;

import cl.arichard.descargador.NowPlayingActivity;
import cl.arichard.descargador.data.PlaylistManager;
import cl.arichard.descargador.model.SearchResult;
import cl.arichard.descargador.model.Song;
import cl.arichard.descargador.player.PlayerManager;

/** Acciones comunes de una canción de YouTube: reproducir una lista como cola y el menú "+". */
public final class OnlineTrackMenu {
    private OnlineTrackMenu() {}

    public static Song songOf(SearchResult result) {
        return new Song(0, result.getTitle(), result.getChannel(), "YouTube", result.getDurationSeconds() * 1000L,
                "youtube:" + result.getId(), "YouTube", 0);
    }

    /** Reproduce {@code list} desde {@code index}; lo que sigue queda en la cola del reproductor. */
    public static void playList(Activity activity, String sourceName, List<SearchResult> list, int index) {
        if (list == null || index < 0 || index >= list.size()) return;
        List<Song> queue = new ArrayList<>();
        for (SearchResult item : list) queue.add(songOf(item));
        PlayerManager player = PlayerManager.getInstance();
        player.setPlaybackSource("artist", sourceName == null ? "" : sourceName);
        player.playSong(queue.get(index), queue);
        SearchResult chosen = list.get(index);
        activity.startActivity(new Intent(activity, NowPlayingActivity.class)
                .putExtra(NowPlayingActivity.EXTRA_VIDEO_ID, chosen.getId()).putExtra(NowPlayingActivity.EXTRA_URL, chosen.getUrl())
                .putExtra(NowPlayingActivity.EXTRA_TITLE, chosen.getTitle()).putExtra(NowPlayingActivity.EXTRA_CHANNEL, chosen.getChannel())
                .putExtra(NowPlayingActivity.EXTRA_THUMBNAIL, chosen.getThumbnailUrl()));
    }

    /** Menú "+": agregar a una playlist, reproducir a continuación o agregar a la cola. */
    public static void show(Activity activity, SearchResult result, View anchor) {
        PopupMenu menu = new PopupMenu(activity, anchor);
        menu.getMenu().add(Tr.t("Agregar a playlist")).setOnMenuItemClickListener(item -> { addToPlaylist(activity, result); return true; });
        Song song = songOf(result);
        menu.getMenu().add(Tr.t("Reproducir a continuación")).setOnMenuItemClickListener(item -> {
            PlayerManager.getInstance().enqueue(song, true);
            Toast.makeText(activity, Tr.t("Agregada a continuación"), Toast.LENGTH_SHORT).show();
            return true;
        });
        menu.getMenu().add(Tr.t("Agregar a la cola")).setOnMenuItemClickListener(item -> {
            PlayerManager.getInstance().enqueue(song, false);
            Toast.makeText(activity, Tr.t("Agregada a la cola"), Toast.LENGTH_SHORT).show();
            return true;
        });
        menu.show();
    }

    public static void addToPlaylist(Activity activity, SearchResult result) {
        PlaylistManager manager = PlaylistManager.getInstance(activity);
        List<String> names = manager.getPlaylistNames();
        String[] shown = new String[names.size()];
        for (int i = 0; i < shown.length; i++) shown[i] = Tr.name(names.get(i));
        new MaterialAlertDialogBuilder(activity).setTitle(Tr.t("Agregar a playlist")).setItems(shown, (dialog, which) -> {
            boolean added = manager.addOnlineTrack(names.get(which), result);
            Toast.makeText(activity, (added ? Tr.t("Agregada a ") : Tr.t("Ya estaba en ")) + shown[which], Toast.LENGTH_SHORT).show();
        }).setNegativeButton(Tr.t("Cancelar"), null).show();
    }
}
