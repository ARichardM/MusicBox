package cl.arichard.descargador;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;
import android.widget.EditText;
import android.os.SystemClock;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** Prueba el contrato real del menú Compartir sin descargar ni modificar playlists. */
@RunWith(AndroidJUnit4.class)
public class SharedMusicDeviceTest {
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private final UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());

    @Before public void avoidPermissionPromptsFromBlockingNavigation() {
        context.getSharedPreferences("downloader", Context.MODE_PRIVATE).edit()
                .putBoolean("asked_notifications", true).commit();
        context.getSharedPreferences("musicbox_prefs", Context.MODE_PRIVATE).edit()
                .putBoolean("all_permissions_requested_v1", true).commit();
    }

    private Intent share(String text) {
        return new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
                .setPackage(context.getPackageName()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    private <T extends Activity> T resumed(Class<T> type) {
        long deadline = SystemClock.uptimeMillis() + 10000;
        do {
            final Activity[] match = {null};
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                for (Activity activity : ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED))
                    if (type.isInstance(activity)) match[0] = activity;
            });
            if (match[0] != null) return type.cast(match[0]);
            SystemClock.sleep(50);
        } while (SystemClock.uptimeMillis() < deadline);
        return null;
    }

    @Test public void shareChooserRoutesPlaylistArtistAndTrackOnThePhone() throws Exception {
        assumeTrue(context.getPackageName().endsWith(".verification"));

        Activity artistEntry = InstrumentationRegistry.getInstrumentation().startActivitySync(
                share("The Weeknd on Spotify https://open.spotify.com/artist/1Xyo4u8uXC1ZmMpatF05PJ"));
        PreviewMainActivity searchActivity = resumed(PreviewMainActivity.class);
        assertNotNull("El artista abre la búsqueda", searchActivity);
        assertEquals("The Weeknd", ((EditText) searchActivity.findViewById(R.id.inputSearch)).getText().toString());
        artistEntry.finish(); device.pressBack();

        String track = "Blinding Lights by The Weeknd on Spotify https://open.spotify.com/track/0VjIjW4GlUZAMYd2vXMi3b";
        Activity trackEntry = InstrumentationRegistry.getInstrumentation().startActivitySync(share(track));
        PreviewMainActivity trackSearch = resumed(PreviewMainActivity.class);
        assertNotNull("La canción de Spotify abre la búsqueda", trackSearch);
        assertEquals("Blinding Lights by The Weeknd",
                ((EditText) trackSearch.findViewById(R.id.inputSearch)).getText().toString());
        trackEntry.finish();
        device.pressBack();

        // La lectura de red de la playlist queda al final para no interferir con las otras rutas.
        Intent playlist = share("https://open.spotify.com/playlist/37i9dQZF1DX4WYpdgoIcn6");
        assertEquals(DownloaderActivity.class.getName(), context.getPackageManager().resolveActivity(playlist, 0).activityInfo.name);
        Activity opened = InstrumentationRegistry.getInstrumentation().startActivitySync(playlist);
        // Compartir una URL conocida salta el descargador; no se confirma la importación.
        assertEquals("", ((EditText) opened.findViewById(R.id.inputLinks)).getText().toString());
        opened.finish();
    }
}
