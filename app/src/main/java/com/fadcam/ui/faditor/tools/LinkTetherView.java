package com.fadcam.ui.faditor.tools;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.View;

import androidx.annotation.NonNull;

import com.fadcam.ui.faditor.Studio;

/**
 * Shows links in the picture (SPEC_20260924_LINKING §12). Draws, and never takes a touch:
 * <ul>
 *   <li>TETHERS — a thin line from a parent's centre to each follower's, with arrows travelling
 *       parent → child ("parent-child relationships get animated arrows travelling one
 *       direction"). Shown for the selected object's links.</li>
 *   <li>CAN-LINK MARKS — while the link button is armed, a soft ring around every object that
 *       can take the link, so targets are "hard to miss".</li>
 * </ul>
 * The host fills the lists each frame it changes and calls {@link #invalidate()}.
 */
public final class LinkTetherView extends View {

    /** One tether, in this view's pixels. */
    public static final class Tether {
        public final float px, py, cx, cy;
        public Tether(float px, float py, float cx, float cy) {
            this.px = px; this.py = py; this.cx = cx; this.cy = cy;
        }
    }

    private final java.util.List<Tether> tethers = new java.util.ArrayList<>();
    private final java.util.List<RectF> targets = new java.util.ArrayList<>();
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arrow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final float d;
    private int colour = Studio.ARMED;

    public LinkTetherView(@NonNull Context ctx) {
        super(ctx);
        d = getResources().getDisplayMetrics().density;
        setWillNotDraw(false);
        setClickable(false);
        setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(1.5f * d);
        arrow.setStyle(Paint.Style.FILL);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(2f * d);
        ring.setPathEffect(new DashPathEffect(new float[]{6 * d, 5 * d}, 0));
    }

    /** The colour of this relationship (the object's own colour). */
    public void setColour(int c) { colour = c; }

    public void setTethers(@NonNull java.util.List<Tether> list) {
        tethers.clear();
        tethers.addAll(list);
        invalidate();
    }

    public void setTargets(@NonNull java.util.List<RectF> list) {
        targets.clear();
        targets.addAll(list);
        invalidate();
    }

    public boolean isEmpty() { return tethers.isEmpty() && targets.isEmpty(); }

    @Override
    protected void onDraw(@NonNull Canvas c) {
        if (tethers.isEmpty() && targets.isEmpty()) return;
        long now = SystemClock.uptimeMillis();
        // The marks breathe, so an armed tool reads as waiting for a choice.
        float breathe = 0.55f + 0.45f * (float) Math.abs(Math.sin(now / 420.0));
        ring.setColor(Studio.alpha(colour, Math.round(0xFF * breathe)));
        for (RectF r : targets) {
            float pad = 6f * d;
            c.drawRoundRect(r.left - pad, r.top - pad, r.right + pad, r.bottom + pad,
                    10f * d, 10f * d, ring);
        }
        line.setColor(Studio.alpha(colour, 0xB0));
        arrow.setColor(colour);
        for (Tether t : tethers) {
            c.drawLine(t.px, t.py, t.cx, t.cy, line);
            float dx = t.cx - t.px, dy = t.cy - t.py;
            float len = (float) Math.hypot(dx, dy);
            if (len < 24f * d) continue;
            float ux = dx / len, uy = dy / len;
            // Chevrons 28dp apart, flowing parent → child at 60dp a second.
            float spacing = 28f * d;
            float phase = ((now / 1000f) * 60f * d) % spacing;
            for (float s = phase; s < len - 8f * d; s += spacing) {
                float ax = t.px + ux * s, ay = t.py + uy * s;
                float h = 5f * d;
                path.reset();
                path.moveTo(ax + ux * h, ay + uy * h);
                path.lineTo(ax - uy * h * 0.8f, ay + ux * h * 0.8f);
                path.lineTo(ax + uy * h * 0.8f, ay - ux * h * 0.8f);
                path.close();
                c.drawPath(path, arrow);
            }
        }
        postInvalidateOnAnimation();
    }
}
