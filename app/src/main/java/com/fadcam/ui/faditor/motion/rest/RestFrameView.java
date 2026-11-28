package com.fadcam.ui.faditor.motion.rest;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.function.Supplier;

/**
 * STU-7 §K.3: the exact export frame, laid over the composed picture while the playhead rests,
 * and a small turning arc in the picture's corner while it is being drawn.
 *
 * <p>It sits in the preview stack just above the text and image layer, so it covers the video
 * and every Canvas-drawn title, picture and sprite (which would otherwise paint their sharp
 * copies over it), and below captions, the crop and safe-zone guides, the selection handles and
 * the transform box, which stay visible. It never takes a touch: not clickable, not focusable,
 * so a tap goes on to the handles and layers beneath exactly as if it were not there.</p>
 */
public final class RestFrameView extends View {

    private final Paint framePaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final Paint arcPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF arc = new RectF();
    private final Supplier<RectF> canvasRect;
    @Nullable private Bitmap frame;
    private boolean busy;
    private final String busyLabel;

    public RestFrameView(@NonNull Context ctx, @NonNull Supplier<RectF> canvasRect,
                         @NonNull String busyLabel) {
        super(ctx);
        this.canvasRect = canvasRect;
        this.busyLabel = busyLabel;
        setClickable(false);
        setFocusable(false);
        setWillNotDraw(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        float d = ctx.getResources().getDisplayMetrics().density;
        arcPaint.setStyle(Paint.Style.STROKE);
        arcPaint.setStrokeWidth(2f * d);
        arcPaint.setStrokeCap(Paint.Cap.ROUND);
        arcPaint.setColor(0xB3FFFFFF);
    }

    /** Show {@code b}; the caller owns it and recycles it only after replacing or clearing it. */
    public void show(@NonNull Bitmap b) {
        frame = b;
        invalidate();
    }

    public void clear() {
        if (frame == null && !busy) return;
        frame = null;
        setBusy(false);
        invalidate();
    }

    public boolean isShowingFrame() { return frame != null; }

    public boolean isBusy() { return busy; }

    public void setBusy(boolean b) {
        if (busy == b) return;
        busy = b;
        setContentDescription(b ? busyLabel : null);
        invalidate();
    }

    @Override public boolean onTouchEvent(android.view.MotionEvent e) { return false; }

    @Override protected void onDraw(@NonNull Canvas c) {
        RectF r = canvasRect.get();
        if (r == null || r.isEmpty()) return;
        Bitmap b = frame;
        if (b != null && !b.isRecycled()) c.drawBitmap(b, null, r, framePaint);
        if (!busy) return;
        // Small and quiet: a 12dp arc, 8dp inside the picture's top right corner.
        float d = getResources().getDisplayMetrics().density;
        float size = 12f * d, inset = 8f * d;
        arc.set(r.right - inset - size, r.top + inset, r.right - inset, r.top + inset + size);
        float sweep = (android.os.SystemClock.uptimeMillis() % 900L) / 900f * 360f;
        c.drawArc(arc, sweep, 270f, false, arcPaint);
        postInvalidateOnAnimation();
    }
}
