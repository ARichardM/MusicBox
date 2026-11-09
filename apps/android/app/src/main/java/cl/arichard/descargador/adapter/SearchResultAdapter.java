package cl.arichard.descargador.adapter;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
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
        void onMoreClick(SearchResult result, View anchor);
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
    private static final int TYPE_RESULT = 0, TYPE_LOADING_MORE = 1;
    /** Spinner al final de la lista mientras llega la página siguiente. */
    private boolean loadingMore;
    /** Desde esta posición, las filas recién agregadas entran con una animación; -1 si no hay ninguna. */
    private int appearFrom = -1;

    public SearchResultAdapter(Context context, OnResultClickListener listener) {
        this.context = context;
        this.listener = listener;
    }

    /** Pestaña de la búsqueda ("songs", "videos" o "all"): decide la forma del marco de la imagen. */
    private String filter = "songs";

    public void setFilter(String value) {
        String next = value == null ? "songs" : value;
        if (next.equals(filter)) return;
        filter = next;
        notifyDataSetChanged();
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
        List<SearchResult> next = newResults == null ? new ArrayList<>() : new ArrayList<>(newResults);
        int before = results.size();
        if (before > 0 && next.size() > before && startsWithCurrent(next)) {
            // Página siguiente: solo se insertan las filas nuevas, sin redibujar ni saltar lo que ya se ve.
            results.addAll(next.subList(before, next.size()));
            appearFrom = before;
            notifyItemRangeInserted(before, next.size() - before);
            return;
        }
        results.clear();
        results.addAll(next);
        appearFrom = -1;
        notifyDataSetChanged();
    }

    private boolean startsWithCurrent(List<SearchResult> next) {
        for (int i = 0; i < results.size(); i++)
            if (!results.get(i).getId().equals(next.get(i).getId())) return false;
        return true;
    }

    /** Muestra u oculta el spinner del final mientras se carga la página siguiente. */
    public void setLoadingMore(boolean loading) {
        if (loading == loadingMore) return;
        loadingMore = loading;
        if (loading) notifyItemInserted(results.size());
        else notifyItemRemoved(results.size());
    }

    public boolean isLoadingMore() { return loadingMore; }

    @Override
    public int getItemViewType(int position) {
        return position >= results.size() ? TYPE_LOADING_MORE : TYPE_RESULT;
    }
    public List<SearchResult> getResults() { return new ArrayList<>(results); }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (viewType == TYPE_LOADING_MORE) {
            float density = context.getResources().getDisplayMetrics().density;
            android.widget.FrameLayout footer = new android.widget.FrameLayout(context);
            footer.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.round(72 * density)));
            cl.arichard.descargador.util.LoadingRingView ring = new cl.arichard.descargador.util.LoadingRingView(context);
            ring.setId(R.id.searchLoadingMoreRing);
            int size = Math.round(30 * density);
            footer.addView(ring, new android.widget.FrameLayout.LayoutParams(size, size, android.view.Gravity.CENTER));
            return new ViewHolder(footer);
        }
        View view = LayoutInflater.from(context).inflate(R.layout.item_search_result, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position, @NonNull java.util.List<Object> payloads) {
        if (getItemViewType(position) == TYPE_LOADING_MORE) {
            cl.arichard.descargador.util.LoadingRingView ring = holder.itemView.findViewById(R.id.searchLoadingMoreRing);
            if (ring != null) ring.setActive(true);
            return;
        }
        if (payloads.contains(PROGRESS)) {
            Float fraction = downloadProgress.get(results.get(position).getId());
            holder.progressDownload.setProgress(fraction == null ? -1f : fraction);
            return;
        }
        super.onBindViewHolder(holder, position, payloads);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        if (getItemViewType(position) == TYPE_LOADING_MORE) return;
        SearchResult item = results.get(position);
        animateArrival(holder.itemView, position);
        holder.txtTitle.setText(item.getTitle());
        holder.txtChannel.setText(item.getChannel() + " • " + item.getFormattedDuration());

        // Canciones: marco cuadrado (la portada del álbum). Videos: 16:9. En "Todo" se distingue por la imagen.
        float density = context.getResources().getDisplayMetrics().density;
        boolean wide = "videos".equals(filter) || ("all".equals(filter) && item.getThumbnailUrl() != null
                && item.getThumbnailUrl().contains("ytimg.com/vi/"));
        ViewGroup.LayoutParams frame = holder.thumbFrame.getLayoutParams();
        int frameWidth = Math.round((wide ? 110 : 62) * density);
        if (frame.width != frameWidth) { frame.width = frameWidth; holder.thumbFrame.setLayoutParams(frame); }
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
                    cl.arichard.descargador.util.DownloadMark.reveal(holder.imgDownloaded);
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

        holder.btnMore.setOnClickListener(v -> {
            if (listener != null) listener.onMoreClick(item, holder.btnMore);
        });

        holder.btnPlay.setOnClickListener(v -> { if (listener != null) listener.onPlayClick(item); });
        cl.arichard.descargador.util.SafeTap.install(holder.btnPlay);
        cl.arichard.descargador.util.SafeTap.install(holder.btnFavorite);
        cl.arichard.descargador.util.SafeTap.install(holder.btnMore);
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

    /** Las filas de una página nueva suben y aparecen en cascada, sin cortes. */
    private void animateArrival(View row, int position) {
        row.animate().cancel();
        if (appearFrom < 0 || position < appearFrom) {
            row.setAlpha(1f);
            row.setTranslationY(0f);
            return;
        }
        float density = context.getResources().getDisplayMetrics().density;
        row.setAlpha(0f);
        row.setTranslationY(18 * density);
        row.animate().alpha(1f).translationY(0f).setDuration(280)
                .setStartDelay(Math.min(6, position - appearFrom) * 45L)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f)).start();
    }

    @Override
    public void onViewRecycled(@NonNull ViewHolder holder) {
        holder.itemView.animate().cancel();
        holder.itemView.setAlpha(1f);
        holder.itemView.setTranslationY(0f);
        super.onViewRecycled(holder);
    }

    @Override
    public int getItemCount() {
        return results.size() + (loadingMore ? 1 : 0);
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        ImageView imgThumbnail;
        TextView txtTitle;
        TextView txtChannel;
        View btnMore;
        ImageView btnPlay, btnFavorite, imgDownloaded;
        cl.arichard.descargador.util.LoadingRingView progressDownload;

        View thumbFrame;

        public ViewHolder(@NonNull View itemView) {
            super(itemView);
            thumbFrame = itemView.findViewById(R.id.thumbFrame);
            // Esquinas redondeadas del marco de la portada (recorta también la marca de descarga y el botón).
            final float radius = 10 * itemView.getResources().getDisplayMetrics().density;
            thumbFrame.setOutlineProvider(new android.view.ViewOutlineProvider() {
                @Override public void getOutline(View view, android.graphics.Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
                }
            });
            thumbFrame.setClipToOutline(true);
            imgThumbnail = itemView.findViewById(R.id.imgThumbnail);
            txtTitle = itemView.findViewById(R.id.txtResultTitle);
            txtChannel = itemView.findViewById(R.id.txtResultChannel);
            btnMore = itemView.findViewById(R.id.btnMoreResult);
            btnPlay = itemView.findViewById(R.id.btnPlayResult);
            btnFavorite = itemView.findViewById(R.id.btnFavoriteResult);
            imgDownloaded = itemView.findViewById(R.id.imgDownloadedResult);
            progressDownload = itemView.findViewById(R.id.progressDownloadResult);
        }
    }
}
