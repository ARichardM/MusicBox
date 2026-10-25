package cl.arichard.descargador.util;

import android.content.Context;

import java.util.List;

import cl.arichard.descargador.data.ClickStats;
import cl.arichard.descargador.model.Song;

/** "Clics": how many times songs were played, shown like YouTube views. */
public final class Clicks {
    private Clicks() {}

    public static int of(Context context, Song song) {
        return ClickStats.getInstance(context).count(song);
    }

    public static int of(Context context, List<Song> songs) {
        return ClickStats.getInstance(context).count(songs);
    }

    /** " • ▶ 1.2K" or "" when never played. */
    public static String suffix(int count) {
        return count <= 0 ? "" : " • ▶ " + format(count);
    }

    public static String format(int count) {
        if (count < 1000) return String.valueOf(count);
        if (count < 1_000_000) return count % 1000 < 100 || count >= 100_000 ? (count / 1000) + "K" : String.format(java.util.Locale.US, "%.1fK", count / 1000f);
        return String.format(java.util.Locale.US, "%.1fM", count / 1_000_000f);
    }
}
