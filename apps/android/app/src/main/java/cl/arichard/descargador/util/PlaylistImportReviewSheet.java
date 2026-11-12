package cl.arichard.descargador.util;

import android.app.Activity;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import cl.arichard.descargador.DescargadorApp;
import cl.arichard.descargador.R;
import cl.arichard.descargador.VideoSearchSheet;
import cl.arichard.descargador.data.PlaylistManager;
import cl.arichard.descargador.model.SearchResult;
import cl.arichard.descargador.model.Song;
import cl.arichard.descargador.player.PlayerManager;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import java.util.ArrayList;
import java.util.List;

/**
 * Revisión de una playlist leída de un enlace, antes de crearla: cada canción con portada, título, artista y
 * duración, para escucharla, ver alternativas o descartarla. Las que no traen video (Spotify, Deezer…) se buscan
 * en YouTube aquí mismo; si la coincidencia no es segura quedan fuera hasta que el usuario elija una versión.
 */
public final class PlaylistImportReviewSheet {
    private static final String PROGRESS = "progress";

    /** Playlist importada desde una pantalla que no es Principal: Principal la muestra (con rebote) al volver. */
    public static volatile String pendingReveal = "";

    private static final class Item {
        final PlaylistImport.Track track;
        SearchResult video;
        boolean resolving, discarded;
        /** El video es solo el mejor candidato: entra a la playlist, pero conviene comparar con las alternativas. */
        boolean uncertain;
        Item(PlaylistImport.Track track) { this.track = track; this.video = track.direct; this.resolving = track.direct == null; }
        boolean included() { return video != null && !discarded; }
    }

    private final Activity activity;
    private final PlaylistImport.Playlist playlist;
    private final List<Item> items = new ArrayList<>();
    private final PlayerManager.Preview preview;
    private final Adapter adapter = new Adapter();
    private BottomSheetDialog dialog;
    private EditText name;
    private TextView summary;
    private MaterialButton importButton;
    private volatile boolean closed;
    private String activeId = "";
    private int activeState = PlayerManager.PREVIEW_ENDED, activePosition, activeDuration;
    private int pendingSearches;

    public static void show(Activity activity, PlaylistImport.Playlist playlist) {
        if (activity == null || activity.isFinishing() || playlist == null) return;
        new PlaylistImportReviewSheet(activity, playlist).open();
    }

    private PlaylistImportReviewSheet(Activity activity, PlaylistImport.Playlist playlist) {
        this.activity = activity;
        this.playlist = playlist;
        for (PlaylistImport.Track track : playlist.tracks) items.add(new Item(track));
        this.preview = PlayerManager.getInstance().newPreview(this::onPreview);
    }

    private int dp(float value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }
    private int color(int id) { return AccentColors.getColor(activity, id); }

    private void open() {
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

        TextView heading = text(Tr.t("Importar playlist"), 18f, R.color.text_primary, true);
        heading.setPadding(dp(4), 0, dp(4), 0);
        root.addView(heading);
        summary = text("", 12f, R.color.text_secondary, false);
        summary.setPadding(dp(4), dp(2), dp(4), dp(6));
        root.addView(summary);

        name = new EditText(activity);
        name.setSingleLine(true);
        name.setHint(Tr.t("Nombre de la playlist"));
        name.setText(playlist.name);
        name.setSelection(name.length());
        root.addView(name, new LinearLayout.LayoutParams(-1, -2));

        RecyclerView list = new RecyclerView(activity);
        list.setLayoutManager(new LinearLayoutManager(activity));
        list.setAdapter(adapter);
        if (list.getItemAnimator() != null) list.getItemAnimator().setChangeDuration(0);
        LinearLayout.LayoutParams listParams = new LinearLayout.LayoutParams(-1, 0, 1);
        listParams.topMargin = dp(4);
        root.addView(list, listParams);

        importButton = new MaterialButton(activity);
        importButton.setOnClickListener(v -> importSelected());
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(-1, dp(52));
        buttonParams.topMargin = dp(6);
        root.addView(importButton, buttonParams);

        dialog.setContentView(root);
        root.getLayoutParams().height = Math.round(activity.getResources().getDisplayMetrics().heightPixels * .9f);
        BottomSheetBehavior<?> behavior = dialog.getBehavior();
        behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
        behavior.setSkipCollapsed(true);
        behavior.setDraggable(false);   // arrastrar la lista no debe cerrar la hoja por accidente
        dialog.setOnDismissListener(d -> { closed = true; preview.release(true); });
        dialog.show();
        refreshSummary();
        resolveMissing();
    }

