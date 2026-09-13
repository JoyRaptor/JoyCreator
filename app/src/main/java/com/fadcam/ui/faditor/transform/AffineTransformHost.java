package com.fadcam.ui.faditor.transform;

import android.graphics.RectF;

import androidx.annotation.NonNull;

import com.fadcam.ui.faditor.overlay.PreviewHandlesOverlay;

/**
 * THE affine transform host. One implementation, for every object that can be moved, scaled and
 * rotated but not distorted.
 *
 * <h3>Why this exists — SPEC Y, stage 1</h3>
 * <p>JoyRaptor, 2026-09-13: <i>"Make sure that the transform tool and all the holders and behavior
 * being developed is uniform across the board and all referencing the same things. So if I need to
 * change something about the transform tool, that'll happen to all of them uniformly instead of
 * having everything hard coded per situation."</i>
 *
 * <p>This class is the first half of that answer. It replaces {@code TextAffineTransformHost} and
 * {@code PipAffineTransformHost}, which were 242 and 217 lines and — as a side-by-side read on
 * 2026-09-13 established — the SAME ALGORITHM twice: identical {@code readQuad}, {@code readPivot},
 * {@code writeTranslate}, {@code writeRotation}, {@code writeSimilarity}, {@code commitGesture} and
 * {@code writeQuad}, differing only in comment volume and in one redundant field. Two copies of one
 * behaviour is how two behaviours start, and the comparison found them already drifting: one host
 * carried dead locals and a thinking-out-loud comment shipped as code, the other silently reset a
 * property its sibling deliberately preserved.
 *
 * <h3>What a type is allowed to differ about</h3>
 * <p>Exactly two things, and both are data rather than code:
 * <ul>
 *   <li>{@link #minSizeFraction} — the floor a scale may not go below. It was 0.02 in both affine
 *       hosts and 0.01 in the image host, so a naive collapse to "one constant" would silently
 *       halve or double somebody's floor. It is a parameter for that reason, not a constant.</li>
 *   <li>{@link ResetPolicy} — what "reset geometry" means. Text keeps its size (authored work with
 *       no canonical default); a PiP returns to its creation pose. That is a real product decision
 *       per type and the only genuinely different body in either class.</li>
 * </ul>
 *
 * <p>Everything else — the quad decomposition, the affine validation, the write ORDER (size and
 * angle first, position last), the position clamp, the winding normalisation, the
 * {@code onChanged} contract — lives here once. A change to any of it is now a change to all of
 * them, which is the whole request.
 *
 * <h3>What it deliberately refuses</h3>
 * <p>{@link #writeQuad} accepts only a rotated, uniformly-scaled rectangle and returns false for
 * anything sheared, trapezoidal or folded. Neither text nor PiP has a pinned render path in the
 * preview OR the export, and authoring a distortion no renderer can draw would look like it worked
 * in the handles and vanish in the file. The surface is additionally put in {@code affineOnly} mode
 * by the activity so the role ring never offers Tilt or Free — refusing here is the safety net,
 * not the interface.
 */
public final class AffineTransformHost implements TransformOverlayView.Host {

    /** The clock this object's poses are expressed in. */
    public interface Playhead { long timelineMs(); }

    /**
     * The one genuinely per-type body. Implementations must end by notifying, the same way every
     * write path here does — a reset moves the object and the picture has to follow.
     */
    public interface ResetPolicy { void reset(); }

    @NonNull private final PreviewHandlesOverlay.Target target;
    @NonNull private final Playhead playhead;
    @NonNull private final Runnable onChanged;
    private final float minSizeFraction;
    @NonNull private final ResetPolicy resetPolicy;

    private final RectF box = new RectF();
    private final float[] tmp = new float[2];

    /** Captured at beginGesture: the pose every relative write is measured from. */
    private float startCx, startCy, startSize, startRot;
    private float startW, startH;

    public AffineTransformHost(@NonNull PreviewHandlesOverlay.Target target,
                               @NonNull Playhead playhead,
                               @NonNull Runnable onChanged,
                               float minSizeFraction,
                               @NonNull ResetPolicy resetPolicy) {
        this.target = target;
        this.playhead = playhead;
        this.onChanged = onChanged;
        this.minSizeFraction = minSizeFraction;
        this.resetPolicy = resetPolicy;
    }

