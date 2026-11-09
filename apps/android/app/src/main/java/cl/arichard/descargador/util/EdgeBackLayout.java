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
import android.widget.FrameLayout;
import android.widget.SeekBar;
import androidx.activity.ComponentActivity;
import cl.arichard.descargador.LoginActivity;
import cl.arichard.descargador.MainActivity;
import cl.arichard.descargador.NowPlayingActivity;
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

    /**
     * Si la pantalla tiene su propio deslizar hacia la derecha (p. ej. ir a la playlist anterior), la franja ancha del
     * borde se le cede mientras pueda hacerlo; solo la franja angosta, junto al borde, sigue siendo "volver".
     */
    public void setContentSwipePriority(BooleanSupplier contentCanSwipeRight) { contentSwipe = contentCanSwipeRight; }
    private BooleanSupplier contentSwipe = () -> false;

    public static void attach(Activity activity) {
        if (!(activity instanceof ComponentActivity) || activity instanceof LoginActivity
                || activity instanceof NowPlayingActivity || activity.isFinishing()) return;
        ViewGroup content = activity.findViewById(android.R.id.content);
        if (content == null || content.getChildCount() == 0 || content.getChildAt(0) instanceof EdgeBackLayout) return;
        EdgeBackLayout wrapper = new EdgeBackLayout(activity);
        wrapper.setBackAction(() -> ((ComponentActivity) activity).getOnBackPressedDispatcher().onBackPressed(),
                () -> !(activity instanceof MainActivity) || ((MainActivity) activity).canSwipeBack());
        if (activity instanceof MainActivity)
            wrapper.setContentSwipePriority(((MainActivity) activity)::hasPreviousCollection);
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
        // Un EditText puede ocupar casi todo el ancho (Importador/Descargas). El gesto
        // horizontal ya se distingue del toque y del scroll, por lo que no debe bloquearlo.
        if (view instanceof SeekBar || view instanceof SwipeActionsLayout) return true;
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
        // Samsung reserva el borde estricto para su navegación. Una franja algo mayor
        // permite iniciar el gesto justo por dentro de esa zona y sigue evitando el
        // contenido horizontal gracias a la detección de dirección de claim().
        float responsiveEdge = Math.min(180 * density, getWidth() * .42f);
        // Franja angosta junto al borde (incluye la que Samsung deja "justo dentro"): aquí siempre es "volver".
        float narrowEdge = Math.min(64 * density, getWidth() * .2f);
        boolean yieldToContent = event.getX() > narrowEdge && contentSwipe.getAsBoolean();
        eligible = enabled.getAsBoolean() && event.getX() <= responsiveEdge && !yieldToContent
                && !protectedControl(this, (int) event.getRawX(), (int) event.getRawY());
    }

    private boolean claim(MotionEvent event) {
        if (!eligible || event.getPointerCount() != 1) { eligible = false; return false; }
        // Raw coordinates stay stable while the content follows the finger.
        float dx = event.getRawX() - downX;
        float dy = event.getRawY() - downY;
        if (!dragging && Math.max(Math.abs(dx), Math.abs(dy)) > slop * 1.2f) {
            if (dx > 0 && dx > Math.abs(dy) * 1.15f) {
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
                if (claim(event)) setTranslationX(Math.max(0, Math.min(getWidth(), event.getRawX() - downX)));
                return eligible || dragging;
            case MotionEvent.ACTION_UP:
                float dx = event.getRawX() - downX;
                boolean commit = dragging && eligible && enabled.getAsBoolean()
                        && dx > Math.max(56 * density, getWidth() * .08f);
                if (commit && back != null) { complete(); return true; }
                reset();
                return true;
            case MotionEvent.ACTION_POINTER_DOWN:
            case MotionEvent.ACTION_CANCEL: reset(); return true;
        }
        return super.onTouchEvent(event);
    }

    /**
     * Gesto completado. Si la pantalla se cierra, sigue su camino hacia la derecha desde donde la dejó el dedo; antes se
     * devolvía a su sitio y el sistema la cerraba con la animación normal del tema (hacia abajo), en contra del gesto.
     * Si sigue abierta (p. ej. volver a una lista dentro de Principal), vuelve a su lugar.
     */
    private void complete() {
        dragging = false; eligible = false;
        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
        back.run();
        Activity activity = activityOf(getContext());
        if (activity != null && activity.isFinishing()) {
            activity.overridePendingTransition(cl.arichard.descargador.R.anim.fade_in, cl.arichard.descargador.R.anim.slide_out_right);
        } else {
            setTranslationX(0);
        }
    }

    private static Activity activityOf(Context context) {
        while (context instanceof android.content.ContextWrapper) {
            if (context instanceof Activity) return (Activity) context;
            context = ((android.content.ContextWrapper) context).getBaseContext();
        }
        return null;
    }

    private void reset() {
        dragging = false; eligible = false;
        setTranslationX(0);
        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
    }
}
