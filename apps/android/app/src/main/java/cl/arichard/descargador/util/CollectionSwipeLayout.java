package cl.arichard.descargador.util;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;

/** Horizontal collection paging that leaves vertical RecyclerView scrolling untouched. */
public final class CollectionSwipeLayout extends FrameLayout {
    public interface Listener {
        boolean canSwipe();
        boolean canNavigate(int direction);
        void onDrag(float distance, boolean destinationAvailable);
        void onRelease(int direction, boolean committed, float distance, float velocity);
    }

    private final int slop;
    private float downX, downY;
    private boolean horizontal, vertical;
    private VelocityTracker velocity;
    private Listener listener;

    public CollectionSwipeLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        slop = ViewConfiguration.get(context).getScaledTouchSlop();
    }

    public void setListener(Listener listener) { this.listener = listener; }
    public boolean isSwipeEnabled() { return listener != null && listener.canSwipe(); }

    private void begin(MotionEvent event) {
        downX = event.getRawX(); downY = event.getRawY();
        horizontal = false; vertical = false;
        if (velocity != null) velocity.recycle();
        velocity = VelocityTracker.obtain(); velocity.addMovement(event);
    }

    private boolean claim(MotionEvent event) {
        if (!isSwipeEnabled() || event.getPointerCount() != 1 || vertical) return false;
        float dx = Math.abs(event.getRawX() - downX), dy = Math.abs(event.getRawY() - downY);
        if (!horizontal && Math.max(dx, dy) > slop * 2f) {
            if (dx > dy * 1.35f) horizontal = true;
            else vertical = true;
        }
        return horizontal;
    }

    @Override public boolean onInterceptTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) begin(event);
        else if (event.getActionMasked() == MotionEvent.ACTION_MOVE) return claim(event);
        return false;
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (velocity != null) velocity.addMovement(event);
        if (event.getActionMasked() == MotionEvent.ACTION_MOVE && claim(event)) {
            float distance = event.getRawX() - downX;
            int direction = distance < 0 ? 1 : -1;
            listener.onDrag(distance, listener.canNavigate(direction));
            return true;
        }
        if (event.getActionMasked() == MotionEvent.ACTION_UP && horizontal) {
            float distance = event.getRawX() - downX;
            int direction = distance < 0 ? 1 : -1;
            velocity.computeCurrentVelocity(1000);
            float speed = velocity.getXVelocity();
            boolean enoughDistance = Math.abs(distance) > getWidth() * 0.17f;
            boolean enoughSpeed = Math.abs(speed) > 850f && Math.signum(speed) == Math.signum(distance);
            boolean committed = listener.canNavigate(direction) && (enoughDistance || enoughSpeed);
            listener.onRelease(direction, committed, distance, speed);
            reset(); return true;
        }
        if (event.getActionMasked() == MotionEvent.ACTION_CANCEL && horizontal) {
            listener.onRelease(0, false, event.getRawX() - downX, 0);
            reset(); return true;
        }
        return super.onTouchEvent(event);
    }

    private void reset() {
        horizontal = false; vertical = false;
        if (velocity != null) { velocity.recycle(); velocity = null; }
    }
}
