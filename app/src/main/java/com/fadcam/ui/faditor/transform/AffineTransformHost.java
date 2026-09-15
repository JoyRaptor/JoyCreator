package com.fadcam.ui.faditor.transform;

import android.graphics.RectF;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

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

    /**
     * OPTIONAL: a type whose renderers can draw a corner pin.
     *
     * <p>Null means affine-only, which is what text, PiP and the spine still are. A type supplies
     * this ONLY once both its preview and its export draw a pinned picture — the rule stated at
     * {@code FaditorEditorActivity} ~25544 and the reason text and PiP do not have one. Handing a
     * channel to a type whose renderers cannot draw the result would author a distortion that
     * looks right in the handles and vanishes in the file.
     *
     * <p>Deliberately narrow. It is the eight offsets and nothing else: no keyframes, no bake, no
     * mirror, no pivot. Those belong to the image host's much larger seam, and a type that needs
     * them needs that host, not this one.
     */
    public interface PinChannel {
        /** The object's pin at {@code t}, into {@code out8} (CornerPin order). */
        void readPins(long t, @NonNull float[] out8);

        /** Store eight offsets. Return false to refuse — the view then rolls the gesture back. */
        boolean writePins(@NonNull float[] pin8, long t);

        /**
         * Is the object currently mirrored? Asked HERE rather than of the Target because mirroring
         * is a model fact and the Target has no notion of one. See {@code writePinned} for why a
         * mirrored object refuses a distortion instead of guessing at one.
         */
        default boolean mirrored() { return false; }
    }

    @NonNull private final PreviewHandlesOverlay.Target target;
    @NonNull private final Playhead playhead;
    @NonNull private final Runnable onChanged;
    private final float minSizeFraction;
    @NonNull private final ResetPolicy resetPolicy;
    @Nullable private final PinChannel pin;
    /**
     * The bend, when this type's BOTH renderers can draw one. Null = affine only, and the ring
     * never offers the net — the same shape as {@link #pin}, and the same rule behind it.
     *
     * <p>Shared with the image host rather than reimplemented: {@link MeshBendSeam} is the one
     * copy, so a fix to the bend tool reaches every warpable type at once. That is the whole point
     * of SPEC Y.
     */
    @Nullable private final MeshBendSeam bend;

    private final float[] pinScratch = new float[com.fadcam.ui.faditor.model.CornerPin.SIZE];

    private final RectF box = new RectF();
    private final float[] tmp = new float[2];

    /** Captured at beginGesture: the pose every relative write is measured from. */
    private float startCx, startCy, startSize, startRot;
    private float startW, startH;

    /** Affine-only. */
    public AffineTransformHost(@NonNull PreviewHandlesOverlay.Target target,
                               @NonNull Playhead playhead,
                               @NonNull Runnable onChanged,
                               float minSizeFraction,
                               @NonNull ResetPolicy resetPolicy) {
        this(target, playhead, onChanged, minSizeFraction, resetPolicy, null);
    }

    /** With a pin channel, for a type whose BOTH renderers draw one. */
    public AffineTransformHost(@NonNull PreviewHandlesOverlay.Target target,
                               @NonNull Playhead playhead,
                               @NonNull Runnable onChanged,
                               float minSizeFraction,
                               @NonNull ResetPolicy resetPolicy,
                               @Nullable PinChannel pin) {
        this(target, playhead, onChanged, minSizeFraction, resetPolicy, pin, null);
    }

    /** With a pin channel AND a bend, for a type whose both renderers draw both. */
    public AffineTransformHost(@NonNull PreviewHandlesOverlay.Target target,
                               @NonNull Playhead playhead,
                               @NonNull Runnable onChanged,
                               float minSizeFraction,
                               @NonNull ResetPolicy resetPolicy,
                               @Nullable PinChannel pin,
                               @Nullable MeshBendSeam bend) {
        this.target = target;
        this.playhead = playhead;
        this.onChanged = onChanged;
        this.minSizeFraction = minSizeFraction;
        this.resetPolicy = resetPolicy;
        this.pin = pin;
        this.bend = bend;
    }

    /** True when this host can author a distortion — the ring reads it to offer Tilt and Free. */
    public boolean supportsPin() { return pin != null; }

    // ── The bend, delegated to the shared seam ───────────────────────────────────────────────

    @Override
    public boolean supportsBend() { return bend != null; }

    @Override
    public boolean hasBend() { return bend != null && bend.hasBend(); }

    @Override
    public int bendHandleCount() { return bend == null ? 0 : bend.handleCount(); }

    @Override
    public int bendGridSide() { return bend == null ? 0 : bend.gridSide(); }

    @Override
    public boolean bendHandlePosition(int i, @NonNull float[] h, @NonNull float[] out2) {
        return bend != null && bend.handlePosition(i, h, out2);
    }

    @Override
    public boolean bendDragTo(int i, @NonNull float[] hInv, float stageX, float stageY) {
        return bend != null && bend.dragTo(i, hInv, stageX, stageY);
    }

    @Override
    public void beginBendGesture() {
        if (bend != null) bend.begin();
        beginGesture();
    }

    @Override
    public void commitBendGesture(@NonNull String what) {
        if (bend != null) bend.commit();
        commitGesture(what);
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
        // With a pin, the handles must hug the DISTORTED picture — the corners the renderers
        // actually draw — or every gesture would be measured from a rectangle the user cannot
        // see. Offsets are fractions of the item's own size, in CornerPin's TL,TR,BR,BL order,
        // which is the order xs/ys are built in.
        if (pin != null) {
            pin.readPins(t, pinScratch);
            float bw = box.width(), bh = box.height();
            for (int i = 0; i < 4; i++) {
                xs[i] += pinScratch[i * 2] * bw;
                ys[i] += pinScratch[i * 2 + 1] * bh;
            }
        }
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
                    || Math.abs(topY + bottomY) > span * 0.02f) return writePinned(quad8, t);
            if (Math.abs(rightX + leftX) > span * 0.02f
                    || Math.abs(rightY + leftY) > span * 0.02f) return writePinned(quad8, t);
        }
        // Then perpendicularity: adjacent edges must still meet at a right angle, or the shape
        // is a shear.
        float lenTop = (float) Math.hypot(topX, topY);
        float lenRight = (float) Math.hypot(rightX, rightY);
        if (lenTop < 1f || lenRight < 1f) return false;
        float dot = (topX * rightX + topY * rightY) / (lenTop * lenRight);
        if (Math.abs(dot) > 0.02f) return writePinned(quad8, t);

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

    /**
     * A NON-AFFINE quad, stored as eight corner offsets — the distortion branch.
     *
     * <p>Reached only when the shape is not a rotated rectangle. Without a {@link PinChannel} that
     * is a refusal and the view rolls the drag back, which is what text, PiP and the spine still
     * do. With one, the same {@code TransformQuad.solvePinForQuad} the image host uses inverts the
     * render equation into offsets — one solver, so a sprite's distortion and an image's cannot
     * be computed differently.
     *
     * <p>MIRROR IS REFUSED RATHER THAN GUESSED. A flipped sprite applies its flip OUTSIDE the pin
     * in the canvas stack, so the quad the finger drew is in mirrored screen space while the
     * offsets would be stored in unmirrored item space. Solving that correctly is the image host's
     * un-mirror step, which this narrow channel deliberately does not carry. Refusing is honest —
     * the drag stops and nothing is stored — where guessing would save a distortion that renders
     * inside out. Unflip, distort, reflip.
     */
    private boolean writePinned(@NonNull float[] quad8, long t) {
        if (pin == null) return false;
        if (!readBox(t)) return false;
        if (pin.mirrored()) return false;
        boolean ok = TransformQuad.solvePinForQuad(
                quad8,
                box.centerX(), box.centerY(),
                box.width(), box.height(),
                target.rotationDeg(t),
                1f, 1f,
                // Centre pivot: this channel carries no stored rotation pivot, and 0.5/0.5 is
                // what "no pivot" means to the solver.
                0.5f, 0.5f,
                pinScratch);
        if (!ok) return false;
        for (float v : pinScratch) {
            if (Float.isNaN(v) || Float.isInfinite(v)
                    || Math.abs(v) > com.fadcam.ui.faditor.model.CornerPin.MAX_OFFSET) {
                return false;   // out of the model's range: refuse, do not clamp into a lie
            }
        }
        if (!pin.writePins(pinScratch, t)) return false;
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
