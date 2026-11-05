package cl.arichard.descargador.util;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import cl.arichard.descargador.R;

/** Lightweight animated placeholder used while artwork is being decoded or downloaded. */
public final class ShimmerDrawable extends Drawable implements Runnable {
    private static final long PERIOD_MS = 1250L;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final android.graphics.Matrix matrix = new android.graphics.Matrix();
    private final int baseColor;
    private final int highlightColor;
    private LinearGradient shader;
    private int shaderWidth = -1;

    public ShimmerDrawable(Context context) {
        baseColor = ContextCompat.getColor(context, R.color.skeleton_base);
        highlightColor = ContextCompat.getColor(context, R.color.skeleton_highlight);
    }

    @Override public void draw(@NonNull Canvas canvas) {
        int width = Math.max(1, getBounds().width());
        // El degradado se crea una sola vez por ancho y solo se desplaza en cada cuadro (antes se reconstruía en cada uno).
        if (shader == null || shaderWidth != width) {
            shaderWidth = width;
            shader = new LinearGradient(-width, 0, width, 0, new int[]{baseColor, baseColor, highlightColor, baseColor, baseColor},
                    new float[]{0f, .28f, .5f, .72f, 1f}, Shader.TileMode.CLAMP);
            paint.setShader(shader);
        }
        // Reloj global: todos los marcadores de posición brillan a la vez.
        float phase = SystemClock.uptimeMillis() % PERIOD_MS / (float) PERIOD_MS;
        matrix.setTranslate(getBounds().left + -width + phase * width * 3f, 0);
        shader.setLocalMatrix(matrix);
        canvas.drawRect(getBounds(), paint);
        scheduleSelf(this, SystemClock.uptimeMillis() + 16L);
    }

    @Override public void run() { invalidateSelf(); }
    @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
    @Override public void setColorFilter(android.graphics.ColorFilter filter) { paint.setColorFilter(filter); }
    @Override public int getOpacity() { return PixelFormat.OPAQUE; }
}
