package cl.arichard.descargador.util;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.graphics.Rect;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.SeekBar;
import androidx.activity.ComponentActivity;
import cl.arichard.descargador.LoginActivity;
import cl.arichard.descargador.MainActivity;
import java.util.function.BooleanSupplier;

/** Volver desde el borde izquierdo, usando la misma acción que Atrás del sistema. */
public final class EdgeBackLayout extends FrameLayout {
    private final float density;
    private final int slop;
    private float downX, downY;
    private boolean eligible, dragging;
    private Runnable back;
    private BooleanSupplier enabled = () -> true;

    public EdgeBackLayout(Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        slop = ViewConfiguration.get(context).getScaledTouchSlop();
    }

    public void setBackAction(Runnable action, BooleanSupplier available) { back = action; enabled = available; }

    public static void attach(Activity activity) {
        if (!(activity instanceof ComponentActivity) || activity instanceof LoginActivity || activity.isFinishing()) return;
        ViewGroup content = activity.findViewById(android.R.id.content);
        if (content == null || content.getChildCount() == 0 || content.getChildAt(0) instanceof EdgeBackLayout) return;
        EdgeBackLayout wrapper = new EdgeBackLayout(activity);
        wrapper.setBackAction(() -> ((ComponentActivity) activity).getOnBackPressedDispatcher().onBackPressed(),
                () -> !(activity instanceof MainActivity) || ((MainActivity) activity).canSwipeBack());
        while (content.getChildCount() > 0) {
            View child = content.getChildAt(0);
            ViewGroup.LayoutParams params = child.getLayoutParams();
            content.removeViewAt(0);
            wrapper.addView(child, params);
        }
        content.addView(wrapper, new ViewGroup.LayoutParams(-1, -1));
    }

    public static void install(Application app) {
        app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityStarted(Activity activity) { attach(activity); }
            @Override public void onActivityCreated(Activity activity, Bundle state) { }
            @Override public void onActivityResumed(Activity activity) { }
            @Override public void onActivityPaused(Activity activity) { }
            @Override public void onActivityStopped(Activity activity) { }
            @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) { }
            @Override public void onActivityDestroyed(Activity activity) { }
        });
    }

    private boolean protectedControl(View view, int x, int y) {
        Rect rect = new Rect();
        if (view.getVisibility() != VISIBLE || !view.getGlobalVisibleRect(rect) || !rect.contains(x, y)) return false;
        if (view instanceof SeekBar || view instanceof EditText || view instanceof SwipeActionsLayout) return true;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) if (protectedControl(group.getChildAt(i), x, y)) return true;
        }
        return false;
    }

    private void begin(MotionEvent event) {
        animate().cancel();
        setTranslationX(0);
        downX = event.getRawX(); downY = event.getRawY(); dragging = false;
        eligible = enabled.getAsBoolean() && event.getX() <= 32 * density
                && !protectedControl(this, (int) event.getRawX(), (int) event.getRawY());
    }

    private boolean claim(MotionEvent event) {
        if (!eligible || event.getPointerCount() != 1) { eligible = false; return false; }
        // Raw coordinates stay stable while the content follows the finger.
        float dx = event.getRawX() - downX;
        float dy = event.getRawY() - downY;
        if (!dragging && Math.max(Math.abs(dx), Math.abs(dy)) > slop * 2) {
            if (dx > 0 && dx > Math.abs(dy) * 1.5f) {
                dragging = true;
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
            } else eligible = false;
        }
        return dragging && eligible;
    }

    @Override public boolean onInterceptTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) begin(event);
        else if (event.getActionMasked() == MotionEvent.ACTION_MOVE) return claim(event);
        return false;
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: begin(event); return eligible;
            case MotionEvent.ACTION_MOVE:
                if (claim(event)) setTranslationX(Math.max(0, Math.min(getWidth(), event.getRawX() - downX)) * .5f);
                return eligible || dragging;
            case MotionEvent.ACTION_UP:
                float dx = event.getRawX() - downX;
                boolean commit = dragging && eligible && enabled.getAsBoolean() && dx > Math.max(72 * density, getWidth() * .22f);
                reset();
                if (commit && back != null) back.run();
                return true;
            case MotionEvent.ACTION_POINTER_DOWN:
            case MotionEvent.ACTION_CANCEL: reset(); return true;
        }
        return super.onTouchEvent(event);
    }

    private void reset() {
        dragging = false; eligible = false;
        setTranslationX(0);
        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
    }
}
