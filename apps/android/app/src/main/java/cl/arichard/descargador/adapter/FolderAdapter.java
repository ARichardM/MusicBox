package cl.arichard.descargador.adapter;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import cl.arichard.descargador.R;
import cl.arichard.descargador.model.Song;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class FolderAdapter extends RecyclerView.Adapter<FolderAdapter.FolderViewHolder> {

    public interface OnFolderClickListener {
        void onFolderClick(String folderName, List<Song> songs);
    }

    private final Context context;
    private final Map<String, List<Song>> allFolders = new LinkedHashMap<>();
    private final List<String> displayedFolderNames = new ArrayList<>();
    private final OnFolderClickListener listener;

    public FolderAdapter(Context context, Map<String, List<Song>> foldersMap, OnFolderClickListener listener) {
        this.context = context;
        this.listener = listener;
        updateFolders(foldersMap);
    }

    public void updateFolders(Map<String, List<Song>> foldersMap) {
        allFolders.clear();
        if (foldersMap != null) {
            allFolders.putAll(foldersMap);
        }
        filter("");
    }

    public void filter(String query) {
        displayedFolderNames.clear();
        String q = query != null ? query.trim().toLowerCase() : "";
        for (String name : allFolders.keySet()) {
            if (q.isEmpty() || name.toLowerCase().contains(q)) {
                displayedFolderNames.add(name);
            }
        }
        notifyDataSetChanged();
    }

    public int getDisplayedCount() { return displayedFolderNames.size(); }

    @NonNull @Override
    public FolderViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(context).inflate(R.layout.item_folder, parent, false);
        return new FolderViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull FolderViewHolder holder, int position) {
        String name = displayedFolderNames.get(position);
        List<Song> songs = allFolders.get(name);
        int count = songs != null ? songs.size() : 0;

        holder.collage.setSongs(songs);
        holder.txtName.setText(name);
        holder.txtCount.setText(count + (count == 1 ? cl.arichard.descargador.util.Tr.t(" canción") : cl.arichard.descargador.util.Tr.t(" canciones"))
                + cl.arichard.descargador.util.Clicks.suffix(cl.arichard.descargador.util.Clicks.of(holder.itemView.getContext(), songs)));

        holder.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onFolderClick(name, songs);
        });

        boolean autoDl = context.getSharedPreferences("auto_download_folders", Context.MODE_PRIVATE)
                .getBoolean(name, false);
        holder.autoDownload.setChecked(autoDl);
        holder.autoDownload.setOnClickListener(v -> {
            boolean checked = holder.autoDownload.isChecked();
            if (!checked) {
                holder.autoDownload.setChecked(true);
                new com.google.android.material.dialog.MaterialAlertDialogBuilder(context)
                        .setTitle(cl.arichard.descargador.util.Tr.t("¿Desactivar descarga automática?"))
                        .setMessage(cl.arichard.descargador.util.Tr.t("Si desactivas la auto-descarga, las canciones nuevas no se descargarán automáticamente al agregarse.\nLas ya descargadas se mantendrán."))
                        .setPositiveButton(cl.arichard.descargador.util.Tr.t("Desactivar"), (dialog, which) -> {
                            holder.autoDownload.setChecked(false);
                            context.getSharedPreferences("auto_download_folders", Context.MODE_PRIVATE).edit()
                                    .putBoolean(name, false).apply();
                        })
                        .setNegativeButton(cl.arichard.descargador.util.Tr.t("Cancelar"), null)
                        .show();
            } else {
                context.getSharedPreferences("auto_download_folders", Context.MODE_PRIVATE).edit()
                        .putBoolean(name, true).apply();
            }
        });

        cl.arichard.descargador.util.SafeTap.install(holder.autoDownload);
        cl.arichard.descargador.util.SafeTap.installRowTap(holder.itemView);
    }

    @Override public int getItemCount() { return displayedFolderNames.size(); }

    static class FolderViewHolder extends RecyclerView.ViewHolder {
        TextView txtName, txtCount;
        cl.arichard.descargador.util.CoverCollageView collage;
        androidx.appcompat.widget.SwitchCompat autoDownload;

        public FolderViewHolder(@NonNull View itemView) {
            super(itemView);
            collage = itemView.findViewById(R.id.folderCollage);
            txtName = itemView.findViewById(R.id.txtFolderName);
            txtCount = itemView.findViewById(R.id.txtFolderSongCount);
            autoDownload = itemView.findViewById(R.id.switchFolderAutoDownload);
        }
    }
}
