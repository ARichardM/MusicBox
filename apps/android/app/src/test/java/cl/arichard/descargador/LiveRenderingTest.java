package cl.arichard.descargador;

import android.app.Application;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.util.LruCache;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import cl.arichard.descargador.data.ClickStats;
import cl.arichard.descargador.model.Song;
import cl.arichard.descargador.util.CoverArtLoader;
import cl.arichard.descargador.util.ImageLoader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class, qualifiers = "es")
public class LiveRenderingTest {
    private Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true); return field.get(target);
    }
    private Song song(int n) {
        return new Song(n, "Tema " + n, "Artista", "Álbum", 100000, "/test/song" + n + ".mp3", "Test", 0);
    }

    @Test public void listeningUpdatesKeepTheScreenRowsAndScroll() throws Exception {
        ClickStats stats = ClickStats.getInstance(RuntimeEnvironment.getApplication());
        stats.resetForAccount();
        for (int i = 0; i < 20; i++) stats.record(song(i));
        stats.recordListening(song(0), "", 1000, true);
        try (ActivityController<StatisticsActivity> controller = Robolectric.buildActivity(StatisticsActivity.class).setup()) {
            StatisticsActivity activity = controller.get();
            LinearLayout content = (LinearLayout) field(activity, "content");
            ScrollView scroll = (ScrollView) content.getParent();
            scroll.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY));
            scroll.layout(0, 0, 1080, 600); scroll.scrollTo(0, 250);
            Map<?, ?> rows = (Map<?, ?>) field(activity, "rowsByKey");
            String key = "song:" + ClickStats.keyOf(song(0));
            Object original = rows.get(key);
            assertNotNull(original);
            ImageView image = (ImageView) field(original, "image");
            android.graphics.drawable.Drawable cover = image.getDrawable();
            stats.recordListening(song(0), "", 4000, false); stats.flushListening();
            assertSame(content, field(activity, "content"));
            assertSame(scroll, content.getParent());
            assertSame(original, rows.get(key));
            assertSame(cover, image.getDrawable());
            assertEquals(250, scroll.getScrollY());
            assertEquals("5 s", ((android.widget.TextView) field(activity, "totalTime")).getText().toString());
        }
    }

    @Test public void selectedStatisticsTabSurvivesNewCounters() throws Exception {
        ClickStats stats = ClickStats.getInstance(RuntimeEnvironment.getApplication());
        stats.resetForAccount();
        stats.recordListening(song(1), "Mi lista", 1000, true);
        try (ActivityController<StatisticsActivity> controller = Robolectric.buildActivity(StatisticsActivity.class).setup()) {
            StatisticsActivity activity = controller.get();
            activity.findViewById(android.R.id.button2).performClick();
            Object ranking = field(activity, "ranking");
            stats.recordListening(song(1), "Mi lista", 5000, false); stats.flushListening();
            assertEquals(true, field(activity, "playlists"));
            assertSame(ranking, field(activity, "ranking"));
        }
    }

    @Test public void deviceHeartbeatKeepsCardsAndUpdatesOnlyTheirText() throws Exception {
        try (ActivityController<SettingsActivity> controller = Robolectric.buildActivity(SettingsActivity.class)) {
            // Exercise the renderer without account initialization or update/network checks.
            SettingsActivity activity = controller.get();
            LinearLayout container = new LinearLayout(activity);
            container.setId(R.id.layoutConnectedDevices);
            activity.setContentView(container);
            SharedPreferences prefs = activity.getSharedPreferences("musicbox_cloud", 0);
            Field prefField = SettingsActivity.class.getDeclaredField("cloudPrefs");
            prefField.setAccessible(true); prefField.set(activity, prefs);
            JSONObject a = new JSONObject().put("name", "PC de muestra").put("lastSeen", 100000);
            JSONObject b = new JSONObject().put("name", "Notebook de muestra").put("lastSeen", 200000);
            prefs.edit().putString("devices_json", new JSONObject().put("a", a).put("b", b).toString()).commit();
            Method render = SettingsActivity.class.getDeclaredMethod("renderDevices"); render.setAccessible(true); render.invoke(activity);
            View first = container.getChildAt(0), second = container.getChildAt(1);
            a.put("lastSeen", 500000).put("name", "PC renombrado");
            prefs.edit().putString("devices_json", new JSONObject().put("b", b).put("a", a).toString()).commit();
            render.invoke(activity);
            assertSame(first, container.getChildAt(0)); assertSame(second, container.getChildAt(1));
            android.widget.TextView name = (android.widget.TextView) ((LinearLayout) ((LinearLayout) first).getChildAt(0)).getChildAt(0);
            assertEquals("PC renombrado", name.getText().toString());
            prefs.edit().putString("devices_json", new JSONObject().put("b", b).toString()).commit(); render.invoke(activity);
            assertEquals(1, container.getChildCount()); assertSame(second, container.getChildAt(0));
        }
    }

    @Test public void bindingTheSameCoverDoesNotRestartItsDrawable() throws Exception {
        ImageLoader loader = ImageLoader.getInstance();
        LruCache<String, Bitmap> cache = (LruCache<String, Bitmap>) field(loader, "memoryCache");
        String url = "https://example.invalid/stable-cover";
        cache.put(url, Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888));
        ImageView image = new ImageView(RuntimeEnvironment.getApplication());
        CoverArtLoader.getInstance().loadSongCover(url, image);
        android.graphics.drawable.Drawable drawable = image.getDrawable();
        CoverArtLoader.getInstance().loadSongCover(url, image);
        assertSame(drawable, image.getDrawable());
        loader.load(url, image);
        assertSame(drawable, image.getDrawable());
        CoverArtLoader.getInstance().loadSongCover("", image);
        assertEquals("", image.getTag());
        assertNotSame(drawable, image.getDrawable());
    }
}
