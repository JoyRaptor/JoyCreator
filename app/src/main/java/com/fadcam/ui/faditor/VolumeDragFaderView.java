package com.fadcam.ui.faditor;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

/**
 * Transient vertical fader shown in the centre HUD while the user drags the Volume tool.
 *
 * <p>It visualises the audio volume on a 0–200% vertical track: a green fill from the bottom,
 * a thumb at the current level, a faint 100% groove in the middle, and small end ticks. The
 * point is to show that a short physical drag maps to the full 0–200% range — the host
 * translates this view to follow the finger so it reads as a fader you're holding.</p>
 */
public class VolumeDragFaderView extends View {

    private float volume = 1.0f; // 0..2

    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint groovePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;

    public VolumeDragFaderView(Context c) { this(c, null); }

    public VolumeDragFaderView(Context c, @Nullable AttributeSet a) {
        super(c, a);
        density = getResources().getDisplayMetrics().density;
        trackPaint.setColor(0x66000000);
        fillPaint.setColor(Studio.GO);   // green
        thumbPaint.setColor(Studio.INK);
        groovePaint.setColor(0x66FFFFFF);
        groovePaint.setStrokeWidth(1.5f * density);
    }

    /** Set the volume (0–2) and redraw. */
    public void setVolume(float v) {
        this.volume = Math.max(0f, Math.min(v, 2.0f));
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth();
        float h = getHeight();
        float trackW = w * 0.55f;
        float left = (w - trackW) / 2f;
        float radius = trackW / 2f;

        // Track background
        canvas.drawRoundRect(new RectF(left, 0, left + trackW, h), radius, radius, trackPaint);

        // Fill from bottom proportional to volume/2
        float frac = volume / 2.0f;
        float fillTop = h - frac * h;
        canvas.drawRoundRect(new RectF(left, Math.max(0, fillTop), left + trackW, h),
                radius, radius, fillPaint);

        // 100% groove line (centre)
        float midY = h * 0.5f;
        canvas.drawLine(left - 3f * density, midY, left + trackW + 3f * density, midY, groovePaint);

        // Thumb at the current level
        float thumbY = Math.max(radius, Math.min(h - radius, fillTop));
        canvas.drawCircle(w / 2f, thumbY, trackW * 0.62f, thumbPaint);
        fillPaint.setColor(Studio.GO);
        canvas.drawCircle(w / 2f, thumbY, trackW * 0.34f, fillPaint);
        fillPaint.setColor(Studio.GO);
    }
}

