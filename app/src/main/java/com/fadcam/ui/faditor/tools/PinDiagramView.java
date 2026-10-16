package com.fadcam.ui.faditor.tools;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.ui.faditor.Studio;
import com.fadcam.ui.type.Type;

/**
 * A SMALL MAP OF A PICTURE'S PINS, for the "Follow what?" picker.
 *
 * <p>The owner could not tell which pin was which in a plain list of "Pin 1 ... Pin 9". This
 * draws the picture's cell as a rounded rectangle (its real proportions) and puts each pin on
 * it as a numbered dot at its REST position, so "Pin 3" is the dot marked 3, on the hand where
 * it really is.
 *
 * <p>One highlighted thing at a time, shared with the list under it: {@link #HIGHLIGHT_WHOLE}
 * outlines the whole cell ("the picture itself"), a pin index lights that dot in
 * {@link Studio#ARMED}, {@link #HIGHLIGHT_NONE} lights nothing. Touching, hovering (stylus or
 * mouse) or focusing a dot reports through {@link Listener#onHighlight}; lifting a finger on a
 * dot picks it. Each dot has a 44dp touch area even though it is drawn smaller; where dots
 * overlap, the nearest centre wins.
 */
public final class PinDiagramView extends View {

    /** Nothing is lit. */
    public static final int HIGHLIGHT_NONE = -2;
    /** The whole cell is lit ("the picture itself"). */
    public static final int HIGHLIGHT_WHOLE = -1;

    public interface Listener {
        /** A dot was tapped: choose this pin (0-based). */
        void onPick(int pin);

        /** A dot is being touched or hovered, or let go ({@link #HIGHLIGHT_NONE}). */
        void onHighlight(int highlight);
    }

    private static final float DOT_DP = 12f;       // drawn radius
    private static final float TOUCH_DP = 22f;     // 44dp touch area
    private static final float PAD_DP = 20f;       // room so an edge dot is never clipped

    private float[] restX = new float[0];
    private float[] restY = new float[0];
    private float cellAspect = 1f;
    private int highlight = HIGHLIGHT_NONE;
    @Nullable private Listener listener;

    private final RectF cell = new RectF();
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint num = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float d;

    public PinDiagramView(@NonNull Context ctx) {
        super(ctx);
        d = ctx.getResources().getDisplayMetrics().density;
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(Studio.SUNK);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(Math.max(1f, d));
        grid.setStyle(Paint.Style.STROKE);
        grid.setStrokeWidth(Math.max(1f, d));
        grid.setColor(Studio.alpha(Studio.LINE, 0x99));
        num.setTextAlign(Paint.Align.CENTER);
        num.setTextSize(12f * d);
        num.setTypeface(Type.body(ctx, Type.BOLD));
        setClickable(true);
        setFocusable(false);
    }

    /** Pin rest positions in the picture's unit square (top-left origin). */
    public void setPins(@NonNull float[] x, @NonNull float[] y, float aspect) {
        restX = x;
        restY = y;
        cellAspect = aspect > 0.05f && aspect < 20f ? aspect : 1f;
        requestLayout();
        invalidate();
    }

    public void setListener(@Nullable Listener l) { listener = l; }

