package cl.arichard.descargador.util;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Shader;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;
import androidx.core.content.ContextCompat;
import cl.arichard.descargador.R;

/** Skeleton rows for list-loading states; deliberately contains no text or interaction. */
public final class SkeletonLoadingView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix shimmerMatrix = new Matrix();
    private LinearGradient shimmer;
    private final int baseColor;
    private final int highlightColor;
    private final long startedAt = SystemClock.uptimeMillis();

    public SkeletonLoadingView(Context context, AttributeSet attrs) {
        super(context, attrs);
        baseColor = ContextCompat.getColor(context, R.color.skeleton_base);
        highlightColor = ContextCompat.getColor(context, R.color.skeleton_highlight);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        shimmer = new LinearGradient(-Math.max(1, w), 0, Math.max(1, w), 0,
                new int[]{baseColor, baseColor, highlightColor, baseColor, baseColor},
                new float[]{0f, .28f, .5f, .72f, 1f}, Shader.TileMode.CLAMP);
        paint.setShader(shimmer);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float density = getResources().getDisplayMetrics().density;
        float width = getWidth();
        float phase = (SystemClock.uptimeMillis() - startedAt) % 1250L / 1250f;
        float center = -width + phase * width * 3f;
        if (shimmer == null) return;
        shimmerMatrix.setTranslate(center, 0);
        shimmer.setLocalMatrix(shimmerMatrix);
        float top = 12 * density;
        for (int i = 0; i < 7 && top < getHeight(); i++, top += 82 * density) {
            canvas.drawRoundRect(20 * density, top, 74 * density, top + 54 * density, 12 * density, 12 * density, paint);
            canvas.drawRoundRect(88 * density, top + 7 * density, width - 42 * density, top + 22 * density, 8 * density, 8 * density, paint);
            canvas.drawRoundRect(88 * density, top + 32 * density, width - 110 * density, top + 44 * density, 6 * density, 6 * density, paint);
        }
        if (getVisibility() == VISIBLE) postInvalidateOnAnimation();
    }
}