    /** Busca en YouTube, de una en una, las canciones que no traían video. */
    private void resolveMissing() {
        final List<Item> missing = new ArrayList<>();
        for (Item item : items) if (item.resolving) missing.add(item);
        pendingSearches = missing.size();
        if (missing.isEmpty()) return;
        DescargadorApp.IO.execute(() -> {
            for (Item item : missing) {
                if (closed) return;
                PlaylistImport.Resolved found = null;
                // Un fallo de red no debe dejar una canción fuera: se reintenta antes de rendirse.
                for (int attempt = 0; attempt < 3 && found == null && !closed; attempt++) {
                    try { found = PlaylistImport.resolve(item.track); break; }
                    catch (Exception network) { try { Thread.sleep(700L * (attempt + 1)); } catch (InterruptedException stop) { return; } }
                }
                final PlaylistImport.Resolved result = found;
                activity.runOnUiThread(() -> {
                    if (closed || activity.isDestroyed()) return;
                    item.resolving = false;
                    if (item.video == null && result != null) {   // el usuario pudo elegir una a mano mientras tanto
                        item.video = result.video;
                        item.uncertain = !result.confident;
                    }
                    pendingSearches--;
                    int index = items.indexOf(item);
                    if (index >= 0) adapter.notifyItemChanged(index);
                    refreshSummary();
                });
            }
        });
    }

    private int includedCount() {
        int count = 0;
        for (Item item : items) if (item.included()) count++;
        return count;
    }

    private void refreshSummary() {
        int unmatched = 0, doubtful = 0;
        for (Item item : items) {
            if (item.discarded) continue;
            if (!item.resolving && item.video == null) unmatched++;
            else if (item.uncertain) doubtful++;
        }
        String text = Tr.f("%d canciones de %s", items.size(), playlist.platform.label);
        if (pendingSearches > 0) text += " · " + Tr.f("buscando coincidencias… faltan %d", pendingSearches);
        else {
            if (doubtful > 0) text += " · " + Tr.f("%d por revisar", doubtful);
            if (unmatched > 0) text += " · " + Tr.f("%d sin resultado", unmatched);
        }
        summary.setText(text);
        int count = includedCount();
        importButton.setText(count == 0 ? Tr.t("No hay canciones para importar") : Tr.f("Importar %d canciones", count));
        importButton.setEnabled(count > 0);
    }

    private void importSelected() {
        String target = name.getText().toString().trim();
        if (target.isEmpty()) { name.setError(Tr.t("Escribe un nombre")); return; }
        final List<SearchResult> chosen = new ArrayList<>();
        for (Item item : items) if (item.included()) chosen.add(item.video);
        if (chosen.isEmpty()) return;
        importButton.setEnabled(false);
        final PlaylistManager playlists = PlaylistManager.getInstance(activity);
        DescargadorApp.IO.execute(() -> {
            if (!playlists.getPlaylistNames().contains(target)) playlists.createPlaylist(target);
            int added = 0;
            for (SearchResult video : chosen) if (playlists.addOnlineTrack(target, video)) added++;
            final int total = added;
            activity.runOnUiThread(() -> {
                if (dialog.isShowing()) dialog.dismiss();
                // La biblioteca se entera ya: si es la pantalla actual, la lista se actualiza y la nueva rebota.
                if (activity instanceof cl.arichard.descargador.MainActivity)
                    ((cl.arichard.descargador.MainActivity) activity).onPlaylistImported(target);
                else pendingReveal = target;   // otra pantalla: Principal la muestra al volver
                if (activity.isFinishing() || activity.isDestroyed()) return;
                AppNotice.info(activity.findViewById(android.R.id.content), Tr.f("Playlist «%s» creada con %d canciones", target, total));
            });
        });
    }

    private void discard(Item item, boolean value) {
        item.discarded = value;
        int index = items.indexOf(item);
        if (index >= 0) adapter.notifyItemChanged(index);
        refreshSummary();
    }

    private void showAlternatives(Item item) {
        preview.release(false);
        Song song = item.video != null ? OnlineTrackMenu.songOf(item.video)
                : new Song(0, item.track.title, item.track.artist, "YouTube", item.track.seconds * 1000L, "pending:" + item.track.title, "YouTube", 0);
        SongAlternativesSheet.pick(activity, song, chosen -> {
            item.video = chosen;
            item.uncertain = false;   // el usuario la eligió a mano
            item.resolving = false;
            item.discarded = false;
            int index = items.indexOf(item);
            if (index >= 0) adapter.notifyItemChanged(index);
            refreshSummary();
        });
    }

