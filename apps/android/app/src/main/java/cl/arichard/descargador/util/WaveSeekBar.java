package cl.arichard.descargador.util;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import androidx.appcompat.widget.AppCompatSeekBar;
import cl.arichard.descargador.R;

/**
 * Barra de progreso. Con el crossfade activo se convierte en el relieve de volumen de la canción (sus altos y bajos) y,
 * en el tramo final, muestra la mezcla: las barras de la canción que sale bajan mientras las de la siguiente suben.
 * Mientras suena la mezcla la barra se acerca a ese tramo para que se vea con claridad y luego vuelve.
 * Hasta que se conoce el relieve real se dibuja uno aproximado, que se transforma barra a barra cuando llega el verdadero.
 */
public class WaveSeekBar extends AppCompatSeekBar {
    private static final int COLS = WaveformStore.COARSE;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bar = new RectF();
    private final float[] display = new float[COLS], from = new float[COLS], to = new float[COLS];
    private WaveformStore.Envelope envelope, nextEnvelope;
    private int seed;
    private boolean real;
    private float zoneTarget, zoneShown, endFraction = 1f, waveAlpha, zoom, morph = 1f, realness = 1f;
    private boolean waveWanted, playing, dragging, interactive = true;
    private int anchorProgress;
    private long anchorTime;
    private ValueAnimator morphAnimator, zoneAnimator, waveAnimator, zoomAnimator;

    public WaveSeekBar(Context context, AttributeSet attrs) {
        super(context, attrs);
        approximate(display, 0);
        System.arraycopy(display, 0, to, 0, COLS);
    }

