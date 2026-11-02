package cl.arichard.descargador.util;

import android.app.Activity;
import android.app.Application;
import android.content.pm.ActivityInfo;
import android.os.Bundle;

/**
 * Las tablets giran con el sensor (según el ajuste de rotación del sistema); los teléfonos quedan en vertical,
 * porque el reproductor y las listas no tienen diseño apaisado para pantallas pequeñas.
 */
public final class ScreenRotation implements Application.ActivityLifecycleCallbacks {
    /** Ancho mínimo de pantalla a partir del cual Android considera que es una tablet. */
    static final int TABLET_MIN_DP = 600;

    public static void install(Application app) { app.registerActivityLifecycleCallbacks(new ScreenRotation()); }

    static int orientationFor(int smallestWidthDp) {
        return smallestWidthDp >= TABLET_MIN_DP
                ? ActivityInfo.SCREEN_ORIENTATION_FULL_USER : ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
    }

    @Override public void onActivityCreated(Activity activity, Bundle state) {
        int wanted = orientationFor(activity.getResources().getConfiguration().smallestScreenWidthDp);
        if (activity.getRequestedOrientation() != wanted) activity.setRequestedOrientation(wanted);
    }

    @Override public void onActivityStarted(Activity activity) {}
    @Override public void onActivityResumed(Activity activity) {}
    @Override public void onActivityPaused(Activity activity) {}
    @Override public void onActivityStopped(Activity activity) {}
    @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) {}
    @Override public void onActivityDestroyed(Activity activity) {}
}
