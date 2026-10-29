package cl.arichard.descargador.util;

import android.content.Context;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.RelativeLayout;
import android.widget.SeekBar;

/** Page swipes participate in normal touch dispatch, after giving local gestures priority. */
public class TabSwipeLayout extends RelativeLayout {
    private final int slop;
    private float downX, downY;
    private boolean protectedGesture, vertical, horizontal;
    private Runnable left, right;
    public interface DragListener { void onDrag(float dx); void onCancel(); }
    private DragListener dragListener;
    public void setDragListener(DragListener listener) { dragListener = listener; }

    public TabSwipeLayout(Context context, AttributeSet attrs) {
        super(context, attrs); slop = ViewConfiguration.get(context).getScaledTouchSlop();
    }
    public void setTabActions(Runnable left, Runnable right) { this.left = left; this.right = right; }
    private boolean ownsGesture(View view, int x, int y) {
        Rect bounds = new Rect();
        if (view.getVisibility() != VISIBLE || !view.getGlobalVisibleRect(bounds) || !bounds.contains(x, y)) return false;
        if (Boolean.TRUE.equals(view.getTag(cl.arichard.descargador.R.id.protectedAction))
                || view instanceof HorizontalScrollView || view instanceof EditText || view instanceof SeekBar
                || view instanceof SwipeActionsLayout || view instanceof DragTabBarLayout
                || (view instanceof CollectionSwipeLayout && ((CollectionSwipeLayout) view).isSwipeEnabled())
                || (view != this && view instanceof ViewGroup &&
                (view.canScrollHorizontally(-1) || view.canScrollHorizontally(1)))) return true;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = group.getChildCount() - 1; i >= 0; i--)
                if (ownsGesture(group.getChildAt(i), x, y)) return true;
        }
        return false;
    }
    private void begin(MotionEvent event) {
        downX = event.getRawX(); downY = event.getRawY(); horizontal = false; vertical = false;
        protectedGesture = ownsGesture(this, (int) downX, (int) downY);
    }
    private boolean claim(MotionEvent event) {
        if (event.getPointerCount() != 1) protectedGesture = true;
        if (protectedGesture || vertical) return false;
        float dx = Math.abs(event.getRawX() - downX), dy = Math.abs(event.getRawY() - downY);
        if (!horizontal && Math.max(dx, dy) > slop * 2) {
            if (dx > dy * 1.5f) horizontal = true;
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
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: begin(event); return !protectedGesture;
            case MotionEvent.ACTION_MOVE:
                if (claim(event) && dragListener != null) dragListener.onDrag(event.getRawX() - downX);
                return !protectedGesture;
            case MotionEvent.ACTION_UP:
                float dx = event.getRawX() - downX;
                boolean handled = horizontal && !protectedGesture && !vertical;
                horizontal = false;
                if (handled && Math.abs(dx) > 48 * getResources().getDisplayMetrics().density) {
                    Runnable action = dx < 0 ? left : right;
                    if (action != null) action.run();
                } else if (handled && dragListener != null) dragListener.onCancel();
                return handled || super.onTouchEvent(event);
            case MotionEvent.ACTION_POINTER_DOWN:
            case MotionEvent.ACTION_CANCEL:
                if (horizontal && dragListener != null) dragListener.onCancel();
                protectedGesture = true; horizontal = false; return true;
        }
        return super.onTouchEvent(event);
    }
}
