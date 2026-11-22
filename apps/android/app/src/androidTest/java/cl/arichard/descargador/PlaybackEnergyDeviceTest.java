package cl.arichard.descargador;

import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.UiDevice;
import cl.arichard.descargador.model.Song;
import cl.arichard.descargador.player.PlayerManager;
import cl.arichard.descargador.service.MusicService;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class PlaybackEnergyDeviceTest {
    @Test public void pausedPlaybackStopsProgressPollingAndResumesItWhenPlaying() throws Exception {
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        var context = instrumentation.getTargetContext();
        assertEquals("cl.arichard.descargador.verification", context.getPackageName());
        var device = UiDevice.getInstance(instrumentation);
        device.wakeUp();
        var activity = instrumentation.startActivitySync(new Intent(context, SettingsActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        File file = new File(context.getCacheDir(), "playback-energy-silence.wav");
        int bytes = 16000 * 2 * 60;
        ByteBuffer wave = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        wave.put(new byte[]{'R','I','F','F'}).putInt(36 + bytes).put(new byte[]{'W','A','V','E','f','m','t',' '});
        wave.putInt(16).putShort((short) 1).putShort((short) 1).putInt(16000).putInt(32000);
        wave.putShort((short) 2).putShort((short) 16).put(new byte[]{'d','a','t','a'}).putInt(bytes);
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(wave.array());
            byte[] silence = new byte[32000];
            for (int second = 0; second < 60; second++) output.write(silence);
        }
        PlayerManager player = PlayerManager.getInstance();
        Song song = new Song(0, "Silencio de prueba", "Verificación", "", 60000, file.getPath(), "", 0);
        AtomicInteger ticks = new AtomicInteger();
        PlayerManager.PlaybackListener listener = new PlayerManager.PlaybackListener() {
            @Override public void onTrackChanged(Song selected) {}
            @Override public void onPlaybackStateChanged(boolean playing) {}
            @Override public void onProgressUpdate(int position, int duration) { ticks.incrementAndGet(); }
        };
        try {
            instrumentation.runOnMainSync(() -> {
                player.addListener(listener);
                player.playSong(song, Collections.singletonList(song), false);
            });
            waitForTicks(ticks, 2);
            AtomicBoolean exo = new AtomicBoolean();
            instrumentation.runOnMainSync(() -> exo.set(player.isUsingExoPlayer()));
            assertTrue("Playback on real device must use Media3 ExoPlayer", exo.get());
            waitForCondition("MusicService enters foreground while playing", MusicService::isInForeground, 4000);

            // Verify seek works smoothly on ExoPlayer
            instrumentation.runOnMainSync(() -> player.seekTo(15_000));
            waitForCondition("Seek advances position near 15s", () -> {
                AtomicInteger pos = new AtomicInteger();
                instrumentation.runOnMainSync(() -> pos.set(player.getCurrentPosition()));
                return pos.get() >= 14_000;
            }, 3000);

            device.pressHome();
            instrumentation.runOnMainSync(() -> {
                player.pause();
                ticks.set(0);
            });
            waitForCondition("MusicService detaches foreground on pause", () -> !MusicService.isInForeground(), 4000);
            SystemClock.sleep(3500);
            int paused = ticks.get();
            Bundle sample = new Bundle();
            sample.putString("metric", "paused_progress_callbacks_3500ms");
            sample.putInt("count", paused);
            sample.putBoolean("using_exoplayer", exo.get());
            sample.putBoolean("foreground_while_paused", MusicService.isInForeground());
            instrumentation.sendStatus(0, sample);
            instrumentation.runOnMainSync(player::play);
            waitForTicks(ticks, paused + 2);
            waitForCondition("MusicService re-enters foreground on resume", MusicService::isInForeground, 4000);
            assertEquals("Progress callbacks while paused", 0, paused);
        } finally {
            instrumentation.runOnMainSync(() -> {
                player.removeListener(listener);
                player.stopAndRelease();
                activity.finish();
            });
            file.delete();
        }
    }

    @Test public void compressedAudioPlaysSmoothlyInBackgroundAndProbesHardwareOffload() throws Exception {
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        var context = instrumentation.getTargetContext();
        var device = UiDevice.getInstance(instrumentation);
        device.wakeUp();
        var activity = instrumentation.startActivitySync(new Intent(context, SettingsActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        File m4a = new File(context.getCacheDir(), "offload-probe.m4a");
        createSilentAacM4a(m4a, 65);

        PlayerManager player = PlayerManager.getInstance();
        Song song = new Song(0, "Offload Probe", "Verificación", "", 65_000, m4a.getPath(), "", 0);
        try {
            instrumentation.runOnMainSync(() -> {
                player.setCrossfadeSeconds(0);
                player.playSong(song, Collections.singletonList(song), false);
            });
            waitForCondition("AAC track starts playing", () -> {
                AtomicBoolean playing = new AtomicBoolean();
                instrumentation.runOnMainSync(() -> playing.set(player.isPlaying()));
                return playing.get();
            }, 6000);

            AtomicBoolean exo = new AtomicBoolean();
            instrumentation.runOnMainSync(() -> exo.set(player.isUsingExoPlayer()));
            assertTrue("AAC playback uses ExoPlayer", exo.get());

            boolean offloadSupported = false;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                AudioFormat format = new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_AAC_LC)
                        .setSampleRate(44100)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build();
                AudioAttributes attrs = new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build();
                offloadSupported = AudioManager.isOffloadedPlaybackSupported(format, attrs);
            }

            // Put app in background with no UI listeners attached and confirm playback progresses cleanly.
            device.pressHome();
            AtomicInteger posStart = new AtomicInteger();
            instrumentation.runOnMainSync(() -> posStart.set(player.getCurrentPosition()));
            boolean observedOffloadSleep = false;
            long deadline = SystemClock.elapsedRealtime() + 4500;
            while (SystemClock.elapsedRealtime() < deadline) {
                AtomicBoolean sleeping = new AtomicBoolean();
                instrumentation.runOnMainSync(() -> sleeping.set(player.isSleepingForOffload()));
                if (sleeping.get()) observedOffloadSleep = true;
                SystemClock.sleep(250);
            }
            AtomicInteger posEnd = new AtomicInteger();
            AtomicBoolean stillPlaying = new AtomicBoolean();
            instrumentation.runOnMainSync(() -> {
                posEnd.set(player.getCurrentPosition());
                stillPlaying.set(player.isPlaying());
            });

            Bundle metrics = new Bundle();
            metrics.putString("metric", "aac_background_offload_probe");
            metrics.putBoolean("using_exoplayer", exo.get());
            metrics.putBoolean("offload_supported_by_hal", offloadSupported);
            metrics.putBoolean("offload_sleeping_observed", observedOffloadSleep);
            metrics.putInt("progress_delta_ms", posEnd.get() - posStart.get());
            instrumentation.sendStatus(0, metrics);

            assertTrue("Track remains playing in background", stillPlaying.get());
            assertTrue("Background playback advances smoothly, delta=" + (posEnd.get() - posStart.get()),
                    posEnd.get() - posStart.get() >= 2500);
        } finally {
            instrumentation.runOnMainSync(() -> {
                player.stopAndRelease();
                activity.finish();
            });
            m4a.delete();
        }
    }

    private static void createSilentAacM4a(File out, int seconds) throws Exception {
        int sampleRate = 44100;
        int channels = 2;
        MediaFormat format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels);
        format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        format.setInteger(MediaFormat.KEY_BIT_RATE, 128_000);
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);

        MediaCodec codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
        MediaMuxer muxer = new MediaMuxer(out.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        int trackIndex = -1;
        boolean muxerStarted = false;
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            codec.start();
            byte[] silentPcm = new byte[1024 * channels * 2];
            long totalFrames = (long) sampleRate * seconds / 1024L;
            long inputFrame = 0;
            boolean inputDone = false;
            boolean outputDone = false;
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            while (!outputDone) {
                if (!inputDone) {
                    int inIndex = codec.dequeueInputBuffer(10_000);
                    if (inIndex >= 0) {
                        ByteBuffer inBuf = codec.getInputBuffer(inIndex);
                        inBuf.clear();
                        if (inputFrame >= totalFrames) {
                            codec.queueInputBuffer(inIndex, 0, 0, inputFrame * 1024L * 1_000_000L / sampleRate,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            inBuf.put(silentPcm);
                            codec.queueInputBuffer(inIndex, 0, silentPcm.length,
                                    inputFrame * 1024L * 1_000_000L / sampleRate, 0);
                            inputFrame++;
                        }
                    }
                }
                int outIndex = codec.dequeueOutputBuffer(info, 10_000);
                if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    trackIndex = muxer.addTrack(codec.getOutputFormat());
                    muxer.start();
                    muxerStarted = true;
                } else if (outIndex >= 0) {
                    ByteBuffer outBuf = codec.getOutputBuffer(outIndex);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && info.size > 0 && muxerStarted) {
                        outBuf.position(info.offset);
                        outBuf.limit(info.offset + info.size);
                        muxer.writeSampleData(trackIndex, outBuf, info);
                    }
                    codec.releaseOutputBuffer(outIndex, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true;
                }
            }
        } finally {
            try { codec.stop(); } catch (Exception ignored) {}
            try { codec.release(); } catch (Exception ignored) {}
            if (muxerStarted) try { muxer.stop(); } catch (Exception ignored) {}
            try { muxer.release(); } catch (Exception ignored) {}
        }
    }

    private interface Check { boolean ok(); }

    private static void waitForCondition(String description, Check check, long timeoutMs) {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        while (!check.ok() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50);
        assertTrue(description, check.ok());
    }

    private static void waitForTicks(AtomicInteger ticks, int expected) {
        long deadline = SystemClock.elapsedRealtime() + 8000;
        while (ticks.get() < expected && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(40);
        assertTrue("Playback progress callbacks arrive", ticks.get() >= expected);
    }
}

