package cl.arichard.descargador.util;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import cl.arichard.descargador.R;

/**
 * Fast-scroll handle with a fixed, comfortable size (the stock one shrinks with the list length and
 * becomes almost impossible to grab). Dragging jumps proportionally through the list; it fades out when idle.
 */
public class FastScrollView extends View {
    private static final long HIDE_DELAY_MS = 1200;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final float thumbHeight, thumbWidth, activeWidth, touchSlop;
    private RecyclerView recycler;
    private boolean dragging;
    private float fraction;
    private final Runnable hide = () -> animate().alpha(0f).setDuration(260).start();

    private final RecyclerView.OnScrollListener scrollListener = new RecyclerView.OnScrollListener() {
        @Override public void onScrolled(RecyclerView view, int dx, int dy) {
            if (dragging) return;
            int range = view.computeVerticalScrollRange() - view.computeVerticalScrollExtent();
            fraction = range <= 0 ? 0f : Math.min(1f, Math.max(0f, view.computeVerticalScrollOffset() / (float) range));
            if (dy != 0 && range > view.getHeight()) reveal();
            invalidate();
        }
    };

    public FastScrollView(Context context, AttributeSet attrs) {
        super(context, attrs);
        float density = context.getResources().getDisplayMetrics().density;
        thumbHeight = 64 * density;
        thumbWidth = 5 * density;
        activeWidth = 9 * density;
        touchSlop = 14 * density;
        setAlpha(0f);
    }

    public void attach(RecyclerView view) {
        if (recycler != null) recycler.removeOnScrollListener(scrollListener);
        recycler = view;
        view.addOnScrollListener(scrollListener);
    }

    private void reveal() {
        removeCallbacks(hide);
        animate().cancel();
        animate().alpha(1f).setDuration(120).start();
        postDelayed(hide, HIDE_DELAY_MS);
    }

    private float thumbTop() {
        return fraction * Math.max(0f, getHeight() - thumbHeight);
    }

    @Override protected void onDraw(Canvas canvas) {
        float width = dragging ? activeWidth : thumbWidth;
        float right = getWidth() - 3 * getResources().getDisplayMetrics().density;
        float top = thumbTop();
        rect.set(right - width, top, right, top + thumbHeight);
        paint.setColor(ContextCompat.getColor(getContext(), dragging ? R.color.accent : R.color.text_secondary));
        paint.setAlpha(dragging ? 255 : 170);
        canvas.drawRoundRect(rect, width / 2f, width / 2f, paint);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (recycler == null || recycler.getAdapter() == null) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                float top = thumbTop();
                boolean onThumb = event.getY() >= top - touchSlop && event.getY() <= top + thumbHeight + touchSlop
                        && event.getX() >= getWidth() - 2 * touchSlop - thumbWidth;
                if (!onThumb) return false;
                dragging = true;
                getParent().requestDisallowInterceptTouchEvent(true);
                removeCallbacks(hide);
                animate().cancel(); setAlpha(1f);
                jumpTo(event.getY());
                return true;
            }
            case MotionEvent.ACTION_MOVE:
                if (dragging) { jumpTo(event.getY()); return true; }
                return false;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (dragging) {
                    dragging = false;
                    postDelayed(hide, HIDE_DELAY_MS);
                    invalidate();
                    return true;
                }
                return false;
            default: return false;
        }
    }

    private void jumpTo(float y) {
        float travel = Math.max(1f, getHeight() - thumbHeight);
        fraction = Math.min(1f, Math.max(0f, (y - thumbHeight / 2f) / travel));
        int count = recycler.getAdapter().getItemCount();
        if (count > 0 && recycler.getLayoutManager() instanceof LinearLayoutManager)
            ((LinearLayoutManager) recycler.getLayoutManager()).scrollToPositionWithOffset(Math.round(fraction * (count - 1)), 0);
        invalidate();
    }

    @Override protected void onDetachedFromWindow() {
        removeCallbacks(hide);
        if (recycler != null) recycler.removeOnScrollListener(scrollListener);
        super.onDetachedFromWindow();
    }
}
