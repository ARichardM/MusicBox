package cl.arichard.descargador.util;

import android.content.Context;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.LinearLayout;
import cl.arichard.descargador.R;

/** Handles vertical dismiss and horizontal track changes beginning on the artwork. */
public class PlayerDismissLayout extends LinearLayout {
    private float downX, downY;
    private long downTime;
    private boolean eligible, dragging, rejected;
    private int axis;
    private final int slop;
    private Runnable dismiss, next, previous;

    public PlayerDismissLayout(Context context, AttributeSet attrs) {
        super(context, attrs); slop = ViewConfiguration.get(context).getScaledTouchSlop();
    }
    public void setDismissAction(Runnable action) { dismiss = action; }
    public void setTrackActions(Runnable nextAction, Runnable previousAction) {
        next = nextAction;
        previous = previousAction;
    }
    private void begin(MotionEvent event) {
        downX = event.getRawX(); downY = event.getRawY(); downTime = event.getEventTime();
        dragging = false; rejected = false; axis = 0;
        View lyrics = findViewById(R.id.containerLyrics);
        if (lyrics != null && lyrics.getVisibility() == View.VISIBLE) {
            eligible = false;
            return;
        }
        View artwork = findViewById(R.id.playerArtworkArea);
        Rect rect = new Rect();
        eligible = artwork != null && artwork.getGlobalVisibleRect(rect) && rect.contains((int) downX, (int) downY);
    }
    private boolean claim(MotionEvent event) {
        if (!eligible || rejected) return false;
        if (event.getPointerCount() != 1) { rejected = true; reset(); return false; }
        float dx = event.getRawX() - downX, dy = event.getRawY() - downY;
        if (!dragging && (Math.abs(dx) > slop * 2 || Math.abs(dy) > slop * 2)) {
            if (Math.abs(dx) > Math.abs(dy) * 1.5f) axis = 1;
            else if (dy > 0 && dy > Math.abs(dx) * 1.5f) axis = 2;
            else { rejected = true; return false; }
            dragging = true;
            getParent().requestDisallowInterceptTouchEvent(true);
        }
        return dragging;
    }
    @Override public boolean onInterceptTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) begin(event);
        if (event.getActionMasked() == MotionEvent.ACTION_MOVE) return claim(event);
        return false;
    }
    @Override public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: begin(event); return eligible || super.onTouchEvent(event);
            case MotionEvent.ACTION_MOVE:
                if (claim(event)) {
                    if (axis == 1) {
                        View artwork = findViewById(R.id.playerArtworkArea);
                        float dx = event.getRawX() - downX;
                        artwork.setTranslationX(dx * .72f);
                        artwork.setAlpha(1f - Math.min(.45f, Math.abs(dx) / Math.max(1, getWidth())));
                    } else {
                        float dy = Math.max(0, event.getRawY() - downY);
                        setTranslationY(dy * .8f);
                        setAlpha(1f - Math.min(.35f, dy / Math.max(1, getHeight())));
                    }
                }
                return eligible;
            case MotionEvent.ACTION_UP:
                float dx = event.getRawX() - downX;
                float dy = event.getRawY() - downY;
                float density = getResources().getDisplayMetrics().density;
                long elapsed = Math.max(1, event.getEventTime() - downTime);
                float verticalSpeed = dy * 1000 / elapsed;
                float horizontalSpeed = Math.abs(dx) * 1000 / elapsed;
                boolean close = dragging && !rejected && axis == 2 && (dy > 120 * density
                        || (dy > 56 * density && verticalSpeed > 900 * density));
                boolean changeTrack = dragging && !rejected && axis == 1 && (Math.abs(dx) > 72 * density
                        || (Math.abs(dx) > 40 * density && horizontalSpeed > 800 * density));
                boolean handled = eligible;
                dragging = false; eligible = false; axis = 0;
                getParent().requestDisallowInterceptTouchEvent(false);
                if (close && dismiss != null) dismiss.run();
                else {
                    reset();
                    if (changeTrack) {
                        performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
                        Runnable action = dx < 0 ? next : previous;
                        if (action != null) action.run();
                    }
                }
                return handled;
            case MotionEvent.ACTION_POINTER_DOWN:
            case MotionEvent.ACTION_CANCEL:
                eligible = false; rejected = true; dragging = false; axis = 0;
                getParent().requestDisallowInterceptTouchEvent(false); reset(); return true;
        }
        return super.onTouchEvent(event);
    }
    private void reset() {
        animate().translationY(0).alpha(1f).setDuration(180).start();
        View artwork = findViewById(R.id.playerArtworkArea);
        if (artwork != null) artwork.animate().translationX(0).alpha(1f).setDuration(180).start();
    }
}
