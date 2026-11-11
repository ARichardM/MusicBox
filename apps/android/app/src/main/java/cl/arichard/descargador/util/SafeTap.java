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
        install(view, true, 0.82f);
    }
    public static void installRowTap(View view) { install(view, false, 1f); }
    public static void installRowBounce(View view) { install(view, false, 0.96f); }

    /** Elegant "pop" for toggles such as the heart: a quick squash, then a springy overshoot. */
    public static void pop(View view, boolean activated) {
        view.animate().cancel();
        view.setScaleX(0.7f); view.setScaleY(0.7f);
        view.animate().scaleX(1f).scaleY(1f).setDuration(activated ? 380 : 220)
                .setInterpolator(new OvershootInterpolator(activated ? 4f : 1.5f)).start();
    }

    private static void press(View view, boolean down, float scale, boolean bounce) {
        view.animate().cancel();
        if (down) view.animate().scaleX(scale).scaleY(scale).setStartDelay(0).setDuration(85)
                .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
        else view.animate().scaleX(1f).scaleY(1f).setStartDelay(0).setDuration(scale >= 0.95f ? 190 : 260)
                .setInterpolator(bounce ? new OvershootInterpolator(scale >= 0.95f ? 1.6f : 3f)
                        : new android.view.animation.DecelerateInterpolator()).start();
    }
    private static void install(View view, boolean protectNavigation, float pressScale) {
        if (protectNavigation) view.setTag(R.id.protectedAction, true);
        int slop = ViewConfiguration.get(view.getContext()).getScaledTouchSlop();
        view.setOnTouchListener(new View.OnTouchListener() {
            float x, y; boolean dragged;
            @Override public boolean onTouch(View target, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        x = event.getRawX(); y = event.getRawY(); dragged = false;
                        if (pressScale < 1f) press(target, true, pressScale, false);
                        break;
                    case MotionEvent.ACTION_MOVE:
                        if (!dragged && Math.hypot(event.getRawX() - x, event.getRawY() - y) > slop) {
                            dragged = true;
                            if (pressScale < 1f) press(target, false, pressScale, protectNavigation);
                            MotionEvent cancel = MotionEvent.obtain(event);
                            cancel.setAction(MotionEvent.ACTION_CANCEL); target.onTouchEvent(cancel); cancel.recycle();
                        }
                        return dragged;
                    case MotionEvent.ACTION_POINTER_DOWN:
                        if (!dragged && pressScale < 1f) press(target, false, pressScale, false);
                        dragged = true;
                        MotionEvent cancel = MotionEvent.obtain(event);
                        cancel.setAction(MotionEvent.ACTION_CANCEL); target.onTouchEvent(cancel); cancel.recycle();
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        if (!dragged && pressScale < 1f) press(target, false, pressScale, protectNavigation);
                        dragged = true;
                        break;
                    case MotionEvent.ACTION_UP:
                        if (!dragged && pressScale < 1f) press(target, false, pressScale, true);
                        return dragged;
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
