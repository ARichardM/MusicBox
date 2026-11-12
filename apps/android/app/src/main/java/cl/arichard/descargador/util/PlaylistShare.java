package cl.arichard.descargador.util;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.view.View;
import androidx.core.content.FileProvider;
import cl.arichard.descargador.model.Song;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

public final class PlaylistShare {
    private PlaylistShare() {}

    public static void show(Activity activity, View noticeAnchor, String name, List<Song> songs) {
        if (songs.isEmpty()) {
            AppNotice.info(noticeAnchor, Tr.t("La playlist no tiene canciones para compartir."));
            return;
        }

        String filename = name.replaceAll("[<>:\"/\\\\|?*\\p{Cntrl}]", "_").trim();
        int codePoints = filename.codePointCount(0, filename.length());
        filename = filename.substring(0, filename.offsetByCodePoints(0, Math.min(48, codePoints)));
        File directory = new File(activity.getCacheDir(), "playlist_exports/" + UUID.randomUUID());
        File file = new File(directory, "MusicBox - " + filename + ".csv");
        try {
            if (!directory.mkdirs()) throw new IOException("Could not create playlist export directory");
            try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                    new FileOutputStream(file), StandardCharsets.UTF_8))) {
                writer.write("title,artist,album\r\n");
                for (Song song : songs) {
                    String artist = song.getArtist();
                    if (artist.equals(Tr.t("Artista desconocido")) || (song.isOnline() && artist.equals("YouTube"))) {
                        artist = "";
                    }
                    String album = song.getAlbum();
                    if (song.isOnline() || album.equals(Tr.t("Álbum desconocido"))) album = "";
                    writeRow(writer, song.getTitle(), artist, album);
                }
            }
            Uri uri = FileProvider.getUriForFile(activity, activity.getPackageName() + ".files", file);
            Intent send = new Intent(Intent.ACTION_SEND)
                    .setType("text/csv")
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .putExtra(Intent.EXTRA_SUBJECT, name)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            send.setClipData(ClipData.newRawUri(name, uri));
            activity.startActivity(Intent.createChooser(send, Tr.t("Compartir playlist (CSV)")));
        } catch (IOException | IllegalArgumentException | ActivityNotFoundException | SecurityException failure) {
            AppNotice.error(noticeAnchor, Tr.t("No se pudo compartir la playlist."));
        }
    }

    private static void writeRow(BufferedWriter writer, String title, String artist, String album) throws IOException {
        writer.write(csvField(title) + "," + csvField(artist) + "," + csvField(album) + "\r\n");
    }

    private static String csvField(String value) {
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }
}
