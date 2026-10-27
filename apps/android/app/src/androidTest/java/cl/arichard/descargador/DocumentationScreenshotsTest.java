package cl.arichard.descargador;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.util.LruCache;
import android.view.View;
import android.widget.EditText;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.Until;
import cl.arichard.descargador.adapter.SongAdapter;
import cl.arichard.descargador.model.Song;
import cl.arichard.descargador.player.PlayerManager;
import cl.arichard.descargador.util.CoverArtLoader;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.After;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import static org.junit.Assume.*;

/** Opt-in, offline y exclusivo de verification. Nunca exporta una biblioteca escaneada. */
@RunWith(AndroidJUnit4.class)
public class DocumentationScreenshotsTest {
    private androidx.core.os.LocaleListCompat previousLocales;
    private Activity libraryActivity, playerActivity;

    @After public void cleanup() {
        if (previousLocales == null) return;
        main(() -> {
            if (playerActivity != null) playerActivity.finish();
            if (libraryActivity != null) libraryActivity.finish();
            PlayerManager.getInstance().stopAndRelease();
            androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(previousLocales);
        });
    }
    private static Field field(Object object, String name) throws Exception {
        Class<?> type = object.getClass();
        while (type != null) {
            try { Field result = type.getDeclaredField(name); result.setAccessible(true); return result; }
            catch (NoSuchFieldException missing) { type = type.getSuperclass(); }
        }
        throw new NoSuchFieldException(name);
    }
    private static void set(Object object, String name, Object value) throws Exception { field(object, name).set(object, value); }
    private static void invoke(Object object, String name) throws Exception {
        Method method = MainActivity.class.getDeclaredMethod(name); method.setAccessible(true); method.invoke(object);
    }
    private static void main(Runnable action) { InstrumentationRegistry.getInstrumentation().runOnMainSync(action); }
    private static void fullscreen(Activity activity) {
        activity.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (android.os.Build.VERSION.SDK_INT >= 27) {
            activity.setShowWhenLocked(true); activity.setTurnScreenOn(true);
        }
        activity.getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }
    private static void capture(Activity activity, File output) throws Exception {
        Thread.sleep(1200);
        AtomicReference<Bitmap> captured = new AtomicReference<>();
        main(() -> {
            View content = activity.findViewById(android.R.id.content);
            Bitmap image = Bitmap.createBitmap(content.getWidth(), content.getHeight(), Bitmap.Config.ARGB_8888);
            content.draw(new Canvas(image)); captured.set(image);
        });
        try (FileOutputStream stream = new FileOutputStream(output)) {
            assertTrue(captured.get().compress(Bitmap.CompressFormat.PNG, 100, stream));
        }
        captured.get().recycle();
    }

    @SuppressWarnings("unchecked")
    @Test public void captureDemoLibraryAndPlayer() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assumeTrue("No se ejecuta en una instalación real", context.getPackageName().endsWith(".verification"));
        assumeTrue("Capturas solo bajo solicitud explícita", "true".equals(
                InstrumentationRegistry.getArguments().getString("documentationScreenshots")));
        File folder = new File(context.getExternalFilesDir(null), "documentation");
        JSONObject fixture = new JSONObject(new String(Files.readAllBytes(new File(folder, "library.json").toPath()), StandardCharsets.UTF_8));
        JSONArray rows = fixture.getJSONArray("songs");
        assertEquals(9, rows.length());
        List<Song> songs = new ArrayList<>();
        CoverArtLoader loader = CoverArtLoader.getInstance();
        LruCache<String, Bitmap> cache = (LruCache<String, Bitmap>) field(loader, "cache").get(loader);
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.getJSONObject(i);
            String path = new File(context.getFilesDir(), "documentation/" + row.getString("id") + ".mp3").getPath();
            songs.add(new Song(90000+i, row.getString("title"), row.getString("artist"), row.getString("album"),
                    row.getLong("duration")*1000, path, "MusicBox Demo", 0));
            Bitmap cover = BitmapFactory.decodeFile(new File(folder, "cover-" + i + ".png").getPath());
            assertNotNull(cover); cache.put(path, cover);
        }
        PlayerManager player = PlayerManager.getInstance();
        main(player::stopAndRelease);
        previousLocales = androidx.appcompat.app.AppCompatDelegate.getApplicationLocales();
        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        device.wakeUp(); device.pressHome();
        Activity initial = InstrumentationRegistry.getInstrumentation().startActivitySync(
                new Intent(context, PreviewMainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        libraryActivity = initial;
        main(() -> { fullscreen(initial); androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(
                androidx.core.os.LocaleListCompat.forLanguageTags("es")); });
        Thread.sleep(800);
        MainActivity activity = (MainActivity) InstrumentationRegistry.getInstrumentation().startActivitySync(
                new Intent(context, PreviewMainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        libraryActivity = activity;
        assertEquals("es", activity.getResources().getConfiguration().getLocales().get(0).getLanguage());
        main(() -> fullscreen(activity));
        assertNotNull(device.wait(Until.findObject(By.res(context.getPackageName(), "navTabLibrary")), 15000));
        device.findObject(By.res(context.getPackageName(), "navTabLibrary")).click();
        Thread.sleep(2200);
        AtomicReference<Exception> failure = new AtomicReference<>();
        main(() -> {
            try {
                // Invalidar escaneos pendientes y sustituir todos los datos antes de exportar píxeles.
                set(activity, "scanGeneration", (int)field(activity,"scanGeneration").get(activity)+1);
                List<Song> library = (List<Song>)field(activity,"allSongs").get(activity);
                library.clear(); library.addAll(songs);
                MainUiState state = (MainUiState)field(activity,"uiState").get(activity);
                state.library.clear(); state.library.addAll(songs); state.libraryLoaded = true;
                set(activity,"libraryReady",true); set(activity,"currentLibraryMode",0); set(activity,"isViewingSubList",false);
                ((EditText)activity.findViewById(R.id.inputSearchLibrary)).setText("");
                View loading = activity.findViewById(R.id.libraryLoading);
                if (loading != null) loading.setVisibility(View.GONE);
                activity.findViewById(R.id.recyclerLibrary).setVisibility(View.VISIBLE);
                set(player,"queue",new ArrayList<>(songs)); set(player,"originalQueue",new ArrayList<>(songs));
                set(player,"currentSong",songs.get(0)); set(player,"currentIndex",0);
                set(player,"resumePosition",87000); set(player,"savedDuration",244000);
                set(player,"playWhenReady",false);
                player.setPlaybackSource("playlist","Noche electrónica");
                invoke(activity,"updateLibraryHeader"); invoke(activity,"rebuildLibraryContent");
                activity.onTrackChanged(songs.get(0));
                fullscreen(activity);
                SongAdapter adapter = (SongAdapter)((RecyclerView)activity.findViewById(R.id.recyclerLibrary)).getAdapter();
                assertEquals(9,adapter.getItemCount());
                for (Song song : adapter.getDisplayedList()) assertTrue(song.getPath().startsWith(context.getFilesDir()+"/documentation/"));
            } catch (Exception error) { failure.set(error); }
        });
        if (failure.get() != null) throw failure.get();
        capture(activity,new File(folder,"android-library.png"));
        Activity nowPlaying = InstrumentationRegistry.getInstrumentation().startActivitySync(
                new Intent(context,NowPlayingActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        playerActivity = nowPlaying;
        main(() -> fullscreen(nowPlaying));
        assertNotNull(device.wait(Until.findObject(By.res(context.getPackageName(),"txtNowPlayingTitle")),10000));
        capture(nowPlaying,new File(folder,"android-player.png"));
    }
}
