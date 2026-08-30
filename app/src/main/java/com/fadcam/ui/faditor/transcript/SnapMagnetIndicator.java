package com.fadcam.ui.faditor.transcript;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

/**
 * Word Sync v2 (JoyRaptor, 2026-08-30): a small horseshoe magnet beside the shuttle that
 * explains SNAP instead of hiding it. Dark gray while idle; turns WHITE the moment the
 * word's current desired position is inside a snapping zone — so when a word refuses to
 * move, the white magnet says "you're on the magnet" instead of the gesture feeling dead.
 *
 * <p>Pure indicator — it never touches snap logic. The activity calls
 * {@link #setSnapping(boolean)} with the same tolerance the snap actually honors
 * ({@link WordSyncMode#SNAP_STICKINESS}-scaled), so the lit state is exactly the zone
 * where the next snap would grab the word.</p>
 */
public class SnapMagnetIndicator extends View {

    private boolean snapping = false;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public SnapMagnetIndicator(Context context) { this(context, null); }

    public SnapMagnetIndicator(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.BUTT);
    }

    /** Idle dark gray / lit white. Cheap: repaint only on state flips. */
    public void setSnapping(boolean s) {
        if (snapping == s) return;
        snapping = s;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        float d = getResources().getDisplayMetrics().density;

        paint.setColor(snapping ? 0xFFFFFFFF : 0xFF6A6A6A);

        // Geometry for a ~20dp box: a "U" (bottom half-arc, opens upward) with pole caps on
        // the two top ends. The FIRST version computed radius (3.6dp) smaller than the stroke
        // (4dp) and its guard silently returned — the magnet never drew at all (JoyRaptor:
        // "I'm not seeing any magnet"). New math: radius is width-driven and the guard
        // shrinks the stroke instead of ever bailing.
        float stroke = 3f * d;
        float capH = 4f * d;
        float capOverhang = 0.75f * d;
        float margin = 1f * d;
        float cx = w / 2f;
        float radius = w / 2f - stroke / 2f - margin;
        if (radius < stroke) {
            radius = stroke;
            stroke = radius;
        }
        paint.setStrokeWidth(stroke);
        float cy = (h - radius - stroke / 2f - capH) / 2f + capH; // vertically centered block

        // Bottom half-arc: 0° is the right end at cy, sweeping 180° clockwise (screen
        // y-down) through the bottom to the left end.
        canvas.drawArc(cx - radius, cy - radius, cx + radius, cy + radius,
                0f, 180f, false, paint);

        // Pole caps at the two ends, a touch wider than the stroke — the iconic look.
        paint.setStyle(Paint.Style.FILL);
        canvas.drawRect(cx - radius - stroke / 2f - capOverhang, cy - capH,
                cx - radius + stroke / 2f + capOverhang, cy, paint);
        canvas.drawRect(cx + radius - stroke / 2f - capOverhang, cy - capH,
                cx + radius + stroke / 2f + capOverhang, cy, paint);
        paint.setStyle(Paint.Style.STROKE);
    }
}
