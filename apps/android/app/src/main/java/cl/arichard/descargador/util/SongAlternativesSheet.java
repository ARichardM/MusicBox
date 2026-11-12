package cl.arichard.descargador.util;

import android.app.Activity;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import cl.arichard.descargador.R;
import cl.arichard.descargador.VideoSearchSheet;
import cl.arichard.descargador.data.PlaylistManager;
import cl.arichard.descargador.importer.TextImport;
import cl.arichard.descargador.model.SearchResult;
import cl.arichard.descargador.model.Song;
import cl.arichard.descargador.player.PlayerManager;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * "Ver alternativas": otras versiones de una canción en YouTube Music y YouTube. Cada una se escucha sin tocar
 * la cola (la reproducción principal se pausa y se reanuda al cerrar) y puede reemplazar a la actual en todas
 * las playlists y en la cola. Si la canción no está en ninguna, el botón la guarda en una playlist.
 */
public final class SongAlternativesSheet {
    private static final String PROGRESS = "progress";

    private final Activity activity;
    private final Song song;
    private final Runnable onReplaced;
    private final PlayerManager player = PlayerManager.getInstance();
    private final List<SearchResult> results = new ArrayList<>();
    private final Set<String> replacedIds = new HashSet<>();
    private BottomSheetDialog dialog;
    private RecyclerView list;
    private ProgressBar loading;
    private TextView status;
    private PlayerManager.Preview preview;
    private boolean replaceable, replaced;
    private String activeId = "";
    private int activeState = PlayerManager.PREVIEW_ENDED, activePosition, activeDuration;
    private int generation;
    private final Adapter adapter = new Adapter();

    public static void show(Activity activity, Song song) { show(activity, song, null); }

    /** [onReplaced] se ejecuta tras reemplazar, para que la pantalla que llama se actualice. */
    public static void show(Activity activity, Song song, Runnable onReplaced) {
        if (activity == null || activity.isFinishing() || song == null) return;
        new SongAlternativesSheet(activity, song, onReplaced).open();
    }

    /** Si no es null, la hoja solo deja elegir una versión y la entrega aquí (revisión de una playlist importada). */
    private java.util.function.Consumer<SearchResult> picker;

    /** Muestra las versiones de {@code song} y entrega la que el usuario elija, sin tocar playlists ni cola. */
    public static void pick(Activity activity, Song song, java.util.function.Consumer<SearchResult> onPick) {
        if (activity == null || activity.isFinishing() || song == null || onPick == null) return;
        SongAlternativesSheet sheet = new SongAlternativesSheet(activity, song, null);
        sheet.picker = onPick;
        sheet.open();
    }

    private SongAlternativesSheet(Activity activity, Song song, Runnable onReplaced) {
        this.activity = activity; this.song = song; this.onReplaced = onReplaced;
    }

    private int dp(float value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }
    private int color(int id) { return AccentColors.getColor(activity, id); }

