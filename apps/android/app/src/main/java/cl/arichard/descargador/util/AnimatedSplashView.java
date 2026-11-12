package cl.arichard.descargador.util;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;

import cl.arichard.descargador.R;

/**
 * Splash de MusicBox, igual al de escritorio: el cubo flota y la onda del pulso recorre su cara
 * con su brillo cian. Los puntos y la fórmula de la onda son los mismos de Main.qml.
 */
public final class AnimatedSplashView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint waveformPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint waveformGlowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path waveform = new Path();
    private final Bitmap cube;
    private final RectF target = new RectF();
    private ValueAnimator animator;
    private ValueAnimator introAnimator;
    private float phase;
    private float intro;
    /** Duración de una vuelta de la onda, la misma del escritorio. */
    static final long CYCLE_MS = 1650;
    /** Puntos de la onda en el espacio del logo (446 × 488), copiados del splash de escritorio. */
    static final float[][] WAVE = {{18, 238}, {32, 245}, {43, 238}, {56, 192}, {67, 190}, {83, 282}, {94, 295},
            {120, 122}, {130, 128}, {148, 361}, {158, 364}, {177, 244}, {187, 242}, {201, 302}, {220, 312}};
    static final float LOGO_W = 446f, LOGO_H = 488f;
    private final float[][] points = new float[WAVE.length][2];
    /** El brillo difuminado de la onda solo se dibuja con aceleración por hardware desde Android 9. */
    private static final boolean SOFT_GLOW = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P;
    private float glowRadius = -1f;
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
        boolean orange = "orange".equals(AccentColors.selected(context));
        if (orange) paint.setColorFilter(new ColorMatrixColorFilter(new float[]{
                0, 0, 1, 0, 0,
                .15f, .68f, 0, 0, 0,
                1, 0, 0, 0, 0,
                0, 0, 0, 1, 0}));
        waveformPaint.setStyle(Paint.Style.STROKE);
        waveformPaint.setStrokeCap(Paint.Cap.ROUND);
        waveformPaint.setStrokeJoin(Paint.Join.ROUND);
        waveformPaint.setColor(orange ? Color.rgb(0xFF, 0xBD, 0x7A) : Color.rgb(0x50, 0xE9, 0xFF));
        waveformGlowPaint.setStyle(Paint.Style.STROKE);
        waveformGlowPaint.setStrokeCap(Paint.Cap.ROUND);
        waveformGlowPaint.setStrokeJoin(Paint.Join.ROUND);
        waveformGlowPaint.setColor(orange ? Color.rgb(0xFF, 0x9F, 0x43) : Color.rgb(0x20, 0xBF, 0xFF));
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
        animator.setDuration(CYCLE_MS);
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

    /**
     * Desplazamiento vertical de cada punto de la onda en unidades del logo: una onda que viaja de
     * izquierda a derecha y queda fija en los extremos, como en el escritorio.
     */
    static float waveOffset(int index, float phase) {
        float t = index / (float) (WAVE.length - 1);
        double travel = Math.sin(phase * Math.PI * 2 - WAVE[index][0] / LOGO_W * Math.PI * 4);
        double anchor = Math.sin(t * Math.PI);
        return (float) (15 * travel * anchor);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (cube == null) return;
        float available = Math.max(0f, Math.min(getWidth(), getHeight()) - dp(48));
        float size = Math.min(dp(250), available * (dismissAutomatically ? .42f : .78f));
        float scale = Math.min(size / LOGO_W, size / LOGO_H);
        float drawWidth = LOGO_W * scale;
        float drawHeight = LOGO_H * scale;
        // El cubo flota hacia arriba igual que en el escritorio (5 de 250 px).
        float bob = size * .02f * (float) Math.abs(Math.sin(phase * Math.PI * 2));
        float left = (getWidth() - drawWidth) / 2f;
        float top = (getHeight() - drawHeight) / 2f - bob;
        target.set(left, top, left + drawWidth, top + drawHeight);
        float alpha = Math.max(0f, Math.min(1f, intro));
        paint.setAlpha(Math.round(255f * alpha));
        canvas.drawBitmap(cube, null, target, paint);

        for (int i = 0; i < WAVE.length; i++) {
            points[i][0] = left + WAVE[i][0] * scale;
            points[i][1] = top + (WAVE[i][1] + waveOffset(i, phase)) * scale;
        }
        waveform.reset();
        waveform.moveTo(points[0][0], points[0][1]);
        for (int j = 1; j < points.length - 1; j++) {
            float mx = (points[j][0] + points[j + 1][0]) / 2f;
            float my = (points[j][1] + points[j + 1][1]) / 2f;
            waveform.quadTo(points[j][0], points[j][1], mx, my);
        }
        waveform.lineTo(points[points.length - 1][0], points[points.length - 1][1]);
        if (SOFT_GLOW) {
            // Igual que shadowBlur 12 del escritorio: el mismo trazo, difuminado y en azul.
            float radius = Math.max(1f, 12f * scale);
            if (radius != glowRadius) {
                glowRadius = radius;
                waveformGlowPaint.setMaskFilter(new BlurMaskFilter(radius, BlurMaskFilter.Blur.NORMAL));
            }
            waveformGlowPaint.setAlpha(Math.round(255f * alpha));
            waveformGlowPaint.setStrokeWidth(9f * scale * 1.4f);
        } else {
            waveformGlowPaint.setAlpha(Math.round(60f * alpha));
            waveformGlowPaint.setStrokeWidth(9f * scale * 2.2f);
        }
        waveformPaint.setAlpha(Math.round(255f * alpha));
        waveformPaint.setStrokeWidth(9f * scale);
        canvas.drawPath(waveform, waveformGlowPaint);
        canvas.drawPath(waveform, waveformPaint);
    }

    private float dp(float value) { return value * getResources().getDisplayMetrics().density; }
}
