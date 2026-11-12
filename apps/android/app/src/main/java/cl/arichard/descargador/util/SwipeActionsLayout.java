package cl.arichard.descargador.util;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.widget.LinearLayout;

/** Recognizes track and upward swipes, including gestures that start over buttons. */
public class SwipeActionsLayout extends LinearLayout {
    private float downX, downY;
    private int axis;
    private boolean rejected;
    private final int slop;
    private final float upwardSwipeDistance;
    private Runnable next, previous, expand;
    public interface HorizontalDragListener {
        void onDrag(float distance);
        void onEnd(boolean committed);
    }
    private HorizontalDragListener dragListener;
    public void setHorizontalDragListener(HorizontalDragListener listener) { dragListener = listener; }

    public SwipeActionsLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        ViewConfiguration configuration = ViewConfiguration.get(context);
        slop = configuration.getScaledTouchSlop();
        upwardSwipeDistance = Math.max(slop * 2, 32 * getResources().getDisplayMetrics().density);
        setClickable(true);
    }

    public void setSwipeActions(Runnable next, Runnable previous, Runnable expand) {
        this.next = next; this.previous = previous; this.expand = expand;
    }

    private void begin(MotionEvent event) {
        downX = event.getX(); downY = event.getY(); axis = 0; rejected = false;
    }
    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) begin(event);
        if (action == MotionEvent.ACTION_POINTER_DOWN) cancelGesture();
        return super.dispatchTouchEvent(event);
    }
    private void disallowParent(boolean disallow) {
        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(disallow);
    }
    private void cancelGesture() {
        if (axis == 1 && dragListener != null) dragListener.onEnd(false);
        axis = 0; rejected = true; setPressed(false);
        disallowParent(false);
    }
    private boolean claim(MotionEvent event) {
        if (event.getPointerCount() != 1) { rejected = true; axis = 0; return false; }
        if (!rejected && axis == 0) {
            float dx = event.getX() - downX, dy = event.getY() - downY;
            if (Math.abs(dx) > slop * 2 && Math.abs(dx) > Math.abs(dy) * 1.5f) axis = 1;
            else if (expand != null && dy < -slop && isUpward(dx, dy)) axis = 2;
            // Give an imprecise upward start time to resolve before rejecting it as diagonal.
            else if (dy > slop * 2 || Math.max(Math.abs(dx), Math.abs(dy)) > slop * 4) rejected = true;
        }
        if (axis != 0) disallowParent(true);
        return axis != 0;
    }

    private static boolean isUpward(float x, float y) {
        return -y > Math.abs(x) * 1.15f;
    }

    private boolean suppressClick(MotionEvent event) {
        return rejected || Math.max(Math.abs(event.getX() - downX), Math.abs(event.getY() - downY)) > slop * 2;
    }

    @Override public boolean onInterceptTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        // Cancel a child's pending click even if the motion is rejected as a swipe.
        if (action == MotionEvent.ACTION_MOVE) return claim(event) || rejected;
        return action == MotionEvent.ACTION_POINTER_DOWN
                || (action == MotionEvent.ACTION_UP && suppressClick(event));
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        float dx = event.getX() - downX, dy = event.getY() - downY;
        if (event.getActionMasked() == MotionEvent.ACTION_MOVE && (claim(event) || rejected)) {
            if (axis == 1 && dragListener != null) dragListener.onDrag(dx);
            setPressed(false);
            return true;
        }
        if (event.getActionMasked() == MotionEvent.ACTION_UP && axis != 0) {
            disallowParent(false);
            float threshold = 48 * getResources().getDisplayMetrics().density;
            if (axis == 1 && dragListener != null) dragListener.onEnd(Math.abs(dx) > threshold);
            if (axis == 1 && Math.abs(dx) > threshold) {
                Runnable action = dx < 0 ? next : previous;
                if (action != null) action.run();
            } else if (axis == 2 && -dy >= upwardSwipeDistance && isUpward(dx, dy) && expand != null) {
                expand.run();
            }
            axis = 0; return true;
        }
        if (event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
            cancelGesture(); return true;
        }
        if (event.getActionMasked() == MotionEvent.ACTION_UP && suppressClick(event)) {
            setPressed(false);
            return true;
        }
        return super.onTouchEvent(event);
    }
}
