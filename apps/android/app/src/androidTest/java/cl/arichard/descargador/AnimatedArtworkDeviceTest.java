package cl.arichard.descargador;

import android.content.Context;
import android.content.Intent;
import android.view.TextureView;
import android.widget.FrameLayout;
import android.widget.ImageView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import cl.arichard.descargador.model.Song;
import cl.arichard.descargador.player.PlayerManager;
import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class AnimatedArtworkDeviceTest {
    @Test public void rendersLiveArtworkForLocalAndOnlineTracksAndRestoresStaticCover() throws Exception {
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        Context context = instrumentation.getTargetContext();
        assertEquals("cl.arichard.descargador.verification", context.getPackageName());
        PlayerManager player = PlayerManager.getInstance();
        File audio = File.createTempFile("artwork-test", ".wav", context.getCacheDir());
        var preferences = context.getSharedPreferences("player", Context.MODE_PRIVATE);
        boolean oldDownload = AnimatedArtwork.downloadsEnabled(context);
        preferences.edit().putBoolean(AnimatedArtwork.DOWNLOAD_PREFERENCE, true).commit();
        UUID key = UUID.nameUUIDFromBytes(("file:" + audio.getPath()).getBytes(StandardCharsets.UTF_8));
        File saved = new File(context.getFilesDir(), "animated-artwork/" + key + ".mp4");
        int bytes = 16000 * 2 * 60;
        ByteBuffer wav = ByteBuffer.allocate(44 + bytes).order(ByteOrder.LITTLE_ENDIAN);
        wav.put(new byte[]{'R','I','F','F'}).putInt(36 + bytes).put(new byte[]{'W','A','V','E','f','m','t',' '});
        wav.putInt(16).putShort((short) 1).putShort((short) 1).putInt(16000).putInt(32000);
        wav.putShort((short) 2).putShort((short) 16).put(new byte[]{'d','a','t','a'}).putInt(bytes);
        try (FileOutputStream output = new FileOutputStream(audio)) {
            output.write(wav.array());
        }
        Song local = new Song(0, "Anti-Hero", "Taylor Swift", "Midnights", 0, audio.getPath(), "", 0);
        instrumentation.runOnMainSync(() -> {
            player.init(context);
            player.playSong(local, Collections.singletonList(local), false);
        });
        NowPlayingActivity now = (NowPlayingActivity) instrumentation.startActivitySync(
                new Intent(context, NowPlayingActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            AtomicReference<TextureView> animation = new AtomicReference<>();
            await("Local audio plays", player::isPlaying);
            await("Local song renders its animated cover", () -> {
                FrameLayout disc = currentPlayer(now).findViewById(R.id.discContainer);
                animation.set(findAnimation(disc));
                return animation.get().getAlpha() == 1;
            });
            assertTrue("Animated artwork was saved for offline playback", saved.isFile() && saved.length() > 0);
            instrumentation.runOnMainSync(() -> player.playOnline("b1kbLwvqugk", "Anti-Hero",
                    "Taylor Swift", "", "https://www.youtube.com/watch?v=b1kbLwvqugk"));
            await("Online track is selected", () -> player.getCurrentSong().isOnline());
            await("Online song renders its animated cover", () -> {
                FrameLayout disc = currentPlayer(now).findViewById(R.id.discContainer);
                animation.set(findAnimation(disc));
                return animation.get().getAlpha() == 1;
            });
            Song fallback = new Song(0, "Nights", "Frank Ocean", "Blonde", 0, audio.getPath(), "", 0);
            instrumentation.runOnMainSync(() -> player.playSong(fallback, Collections.singletonList(fallback), false));
            await("Static cover is visible after track switch",
                    () -> animation.get().getAlpha() == 0 && currentPlayer(now).findViewById(R.id.imgNowPlayingCover).isShown());
            instrumentation.runOnMainSync(() -> currentPlayer(now).finish());
            await("Animation is released when the screen closes", () -> animation.get().getAlpha() == 0);
        } finally {
            instrumentation.runOnMainSync(() -> {
                currentPlayer(now).finish();
                player.stopAndRelease();
            });
            preferences.edit().putBoolean(AnimatedArtwork.DOWNLOAD_PREFERENCE, oldDownload).commit();
            saved.delete();
            audio.delete();
        }
    }

    private static TextureView findAnimation(FrameLayout disc) {
        for (int i = 0; i < disc.getChildCount(); i++)
            if (disc.getChildAt(i) instanceof TextureView) return (TextureView) disc.getChildAt(i);
        throw new AssertionError("Animated artwork view is missing");
    }

    private static NowPlayingActivity currentPlayer(NowPlayingActivity fallback) {
        // Applying the selected language can recreate the activity on a freshly installed verification build.
        for (android.app.Activity activity : androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
                .getInstance().getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED)) {
            if (activity instanceof NowPlayingActivity) return (NowPlayingActivity) activity;
        }
        return fallback;
    }

    private static void await(String message, BooleanSupplier condition) throws InterruptedException {
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        AtomicBoolean ready = new AtomicBoolean();
        long deadline = android.os.SystemClock.elapsedRealtime() + 20000;
        do {
            instrumentation.runOnMainSync(() -> ready.set(condition.getAsBoolean()));
            if (ready.get()) return;
            Thread.sleep(100);
        } while (android.os.SystemClock.elapsedRealtime() < deadline);
        fail(message);
    }
}
