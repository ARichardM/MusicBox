package cl.arichard.descargador.util;

import android.app.Application;
import android.graphics.Bitmap;
import android.os.Looper;
import android.widget.TextView;
import androidx.core.graphics.drawable.RoundedBitmapDrawable;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class ArtistAvatarLoaderTest {
    private Application app;
    @Before public void setup() {
        app = RuntimeEnvironment.getApplication();
        app.getSharedPreferences("artist_avatar_urls", 0).edit().clear().commit();
    }
    private void idle() { Shadows.shadowOf(Looper.getMainLooper()).idle(); }
    private Bitmap photo() { return Bitmap.createBitmap(40, 30, Bitmap.Config.ARGB_8888); }

    @Test public void oneLookupServesMultipleViewsAndSurvivesRebinding() {
        List<Runnable> jobs = new ArrayList<>();
        AtomicInteger lookups = new AtomicInteger();
        Bitmap bitmap = photo();
        ArtistAvatarLoader loader = new ArtistAvatarLoader(app, jobs::add,
                artist -> { lookups.incrementAndGet(); return "https://example.invalid/artist"; },
                (url, callback) -> callback.accept(bitmap));
        TextView first = new TextView(app), second = new TextView(app);
        loader.load("Ana", first, 20); loader.load("ana", second, 20);
        assertEquals(1, jobs.size());
        jobs.remove(0).run(); idle();
        assertSame(bitmap, ((RoundedBitmapDrawable) first.getCompoundDrawablesRelative()[0]).getBitmap());
        assertSame(bitmap, ((RoundedBitmapDrawable) second.getCompoundDrawablesRelative()[0]).getBitmap());
        assertEquals(20, first.getCompoundDrawablesRelative()[0].getBounds().width());
        loader.load("ANA", new TextView(app), 20); idle();
        assertEquals(1, lookups.get());
        assertTrue(jobs.isEmpty());
    }

    @Test public void latePhotoCannotReplaceCurrentArtistAndClearingRemovesPortrait() {
        List<Runnable> jobs = new ArrayList<>();
        Map<String, Consumer<Bitmap>> replies = new HashMap<>();
        ArtistAvatarLoader loader = new ArtistAvatarLoader(app, jobs::add,
                artist -> artist, (url, callback) -> replies.put(url, callback));
        TextView target = new TextView(app);
        android.graphics.drawable.Drawable arrow = app.getDrawable(cl.arichard.descargador.R.drawable.ic_artist_dropdown);
        target.setCompoundDrawablesRelative(null, null, arrow, null);
        loader.load("Ana", target, 20); loader.load("Otro", target, 20);
        for (Runnable job : jobs) job.run(); idle();
        replies.get("Ana").accept(photo()); idle();
        assertFalse(target.getCompoundDrawablesRelative()[0] instanceof RoundedBitmapDrawable);
        Bitmap current = photo(); replies.get("Otro").accept(current); idle();
        assertSame(current, ((RoundedBitmapDrawable) target.getCompoundDrawablesRelative()[0]).getBitmap());
        assertSame(arrow, target.getCompoundDrawablesRelative()[2]);
        loader.load("", target, 20);
        assertFalse(target.getCompoundDrawablesRelative()[0] instanceof RoundedBitmapDrawable);
    }

    @Test public void offlineFailureKeepsFallbackAndDoesNotRetryOnEveryRefresh() {
        List<Runnable> jobs = new ArrayList<>();
        AtomicInteger lookups = new AtomicInteger();
        ArtistAvatarLoader loader = new ArtistAvatarLoader(app, jobs::add,
                artist -> { lookups.incrementAndGet(); throw new java.io.IOException("offline"); },
                (url, callback) -> fail("No image request without an artist URL"));
        TextView target = new TextView(app);
        loader.load("Ana", target, 20); jobs.remove(0).run(); idle();
        loader.load("Ana", new TextView(app), 20); idle();
        assertEquals(1, lookups.get());
        assertFalse(target.getCompoundDrawablesRelative()[0] instanceof RoundedBitmapDrawable);
        assertTrue(jobs.isEmpty());
    }
}