    private long now() { return playhead.timelineMs(); }

    /** The item's picture rect in overlay pixels, or false when it should show nothing. */
    private boolean readBox(long t) {
        if (!target.frame(t, box)) return false;
        return box.width() > 0.5f && box.height() > 0.5f;
    }

    @Override
    @NonNull
    public RectF videoRect() { return target.videoRect(); }

    // ── Reading ──────────────────────────────────────────────────────────

    @Override
    public boolean readQuad(@NonNull float[] outQuad8) {
        long t = now();
        if (!readBox(t)) return false;
        float cx = box.centerX(), cy = box.centerY();
        float th = (float) Math.toRadians(target.rotationDeg(t));
        float c = (float) Math.cos(th), s = (float) Math.sin(th);
        float[] xs = {box.left, box.right, box.right, box.left};
        float[] ys = {box.top, box.top, box.bottom, box.bottom};
        for (int i = 0; i < 4; i++) {
            float dx = xs[i] - cx, dy = ys[i] - cy;
            outQuad8[i * 2] = cx + dx * c - dy * s;
            outQuad8[i * 2 + 1] = cy + dx * s + dy * c;
        }
        return true;
    }

    @Override
    public void readPivot(@NonNull float[] outXY) {
        if (!readBox(now())) {
            outXY[0] = 0f;
            outXY[1] = 0f;
            return;
        }
        outXY[0] = box.centerX();
        outXY[1] = box.centerY();
    }

    /**
     * No stored rotation pivot on an affine type, so the fold pivot IS the centre. Both replaced
     * hosts delegated here identically; so does the spine's.
     */
    @Override
    public void readFoldPivot(@NonNull float[] outXY) { readPivot(outXY); }

    @Override
    public float currentRotationDeg() { return target.rotationDeg(now()); }

    // ── Gesture lifecycle ────────────────────────────────────────────────

    @Override
    public void beginGesture() {
        long t = now();
        startCx = target.centerX(t);
        startCy = target.centerY(t);
        startSize = target.sizeFraction(t);
        startRot = target.rotationDeg(t);
        if (readBox(t)) {
            startW = box.width();
            startH = box.height();
        } else {
            startW = 0f;
            startH = 0f;
        }
        target.beginGesture();
    }

    @Override
    public void commitGesture(@NonNull String what) { target.commit(what); }

    // ── Writing ──────────────────────────────────────────────────────────