    private void open() {
        replaceable = picker == null && (!PlaylistManager.getInstance(activity).playlistsContaining(song.getPath()).isEmpty()
                || player.queueContains(song.getPath()));
        preview = player.newPreview(this::onPreview);
        dialog = new BottomSheetDialog(activity);
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12), dp(8), dp(12), dp(8));
        root.setBackgroundColor(color(R.color.bg_primary));
        View handle = new View(activity);
        handle.setBackgroundResource(R.drawable.bg_sheet_handle);
        LinearLayout.LayoutParams handleParams = new LinearLayout.LayoutParams(dp(40), dp(4));
        handleParams.gravity = Gravity.CENTER_HORIZONTAL;
        handleParams.bottomMargin = dp(8);
        root.addView(handle, handleParams);

        root.addView(originalRow());

        FrameLayout body = new FrameLayout(activity);
        list = new RecyclerView(activity);
        list.setLayoutManager(new LinearLayoutManager(activity));
        list.setAdapter(adapter);
        list.setAlpha(0f);
        if (list.getItemAnimator() != null) list.getItemAnimator().setChangeDuration(0);
        body.addView(list, new FrameLayout.LayoutParams(-1, -1));
        loading = new ProgressBar(activity);
        body.addView(loading, new FrameLayout.LayoutParams(dp(32), dp(32), Gravity.CENTER));
        status = new TextView(activity);
        status.setTextSize(14f);
        status.setGravity(Gravity.CENTER);
        status.setPadding(dp(24), 0, dp(24), 0);
        status.setTextColor(color(R.color.text_secondary));
        status.setVisibility(View.GONE);
        status.setOnClickListener(v -> load());
        body.addView(status, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));

        dialog.setContentView(root);
        root.getLayoutParams().height = Math.round(activity.getResources().getDisplayMetrics().heightPixels * .72f);
        BottomSheetBehavior<?> behavior = dialog.getBehavior();
        behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
        behavior.setSkipCollapsed(true);
        dialog.setOnDismissListener(d -> { generation++; preview.release(!replaced); });
        dialog.show();
        load();
    }

    /** Fila fija con la canción actual, para comparar: miniatura, título y artista. */
    private View originalRow() {
        LinearLayout column = new LinearLayout(activity);
        column.setOrientation(LinearLayout.VERTICAL);
        TextView heading = new TextView(activity);
        heading.setText(Tr.t("Alternativas"));
        heading.setTextSize(18f);
        heading.setTypeface(null, Typeface.BOLD);
        heading.setTextColor(color(R.color.text_primary));
        heading.setPadding(dp(4), 0, dp(4), dp(6));
        column.addView(heading);

        LinearLayout row = new LinearLayout(activity);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(8), dp(6), dp(10), dp(6));
        row.setBackgroundResource(R.drawable.bg_card);
        ImageView cover = rounded(dp(36), dp(6));
        CoverArtLoader.getInstance().loadSongCover(song.getCoverPath(), cover);
        row.addView(cover, new LinearLayout.LayoutParams(dp(36), dp(36)));
        LinearLayout texts = new LinearLayout(activity);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(dp(10), 0, dp(8), 0);
        TextView title = line(song.getTitle(), 13f, R.color.text_primary, true);
        TextView artist = line(song.getArtist() == null ? "" : song.getArtist(), 11f, R.color.text_secondary, false);
        texts.addView(title);
        texts.addView(artist);
        row.addView(texts, new LinearLayout.LayoutParams(0, -2, 1));
        TextView badge = new TextView(activity);
        badge.setText(Tr.t("Actual"));
        badge.setTextSize(11f);
        badge.setTextColor(color(R.color.accent));
        row.addView(badge);
        column.addView(row, new LinearLayout.LayoutParams(-1, -2));
        View gap = new View(activity);
        column.addView(gap, new LinearLayout.LayoutParams(-1, dp(6)));
        return column;
    }

    private TextView line(String value, float size, int colorRes, boolean bold) {
        TextView view = new TextView(activity);
        view.setText(value);
        view.setTextSize(size);
        view.setSingleLine(true);
        view.setEllipsize(android.text.TextUtils.TruncateAt.END);
        view.setTextColor(color(colorRes));
        if (bold) view.setTypeface(null, Typeface.BOLD);
        return view;
    }

    private ImageView rounded(int size, int radius) {
        ImageView image = new ImageView(activity);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setBackgroundResource(R.drawable.bg_cover_fallback);
        image.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline outline) { outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius); }
        });
        image.setClipToOutline(true);
        return image;
    }

    /** La mejor búsqueda para esta canción: título y artista, sin los datos que no aportan. */
    static String queryFor(Song song) {
        String artist = song.getArtist() == null ? "" : song.getArtist().trim();
        if (artist.isEmpty() || artist.equalsIgnoreCase("<unknown>") || artist.equalsIgnoreCase("unknown")) artist = "";
        return TextImport.songQuery(song.getTitle() == null ? "" : song.getTitle(), artist).trim();
    }

    /**
     * Deja las versiones de la misma cancion (mismo titulo o muy parecido), las mas parecidas primero.
     * Si ninguna se parece, devuelve la lista tal cual para no mostrar una hoja vacia.
     */
    static List<SearchResult> rankSimilar(List<SearchResult> items, String title) {
        String want = cl.arichard.descargador.importer.SongMatcher.normalize(title == null ? "" : title);
        if (want.isEmpty() || items.isEmpty()) return items;
        List<SearchResult> kept = new ArrayList<>();
        List<Double> scores = new ArrayList<>();
        for (SearchResult item : items) {
            String got = cl.arichard.descargador.importer.SongMatcher.normalize(item.getTitle());
            double score = got.contains(want) || want.contains(got) && !got.isEmpty() ? 1.0
                    : cl.arichard.descargador.importer.SongMatcher.ratio(want, got);
            if (score < .6) continue;
            int at = kept.size();
            while (at > 0 && scores.get(at - 1) < score) at--;
            kept.add(at, item);
            scores.add(at, score);
        }
        return kept.isEmpty() ? items : kept;
    }

    private void load() {
        final int mine = ++generation;
        status.setVisibility(View.GONE);
        loading.setVisibility(View.VISIBLE);
        final String query = queryFor(song);
        final String currentId = song.getVideoId();
        new Thread(() -> {
            List<SearchResult> found = new ArrayList<>();
            boolean failed = false;
            try { found.addAll(YtMusicApi.searchPage(query, "songs", null, 12).songs); } catch (Exception ignored) { failed = true; }
            try { found.addAll(YoutubeApi.search(query, 12)); failed = false; } catch (Exception ignored) { /* ya hay canciones de YouTube Music */ }
            List<SearchResult> usable = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            if (currentId != null) seen.add(currentId);
            for (SearchResult item : VideoSearchSheet.usable(found)) if (seen.add(item.getId())) usable.add(item);
            usable = rankSimilar(usable, song.getTitle());
            final List<SearchResult> shown = usable;
            final boolean error = failed && shown.isEmpty();
            activity.runOnUiThread(() -> {
                if (mine != generation || activity.isFinishing() || activity.isDestroyed()) return;
                render(shown, error);
            });
        }).start();
    }

    private void render(List<SearchResult> found, boolean error) {
        loading.setVisibility(View.GONE);
        if (found.isEmpty()) {
            status.setText(error ? Tr.t("No se pudieron cargar las alternativas. Toca para reintentar.")
                    : Tr.t("No encontramos otras versiones de esta canción."));
            status.setAlpha(0f);
            status.setVisibility(View.VISIBLE);
            status.animate().alpha(1f).setDuration(220).start();
            return;
        }
        results.clear();
        results.addAll(found);
        adapter.notifyDataSetChanged();
        list.setTranslationY(dp(12));
        list.animate().alpha(1f).translationY(0f).setDuration(280).setInterpolator(new DecelerateInterpolator()).start();
    }

    /** Estado de la escucha: actualiza solo la fila afectada (y solo su barra en cada avance). */
    private void onPreview(String videoId, int state, int position, int duration) {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        String previous = activeId;
        int previousState = activeState;
        boolean sameRow = videoId.equals(previous);
        if (state == PlayerManager.PREVIEW_ENDED || state == PlayerManager.PREVIEW_ERROR) {
            if (videoId.equals(activeId)) { activeId = ""; activeState = state; activePosition = 0; activeDuration = 0; }
        } else {
            activeId = videoId; activeState = state; activePosition = position; activeDuration = duration;
        }
        int index = indexOf(videoId);
        // En cada avance de la reproducción solo se mueve la barra; un cambio de estado repinta la fila.
        boolean onlyProgress = sameRow && state == PlayerManager.PREVIEW_PLAYING && previousState == PlayerManager.PREVIEW_PLAYING;
        if (index >= 0) adapter.notifyItemChanged(index, onlyProgress ? PROGRESS : null);
        if (!previous.isEmpty() && !sameRow && indexOf(previous) >= 0) adapter.notifyItemChanged(indexOf(previous));
        if (state == PlayerManager.PREVIEW_ERROR)
            android.widget.Toast.makeText(activity, Tr.t("No se pudo reproducir esta versión"), android.widget.Toast.LENGTH_SHORT).show();
    }

    private int indexOf(String id) {
        for (int i = 0; i < results.size(); i++) if (results.get(i).getId().equals(id)) return i;
        return -1;
    }

    private void onAction(SearchResult alternative) {
        if (picker != null) { preview.release(false); picker.accept(alternative); dialog.dismiss(); return; }
        if (!replaceable) { OnlineTrackMenu.addToPlaylist(activity, alternative); return; }
        new MaterialAlertDialogBuilder(activity)
                .setTitle(Tr.t("¿Reemplazar la canción?"))
                .setMessage(Tr.f("«%s» se cambiará por esta versión en tus playlists y en la cola. No se borra ningún archivo.", song.getTitle()))
                .setNegativeButton(Tr.t("Cancelar"), null)
                .setPositiveButton(Tr.t("Reemplazar"), (d, which) -> replaceWith(alternative))
                .show();
    }

    private void replaceWith(SearchResult alternative) {
        preview.release(false);
        PlaylistManager playlists = PlaylistManager.getInstance(activity);
        int inPlaylists = playlists.replaceEverywhere(song.getPath(), alternative);
        boolean inQueue = player.replaceQueueSong(song.getPath(), OnlineTrackMenu.songOf(alternative));
        replaced = true;
        replacedIds.add(alternative.getId());
        if (onReplaced != null) onReplaced.run();
        dialog.dismiss();
        String message = inPlaylists + (inQueue ? 1 : 0) > 0
                ? Tr.f("Reemplazada por «%s»", alternative.getTitle()) : Tr.t("No había nada que reemplazar");
        android.widget.Toast.makeText(activity, message, android.widget.Toast.LENGTH_SHORT).show();
    }

    private final class Adapter extends RecyclerView.Adapter<Holder> {
        @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            FrameLayout frame = new FrameLayout(activity);
            frame.setLayoutParams(new RecyclerView.LayoutParams(-1, dp(58)));
            LinearLayout row = new LinearLayout(activity);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(6), dp(5), dp(4), dp(5));
            ImageView cover = rounded(dp(44), dp(8));
            row.addView(cover, new LinearLayout.LayoutParams(dp(44), dp(44)));
            LinearLayout texts = new LinearLayout(activity);
            texts.setOrientation(LinearLayout.VERTICAL);
            texts.setPadding(dp(10), 0, dp(6), 0);
            TextView title = line("", 14f, R.color.text_primary, false);
            TextView meta = line("", 11f, R.color.text_secondary, false);
            texts.addView(title);
            texts.addView(meta);
            row.addView(texts, new LinearLayout.LayoutParams(0, -2, 1));

            FrameLayout play = new FrameLayout(activity);
            play.setBackgroundResource(R.drawable.bg_play_circle);
            ImageView icon = new ImageView(activity);
            icon.setColorFilter(0xFF111111);
            play.addView(icon, new FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER));
            ProgressBar spinner = new ProgressBar(activity);
            spinner.setVisibility(View.GONE);
            play.addView(spinner, new FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER));
            LinearLayout.LayoutParams playParams = new LinearLayout.LayoutParams(dp(34), dp(34));
            playParams.rightMargin = dp(6);
            row.addView(play, playParams);

            TextView action = new TextView(activity);
            action.setTextSize(12f);
            action.setGravity(Gravity.CENTER);
            action.setMinWidth(dp(76));
            action.setPadding(dp(10), 0, dp(10), 0);
            action.setSingleLine(true);
            row.addView(action, new LinearLayout.LayoutParams(-2, dp(32)));
            frame.addView(row, new FrameLayout.LayoutParams(-1, -1));

            View bar = new View(activity);
            bar.setBackgroundColor(color(R.color.accent));
            bar.setPivotX(0f);
            bar.setScaleX(0f);
            frame.addView(bar, new FrameLayout.LayoutParams(-1, dp(2), Gravity.BOTTOM));
            return new Holder(frame, cover, title, meta, play, icon, spinner, action, bar);
        }

        @Override public void onBindViewHolder(@NonNull Holder h, int position) { bind(h, position, true); }

        @Override public void onBindViewHolder(@NonNull Holder h, int position, @NonNull List<Object> payloads) {
            if (payloads.contains(PROGRESS)) { bar(h, results.get(position)); return; }
            bind(h, position, payloads.isEmpty());
        }

        private void bar(Holder h, SearchResult item) {
            boolean active = item.getId().equals(activeId);
            float fraction = active && activeDuration > 0 ? Math.min(1f, activePosition / (float) activeDuration) : 0f;
            h.bar.animate().cancel();
            h.bar.setScaleX(fraction);
        }

        private void bind(Holder h, int position, boolean full) {
            SearchResult item = results.get(position);
            boolean active = item.getId().equals(activeId);
            if (full) {
                h.title.setText(item.getTitle());
                String duration = VideoSearchSheet.durationLabel(item.getDurationSeconds());
                h.meta.setText(item.getChannel() + (duration.isEmpty() ? "" : " · " + duration));
                CoverArtLoader.getInstance().loadSongCover(item.getThumbnailUrl(), h.cover);
            }
            h.itemView.setBackgroundResource(active ? R.drawable.bg_song_playing : 0);
            boolean busy = active && activeState == PlayerManager.PREVIEW_LOADING;
            h.spinner.setVisibility(busy ? View.VISIBLE : View.GONE);
            h.icon.setVisibility(busy ? View.GONE : View.VISIBLE);
            h.icon.setImageResource(active && activeState == PlayerManager.PREVIEW_PLAYING ? R.drawable.ic_pause : R.drawable.ic_play_arrow);
            h.play.setContentDescription(Tr.t(active && activeState == PlayerManager.PREVIEW_PLAYING ? "Pausar" : "Escuchar"));
            bar(h, item);
            boolean done = replacedIds.contains(item.getId());
            h.action.setText(done ? Tr.t("✓ Lista") : picker != null ? Tr.t("Elegir") : replaceable ? Tr.t("Reemplazar") : Tr.t("Guardar"));
            h.action.setEnabled(!done);
            h.action.setTextColor(color(done ? R.color.text_secondary : R.color.accent));
            h.action.setBackgroundResource(R.drawable.bg_chip_inactive);
            View.OnClickListener toggle = v -> preview.toggle(item.getId());
            h.itemView.setOnClickListener(toggle);
            h.play.setOnClickListener(toggle);
            h.action.setOnClickListener(v -> onAction(item));
            SafeTap.installRowTap(h.itemView);
        }

        @Override public int getItemCount() { return results.size(); }
    }

    private static final class Holder extends RecyclerView.ViewHolder {
        final ImageView cover, icon;
        final TextView title, meta, action;
        final View play, bar;
        final ProgressBar spinner;
        Holder(View view, ImageView cover, TextView title, TextView meta, View play, ImageView icon, ProgressBar spinner, TextView action, View bar) {
            super(view);
            this.cover = cover; this.title = title; this.meta = meta; this.play = play; this.icon = icon;
            this.spinner = spinner; this.action = action; this.bar = bar;
        }
    }
}