    private void onPreview(String videoId, int state, int position, int duration) {
        if (closed || activity.isFinishing() || activity.isDestroyed()) return;
        String previous = activeId;
        boolean sameRow = videoId.equals(previous);
        boolean wasPlaying = activeState == PlayerManager.PREVIEW_PLAYING;
        if (state == PlayerManager.PREVIEW_ENDED || state == PlayerManager.PREVIEW_ERROR) {
            if (videoId.equals(activeId)) { activeId = ""; activeState = state; activePosition = 0; activeDuration = 0; }
        } else {
            activeId = videoId; activeState = state; activePosition = position; activeDuration = duration;
        }
        int index = indexOfVideo(videoId);
        boolean onlyProgress = sameRow && state == PlayerManager.PREVIEW_PLAYING && wasPlaying;
        if (index >= 0) adapter.notifyItemChanged(index, onlyProgress ? PROGRESS : null);
        if (!previous.isEmpty() && !sameRow && indexOfVideo(previous) >= 0) adapter.notifyItemChanged(indexOfVideo(previous));
        if (state == PlayerManager.PREVIEW_ERROR)
            android.widget.Toast.makeText(activity, Tr.t("No se pudo reproducir esta versión"), android.widget.Toast.LENGTH_SHORT).show();
    }

    private int indexOfVideo(String id) {
        for (int i = 0; i < items.size(); i++) if (items.get(i).video != null && items.get(i).video.getId().equals(id)) return i;
        return -1;
    }

