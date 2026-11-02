package cl.arichard.descargador.util;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

/**
 * Deja el contenido fuera de la barra de estado, la de navegación, el recorte de la cámara y el teclado.
 *
 * Desde Android 15 el sistema dibuja todas las apps de borde a borde: sin esto, los botones de arriba
 * (corazón, menú, volver) quedan bajo la barra de estado y los de abajo bajo la barra de gestos, y los
 * toques no les llegan. Se aplica igual en todas las versiones para que se vean y respondan lo mismo.
 */
public final class SystemBars {
    private SystemBars() {}

    public static void fit(Activity activity) {
        fit(activity, null);
    }

    /**
     * Igual que {@link #fit(Activity)}, pero la pantalla reparte los márgenes ella misma: recibe las barras
     * (arriba, lados, abajo) para que, por ejemplo, una hoja que asoma desde abajo llegue al borde y se vea entera.
     */
    public static void fit(Activity activity, java.util.function.Consumer<Insets> onInsets) {
        View content = activity.findViewById(android.R.id.content);
        if (!(content instanceof ViewGroup) || ((ViewGroup) content).getChildCount() == 0) return;
        View root = ((ViewGroup) content).getChildAt(0);
        // Las pantallas que ya se ajustan solas (android:fitsSystemWindows) no se tocan.
        if (root.getFitsSystemWindows()) return;
        WindowCompat.setDecorFitsSystemWindows(activity.getWindow(), false);
        final int left = root.getPaddingLeft(), top = root.getPaddingTop();
        final int right = root.getPaddingRight(), bottom = root.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            Insets keyboard = insets.getInsets(WindowInsetsCompat.Type.ime());
            if (onInsets != null) { onInsets.accept(bars); return WindowInsetsCompat.CONSUMED; }
            view.setPadding(left + bars.left, top + bars.top, right + bars.right,
                    bottom + Math.max(bars.bottom, keyboard.bottom));
            return WindowInsetsCompat.CONSUMED;
        });
        ViewCompat.requestApplyInsets(root);
    }
}
