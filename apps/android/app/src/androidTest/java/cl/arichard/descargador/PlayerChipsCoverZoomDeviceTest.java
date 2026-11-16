package cl.arichard.descargador;

import android.content.Context;
import android.content.Intent;
import android.view.View;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import cl.arichard.descargador.model.Song;
import cl.arichard.descargador.player.PlayerManager;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Collections;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Cover zoom in MP3 mode and flicker-free chips, with a private synthetic tone. */
@RunWith(AndroidJUnit4.class)
public class PlayerChipsCoverZoomDeviceTest {
    @Test public void tappingTheCoverZoomsItAndBackRestoresIt() throws Exception {
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        Context context = instrumentation.getTargetContext();
        assertEquals("cl.arichard.descargador.verification", context.getPackageName());
        androidx.test.uiautomator.UiDevice.getInstance(instrumentation).wakeUp();
        org.junit.Assume.assumeFalse("Unlock the verification device before running touch tests",
                ((android.app.KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE)).isKeyguardLocked());
        context.getSharedPreferences("player", 0).edit().putBoolean("zoom_mp3", false).putBoolean("zoom_mp4", false).commit();
        File audio = new File(context.getCacheDir(), "zoom-test.wav");
        int bytes = 16000 * 2 * 30;
        ByteBuffer wav = ByteBuffer.allocate(44 + bytes).order(ByteOrder.LITTLE_ENDIAN);
        wav.put(new byte[]{'R','I','F','F'}).putInt(36 + bytes).put(new byte[]{'W','A','V','E','f','m','t',' '});
        wav.putInt(16).putShort((short) 1).putShort((short) 1).putInt(16000).putInt(32000);
        wav.putShort((short) 2).putShort((short) 16).put(new byte[]{'d','a','t','a'}).putInt(bytes);
        try (FileOutputStream output = new FileOutputStream(audio)) { output.write(wav.array()); }
        Song song = new Song(0, "Zoom", "Verification", "Synthetic", 30000, audio.getPath(), "", 0);
        PlayerManager player = PlayerManager.getInstance();
        instrumentation.runOnMainSync(() -> { player.init(context); player.playSong(song, Collections.singletonList(song), false); });
        NowPlayingActivity launched = (NowPlayingActivity) instrumentation.startActivitySync(
                new Intent(context, NowPlayingActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        Thread.sleep(1500);
        java.util.concurrent.atomic.AtomicReference<NowPlayingActivity> active = new java.util.concurrent.atomic.AtomicReference<>(launched);
        instrumentation.runOnMainSync(() -> {
            for (android.app.Activity activity : androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED))
                if (activity instanceof NowPlayingActivity) active.set((NowPlayingActivity) activity);
        });
        NowPlayingActivity now = active.get();
        try {
            View disc = now.findViewById(R.id.discContainer);
            View mp4 = now.findViewById(R.id.tabSelectorMp4);
            instrumentation.runOnMainSync(() -> assertTrue("Verification player has focus", now.hasWindowFocus()));
            instrumentation.runOnMainSync(() -> player.setCrossfadeSeconds(7));
            androidx.test.uiautomator.UiDevice device = androidx.test.uiautomator.UiDevice.getInstance(instrumentation);
            for (int i = 0; i < 2; i++) {
                instrumentation.runOnMainSync(() -> now.findViewById(R.id.btnPlayerMore).performClick());
                androidx.test.uiautomator.UiObject2 toggle = device.wait(androidx.test.uiautomator.Until.findObject(
                        androidx.test.uiautomator.By.text("Crossfade")), 3000);
                assertNotNull(toggle); android.graphics.Rect box = toggle.getVisibleBounds(); device.click(box.right + 40, box.centerY()); Thread.sleep(500);
                instrumentation.waitForIdleSync();
                assertEquals("Quick menu preserves the configured duration (finishing=" + now.isFinishing() + " focus=" + now.hasWindowFocus() + " i=" + i + ")", i == 0 ? 0 : 7, player.getCrossfadeSeconds());
            }
            int[] original = new int[2];
            instrumentation.runOnMainSync(() -> { original[0] = disc.getWidth(); original[1] = disc.getHeight(); });
            int[] centre = new int[2];
            instrumentation.runOnMainSync(() -> { disc.getLocationOnScreen(centre); centre[0] += disc.getWidth() / 2; centre[1] += disc.getHeight() / 2; });
            device.click(centre[0], centre[1]);
            Thread.sleep(900);
            instrumentation.runOnMainSync(() -> {
                int[] position = new int[2], title = new int[2];
                disc.getLocationInWindow(position);
                now.findViewById(R.id.txtNowPlayingTitle).getLocationInWindow(title);
                assertEquals("Like MP4, artwork starts at the screen's top", 0, position[1], 2);
                assertEquals("Artwork touches the left screen edge", 0, position[0], 2);
                assertEquals(context.getResources().getDisplayMetrics().widthPixels, disc.getWidth());
                assertEquals("Same bottom edge as immersive MP4", title[1], position[1] + disc.getHeight(), 2);
                assertEquals(0f, ((androidx.cardview.widget.CardView) disc).getRadius(), 0.1f);
                assertEquals("No scaling shortcut", 1f, disc.getScaleX(), 0.001f);
            });
            instrumentation.runOnMainSync(() -> now.getOnBackPressedDispatcher().onBackPressed());
            Thread.sleep(700);
            instrumentation.runOnMainSync(() -> {
                assertEquals("Back restores the square cover", disc.getWidth(), disc.getHeight());
                assertTrue("Back shrinks it again", disc.getWidth() < context.getResources().getDisplayMetrics().widthPixels);
            });
            // A local tone has no video: the MP4 chip must stay hidden and never flash.
            boolean[] flashed = new boolean[1];
            for (int i = 0; i < 20; i++) { instrumentation.runOnMainSync(() -> flashed[0] |= mp4.getVisibility() == View.VISIBLE); Thread.sleep(50); }
            assertFalse("MP4 chip does not flash for audio-only songs", flashed[0]);
            instrumentation.runOnMainSync(() -> {
                player.playSong(song, new java.util.ArrayList<>(Collections.nCopies(20, song)), false);
                com.google.android.material.bottomsheet.BottomSheetBehavior.from(now.findViewById(R.id.queueSheet))
                        .setState(com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED);
            });
            Thread.sleep(700);
            androidx.recyclerview.widget.RecyclerView rows = findRows(now.findViewById(R.id.queueSheet));
            assertNotNull(rows);
            instrumentation.runOnMainSync(() -> rows.scrollToPosition(rows.getAdapter().getItemCount() - 1));
            Thread.sleep(500);
            instrumentation.runOnMainSync(() -> {
                androidx.recyclerview.widget.LinearLayoutManager layout = (androidx.recyclerview.widget.LinearLayoutManager) rows.getLayoutManager();
                assertEquals("The final queue entry is fully visible", rows.getAdapter().getItemCount() - 1,
                        layout.findLastCompletelyVisibleItemPosition());
                int[] position = new int[2]; rows.getLocationOnScreen(position);
                assertTrue("Queue viewport stays above the navigation bar", position[1] + rows.getHeight()
                        <= now.getWindow().getDecorView().getHeight());
            });
        } finally {
            instrumentation.runOnMainSync(() -> { now.finish(); player.stopAndRelease(); });
            audio.delete();
        }
    }
    private androidx.recyclerview.widget.RecyclerView findRows(View view) {
        if (view instanceof androidx.recyclerview.widget.RecyclerView) return (androidx.recyclerview.widget.RecyclerView) view;
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                androidx.recyclerview.widget.RecyclerView rows = findRows(group.getChildAt(i));
                if (rows != null) return rows;
            }
        }
        return null;
    }
}