    private TextView text(String value, float size, int colorRes, boolean bold) {
        TextView view = new TextView(activity);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color(colorRes));
        if (bold) view.setTypeface(null, Typeface.BOLD);
        return view;
    }

    private ImageView rounded(int radius) {
        ImageView image = new ImageView(activity);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setBackgroundResource(R.drawable.bg_cover_fallback);
        image.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline outline) { outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius); }
        });
        image.setClipToOutline(true);
        return image;
    }

    private ImageView iconButton(int drawable, String description) {
        ImageView button = new ImageView(activity);
        button.setImageResource(drawable);
        button.setColorFilter(color(R.color.text_secondary));
        button.setPadding(dp(8), dp(8), dp(8), dp(8));
        button.setContentDescription(description);
        return button;
    }

    private final class Adapter extends RecyclerView.Adapter<Holder> {
        @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            FrameLayout frame = new FrameLayout(activity);
            frame.setLayoutParams(new RecyclerView.LayoutParams(-1, -2));
            LinearLayout row = new LinearLayout(activity);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(6), dp(6), dp(2), dp(6));
            ImageView cover = rounded(dp(8));
            row.addView(cover, new LinearLayout.LayoutParams(dp(48), dp(48)));

            LinearLayout texts = new LinearLayout(activity);
            texts.setOrientation(LinearLayout.VERTICAL);
            texts.setPadding(dp(10), 0, dp(6), 0);
            TextView title = text("", 14f, R.color.text_primary, false);
            title.setMaxLines(2);
            title.setEllipsize(TextUtils.TruncateAt.END);
            TextView meta = text("", 11f, R.color.text_secondary, false);
            meta.setSingleLine(true);
            meta.setEllipsize(TextUtils.TruncateAt.END);
            TextView status = text("", 11f, R.color.accent, false);
            status.setSingleLine(true);
            status.setEllipsize(TextUtils.TruncateAt.END);
            texts.addView(title);
            texts.addView(meta);
            texts.addView(status);
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
            playParams.rightMargin = dp(2);
            row.addView(play, playParams);

            ImageView alternatives = iconButton(R.drawable.ic_refresh, Tr.t("Ver alternativas"));
            row.addView(alternatives, new LinearLayout.LayoutParams(dp(38), dp(38)));
            ImageView remove = iconButton(R.drawable.ic_close, Tr.t("Descartar"));
            row.addView(remove, new LinearLayout.LayoutParams(dp(38), dp(38)));
            TextView restore = text(Tr.t("Restaurar"), 12f, R.color.accent, false);
            restore.setGravity(Gravity.CENTER);
            restore.setPadding(dp(12), dp(8), dp(12), dp(8));
            restore.setVisibility(View.GONE);
            row.addView(restore, new LinearLayout.LayoutParams(-2, -2));
            frame.addView(row, new FrameLayout.LayoutParams(-1, -2));

            View bar = new View(activity);
            bar.setBackgroundColor(color(R.color.accent));
            bar.setPivotX(0f);
            bar.setScaleX(0f);
            frame.addView(bar, new FrameLayout.LayoutParams(-1, dp(2), Gravity.BOTTOM));
            return new Holder(frame, row, cover, title, meta, status, play, icon, spinner, alternatives, remove, restore, bar);
        }

        @Override public void onBindViewHolder(@NonNull Holder h, int position) { bind(h, position); }

        @Override public void onBindViewHolder(@NonNull Holder h, int position, @NonNull List<Object> payloads) {
            if (payloads.contains(PROGRESS)) { bar(h, items.get(position)); return; }
            bind(h, position);
        }

        private void bar(Holder h, Item item) {
            boolean active = item.video != null && item.video.getId().equals(activeId);
            h.bar.setScaleX(active && activeDuration > 0 ? Math.min(1f, activePosition / (float) activeDuration) : 0f);
        }

        private void bind(Holder h, int position) {
            Item item = items.get(position);
            PlaylistImport.Track track = item.track;
            h.title.setText(item.video != null ? item.video.getTitle() : track.title);
            String artist = item.video != null && !item.video.getChannel().isEmpty() ? item.video.getChannel() : track.artist;
            long seconds = item.video != null && item.video.getDurationSeconds() > 0 ? item.video.getDurationSeconds() : track.seconds;
            String duration = VideoSearchSheet.durationLabel(seconds);
            h.meta.setText(artist + (artist.isEmpty() || duration.isEmpty() ? "" : " · ") + duration);
            if (item.video != null) CoverArtLoader.getInstance().loadSongCover(item.video.getThumbnailUrl(), h.cover);
            else h.cover.setImageDrawable(null);

            h.status.setVisibility(View.VISIBLE);
            if (item.discarded) h.status.setText(Tr.t("Descartada"));
            else if (item.resolving) h.status.setText(Tr.t("Buscando en YouTube…"));
            else if (item.video == null) h.status.setText(Tr.t("Sin resultado en YouTube · busca una alternativa"));
            else if (item.uncertain) h.status.setText(Tr.f("Por revisar · pedida: %s", track.artist.isEmpty() ? track.title : track.artist + " – " + track.title));
            else h.status.setVisibility(View.GONE);

            boolean hasVideo = item.video != null;
            boolean active = hasVideo && item.video.getId().equals(activeId);
            boolean busy = active && activeState == PlayerManager.PREVIEW_LOADING;
            boolean off = item.discarded;
            h.row.setAlpha(off ? .45f : 1f);
            h.play.setVisibility(off || !hasVideo ? View.GONE : View.VISIBLE);
            h.alternatives.setVisibility(off || item.resolving ? View.GONE : View.VISIBLE);
            h.remove.setVisibility(off ? View.GONE : View.VISIBLE);
            h.restore.setVisibility(off ? View.VISIBLE : View.GONE);
            h.spinner.setVisibility(busy ? View.VISIBLE : View.GONE);
            h.icon.setVisibility(busy ? View.GONE : View.VISIBLE);
            h.icon.setImageResource(active && activeState == PlayerManager.PREVIEW_PLAYING ? R.drawable.ic_pause : R.drawable.ic_play_arrow);
            h.play.setContentDescription(Tr.t(active && activeState == PlayerManager.PREVIEW_PLAYING ? "Pausar" : "Escuchar"));
            h.itemView.setBackgroundResource(active ? R.drawable.bg_song_playing : 0);
            bar(h, item);

            View.OnClickListener toggle = v -> { if (item.video != null) preview.toggle(item.video.getId()); };
            h.play.setOnClickListener(toggle);
            h.itemView.setOnClickListener(item.discarded ? null : toggle);
            h.itemView.setClickable(!item.discarded);
            h.alternatives.setOnClickListener(v -> showAlternatives(item));
            h.remove.setOnClickListener(v -> discard(item, true));
            h.restore.setOnClickListener(v -> discard(item, false));
        }

        @Override public int getItemCount() { return items.size(); }
    }

    private static final class Holder extends RecyclerView.ViewHolder {
        final View row, play, bar;
        final ImageView cover, icon, alternatives, remove;
        final TextView title, meta, status, restore;
        final ProgressBar spinner;
        Holder(View view, View row, ImageView cover, TextView title, TextView meta, TextView status, View play, ImageView icon,
               ProgressBar spinner, ImageView alternatives, ImageView remove, TextView restore, View bar) {
            super(view);
            this.row = row; this.cover = cover; this.title = title; this.meta = meta; this.status = status; this.play = play;
            this.icon = icon; this.spinner = spinner; this.alternatives = alternatives; this.remove = remove;
            this.restore = restore; this.bar = bar;
        }
    }
}
