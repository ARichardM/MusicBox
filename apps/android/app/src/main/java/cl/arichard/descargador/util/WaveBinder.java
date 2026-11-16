package cl.arichard.descargador.util;

import android.content.Context;
import cl.arichard.descargador.model.Song;
import cl.arichard.descargador.player.PlayerManager;
import java.util.List;
import java.util.Objects;

/** Conecta una [WaveSeekBar] con el reproductor: pide el relieve de la canción y de la siguiente y marca la zona de mezcla. */
public final class WaveBinder {
    private final Context context;
    private final WaveSeekBar bar;
    private WaveformStore.Envelope current, next;
    private boolean requested;
    private Song boundSong;

    public WaveBinder(Context context, WaveSeekBar bar) {
        this.context = context;
        this.bar = bar;
    }

    public static boolean wanted(Context context) {
        return PlayerManager.getInstance().getCrossfadeSeconds() > 0
                && context.getSharedPreferences("player", Context.MODE_PRIVATE).getBoolean("crossfade_show_zone", true);
    }

    private Song nextInQueue() {
        PlayerManager player = PlayerManager.getInstance();
        List<Song> queue = player.getQueue();
        int index = player.getCurrentIndex() + 1;
        if (index >= queue.size()) index = player.getRepeatMode() == PlayerManager.REPEAT_ALL ? 0 : -1;
        return index < 0 || index >= queue.size() ? null : queue.get(index);
    }

    private static String keyOf(Song song) {
        return song == null ? "" : (song.getVideoId() != null && !song.getVideoId().isEmpty() ? song.getVideoId() : song.getPath());
    }

    private static boolean same(Song a, Song b) { return a != null && b != null && Objects.equals(a.getPath(), b.getPath()); }

    /** Fuerza a pedir de nuevo el relieve (p. ej. al activar el crossfade con una canción ya sonando). */
    public void reset() { requested = false; current = null; }

    /** Cambia la barra a [song]: usa lo que ya se sabe y, si hace falta, lo calcula en segundo plano. */
    public void bind(Song song) {
        WaveformStore store = WaveformStore.get(context);
        boundSong = song;
        requested = song != null && wanted(context);
        current = song == null ? null : store.peek(song);
        Song following = nextInQueue();
        next = following == null ? null : store.peek(following);
        bar.setEnvelope(current, next, keyOf(song));
        if (song == null || !wanted(context)) return;
        store.request(song, envelope -> {
            if (!same(boundSong, song)) return;
            current = envelope;
            bar.setEnvelope(envelope, next, keyOf(song));
        });
        if (following != null) store.request(following, envelope -> {
            if (!same(nextInQueue(), following)) return;
            next = envelope;
            bar.setEnvelope(current, envelope, keyOf(boundSong));
        });
    }

    /** Se llama con cada avance: ajusta el modo de la barra y, si hace falta, pide lo que falta. */
    public void update(int duration) {
        PlayerManager player = PlayerManager.getInstance();
        // Mientras suena la mezcla la barra se acerca al tramo final.
        bar.setMixZoom(player.isCrossfading());
        // En plena mezcla la cola ya apunta a la canción que entra: la barra conserva lo que mostraba de la que sale.
        if (player.isCrossfading()) return;
        boolean wanted = wanted(context);
        int zone = player.crossfadeZoneMs();
        float zoneFraction = zone > 0 && duration > 0 ? Math.min(1f, zone / (float) duration) : 0f;
        float endFraction = duration > 0 ? Math.min(1f, player.crossfadeEndMs() / (float) duration) : 1f;
        bar.setCrossfade(wanted, zoneFraction, endFraction);
        if (wanted && !requested) bind(player.getCurrentSong());
    }
}