    @Override
    public boolean writeQuad(@NonNull float[] quad8) {
        if (!TransformQuad.isValid(quad8)) return false;
        if (startW < 0.5f || startH < 0.5f) return false;
        long t = now();

        float tlx = quad8[0], tly = quad8[1];
        float trx = quad8[2], try_ = quad8[3];
        float brx = quad8[4], bry = quad8[5];
        float blx = quad8[6], bly = quad8[7];
        float topX = trx - tlx, topY = try_ - tly;
        float rightX = brx - trx, rightY = bry - try_;
        float bottomX = blx - brx, bottomY = bly - bry;
        float leftX = tlx - blx, leftY = tly - bly;

        // AFFINE VALIDATION, in two parts. First a parallelogram: TL + top + right must land on
        // BR. When that is marginal, fall back to the equivalent statement that opposite edges
        // are equal and opposite — the same shape read a different way, which tolerates the
        // rounding a long edge accumulates without tolerating an actual trapezoid.
        float expBrX = tlx + topX + rightX;
        float expBrY = tly + topY + rightY;
        float span = Math.max(1e-4f, Math.max(Math.abs(trx - blx), Math.abs(try_ - bly)));
        if (Math.abs(expBrX - brx) > span * 0.02f || Math.abs(expBrY - bry) > span * 0.02f) {
            if (Math.abs(topX + bottomX) > span * 0.02f
                    || Math.abs(topY + bottomY) > span * 0.02f) return false;
            if (Math.abs(rightX + leftX) > span * 0.02f
                    || Math.abs(rightY + leftY) > span * 0.02f) return false;
        }
        // Then perpendicularity: adjacent edges must still meet at a right angle, or the shape
        // is a shear and there is no renderer for it.
        float lenTop = (float) Math.hypot(topX, topY);
        float lenRight = (float) Math.hypot(rightX, rightY);
        if (lenTop < 1f || lenRight < 1f) return false;
        float dot = (topX * rightX + topY * rightY) / (lenTop * lenRight);
        if (Math.abs(dot) > 0.02f) return false;

        float[] cen = tmp;
        TransformQuad.centroid(quad8, cen);
        float cx = cen[0], cy = cen[1];
        float curW = (lenTop + (float) Math.hypot(bottomX, bottomY)) * 0.5f;
        float curH = (lenRight + (float) Math.hypot(leftX, leftY)) * 0.5f;
        float factorW = curW / Math.max(1f, startW);
        float factorH = curH / Math.max(1f, startH);
        // ONE uniform factor from two axis factors. Dragging a side handle changes one axis and
        // leaves the other at 1, and averaging then halves the change the finger actually made —
        // so an axis that barely moved is treated as "not the one being dragged" and ignored.
        float factor;
        boolean wIdle = Math.abs(factorW - 1f) < 0.015f;
        boolean hIdle = Math.abs(factorH - 1f) < 0.015f;
        if (wIdle && !hIdle) factor = factorH;
        else if (hIdle && !wIdle) factor = factorW;
        else factor = (factorW + factorH) * 0.5f;
        factor = TransformQuad.clamp(factor, TransformQuad.MIN_FACTOR, TransformQuad.MAX_FACTOR);
        float newSize = Math.max(minSizeFraction, startSize * factor);

        float newAngle = (float) Math.toDegrees(Math.atan2(topY, topX));
        // atan2 returns (-180, 180], so a gesture crossing the branch cut would otherwise jump a
        // full turn. Choose the equivalent angle nearest where the gesture started.
        while (newAngle - startRot > 180f) newAngle -= 360f;
        while (newAngle - startRot < -180f) newAngle += 360f;

        RectF v = target.videoRect();
        if (v.width() <= 0f || v.height() <= 0f) return false;
        // Size and angle first, position last: the target's own position write is expressed
        // against the current size, so reordering these moves the object by the size delta.
        target.scaleTo(newSize, t);
        target.rotateTo(newAngle, t);
        float normCx = TransformQuad.clamp((cx - v.left) / v.width(), -2f, 3f);
        float normCy = TransformQuad.clamp((cy - v.top) / v.height(), -2f, 3f);
        target.moveTo(normCx, normCy, t);
        onChanged.run();
        return true;
    }

    @Override
    public void writeTranslate(float dxPx, float dyPx) {
        RectF v = target.videoRect();
        if (v.width() <= 0f || v.height() <= 0f) return;
        target.moveTo(startCx + dxPx / v.width(), startCy + dyPx / v.height(), now());
        onChanged.run();
    }

    @Override
    public void writeSimilarity(float factor, float deltaDeg, float cxPx, float cyPx) {
        long t = now();
        RectF v = target.videoRect();
        if (v.width() <= 0f || v.height() <= 0f) return;
        // Same order as writeQuad, for the same reason.
        target.scaleTo(Math.max(minSizeFraction, startSize * factor), t);
        target.rotateTo(startRot + deltaDeg, t);
        target.moveTo((cxPx - v.left) / v.width(), (cyPx - v.top) / v.height(), t);
        onChanged.run();
    }

    @Override
    public void writeRotation(float deg) {
        target.rotateTo(deg, now());
        onChanged.run();
    }

    @Override
    public void resetObjectGeometry() { resetPolicy.reset(); }

    /**
     * False for every affine type here. Neither text nor PiP has an honest render path for a
     * mirrored picture, and the surface must not offer a gesture the renderers cannot draw —
     * the same rule {@link Host#supportsBend()} states for bending.
     *
     * <p>Said HERE rather than by leaving {@link #flip} empty, because an empty body still let the
     * ring open and commit a gesture around it, which spent one of the user's undo presses doing
     * nothing.
     */
    @Override
    public boolean supportsFlip() { return false; }

    @Override
    public void flip(boolean horizontal) { /* see supportsFlip */ }
}