    /** Light a dot, the whole cell, or nothing. Does not call the listener. */
    public void setHighlight(int h) {
        if (h == highlight) return;
        highlight = h;
        invalidate();
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec);
        int h = MeasureSpec.getSize(hSpec);
        // Square, as large as asked; the host picks the size with its layout params.
        int s = Math.min(w > 0 ? w : Integer.MAX_VALUE, h > 0 ? h : Integer.MAX_VALUE);
        if (s == Integer.MAX_VALUE) s = Math.round(200f * d);
        setMeasuredDimension(MeasureSpec.getMode(wSpec) == MeasureSpec.UNSPECIFIED ? s : w,
                MeasureSpec.getMode(hSpec) == MeasureSpec.UNSPECIFIED ? s : h);
    }

    private void layoutCell() {
        float pad = PAD_DP * d;
        float aw = Math.max(1f, getWidth() - 2 * pad);
        float ah = Math.max(1f, getHeight() - 2 * pad);
        float cw, ch;
        if (aw / ah > cellAspect) { ch = ah; cw = ah * cellAspect; } else { cw = aw; ch = aw / cellAspect; }
        float l = (getWidth() - cw) / 2f, t = (getHeight() - ch) / 2f;
        cell.set(l, t, l + cw, t + ch);
    }

    private float px(int i) { return cell.left + restX[i] * cell.width(); }

    private float py(int i) { return cell.top + restY[i] * cell.height(); }

    @Override
    protected void onDraw(@NonNull Canvas c) {
        layoutCell();
        float r = 14f * d;
        c.drawRoundRect(cell, r, r, fill);
        // Faint thirds, so a dot has something to be "left of" or "near the top" against.
        for (int k = 1; k <= 2; k++) {
            float gx = cell.left + cell.width() * k / 3f, gy = cell.top + cell.height() * k / 3f;
            c.drawLine(gx, cell.top + r * 0.5f, gx, cell.bottom - r * 0.5f, grid);
            c.drawLine(cell.left + r * 0.5f, gy, cell.right - r * 0.5f, gy, grid);
        }
        boolean whole = highlight == HIGHLIGHT_WHOLE;
        stroke.setColor(whole ? Studio.ARMED : Studio.LINE);
        stroke.setStrokeWidth(whole ? 2f * d : Math.max(1f, d));
        c.drawRoundRect(cell, r, r, stroke);

        float dr = DOT_DP * d;
        for (int i = 0; i < restX.length; i++) {
            if (i != highlight) drawDot(c, i, dr, false);
        }
        if (highlight >= 0 && highlight < restX.length) drawDot(c, highlight, dr * 1.15f, true);
    }

    private void drawDot(Canvas c, int i, float r, boolean on) {
        float x = px(i), y = py(i);
        dot.setStyle(Paint.Style.FILL);
        dot.setColor(on ? Studio.ARMED : Studio.RAISED);
        c.drawCircle(x, y, r, dot);
        dot.setStyle(Paint.Style.STROKE);
        dot.setStrokeWidth(Math.max(1f, on ? 2f * d : d));
        dot.setColor(on ? Studio.ARMED_LIGHT : Studio.INK_FAINT);
        c.drawCircle(x, y, r, dot);
        num.setColor(on ? Studio.ON_GO : Studio.INK);
        float base = y - (num.ascent() + num.descent()) / 2f;
        c.drawText(String.valueOf(i + 1), x, base, num);
    }

    /** The dot whose 44dp touch area holds (x, y), nearest centre first; -1 for none. */
    private int hit(float x, float y) {
        layoutCell();
        int best = -1;
        float bestD = TOUCH_DP * d * TOUCH_DP * d;
        for (int i = 0; i < restX.length; i++) {
            float dx = px(i) - x, dy = py(i) - y;
            float dd = dx * dx + dy * dy;
            if (dd <= bestD) { bestD = dd; best = i; }
        }
        return best;
    }

    private void light(int pin) {
        int h = pin < 0 ? HIGHLIGHT_NONE : pin;
        if (h == highlight) return;
        highlight = h;
        invalidate();
        if (listener != null) listener.onHighlight(h);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_MOVE:
                light(hit(e.getX(), e.getY()));
                return true;
            case MotionEvent.ACTION_UP: {
                int pin = hit(e.getX(), e.getY());
                light(-1);
                if (pin >= 0) {
                    performClick();
                    if (listener != null) listener.onPick(pin);
                }
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                light(-1);
                return true;
            default:
                return super.onTouchEvent(e);
        }
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    /** Stylus or mouse hover lights the dot under it, like a touch without the commitment. */
    @Override
    public boolean onHoverEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_HOVER_ENTER:
            case MotionEvent.ACTION_HOVER_MOVE:
                light(hit(e.getX(), e.getY()));
                return true;
            case MotionEvent.ACTION_HOVER_EXIT:
                light(-1);
                return true;
            default:
                return super.onHoverEvent(e);
        }
    }
}
