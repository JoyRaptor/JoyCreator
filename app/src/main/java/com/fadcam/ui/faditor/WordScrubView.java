package com.fadcam.ui.faditor;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import androidx.annotation.Nullable;

public class WordScrubView extends View {

    public interface Listener {
        void onScrubStarted();
        void onWordDeltaMs(long deltaMs);
        void onScrubFinished();
    }

    @Nullable private Listener listener;
    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint centerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gripPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;

    private float downX;
    private float dragOffsetX;
    private boolean dragging;
    private long accumulatedDeltaMs;
    private ValueAnimator snapAnim;

    private static final float UNIT_DP = 25f;
    private static final float ACCEL_EXPONENT = 2.0f;
    private static final float MAX_VELOCITY_DP_PER_FRAME = 800f;

    public WordScrubView(Context c) { this(c, null); }

    public WordScrubView(Context c, @Nullable AttributeSet a) {
        super(c, a);
        density = getResources().getDisplayMetrics().density;
        trackPaint.setColor(0xFF33333C);
        fillPaint.setColor(0xFF22D3EE);
        centerPaint.setColor(0x88FFFFFF);
        gripPaint.setColor(0x66FFFFFF);
    }

    public void setListener(@Nullable Listener l) { this.listener = l; }

    /** Reset the scrub to centred (e.g. after a new word is loaded). */
    public void reset() {
        if (snapAnim != null) snapAnim.cancel();
        dragOffsetX = 0;
        accumulatedDeltaMs = 0;
        dragging = false;
        invalidate();
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (snapAnim != null) snapAnim.cancel();
                downX = e.getX();
                dragOffsetX = 0;
                accumulatedDeltaMs = 0;
                dragging = true;
                getParent().requestDisallowInterceptTouchEvent(true);
                if (listener != null) listener.onScrubStarted();
                return true;

            case MotionEvent.ACTION_MOVE: {
                float dx = e.getX() - downX;
                dragOffsetX = dx;
                float maxDragPx = 150f * density;
                if (getWidth() > 0) {
                    maxDragPx = getWidth() / 2f;
                }
                float fraction = Math.max(-1f, Math.min(1f, dragOffsetX / maxDragPx));
                long targetDeltaMs = (long) (fraction * 250f);
                if (listener != null) {
                    listener.onWordDeltaMs(targetDeltaMs);
                }
                invalidate();
                return true;
            }

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                getParent().requestDisallowInterceptTouchEvent(false);
                snapBack();
                if (listener != null) listener.onScrubFinished();
                return true;
        }
        return super.onTouchEvent(e);
    }

    private void snapBack() {
        snapAnim = ValueAnimator.ofFloat(dragOffsetX, 0f);
        snapAnim.setDuration(200);
        snapAnim.setInterpolator(new DecelerateInterpolator());
        snapAnim.addUpdateListener(a -> {
            dragOffsetX = (float) a.getAnimatedValue();
            invalidate();
        });
        snapAnim.start();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        float cy = h / 2f;
        float trackH = 6 * density;
        float cx = w / 2f;

        canvas.drawRoundRect(cx - w / 2f, cy - trackH / 2f, cx + w / 2f, cy + trackH / 2f,
                3 * density, 3 * density, trackPaint);

        float fillEnd;
        if (dragging) {
            fillEnd = cx + dragOffsetX;
        } else {
            fillEnd = cx;
        }
        float left = Math.min(cx, fillEnd);
        float right = Math.max(cx, fillEnd);
        canvas.drawRoundRect(left, cy - trackH / 2f, right, cy + trackH / 2f,
                3 * density, 3 * density, fillPaint);

        canvas.drawCircle(cx, cy, 4 * density, centerPaint);
        float gripW = 3 * density;
        for (int i = -1; i <= 1; i++) {
            canvas.drawRect(cx + dragOffsetX + i * gripW * 2 - gripW / 2f, cy - 8 * density,
                    cx + dragOffsetX + i * gripW * 2 + gripW / 2f, cy + 8 * density, gripPaint);
        }
    }
}
