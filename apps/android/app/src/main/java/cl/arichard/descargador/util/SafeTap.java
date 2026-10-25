package cl.arichard.descargador.util;

import android.view.MotionEvent;
import android.view.animation.OvershootInterpolator;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import cl.arichard.descargador.R;

/** Cancels a button press as soon as the finger starts dragging, even within its bounds. */
public final class SafeTap {
    private SafeTap() {}
    public static void install(View view) {
        install(view, true);
    }
    public static void installRowTap(View view) { install(view, false); }

    /** Elegant "pop" for toggles such as the heart: a quick squash, then a springy overshoot. */
    public static void pop(View view, boolean activated) {
        view.animate().cancel();
        view.setScaleX(0.7f); view.setScaleY(0.7f);
        view.animate().scaleX(1f).scaleY(1f).setDuration(activated ? 380 : 220)
                .setInterpolator(new OvershootInterpolator(activated ? 4f : 1.5f)).start();
    }

    private static void press(View view, boolean down) {
        view.animate().cancel();
        if (down) view.animate().scaleX(0.82f).scaleY(0.82f).setDuration(90).start();
        else view.animate().scaleX(1f).scaleY(1f).setDuration(260).setInterpolator(new OvershootInterpolator(3f)).start();
    }
    private static void install(View view, boolean protectNavigation) {
        if (protectNavigation) view.setTag(R.id.protectedAction, true);
        int slop = ViewConfiguration.get(view.getContext()).getScaledTouchSlop();
        view.setOnTouchListener(new View.OnTouchListener() {
            float x, y; boolean dragged;
            @Override public boolean onTouch(View target, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN: x = event.getRawX(); y = event.getRawY(); dragged = false; if (protectNavigation) press(target, true); break;
                    case MotionEvent.ACTION_MOVE:
                        if (!dragged && Math.hypot(event.getRawX() - x, event.getRawY() - y) > slop) {
                            dragged = true; if (protectNavigation) press(target, false); MotionEvent cancel = MotionEvent.obtain(event);
                            cancel.setAction(MotionEvent.ACTION_CANCEL); target.onTouchEvent(cancel); cancel.recycle();
                        }
                        return dragged;
                    case MotionEvent.ACTION_POINTER_DOWN:
                        dragged = true;
                        MotionEvent cancel = MotionEvent.obtain(event);
                        cancel.setAction(MotionEvent.ACTION_CANCEL); target.onTouchEvent(cancel); cancel.recycle();
                        return true;
                    case MotionEvent.ACTION_CANCEL: dragged = true; if (protectNavigation) press(target, false); break;
                    case MotionEvent.ACTION_UP: if (protectNavigation) press(target, false); return dragged;
                }
                return false;
            }
        });
    }
    public static void installButtons(View root) {
        if (root.isClickable() && (root instanceof ImageView || root instanceof Button || root instanceof TextView)
                && !(root instanceof android.widget.EditText)) install(root);
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) installButtons(group.getChildAt(i));
        }
    }
}
