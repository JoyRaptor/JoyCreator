package com.fadcam.ui.faditor.tools;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.widget.HorizontalScrollView;

import androidx.annotation.Nullable;

/**
 * HorizontalScrollView for the Faditor tools carousel.
 *
 * <p><b>Normal mode.</b> Detects a vertical swipe-UP and reports it via
 * {@link OnSwipeUpListener} (used to open the all-tools drawer), while still
 * allowing normal horizontal scrolling and child taps. The plain
 * {@code setOnTouchListener} + {@code GestureDetector} approach fails because
 * the clickable tool cells consume the DOWN event, so the scroll view's touch
 * listener never sees a full gesture; overriding {@link #onInterceptTouchEvent}
 * lets us watch every gesture from the DOWN.</p>
 *
 * <p><b>Edit mode (v2).</b> Swipes/flings must SCROLL the row and must never
 * move icons. Only a LONG-PRESS on a cell picks it up to drag. This view runs a
 * long-press timer on the DOWN cell; if the finger stays put past the timeout
 * it asks the {@link EditDragDelegate} to begin a drag and, from then on,
 * intercepts the gesture so it drives the drag (lift + green drop line +
 * near-edge auto-scroll) instead of scrolling. If the finger moves past
 * touch-slop before the timer fires, the long-press is cancelled and the
 * gesture scrolls normally.</p>
 */
public class SwipeUpHorizontalScrollView extends HorizontalScrollView {

    public interface OnSwipeUpListener {
        void onSwipeUp();
    }

    /**
     * Edit-mode drag delegate (v2). The scroll view drives the drag by calling
     * these; content-space X is {@code rawX + scrollX} so the delegate works in
     * the un-scrolled coordinate space of the row.
     */
    public interface EditDragDelegate {
        /** True when the carousel is in edit mode (long-press-to-drag armed). */
        boolean isEditMode();
        /** Is there a tool cell at this content-space X (a valid drag target)? */
        boolean hasCellAtContentX(float contentX);
        /** Begin dragging the cell under this content-space X. */
        void onDragStart(float contentX);
        /** Finger moved. {@code viewportX} is X within this view (for edge auto-scroll). */
        void onDragMove(float contentX, float viewportX);
        /** Finger lifted / cancelled — settle the item into its slot. */
        void onDragEnd();
    }

    @Nullable private OnSwipeUpListener swipeUpListener;
    @Nullable private EditDragDelegate dragDelegate;

    private final Handler handler = new Handler(Looper.getMainLooper());
    @Nullable private Runnable longPressRunnable;

    private float downX, downY;
    private boolean firedForGesture;   // normal-mode swipe-up fired
    private boolean dragging;          // edit-mode drag in progress
    private boolean longPressArmed;    // edit-mode long-press timer running
    private final int slop;
    private final int minUp;
    private final int longPressTimeout;

    public SwipeUpHorizontalScrollView(Context context) {
        this(context, null);
    }

    public SwipeUpHorizontalScrollView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        this.slop = ViewConfiguration.get(context).getScaledTouchSlop();
        this.minUp = Math.round(28 * context.getResources().getDisplayMetrics().density);
        this.longPressTimeout = ViewConfiguration.getLongPressTimeout();
    }

    public void setOnSwipeUpListener(@Nullable OnSwipeUpListener l) {
        this.swipeUpListener = l;
    }

    public void setEditDragDelegate(@Nullable EditDragDelegate d) {
        this.dragDelegate = d;
    }

    private boolean editActive() {
        return dragDelegate != null && dragDelegate.isEditMode();
    }

    // ── Long-press arming ─────────────────────────────────────────────

    private void armPickup(final float contentX) {
        cancelPickup();
        longPressArmed = true;
        longPressRunnable = () -> {
            longPressArmed = false;
            if (dragDelegate != null && dragDelegate.hasCellAtContentX(contentX)) {
                performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
                dragging = true;
                dragDelegate.onDragStart(contentX);
            }
        };
        handler.postDelayed(longPressRunnable, longPressTimeout);
    }

    private void cancelPickup() {
        longPressArmed = false;
        if (longPressRunnable != null) {
            handler.removeCallbacks(longPressRunnable);
            longPressRunnable = null;
        }
    }

    // ── Intercept ─────────────────────────────────────────────────────

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (editActive()) {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = ev.getX();
                    downY = ev.getY();
                    dragging = false;
                    // Arm long-press only over an actual cell; empty gaps/the
                    // divider/Done fall through to normal scrolling.
                    if (dragDelegate != null
                            && dragDelegate.hasCellAtContentX(ev.getX() + getScrollX())) {
                        armPickup(ev.getX() + getScrollX());
                    }
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (dragging) return true; // we own the gesture now
                    // Movement before the timer fires = a scroll/fling, not a
                    // drag: cancel the pickup and let the ScrollView scroll.
                    if (longPressArmed
                            && (Math.abs(ev.getX() - downX) > slop
                            || Math.abs(ev.getY() - downY) > slop)) {
                        cancelPickup();
                    }
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    cancelPickup();
                    break;
                default:
                    break;
            }
            return super.onInterceptTouchEvent(ev);
        }

        // ── Normal mode: swipe-up detection ──
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
                        return true; // children get CANCEL, no stray tap
                    }
                }
                break;
            default:
                break;
        }
        return super.onInterceptTouchEvent(ev);
    }

    // ── Touch ─────────────────────────────────────────────────────────

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (editActive()) {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    // Reached here only if a child didn't take the DOWN (empty
                    // area). Arm long-press if over a cell anyway.
                    downX = ev.getX();
                    downY = ev.getY();
                    if (!dragging && dragDelegate != null
                            && dragDelegate.hasCellAtContentX(ev.getX() + getScrollX())) {
                        armPickup(ev.getX() + getScrollX());
                    }
                    if (dragging) return true;
                    return super.onTouchEvent(ev);
                case MotionEvent.ACTION_MOVE:
                    if (dragging) {
                        if (dragDelegate != null) {
                            dragDelegate.onDragMove(ev.getX() + getScrollX(), ev.getX());
                        }
                        return true;
                    }
                    if (longPressArmed
                            && (Math.abs(ev.getX() - downX) > slop
                            || Math.abs(ev.getY() - downY) > slop)) {
                        cancelPickup();
                    }
                    return super.onTouchEvent(ev);
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    cancelPickup();
                    if (dragging) {
                        dragging = false;
                        if (dragDelegate != null) dragDelegate.onDragEnd();
                        return true;
                    }
                    return super.onTouchEvent(ev);
                default:
                    if (dragging) return true;
                    return super.onTouchEvent(ev);
            }
        }

        if (firedForGesture) {
            return true; // consume the rest of a swipe-up gesture silently
        }
        return super.onTouchEvent(ev);
    }
}
