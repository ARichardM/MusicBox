package cl.arichard.descargador.util;

import android.view.View;
import android.view.animation.OvershootInterpolator;

/** Transición de la marca de descargada: aparece en el lugar del anillo mientras este se desvanece. */
public final class DownloadMark {
    /** Espera un poco a que el anillo empiece a irse, para que se crucen en vez de amontonarse. */
    static final long DELAY_MS = 30;
    static final long DURATION_MS = 280;

    private DownloadMark() {}

    public static void reveal(View mark) {
        mark.animate().cancel();
        mark.setRotation(0f);
        mark.setAlpha(0f);
        mark.setScaleX(.7f);
        mark.setScaleY(.7f);
        mark.animate().alpha(1f).scaleX(1f).scaleY(1f)
                .setStartDelay(DELAY_MS).setDuration(DURATION_MS)
                .setInterpolator(new OvershootInterpolator(1.2f)).start();
    }
}
