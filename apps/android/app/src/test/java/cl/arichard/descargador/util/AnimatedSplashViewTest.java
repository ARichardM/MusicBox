package cl.arichard.descargador.util;

import android.app.Activity;
import android.os.Looper;
import android.widget.FrameLayout;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import java.time.Duration;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class AnimatedSplashViewTest {
    @Test public void stoppingCancelsTheScheduledDismissal() {
        try (org.robolectric.android.controller.ActivityController<Activity> controller =
                     Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            FrameLayout parent = new FrameLayout(activity);
            activity.setContentView(parent);
            AnimatedSplashView splash = new AnimatedSplashView(activity);
            parent.addView(splash);
            splash.start();
            splash.stop();
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3));
            assertSame(parent, splash.getParent());
            assertEquals(1f, splash.getAlpha(), .01f);
        }
    }
}
