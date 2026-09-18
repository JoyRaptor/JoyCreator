package com.fadcam.ui.faditor.tools;

import com.fadcam.ui.faditor.Studio;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.NonNull;

/**
 * SPEC F: the rotation dial — the compact turn-rings control that replaces the Rotate
 * SLIDER on every row whose {@code Prop} key is {@link
 * com.fadcam.ui.faditor.keyframe.KeyframeSet#ROTATION}. A slider cannot represent
 * winding: it tops out, and touching it folds a typed 720 back into its window (the F1
 * hazard in SPEC A's delivery record). The dial can: the value behind it is the raw
 * stored degrees, and the drawing SHOWS the winding —
 *
 * <ul>
 *   <li>completed turns are thin rings stacking inward at a fixed pitch (line width +
 *       small gap), countable until they physically reach the value hub (~5 rings at
 *       40dp);</li>
 *   <li>turns that no longer fit grow a solid core FROM THE CENTRE OUTWARD, born at
 *       half the hub-fitting radius (JoyRaptor, 2026-09-05: "make the center plug start
 *       fifty percent smaller") — never a full-face plug at a threshold;</li>
 *   <li>the current partial turn is the bright arc on the rim;</li>
 *   <li>a notch at the top marks 0°/360°, so 180° reads as straight down at ANY turn
 *       multiple — the directionality JoyRaptor chose the rings for over the spiral;</li>
 *   <li>green = positive winding, reddish-pink = negative.</li>
 * </ul>
 *
 * <p>The view owns only look + gesture. Storage, keyframes, undo and the typing
 * grammar stay exactly where SPEC A put them: the caller writes {@link #getDegrees()}
 * through the same {@code Prop.write} the slider used, and a tap (no drag) asks the
 * caller to open the tap-to-type prompt — the dial never clamps, never folds, and
 * never writes by itself.</p>
 */
public class RotationDialView extends View {

    /** Green for positive winding, pink for negative (JoyRaptor's colour language). */
    private static final int POS_COL = Studio.GO;
    private static final int NEG_COL = Studio.DANGER;
    private static final int BASE_COL = Studio.OFF;
    private static final int NOTCH_COL = Studio.INK_FAINT;
    private static final int PLATE_COL = 0x8C000000;
    private static final int TEXT_COL = Studio.INK;

    /** Gesture callbacks. {@link #onDragDelta} fires AFTER the view has applied the
     *  delta to its own value — read {@link #getDegrees()} and write it through. */
    public interface Listener {
        void onDragStart();
        void onDragDelta();
        void onDragEnd();
        void onTap();
    }

    private float degrees;                 // RAW winding — never clamped, never folded
    private Listener listener;

    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path notch = new Path();

    private final float density = getResources().getDisplayMetrics().density;

    // Touch state
    private boolean dragging;
    private float downAngle;
    private float lastAngle;
    private long downAt;
    private float accumulated;

    public RotationDialView(@NonNull Context context) {
        super(context);
        stroke.setStyle(Paint.Style.STROKE);
        fill.setStyle(Paint.Style.FILL);
        text.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        text.setColor(TEXT_COL);
        text.setTextAlign(Paint.Align.CENTER);
    }

    public void setListener(@NonNull Listener l) { listener = l; }

    /** The raw winding this dial displays and reports. Unclamped by design. */
    public float getDegrees() { return degrees; }

    /** Set the displayed winding (raw). Called by the row's refresher — the model is
     *  the single source of truth; the dial is a view of it. */
    public void setDegrees(float raw) {
        degrees = raw;
        invalidate();
    }

    // ── drawing ─────────────────────────────────────────────────────────────────────

