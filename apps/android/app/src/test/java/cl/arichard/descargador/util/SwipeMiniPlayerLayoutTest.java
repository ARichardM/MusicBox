package cl.arichard.descargador.util;

import android.app.Application;
import android.view.MotionEvent;
import android.widget.FrameLayout;
import android.widget.TextView;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class SwipeMiniPlayerLayoutTest {
    @Test public void swipesOnPlayerBackgroundWorkAndCancelledOrDiagonalDragsDoNothing() {
        org.robolectric.android.controller.ActivityController<android.app.Activity> controller =
                org.robolectric.Robolectric.buildActivity(android.app.Activity.class).setup().visible();
        SwipeActionsLayout layout = new SwipeActionsLayout(controller.get(), null);
        FrameLayout parent = new FrameLayout(controller.get()); parent.addView(layout);
        controller.get().setContentView(parent); layout.layout(0, 0, 800, 500);
        int[] actions = {0}; layout.setSwipeActions(() -> actions[0]++, () -> actions[0]++, () -> actions[0]++);
        gesture(layout, 600, 50, 100, 50); assertEquals(1, actions[0]);
        gesture(layout, 100, 50, 300, 250); assertEquals(1, actions[0]);
        long now = android.os.SystemClock.uptimeMillis();
        for (int action : new int[]{MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_CANCEL}) {
            MotionEvent event = MotionEvent.obtain(now, now + 20, action, action == MotionEvent.ACTION_DOWN ? 600 : 100, 50, 0);
            layout.dispatchTouchEvent(event); event.recycle();
        }
        assertEquals(1, actions[0]); controller.pause().stop().destroy();
    }
    @Test public void swipesOverChildButtonsChangeTracksWithoutTriggeringTheirClicks() {
        org.robolectric.android.controller.ActivityController<android.app.Activity> controller =
                org.robolectric.Robolectric.buildActivity(android.app.Activity.class).setup().visible();
        SwipeActionsLayout layout = new SwipeActionsLayout(controller.get(), null);
        FrameLayout parent = new FrameLayout(layout.getContext()); parent.addView(layout);
        controller.get().setContentView(parent);
        TextView button = new TextView(layout.getContext());
        int[] next = {0}, previous = {0}, expand = {0}, taps = {0};
        button.setOnClickListener(v -> taps[0]++);
        layout.addView(button, new android.widget.LinearLayout.LayoutParams(800, 100));
        layout.layout(0, 0, 800, 100); button.layout(0, 0, 800, 100);
        layout.setSwipeActions(() -> next[0]++, () -> previous[0]++, () -> expand[0]++);
        gesture(layout, 600, 50, 100, 50);
        assertEquals(1, next[0]); assertEquals(0, taps[0]);
        gesture(layout, 100, 50, 600, 50);
        assertEquals(1, previous[0]); assertEquals(0, expand[0]);
        gesture(layout, 100, 50, 100, 50);
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        assertEquals(1, taps[0]);
        controller.pause().stop().destroy();
    }
    private void gesture(SwipeActionsLayout layout, float x1, float y1, float x2, float y2) {
        long now = android.os.SystemClock.uptimeMillis();
        int step = 0;
        for (int action : new int[]{MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP}) {
            MotionEvent event = MotionEvent.obtain(now, now + step++ * 20, action,
                    action == MotionEvent.ACTION_DOWN ? x1 : x2, action == MotionEvent.ACTION_DOWN ? y1 : y2, 0);
            layout.dispatchTouchEvent(event); event.recycle();
        }
    }

    @Test public void shortUpwardSwipeAndImpreciseSwipeExpandWithoutClickingButtons() {
        try (org.robolectric.android.controller.ActivityController<android.app.Activity> controller =
                org.robolectric.Robolectric.buildActivity(android.app.Activity.class).setup().visible()) {
            SwipeActionsLayout layout = new SwipeActionsLayout(controller.get(), null);
            FrameLayout parent = new FrameLayout(controller.get()); parent.addView(layout);
            controller.get().setContentView(parent);
            TextView button = new TextView(controller.get());
            int[] expands = {0}, taps = {0}, tracks = {0};
            button.setOnClickListener(v -> taps[0]++);
            layout.addView(button, new android.widget.LinearLayout.LayoutParams(800, 200));
            layout.layout(0, 0, 800, 200); button.layout(0, 0, 800, 200);
            layout.setSwipeActions(() -> tracks[0]++, () -> tracks[0]++, () -> expands[0]++);
            float density = layout.getResources().getDisplayMetrics().density;
            // Shorter than the old 48dp swipe, and far shorter than the old full-player drag.
            timedGesture(layout, 200, 100, 204, 100 - 36 * density, 32, false);
            assertEquals(1, expands[0]); assertEquals(0, taps[0]); assertEquals(0, tracks[0]);
            // A slow swipe can drift sideways; it need not follow a perfectly vertical line.
            timedGesture(layout, 200, 100, 265, 100 - 90 * density, 500, false);
            assertEquals(2, expands[0]); assertEquals(0, taps[0]); assertEquals(0, tracks[0]);
            // Small movements, equal diagonals, downward swipes and cancellation do nothing.
            timedGesture(layout, 200, 100, 200, 100 - 24 * density, 650, false);
            timedGesture(layout, 200, 100, 280, 20, 100, false);
            timedGesture(layout, 200, 30, 200, 150, 100, false);
            timedGesture(layout, 200, 100, 200, 10, 100, true);
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            assertEquals(2, expands[0]); assertEquals(0, taps[0]); assertEquals(0, tracks[0]);
            gesture(layout, 200, 50, 200, 50);
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            assertEquals(1, taps[0]);
        }
    }

    private void timedGesture(SwipeActionsLayout layout, float x1, float y1, float x2, float y2,
                              long duration, boolean cancelled) {
        long start = android.os.SystemClock.uptimeMillis();
        for (int step = 0; step <= 5; step++) {
            int action = step == 0 ? MotionEvent.ACTION_DOWN : step == 5
                    ? (cancelled ? MotionEvent.ACTION_CANCEL : MotionEvent.ACTION_UP) : MotionEvent.ACTION_MOVE;
            float fraction = step / 5f;
            MotionEvent event = MotionEvent.obtain(start, start + duration * step / 5, action,
                    x1 + (x2 - x1) * fraction, y1 + (y2 - y1) * fraction, 0);
            layout.dispatchTouchEvent(event);
            event.recycle();
        }
    }
}