    private boolean still() {
        return android.provider.Settings.Global.getFloat(getContext().getContentResolver(),
                android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f;
    }

    /** Sin deslizador ni toques: la versión del minirreproductor. */
    public void setInteractive(boolean interactive) { this.interactive = interactive; setEnabled(interactive); }

    public void setPlaying(boolean playing) {
        if (this.playing == playing) return;
        anchorProgress = getProgress(); anchorTime = SystemClock.elapsedRealtime();
        this.playing = playing;
        postInvalidateOnAnimation();
    }

    @Override public synchronized void setProgress(int progress) {
        anchorProgress = progress; anchorTime = SystemClock.elapsedRealtime();
        super.setProgress(progress);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (!interactive) return false;
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) dragging = true;
        else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            dragging = false; anchorProgress = getProgress(); anchorTime = SystemClock.elapsedRealtime();
        }
        return super.onTouchEvent(event);
    }

    /**
     * Activa o apaga el modo relieve. [zoneFraction] es la parte de la pista que dura la mezcla (0 si no hay) y
     * [endFraction] dónde termina de sonar de verdad la pista (1 si no hay silencio al final).
     */
    public void setCrossfade(boolean wanted, float zoneFraction, float endFraction) {
        zoneFraction = wanted ? Math.max(0f, Math.min(1f, zoneFraction)) : 0f;
        this.endFraction = Math.max(zoneFraction, Math.min(1f, endFraction));
        if (wanted != waveWanted) {
            waveWanted = wanted;
            if (waveAnimator != null) waveAnimator.cancel();
            if (still() || !isShown()) { waveAlpha = wanted ? 1f : 0f; invalidate(); }
            else {
                waveAnimator = ValueAnimator.ofFloat(waveAlpha, wanted ? 1f : 0f);
                waveAnimator.setDuration(460);
                waveAnimator.setInterpolator(new DecelerateInterpolator(1.6f));
                waveAnimator.addUpdateListener(a -> { waveAlpha = (float) a.getAnimatedValue(); invalidate(); });
                waveAnimator.start();
            }
        }
        if (Math.abs(zoneFraction - zoneTarget) > .001f) {
            zoneTarget = zoneFraction;
            if (zoneAnimator != null) zoneAnimator.cancel();
            if (still() || !isShown()) { zoneShown = zoneFraction; invalidate(); }
            else {
                zoneAnimator = ValueAnimator.ofFloat(zoneShown, zoneFraction);
                zoneAnimator.setDuration(560);
                zoneAnimator.setInterpolator(new DecelerateInterpolator(1.8f));
                zoneAnimator.addUpdateListener(a -> { zoneShown = (float) a.getAnimatedValue(); invalidate(); });
                zoneAnimator.start();
            }
        } else invalidate();
    }

    private float zoomTarget;

    /** Mientras suena la mezcla, la barra se acerca al tramo final; al terminar vuelve con la misma suavidad. */
    public void setMixZoom(boolean on) {
        float target = on && zoneTarget > 0f ? 1f : 0f;
        if (target == zoomTarget) return;
        zoomTarget = target;
        if (zoomAnimator != null) zoomAnimator.cancel();
        if (still() || !isShown()) { zoom = target; invalidate(); return; }
        zoomAnimator = ValueAnimator.ofFloat(zoom, target);
        zoomAnimator.setDuration(target > zoom ? 720 : 880);
        zoomAnimator.setInterpolator(new AccelerateDecelerateInterpolator());
        zoomAnimator.addUpdateListener(a -> { zoom = (float) a.getAnimatedValue(); invalidate(); });
        zoomAnimator.start();
    }

    /** Relieve de la canción actual (null o parcial = aún no se conoce: se dibuja uno aproximado) y de la siguiente. */
    public void setEnvelope(WaveformStore.Envelope current, WaveformStore.Envelope next, String songKey) {
        nextEnvelope = next;
        int newSeed = songKey == null ? 0 : songKey.hashCode();
        boolean isReal = current != null && current.coarse != null;
        if (current == envelope && newSeed == seed && isReal == real) { invalidate(); return; }
        envelope = current; seed = newSeed; real = isReal;
        System.arraycopy(display, 0, from, 0, COLS);
        if (isReal) for (int i = 0; i < COLS; i++) to[i] = Math.max(.08f, current.coarse[i]);
        else approximate(to, seed);
        if (morphAnimator != null) morphAnimator.cancel();
        if (still() || !isShown()) { System.arraycopy(to, 0, display, 0, COLS); morph = 1f; realness = isReal ? 1f : 0f; invalidate(); return; }
        final float realFrom = realness, realTo = isReal ? 1f : 0f;
        morph = 0f;
        morphAnimator = ValueAnimator.ofFloat(0f, 1f);
        morphAnimator.setDuration(isReal ? 1000 : 900);
        morphAnimator.addUpdateListener(a -> {
            morph = (float) a.getAnimatedValue();
            realness = realFrom + (realTo - realFrom) * morph;
            for (int i = 0; i < COLS; i++) {
                // Cada barra arranca un poco después que la anterior: el cambio recorre la barra de izquierda a derecha.
                float local = Math.max(0f, Math.min(1f, morph * 2.2f - (i / (float) COLS) * 1.2f));
                float eased = 1f - (1f - local) * (1f - local) * (1f - local);
                // Un leve rebote al llegar: las barras "brotan" en vez de aparecer de golpe.
                eased += (float) Math.sin(local * Math.PI) * .06f;
                display[i] = from[i] + (to[i] - from[i]) * eased;
            }
            invalidate();
        });
        morphAnimator.start();
    }

    /** Relieve aproximado y reproducible (depende solo de la canción): suave, con algo de variación. */
    private static void approximate(float[] out, int seed) {
        java.util.Random random = new java.util.Random(seed * 2654435761L + 17);
        double a = .09 + random.nextDouble() * .07, b = .31 + random.nextDouble() * .2, c = .017 + random.nextDouble() * .02;
        double pa = random.nextDouble() * 6.28, pb = random.nextDouble() * 6.28, pc = random.nextDouble() * 6.28;
        for (int i = 0; i < out.length; i++) {
            double slow = .5 + .5 * Math.sin(a * i + pa), mid = .5 + .5 * Math.sin(b * i + pb), section = .5 + .5 * Math.sin(c * i + pc);
            double jitter = random.nextDouble();
            double value = .22 + .42 * slow * (.55 + .45 * mid) + .16 * section + .12 * jitter;
            out[i] = (float) Math.max(.16, Math.min(.88, value));
        }
        // Entrada y salida suaves, como casi cualquier canción.
        for (int i = 0; i < 4; i++) { float k = (i + 1) / 5f; out[i] *= k; out[out.length - 1 - i] *= k; }
    }

    @Override protected void onDetachedFromWindow() {
        if (morphAnimator != null) morphAnimator.cancel();
        if (zoneAnimator != null) zoneAnimator.cancel();
        if (waveAnimator != null) waveAnimator.cancel();
        if (zoomAnimator != null) zoomAnimator.cancel();
        super.onDetachedFromWindow();
    }

    private float smoothProgress() {
        int max = Math.max(1, getMax());
        float value = getProgress();
        if (playing && !dragging && getMax() > 1)
            value = Math.min(max, anchorProgress + (SystemClock.elapsedRealtime() - anchorTime));
        return Math.max(0f, Math.min(1f, value / max));
    }

    @Override protected synchronized void onDraw(Canvas canvas) {
        if (waveAlpha < .999f) {
            int layer = canvas.saveLayerAlpha(0, 0, getWidth(), getHeight(), Math.round((1f - waveAlpha) * 255));
            super.onDraw(canvas);
            canvas.restoreToCount(layer);
        }
        if (waveAlpha > .001f) drawWave(canvas);
        if (playing && (waveAlpha > .001f || waveWanted)) postInvalidateOnAnimation();
    }

    private float sampleBase(float fraction) {
        float position = Math.max(0f, Math.min(1f, fraction)) * COLS - .5f;
        int i0 = Math.max(0, Math.min(COLS - 1, (int) Math.floor(position))), i1 = Math.min(COLS - 1, i0 + 1);
        float k = position - (float) Math.floor(position);
        return display[i0] + (display[i1] - display[i0]) * Math.max(0f, Math.min(1f, k));
    }

    private float tailAt(double timeMs, double endMs) {
        float[] tail = envelope == null ? null : envelope.tail;
        if (tail == null) return -1f;
        double index = (timeMs - (endMs - WaveformStore.FINE_SECONDS * 1000.0)) / (WaveformStore.FINE_SECONDS * 1000.0) * tail.length;
        return tail[(int) Math.max(0, Math.min(tail.length - 1, index))];
    }

    private void drawWave(Canvas canvas) {
        float density = getResources().getDisplayMetrics().density;
        float left = getPaddingLeft() + (interactive ? 4 * density : 0), right = getWidth() - getPaddingRight() - (interactive ? 4 * density : 0);
        float width = right - left, centre = getHeight() / 2f;
        float maxHalf = interactive ? Math.max(2 * density, centre - 2 * density) : Math.max(2 * density, Math.min(centre - 3 * density, 12 * density));
        float pitch = width / COLS, barWidth = Math.max(1.3f * density, pitch * .6f);
        float progress = smoothProgress(), duration = Math.max(1, getMax());
        int accent = AccentColors.getColor(getContext(), R.color.accent);
        float zoneStartFraction = endFraction - zoneShown;
        // Ventana visible: toda la pista, o (con zoom) el tramo de la mezcla y un poco antes.
        float zoomStart = Math.max(0f, endFraction - zoneTarget * 2.2f), zoomEnd = Math.min(1f, endFraction + Math.max(.01f, zoneTarget * .15f));
        float v0 = zoom * zoomStart, v1 = 1f + (zoomEnd - 1f) * zoom;
        float certainty = .74f + .26f * realness;
        paint.setStyle(Paint.Style.FILL);
        for (int i = 0; i < COLS; i++) {
            float f = v0 + (i + .5f) / COLS * (v1 - v0);
            float x = left + pitch * (i + .5f);
            boolean played = f <= progress;
            float height = sampleBase(f);
            if (zoom > .01f && envelope != null && envelope.tail != null) {
                float tail = tailAt(f * duration, endFraction * duration);
                if (tail >= 0f && f * duration >= endFraction * duration - WaveformStore.FINE_SECONDS * 1000f) height += (Math.max(.08f, tail) - height) * zoom;
            }
            float incomingHeight = 0f;
            if (zoneShown > .001f && f >= zoneStartFraction && f <= endFraction) {
                float t = Math.max(0f, Math.min(1f, (f - zoneStartFraction) / Math.max(.0001f, zoneShown)));
                float zoneMs = zoneShown * duration;
                float tail = tailAt(endFraction * duration - zoneMs + t * zoneMs, endFraction * duration);
                float outgoing = tail >= 0 ? Math.max(.08f, tail) : height;
                height = outgoing * (float) Math.cos(t * Math.PI / 2);
                float[] head = nextEnvelope == null ? null : nextEnvelope.head;
                float source = .5f;
                if (head != null) {
                    double index = t * zoneMs / (WaveformStore.FINE_SECONDS * 1000.0) * head.length;
                    source = Math.max(.08f, head[(int) Math.max(0, Math.min(head.length - 1, index))]);
                }
                incomingHeight = source * (float) Math.sin(t * Math.PI / 2);
            }
            int base = played ? 0xFFFFFFFF : 0x61FFFFFF;
            paint.setColor(withAlpha(base, waveAlpha * certainty));
            float half = Math.max(1f * density, height * maxHalf);
            bar.set(x - barWidth / 2f, centre - half, x + barWidth / 2f, centre + half);
            canvas.drawRoundRect(bar, barWidth / 2f, barWidth / 2f, paint);
            if (incomingHeight > 0f) {
                paint.setColor(withAlpha(played ? accent : (accent & 0x00FFFFFF) | 0x99000000, waveAlpha));
                float inner = barWidth * .56f, halfIn = Math.max(1f * density, incomingHeight * maxHalf);
                bar.set(x - inner / 2f, centre - halfIn, x + inner / 2f, centre + halfIn);
                canvas.drawRoundRect(bar, inner / 2f, inner / 2f, paint);
            }
        }
    }

    private static int withAlpha(int color, float alpha) {
        int a = Math.round(((color >>> 24) & 0xFF) * Math.max(0f, Math.min(1f, alpha)));
        return (a << 24) | (color & 0x00FFFFFF);
    }
}
