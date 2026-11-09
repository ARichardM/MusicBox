package cl.arichard.descargador;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.ViewGroup;
import android.widget.EditText;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.UiDevice;
import cl.arichard.descargador.util.EdgeBackLayout;
import cl.arichard.descargador.util.SharedSongSearch;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import static org.junit.Assume.*;

/** Verifica la navegación real sin cuenta, archivos ni playlists del usuario. */
@RunWith(AndroidJUnit4.class)
public class ReleaseNavigationDeviceTest {
    @Test public void secondaryScreensUseTheirExistingBackActionFromTheEdge() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assumeTrue(context.getPackageName().endsWith(".verification"));
        for (Class<? extends Activity> screen : new Class[]{DownloaderActivity.class, ImporterActivity.class,
                SettingsActivity.class, StatisticsActivity.class, ArtistActivity.class}) {
            try (ActivityScenario<?> scenario = ActivityScenario.launch(new Intent(context, screen))) {
                long layoutDeadline = SystemClock.uptimeMillis() + 5000;
                boolean[] laidOut = {false};
                do {
                    scenario.onActivity(activity -> laidOut[0] = activity.findViewById(android.R.id.content).getWidth() > 0);
                    if (!laidOut[0]) SystemClock.sleep(30);
                } while (!laidOut[0] && SystemClock.uptimeMillis() < layoutDeadline);
                assertTrue("Pantalla dibujada: " + screen.getSimpleName(), laidOut[0]);
                scenario.onActivity(activity -> {
                    ViewGroup content = activity.findViewById(android.R.id.content);
                    assertTrue(screen.getSimpleName(), content.getChildAt(0) instanceof EdgeBackLayout);
                });
                UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
                int y = device.getDisplayHeight() / 2;
                assertTrue("Gesto físico inyectado en " + screen.getSimpleName(),
                        device.swipe(120, y, Math.min(700, device.getDisplayWidth() - 40), y, 18));
                // Android 13 entrega Atrás de forma asíncrona. Cuando ActivityScenario
                // inicia una pantalla como raíz de tarea, Atrás puede mandarla al fondo
                // (STARTED) en vez de destruirla; en ambos casos ya fue descartada.
                long closeDeadline = SystemClock.uptimeMillis() + 5000;
                while (scenario.getState() == androidx.lifecycle.Lifecycle.State.RESUMED
                        && SystemClock.uptimeMillis() < closeDeadline) SystemClock.sleep(30);
                assertNotEquals("Atrás en " + screen.getSimpleName(),
                        androidx.lifecycle.Lifecycle.State.RESUMED, scenario.getState());
            }
        }
    }

    @Test public void shazamSearchReachesTheSearchFieldWithoutOpeningDownloads() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assumeTrue(context.getPackageName().endsWith(".verification"));
        Intent original = new Intent(Intent.ACTION_SEND).setType("text/plain");
        Intent routed = SharedSongSearch.searchIntent(context, original,
                "Freakin' Out (Oops All Drop) by Dexter and The Moonrocks https://www.shazam.com/track/873158167/freakin-out-oops-all-drop?referrer=share");
        // La variante aislada abre la misma pantalla sin exigir una cuenta Firebase.
        routed.setClass(context, PreviewMainActivity.class);
        try (ActivityScenario<?> scenario = ActivityScenario.launch(routed)) {
            scenario.onActivity(activity -> {
                EditText input = activity.findViewById(R.id.inputSearch);
                assertEquals("Freakin' Out (Oops All Drop) Dexter and The Moonrocks", input.getText().toString());
                assertEquals(android.view.View.VISIBLE, activity.findViewById(R.id.tabSearch).getVisibility());
            });
        }
    }
}
