package cl.arichard.descargador.util;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.LayoutInflater;
import android.view.View;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import cl.arichard.descargador.R;
import static org.junit.Assert.*;

/** El anillo de descarga muestra el avance real con su porcentaje, no un giro de espera. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class LoadingRingViewTest {
    @Test public void percentageIsRoundedAndClamped() {
        assertEquals("0%", LoadingRingView.percentText(0f));
        assertEquals("42%", LoadingRingView.percentText(0.419f));
        assertEquals("100%", LoadingRingView.percentText(1.4f));
        assertEquals("0%", LoadingRingView.percentText(-0.2f));
    }

    @Test public void songRowRingSitsWhereTheDownloadedMarkGoesAndDrawsWithProgress() {
        try (org.robolectric.android.controller.ActivityController<Activity> controller =
                     Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            activity.setTheme(R.style.Theme_MusicBox);
            View row = LayoutInflater.from(activity).inflate(R.layout.item_song, null, false);
            LoadingRingView ring = row.findViewById(R.id.progressSongDownload);
            assertSame(row.findViewById(R.id.imgDownloadedBadge).getParent(), ring.getParent());
            ring.setActive(true);
            ring.setProgress(0.42f);
            assertEquals(View.VISIBLE, ring.getVisibility());
            int size = Math.round(16 * activity.getResources().getDisplayMetrics().density);
            ring.measure(View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY));
            ring.layout(0, 0, size, size);
            ring.draw(new Canvas(Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)));
        }
    }
}
