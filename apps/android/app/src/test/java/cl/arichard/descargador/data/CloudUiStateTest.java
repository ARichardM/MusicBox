package cl.arichard.descargador.data;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = android.app.Application.class)
public class CloudUiStateTest {
    @Test public void listeningTicksDoNotInvalidateTheLibraryButNewPlaysDo() throws Exception {
        JSONObject clicks = new JSONObject().put("devices", new JSONObject().put("pc", new JSONObject().put("yt:sample", 1)))
                .put("titles", new JSONObject().put("yt:sample", "Muestra")).put("listening", new JSONObject().put("ms", 1000));
        JSONObject before = CloudSyncManager.libraryUiState(new JSONObject(), new JSONObject(), clicks);
        clicks.getJSONObject("listening").put("ms", 9000);
        assertTrue(PlaylistSync.same(before, CloudSyncManager.libraryUiState(new JSONObject(), new JSONObject(), clicks)));
        clicks.getJSONObject("devices").getJSONObject("pc").put("yt:sample", 2);
        assertFalse(PlaylistSync.same(before, CloudSyncManager.libraryUiState(new JSONObject(), new JSONObject(), clicks)));
    }
    @Test public void playlistChangesStillInvalidateTheLibrary() throws Exception {
        JSONObject lists = new JSONObject().put("Favoritos", "antes");
        JSONObject before = CloudSyncManager.libraryUiState(lists, new JSONObject(), new JSONObject());
        lists.put("Favoritos", "después");
        assertFalse(PlaylistSync.same(before, CloudSyncManager.libraryUiState(lists, new JSONObject(), new JSONObject())));
    }
}
