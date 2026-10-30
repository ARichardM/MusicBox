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
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;

import cl.arichard.descargador.R;

/** Minimal splash that animates the original MusicBox artwork as one piece. */
public final class AnimatedSplashView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint auraPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint waveformPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint waveformGlowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path waveform = new Path();
    private final Bitmap cube;
    private final RectF target = new RectF();
    private ValueAnimator animator;
    private ValueAnimator introAnimator;
    private float phase;
    private float intro;
    private static final long LAUNCH_HOLD_MS = 1050;
    private static final long LAUNCH_EXIT_MS = 300;
    private final boolean dismissAutomatically;
    private final Runnable dismissAnimation = this::dismiss;

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
        waveformPaint.setStyle(Paint.Style.STROKE);
        waveformPaint.setStrokeCap(Paint.Cap.ROUND);
        waveformPaint.setStrokeJoin(Paint.Join.ROUND);
        waveformPaint.setColor(Color.rgb(84, 238, 255));
        waveformGlowPaint.setStyle(Paint.Style.STROKE);
        waveformGlowPaint.setStrokeCap(Paint.Cap.ROUND);
        waveformGlowPaint.setStrokeJoin(Paint.Join.ROUND);
        waveformGlowPaint.setColor(Color.rgb(0, 158, 255));
        // Let the window renderer composite this view directly. A dedicated full-screen layer is expensive to
        // recreate while the splash is animating and caused visible stalls on older Samsung GPUs.
    }

    public void start() {
        stop();
        phase = 0f;
        intro = 0f;
        setAlpha(1f);
        setScaleX(1f);
        setScaleY(1f);
        setTranslationY(0f);
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(1200);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        // Constant speed prevents the subtle pause that a decelerating repeating animator creates at each loop.
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(value -> { phase = (float) value.getAnimatedValue(); invalidate(); });
        animator.start();
        introAnimator = ValueAnimator.ofFloat(0f, 1f);
        introAnimator.setDuration(650);
        introAnimator.setInterpolator(new DecelerateInterpolator(1.8f));
        introAnimator.addUpdateListener(value -> { intro = (float) value.getAnimatedValue(); invalidate(); });
        introAnimator.start();
        if (dismissAutomatically) {
            // Una vuelta completa de la onda y luego un acercamiento suave mientras aparece la app.
            postDelayed(dismissAnimation, LAUNCH_HOLD_MS);
        }
    }

    private void dismiss() {
        animate().alpha(0f).scaleX(1.12f).scaleY(1.12f).translationY(-dp(8)).setDuration(LAUNCH_EXIT_MS)
                .setInterpolator(new DecelerateInterpolator(1.4f)).withEndAction(() -> {
                    stop();
                    ViewGroup parent = (ViewGroup) getParent();
                    if (parent != null) parent.removeView(this);
                }).start();
    }

    public void stop() {
        removeCallbacks(dismissAnimation);
        animate().cancel();
        if (animator != null) animator.cancel();
        if (introAnimator != null) introAnimator.cancel();
        animator = null;
        introAnimator = null;
    }

    @Override protected void onDetachedFromWindow() {
        stop();
        super.onDetachedFromWindow();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (cube == null) return;
        float cycle = (float) Math.sin(phase * Math.PI * 2);
        float entranceScale = .82f + .18f * intro;
        float available = Math.max(0f, Math.min(getWidth(), getHeight()) - dp(48));
        float size = Math.min(dp(250), available * (dismissAutomatically ? .36f : .78f))
                * entranceScale;
        float maxSide = Math.max(cube.getWidth(), cube.getHeight());
        float drawWidth = size * cube.getWidth() / maxSide;
        float drawHeight = size * cube.getHeight() / maxSide;
        float left = (getWidth() - drawWidth) / 2f;
        float top = (getHeight() - drawHeight) / 2f - dp(9) * cycle;
        target.set(left, top, left + drawWidth, top + drawHeight);
        float cubeAlpha = dismissAutomatically ? .2f + .8f * intro : intro;
        float centerX = target.centerX();
        float centerY = target.centerY();
        float auraPulse = .82f + .18f * cycle;
        float auraRadius = Math.max(drawWidth, drawHeight) * (.68f + .06f * cycle);
        int auraAlpha = Math.round(105f * intro * auraPulse);
        auraPaint.setShader(new RadialGradient(centerX, centerY, auraRadius,
                new int[] { Color.argb(auraAlpha, 25, 205, 255),
                        Color.argb(Math.round(auraAlpha * .42f), 10, 132, 255), Color.TRANSPARENT },
                new float[] { 0f, .48f, 1f }, Shader.TileMode.CLAMP));
        canvas.drawCircle(centerX, centerY, auraRadius, auraPaint);
        auraPaint.setShader(null);
        paint.setAlpha(Math.round(255f * cubeAlpha));
        canvas.save();
        canvas.rotate(1.8f * cycle * intro, centerX, centerY);
        canvas.drawBitmap(cube, null, target, paint);
        // La onda es parte esencial de la marca. Se traza encima para que los optimizadores PNG
        // de algunos Samsung no puedan perder sus segmentos interiores al decodificar el recurso.
        waveform.reset();
        waveform.moveTo(target.left + drawWidth * .045f, target.top + drawHeight * .40f);
        waveform.cubicTo(target.left + drawWidth * .09f, target.top + drawHeight * .40f,
                target.left + drawWidth * .08f, target.top + drawHeight * .49f,
                target.left + drawWidth * .145f, target.top + drawHeight * .49f);
        waveform.cubicTo(target.left + drawWidth * .21f, target.top + drawHeight * .49f,
                target.left + drawWidth * .18f, target.top + drawHeight * .35f,
                target.left + drawWidth * .245f, target.top + drawHeight * .35f);
        waveform.cubicTo(target.left + drawWidth * .31f, target.top + drawHeight * .35f,
                target.left + drawWidth * .27f, target.top + drawHeight * .66f,
                target.left + drawWidth * .35f, target.top + drawHeight * .66f);
        waveform.cubicTo(target.left + drawWidth * .42f, target.top + drawHeight * .66f,
                target.left + drawWidth * .38f, target.top + drawHeight * .50f,
                target.left + drawWidth * .45f, target.top + drawHeight * .50f);
        waveform.cubicTo(target.left + drawWidth * .50f, target.top + drawHeight * .50f,
                target.left + drawWidth * .47f, target.top + drawHeight * .58f,
                target.left + drawWidth * .51f, target.top + drawHeight * .59f);
        waveformGlowPaint.setAlpha(Math.round(120f * cubeAlpha));
        waveformGlowPaint.setStrokeWidth(Math.max(dp(5), drawWidth * .035f));
        waveformPaint.setAlpha(Math.round(255f * cubeAlpha));
        waveformPaint.setStrokeWidth(Math.max(dp(2), drawWidth * .014f));
        canvas.drawPath(waveform, waveformGlowPaint);
        canvas.drawPath(waveform, waveformPaint);
        canvas.restore();
    }

    private float dp(float value) { return value * getResources().getDisplayMetrics().density; }
}
