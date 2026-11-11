package cl.arichard.descargador.adapter;

import android.app.Application;
import java.util.Arrays;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class PlaylistAdapterOrderTest {
    @Test public void movingFilteredRowsKeepsHiddenRowsAndStableIds() {
        PlaylistAdapter adapter = new PlaylistAdapter(RuntimeEnvironment.getApplication(),
                Arrays.asList("Viajes A", "Chill", "Viajes B", "Gym"), null);
        long id = adapter.getItemId(0);
        adapter.filter("Viajes");
        assertTrue(adapter.move(0, 1));
        assertEquals(Arrays.asList("Chill", "Viajes B", "Viajes A", "Gym"), adapter.getPlaylistOrder());
        assertEquals(id, adapter.getItemId(1));
        adapter.updatePlaylists(adapter.getPlaylistOrder());
        assertEquals(2, adapter.getItemCount());
        assertFalse(adapter.move(-1, 1));
        adapter.filter("");
        assertEquals(4, adapter.getItemCount());
    }
}
