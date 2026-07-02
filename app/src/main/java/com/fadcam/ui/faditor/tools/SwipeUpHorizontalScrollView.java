package com.fadcam.ui.faditor.tools;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.widget.HorizontalScrollView;

import androidx.annotation.Nullable;

/**
 * HorizontalScrollView for the Faditor tools carousel that detects a vertical
 * swipe-UP and reports it via {@link OnSwipeUpListener}, while still allowing
 * normal horizontal scrolling and child taps.
 *
 * <p>The plain {@code setOnTouchListener} + {@code GestureDetector} approach
 * fails here because the clickable tool cells consume the DOWN event, so the
 * scroll view's touch listener never sees a complete gesture. By overriding
 * {@link #onInterceptTouchEvent} we watch every gesture from the DOWN: once the
 * finger has moved up further than it has moved sideways (past touch-slop) we
 * intercept, fire the swipe-up callback, and cancel the child so no tap fires.</p>
 */
public class SwipeUpHorizontalScrollView extends HorizontalScrollView {

    public interface OnSwipeUpListener {
        void onSwipeUp();
    }

    /**
     * Edit-mode drag delegate. When {@link #isActive()} returns true, this view
     * routes touch gestures to the delegate for drag-reordering instead of
     * scrolling / swipe-up. Returning true from the down/move handlers means
     * "I'm handling this gesture."
     */
    public interface EditDragDelegate {
        boolean isActive();
        void onDragStart(float x, float y);
        void onDragMove(float x, float y);
        void onDragEnd();
    }

    @Nullable private OnSwipeUpListener swipeUpListener;
    @Nullable private EditDragDelegate dragDelegate;
    private float downX, downY;
    private boolean firedForGesture;
    private boolean draggingEdit;
    private final int slop;
    private final int minUp;

    public SwipeUpHorizontalScrollView(Context context) {
        this(context, null);
    }

    public SwipeUpHorizontalScrollView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        this.slop = ViewConfiguration.get(context).getScaledTouchSlop();
        this.minUp = Math.round(28 * context.getResources().getDisplayMetrics().density);
    }

    public void setOnSwipeUpListener(@Nullable OnSwipeUpListener l) {
        this.swipeUpListener = l;
    }

    public void setEditDragDelegate(@Nullable EditDragDelegate d) {
        this.dragDelegate = d;
    }

    private boolean editActive() {
        return dragDelegate != null && dragDelegate.isActive();
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        // In edit mode, intercept all gestures to drive drag-reordering.
        if (editActive()) {
            if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
                downX = ev.getX();
                downY = ev.getY();
            }
            // Intercept once the finger moves horizontally beyond slop.
            if (ev.getActionMasked() == MotionEvent.ACTION_MOVE
                    && Math.abs(ev.getX() - downX) > slop) {
                return true;
            }
            return super.onInterceptTouchEvent(ev);
        }
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = ev.getX();
                downY = ev.getY();
                firedForGesture = false;
                break;
            case MotionEvent.ACTION_MOVE:
                if (!firedForGesture) {
                    float dx = ev.getX() - downX;
                    float dy = ev.getY() - downY;
                    if (dy < -minUp && Math.abs(dy) > Math.abs(dx) + slop) {
                        firedForGesture = true;
                        if (swipeUpListener != null) swipeUpListener.onSwipeUp();
                        // Intercept so children get CANCEL (no stray tap) and we
                        // swallow the rest of this gesture.
                        return true;
                    }
                }
                break;
            default:
                break;
        }
        return super.onInterceptTouchEvent(ev);
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (editActive()) {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = ev.getX();
                    downY = ev.getY();
                    draggingEdit = true;
                    if (dragDelegate != null) dragDelegate.onDragStart(downX + getScrollX(), downY);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    // If we intercepted mid-gesture (drag began on a child cell),
                    // lazily start the drag anchored at the original DOWN point.
                    if (!draggingEdit) {
                        draggingEdit = true;
                        if (dragDelegate != null) dragDelegate.onDragStart(downX + getScrollX(), downY);
                    }
                    if (dragDelegate != null) {
                        dragDelegate.onDragMove(ev.getX() + getScrollX(), ev.getY());
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (draggingEdit && dragDelegate != null) dragDelegate.onDragEnd();
                    draggingEdit = false;
                    return true;
                default:
                    return true;
            }
        }
        // If we intercepted for a swipe-up, consume remaining events silently.
        if (firedForGesture) {
            return true;
        }
        return super.onTouchEvent(ev);
    }
}
