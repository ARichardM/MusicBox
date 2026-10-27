package cl.arichard.descargador;

import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;
import androidx.core.content.ContextCompat;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import cl.arichard.descargador.model.SearchResult;
import cl.arichard.descargador.util.ImageLoader;
import cl.arichard.descargador.util.OnlineTrackMenu;
import cl.arichard.descargador.util.SafeTap;
import cl.arichard.descargador.util.ShimmerDrawable;
import cl.arichard.descargador.util.Tr;
import cl.arichard.descargador.util.YtMusicApi;

/**
 * Página del artista: foto con marco redondo, sus canciones más escuchadas, todas sus canciones y su discografía.
 * Tocar una canción reproduce la lista como cola; tocar un álbum abre sus pistas.
 */
public class ArtistActivity extends AppCompatActivity {
    public static final String EXTRA_ID = "artist_id", EXTRA_NAME = "artist_name", EXTRA_PHOTO = "artist_photo";

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private LinearLayout content;
    private YtMusicApi.ArtistPage page;
    private List<SearchResult> allSongs;
    private boolean loadingAll, showingAlbum, destroyed;

    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(ContextCompat.getColor(this, R.color.bg_primary));
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(20), dp(20), dp(60));
        scroll.addView(content, new FrameLayout.LayoutParams(-1, -2));
        setContentView(scroll);
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() { back(); }
        });
        loadArtist();
    }

    @Override protected void onDestroy() {
        destroyed = true;
        io.shutdownNow();
        super.onDestroy();
    }

    private void back() {
        if (showingAlbum && page != null) renderArtist();
        else finish();
    }

    // --- Piezas de interfaz ---

    private TextView label(String text, float size, boolean bold, int color) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(ContextCompat.getColor(this, color));
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private void backButton() {
        ImageView back = new ImageView(this);
        back.setImageResource(R.drawable.ic_arrow_back);
        back.setPadding(dp(11), dp(11), dp(11), dp(11));
        back.setBackgroundResource(R.drawable.bg_settings_icon_button);
        back.setContentDescription(Tr.t("Volver"));
        back.setOnClickListener(v -> back());
        SafeTap.install(back);
        content.addView(back, new LinearLayout.LayoutParams(dp(44), dp(44)));
    }

    private void begin() {
        content.removeAllViews();
        showingAlbum = false;
        backButton();
    }

    private void animateIn(View view, long delay) {
        view.setAlpha(0f);
        view.setTranslationY(dp(12));
        view.animate().alpha(1f).translationY(0f).setStartDelay(delay).setDuration(240)
                .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
    }

    private View shimmer(int width, int height, int radius) {
        CardView card = new CardView(this);
        card.setRadius(dp(radius));
        card.setCardElevation(0f);
        View fill = new View(this);
        fill.setBackground(new ShimmerDrawable(this));
        card.addView(fill, new FrameLayout.LayoutParams(-1, -1));
        card.setLayoutParams(new LinearLayout.LayoutParams(width, height));
        return card;
    }

    private void skeleton() {
        begin();
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(18), 0, dp(18));
        row.addView(shimmer(dp(124), dp(124), 62));
        LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        words.setPadding(dp(18), 0, 0, 0);
        LinearLayout.LayoutParams bar = new LinearLayout.LayoutParams(dp(170), dp(20));
        words.addView(shimmer(dp(170), dp(24), 8), bar);
        row.addView(words);
        content.addView(row);
        for (int i = 0; i < 5; i++) {
            LinearLayout line = new LinearLayout(this);
            line.setGravity(Gravity.CENTER_VERTICAL);
            line.setPadding(0, dp(7), 0, dp(7));
            line.addView(shimmer(dp(52), dp(52), 10));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(180), dp(16));
            lp.setMarginStart(dp(12));
            line.addView(shimmer(dp(180), dp(16), 8), lp);
            content.addView(line);
        }
    }

    private void message(String text, Runnable retry) {
        begin();
        TextView view = label(text, 15, false, R.color.text_secondary);
        view.setGravity(Gravity.CENTER);
        view.setPadding(0, dp(60), 0, dp(16));
        content.addView(view, new LinearLayout.LayoutParams(-1, -2));
        if (retry != null) {
            TextView button = pill(Tr.t("Reintentar"), false);
            button.setOnClickListener(v -> retry.run());
            content.addView(button, new LinearLayout.LayoutParams(-1, dp(52)));
        }
    }

    /** Botón redondeado: principal (relleno del color de acento) o secundario (de tarjeta). */
    private TextView pill(String text, boolean primary) {
        TextView button = new TextView(this);
        button.setText(text);
        button.setGravity(Gravity.CENTER);
        button.setTextSize(15);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setTextColor(primary ? 0xFFFFFFFF : ContextCompat.getColor(this, R.color.text_primary));
        button.setBackgroundResource(primary ? R.drawable.bg_button_accent : R.drawable.bg_button_secondary);
        SafeTap.install(button);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(52));
        lp.setMargins(0, dp(6), 0, dp(6));
        button.setLayoutParams(lp);
        return button;
    }

    /** Foto circular con un marco del color de acento. */
    private View circlePhoto(String url, int sizeDp, int ringDp) {
        FrameLayout frame = new FrameLayout(this);
        CardView card = new CardView(this);
        card.setRadius(dp(sizeDp) / 2f);
        card.setCardElevation(0f);
        card.setCardBackgroundColor(ContextCompat.getColor(this, R.color.bg_card));
        ImageView photo = new ImageView(this);
        photo.setScaleType(ImageView.ScaleType.CENTER_CROP);
        card.addView(photo, new FrameLayout.LayoutParams(-1, -1));
        if (url != null && !url.isEmpty()) ImageLoader.getInstance().load(url, photo);
        FrameLayout.LayoutParams inner = new FrameLayout.LayoutParams(dp(sizeDp), dp(sizeDp));
        frame.addView(card, inner);
        View ring = new View(this);
        GradientDrawable oval = new GradientDrawable();
        oval.setShape(GradientDrawable.OVAL);
        oval.setStroke(dp(ringDp), ContextCompat.getColor(this, R.color.accent));
        ring.setBackground(oval);
        frame.addView(ring, new FrameLayout.LayoutParams(dp(sizeDp), dp(sizeDp)));
        frame.setLayoutParams(new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)));
        return frame;
    }

    private void section(String title) {
        TextView view = label(title, 20, true, R.color.text_primary);
        view.setPadding(0, dp(22), 0, dp(10));
        content.addView(view, new LinearLayout.LayoutParams(-1, -2));
    }

    // --- Artista ---

    private void loadArtist() {
        skeleton();
        final String id = getIntent().getStringExtra(EXTRA_ID);
        io.execute(() -> {
            try {
                YtMusicApi.ArtistPage loaded = YtMusicApi.artistPage(id);
                runOnUiThread(() -> {
                    if (destroyed) return;
                    if (loaded.name.isEmpty() && loaded.topSongs.isEmpty()) { message(Tr.t("No se pudo cargar este artista. Revisa tu conexión."), this::loadArtist); return; }
                    page = loaded;
                    renderArtist();
                });
            } catch (Exception e) {
                runOnUiThread(() -> { if (!destroyed) message(Tr.t("No se pudo cargar este artista. Revisa tu conexión."), this::loadArtist); });
            }
        });
    }

    private String artistName() {
        String name = page == null ? "" : page.name;
        return name.isEmpty() ? String.valueOf(getIntent().getStringExtra(EXTRA_NAME)) : name;
    }

    private void renderArtist() {
        begin();
        String photo = page.photo.isEmpty() ? getIntent().getStringExtra(EXTRA_PHOTO) : page.photo;
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(0, dp(14), 0, dp(8));
        header.addView(circlePhoto(photo, 124, 3));
        LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        words.setPadding(dp(18), 0, 0, 0);
        words.addView(label(artistName(), 26, true, R.color.text_primary));
        if (!page.subscribers.isEmpty()) words.addView(label(Tr.f("%s suscriptores", page.subscribers), 13, false, R.color.text_secondary));
        header.addView(words, new LinearLayout.LayoutParams(0, -2, 1f));
        content.addView(header);
        animateIn(header, 0);

        if (!page.topSongs.isEmpty()) {
            TextView play = pill(Tr.t("Reproducir"), true);
            play.setOnClickListener(v -> play(allSongs != null ? allSongs : page.topSongs, 0));
            content.addView(play);
            section(Tr.t("Canciones más escuchadas"));
            addSongs(page.topSongs);
        }
        if (!page.songsPlaylist.isEmpty()) {
            if (allSongs == null) {
                TextView all = pill(loadingAll ? Tr.t("Cargando canciones…") : Tr.t("Ver todas sus canciones"), false);
                all.setEnabled(!loadingAll);
                all.setOnClickListener(v -> loadAll());
                content.addView(all);
            } else {
                section(Tr.t("Todas sus canciones") + "  ·  " + allSongs.size());
                addSongs(allSongs);
            }
        }
        carousel(Tr.t("Álbumes"), page.albums);
        carousel(Tr.t("Sencillos y EPs"), page.singles);
    }

    private void loadAll() {
        if (loadingAll || allSongs != null) return;
        loadingAll = true;
        renderArtist();
        io.execute(() -> {
            List<SearchResult> songs;
            try { songs = YtMusicApi.playlist(page.songsPlaylist, artistName()); } catch (Exception e) { songs = null; }
            final List<SearchResult> loaded = songs;
            runOnUiThread(() -> {
                if (destroyed) return;
                loadingAll = false;
                if (loaded != null && !loaded.isEmpty()) allSongs = loaded;
                if (!showingAlbum) renderArtist();
            });
        });
    }

    private void addSongs(List<SearchResult> songs) {
        for (int i = 0; i < songs.size(); i++) content.addView(songRow(songs, i));
    }

    private View songRow(List<SearchResult> songs, int index) {
        SearchResult song = songs.get(index);
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(7), 0, dp(7));
        CardView art = new CardView(this);
        art.setRadius(dp(10));
        art.setCardElevation(0f);
        art.setCardBackgroundColor(ContextCompat.getColor(this, R.color.bg_card));
        ImageView cover = new ImageView(this);
        cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        art.addView(cover, new FrameLayout.LayoutParams(-1, -1));
        ImageLoader.getInstance().load(song.getThumbnailUrl(), cover);
        row.addView(art, new LinearLayout.LayoutParams(dp(52), dp(52)));
        LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        words.setPadding(dp(12), 0, dp(8), 0);
        TextView title = label(song.getTitle(), 15, true, R.color.text_primary);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        words.addView(title);
        String duration = VideoSearchSheet.durationLabel(song.getDurationSeconds());
        TextView meta = label(song.getChannel() + (duration.isEmpty() ? "" : " · " + duration), 12, false, R.color.text_secondary);
        meta.setSingleLine(true);
        meta.setEllipsize(TextUtils.TruncateAt.END);
        words.addView(meta);
        row.addView(words, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView more = label("＋", 20, true, R.color.accent);
        more.setGravity(Gravity.CENTER);
        more.setContentDescription(Tr.t("Agregar a playlist"));
        more.setOnClickListener(v -> OnlineTrackMenu.show(this, song, v));
        SafeTap.install(more);
        row.addView(more, new LinearLayout.LayoutParams(dp(44), dp(44)));
        row.setOnClickListener(v -> play(songs, index));
        SafeTap.installRowTap(row);
        return row;
    }

    private void play(List<SearchResult> songs, int index) { OnlineTrackMenu.playList(this, artistName(), songs, index); }

    // --- Discografía ---

    private void carousel(String title, List<YtMusicApi.Album> albums) {
        if (albums.isEmpty()) return;
        section(title);
        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout strip = new LinearLayout(this);
        for (YtMusicApi.Album album : albums) strip.addView(albumCard(album));
        scroll.addView(strip);
        content.addView(scroll, new LinearLayout.LayoutParams(-1, -2));
    }

    private View albumCard(YtMusicApi.Album album) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(148), ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(dp(12));
        card.setLayoutParams(lp);
        card.addView(cover(album.thumbnail, 148, 16));
        TextView name = label(album.title, 13, true, R.color.text_primary);
        name.setMaxLines(2);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setPadding(dp(2), dp(8), dp(2), 0);
        card.addView(name);
        String sub = album.year + ("single".equals(album.kind) ? (album.year.isEmpty() ? "" : " · ") + Tr.t("Sencillo") : "");
        if (!sub.isEmpty()) {
            TextView meta = label(sub, 11, false, R.color.text_secondary);
            meta.setPadding(dp(2), 0, dp(2), 0);
            card.addView(meta);
        }
        card.setOnClickListener(v -> loadAlbum(album));
        SafeTap.installRowTap(card);
        return card;
    }

    private View cover(String url, int sizeDp, int radiusDp) {
        CardView art = new CardView(this);
        art.setRadius(dp(radiusDp));
        art.setCardElevation(0f);
        art.setCardBackgroundColor(ContextCompat.getColor(this, R.color.bg_card));
        ImageView image = new ImageView(this);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        art.addView(image, new FrameLayout.LayoutParams(-1, -1));
        if (url != null && !url.isEmpty()) ImageLoader.getInstance().load(url, image);
        art.setLayoutParams(new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)));
        return art;
    }

    private void loadAlbum(YtMusicApi.Album album) {
        skeleton();
        showingAlbum = true;
        io.execute(() -> {
            try {
                YtMusicApi.AlbumPage loaded = YtMusicApi.albumPage(album.browseId);
                runOnUiThread(() -> {
                    if (destroyed || !showingAlbum) return;
                    if (loaded.tracks.isEmpty()) { message(Tr.t("No se pudo cargar este álbum."), () -> loadAlbum(album)); showingAlbum = true; return; }
                    renderAlbum(album, loaded);
                });
            } catch (Exception e) {
                runOnUiThread(() -> { if (!destroyed) { message(Tr.t("No se pudo cargar este álbum."), () -> loadAlbum(album)); showingAlbum = true; } });
            }
        });
    }

    private void renderAlbum(YtMusicApi.Album album, YtMusicApi.AlbumPage loaded) {
        begin();
        showingAlbum = true;
        String title = loaded.title.isEmpty() ? album.title : loaded.title;
        String cover = loaded.thumbnail.isEmpty() ? album.thumbnail : loaded.thumbnail;
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(0, dp(14), 0, dp(8));
        header.addView(cover(cover, 132, 18));
        LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        words.setPadding(dp(16), 0, 0, 0);
        TextView name = label(title, 22, true, R.color.text_primary);
        name.setMaxLines(3);
        name.setEllipsize(TextUtils.TruncateAt.END);
        words.addView(name);
        String artist = loaded.artist.isEmpty() ? artistName() : loaded.artist;
        words.addView(label(artist + (loaded.year.isEmpty() ? "" : " · " + loaded.year), 13, false, R.color.text_secondary));
        header.addView(words, new LinearLayout.LayoutParams(0, -2, 1f));
        content.addView(header);
        animateIn(header, 0);
        TextView play = pill(Tr.t("Reproducir álbum"), true);
        play.setOnClickListener(v -> play(loaded.tracks, 0));
        content.addView(play);
        addSongs(loaded.tracks);
    }
}
