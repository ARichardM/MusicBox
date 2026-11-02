package cl.arichard.descargador.player;

import org.junit.Test;
import static org.junit.Assert.*;

/** La siguiente canción online se resuelve por adelantado según la cola y el modo de repetición. */
public class StreamPrefetchTest {
    @Test public void nextSongInTheQueue() {
        assertEquals(1, PlayerManager.followingIndex(0, 3, PlayerManager.REPEAT_OFF));
        assertEquals(2, PlayerManager.followingIndex(1, 3, PlayerManager.REPEAT_OFF));
    }

    @Test public void lastSongOnlyWrapsWhenRepeatingAll() {
        assertEquals(-1, PlayerManager.followingIndex(2, 3, PlayerManager.REPEAT_OFF));
        assertEquals(0, PlayerManager.followingIndex(2, 3, PlayerManager.REPEAT_ALL));
    }

    @Test public void repeatOneSingleSongAndInvalidPositionsPrefetchNothing() {
        assertEquals(-1, PlayerManager.followingIndex(0, 3, PlayerManager.REPEAT_ONE));
        assertEquals(-1, PlayerManager.followingIndex(0, 1, PlayerManager.REPEAT_ALL));
        assertEquals(-1, PlayerManager.followingIndex(-1, 3, PlayerManager.REPEAT_OFF));
        assertEquals(-1, PlayerManager.followingIndex(0, 0, PlayerManager.REPEAT_OFF));
    }
}
