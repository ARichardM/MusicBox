package cl.arichard.descargador.util;

import android.content.Context;
import android.graphics.Color;
import androidx.core.content.ContextCompat;
import cl.arichard.descargador.R;
import com.google.android.material.color.MaterialColors;

/** Shared accent preference and theme-aware replacements for legacy color resources. */
public final class AccentColors {
    public static final String KEY = "accentColor";
    private AccentColors() {}

    public static boolean isValid(String value) {
        return "blue".equals(value) || "orange".equals(value);
    }

    public static String selected(Context context) {
        String value = context.getSharedPreferences("musicbox_prefs", Context.MODE_PRIVATE).getString(KEY, "blue");
        return isValid(value) ? value : "blue";
    }

    public static void apply(Context context) {
        if ("orange".equals(selected(context)))
            context.getTheme().applyStyle(R.style.ThemeOverlay_MusicBox_Orange, true);
    }

    /** Warm decorative blues as well, preserving their brightness and transparency. */
    public static int adaptive(Context context, int color) {
        if (!"orange".equals(selected(context))) return color;
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        hsv[0] = 27f;
        return Color.HSVToColor(Color.alpha(color), hsv);
    }

    public static int onAccent(Context context) {
        return MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnPrimary, Color.WHITE);
    }

    public static int getColor(Context context, int resource) {
        if (resource == R.color.accent || resource == R.color.accent_primary || resource == R.color.now_playing)
            return MaterialColors.getColor(context, androidx.appcompat.R.attr.colorPrimary,
                    ContextCompat.getColor(context, resource));
        if (resource == R.color.dialog_button_ripple)
            return MaterialColors.getColor(context, R.attr.musicboxAccentRipple,
                    ContextCompat.getColor(context, resource));
        return ContextCompat.getColor(context, resource);
    }
}
