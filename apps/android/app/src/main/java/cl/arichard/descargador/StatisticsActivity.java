package cl.arichard.descargador;

import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;
import androidx.core.content.ContextCompat;
import cl.arichard.descargador.data.ClickStats;
import cl.arichard.descargador.data.MusicScanner;
import cl.arichard.descargador.data.PlaylistManager;
import cl.arichard.descargador.model.Song;
import cl.arichard.descargador.util.CoverArtLoader;
import cl.arichard.descargador.util.Tr;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Live statistics: keep the screen and keyed rows, update only changed values. */
public final class StatisticsActivity extends AppCompatActivity {
    private LinearLayout content, ranking;
    private TextView totalTime, totalPlays, rankingHeading, empty;
    private boolean playlists;
    private final Map<String, Song> library = new HashMap<>();
    private final Map<String, Row> rowsByKey = new HashMap<>();
    private List<JSONObject> songs = new ArrayList<>(), lists = new ArrayList<>();
    private final Runnable observer = () -> runOnUiThread(this::render);

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (state != null) playlists = state.getBoolean("playlists");
        build();
        ClickStats.getInstance(this).addObserver(observer);
        render();
        new Thread(() -> {
            List<Song> scanned = MusicScanner.scanAllMusic(getApplicationContext());
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                PlaylistManager manager = PlaylistManager.getInstance(this);
                for (Song song : scanned) {
                    library.put(ClickStats.keyOf(song), song);
                    String id = manager.videoIdFor(song.getPath());
                    if (id != null) library.put("yt:" + id, song);
                }
                render();
            });
        }, "statistics-covers").start();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putBoolean("playlists", playlists);
        super.onSaveInstanceState(state);
    }
    @Override protected void onDestroy() {
        ClickStats.getInstance(this).removeObserver(observer);
        super.onDestroy();
    }
    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private int color(int resource) { return ContextCompat.getColor(this, resource); }
    private TextView text(String value, int size, boolean bold, boolean muted) {
        TextView view = new TextView(this);
        view.setText(value); view.setTextSize(size);
        view.setTextColor(color(muted ? R.color.text_secondary : R.color.text_primary));
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }
    private static void updateText(TextView view, String value) {
        if (!value.contentEquals(view.getText())) view.setText(value);
    }
    public static String duration(long millis) {
        long seconds = Math.max(0, millis / 1000);
        if (seconds < 60) return seconds + " s";
        long minutes = seconds / 60;
        return minutes < 60 ? minutes + " min" : minutes / 60 + " h " + minutes % 60 + " min";
    }
    private void build() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(color(R.color.bg_primary));
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(22), dp(18), dp(22), dp(38));
        scroll.addView(content);
        setContentView(scroll);
        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        ImageButton back = new ImageButton(this);
        back.setImageResource(R.drawable.ic_arrow_back);
        back.setColorFilter(color(R.color.text_primary));
        back.setBackgroundResource(android.R.color.transparent);
        back.setContentDescription(Tr.t("Volver"));
        back.setOnClickListener(v -> finish());
        toolbar.addView(back, new LinearLayout.LayoutParams(dp(44), dp(48)));
        toolbar.addView(text(Tr.t("Tu música en cifras"), 24, true, false), new LinearLayout.LayoutParams(0, -2, 1));
        content.addView(toolbar);
        TextView subtitle = text(Tr.t("Cada reproducción cuenta"), 14, false, true);
        subtitle.setPadding(dp(4), dp(6), 0, dp(22)); content.addView(subtitle);
        LinearLayout metrics = new LinearLayout(this);
        totalTime = metric(metrics, Tr.t("Tiempo escuchado"));
        totalPlays = metric(metrics, Tr.t("Reproducciones"));
        content.addView(metrics);
        TextView note = text(Tr.t("El tiempo se mide desde esta actualización. Las reproducciones anteriores se conservan."), 12, false, true);
        note.setPadding(dp(4), dp(12), dp(4), dp(20)); content.addView(note);
        com.google.android.material.button.MaterialButtonToggleGroup tabs =
                new com.google.android.material.button.MaterialButtonToggleGroup(this);
        tabs.setSingleSelection(true); tabs.setSelectionRequired(true);
        for (int i = 0; i < 2; i++) {
            com.google.android.material.button.MaterialButton button = new com.google.android.material.button.MaterialButton(
                    this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
            button.setId(i == 0 ? android.R.id.button1 : android.R.id.button2);
            button.setText(Tr.t(i == 0 ? "Canciones" : "Playlists"));
            button.setAllCaps(false); button.setTextSize(14); button.setCornerRadius(dp(24)); button.setLetterSpacing(0);
            tabs.addView(button, new LinearLayout.LayoutParams(0, dp(52), 1));
        }
        tabs.check(playlists ? android.R.id.button2 : android.R.id.button1);
        tabs.addOnButtonCheckedListener((group, id, checked) -> {
            if (checked) { playlists = id == android.R.id.button2; renderRanking(); }
        });
        content.addView(tabs);
        rankingHeading = text("", 19, true, false);
        rankingHeading.setPadding(dp(2), dp(24), 0, dp(14)); content.addView(rankingHeading);
        empty = text("", 14, false, true);
        empty.setPadding(dp(8), dp(30), dp(8), dp(30)); content.addView(empty);
        ranking = new LinearLayout(this);
        ranking.setOrientation(LinearLayout.VERTICAL); content.addView(ranking);
    }
    private TextView metric(LinearLayout parent, String caption) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(20), dp(12), dp(20));
        card.setBackgroundResource(R.drawable.bg_settings_card);
        TextView value = text("", 23, true, false); card.addView(value);
        TextView label = text(caption, 12, false, true);
        label.setPadding(0, dp(5), 0, 0); card.addView(label);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -1, 1);
        if (parent.getChildCount() > 0) params.setMarginStart(dp(10));
        parent.addView(card, params);
        return value;
    }
    private void render() {
        if (isFinishing() || isDestroyed()) return;
        ClickStats stats = ClickStats.getInstance(this);
        songs = stats.listeningRows(false); lists = stats.listeningRows(true);
        long millis = 0;
        for (JSONObject row : songs) millis += row.optLong("ms");
        updateText(totalTime, duration(millis));
        updateText(totalPlays, String.valueOf(stats.total()));
        renderRanking();
    }
    private void renderRanking() {
        if (ranking == null) return;
        updateText(rankingHeading, Tr.t(playlists ? "Tus playlists más escuchadas" : "Tus canciones más escuchadas"));
        List<JSONObject> data = playlists ? lists : songs;
        empty.setVisibility(data.isEmpty() ? View.VISIBLE : View.GONE);
        updateText(empty, Tr.t(playlists ? "Escucha desde una playlist para ver aquí su actividad."
                : "Reproduce tu primera canción y empieza tu historia musical."));
        long maximum = data.isEmpty() ? 1 : Math.max(1, data.get(0).optLong("plays"));
        Set<String> retained = new HashSet<>();
        for (int index = 0; index < data.size(); index++) {
            JSONObject item = data.get(index);
            String key = (playlists ? "playlist:" : "song:") + item.optString("key");
            retained.add(key);
            Row row = rowsByKey.get(key);
            if (row == null) { row = new Row(); rowsByKey.put(key, row); }
            row.bind(item, index, maximum);
            if (ranking.indexOfChild(row.root) != index) {
                ranking.removeView(row.root);
                ranking.addView(row.root, index);
            }
        }
        for (String key : new ArrayList<>(rowsByKey.keySet())) if (!retained.contains(key))
            ranking.removeView(rowsByKey.remove(key).root);
    }
    private final class Row {
        final LinearLayout root = new LinearLayout(StatisticsActivity.this);
        final TextView rank = text("", 12, true, true), name = text("", 14, true, false),
                artist = text("", 12, false, true), detail = text("", 12, false, true);
        final ImageView image = new ImageView(StatisticsActivity.this);
        final View fill = new View(StatisticsActivity.this), remainder = new View(StatisticsActivity.this);
        private String coverPath;
        private float fraction = -1;
        Row() {
            root.setGravity(Gravity.CENTER_VERTICAL);
            root.setPadding(dp(12), dp(14), dp(12), dp(14));
            root.setBackgroundResource(R.drawable.bg_settings_card);
            root.addView(rank, new LinearLayout.LayoutParams(dp(26), -2));
            CardView cover = new CardView(StatisticsActivity.this);
            cover.setRadius(dp(13)); cover.setCardElevation(0);
            cover.setCardBackgroundColor(color(R.color.bg_elevated));
            image.setScaleType(ImageView.ScaleType.CENTER_CROP); image.setImageResource(R.drawable.ic_music_note);
            cover.addView(image, new FrameLayout.LayoutParams(-1, -1));
            root.addView(cover, new LinearLayout.LayoutParams(dp(58), dp(58)));
            LinearLayout words = new LinearLayout(StatisticsActivity.this);
            words.setOrientation(LinearLayout.VERTICAL); words.setPadding(dp(13), 0, 0, 0);
            name.setSingleLine(); name.setEllipsize(android.text.TextUtils.TruncateAt.END); words.addView(name);
            artist.setSingleLine(); artist.setEllipsize(android.text.TextUtils.TruncateAt.END); words.addView(artist);
            detail.setPadding(0, dp(5), 0, dp(7)); words.addView(detail);
            LinearLayout bar = new LinearLayout(StatisticsActivity.this);
            bar.setBackgroundColor(color(R.color.bg_elevated)); fill.setBackgroundColor(color(R.color.accent));
            bar.addView(fill, new LinearLayout.LayoutParams(0, dp(3), 0));
            bar.addView(remainder, new LinearLayout.LayoutParams(0, dp(3), 1));
            words.addView(bar, new LinearLayout.LayoutParams(-1, dp(3)));
            root.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
            params.bottomMargin = dp(10); root.setLayoutParams(params);
        }
        void bind(JSONObject data, int index, long maximum) {
            String key = data.optString("key"), title = data.optString("title"),
                    byline = data.optString("artist"), thumbnail = data.optString("thumbnail");
            Song local = playlists ? null : library.get(key);
            if (local != null) { title = local.getTitle(); byline = local.getArtist(); thumbnail = local.getCoverPath(); }
            if (playlists) {
                PlaylistManager manager = PlaylistManager.getInstance(StatisticsActivity.this);
                for (String entry : manager.getSongPaths(title)) {
                    cl.arichard.descargador.model.SearchResult online = manager.getOnlineTrack(entry);
                    if (online != null) { thumbnail = online.getThumbnailUrl(); break; }
                    for (Song candidate : library.values()) if (candidate.getPath().equals(entry)) {
                        thumbnail = candidate.getCoverPath(); break;
                    }
                    if (!thumbnail.isEmpty()) break;
                }
            }
            if (thumbnail.isEmpty() && key.startsWith("yt:"))
                thumbnail = "https://i.ytimg.com/vi/" + key.substring(3) + "/mqdefault.jpg";
            if (!thumbnail.equals(coverPath)) {
                coverPath = thumbnail;
                // The loader also cancels a recycled image's previous request for an empty path.
                CoverArtLoader.getInstance().loadSongCover(thumbnail, image);
            }
            updateText(rank, String.format(java.util.Locale.getDefault(), "%02d", index + 1));
            updateText(name, title); updateText(artist, byline);
            artist.setVisibility(byline.isEmpty() ? View.GONE : View.VISIBLE);
            updateText(detail, data.optLong("plays") + " " + Tr.t("reproducciones") + "  ·  " + duration(data.optLong("ms")));
            float next = Math.min(1f, Math.max(.015f, (float) data.optLong("plays") / maximum));
            if (next != fraction) {
                fraction = next;
                ((LinearLayout.LayoutParams) fill.getLayoutParams()).weight = next;
                ((LinearLayout.LayoutParams) remainder.getLayoutParams()).weight = 1 - next;
                fill.requestLayout(); remainder.requestLayout();
            }
        }
    }
}
