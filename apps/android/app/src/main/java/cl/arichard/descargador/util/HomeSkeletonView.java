package cl.arichard.descargador.util;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.View;
import androidx.core.content.ContextCompat;
import cl.arichard.descargador.R;

/**
 * Esqueleto del Inicio: las mismas secciones que verá el usuario (Para ti, Artistas, Colecciones, Recientes y Más
 * escuchadas) dibujadas como formas grises con un único brillo que las recorre en una ola continua. Es una sola
 * vista, así que cuesta muy poco aunque haya decenas de formas, y no tiene texto ni interacción.
 */
public final class HomeSkeletonView extends View {
    private static final long PERIOD_MS = 1500L;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix matrix = new Matrix();
    private final RectF rect = new RectF();
    private final int base, highlight;
    private LinearGradient shader;
    private final float d;

    public HomeSkeletonView(Context context) {
        super(context);
        base = ContextCompat.getColor(context, R.color.skeleton_base);
        highlight = ContextCompat.getColor(context, R.color.skeleton_highlight);
        d = context.getResources().getDisplayMetrics().density;
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        setClickable(false);
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        float width = Math.max(1, w);
        shader = new LinearGradient(-width, 0, width, 0, new int[]{base, base, highlight, base, base},
                new float[]{0f, .3f, .5f, .7f, 1f}, Shader.TileMode.CLAMP);
        paint.setShader(shader);
    }

    private void box(Canvas canvas, float x, float y, float w, float h, float radius) {
        rect.set(x * d, y * d, (x + w) * d, (y + h) * d);
        if (rect.top > getHeight()) return;
        canvas.drawRoundRect(rect, radius * d, radius * d, paint);
    }

    private void circle(Canvas canvas, float x, float y, float size) {
        if (y * d > getHeight()) return;
        canvas.drawCircle((x + size / 2f) * d, (y + size / 2f) * d, size / 2f * d, paint);
    }

    @Override protected void onDraw(Canvas canvas) {
        if (shader == null) return;
        float width = getWidth();
        // El reloj es global: todas las formas comparten fase y el brillo cruza la pantalla de lado a lado.
        float phase = SystemClock.uptimeMillis() % PERIOD_MS / (float) PERIOD_MS;
        matrix.setTranslate(-width + phase * width * 3f, 0);
        shader.setLocalMatrix(matrix);

        final float left = 16f;
        float y = 14f;

        // Para ti: título, subtítulo y tarjetas con portada cuadrada.
        box(canvas, left, y, 110, 18, 6);
        box(canvas, left, y + 26, 170, 11, 5);
        y += 50;
        for (int i = 0; i < 3; i++) {
            float x = left + i * 168f;
            box(canvas, x, y, 156, 156, 16);
            box(canvas, x + 2, y + 166, 110, 10, 5);
            box(canvas, x + 2, y + 184, 70, 9, 4);
        }
        y += 218;

        // Artistas para ti: círculos con el nombre debajo.
        box(canvas, left, y, 130, 18, 6);
        y += 34;
        for (int i = 0; i < 4; i++) {
            float x = left + 10 + i * 120f;
            circle(canvas, x, y, 92);
            box(canvas, x + 14, y + 102, 64, 10, 5);
        }
        y += 128;

        // Colecciones más escuchadas: tarjeta ancha con portada y dos líneas.
        box(canvas, left, y, 170, 18, 6);
        y += 32;
        for (int i = 0; i < 2; i++) {
            float x = left + i * 280f;
            box(canvas, x, y, 270, 84, 16);
        }
        y += 112;

        // Recientes: portadas con título y artista.
        box(canvas, left, y, 130, 18, 6);
        y += 32;
        for (int i = 0; i < 3; i++) {
            float x = left + i * 134f;
            box(canvas, x, y, 122, 122, 14);
            box(canvas, x + 2, y + 132, 90, 10, 5);
            box(canvas, x + 2, y + 150, 56, 9, 4);
        }
        y += 186;

        // Más escuchadas de la biblioteca: filas de canción.
        box(canvas, left, y, 190, 18, 6);
        y += 34;
        float rowWidth = width / d - left * 2;
        for (int i = 0; i < 4; i++) {
            box(canvas, left, y, 54, 54, 12);
            box(canvas, left + 68, y + 8, rowWidth - 120, 14, 6);
            box(canvas, left + 68, y + 32, rowWidth - 190, 11, 5);
            y += 70;
        }
        if (getVisibility() == VISIBLE && isAttachedToWindow()) postInvalidateOnAnimation();
    }
}
