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
                    EdgeBackLayout root = (EdgeBackLayout) content.getChildAt(0);
                    float density = activity.getResources().getDisplayMetrics().density;
                    long now = SystemClock.uptimeMillis();
                    for (int step = 0; step < 3; step++) {
                        int action = step == 0 ? MotionEvent.ACTION_DOWN : step == 1 ? MotionEvent.ACTION_MOVE : MotionEvent.ACTION_UP;
                        MotionEvent event = MotionEvent.obtain(now, now + step * 150, action,
                                step == 0 ? 8 * density : root.getWidth() * .7f, root.getHeight() * .85f, 0);
                        root.dispatchTouchEvent(event); event.recycle();
                    }
                });
                // Android 13 entrega el cierre de Atrás mediante una llamada asíncrona del sistema.
                long closeDeadline = SystemClock.uptimeMillis() + 5000;
                while (scenario.getState() != androidx.lifecycle.Lifecycle.State.DESTROYED
                        && SystemClock.uptimeMillis() < closeDeadline) SystemClock.sleep(30);
                assertEquals("Atrás en " + screen.getSimpleName(), androidx.lifecycle.Lifecycle.State.DESTROYED, scenario.getState());
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
