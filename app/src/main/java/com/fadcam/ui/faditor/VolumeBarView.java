package com.fadcam.ui.faditor;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;

/**
 * Horizontal volume bar (0–200%) for the volume top-drawer. Drag the marker (or tap) to set
 * the volume; the host writes a keyframe (when armed) or sets the whole-clip volume.
 *
 * <p>As the playhead scrubs, the host calls {@link #setVolume(float)} so the bar tracks the gain
 * at the playhead. When the playhead sits exactly on a keyframe, the host calls
 * {@link #setOnKeyframe(boolean)} and the round marker becomes a <b>diamond</b> — signalling that
 * dragging edits that keyframe rather than creating a new one.</p>
 */
public class VolumeBarView extends View {

    public interface Listener {
        /** User dragged/tapped the bar to this volume (0–2). */
        void onVolumeSet(float volume);
        /** Drag finished (commit / autosave). */
        default void onVolumeCommitted() {}
    }

    private float volume = 1.0f; // 0..2
    private boolean onKeyframe;
    @Nullable private Listener listener;

    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint markerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tickPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path diamond = new Path();
    private final float density;

    public VolumeBarView(Context c) { this(c, null); }

    public VolumeBarView(Context c, @Nullable AttributeSet a) {
        super(c, a);
        density = getResources().getDisplayMetrics().density;
        trackPaint.setColor(Studio.OFF);
        fillPaint.setColor(Studio.GO);
        markerPaint.setColor(Studio.INK);
        tickPaint.setColor(0x88FFFFFF);
        tickPaint.setStrokeWidth(1.5f * density);
    }

    public void setListener(@Nullable Listener l) { this.listener = l; }

    public void setVolume(float v) {
        this.volume = Math.max(0f, Math.min(v, 2.0f));
        updateColor();
        invalidate();
    }

    public float getVolume() { return volume; }

    public void setOnKeyframe(boolean on) {
        if (this.onKeyframe != on) {
            this.onKeyframe = on;
            invalidate();
        }
    }

    private void updateColor() {
        fillPaint.setColor(volume > 1.01f ? Studio.DANGER : Studio.GO);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth();
        float h = getHeight();
        float cy = h / 2f;
        float trackH = 6f * density;
        float pad = 16f * density; // marker radius room
        float left = pad;
        float right = w - pad;
        float span = right - left;

        // Track
        canvas.drawRoundRect(new RectF(left, cy - trackH / 2f, right, cy + trackH / 2f),
                trackH / 2f, trackH / 2f, trackPaint);

        float x = left + (volume / 2.0f) * span;

        // Fill up to the marker
        canvas.drawRoundRect(new RectF(left, cy - trackH / 2f, x, cy + trackH / 2f),
                trackH / 2f, trackH / 2f, fillPaint);

        // 100% tick (centre)
        float midX = left + span * 0.5f;
        canvas.drawLine(midX, cy - 10f * density, midX, cy + 10f * density, tickPaint);

        // Marker: diamond when sitting on a keyframe, else a round knob.
        float r = 11f * density;
        if (onKeyframe) {
            // Diamond = "you're ON a keyframe; dragging edits it".
            float rr = r * 1.15f;
            diamond.reset();
            diamond.moveTo(x, cy - rr);
            diamond.lineTo(x + rr, cy);
            diamond.lineTo(x, cy + rr);
            diamond.lineTo(x - rr, cy);
            diamond.close();
            canvas.drawPath(diamond, markerPaint); // white body
            int saved = fillPaint.getColor();
            fillPaint.setColor(volume > 1.01f ? Studio.DANGER : Studio.GO);
            float ir = rr * 0.5f;
            Path inner = new Path();
            inner.moveTo(x, cy - ir);
            inner.lineTo(x + ir, cy);
            inner.lineTo(x, cy + ir);
            inner.lineTo(x - ir, cy);
            inner.close();
            canvas.drawPath(inner, fillPaint);
            fillPaint.setColor(saved);
        } else {
            canvas.drawCircle(x, cy, r, markerPaint);
            float ir = r * 0.5f;
            int saved = fillPaint.getColor();
            fillPaint.setColor(volume > 1.01f ? Studio.DANGER : Studio.GO);
            canvas.drawCircle(x, cy, ir, fillPaint);
            fillPaint.setColor(saved);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        float pad = 16f * density;
        float left = pad;
        float span = getWidth() - 2 * pad;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_MOVE: {
                getParent().requestDisallowInterceptTouchEvent(true);
                float frac = Math.max(0f, Math.min(1f, (e.getX() - left) / Math.max(1f, span)));
                setVolume(frac * 2.0f);
                if (listener != null) listener.onVolumeSet(volume);
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                getParent().requestDisallowInterceptTouchEvent(false);
                if (listener != null) listener.onVolumeCommitted();
                return true;
        }
        return super.onTouchEvent(e);
    }
}


