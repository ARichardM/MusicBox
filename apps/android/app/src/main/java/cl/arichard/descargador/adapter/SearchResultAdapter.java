package cl.arichard.descargador.adapter;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import cl.arichard.descargador.R;
import cl.arichard.descargador.model.SearchResult;
import cl.arichard.descargador.util.ImageLoader;
import cl.arichard.descargador.data.PlaylistManager;
import java.util.HashSet;
import java.util.Set;
import java.util.ArrayList;
import java.util.List;

public class SearchResultAdapter extends RecyclerView.Adapter<SearchResultAdapter.ViewHolder> {

    public interface OnResultClickListener {
        void onAddClick(SearchResult result, View anchor);
        void onPlayClick(SearchResult result);
        void onFavoriteChanged(SearchResult result, boolean favorite);
        void onItemClick(SearchResult result);
    }

    private final Context context;
    private final List<SearchResult> results = new ArrayList<>();
    private final OnResultClickListener listener;
    private final Set<String> downloadedIds = new HashSet<>();
    private final Set<String> downloadedTitles = new HashSet<>();
    private static final String PROGRESS = "progress";
    private final Set<String> downloadingIds = new HashSet<>();
    private final java.util.Map<String, Float> downloadProgress = new java.util.HashMap<>();
    private final Set<String> justCompletedIds = new HashSet<>();

    public SearchResultAdapter(Context context, OnResultClickListener listener) {
        this.context = context;
        this.listener = listener;
    }

    public void setDownloadedIds(Set<String> ids) {
        downloadedIds.clear();
        if (ids != null) downloadedIds.addAll(ids);
        notifyDataSetChanged();
    }

    public void setDownloadedTitles(Set<String> titles) {
        downloadedTitles.clear();
        if (titles != null) downloadedTitles.addAll(titles);
        notifyDataSetChanged();
    }

    public void setDownloadingIds(Set<String> ids) {
        downloadingIds.clear();
        if (ids != null) downloadingIds.addAll(ids);
        notifyDataSetChanged();
    }

    public void addDownloadingId(String id) {
        if (id != null) {
            downloadingIds.add(id);
            notifyDataSetChanged();
        }
    }

    /** Real progress (0 to 1) of the download of [id]; a negative value means "still connecting". */
    public void setDownloadProgress(String id, float fraction) {
        if (id == null) return;
        downloadProgress.put(id, fraction);
        for (int i = 0; i < results.size(); i++) {
            if (id.equals(results.get(i).getId())) { notifyItemChanged(i, PROGRESS); break; }
        }
    }

    public void removeDownloadingId(String id) {
        if (id != null) {
            downloadProgress.remove(id);
            downloadingIds.remove(id);
            notifyDataSetChanged();
        }
    }

    public void markDownloadCompleted(String id) {
        if (id != null) {
            downloadProgress.remove(id);
            downloadingIds.remove(id);
            downloadedIds.add(id);
            justCompletedIds.add(id);
            notifyDataSetChanged();
        }
    }

    private String normalize(String value) {
        return value == null ? "" : java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").replaceAll("[^a-zA-Z0-9]", "").toLowerCase(java.util.Locale.ROOT);
    }

