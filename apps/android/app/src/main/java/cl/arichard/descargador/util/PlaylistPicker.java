package cl.arichard.descargador.util;

import android.app.Activity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import cl.arichard.descargador.R;
import cl.arichard.descargador.data.PlaylistManager;
import cl.arichard.descargador.model.Song;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;

public final class PlaylistPicker {
    private PlaylistPicker() {}

    public static AlertDialog show(Activity activity, String title, boolean allowCreate, Consumer<String> selected) {
        PlaylistManager manager = PlaylistManager.getInstance(activity);
        List<String> names = manager.getPlaylistNames();
        Map<String, List<Song>> songs = new HashMap<>();
        BaseAdapter adapter = new BaseAdapter() {
            @Override public int getCount() { return names.size() + (allowCreate ? 1 : 0); }
            @Override public String getItem(int position) { return position < names.size() ? names.get(position) : null; }
            @Override public long getItemId(int position) { return position; }
            @Override public View getView(int position, View recycled, ViewGroup parent) {
                View row = recycled == null ? LayoutInflater.from(activity).inflate(R.layout.item_playlist_choice, parent, false) : recycled;
                String name = getItem(position);
                boolean create = name == null;
                ((TextView) row.findViewById(R.id.playlistChoiceName)).setText(create ? Tr.t("Nueva playlist") : Tr.name(name));
                TextView count = row.findViewById(R.id.playlistChoiceCount);
                count.setVisibility(create ? View.GONE : View.VISIBLE);
                if (!create) {
                    int size = manager.getSongPaths(name).size();
                    count.setText(Tr.f(size == 1 ? "%d canción" : "%d canciones", size));
                }
                CoverCollageView cover = row.findViewById(R.id.playlistChoiceCover);
                cover.setVisibility(create ? View.GONE : View.VISIBLE);
                if (!create) cover.setSongs(songs.getOrDefault(name, Collections.emptyList()));
                row.findViewById(R.id.playlistChoiceAdd).setVisibility(create ? View.VISIBLE : View.GONE);
                return row;
            }
        };
        AlertDialog dialog = new MaterialAlertDialogBuilder(activity).setTitle(title)
                .setAdapter(adapter, (window, position) -> selected.accept(position < names.size() ? names.get(position) : null))
                .setNegativeButton(Tr.t("Cancelar"), null).show();
        new Thread(() -> {
            List<Song> library = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (String name : names) for (String path : manager.getSongPaths(name)) {
                if (path.startsWith("youtube:") || !seen.add(path)) continue;
                File file = new File(path);
                if (file.isFile()) library.add(new Song(0, file.getName(), "", "", 0, path, "", 0));
            }
            Map<String, List<Song>> loaded = new HashMap<>();
            for (String name : names) loaded.put(name, manager.resolveSongs(name, library));
            activity.runOnUiThread(() -> {
                if (activity.isDestroyed() || !dialog.isShowing()) return;
                songs.putAll(loaded);
                adapter.notifyDataSetChanged();
            });
        }, "playlist-covers").start();
        return dialog;
    }
}
