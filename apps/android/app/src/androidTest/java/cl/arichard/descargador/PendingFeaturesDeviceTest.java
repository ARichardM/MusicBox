package cl.arichard.descargador;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.net.Uri;
import android.view.View;
import android.widget.EditText;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.Configurator;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;
import cl.arichard.descargador.adapter.SongAdapter;
import cl.arichard.descargador.data.MusicScanner;
import cl.arichard.descargador.download.DownloadManager;
import cl.arichard.descargador.model.Song;
import cl.arichard.descargador.player.PlayerManager;
import cl.arichard.descargador.util.Tr;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import static org.junit.Assume.*;

/** Dispositivo real, variante aislada. Solo puede borrar las tres muestras expresamente nombradas. */
@RunWith(AndroidJUnit4.class)
public class PendingFeaturesDeviceTest {
    private Context context;
    private UiDevice device;
    private String pkg;
    private final PlayerManager player = PlayerManager.getInstance();

    private <T> T main(Supplier<T> read) {
        AtomicReference<T> result = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> result.set(read.get()));
        return result.get();
    }
    private void action(Runnable run) { main(() -> { run.run(); return true; }); }
    private void waitFor(Supplier<Boolean> condition) throws Exception {
        waitFor(condition, 20000);
    }
    private void waitFor(Supplier<Boolean> condition, long timeout) throws Exception {
        long end = System.currentTimeMillis() + timeout;
        while (!condition.get() && System.currentTimeMillis() < end) Thread.sleep(100);
        assertTrue("Expected device state", condition.get());
    }
    private UiObject2 view(String id) {
        UiObject2 result = device.wait(Until.findObject(By.res(pkg, id)), 15000);
        assertNotNull("Visible: " + id, result); return result;
    }
    private Activity launch(Class<? extends Activity> type) {
        return InstrumentationRegistry.getInstrumentation().startActivitySync(
                new Intent(context, type).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
    }
    @Before public void setup() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        pkg = context.getPackageName();
        assumeTrue("Independent verification only", pkg.endsWith(".verification"));
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        Configurator.getInstance().setWaitForIdleTimeout(0).setWaitForSelectorTimeout(2000);
        device.wakeUp(); device.pressHome();
    }
    @After public void stopAudio() { action(player::stopAndRelease); }

    private MainActivity library() throws Exception {
        MainActivity activity = (MainActivity) launch(PreviewMainActivity.class);
        view("navTabLibrary").click(); view("chipSongs").click();
        action(() -> ((EditText) activity.findViewById(R.id.inputSearchLibrary)).setText(""));
        waitFor(() -> main(() -> adapter(activity).getItemCount() >= 3));
        return activity;
    }
    private SongAdapter adapter(MainActivity activity) {
        return (SongAdapter) ((RecyclerView) activity.findViewById(R.id.recyclerLibrary)).getAdapter();
    }

    private void selectVisibleSong(MainActivity activity) throws Exception {
        RecyclerView list = activity.findViewById(R.id.recyclerLibrary);
        action(() -> list.scrollToPosition(0));
        waitFor(() -> main(() -> list.getScrollState() == RecyclerView.SCROLL_STATE_IDLE
                && (list.getItemAnimator() == null || !list.getItemAnimator().isRunning())));
        Thread.sleep(450);
        android.graphics.Rect bounds = view("recyclerLibrary").getVisibleBounds();
        UiObject2 title = null;
        for (UiObject2 candidate : device.findObjects(By.res(pkg, "txtSongTitle"))) {
            android.graphics.Rect row = candidate.getVisibleBounds();
            if (row.height() > 0 && row.top >= bounds.top && row.bottom <= bounds.bottom) { title = candidate; break; }
        }
        assertNotNull("Fully visible song title", title);
        title.longClick();
        waitFor(() -> main(() -> adapter(activity).isSelectionMode()));
    }

    @Test public void selectionAllAndBulkQueueFitTheScreen() throws Exception {
        MainActivity activity = library();
        List<Song> songs = MusicScanner.scanAllMusic(context);
        action(() -> player.playSong(songs.get(0), new ArrayList<>(songs.subList(0, 3))));
        waitFor(() -> main(() -> !player.isLoading()));
        // Las muestras de eliminación duran muy poco: no cambiar de fila a mitad de la pulsación larga.
        action(player::pause);
        selectVisibleSong(activity);
        view("selectionBar");
        waitFor(() -> main(() -> activity.findViewById(R.id.bottomBarArea).getVisibility() != View.VISIBLE));
        assertTrue(main(() -> adapter(activity).isSelectionMode()));
        view("btnSelectAll").click();
        assertEquals(main(() -> adapter(activity).getItemCount()), main(() -> adapter(activity).getSelectionCount()));
        assertTrue(main(() -> adapter(activity).areAllSelected()));
        android.graphics.Rect bar = view("selectionBar").getVisibleBounds();
        android.graphics.Rect delete = view("btnBulkDelete").getVisibleBounds();
        assertTrue("Delete action is inside the selection bar", bar.contains(delete));
        view("btnSelectAll").click();
        waitFor(() -> main(() -> !adapter(activity).isSelectionMode()));
        selectVisibleSong(activity);
        Song selected = main(() -> adapter(activity).getSelectedSongs().get(0));
        view("btnBulkQueue").click();
        waitFor(() -> main(() -> !adapter(activity).isSelectionMode()));
        assertTrue(main(() -> player.getQueue().stream().anyMatch(song -> song.getPath().equals(selected.getPath()))));
    }

    @Test public void sortMenuChangesOrderAndRemembersIt() throws Exception {
        MainActivity activity = library();
        context.getSharedPreferences("musicbox_prefs", 0).edit().putString("library_sort", "DEFAULT:1").commit();
        // Reiniciar la pantalla lee exactamente la misma preferencia que un arranque normal.
        activity = library();
        view("btnLibrarySort").click();
        UiObject2 title = device.wait(Until.findObject(By.textContains(Tr.t("Título A–Z"))), 5000);
        assertNotNull(title); title.click();
        MainActivity sorted = activity;
        waitFor(() -> main(() -> adapter(sorted).getSort() == SongAdapter.Sort.TITLE));
        assertTrue(main(() -> adapter(sorted).isAscending()));
        view("btnLibrarySort").click();
        UiObject2 selected = device.wait(Until.findObject(By.text(java.util.regex.Pattern.compile("✓.*" + java.util.regex.Pattern.quote(Tr.t("Título A–Z"))))), 5000);
        assertNotNull(selected); selected.click();
        waitFor(() -> main(() -> !adapter(sorted).isAscending()));
        MainActivity reopened = library();
        assertEquals(SongAdapter.Sort.TITLE, main(() -> adapter(reopened).getSort()));
        assertFalse(main(() -> adapter(reopened).isAscending()));
    }

    @Test public void sharedLinksOpenInDownloaderWithoutStartingAJob() throws Exception {
        int before = DownloadManager.get(context).jobs().size();
        String link = "https://www.youtube.com/watch?v=abcdefghijk";
        Intent share = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, link)
                .setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        assertEquals(DownloaderActivity.class.getName(), context.getPackageManager().resolveActivity(share, 0).activityInfo.name);
        InstrumentationRegistry.getInstrumentation().startActivitySync(share);
        assertEquals(link, view("inputLinks").getText());
        assertEquals(before, DownloadManager.get(context).jobs().size());
    }

    @Test public void bundledOcrRecognizesAListAndLetsTheUserReviewIt() throws Exception {
        ImporterActivity activity = (ImporterActivity) launch(ImporterActivity.class);
        view("inputList");
        action(() -> ((EditText) activity.findViewById(R.id.inputList)).setText("Mi canción anterior"));
        Bitmap bitmap = Bitmap.createBitmap(1600, 600, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap); canvas.drawColor(Color.WHITE);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG); paint.setColor(Color.BLACK); paint.setTextSize(54);
        canvas.drawText("Los Prisioneros - Tren al sur", 50, 160, paint);
        canvas.drawText("Soda Stereo - De musica ligera", 50, 290, paint);
        File image = new File(context.getCacheDir(), "ocr-device-test.png");
        try (FileOutputStream out = new FileOutputStream(image)) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, out); }
        bitmap.recycle();
        Method read = ImporterActivity.class.getDeclaredMethod("readImage", Uri.class); read.setAccessible(true);
        action(() -> { try { read.invoke(activity, Uri.fromFile(image)); } catch (Exception e) { throw new AssertionError(e); } });
        assertNotNull(device.wait(Until.findObject(By.text(Tr.t("Revisar texto de la imagen"))), 30000));
        assertNotNull(device.findObject(By.textContains("Tren al sur")));
        UiObject2 accept = device.wait(Until.findObject(By.res("android", "button1")), 5000);
        assertNotNull(accept); accept.click();
        String result = view("inputList").getText();
        assertTrue(result.startsWith("Mi canción anterior\n"));
        assertTrue(result.contains("Tren al sur"));
        assertTrue(result.contains("musica ligera"));
        assertTrue(image.delete());
    }

    @Test public void systemDeletePromptDeletesOnlyTheNamedTestSamples() throws Exception {
        assumeTrue(android.os.Build.VERSION.SDK_INT >= 30);
        MainActivity activity = library();
        List<Song> samples = new ArrayList<>();
        for (Song song : MusicScanner.scanAllMusic(context)) {
            if (song.getPath().matches("/storage/emulated/0/Music/zz_prueba_borrar_[123]\\.mp3")) samples.add(song);
        }
        assumeFalse("Named disposable samples exist", samples.isEmpty());
        for (Song song : samples) assertTrue(new File(song.getPath()).getName().startsWith("zz_prueba_borrar_"));
        java.lang.reflect.Field promptField = MainActivity.class.getDeclaredField("deletePrompt");
        promptField.setAccessible(true);
        cl.arichard.descargador.util.SongDeleter.SystemPrompt prompt =
                (cl.arichard.descargador.util.SongDeleter.SystemPrompt) promptField.get(activity);
        action(() -> cl.arichard.descargador.util.SongDeleter.delete(activity, samples, prompt, () -> {}));
        UiObject2 confirm = device.wait(Until.findObject(By.res("android", "button1")), 10000);
        assertNotNull("App confirmation", confirm); confirm.click();
        UiObject2 system = device.wait(Until.findObject(By.res("com.google.android.providers.media.module", "delete")), 8000);
        if (system == null) system = device.findObject(By.res("com.android.providers.media.module", "delete"));
        if (system == null) system = device.findObject(By.text(java.util.regex.Pattern.compile("(?i)eliminar|delete|permitir|allow")));
        assertNotNull("Android MediaStore confirmation", system); system.click();
        waitFor(() -> samples.stream().noneMatch(song -> new File(song.getPath()).exists()));
    }

    @Test public void removingTheTaskStopsPlaybackAndRemovesTheMediaNotification() throws Exception {
        MainActivity activity = library();
        List<Song> songs = MusicScanner.scanAllMusic(context);
        action(() -> player.playSong(songs.get(0), new ArrayList<>(songs.subList(0, 3))));
        waitFor(() -> main(player::isPlaying));
        int task = activity.getTaskId();
        action(() -> {
            android.app.ActivityManager manager = (android.app.ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            for (android.app.ActivityManager.AppTask appTask : manager.getAppTasks())
                if (appTask.getTaskInfo().taskId == task) appTask.finishAndRemoveTask();
        });
        waitFor(() -> main(() -> !player.isPlaying()));
        assertFalse(main(player::isLoading));
        waitFor(() -> java.util.Arrays.stream(((android.app.NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE))
                .getActiveNotifications()).noneMatch(notification -> notification.getId() == cl.arichard.descargador.service.MusicService.NOTIFICATION_ID));
    }

    @Test public void tappingOutsideTheInlineQueueCollapsesItSmoothlyWithoutTriggeringThePlayer() throws Exception {
        library();
        List<Song> songs = MusicScanner.scanAllMusic(context);
        action(() -> player.playSong(songs.get(0), new ArrayList<>(songs.subList(0, 5))));
        NowPlayingActivity activity = (NowPlayingActivity) launch(NowPlayingActivity.class);
        view("seekBar");
        com.google.android.material.bottomsheet.BottomSheetBehavior<View> behavior = main(() ->
                com.google.android.material.bottomsheet.BottomSheetBehavior.from(activity.findViewById(R.id.queueSheet)));
        List<Float> offsets = new java.util.concurrent.CopyOnWriteArrayList<>();
        com.google.android.material.bottomsheet.BottomSheetBehavior.BottomSheetCallback callback =
                new com.google.android.material.bottomsheet.BottomSheetBehavior.BottomSheetCallback() {
                    @Override public void onStateChanged(View sheet, int state) {}
                    @Override public void onSlide(View sheet, float offset) { offsets.add(offset); }
                };
        action(() -> behavior.setState(com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED));
        waitFor(() -> main(() -> behavior.getState() == com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED));
        action(() -> behavior.addBottomSheetCallback(callback));
        android.graphics.Rect outside = view("btnNowPlayingFavorite").getVisibleBounds();
        boolean favorite = cl.arichard.descargador.data.PlaylistManager.getInstance(context).isFavorite(songs.get(0).getPath());
        device.click(outside.centerX(), outside.centerY());
        waitFor(() -> main(() -> behavior.getState() == com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_COLLAPSED));
        assertTrue("Native settling animation has intermediate frames", offsets.stream().anyMatch(value -> value > 0f && value < 1f));
        assertEquals(favorite, cl.arichard.descargador.data.PlaylistManager.getInstance(context).isFavorite(songs.get(0).getPath()));
        assertFalse(activity.isFinishing());
        assertEquals(1f, main(() -> activity.findViewById(R.id.nowPlayingRoot).getAlpha()), .01f);
    }

    @Test public void onlineAutoplayAddsRelatedSongsAndAdvancesAfterCompletion() throws Exception {
        library();
        List<String> errors = new java.util.concurrent.CopyOnWriteArrayList<>();
        PlayerManager.PlaybackListener listener = new PlayerManager.PlaybackListener() {
            @Override public void onTrackChanged(Song song) {}
            @Override public void onPlaybackStateChanged(boolean playing) {}
            @Override public void onProgressUpdate(int position, int duration) {}
            @Override public void onPlaybackError(String error) { errors.add(error); }
        };
        action(() -> {
            player.addListener(listener);
            while (player.getRepeatMode() != PlayerManager.REPEAT_OFF) player.toggleRepeat();
            player.setAutoplay(true);
            player.playOnline("n0vo-mLGe6I", "No sabía", "Luis Jara", "", "https://www.youtube.com/watch?v=n0vo-mLGe6I");
        });
        try {
            waitFor(() -> main(() -> player.getQueue().size() > 1 && player.isPlaying()), 90000);
            assertTrue("No playback errors: " + errors, errors.isEmpty());
            List<Song> queue = main(() -> new ArrayList<>(player.getQueue()));
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (Song song : queue) {
                assertTrue("No repeated online songs", seen.add(song.getPath()));
                assertTrue("No long mixes", song.getDuration() <= 600000);
            }
            action(() -> player.seekTo(Math.max(0, player.getDuration() - 1200)));
            waitFor(() -> main(() -> !"n0vo-mLGe6I".equals(player.getCurrentOnlineVideoId()) && player.isPlaying()), 90000);
            assertTrue("No playback errors: " + errors, errors.isEmpty());
        } finally { action(() -> { player.removeListener(listener); player.stopAndRelease(); }); }
    }

    @Test public void downloadContinuesAfterRemovingTheTaskAndRecordsTheFinishedFile() throws Exception {
        MainActivity activity = library();
        DownloadManager manager = DownloadManager.get(context);
        File destination = new File(context.getCacheDir(), "download-device-test");
        assertTrue(destination.isDirectory() || destination.mkdir());
        try (WaveServer server = new WaveServer()) {
            String url = "http://127.0.0.1:" + server.port() + "/musicbox-download-test.wav";
            int added = manager.enqueue(java.util.Collections.singletonList(url),
                    new cl.arichard.descargador.download.DownloadOptions(true, "192 kbps", false, "", "", destination.getAbsolutePath()));
            assertEquals(1, added);
            android.app.NotificationManager notifications = (android.app.NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            waitFor(() -> java.util.Arrays.stream(notifications.getActiveNotifications()).anyMatch(n -> n.getId() == 4410));
            action(() -> {
                android.app.ActivityManager activities = (android.app.ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
                for (android.app.ActivityManager.AppTask task : activities.getAppTasks())
                    if (task.getTaskInfo().taskId == activity.getTaskId()) task.finishAndRemoveTask();
            });
            waitFor(() -> !manager.isBusy(), 120000);
            cl.arichard.descargador.download.DownloadJob job = manager.jobs().stream().filter(j -> j.url.equals(url)).findFirst().get();
            assertEquals("Download detail: " + job.error, cl.arichard.descargador.download.DownloadJob.Status.DONE, job.status);
            assertEquals(1, job.filesDone + job.filesExisting);
            File output = null;
            for (DownloadManager.ReadyFile file : manager.readyFiles())
                if (file.path.startsWith(destination.getAbsolutePath() + "/")) output = new File(file.path);
            assertNotNull("Finished MP3", output);
            assertTrue(output.isFile() && output.length() > 0 && output.getName().endsWith(".mp3"));
            waitFor(() -> java.util.Arrays.stream(notifications.getActiveNotifications()).noneMatch(n -> n.getId() == 4410));
            assertTrue(output.delete());
            assertTrue(destination.delete());
        } finally { manager.cancelAll(); }
    }

    /** Audio de silencio generado por la prueba y servido solo por loopback; no descarga música ajena. */
    private static final class WaveServer implements AutoCloseable {
        private final java.net.ServerSocket server = new java.net.ServerSocket(0, 8, java.net.InetAddress.getByName("127.0.0.1"));
        private final byte[] wave;
        private volatile boolean running = true;
        WaveServer() throws Exception {
            java.nio.ByteBuffer bytes = java.nio.ByteBuffer.allocate(44 + 16000 * 2 * 20).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            bytes.put("RIFF".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt(bytes.capacity() - 8)
                    .put("WAVEfmt ".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt(16).putShort((short) 1)
                    .putShort((short) 1).putInt(16000).putInt(32000).putShort((short) 2).putShort((short) 16)
                    .put("data".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt(bytes.capacity() - 44);
            wave = bytes.array();
            Thread thread = new Thread(() -> {
                while (running) try (java.net.Socket socket = server.accept()) {
                    socket.setSoTimeout(5000);
                    java.io.BufferedReader input = new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream()));
                    String first = input.readLine(), line;
                    if (first == null) continue;
                    while ((line = input.readLine()) != null && !line.isEmpty()) { /* cabeceras */ }
                    java.io.OutputStream out = socket.getOutputStream();
                    out.write(("HTTP/1.1 200 OK\r\nContent-Type: audio/wav\r\nContent-Length: " + wave.length
                            + "\r\nConnection: close\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                    out.flush();
                    if (!first.startsWith("HEAD ")) for (int i = 0; i < wave.length && running; i += 8192) {
                        out.write(wave, i, Math.min(8192, wave.length - i)); out.flush(); Thread.sleep(80);
                    }
                } catch (Exception ignored) { /* El extractor puede cerrar su lectura de prueba antes de terminar. */ }
            }, "test-wave-server");
            thread.setDaemon(true); thread.start();
        }
        int port() { return server.getLocalPort(); }
        @Override public void close() throws Exception { running = false; server.close(); }
    }
}
