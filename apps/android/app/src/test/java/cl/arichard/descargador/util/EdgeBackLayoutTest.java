package cl.arichard.descargador.util;

import android.app.Application;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import androidx.appcompat.app.AppCompatActivity;
import cl.arichard.descargador.R;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class EdgeBackLayoutTest {
    public static class Host extends AppCompatActivity {
        @Override protected void onCreate(Bundle state) { setTheme(R.style.Theme_MusicBox); super.onCreate(state); }
    }

    @Test public void onlyCompletedRightwardEdgeGestureGoesBack() {
        try (ActivityController<Host> controller = Robolectric.buildActivity(Host.class).setup().visible()) {
            EdgeBackLayout root = new EdgeBackLayout(controller.get());
            View child = new View(controller.get()); child.setClickable(true);
            root.addView(child, new FrameLayout.LayoutParams(-1, -1));
            controller.get().setContentView(root);
            root.layout(0, 0, 800, 600); child.layout(0, 0, 800, 600);
            int[] backs = {0}; root.setBackAction(() -> backs[0]++, () -> true);
            drag(root, 200, 200, 600, 200, MotionEvent.ACTION_UP);
            drag(root, 8, 200, 30, 500, MotionEvent.ACTION_UP);
            drag(root, 8, 200, 40, 200, MotionEvent.ACTION_UP);
            drag(root, 8, 200, 500, 200, MotionEvent.ACTION_CANCEL);
            assertEquals(0, backs[0]);
            drag(root, 8, 200, 500, 200, MotionEvent.ACTION_UP);
            assertEquals(1, backs[0]);
            // También funciona justo dentro del borde reservado por la navegación Samsung.
            drag(root, 72, 200, 220, 200, MotionEvent.ACTION_UP);
            assertEquals(2, backs[0]);
            drag(root, 140, 200, 260, 200, MotionEvent.ACTION_UP);
            assertEquals(3, backs[0]);
            assertEquals(0f, root.getTranslationX(), .01f);
            root.setBackAction(() -> backs[0]++, () -> false);
            drag(root, 8, 200, 500, 200, MotionEvent.ACTION_UP);
            assertEquals(3, backs[0]);
            root.setBackAction(() -> backs[0]++, () -> true);
            android.widget.SeekBar seek = new android.widget.SeekBar(controller.get());
            root.addView(seek, new FrameLayout.LayoutParams(-1, 100));
            seek.layout(0, 0, 800, 100);
            drag(root, 8, 40, 500, 40, MotionEvent.ACTION_UP);
            assertEquals(3, backs[0]);
        }
    }

    @Test public void wideEdgeYieldsToContentSwipeButNarrowEdgeAlwaysGoesBack() {
        try (ActivityController<Host> controller = Robolectric.buildActivity(Host.class).setup().visible()) {
            EdgeBackLayout root = new EdgeBackLayout(controller.get());
            View child = new View(controller.get()); child.setClickable(true);
            root.addView(child, new FrameLayout.LayoutParams(-1, -1));
            controller.get().setContentView(root);
            root.layout(0, 0, 800, 600); child.layout(0, 0, 800, 600);
            int[] backs = {0}; root.setBackAction(() -> backs[0]++, () -> true);
            boolean[] contentCanSwipe = {true};
            root.setContentSwipePriority(() -> contentCanSwipe[0]);
            // Con una playlist anterior a la que ir, la franja ancha es del contenido: no vuelve.
            drag(root, 140, 200, 400, 200, MotionEvent.ACTION_UP);
            assertEquals(0, backs[0]);
            // Junto al borde sigue siendo "volver" aunque el contenido pueda deslizar.
            drag(root, 8, 200, 400, 200, MotionEvent.ACTION_UP);
            assertEquals(1, backs[0]);
            // Sin nada a lo que deslizar (primera playlist), la franja ancha vuelve a ser "volver".
            contentCanSwipe[0] = false;
            drag(root, 140, 200, 400, 200, MotionEvent.ACTION_UP);
            assertEquals(2, backs[0]);
        }
    }

    @Test public void attachPreservesContentAndExistingBackCallbackAndDoesNotWrapTwice() {
        try (ActivityController<Host> controller = Robolectric.buildActivity(Host.class).setup().visible()) {
            Host activity = controller.get();
            View child = new View(activity); activity.setContentView(child);
            int[] backs = {0};
            activity.getOnBackPressedDispatcher().addCallback(activity, new androidx.activity.OnBackPressedCallback(true) {
                @Override public void handleOnBackPressed() { backs[0]++; }
            });
            EdgeBackLayout.attach(activity); EdgeBackLayout.attach(activity);
            FrameLayout content = activity.findViewById(android.R.id.content);
            assertEquals(1, content.getChildCount());
            EdgeBackLayout root = (EdgeBackLayout) content.getChildAt(0);
            assertSame(child, root.getChildAt(0));
            root.layout(0, 0, 800, 600); child.layout(0, 0, 800, 600);
            drag(root, 8, 200, 500, 200, MotionEvent.ACTION_UP);
            assertEquals(1, backs[0]);
        }
    }

    private void drag(View root, float x, float y, float endX, float endY, int end) {
        long now = android.os.SystemClock.uptimeMillis();
        int i = 0;
        for (int action : new int[]{MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, end}) {
            MotionEvent event = MotionEvent.obtain(now, now + i++ * 100, action,
                    action == MotionEvent.ACTION_DOWN ? x : endX, action == MotionEvent.ACTION_DOWN ? y : endY, 0);
            root.dispatchTouchEvent(event); event.recycle();
        }
    }
}