    public void setResults(List<SearchResult> newResults) {
        results.clear();
        if (newResults != null) {
            results.addAll(newResults);
        }
        notifyDataSetChanged();
    }
    public List<SearchResult> getResults() { return new ArrayList<>(results); }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.item_search_result, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position, @NonNull java.util.List<Object> payloads) {
        if (payloads.contains(PROGRESS)) {
            Float fraction = downloadProgress.get(results.get(position).getId());
            holder.progressDownload.setProgress(fraction == null ? -1f : fraction);
            return;
        }
        super.onBindViewHolder(holder, position, payloads);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        SearchResult item = results.get(position);
        holder.txtTitle.setText(item.getTitle());
        holder.txtChannel.setText(item.getChannel() + " • " + item.getFormattedDuration());

        ImageLoader.getInstance().load(item.getThumbnailUrl(), holder.imgThumbnail);

        boolean isDownloading = downloadingIds.contains(item.getId());
        boolean isDownloaded = downloadedIds.contains(item.getId()) || downloadedTitles.contains(normalize(item.getTitle()));

        holder.imgDownloaded.animate().cancel();
        holder.imgDownloaded.setRotation(0f);
        if (isDownloading) {
            Float fraction = downloadProgress.get(item.getId());
            holder.progressDownload.setProgress(fraction == null ? -1f : fraction);
            holder.progressDownload.setActive(true);
            holder.imgDownloaded.setVisibility(View.GONE);
        } else {
            holder.progressDownload.setActive(false);
            if (isDownloaded) {
                holder.imgDownloaded.setVisibility(View.VISIBLE);
                if (justCompletedIds.remove(item.getId())) {
                    holder.imgDownloaded.setScaleX(0f);
                    holder.imgDownloaded.setScaleY(0f);
                    holder.imgDownloaded.setAlpha(0f);
                    holder.imgDownloaded.setRotation(-35f);
                    holder.imgDownloaded.animate()
                            .scaleX(1f).scaleY(1f).alpha(1f).rotation(0f)
                            .setDuration(520)
                            .setInterpolator(new android.view.animation.OvershootInterpolator(2.4f))
                            .start();
                } else {
                    holder.imgDownloaded.setScaleX(1f);
                    holder.imgDownloaded.setScaleY(1f);
                    holder.imgDownloaded.setAlpha(1f);
                }
            } else {
                holder.imgDownloaded.setVisibility(View.GONE);
            }
        }
        boolean favorite = PlaylistManager.getInstance(context).isOnlineTrackInPlaylist("Favoritos", item.getId());
        holder.btnFavorite.setImageResource(favorite ? R.drawable.ic_favorite_filled : R.drawable.ic_favorite);

        holder.btnAdd.setOnClickListener(v -> {
            if (listener != null) listener.onAddClick(item, holder.btnAdd);
        });

        holder.btnPlay.setOnClickListener(v -> { if (listener != null) listener.onPlayClick(item); });
        cl.arichard.descargador.util.SafeTap.install(holder.btnPlay);
        cl.arichard.descargador.util.SafeTap.install(holder.btnFavorite);
        cl.arichard.descargador.util.SafeTap.install(holder.btnAdd);
        cl.arichard.descargador.util.SafeTap.installRowTap(holder.itemView);
        holder.btnFavorite.setOnClickListener(v -> {
            PlaylistManager pm = PlaylistManager.getInstance(context);
            boolean exists = pm.isOnlineTrackInPlaylist("Favoritos", item.getId());
            if (exists) pm.removeSongFromPlaylist("Favoritos", "youtube:" + item.getId());
            else pm.addOnlineTrack("Favoritos", item);
            cl.arichard.descargador.util.SafeTap.pop(holder.btnFavorite, !exists);
            if (listener != null) listener.onFavoriteChanged(item, !exists);
            notifyItemChanged(holder.getBindingAdapterPosition());
        });

        holder.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onItemClick(item);
        });
    }

    @Override
    public int getItemCount() {
        return results.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        ImageView imgThumbnail;
        TextView txtTitle;
        TextView txtChannel;
        FrameLayout btnAdd;
        ImageView btnPlay, btnFavorite, imgDownloaded;
        cl.arichard.descargador.util.LoadingRingView progressDownload;

        public ViewHolder(@NonNull View itemView) {
            super(itemView);
            imgThumbnail = itemView.findViewById(R.id.imgThumbnail);
            txtTitle = itemView.findViewById(R.id.txtResultTitle);
            txtChannel = itemView.findViewById(R.id.txtResultChannel);
            btnAdd = itemView.findViewById(R.id.btnAddResult);
            btnPlay = itemView.findViewById(R.id.btnPlayResult);
            btnFavorite = itemView.findViewById(R.id.btnFavoriteResult);
            imgDownloaded = itemView.findViewById(R.id.imgDownloadedResult);
            progressDownload = itemView.findViewById(R.id.progressDownloadResult);
        }
    }
}
