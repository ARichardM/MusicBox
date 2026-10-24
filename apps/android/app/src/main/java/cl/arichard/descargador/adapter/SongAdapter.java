package cl.arichard.descargador.adapter;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;
import cl.arichard.descargador.R;
import cl.arichard.descargador.data.PlaylistManager;
import cl.arichard.descargador.model.Song;
import cl.arichard.descargador.player.PlayerManager;
import cl.arichard.descargador.util.CoverArtLoader;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class SongAdapter extends RecyclerView.Adapter<SongAdapter.SongViewHolder> {

    public interface OnSongClickListener {
        void onSongClick(Song song, List<Song> queue, int position);
        void onFavoriteClick(Song song, int position);
        void onMenuClick(Song song, View anchor, int position);
        /** Llamado cuando el modo de selección cambia (se activa/desactiva o cambia el conteo). */
        default void onSelectionChanged(int count) {}
    }

    private final Context context;
    private final List<Song> originalList = new ArrayList<>();
    private final List<Song> displayedList = new ArrayList<>();
    private final OnSongClickListener listener;
    private final java.util.Set<String> selectedPaths = new java.util.LinkedHashSet<>();
    private final java.util.Set<String> downloadingVideoIds = new java.util.HashSet<>();
    private final java.util.Set<String> justCompletedVideoIds = new java.util.HashSet<>();
    private boolean selectionMode = false;

    private String currentQuery = "";

    public SongAdapter(Context context, List<Song> songs, OnSongClickListener listener) {
        this.context = context;
        this.listener = listener;
        setHasStableIds(true);
        updateSongs(songs);
    }

    public void updateSongs(List<Song> songs) {
        originalList.clear();
        if (songs != null) {
            originalList.addAll(songs);
        }
        applyFiltersAndSort();
    }

    public void filter(String query) {
        this.currentQuery = query != null ? query.trim().toLowerCase() : "";
        applyFiltersAndSort();
    }

    private void applyFiltersAndSort() {
        List<Song> before = new ArrayList<>(displayedList);
        displayedList.clear();
        for (Song s : originalList) {
            // Check text query filter
            if (!currentQuery.isEmpty()) {
                boolean matchTitle = s.getTitle().toLowerCase().contains(currentQuery);
                boolean matchArtist = s.getArtist().toLowerCase().contains(currentQuery);
                boolean matchAlbum = s.getAlbum() != null && s.getAlbum().toLowerCase().contains(currentQuery);
                if (!matchTitle && !matchArtist && !matchAlbum) {
                    continue;
                }
            }

            displayedList.add(s);
        }

        List<Song> after = new ArrayList<>(displayedList);
        androidx.recyclerview.widget.DiffUtil.calculateDiff(new androidx.recyclerview.widget.DiffUtil.Callback() {
            @Override public int getOldListSize() { return before.size(); }
            @Override public int getNewListSize() { return after.size(); }
            @Override public boolean areItemsTheSame(int a, int b) { return before.get(a).getPath().equals(after.get(b).getPath()); }
            @Override public boolean areContentsTheSame(int a, int b) {
                Song old = before.get(a), next = after.get(b);
                return old.getTitle().equals(next.getTitle()) && old.getArtist().equals(next.getArtist()) && old.getDuration() == next.getDuration();
            }
        }).dispatchUpdatesTo(this);
    }
    @Override public long getItemId(int position) {
        long id = 0xcbf29ce484222325L;
        for (char value : displayedList.get(position).getPath().toCharArray()) id = (id ^ value) * 0x100000001b3L;
        return id;
    }

    public List<Song> getSelectedSongs() {
        List<Song> result = new ArrayList<>();
        for (Song s : displayedList) if (selectedPaths.contains(s.getPath())) result.add(s);
        return result;
    }
    public int getSelectionCount() { return selectedPaths.size(); }
    public boolean isSelectionMode() { return selectionMode; }
    public void clearSelection() {
        selectedPaths.clear();
        selectionMode = false;
        notifyDataSetChanged();
        if (listener != null) listener.onSelectionChanged(0);
    }
    public void selectAll() {
        for (Song s : displayedList) selectedPaths.add(s.getPath());
        if (!displayedList.isEmpty()) {
            selectionMode = true;
            notifyDataSetChanged();
            if (listener != null) listener.onSelectionChanged(selectedPaths.size());
        }
    }
    private void toggleSelection(Song song) {
        if (selectedPaths.contains(song.getPath())) selectedPaths.remove(song.getPath());
        else selectedPaths.add(song.getPath());
        if (selectedPaths.isEmpty()) { selectionMode = false; }
        notifyDataSetChanged();
        if (listener != null) listener.onSelectionChanged(selectedPaths.size());
    }
    public void activateSelectionMode(Song firstSong) {
        selectionMode = true;
        selectedPaths.clear();
        if (firstSong != null) selectedPaths.add(firstSong.getPath());
        notifyDataSetChanged();
        if (listener != null) listener.onSelectionChanged(selectedPaths.size());
    }

    public List<Song> getDisplayedList() { return displayedList; }

    public void setDownloadStarted(String videoId) {
        if (videoId == null || videoId.isEmpty()) return;
        downloadingVideoIds.add(videoId);
        notifyDataSetChanged();
    }

    public void setDownloadFinished(String videoId, boolean success) {
        if (videoId == null || videoId.isEmpty()) return;
        downloadingVideoIds.remove(videoId);
        if (success) justCompletedVideoIds.add(videoId);
        notifyDataSetChanged();
    }

    @NonNull @Override
    public SongViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(context).inflate(R.layout.item_song, parent, false);
        return new SongViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull SongViewHolder holder, int position) {
        Song song = displayedList.get(position);
        holder.txtTitle.setText(song.getTitle());
        holder.txtSubtitle.setText(song.getArtist() + " • " + song.getFormattedDuration());

        CoverArtLoader.getInstance().loadSongCover(song.getCoverPath(), holder.imgCover);

        String videoId = song.getVideoId();
        boolean downloading = videoId != null && downloadingVideoIds.contains(videoId);
        boolean savedOnline = videoId != null && context.getSharedPreferences("downloaded_videos", Context.MODE_PRIVATE)
                .getBoolean(videoId, false);
        boolean isLocalFile = song.getPath() != null && new File(song.getPath()).exists();
        holder.progressDownload.animate().cancel();
        holder.imgDownloadedBadge.animate().cancel();
        holder.progressDownload.setVisibility(downloading && !selectionMode ? View.VISIBLE : View.GONE);
        if (downloading && !selectionMode) {
            holder.progressDownload.setAlpha(0f);
            holder.progressDownload.animate().alpha(1f).setDuration(180).start();
        }
        if (holder.imgDownloadedBadge != null) {
            holder.imgDownloadedBadge.setVisibility((isLocalFile || savedOnline) && !downloading && !selectionMode
                    ? View.VISIBLE : View.GONE);
            holder.imgDownloadedBadge.setScaleX(1f);
            holder.imgDownloadedBadge.setScaleY(1f);
            holder.imgDownloadedBadge.setRotation(0f);
            holder.imgDownloadedBadge.setAlpha(1f);
            if (savedOnline && justCompletedVideoIds.remove(videoId)) {
                holder.imgDownloadedBadge.setScaleX(0f);
                holder.imgDownloadedBadge.setScaleY(0f);
                holder.imgDownloadedBadge.setRotation(-35f);
                holder.imgDownloadedBadge.setAlpha(0f);
                holder.imgDownloadedBadge.animate().scaleX(1f).scaleY(1f).rotation(0f).alpha(1f)
                        .setDuration(520)
                        .setInterpolator(new android.view.animation.OvershootInterpolator(2.4f)).start();
            }
        }

        boolean isSelected = selectedPaths.contains(song.getPath());
        Song current = PlayerManager.getInstance().getCurrentSong();
        boolean isPlayingThis = !selectionMode && current != null && current.getPath() != null && current.getPath().equals(song.getPath());

        int accentColor = ContextCompat.getColor(holder.itemView.getContext(), R.color.accent);
        int primaryTextColor = ContextCompat.getColor(holder.itemView.getContext(), R.color.text_primary);

        if (selectionMode) {
            // Selection mode UI
            holder.itemView.setBackgroundResource(isSelected ? R.drawable.bg_song_playing : 0);
            holder.txtTitle.setTextColor(isSelected ? accentColor : primaryTextColor);
            holder.viewPlayingOverlay.setVisibility(isSelected ? View.VISIBLE : View.GONE);
            holder.imgIndicator.setVisibility(isSelected ? View.VISIBLE : View.GONE);
            if (isSelected) holder.imgIndicator.setImageResource(R.drawable.ic_check);
            holder.btnFavorite.setVisibility(View.GONE);
            holder.btnMenu.setVisibility(View.GONE);
        } else {
            holder.itemView.setBackgroundResource(isPlayingThis ? R.drawable.bg_song_playing : 0);
            holder.imgIndicator.setImageResource(PlayerManager.getInstance().isPlaying() ? R.drawable.ic_pause : R.drawable.ic_play_arrow);
            holder.itemView.setContentDescription(song.getTitle() + (isPlayingThis ? " · Canción actual" : ""));
            if (isPlayingThis) {
                holder.txtTitle.setTextColor(accentColor);
                holder.viewPlayingOverlay.setVisibility(View.VISIBLE);
                holder.imgIndicator.setVisibility(View.VISIBLE);
            } else {
                holder.txtTitle.setTextColor(primaryTextColor);
                holder.viewPlayingOverlay.setVisibility(View.GONE);
                holder.imgIndicator.setVisibility(View.GONE);
            }
            holder.btnFavorite.setVisibility(View.VISIBLE);
            holder.btnMenu.setVisibility(View.VISIBLE);
        }

        boolean fav = PlaylistManager.getInstance(context).isFavorite(song.getPath());
        holder.btnFavorite.setImageResource(fav ? R.drawable.ic_favorite_filled : R.drawable.ic_favorite);

        holder.itemView.setOnClickListener(v -> {
            if (selectionMode) {
                toggleSelection(song);
            } else {
                if (listener != null) listener.onSongClick(song, displayedList, position);
            }
        });

        holder.itemView.setOnLongClickListener(v -> {
            if (!selectionMode) {
                activateSelectionMode(song);
                return true;
            }
            return false;
        });

        holder.btnFavorite.setOnClickListener(v -> {
            boolean nowFav = PlaylistManager.getInstance(context).toggleFavorite(song.getPath());
            holder.btnFavorite.setImageResource(nowFav ? R.drawable.ic_favorite_filled : R.drawable.ic_favorite);
            if (listener != null) listener.onFavoriteClick(song, position);
        });

        holder.btnMenu.setOnClickListener(v -> {
            if (listener != null) listener.onMenuClick(song, holder.btnMenu, position);
        });
        cl.arichard.descargador.util.SafeTap.install(holder.btnMenu);
        cl.arichard.descargador.util.SafeTap.install(holder.btnFavorite);
        cl.arichard.descargador.util.SafeTap.installRowTap(holder.itemView);
    }

    @Override public int getItemCount() { return displayedList.size(); }

    static class SongViewHolder extends RecyclerView.ViewHolder {
        TextView txtTitle, txtSubtitle;
        ImageView imgCover, imgIndicator, btnFavorite, btnMenu, imgDownloadedBadge;
        android.widget.ProgressBar progressDownload;
        View viewPlayingOverlay;

        public SongViewHolder(@NonNull View itemView) {
            super(itemView);
            txtTitle = itemView.findViewById(R.id.txtSongTitle);
            txtSubtitle = itemView.findViewById(R.id.txtSongSubtitle);
            imgCover = itemView.findViewById(R.id.imgSongCover);
            imgIndicator = itemView.findViewById(R.id.imgPlayingIndicator);
            viewPlayingOverlay = itemView.findViewById(R.id.viewPlayingOverlay);
            btnFavorite = itemView.findViewById(R.id.btnFavorite);
            btnMenu = itemView.findViewById(R.id.btnSongMenu);
            imgDownloadedBadge = itemView.findViewById(R.id.imgDownloadedBadge);
            progressDownload = itemView.findViewById(R.id.progressSongDownload);
        }
    }
}
