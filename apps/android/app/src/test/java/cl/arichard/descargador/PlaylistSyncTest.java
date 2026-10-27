package cl.arichard.descargador;

import android.app.Application;
import android.content.Context;
import cl.arichard.descargador.data.PlaylistManager;
import cl.arichard.descargador.data.PlaylistSync;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class PlaylistSyncTest {
    private JSONObject section(String songs) throws Exception {
        return new JSONObject("{\"playlists\":{\"driving\":" + songs + "}}");
    }

    @Test public void staleEmptyCopyKeepsSongsAddedOnAnotherDevice() throws Exception {
        JSONObject remote = section("['a','b']");
        assertTrue(PlaylistSync.same(remote, PlaylistSync.merge(section("[]"), section("[]"), remote)));
    }

    @Test public void simultaneousAdditionsSurviveWithoutDuplicates() throws Exception {
        assertTrue(PlaylistSync.same(section("['a','b','c']"),
                PlaylistSync.merge(section("['a']"), section("['a','b']"), section("['a','c']"))));
    }

    @Test public void explicitClearDoesNotDeleteUnseenRemoteAdditions() throws Exception {
        assertTrue(PlaylistSync.same(section("['c']"),
                PlaylistSync.merge(section("['a','b']"), section("[]"), section("['a','b','c']"))));
    }

    @Test public void playbackStatsUploadDoesNotResurrectRemoteRemovals() throws Exception {
        JSONObject base = section("['a']");
        assertTrue(PlaylistSync.same(section("[]"), PlaylistSync.merge(base, base, section("[]"))));
        assertTrue(PlaylistSync.same(new JSONObject("{playlists:{}}"),
                PlaylistSync.merge(base, base, new JSONObject("{playlists:{}}"))));
    }

    @Test public void concurrentEditWinsOverAConflictingPlaylistDeletion() throws Exception {
        JSONObject remote = section("['a','b']");
        assertTrue(PlaylistSync.same(remote, PlaylistSync.merge(section("['a']"), new JSONObject("{playlists:{}}"), remote)));
    }

    @Test public void unchangedMetadataAdoptsRemoteEditsAndInputsRemainUntouched() throws Exception {
        JSONObject base = new JSONObject("{playlists:{driving:['a','b']},onlineTracks:{a:{title:'A'}}}");
        JSONObject local = new JSONObject("{playlists:{driving:['b','a']},onlineTracks:{a:{title:'A'}}}");
        JSONObject remote = new JSONObject("{playlists:{driving:['a','b']},onlineTracks:{a:{title:'New A'},c:{title:'C'}}}");
        JSONObject result = PlaylistSync.merge(base, local, remote);
        assertEquals("b", result.getJSONObject("playlists").getJSONArray("driving").getString(0));
        assertTrue(PlaylistSync.same(remote.getJSONObject("onlineTracks"), result.getJSONObject("onlineTracks")));
        result.getJSONObject("playlists").getJSONArray("driving").put("extra");
        assertEquals(2, local.getJSONObject("playlists").getJSONArray("driving").length());
    }

    @Test public void auditRecordsDeviceAndBoundsHistory() throws Exception {
        JSONObject root = new JSONObject();
        for (int i = 0; i < 40; i++) PlaylistSync.appendAudit(root, section("['a']"), section("[]"), "test-device", i);
        JSONArray rows = root.getJSONArray("playlistAudit");
        assertEquals(30, rows.length());
        assertEquals("test-device", rows.getJSONObject(0).getString("device"));
        JSONObject change = rows.getJSONObject(0).getJSONArray("changes").getJSONObject(0);
        assertEquals(1, change.getInt("before")); assertEquals(0, change.getInt("after"));
        PlaylistSync.appendAudit(root, section("[]"), section("[]"), "other", 41);
        assertEquals(30, root.getJSONArray("playlistAudit").length());
    }

    @Test public void replacingAPlaylistPreservesThePreviousLocalSnapshot() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        java.lang.reflect.Constructor<PlaylistManager> constructor = PlaylistManager.class.getDeclaredConstructor(Context.class);
        constructor.setAccessible(true);
        PlaylistManager manager = constructor.newInstance(context);
        manager.importJson(section("['a','b']"));
        manager.importJson(section("[]"));
        assertTrue(manager.getSongPaths("driving").isEmpty());
        JSONArray snapshots = new JSONArray(context.getSharedPreferences("playlist_backups", 0).getString("snapshots", "[]"));
        boolean found = false;
        for (int i = 0; i < snapshots.length(); i++) {
            JSONArray before = snapshots.getJSONObject(i).getJSONObject("state").optJSONObject("playlists").optJSONArray("driving");
            if (before != null && before.length() == 2) found = true;
        }
        assertTrue("The old membership is recoverable", found);
        context.getSharedPreferences("playlist_backups", 0).edit().putString("snapshots", "invalid history").commit();
        manager.importJson(section("['c']"));
        assertEquals("c", manager.getSongPaths("driving").get(0));
    }
}
