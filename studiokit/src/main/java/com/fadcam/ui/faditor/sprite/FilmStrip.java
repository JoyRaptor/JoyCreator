package com.fadcam.ui.faditor.sprite;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;
import android.widget.LinearLayout;
import com.fadcam.ui.faditor.Studio;

/** Shared SpriteLab film stock. Board outline geometry is supplied by its core layout.
 * Solid stock keeps film on either side of the punched holes: edge-biting holes read as
 * castle crenellations rather than sprockets. */
public class FilmStrip extends LinearLayout {
    public enum Style { SOLID, OUTLINE }
    /** Outline stock has no fill. All rectangles and stroke widths come from the host's layout. */
    public static void drawOutline(Canvas canvas, Paint ink, java.util.List<RectF> rails,
                                   java.util.List<RectF> holes) {
        drawOutline(canvas, ink, rails, holes, 0);
    }
    public static void drawOutline(Canvas canvas, Paint ink, java.util.List<RectF> rails,
                                   java.util.List<RectF> holes, float holeRadiusPx) {
        Paint.Style before = ink.getStyle();
        ink.setStyle(Paint.Style.STROKE);
        for (RectF rail : rails) canvas.drawRect(rail, ink);
        for (RectF hole : holes) canvas.drawRoundRect(hole, holeRadiusPx, holeRadiusPx, ink);
        ink.setStyle(before);
    }
    /** Host geometry mode preserves a canvas overlay's pixel-exact frame layout. */
    public interface Overlay {
        RectF bounds(View child);
        void drawStock(Canvas canvas);
        default void drawAfterFrames(Canvas canvas) {}
        default void drawDrop(Canvas canvas, int index) {}
    }
    private Overlay overlay;
    public void setOverlay(Overlay value) {
        overlay = value;
        frames.overlay = value;
        setWillNotDraw(value == null);
        setClipChildren(value == null);
        frames.setClipChildren(value == null);
        requestLayout(); invalidate();
    }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (overlay != null) overlay.drawStock(canvas);
    }
    @Override protected void onMeasure(int w, int h) {
        if (overlay == null) { super.onMeasure(w, h); return; }
        setMeasuredDimension(MeasureSpec.getSize(w), MeasureSpec.getSize(h));
        frames.measure(MeasureSpec.makeMeasureSpec(getMeasuredWidth(), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(getMeasuredHeight(), MeasureSpec.EXACTLY));
    }
    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        if (overlay == null) { super.onLayout(changed, l, t, r, b); return; }
        frames.layout(0, 0, r - l, b - t);
    }
    private final DropRow frames;
    public FilmStrip(Context c) {
        this(c, Style.SOLID);
    }
    public FilmStrip(Context c, Style style) {
        super(c);
        setOrientation(VERTICAL);
        if (style == Style.SOLID) setBackgroundColor(Studio.PANEL);
        float d = c.getResources().getDisplayMetrics().density;
        if (style == Style.SOLID)
            addView(new Perf(c), new LayoutParams(LayoutParams.MATCH_PARENT, (int) (9 * d)));
        frames = new DropRow(c);
        frames.setOrientation(HORIZONTAL);
        int pad = (int) (3 * d);
        if (style == Style.SOLID) frames.setPadding(pad, pad, pad, pad);
        addView(frames);
        if (style == Style.SOLID)
            addView(new Perf(c), new LayoutParams(LayoutParams.MATCH_PARENT, (int) (9 * d)));
    }
    public DropRow frames() { return frames; }

    /**
     * The row of frames, which also draws where a dragged one would land.
     *
     * <p>A lifted chip with no drop line tells you something is moving but not where it is
     * going, and on a roll of twenty frames that is the only question you have.</p>
     */
    public static class DropRow extends LinearLayout {
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float d;
        private int dropAt = -1;
        private Overlay overlay;
        /** A board can match its adaptive ink; SpriteLab keeps Studio.LIVE by default. */
        public void setDropColor(int color) { line.setColor(color); invalidate(); }
        @Override protected void onMeasure(int w, int h) {
            if (overlay == null) { super.onMeasure(w, h); return; }
            setMeasuredDimension(MeasureSpec.getSize(w), MeasureSpec.getSize(h));
            for (int i = 0; i < getChildCount(); i++) {
                View child = getChildAt(i);
                RectF rect = overlay.bounds(child);
                child.measure(MeasureSpec.makeMeasureSpec(Math.max(0, Math.round(rect.width())), MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(Math.max(0, Math.round(rect.height())), MeasureSpec.EXACTLY));
            }
        }
        @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
            if (overlay == null) { super.onLayout(changed, l, t, r, b); return; }
            for (int i = 0; i < getChildCount(); i++) {
                View child = getChildAt(i);
                RectF rect = overlay.bounds(child);
                child.layout(Math.round(rect.left), Math.round(rect.top), Math.round(rect.right), Math.round(rect.bottom));
            }
        }

        DropRow(Context c) {
            super(c);
            d = c.getResources().getDisplayMetrics().density;
            line.setColor(Studio.LIVE);
            setWillNotDraw(false);
        }

        public void setDropAt(int index) {
            if (dropAt == index) return;
            dropAt = index;
            invalidate();
        }

        @Override protected void dispatchDraw(Canvas canvas) {
            super.dispatchDraw(canvas);
            if (overlay != null) overlay.drawAfterFrames(canvas);
            if (dropAt < 0) return;
            if (overlay != null) { overlay.drawDrop(canvas, dropAt); return; }
            float x;
            if (dropAt >= getChildCount()) {
                View last = getChildCount() == 0 ? null : getChildAt(getChildCount() - 1);
                x = last == null ? getPaddingLeft() : last.getRight() + 2 * d;
            } else {
                x = getChildAt(dropAt).getLeft() - 2 * d;
            }
            canvas.drawRoundRect(x - 1.5f * d, getPaddingTop(), x + 1.5f * d,
                    getHeight() - getPaddingBottom(), 1.5f * d, 1.5f * d, line);
        }
    }

    private static class Perf extends View {
        private final Paint hole = new Paint();
        private final float d;
        Perf(Context c) {
            super(c);
            d = c.getResources().getDisplayMetrics().density;
            hole.setColor(Studio.GROUND);
        }
        @Override protected void onDraw(Canvas canvas) {
            float pitch = 13 * d, w = 6 * d;
            float top = getHeight() * 0.25f, bot = getHeight() * 0.75f;
            for (float x = 4 * d; x < getWidth(); x += pitch) {
                canvas.drawRoundRect(new RectF(x, top, x + w, bot), 1.5f * d, 1.5f * d, hole);
            }
        }
    }
}
