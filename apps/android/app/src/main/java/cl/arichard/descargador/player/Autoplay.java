package cl.arichard.descargador.player;

import cl.arichard.descargador.model.SearchResult;
import cl.arichard.descargador.model.Song;
import cl.arichard.descargador.util.OnlineTrackMenu;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** Reglas de la reproducción automática: qué canciones relacionadas se agregan al final de la cola. */
final class Autoplay {
    static final int BATCH = 15;
    /** Más de diez minutos suele ser un mix o un directo, no una canción. */
    static final long MAX_SECONDS = 600;

    private Autoplay() {}

    /** Las relacionadas que aún no están en la cola, sin repetir y con duración de canción, hasta {@code BATCH}. */
    static List<Song> pick(List<SearchResult> related, Collection<String> queuedPaths) {
        List<Song> picked = new ArrayList<>();
        if (related == null) return picked;
        List<String> taken = new ArrayList<>(queuedPaths);
        for (SearchResult result : related) {
            if (result == null || result.getId().isEmpty()) continue;
            if (result.getDurationSeconds() > MAX_SECONDS) continue;
            Song song = OnlineTrackMenu.songOf(result);
            if (taken.contains(song.getPath())) continue;
            taken.add(song.getPath());
            picked.add(song);
            if (picked.size() >= BATCH) break;
        }
        return picked;
    }
}