    @Override protected void onDraw(@NonNull Canvas canvas) {
        final float size = Math.min(getWidth(), getHeight());
        if (size <= 0) return;
        final float c = size / 2f;
        final float R0 = size / 2f - 2.5f * density;
        final boolean positive = degrees >= 0f;
        final int col = positive ? POS_COL : NEG_COL;
        final float lw = Math.max(1.3f * density, size / 22f);
        final float gap = Math.max(0.8f * density, size / 40f);
        final float step = lw + gap;
        final float hubR = (size > 60 ? size * 0.11f : size * 0.18f);
        final float innerLimit = hubR + lw / 2f;
        final float turns = Math.abs(degrees) / 360f;
        final int total = (int) turns;
        final float rem = turns - total;

        // Base ring (the track the eye uses for the notch)
        stroke.setColor(BASE_COL);
        stroke.setStrokeWidth(density);
        canvas.drawCircle(c, c, R0, stroke);

        // Countable rings: fixed pitch from the rim inward, until geometry runs out
        final int countable = (int) Math.floor((R0 - innerLimit) / step);
        final int shown = Math.min(total, countable);
        for (int k = 0; k < shown; k++) {
            stroke.setColor(col);
            stroke.setStrokeWidth(lw);
            stroke.setAlpha(Math.max(56, Math.round(128 - k * 15)));
            canvas.drawCircle(c, c, R0 - k * step, stroke);
        }
        stroke.setAlpha(255);

        // Turns that no longer fit: a solid core born small at the centre, growing
        // outward one pitch per excess turn (never a full-face plug at a threshold)
        final int excess = total - shown;
        if (excess > 0) {
            final float rPlug = Math.min(R0, (innerLimit + step) * 0.5f + (excess - 1) * step);
            fill.setColor(col);
            fill.setAlpha(Math.min(217, Math.round(115 + excess * 31)));
            canvas.drawCircle(c, c, rPlug, fill);
            fill.setAlpha(255);
        }

        // The current partial turn: bright arc on the rim, from the notch, in the
        // winding's direction — this is what keeps 180° pointing straight down.
        if (rem > 0.001f) {
            final float sweep = rem * 360f;
            final float start = -90f;
            stroke.setColor(col);
            stroke.setStrokeWidth(lw * 1.15f);
            stroke.setAlpha(230);
            canvas.drawArc(c - R0, c - R0, c + R0, c + R0,
                    positive ? start : start - sweep, sweep, false, stroke);
            stroke.setAlpha(255);
        }

        // Notch at the top (0°/360°)
        fill.setColor(NOTCH_COL);
        notch.reset();
        notch.moveTo(c, c - R0 - 2f * density);
        notch.lineTo(c - 1.6f * density, c - R0 - 5f * density);
        notch.lineTo(c + 1.6f * density, c - R0 - 5f * density);
        notch.close();
        canvas.drawPath(notch, fill);

        // Hub: the raw value on a dark plate
        final String hub = hubText(degrees);
        text.setTextSize(Math.max(8f, size * 0.23f));
        final float tw = text.measureText(hub);
        final float ph = text.getTextSize() + 3f * density;
        fill.setColor(PLATE_COL);
        canvas.drawRect(c - tw / 2f - 1.5f * density, c - ph / 2f,
                c + tw / 2f + 1.5f * density, c + ph / 2f, fill);
        canvas.drawText(hub, c, c + text.getTextSize() * 0.35f, text);
    }

    /** Compact hub readout: "720", "-45", "5.8k" — the row's full readout lives beside. */
    @NonNull private static String hubText(float v) {
        float a = Math.abs(v);
        if (a >= 10000f) return String.format(java.util.Locale.US, "%.1fk", v / 1000f);
        float r = Math.round(v);
        if (Math.abs(v - r) < 0.005f) return String.valueOf((int) r);
        return String.format(java.util.Locale.US, "%.1f", v);
    }

    // ── touch: drag = wind, tap = type ──────────────────────────────────────────────

    @Override public boolean onTouchEvent(@NonNull MotionEvent e) {
        final float x = e.getX() - getWidth() / 2f;
        final float y = e.getY() - getHeight() / 2f;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downAngle = angleOf(x, y);
                lastAngle = downAngle;
                dragging = false;
                accumulated = 0f;
                downAt = android.view.animation.AnimationUtils.currentAnimationTimeMillis();
                disallowUpstream(true);
                return true;
            case MotionEvent.ACTION_MOVE: {
                final float a = angleOf(x, y);
                float delta = a - lastAngle;
                if (delta > 180f) delta -= 360f;
                if (delta < -180f) delta += 360f;
                accumulated += delta;
                lastAngle = a;
                final float slop = 6f * density;
                if (!dragging && Math.abs(accumulated) > slop) {
                    dragging = true;
                    if (listener != null) listener.onDragStart();
                }
                if (dragging && Math.abs(delta) > 0.05f) {
                    degrees += delta;                 // raw winding; the POINTER unwraps, not the value
                    invalidate();
                    if (listener != null) listener.onDragDelta();
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                disallowUpstream(false);
                final boolean wasTap = !dragging
                        && android.view.animation.AnimationUtils.currentAnimationTimeMillis() - downAt < 400L;
                final boolean wasDragging = dragging;
                dragging = false;
                if (listener == null) return true;
                if (wasDragging) listener.onDragEnd();
                else if (wasTap) listener.onTap();
                return true;
            }
            default:
                return super.onTouchEvent(e);
        }
    }

    /** Ask upstream scrollers to leave this gesture alone while the finger is down. */
    private void disallowUpstream(boolean disallow) {
        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(disallow);
    }

    private static float angleOf(float x, float y) {
        return (float) Math.toDegrees(Math.atan2(y, x));
    }
}
