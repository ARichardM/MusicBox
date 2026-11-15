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
import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;
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
        File audio = new File(context.getCacheDir(), "artwork-test.wav");
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
            FrameLayout disc = now.findViewById(R.id.discContainer);
            TextureView animation = (TextureView) disc.getChildAt(disc.getChildCount() - 1);
            ImageView staticCover = now.findViewById(R.id.imgNowPlayingCover);
            await("Local audio plays", player::isPlaying);
            await("Local song renders its animated cover", () -> animation.getAlpha() == 1);
            instrumentation.runOnMainSync(() -> player.playOnline("b1kbLwvqugk", "Anti-Hero",
                    "Taylor Swift", "", "https://www.youtube.com/watch?v=b1kbLwvqugk"));
            await("Online track is selected", () -> player.getCurrentSong().isOnline());
            await("Online song renders its animated cover", () -> animation.getAlpha() == 1);
            Song fallback = new Song(0, "Nights", "Frank Ocean", "Blonde", 0, audio.getPath(), "", 0);
            instrumentation.runOnMainSync(() -> player.playSong(fallback, Collections.singletonList(fallback), false));
            await("Static cover is visible after track switch",
                    () -> animation.getAlpha() == 0 && staticCover.isShown());
            instrumentation.runOnMainSync(now::finish);
            await("Animation is released when the screen closes", () -> animation.getAlpha() == 0);
        } finally {
            instrumentation.runOnMainSync(() -> {
                now.finish();
                player.stopAndRelease();
            });
            audio.delete();
        }
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
