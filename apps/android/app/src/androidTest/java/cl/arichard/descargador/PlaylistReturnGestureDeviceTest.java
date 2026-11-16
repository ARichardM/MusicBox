package cl.arichard.descargador;

import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.UiDevice;
import cl.arichard.descargador.data.PlaylistManager;
import cl.arichard.descargador.model.SearchResult;
import cl.arichard.descargador.model.Song;
import cl.arichard.descargador.player.PlayerManager;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Real touch input, synthetic playlist, verification package only; no music is played. */
@RunWith(AndroidJUnit4.class)
public class PlaylistReturnGestureDeviceTest {
    private <T> T main(Supplier<T> action) {
        AtomicReference<T> value = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> value.set(action.get()));
        return value.get();
    }
    private Object field(MainActivity activity, String name) {
        try { Field f = MainActivity.class.getDeclaredField(name); f.setAccessible(true); return f.get(activity); }
        catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
    private void call(MainActivity activity, String name, Class<?> type, Object value) {
        try { Method m = MainActivity.class.getDeclaredMethod(name, type); m.setAccessible(true); m.invoke(activity, value); }
        catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
    private void await(Supplier<Boolean> condition) throws InterruptedException {
        long deadline = android.os.SystemClock.elapsedRealtime() + 15000;
        while (!condition.get() && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(50);
        assertTrue("Expected navigation state", condition.get());
    }
    private android.media.MediaPlayer outgoing(PlayerManager player) {
        try { Field f = PlayerManager.class.getDeclaredField("fadingPlayer"); f.setAccessible(true); return (android.media.MediaPlayer) f.get(player); }
        catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
    @Test public void preparedLocalTracksOverlapAndPauseReleasesBothPlayers() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertEquals("cl.arichard.descargador.verification", context.getPackageName());
        File first = new File(context.getCacheDir(), "crossfade-first.wav"), second = new File(context.getCacheDir(), "crossfade-second.wav");
        int bytes = 8000 * 2 * 6;
        ByteBuffer wav = ByteBuffer.allocate(44 + bytes).order(ByteOrder.LITTLE_ENDIAN);
        wav.put(new byte[]{'R','I','F','F'}).putInt(36 + bytes).put(new byte[]{'W','A','V','E','f','m','t',' '});
        wav.putInt(16).putShort((short)1).putShort((short)1).putInt(8000).putInt(16000);
        wav.putShort((short)2).putShort((short)16).put(new byte[]{'d','a','t','a'}).putInt(bytes);
        for (File file : Arrays.asList(first, second)) try (FileOutputStream output = new FileOutputStream(file)) { output.write(wav.array()); }
        int previous = context.getSharedPreferences("player", 0).getInt("crossfade_seconds", 0);
        PlayerManager player = PlayerManager.getInstance();
        Song a = new Song(0, "Synthetic first", "", "", 6000, first.getPath(), "", 0);
        Song b = new Song(0, "Synthetic second", "", "", 6000, second.getPath(), "", 0);
        main(() -> { player.init(context); player.setCrossfadeSeconds(2); player.playSong(a, Arrays.asList(a, b), false); return true; });
        android.app.Activity activity = InstrumentationRegistry.getInstrumentation().startActivitySync(
                new Intent(context, NowPlayingActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            await(() -> main(player::isPlaying));
            await(() -> main(() -> player.getCurrentSong().getPath().equals(second.getPath())
                    && outgoing(player) != null && outgoing(player).isPlaying() && player.isPlaying()));
            main(() -> { player.pause(); return true; });
            assertTrue(main(() -> outgoing(player) == null && !player.isPlaying()));
        } finally {
            main(() -> { player.stopAndRelease(); player.setCrossfadeSeconds(previous); activity.finish(); return true; });
            first.delete(); second.delete();
        }
    }
    @Test public void lastPlaylistReturnsFromLibraryWithItsScrollPosition() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertEquals("cl.arichard.descargador.verification", context.getPackageName());
        PlaylistManager manager = PlaylistManager.getInstance(context);
        JSONObject previous = new JSONObject(manager.exportJson().toString());
        String name = "Verification return gesture";
        manager.importJson(new JSONObject("{\"playlists\":{\"Favoritos\":[]}}"));
        manager.createPlaylist(name);
        for (int i = 0; i < 60; i++) manager.addOnlineTrack(name,
                new SearchResult(String.format("test%07d", i), "Synthetic track " + i, "Verification", 120, "", ""));
        manager.takeFreshlyAdded(name);
        context.getSharedPreferences("library_navigation", 0).edit().clear().commit();
        context.getSharedPreferences("musicbox_prefs", 0).edit().putBoolean("all_permissions_requested_v1", true).commit();
        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        device.wakeUp();
        MainActivity activity = (MainActivity) InstrumentationRegistry.getInstrumentation().startActivitySync(
                new Intent(context, PreviewMainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        try {
            await(() -> main(() -> activity.hasWindowFocus()
                    && new androidx.lifecycle.ViewModelProvider(activity).get(MainUiState.class).libraryLoaded));
            main(() -> { call(activity, "switchTab", boolean.class, false); call(activity, "showPlaylistSongs", String.class, name); return true; });
            RecyclerView rows = main(() -> activity.findViewById(R.id.recyclerLibrary));
            await(() -> main(() -> rows.getAdapter().getItemCount() == 60));
            main(() -> { ((LinearLayoutManager) rows.getLayoutManager()).scrollToPositionWithOffset(25, -12); return true; });
            await(() -> main(() -> ((LinearLayoutManager) rows.getLayoutManager()).findFirstVisibleItemPosition() == 25));
            Rect bounds = main(() -> { Rect r = new Rect(); activity.findViewById(R.id.libraryContentSwipe).getGlobalVisibleRect(r); return r; });
            int left = bounds.left + bounds.width() / 5, right = bounds.right - bounds.width() / 5;
            device.swipe(left, bounds.centerY(), right, bounds.centerY(), 25);
            await(() -> main(() -> !(Boolean) field(activity, "isViewingSubList")));
            await(() -> main(() -> !(Boolean) field(activity, "collectionSwipeAnimating")));
            device.swipe(right, bounds.centerY(), left, bounds.centerY(), 25);
            await(() -> main(() -> (Boolean) field(activity, "isViewingSubList") && name.equals(field(activity, "currentSubListName"))));
            await(() -> main(() -> ((LinearLayoutManager) rows.getLayoutManager()).findFirstVisibleItemPosition() == 25));
        } finally {
            main(() -> { activity.finish(); manager.importJson(previous); return true; });
        }
    }
}
