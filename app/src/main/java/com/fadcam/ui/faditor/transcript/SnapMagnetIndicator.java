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

        // Horseshoe: bottom half-arc (opens upward), thick stroke.
        float stroke = 4f * d;
        paint.setStrokeWidth(stroke);
        float cx = w / 2f;
        float cy = h * 0.44f;
        float radius = Math.min(w, h * 0.56f) / 2f - stroke / 2f;
        if (radius < stroke) return;
        canvas.drawArc(cx - radius, cy - radius, cx + radius, cy + radius,
                180f, 180f, false, paint);

        // Pole caps at the two ends (slightly wider than the stroke — the iconic look).
        float capH = 4.5f * d;
        float capOverhang = 1f * d;
        paint.setStyle(Paint.Style.FILL);
        canvas.drawRect(cx - radius - stroke / 2f - capOverhang, cy - capH,
                cx - radius + stroke / 2f + capOverhang, cy, paint);
        canvas.drawRect(cx + radius - stroke / 2f - capOverhang, cy - capH,
                cx + radius + stroke / 2f + capOverhang, cy, paint);
        paint.setStyle(Paint.Style.STROKE);
    }
}
