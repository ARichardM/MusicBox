package cl.arichard.descargador.util;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;

import cl.arichard.descargador.R;

/** Minimal splash that animates the original MusicBox artwork as one piece. */
public final class AnimatedSplashView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Bitmap cube;
    private final Path wavePath = new Path();
    private ValueAnimator animator;
    private ValueAnimator introAnimator;
    private float phase;
    private float intro;
    private static final long LAUNCH_HOLD_MS = 1650;
    private static final long LAUNCH_EXIT_MS = 460;
    private final boolean dismissAutomatically;

    public AnimatedSplashView(Context context) {
        this(context, true);
    }

    /** The embedded variant uses the exact splash animation without dismissing itself. */
    public AnimatedSplashView(Context context, boolean dismissAutomatically) {
        super(context);
        this.dismissAutomatically = dismissAutomatically;
        // El mismo negro del splash del sistema: el cambio entre uno y otro no se nota.
        setBackgroundColor(dismissAutomatically ? Color.BLACK : Color.TRANSPARENT);
        cube = BitmapFactory.decodeResource(getResources(), R.drawable.logo_cube);
        paint.setFilterBitmap(true);
        paint.setDither(true);
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
    }

    public void start() {
        stop();
        setAlpha(1f);
        setScaleX(1f);
        setScaleY(1f);
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(1650);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setInterpolator(new DecelerateInterpolator());
        animator.addUpdateListener(value -> { phase = (float) value.getAnimatedValue(); invalidate(); });
        animator.start();
        introAnimator = ValueAnimator.ofFloat(0f, 1f);
        introAnimator.setDuration(650);
        introAnimator.setInterpolator(new DecelerateInterpolator(1.8f));
        introAnimator.addUpdateListener(value -> { intro = (float) value.getAnimatedValue(); invalidate(); });
        introAnimator.start();
        if (dismissAutomatically) {
            // Una vuelta completa de la onda y luego un acercamiento suave mientras aparece la app.
            postDelayed(() -> animate().alpha(0f).scaleX(1.14f).scaleY(1.14f).setDuration(LAUNCH_EXIT_MS)
                    .setInterpolator(new DecelerateInterpolator(1.4f)).withEndAction(() -> {
                stop();
                ViewGroup parent = (ViewGroup) getParent();
                if (parent != null) parent.removeView(this);
            }).start(), LAUNCH_HOLD_MS);
        }
    }

    public void stop() {
        animate().cancel();
        if (animator != null) animator.cancel();
        if (introAnimator != null) introAnimator.cancel();
        animator = null;
        introAnimator = null;
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (cube == null) return;
        float cycle = (float) Math.sin(phase * Math.PI * 2);
        float size = Math.min(dp(250), Math.min(getWidth(), getHeight()) * (dismissAutomatically ? .335f : .88f));
        float maxSide = Math.max(cube.getWidth(), cube.getHeight());
        float drawWidth = size * cube.getWidth() / maxSide;
        float drawHeight = size * cube.getHeight() / maxSide;
        float left = (getWidth() - drawWidth) / 2f;
        float top = (getHeight() - drawHeight) / 2f - dp(5) * Math.abs(cycle);
        android.graphics.RectF target = new android.graphics.RectF(left, top, left + drawWidth, top + drawHeight);
        float cubeAlpha = dismissAutomatically ? 1f : intro;
        float auraPulse = 1f + .08f * cycle;
        float auraRadius = Math.max(drawWidth, drawHeight) * .55f * auraPulse;
        float centerX = target.centerX();
        float centerY = target.centerY();
        paint.setShader(new RadialGradient(centerX, centerY, auraRadius,
                new int[]{Color.argb(Math.round(82f * intro), 44, 220, 255),
                        Color.argb(Math.round(25f * intro), 25, 155, 230), Color.TRANSPARENT},
                new float[]{0f, .48f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawCircle(centerX, centerY, auraRadius, paint);
        paint.setShader(null);
        paint.setAlpha(Math.round(255f * cubeAlpha));
        canvas.drawBitmap(cube, null, target, paint);
        drawWave(canvas, left, top, drawWidth, drawHeight, cubeAlpha);
    }

    private void drawWave(Canvas canvas, float left, float top, float width, float height, float alpha) {
        final float[][] raw = {
                {18,238},{32,245},{43,238},{56,192},{67,190},{83,282},{94,295},
                {120,122},{130,128},{148,361},{158,364},{177,244},{187,242},{201,302},{220,312}
        };
        float[] xs = new float[raw.length];
        float[] ys = new float[raw.length];
        for (int i = 0; i < raw.length; i++) {
            float t = i / (float) (raw.length - 1);
            float travel = (float) Math.sin(phase * Math.PI * 2 - raw[i][0] / 446f * Math.PI * 4);
            float anchor = (float) Math.sin(t * Math.PI);
            xs[i] = left + raw[i][0] / 446f * width;
            ys[i] = top + (raw[i][1] + 15f * travel * anchor) / 488f * height;
        }
        wavePath.reset();
        wavePath.moveTo(xs[0], ys[0]);
        for (int i = 1; i < raw.length - 1; i++) {
            wavePath.quadTo(xs[i], ys[i], (xs[i] + xs[i + 1]) / 2f, (ys[i] + ys[i + 1]) / 2f);
        }
        wavePath.lineTo(xs[xs.length - 1], ys[ys.length - 1]);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStrokeWidth(9f / 446f * width);
        paint.setColor(Color.rgb(80, 233, 255));
        paint.setAlpha(Math.round(255f * alpha));
        paint.setShadowLayer(12f / 446f * width, 0, 0, Color.rgb(32, 191, 255));
        canvas.drawPath(wavePath, paint);
        paint.clearShadowLayer();
        paint.setStyle(Paint.Style.FILL);
    }

    private float dp(float value) { return value * getResources().getDisplayMetrics().density; }
}
