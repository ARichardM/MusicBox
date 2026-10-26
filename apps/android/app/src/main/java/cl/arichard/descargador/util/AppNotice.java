package cl.arichard.descargador.util;

import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import com.google.android.material.snackbar.Snackbar;
import cl.arichard.descargador.R;

/** In-app notice styled to belong to MusicBox instead of using system Toasts. */
public final class AppNotice {
    private AppNotice() {}

    /** Aviso informativo con el mismo estilo; se ve solo dentro de la app. */
    public static void info(View anchor, String message) {
        error(anchor, message, null, null);
    }

    public static void error(View anchor, String message) {
        error(anchor, message, null, null);
    }

    public static void error(View anchor, String message, String action, Runnable callback) {
        if (anchor == null) return;
        Snackbar bar = Snackbar.make(anchor, message, Snackbar.LENGTH_LONG);
        bar.setAnchorView(anchor);
        View view = bar.getView();
        float density = anchor.getResources().getDisplayMetrics().density;
        GradientDrawable surface = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{Color.rgb(42, 46, 54), Color.rgb(24, 28, 35)});
        surface.setCornerRadius(18f * density);
        surface.setStroke(Math.max(1, Math.round(density)), Color.argb(105, 70, 221, 251));
        view.setBackground(surface);
        view.setElevation(12f * density);

        TextView text = view.findViewById(com.google.android.material.R.id.snackbar_text);
        text.setTextColor(Color.WHITE);
        text.setTextSize(14f);
        text.setMaxLines(4);
        text.setLineSpacing(0f, 1.08f);

        if (action != null && callback != null) {
            bar.setAction(action, ignored -> callback.run());
            bar.setActionTextColor(Color.rgb(70, 221, 251));
            TextView actionView = view.findViewById(com.google.android.material.R.id.snackbar_action);
            actionView.setTextSize(12f);
        }

        ViewGroup.LayoutParams raw = view.getLayoutParams();
        if (raw instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) raw;
            margins.setMarginStart(Math.round(16f * density));
            margins.setMarginEnd(Math.round(16f * density));
            margins.bottomMargin += Math.round(12f * density);
            view.setLayoutParams(margins);
        }
        bar.show();
    }
}
