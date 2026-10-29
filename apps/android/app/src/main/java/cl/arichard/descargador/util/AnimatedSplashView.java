package cl.arichard.descargador.util;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;

import cl.arichard.descargador.R;

/** Minimal splash that animates the original MusicBox artwork as one piece. */
public final class AnimatedSplashView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Bitmap cube;
    private final RectF target = new RectF();
    private ValueAnimator animator;
    private ValueAnimator introAnimator;
    private float phase;
    private float intro;
    private static final long LAUNCH_HOLD_MS = 760;
    private static final long LAUNCH_EXIT_MS = 280;
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
        float size = Math.min(dp(250), Math.min(getWidth(), getHeight()) * (dismissAutomatically ? .335f : .88f))
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
        paint.setAlpha(Math.round(255f * cubeAlpha));
        canvas.save();
        canvas.rotate(1.8f * cycle * intro, centerX, centerY);
        canvas.drawBitmap(cube, null, target, paint);
        canvas.restore();
    }

    private float dp(float value) { return value * getResources().getDisplayMetrics().density; }
}
