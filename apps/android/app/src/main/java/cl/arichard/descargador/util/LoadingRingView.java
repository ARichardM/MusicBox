package cl.arichard.descargador.util;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.SweepGradient;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.view.animation.OvershootInterpolator;

/**
 * Compact ring in MusicBox colors. Without a progress value it spins while its arc breathes; with
 * {@link #setProgress(float)} it fills like a gauge. {@link #setActive(boolean)} fades it in and out.
 */
public final class LoadingRingView extends View {
    private static final int CYAN = Color.rgb(70, 221, 251);
    private static final int BLUE = Color.rgb(10, 132, 255);

    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bounds = new RectF();
    private final ValueAnimator spin = ValueAnimator.ofFloat(0f, 360f);
    private final ValueAnimator breathe = ValueAnimator.ofFloat(0f, 1f);
    private ValueAnimator fill;
    private float rotation;
    private float sweep = 60f;
    private float shown;            // fraction drawn right now (determinate mode)
    private float target = -1f;     // -1 = no progress known
    private boolean active;

    public LoadingRingView(Context context) { this(context, null); }

    public LoadingRingView(Context context, AttributeSet attrs) {
        super(context, attrs);
        track.setStyle(Paint.Style.STROKE);
        track.setColor(Color.argb(46, 140, 190, 255));
        arc.setStyle(Paint.Style.STROKE);
        arc.setStrokeCap(Paint.Cap.ROUND);
        spin.setDuration(1100);
        spin.setRepeatCount(ValueAnimator.INFINITE);
        spin.setInterpolator(new LinearInterpolator());
        spin.addUpdateListener(a -> { rotation = (float) a.getAnimatedValue(); invalidate(); });
        breathe.setDuration(1500);
        breathe.setRepeatCount(ValueAnimator.INFINITE);
        breathe.setRepeatMode(ValueAnimator.REVERSE);
        breathe.setInterpolator(new AccelerateDecelerateInterpolator());
        breathe.addUpdateListener(a -> sweep = 40f + 220f * (float) a.getAnimatedValue());
        setVisibility(GONE);
        setAlpha(0f);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    public boolean isActive() { return active; }

    /** Fraction between 0 and 1 to show a real progress, or a negative value for the spinning mode. */
    public void setProgress(float fraction) {
        boolean wasDeterminate = target >= 0f;
        target = fraction < 0f ? -1f : Math.min(1f, fraction);
        if (target < 0f) {
            if (wasDeterminate) { shown = 0f; if (active) startAnimators(); }
            invalidate();
            return;
        }
        stopAnimators();
        if (fill != null) fill.cancel();
        fill = ValueAnimator.ofFloat(shown, target);
        fill.setDuration(320);
        fill.setInterpolator(new DecelerateInterpolator());
        fill.addUpdateListener(a -> { shown = (float) a.getAnimatedValue(); invalidate(); });
        fill.start();
    }

    public void setActive(boolean value) {
        if (value == active) return;
        active = value;
        animate().cancel();
        if (value) {
            setVisibility(VISIBLE);
            setScaleX(0.6f);
            setScaleY(0.6f);
            animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(260)
                    .setInterpolator(new OvershootInterpolator(2.2f)).start();
            if (target < 0f) startAnimators();
        } else {
            animate().alpha(0f).scaleX(0.8f).scaleY(0.8f).setDuration(180)
                    .withEndAction(() -> {
                        if (!active) { setVisibility(GONE); stopAnimators(); }
                    }).start();
        }
    }

    private void startAnimators() {
        if (!spin.isStarted()) spin.start();
        if (!breathe.isStarted()) breathe.start();
    }

    private void stopAnimators() {
        spin.cancel();
        breathe.cancel();
    }

    @Override protected void onDetachedFromWindow() {
        stopAnimators();
        if (fill != null) fill.cancel();
        super.onDetachedFromWindow();
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (active && target < 0f) startAnimators();
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        float size = Math.min(w - getPaddingLeft() - getPaddingRight(), h - getPaddingTop() - getPaddingBottom());
        float density = getResources().getDisplayMetrics().density;
        float stroke = Math.max(1.8f * density, Math.min(2.8f * density, size * 0.12f));
        track.setStrokeWidth(stroke);
        arc.setStrokeWidth(stroke);
        float inset = stroke / 2f + 0.5f;
        bounds.set(getPaddingLeft() + inset, getPaddingTop() + inset, w - getPaddingRight() - inset, h - getPaddingBottom() - inset);
        arc.setShader(new SweepGradient(w / 2f, h / 2f,
                new int[]{Color.argb(0, 70, 221, 251), CYAN, BLUE}, new float[]{0f, 0.55f, 1f}));
    }

    @Override protected void onDraw(Canvas canvas) {
        canvas.drawOval(bounds, track);
        if (target >= 0f) {
            if (shown <= 0.002f) return;
            arc.setShader(null);
            arc.setColor(mix(BLUE, CYAN, shown));
            canvas.drawArc(bounds, -90f, 360f * shown, false, arc);
            return;
        }
        if (arc.getShader() == null) onSizeChanged(getWidth(), getHeight(), 0, 0);
        canvas.save();
        canvas.rotate(rotation, getWidth() / 2f, getHeight() / 2f);
        // The gradient runs 0..360 degrees and is brightest at 360, so the arc ends there: transparent tail, bright head.
        canvas.drawArc(bounds, 360f - sweep, sweep, false, arc);
        canvas.restore();
    }

    private static int mix(int from, int to, float amount) {
        return Color.rgb(
                Math.round(Color.red(from) + (Color.red(to) - Color.red(from)) * amount),
                Math.round(Color.green(from) + (Color.green(to) - Color.green(from)) * amount),
                Math.round(Color.blue(from) + (Color.blue(to) - Color.blue(from)) * amount));
    }
}
