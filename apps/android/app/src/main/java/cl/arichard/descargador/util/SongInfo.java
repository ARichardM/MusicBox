package cl.arichard.descargador.util;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.media.MediaMetadataRetriever;
import android.text.format.DateFormat;
import android.text.format.Formatter;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.core.content.ContextCompat;
import cl.arichard.descargador.R;
import cl.arichard.descargador.data.PlaylistManager;
import cl.arichard.descargador.model.Song;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import java.io.File;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** «Información de la canción»: lo que la app sabe de ella y de dónde viene (archivo local o YouTube). */
public final class SongInfo {
    private SongInfo() {}

    /** Una línea de la ficha; {@code header} marca el título de una sección. */
    public static final class Row {
        public final String label, value;
        public final boolean header;
        Row(String label, String value, boolean header) { this.label = label; this.value = value; this.header = header; }
    }

    public static void show(Activity activity, Song song) {
        if (song == null) return;
        List<Row> rows = rows(activity, song);
        float density = activity.getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(22 * density);
        box.setPadding(pad, Math.round(8 * density), pad, Math.round(4 * density));
        for (Row row : rows) box.addView(view(activity, row, density));
        ScrollView scroll = new ScrollView(activity);
        scroll.addView(box);
        MaterialAlertDialogBuilder dialog = new MaterialAlertDialogBuilder(activity)
                .setTitle(Tr.t("Información de la canción")).setView(scroll)
                .setPositiveButton(Tr.t("Cerrar"), null);
        String link = link(activity, song);
        if (!link.isEmpty()) dialog.setNeutralButton(Tr.t("Copiar enlace"), (d, which) -> {
            ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText("MusicBox", link));
            Toast.makeText(activity, Tr.t("Enlace copiado"), Toast.LENGTH_SHORT).show();
        });
        dialog.show();
    }

    private static View view(Context context, Row row, float density) {
        TextView text = new TextView(context);
        if (row.header) {
            text.setText(row.label.toUpperCase(Locale.ROOT));
            text.setTextSize(11f);
            text.setLetterSpacing(.08f);
            text.setTypeface(null, android.graphics.Typeface.BOLD);
            text.setTextColor(AccentColors.getColor(context, R.color.accent));
            text.setPadding(0, Math.round(16 * density), 0, Math.round(2 * density));
            return text;
        }
        LinearLayout line = new LinearLayout(context);
        line.setOrientation(LinearLayout.VERTICAL);
        line.setPadding(0, Math.round(6 * density), 0, Math.round(6 * density));
        TextView label = new TextView(context);
        label.setText(row.label);
        label.setTextSize(12f);
        label.setTextColor(AccentColors.getColor(context, R.color.text_secondary));
        text.setText(row.value);
        text.setTextSize(15f);
        text.setGravity(Gravity.START);
        text.setTextIsSelectable(true);
        text.setTextColor(AccentColors.getColor(context, R.color.text_primary));
        line.addView(label);
        line.addView(text);
        return line;
    }

    /** Enlace de YouTube de la canción, si viene de allí o tiene una copia enlazada; vacío si es solo un archivo. */
    static String link(Context context, Song song) {
        String id = videoId(context, song);
        return id == null || id.isEmpty() ? "" : "https://www.youtube.com/watch?v=" + id;
    }

    private static String videoId(Context context, Song song) {
        if (song.isOnline()) return song.getVideoId();
        return PlaylistManager.getInstance(context).videoIdFor(song.getPath());
    }

    public static List<Row> rows(Context context, Song song) {
        List<Row> rows = new ArrayList<>();
        PlaylistManager playlists = PlaylistManager.getInstance(context);
        rows.add(header(Tr.t("En MusicBox")));
        add(rows, Tr.t("Título"), song.getTitle());
        add(rows, Tr.t("Artista"), song.getArtist());
        String album = song.getAlbum();
        if (album != null && !album.isEmpty() && !"YouTube".equals(album)) add(rows, Tr.t("Álbum"), album);
        add(rows, Tr.t("Duración"), song.getFormattedDuration());
        add(rows, Tr.t("Reproducciones"), String.valueOf(Clicks.of(context, song)));
        add(rows, Tr.t("Favorita"), Tr.t(playlists.isFavoriteSong(song) ? "Sí" : "No"));
        add(rows, Tr.t("Playlists"), playlistNames(playlists, song));

        File file = song.isOnline() ? null : new File(song.getPath());
        String id = videoId(context, song);
        boolean hasFile = file != null && file.isFile();
        add(rows, Tr.t("Estado"), Tr.t(hasFile ? "Guardada en el dispositivo" : "Solo en línea (se reproduce por internet)"));

        if (hasFile) {
            rows.add(header(Tr.t("Archivo")));
            add(rows, Tr.t("Ubicación"), file.getParent());
            add(rows, Tr.t("Nombre"), file.getName());
            add(rows, Tr.t("Formato"), extension(file.getName()));
            add(rows, Tr.t("Tamaño"), Formatter.formatFileSize(context, file.length()));
            add(rows, Tr.t("Calidad"), quality(file));
            add(rows, Tr.t("Fecha del archivo"), DateFormat.getMediumDateFormat(context).format(new Date(file.lastModified())));
        }
        if (id != null && !id.isEmpty()) {
            rows.add(header(Tr.t("Origen")));
            add(rows, Tr.t("Servicio"), "YouTube");
            add(rows, Tr.t("Canal o artista"), song.getArtist());
            add(rows, Tr.t("ID del video"), id);
            add(rows, Tr.t("Enlace"), "https://www.youtube.com/watch?v=" + id);
        }
        return rows;
    }

    private static Row header(String label) { return new Row(label, "", true); }

    private static void add(List<Row> rows, String label, String value) {
        if (value != null && !value.trim().isEmpty()) rows.add(new Row(label, value.trim(), false));
    }

    private static String playlistNames(PlaylistManager manager, Song song) {
        String key = manager.canonicalKey(song);
        List<String> names = new ArrayList<>();
        for (String name : manager.getPlaylistNames()) {
            List<String> paths = manager.getSongPaths(name);
            if (paths.contains(key) || paths.contains(song.getPath())) names.add(name);
        }
        return names.isEmpty() ? Tr.t("Ninguna") : android.text.TextUtils.join(", ", names);
    }

    static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 || dot == name.length() - 1 ? "" : name.substring(dot + 1).toUpperCase(Locale.ROOT);
    }

    /** «192 kbps · MP3»; vacío si el archivo no lo permite leer. */
    private static String quality(File file) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(file.getAbsolutePath());
            String bitrate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE);
            String mime = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE);
            return kbps(bitrate) + (mime == null || mime.isEmpty() ? "" : " · " + mime);
        } catch (Exception ignored) {
            return "";
        } finally {
            try { retriever.release(); } catch (Exception ignored) {}
        }
    }

    /** Convierte bits por segundo (texto) a «N kbps»; vacío si no es un número. */
    static String kbps(String bitsPerSecond) {
        try { return Math.round(Long.parseLong(bitsPerSecond) / 1000f) + " kbps"; }
        catch (Exception e) { return ""; }
    }
}
