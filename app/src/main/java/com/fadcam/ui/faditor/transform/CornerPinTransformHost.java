package com.fadcam.ui.faditor.transform;

import android.graphics.Matrix;
import android.graphics.RectF;

import androidx.annotation.NonNull;

import com.fadcam.FLog;
import com.fadcam.ui.faditor.keyframe.KeyframeSet;
import com.fadcam.ui.faditor.model.CornerPin;
import com.fadcam.ui.faditor.model.TextOverlayItem;
import com.fadcam.ui.faditor.overlay.PreviewHandlesOverlay;

/**
 * THE ONLY FADCAM-SHAPED FILE IN THIS PACKAGE: the thin adapter binding
 * {@link TransformOverlayView} to a {@link TextOverlayItem}.
 *
 * <p>{@link TransformQuad} is arithmetic, {@link HandleModel} is vocabulary and
 * {@link TransformOverlayView} is a View that knows neither — the app-specific knowledge is all
 * here, in about two screens of it. Porting the surface to another app means writing another one
 * of these, not touching the other three files.</p>
 *
 * <h3>Preview and export agree because this file adds no render path at all</h3>
 * <p>Every gesture is written to a property that ALREADY has a preview implementation and an export
 * implementation which were landed together and already agree:</p>
 * <ul>
 *   <li>pan → {@code X}/{@code Y}; pinch → {@code SCALE} + {@code ROTATION} + {@code X}/{@code Y};
 *       the spin arc → {@code ROTATION}. All five are ordinary transform tracks.</li>
 *   <li>every distortion — scale, tilt, free, fold — → the EIGHT {@link CornerPin} tracks,
 *       drawn in the preview by {@code CornerPinImageView} / the GL chain's pin uniforms and in the
 *       export by {@code ImageOverlayDraw} through the SAME {@link CornerPin#buildMatrix}.</li>
 *   <li>flip → the mirror flags (plus a pin-axis negation when distorted), drawn by all three
 *       renderers from {@code TextOverlayItem.mirrorSignX/Y} — and at commit the
 *       {@link TransformQuad#normalizePin} bake folds the affine part out of the pin, so a
 *       flip, fold, rotate or scale leaves the pin cleared instead of spending its budget.</li>
 * </ul>
 * <p>There is no fifth thing this view can author. So the two surfaces cannot disagree about a
 * transform gesture unless they already disagreed about a slider — which is the property the corner
 * pin was built with and the reason nothing new was invented for this.</p>
 *
 * <h3>The one conversion, in both directions</h3>
 * <p>A corner-pin offset is a fraction of the item's own untransformed drawn size, applied INSIDE
 * the item's rotation and scale (see {@link CornerPin}'s class note). So the on-screen quad is</p>
 * <pre>  screen[i] = R(rot, centre) · ( boxCorner[i] + off[i] · (w, h) )</pre>
 * <p>and reading a dragged quad back is the same line rearranged. {@link #readQuad} and
 * {@link #writeQuad} are literally that, which is why they round-trip exactly and why a gesture
 * that changes nothing writes nothing.</p>
 */
public final class CornerPinTransformHost implements TransformOverlayView.Host {

    /** Where the playhead is, asked fresh every call — the object animates under the handles. */
    public interface Playhead { long timelineMs(); }

    @NonNull private final TextOverlayItem item;
    /**
     * The EXISTING write channel for position / size / rotation.
     *
     * <p>Reused rather than reimplemented on purpose: that target already carries the
     * keyframe-armed branch, the image-animation-preset branches (a preset-owned key is shifted,
     * not overwritten), the travel clamp and the drawer refresh. A second copy of that logic would
     * be the third place in this file tree that decides what "move an overlay" means, and the two
     * would drift.</p>
     */
    @NonNull private final PreviewHandlesOverlay.Target target;
    @NonNull private final Playhead playhead;
    @NonNull private final Runnable onChanged;

    private final RectF box = new RectF();
    private final float[] off = new float[CornerPin.SIZE];
    private final Matrix probe = new Matrix();

    // Captured at beginGesture: the pose every relative write is measured from.
    private float startCx, startCy, startSize, startRot;
    /**
     * SPEC G — whether any of the eight pin tracks carried keyframes when the gesture began.
     * A keyframed pin is an animated pose: baking a single static affine over it would destroy
     * the animation, so commit-time normalisation walks away and the gesture stands as authored.
     * Keys the gesture itself writes do NOT count — only what was already there.
     */
    private boolean hadPinKeys;

    public CornerPinTransformHost(@NonNull TextOverlayItem item,
                                  @NonNull PreviewHandlesOverlay.Target target,
                                  @NonNull Playhead playhead,
                                  @NonNull Runnable onChanged) {
        this.item = item;
        this.target = target;
        this.playhead = playhead;
        this.onChanged = onChanged;
    }

    @NonNull public TextOverlayItem item() { return item; }

    @Override
    @NonNull
    public RectF videoRect() { return target.videoRect(); }

    private long now() { return playhead.timelineMs(); }

    // ── Reading ──────────────────────────────────────────────────────────

    /** The item's picture rect in overlay pixels, or false when it should show nothing. */
    private boolean readBox(long t) {
        if (!target.frame(t, box)) return false;
        return box.width() > 0.5f && box.height() > 0.5f;
    }

    @Override
    public boolean readQuad(@NonNull float[] outQuad8) {
        long t = now();
        if (!readBox(t)) return false;
        item.animatedCornerPin(t, off);
        float w = box.width(), h = box.height();
        float cx = box.centerX(), cy = box.centerY();
        double rad = Math.toRadians(target.rotationDeg(t));
        float cs = (float) Math.cos(rad), sn = (float) Math.sin(rad);
        float[] baseX = {box.left, box.right, box.right, box.left};
        float[] baseY = {box.top, box.top, box.bottom, box.bottom};
        // SPEC G — the presented quad wears the mirror (about the box centre, before the
        // rotation, exactly as every renderer draws it). Unmirrored this is the same line.
        float smx = item.mirrorSignX(), smy = item.mirrorSignY();
        for (int i = 0; i < 4; i++) {
            float px = baseX[i] + off[i * 2] * w;
            float py = baseY[i] + off[i * 2 + 1] * h;
            float dx = smx * (px - cx), dy = smy * (py - cy);
            outQuad8[i * 2] = cx + cs * dx - sn * dy;
            outQuad8[i * 2 + 1] = cy + sn * dx + cs * dy;
        }
        return true;
    }

    @Override
    public void readPivot(@NonNull float[] outXY) {
        long t = now();
        if (!readBox(t)) { outXY[0] = 0f; outXY[1] = 0f; return; }
        outXY[0] = box.centerX();
        outXY[1] = box.centerY();
    }

    /**
     * SPEC B — the stored pivot, in overlay pixels, measured on the PRESENTED rect: the
     * pivot point is fold-invariant and sits at presented-centre + R(θ)·δ, δ =
     * (pivot−0.5)·(w,h) — the model's own shared pivot arithmetic. This is what the rotate
     * handle's live preview orbits, so the handles swing with the picture instead of spinning
     * in place while the picture orbits the pivot.
     */
    @Override
    public void readFoldPivot(@NonNull float[] outXY) {
        long t = now();
        if (!readBox(t)) { outXY[0] = 0f; outXY[1] = 0f; return; }
        // Centre pivot turns about the box centre (neutral by definition) — no fold.
        if (item.isRotationPivotCentre()) {
            outXY[0] = box.centerX();
            outXY[1] = box.centerY();
            return;
        }
        float th = target.rotationDeg(t);
        double rad = Math.toRadians(th);
        float pc = (float) Math.cos(rad), ps = (float) Math.sin(rad);
        item.animatedCornerPin(t, off);
        // Mirror-aware fold (SPEC K flip exactness): the rotation turns about the
        // VISUAL pivot, which is the stored offset mirrored by the flags. Unmirrored
        // the signs are +1 and this is the same two lines.
        float dX = item.mirrorSignX()
                * item.pivotOffsetFromCentreX(box.width(), box.height(), off);
        float dY = item.mirrorSignY()
                * item.pivotOffsetFromCentreY(box.width(), box.height(), off);
        outXY[0] = box.centerX() + pc * dX - ps * dY;
        outXY[1] = box.centerY() + ps * dX + pc * dY;
    }

    @Override
    public float currentRotationDeg() { return target.rotationDeg(now()); }

    // ── Writing ──────────────────────────────────────────────────────────

    @Override
    public void beginGesture() {
        long t = now();
        startCx = target.centerX(t);
        startCy = target.centerY(t);
        startSize = target.sizeFraction(t);
        startRot = target.rotationDeg(t);
        hadPinKeys = false;
        KeyframeSet ks = item.getKeyframes();
        if (ks != null) {
            for (String tr : CornerPin.tracks()) {
                if (ks.hasProperty(tr)) { hadPinKeys = true; break; }
            }
        }
        target.beginGesture();
    }

    @Override
    public boolean writeQuad(@NonNull float[] quad8) {
        long t = now();
        if (!readBox(t)) return false;
        float w = box.width(), h = box.height();
        // SPEC B — the dragged quad is PRESENTED (the pose box carried by the pivot fold); the
        // pin offsets live in the POSE frame. ONE un-fold of each dragged corner about the
        // pivot by −θ, measured against the POSE box corners — nothing more. The two earlier
        // attempts each stored a warped offset: un-folding and then un-rotating again applied
        // the frame conversion twice (JoyRaptor: "it looks like it is rotating the image 90
        // degrees as it resizes, and then the bounding box snaps"), and measuring against the
        // folded box stored a pose-dependent shear that made every later pivot change swing a
        // warped picture. At the centre pivot P is the box centre, so this is exactly the
        // plain un-rotation the conversion always did — untouched projects are byte-identical.
        float th = target.rotationDeg(t);
        // NEUTRAL, not "pivot == centre": a centre pivot on a PINNED picture still carries a
        // real offset (the quad centre), so the old test skipped the fold on exactly the
        // pictures that most needed it. See TextOverlayItem.isRotationPivotNeutral.
        item.animatedCornerPin(t, off);
        boolean folded = !item.isRotationPivotNeutral(off) && th != 0f;
        double radU = Math.toRadians(-th);
        float uc = (float) Math.cos(radU), us = (float) Math.sin(radU);
        float smx = item.mirrorSignX(), smy = item.mirrorSignY();
        float pvx = box.centerX(), pvy = box.centerY();
        float bcx = box.centerX(), bcy = box.centerY();
        if (folded) {
            double radP = Math.toRadians(th);
            float pc = (float) Math.cos(radP), ps = (float) Math.sin(radP);
            // SPEC K flip exactness: the fold is mirror-aware — the pivot offset is
            // evaluated on the unmirrored pins and then mirrored, so a mirrored picture
            // folds about its visual pivot, not the bitmap one. Unmirrored this is the
            // same two lines.
            float dX = smx * item.pivotOffsetFromCentreX(w, h, off);
            float dY = smy * item.pivotOffsetFromCentreY(w, h, off);
            // The pivot point is fold-invariant; on the presented rect it sits at
            // centre + R(θ)·δ.
            pvx = bcx + pc * dX - ps * dY;
            pvy = bcy + ps * dX + pc * dY;
            // POSE box centre = the presented centre un-folded about the pivot.
            float dx = bcx - pvx, dy = bcy - pvy;
            bcx = pvx + uc * dx - us * dy;
            bcy = pvy + us * dx + uc * dy;
        }
        float[] next = new float[CornerPin.SIZE];
        // SPEC K — the pin lives in the UNMIRRORED box frame however the flags stand, so the
        // dragged (mirrored) quad is un-mirrored about the pose centre on the way in.
        // Unmirrored this is the same two lines. The solve itself is implicit in the NEW
        // pins (TransformQuad.solvePinForQuad): the pivot offset is a bilinear function
        // of the pins being solved for, so unfolding with the OLD offset stored every
        // fold/scale/free drag on a rotated picture into the wrong frame by
        // (I−R)·(δold−δnew) — the handles followed the finger while the picture lagged,
        // snapping back on release, and repeated gestures walked one corner a full
        // picture-height away. The pose centre above is stable inside a distort gesture
        // (only pins move), so recovering it with the old offset stays exact.
        if (!TransformQuad.solvePinForQuad(quad8, bcx, bcy, w, h, th, smx, smy,
                item.rotationPivotXNorm(), item.rotationPivotYNorm(), next)) return false;
        // SPEC K — a fold is always exactly representable (parallelogram → the commit
        // bake clears it to mirror flags with zero residual), however far its raw pins
        // read past the ±2 budget — a fold over an edge of a ROTATED picture measures
        // up to ~2.8 against the unrotated box. Refusing those for range deadlocked
        // fold-move-fold chains at nonzero rotation (JoyRaptor 2026-09-07: folds "stopped
        // working" from the second fold on). So the range gate opens for exactly what
        // the commit will clear; genuine distortion keeps the budget. normalizePin is
        // pure arithmetic (no writes), so asking it mid-drag is safe — and deterministic:
        // the commit runs the same function and reaches the same fit.
        if (!withinRange(next)
                && !bakesToFlat(w, h, next, smx < 0f, smy < 0f)) return false;
        if (!withinRange(next)) return false;
        // REFUSE, DO NOT CLAMP, when the homography will not solve. Clamping would let the finger
        // keep dragging while the picture silently stopped following it — the shape on screen and
        // the shape in the model would then be two different things, which is the whole class of
        // bug the overlay's rollback exists to avoid. Refusing makes the drag simply stop.
        if (!CornerPin.buildMatrix(probe, 0f, 0f, w, h, next)) return false;
        applyPin(next, t);
        return true;
    }

    /**
     * True when these pins will bake to mirror flags with an exactly-flat residual —
     * i.e. they describe a parallelogram (a fold, flip, rotate or scale wearing pin
     * form), not a genuine distortion. The commit bake is deterministic, so asking
     * here previews exactly what it will do.
     */
    private static boolean bakesToFlat(float w, float h, float[] next,
                                       boolean mirrorX, boolean mirrorY) {
        TransformQuad.PinNormalize fit = TransformQuad.normalizePin(w, h, next, mirrorX, mirrorY);
        return fit != null && fit.valid && fit.baked && CornerPin.isFlat(fit.residual);
    }
    /** Every offset inside {@link CornerPin#MAX_OFFSET} — the reach the tracks can serialise.
     * A 1e-4 dust allowance: a fold from flat lands at exactly ±2 through float
     * reflection math, which can read 2.0000002. The writers clamp to the cap, so admitting
     * dust changes nothing stored — refusing it would fail the fold (SPEC G). */
    private static boolean withinRange(float[] pin) {
        for (float v : pin) {
            if (Float.isNaN(v) || Float.isInfinite(v)) return false;
            if (Math.abs(v) > CornerPin.MAX_OFFSET + 1e-4f) return false;
        }
        return true;
    }

    /**
     * Write eight offsets, keyframe-aware — the SAME convention as every other hand gesture and
     * every drawer slider: armed drops keys at the playhead, unarmed writes the static pose.
     */
    private void applyPin(float[] pin, long t) {
        boolean armed = item.isArmed();
        for (int c = 0; c < 4; c++) {
            for (int a = 0; a < 2; a++) {
                float v = pin[c * 2 + a];
                if (armed) item.addCornerPinKeyframeAt(c, a, t, v);
                else item.setCornerPin(c, a, v);
            }
        }
        onChanged.run();
    }

    @Override
    public void writeTranslate(float dxPx, float dyPx) {
        RectF v = target.videoRect();
        if (v.width() <= 0f || v.height() <= 0f) return;
        target.moveTo(startCx + dxPx / v.width(), startCy + dyPx / v.height(), now());
        // SPEC J — the host contract is that EVERY write path ends in onChanged: the target's
        // own writes already refresh (moveTo → refreshTextAfterHandleWrite → coalesced GL
        // resync), but a future target that stops refreshing cannot be allowed to silently
        // reintroduce the stale-picture bug. Coalesced, so this costs nothing.
        onChanged.run();
    }

    @Override
    public void writeSimilarity(float factor, float deltaDeg, float cxPx, float cyPx) {
        long t = now();
        RectF v = target.videoRect();
        if (v.width() <= 0f || v.height() <= 0f) return;
        // Size and angle first, position last: moveTo is the one that reads the object's CURRENT
        // rendered size to clamp its travel, so it must see the size this gesture just set.
        target.scaleTo(Math.max(0.01f, startSize * factor), t);
        target.rotateTo(startRot + deltaDeg, t);
        // SPEC B — the pivot fold, pinch side. cxPx/cyPx is where the PRESENTED centre lands
        // under the fingers (the view computes it from readPivot, which returns the folded
        // centre); the pose centre to write is that point UN-FOLDED at the new pose:
        // C = ncx − (I − R(θ'))·δ', δ' = (pivot−0.5)·(w,h) at the scaled size (the box dims
        // scale by exactly `factor` — the pinch writes the uniform size). Writing the presented
        // centre as the pose centre would displace the object a second time on the next fold.
        float px = cxPx, py = cyPx;
        item.animatedCornerPin(t, off);
        if (!item.isRotationPivotNeutral(off)) {
            float wN = box.width() * factor, hN = box.height() * factor;
            // SPEC B, pinned pictures — the scaled size carries the pinned quad with it (the pin
            // offsets are size fractions), so the scaled box dims alone keep the pivot honest.
            // Mirror-aware (SPEC K flip exactness): the presented centre folds about the
            // visual pivot. Unmirrored the signs are +1 and this is the same two lines.
            float dX = item.mirrorSignX() * item.pivotOffsetFromCentreX(wN, hN, off);
            float dY = item.mirrorSignY() * item.pivotOffsetFromCentreY(wN, hN, off);
            double rad = Math.toRadians(startRot + deltaDeg);
            float cs = (float) Math.cos(rad), sn = (float) Math.sin(rad);
            px = cxPx - (dX - (cs * dX - sn * dY));
            py = cyPx - (dY - (sn * dX + cs * dY));
        }
        target.moveTo((px - v.left) / v.width(), (py - v.top) / v.height(), t);
        onChanged.run();   // SPEC J — every write path ends in onChanged (coalesced)
    }

    @Override
    public void writeRotation(float deg) {
        target.rotateTo(deg, now());
        onChanged.run();   // SPEC J — every write path ends in onChanged (coalesced)
    }

    @Override
    public void commitGesture(@NonNull String what) {
        // SPEC K — flips skip the bake entirely: a flip leaves canonical state (mirror
        // flag, negated angle, untouched pins), so the bake can only re-decompose it —
        // and each re-decomposition re-rolls mirror/rotation/centre representation
        // (flags in or out, ±180 turns, recentred poses), which reads as the flip
        // "landing" somewhere different every time. The picture is exact without it
        // (proven 0.0px), and double flips restore bit-for-bit.
        if ("Flip".equals(what)) {
            target.commit(what);
            return;
        }
        // SPEC K — distort gestures never write the pose (only pins), so if the pose
        // moved between begin and commit, an outside writer touched it mid-gesture
        // (drawer slider on a second finger, playback tick over keyframes, a stale
        // frame) and the pins were measured against the wrong box: baking them into
        // the pose would teleport the object on finger-up. Walk away exactly like an
        // animated pin (hadPinKeys): the gesture stands as authored, nothing moves.
        // Move/Rotate/Transform/Flip write the pose by design and skip this check.
        if ("Distort".equals(what) || "Fold".equals(what)) {
            long t = now();
            if (Math.abs(target.centerX(t) - startCx) > 1e-4f
                    || Math.abs(target.centerY(t) - startCy) > 1e-4f
                    || Math.abs(target.sizeFraction(t) - startSize)
                            > 1e-4f * Math.max(1f, Math.abs(startSize))
                    || Math.abs(target.rotationDeg(t) - startRot) > 1e-3f) {
                FLog.d("PinBake", "drift-walkaway id=" + item.getId() + " what=" + what);
                TransformDiag.log("drift-walkaway id=" + item.getId() + " what=" + what);
                target.commit(what);
                return;
            }
        }
        tryNormalizeOnCommit(what);
        keepDrawnQuadOnCanvas();
        target.commit(what);
    }

    /**
     * SPEC L Part 2 — the backstop, and it measures the picture rather than the box.
     *
     * <p>The travel clamp guards {@code centerX/centerY}; a corner pin translates the DRAWN
     * quad away from that centre, so a pinned picture could walk off canvas with the model
     * reporting it as barely out. Part 1 puts translation back in the centre, which makes the
     * travel clamp effective again — this is the one-gesture case that could still fling a
     * picture out. At least {@link TransformQuad#QUAD_MIN_VISIBLE_FRAC} of the drawn quad's
     * bounding box must remain on canvas; if not, the POSE is translated (never the pin, never
     * the shape) by the minimum that satisfies it, through the same keyframe-aware target
     * every gesture uses, before the single {@code commit} — so it is part of the same undo
     * step, not a second one.</p>
     *
     * <p>Deliberately narrow: images only, no animated position or pin. An overlay whose X/Y
     * or pin is keyframed may be legitimately off canvas at this instant (a fly-in preset is
     * exactly that), and yanking it would destroy the animation rather than save the picture.
     * Logged when it fires, because if Part 1 is right it should not.</p>
     */
    private void keepDrawnQuadOnCanvas() {
        if (!item.isImage()) return;
        if (hadPinKeys || item.isArmed()) return;
        KeyframeSet ks = item.getKeyframes();
        if (ks != null && (ks.hasProperty(KeyframeSet.X) || ks.hasProperty(KeyframeSet.Y))) return;
        long t = now();
        float[] quad = new float[8];
        if (!readQuad(quad)) return;
        RectF v = target.videoRect();
        if (v.width() <= 0.5f || v.height() <= 0.5f) return;
        float[] fix = new float[2];
        if (!TransformQuad.quadEscapeFix(quad, v.left, v.top, v.right, v.bottom,
                TransformQuad.QUAD_MIN_VISIBLE_FRAC, fix)) return;
        float cx = target.centerX(t), cy = target.centerY(t);
        target.moveTo(cx + fix[0] / v.width(), cy + fix[1] / v.height(), t);
        onChanged.run();
        FLog.d("PinBake", "escape-clamp id=" + item.getId()
                + " dx=" + fix[0] + " dy=" + fix[1]);
        TransformDiag.log("escape-clamp id=" + item.getId()
                + " dx=" + fix[0] + " dy=" + fix[1]);
    }

    /**
     * SPEC G — the budget bake. After every committed gesture the quad is decomposed
     * ({@link TransformQuad#normalizePin}) and only what is genuinely a distortion stays in
     * the pin; the affine part is written back to the object's own centre/size/rotation (and
     * mirror flags) through the SAME keyframe-aware target every hand gesture uses.
     *
     * <p>Safe to run here and nowhere earlier: the finger is up, so the offsets may be
     * rewritten without anything crawling under it. The picture must not move by one pixel
     * across this — {@link #verifyBake} recomputes both poses through the render equation
     * (pivot fold included) and rolls the bake back, keeping the gesture, on any drift.
     * Untouched pictures (flat pin, no mirror, no shift) exit before writing anything, so a
     * move/rotate/pinch commit never sprays keys it did not mean. A recentring beyond 3
     * picture sizes walks away instead (stale frame, not content).
     */
    private void tryNormalizeOnCommit(@NonNull String what) {
        long t = now();
        if (!item.isImage()) return;
        if (!readBox(t)) return;
        float w = box.width(), h = box.height();
        if (!(w > 0.5f) || !(h > 0.5f)) return;
        // An animated pin is a pose that moves: baking one static affine over it would
        // destroy the animation, so the gesture stands exactly as authored.
        if (hadPinKeys) return;
        item.animatedCornerPin(t, off);
        float[] pins0 = off.clone();
        TransformQuad.PinNormalize fit =
                TransformQuad.normalizePin(w, h, pins0, item.isFlipH(), item.isFlipV());
        if (fit == null || !fit.valid || !fit.baked) return;
        boolean mirChange = (fit.mirrorX != item.isFlipH() || fit.mirrorY != item.isFlipV());
        // A static flag cannot keyframe this moment: baking it would flip every frame of an
        // animation, not the one the finger is on. The gesture (pin form) is already correct.
        if (mirChange && item.isArmed()) return;
        // A bend rides the pin's homography and the stamp path cannot draw a mirror flag
        // (SPEC E, unlanded tool): baking one under it would split preview from export.
        if (mirChange && item.hasMesh()) return;
        RectF v = target.videoRect();
        if (v.width() <= 0f || v.height() <= 0f) return;
        float rot0 = target.rotationDeg(t);
        // SPEC P — THE ANCHOR IS THE POSE CENTRE, AND IT IS NOT box.centerX().
        //
        // `box` comes from Target.frame(), which ends in TextOverlayLayer.foldRotationPivotIntoBox:
        // the rect handed back has ALREADY had the pivot fold (I − R(rot)) · o applied, because the
        // dashed selection frame has to sit on the picture the user sees. That makes box.centerX()
        // the PRESENTED centre. The bake writes through target.moveTo, which sets the POSE centre —
        // the un-folded one — so anchoring the new centre at the presented one shipped the fold
        // into the model a second time, and the picture jumped by exactly (I − R(rot0)) · o0 on
        // finger-up: 2·sin(rot/2) · |o|, zero at 0°, sign flipping with the sign of the angle,
        // 50–130px on JoyRaptor's Note 9 at −18.9° with a corner pivot. verifyBake could not see it
        // because it anchored its BEFORE side at the same presented centre and then added the fold
        // again too, so the two errors cancelled inside the check while the screen moved.
        //
        // target.centerX/centerY IS the field moveTo writes, read through the same videoRect the
        // write is normalised by, so it is the anchor by definition — and unlike the box it does
        // not depend on whether the layer happened to fold this frame. Only w/h come from the box
        // now; the fold is a pure translation, so it never touched those.
        float bcx0 = v.left + target.centerX(t) * v.width();
        float bcy0 = v.top + target.centerY(t) * v.height();
        float size0 = target.sizeFraction(t);
        float sx0 = item.animatedScaleX(t), sy0 = item.animatedScaleY(t);
        float a = fit.newW / w, b = fit.newH / h;
        float newSize = size0;
        boolean split = false;
        float newSx = sx0, newSy = sy0;
        if (a != 1f || b != 1f) {
            float rel = Math.abs(a - b) / Math.max(1f, Math.max(a, b));
            if (rel <= TransformQuad.PIN_BAKE_UNIFORM_REL) {
                newSize = size0 * (a + b) * 0.5f;
            } else {
                float g = (float) Math.sqrt(a * b);
                float rr = (float) Math.sqrt(a / b);
                newSize = size0 * g;
                newSx = sx0 * rr;
                newSy = sy0 / rr;
                split = true;
            }
        }
        // Rotation ADDS (SPEC A raw storage: 720 stays 720) — never folded into a window.
        float newRot = rot0 + fit.rotDeltaDeg;
        // New pose centre: the fit's translation, plus the pivot-anchor compensation. The
        // render turns about the stored pivot, whose pin-aware offset is redefined by the new
        // pin; absorbing the anchor difference into the centre keeps the screen pixel-exact.
        // At a neutral pivot both offsets are 0 and this is just the translation.
        // Mirror-aware (SPEC K flip exactness): each side folds about its own visual
        // pivot — pre-bake flags on the way in, the fit's flags on the way out.
        // Unmirrored throughout, this is the same expression.
        float mb0x = item.mirrorSignX(), mb0y = item.mirrorSignY();
        // Centre pivots carry no fold offset (neutral by definition) on either side — the same
        // gate every renderer uses (ImageOverlayDraw, the View pivot, the GL fold).
        boolean wasCentre = item.isRotationPivotCentre();
        float o0x = wasCentre ? 0f : mb0x * item.pivotOffsetFromCentreX(w, h, pins0);
        float o0y = wasCentre ? 0f : mb0y * item.pivotOffsetFromCentreY(w, h, pins0);
        float sm1x = fit.mirrorX ? -1f : 1f, sm1y = fit.mirrorY ? -1f : 1f;
        float o1x = wasCentre ? 0f : sm1x * item.pivotOffsetFromCentreX(fit.newW, fit.newH, fit.residual);
        float o1y = wasCentre ? 0f : sm1y * item.pivotOffsetFromCentreY(fit.newW, fit.newH, fit.residual);
        // SPEC P — one shared derivation, in TransformQuad, tested at every angle off device.
        float[] nc = new float[2];
        TransformQuad.bakedPoseCentre(bcx0, bcy0, rot0, o0x, o0y,
                fit.tx, fit.ty, newRot, o1x, o1y, nc);
        float ncx = nc[0], ncy = nc[1];
        // SPEC K — cap the recentring: no single commit moves the pose centre by more
        // than 3 picture sizes. Genuine content (a fold's ±1-size swing, a sculpt
        // settling onto its centroid, corner-pivot anchor compensation) fits
        // comfortably; beyond that the frame is presumed stale and the gesture stands
        // as authored instead of teleporting on finger-up.
        if (Math.hypot(ncx - bcx0, ncy - bcy0) > 3f * Math.max(w, h)) {
            FLog.d("PinBake", "shift-walkaway id=" + item.getId()
                    + " shift=" + (ncx - bcx0) + "," + (ncy - bcy0));
            TransformDiag.log("shift-walkaway id=" + item.getId()
                    + " shift=" + (ncx - bcx0) + "," + (ncy - bcy0));
            target.commit(what);
            return;
        }
        // The post-gesture pose, so the verify can roll back exactly the bake below while
        // keeping the gesture itself.
        TextOverlayItem.TransformSnapshot preBake = item.snapshotTransform();
        // Every write gated on a real change: an armed no-op write would still drop a key.
        if (Math.hypot(ncx - bcx0, ncy - bcy0) > 1e-3f) {
            target.moveTo((ncx - v.left) / v.width(), (ncy - v.top) / v.height(), t);
        }
        if (fit.rotDeltaDeg != 0f) target.rotateTo(newRot, t);
        if (newSize != size0) target.scaleTo(newSize, t);
        if (split) {
            if (item.isArmed()) {
                // After the size write above, so the seeded siblings read the baked pose.
                item.addPropertyKeyframeAt(KeyframeSet.SCALE_X, t, newSx);
                item.addPropertyKeyframeAt(KeyframeSet.SCALE_Y, t, newSy);
            } else {
                item.setScaleX(newSx);
                item.setScaleY(newSy);
            }
            item.setScaleLinked(false);
        }
        if (mirChange) { item.setFlipH(fit.mirrorX); item.setFlipV(fit.mirrorY); }
        if (item.isArmed()) {
            // The gesture's own pin keys at this moment are replaced by the residual keys —
            // all eight, even when flat, so no stale static distortion shows through at t
            // while other moments keep theirs. (No pin keys pre-existed: hadPinKeys.)
            long localT = Math.max(0L, t - item.getStartMs());
            KeyframeSet ks = item.getKeyframes();
            for (String tr : CornerPin.tracks()) ks.removeKey(tr, localT);
            for (int c = 0; c < 4; c++) {
                for (int ax = 0; ax < 2; ax++) {
                    item.addCornerPinKeyframeAt(c, ax, t, fit.residual[c * 2 + ax]);
                }
            }
        } else if (CornerPin.isFlat(fit.residual)) {
            if (!CornerPin.isFlat(pins0)) item.clearCornerPin();
        } else {
            item.setCornerPin(fit.residual);
        }
        onChanged.run();
        if (!verifyBake(t, v, bcx0, bcy0, w, h, pins0, rot0,
                size0, sx0, sy0, mb0x, mb0y)) {
            FLog.d("PinBake", "rollback id=" + item.getId() + " rot0=" + rot0
                    + " tx=" + fit.tx + " ty=" + fit.ty + " dRot=" + fit.rotDeltaDeg
                    + " newSize=" + fit.newW + "x" + fit.newH
                    + " mir=" + mb0x + "," + mb0y + "->" + fit.mirrorX + "," + fit.mirrorY);
            TransformDiag.log("rollback id=" + item.getId() + " rot0=" + rot0
                    + " tx=" + fit.tx + " ty=" + fit.ty + " dRot=" + fit.rotDeltaDeg
                    + " newSize=" + fit.newW + "x" + fit.newH
                    + " mir=" + mb0x + "," + mb0y + "->" + fit.mirrorX + "," + fit.mirrorY);
            item.restoreTransform(preBake);
            onChanged.run();
        } else if (fit.rotDeltaDeg != 0f || fit.tx != 0f || fit.ty != 0f || mirChange
                || fit.newW != w || fit.newH != h) {
            float worstRes = 0f;
            for (float r : fit.residual) worstRes = Math.max(worstRes, Math.abs(r));
            FLog.d("PinBake", "baked id=" + item.getId() + " rot " + rot0 + "->" + newRot
                    + " centre " + bcx0 + "," + bcy0 + "->" + ncx + "," + ncy
                    + " size " + w + "x" + h + "->" + fit.newW + "x" + fit.newH
                    + " mir=" + mb0x + "," + mb0y + "->" + fit.mirrorX + "," + fit.mirrorY
                    + " worstRes=" + worstRes);
            TransformDiag.log("baked id=" + item.getId() + " rot " + rot0 + "->" + newRot
                    + " centre " + bcx0 + "," + bcy0 + "->" + ncx + "," + ncy
                    + " size " + w + "x" + h + "->" + fit.newW + "x" + fit.newH
                    + " mir=" + mb0x + "," + mb0y + "->" + fit.mirrorX + "," + fit.mirrorY
                    + " worstRes=" + worstRes);
        }
    }

    /**
     * Did the bake keep the picture? Both poses through the render equation —
     * screen = R(rot) . X + C + (I - R(rot)) . o, with the pin-aware pivot offset o —
     * the after-side re-read from the model so clamps and preset branches are accounted,
     * not assumed. Each side folds about its own visual pivot (pre-bake flags before,
     * whatever the bake left after). Over one pixel of drift anywhere and the bake is
     * rolled back.
     */
    private boolean verifyBake(long t, @NonNull RectF v,
                               float bcx0, float bcy0, float w0, float h0,
                               @NonNull float[] pins0, float rot0,
                               float size0, float sx0, float sy0,
                               float mb0x, float mb0y) {
        boolean centre = item.isRotationPivotCentre();
        float o0x = centre ? 0f : mb0x * item.pivotOffsetFromCentreX(w0, h0, pins0);
        float o0y = centre ? 0f : mb0y * item.pivotOffsetFromCentreY(w0, h0, pins0);
        float rotA = target.rotationDeg(t);
        float sizeA = target.sizeFraction(t);
        float sxA = item.animatedScaleX(t), syA = item.animatedScaleY(t);
        float denomW = size0 * sx0, denomH = size0 * sy0;
        if (!(denomW > 0f) || !(denomH > 0f)) return false;
        float wA = w0 * (sizeA * sxA) / denomW;
        float hA = h0 * (sizeA * syA) / denomH;
        if (!(wA > 0.5f) || !(hA > 0.5f)) return false;
        float cAx = v.left + target.centerX(t) * v.width();
        float cAy = v.top + target.centerY(t) * v.height();
        float[] pinsA = new float[CornerPin.SIZE];
        item.animatedCornerPin(t, pinsA);
        float mAx = item.mirrorSignX(), mAy = item.mirrorSignY();
        float oAx = centre ? 0f : mAx * item.pivotOffsetFromCentreX(wA, hA, pinsA);
        float oAy = centre ? 0f : mAy * item.pivotOffsetFromCentreY(wA, hA, pinsA);
        // The mirror rides the local corners on both sides (order of SPEC G: mirror, then
        // pin, then rotate). Before-side wears the pre-bake flags, after-side whatever the
        // bake left on the item — equal when the bake wrote nothing.
        //
        // SPEC P — both sides go through TransformQuad.renderQuad, the single copy of the
        // render equation, and BOTH are anchored at a POSE centre (bcx0 is now read from
        // target.centerX, not from the pivot-folded selection box). While this method kept its
        // own transcription and anchored its before-side at the folded box, it added the fold
        // twice on that side — the same mistake the bake was making, so the two cancelled and
        // the check certified a picture that had visibly moved. One equation, two poses.
        float[] before = new float[8], after = new float[8];
        TransformQuad.renderQuad(bcx0, bcy0, w0, h0, pins0, rot0, mb0x, mb0y, o0x, o0y, before);
        TransformQuad.renderQuad(cAx, cAy, wA, hA, pinsA, rotA, mAx, mAy, oAx, oAy, after);
        float worst = 0f;
        for (int i = 0; i < 4; i++) {
            worst = Math.max(worst, (float) Math.hypot(after[i * 2] - before[i * 2],
                    after[i * 2 + 1] - before[i * 2 + 1]));
            if (worst > 1f) return false;
        }
        return true;
    }

    // ── The two disjoint resets ──────────────────────────────────────────

    /**
     * RESET THE OBJECT — geometry only, and only geometry.
     *
     * <p>Every distortion goes (static offsets AND the eight animated tracks — leaving the tracks
     * behind would put the pin straight back on the next playhead tick, which would read as the
     * reset not working). Rotation goes and the object returns to the centre of the frame.</p>
     *
     * <p><b>SIZE is deliberately kept</b>, which is the one place this departs from the prototype.
     * In the prototype the object has a canonical default size to go back to; a real overlay does
     * not — its size is an authored value with no natural zero, and an image whose size silently
     * jumped would read as the reset having deleted work rather than undone a distortion. Handle
     * roles are not touched here at all: they belong to "reset all helpers", which lives in
     * {@link HandleModel#resetHelpers} and touches nothing in the project.</p>
     */
    @Override
    public void resetObjectGeometry() {
        item.clearCornerPin();
        // SPEC G: the mirror is geometry too — a reset that left the picture mirrored would
        // read as the reset not working. No tracks to remove: the flags are static.
        item.setFlipH(false);
        item.setFlipV(false);
        // SPEC H: the bend is geometry too, for the same reason — a reset that left the
        // picture bent would read as the reset not working. Null when already null, so
        // unbent pictures take the identical path they always did. The snapshot carries
        // the mesh, so undo restores the bend with the pose.
        if (item.getMesh() != null) item.setMesh(null);
        KeyframeSet ks = item.getKeyframes();
        for (String track : CornerPin.tracks()) ks.removeProperty(track);
        ks.removeProperty(KeyframeSet.ROTATION);
        item.setRotationDeg(0f);
        ks.removeProperty(KeyframeSet.X);
        ks.removeProperty(KeyframeSet.Y);
        item.setCenter(0.5f, 0.5f);
        onChanged.run();
    }

    /**
     * MIRROR THE PICTURE INSIDE ITS OWN BOX, ABOUT ITS OWN CENTRE LINE.
     *
     * <p>Unarmed, this toggles the mirror flag and touches nothing else. The pin offsets
     * live in the UNMIRRORED box frame with the mirror applied OUTSIDE them
     * ({@code local = R(d) . M . (B + off.s) + t}), so flag-only IS the central mirror —
     * and the stored rotation is negated, because mirroring conjugates rotation
     * ({@code M . R(d) = R(-d) . M}): the mirror of a 5° tilt visibly tilts -5°, and no
     * flag/pin choice at the old angle can draw it. The earlier flag-plus-negate variant
     * shifted the destination by twice the distortion (invisible on flat pictures, a
     * sideways shove on pinned ones — JoyRaptor 2026-09-07: corner flip "flipped on a side
     * axis" with the picture half out of its quad). Together with the mirror-aware
     * pivot fold, a flip is now the exact central mirror at every pivot, rotation and
     * distortion, and flipping twice restores the item bit-for-bit. The commit-time
     * bake then folds the affine part into the pose exactly as before.
     *
     * <p>Armed — or carrying a mesh bend — it keeps the old pin permutation, keyframed at the
     * playhead in the armed case: a static flag cannot be keyframed, so toggling it here would
     * flip every frame of an animation, not the one the finger is on, and the mesh stamp path
     * cannot draw a flag at all. (The bake still runs at commit, but walks away from a
     * mirror change on an armed or meshed item for the same reasons.)
     */
    @Override
    public void flip(boolean horizontal) {
        long t = now();
        if (!item.isImage()) return;
        item.animatedCornerPin(t, off);
        // A bend rides the pin's homography and the stamp path cannot draw a mirror flag:
        // a flagged flip would split the stamp from every other surface, while the pin
        // permutation below mirrors the stamp with everything else. Same reason the bake
        // walks away from a mirror change on a meshed item.
        if (!item.isArmed() && !item.hasMesh()) {
            // Pins untouched (see the class note): the magnitudes, the reach check and the
            // homography are exactly what already renders today. Never refuses, spends nothing.
            // The flag toggles only after the probe passes, so a refusal leaves no half-flip.
            if (!readBox(t)) return;
            if (!CornerPin.buildMatrix(probe, 0f, 0f, box.width(), box.height(), off)) return;
            if (horizontal) {
                item.setFlipH(!item.isFlipH());
            } else {
                item.setFlipV(!item.isFlipV());
            }
            applyPin(off, t);
        } else {
            float[] next = new float[CornerPin.SIZE];
            System.arraycopy(off, 0, next, 0, CornerPin.SIZE);
            if (horizontal) {
                // corner order TL, TR, BR, BL → left-hand corners are TL and BL
                float[] s = {1f, -1f, -1f, 1f};
                for (int c = 0; c < 4; c++) next[c * 2] = s[c] - off[c * 2];
            } else {
                float[] s = {1f, 1f, -1f, -1f};   // top corners are TL and TR
                for (int c = 0; c < 4; c++) next[c * 2 + 1] = s[c] - off[c * 2 + 1];
            }
            // A flip of an ALREADY hard-pinned picture can ask for an offset past what the tracks can
            // carry. Refuse it whole rather than clamping some corners and not others, which would
            // shear the picture instead of mirroring it.
            if (!withinRange(next)) return;
            if (!readBox(t)) return;
            if (!CornerPin.buildMatrix(probe, 0f, 0f, box.width(), box.height(), next)) return;
            applyPin(next, t);
        }
        // A mirror conjugates rotation (M.R(d) = R(-d).M): the stored angle follows the
        // picture through the mirror so the exact central mirror holds at any rotation.
        // Keyframe-aware via the shared target (armed drops a key at the playhead, unarmed
        // writes the static pose); skipped at exactly 0 so a flat flip writes no rotation.
        // (Armed/meshed keeps the pose-frame permutation above — an approximation for
        // animated pins at nonzero rotation, unchanged.)
        float curRot = target.rotationDeg(t);
        if (curRot != 0f) target.rotateTo(-curRot, t);
        FLog.d("PinFlip", "flip id=" + item.getId() + (horizontal ? " H" : " V")
                + " armed=" + item.isArmed() + " mesh=" + item.hasMesh()
                + " rot=" + curRot + " flags=" + item.isFlipH() + "," + item.isFlipV());
        TransformDiag.log("flip id=" + item.getId() + (horizontal ? " H" : " V")
                + " armed=" + item.isArmed() + " mesh=" + item.hasMesh()
                + " rot=" + curRot + " flags=" + item.isFlipH() + "," + item.isFlipV());
    }

    /** Does this item carry any distortion right now? Drives the entry point's on/off look. */
    public boolean isDistorted() { return item.hasCornerPin() || item.hasMirror(); }

    // ── SPEC H: bend (mesh) edit seam ─────────────────────────────────────
    //
    // The net's dots live in the picture's OWN unit space and are re-projected through the
    // CURRENT quad every frame (MeshProjection), so a structural edit carries the bend instead
    // of wiping it. One finger drags one dot; the guard refuses folds; commit writes ONCE.
    //
    // Undo: beginBendGesture snapshots (TransformSnapshot already deep-copies the mesh), and
    // commitBendGesture records ONE step through the same target.commit every other gesture
    // uses. Unarmed the pose lives in the static handles (no track, no drawer diamond for a
    // static bend); armed it is put ONCE to the pose track at the local clock. Either way one
    // drag is one undo press.
    //
    // All scratch here is UI-thread only (the GL threads keep their own engine/scratch inside
    // MeshStampGl); nothing here is ever shared across threads.

    /** UI-thread guard scratch. Bound to the spec's topology before every check. */
    @NonNull
    private final com.fadcam.ui.faditor.transform.mesh.MeshBuffers bendScratch =
            new com.fadcam.ui.faditor.transform.mesh.MeshBuffers();
    /** UI-thread deformer, built lazily per kind (never shared with the GL threads). */
    @androidx.annotation.Nullable
    private com.fadcam.ui.faditor.transform.mesh.MeshDeformer bendDeformer;
    /**
     * UI-thread pose working space, sized EXACTLY to the topology's arity. Never shared.
     *
     * <p>SPEC O — these used to be fixed {@code float[50]} buffers (the largest lattice's arity),
     * which silently disabled the whole tool: {@link
     * com.fadcam.ui.faditor.transform.mesh.MeshDeformer#solve} takes a pose whose length IS the
     * arity — {@code MeshEngine} resizes to {@code topo.handleArity()} for exactly that reason —
     * so a 50-float candidate handed to a 3x3 (18-float) lattice made {@code LatticeDeformer.solve}
     * return false, {@code MeshGuard.accepts} refuse, and every drag frame do nothing at all.
     * Sized on the arity, so it allocates once per topology and never per frame.</p>
     */
    @NonNull
    private float[] bendPose = new float[0];
    /** UI-thread candidate pose (guard-checked before commit to the live handles). */
    @NonNull
    private float[] bendCand = new float[0];
    @NonNull
    private final float[] bendOut2 = new float[2];
    /**
     * The refusal already reported for the gesture in flight, so a refused drag says WHY once
     * instead of once per frame (TransformDiag is a discrete-gesture recorder, never a spammer).
     */
    @androidx.annotation.Nullable
    private String bendSaid;
    /** Whether this gesture already recorded its one "bend applied" line. */
    private boolean bendAppliedSaid;

    /** Largest pose any registered topology may ask for. A sanity bound, not a lattice fact. */
    private static final int BEND_MAX_ARITY = 4096;

    /** Grow/shrink a pose buffer to exactly {@code arity}. Allocation-free when it already is. */
    @NonNull
    private static float[] bendFit(@NonNull float[] a, int arity) {
        return a.length == arity ? a : new float[arity];
    }

    /**
     * SPEC O — no silent refusal survives in the bend path. Records the reason ONCE per gesture
     * (the same frame-by-frame reason is not news) and returns false so callers stay one-liners.
     */
    private boolean bendRefuse(@NonNull String why) {
        if (!why.equals(bendSaid)) {
            bendSaid = why;
            TransformDiag.log("bend refused: " + why);
        }
        return false;
    }

    @Override
    public boolean supportsBend() { return item.isImage(); }

    @Override
    public boolean hasBend() { return item.hasMesh(); }

    /**
     * The net the tool WOULD edit: the stored topology, or the L2 default before the first
     * drag. Reporting the default keeps the rest grid drawable and grabbable with no model
     * change — opening the tool writes nothing (toJson stays null for identity).
     */
    @NonNull
    private static final com.fadcam.ui.faditor.transform.mesh.LatticeTopology BEND_DEFAULT =
            new com.fadcam.ui.faditor.transform.mesh.LatticeTopology(
                    com.fadcam.ui.faditor.transform.mesh.LatticeTopology.L2);

    @Override
    public int bendHandleCount() {
        try {
            com.fadcam.ui.faditor.transform.mesh.MeshWarpSpec s = item.getMesh();
            if (s == null || s.topology() == null) return BEND_DEFAULT.handleCount();
            return Math.max(0, s.topology().handleCount());
        } catch (Exception ignored) {
            return 0;
        }
    }

    @Override
    public int bendGridSide() {
        try {
            com.fadcam.ui.faditor.transform.mesh.MeshWarpSpec s = item.getMesh();
            com.fadcam.ui.faditor.transform.mesh.MeshTopology t =
                    (s == null || s.topology() == null) ? BEND_DEFAULT : s.topology();
            if (t instanceof com.fadcam.ui.faditor.transform.mesh.LatticeTopology) {
                return ((com.fadcam.ui.faditor.transform.mesh.LatticeTopology) t).side();
            }
        } catch (Exception ignored) { }
        return 0;
    }

    /**
     * Ensure a bend spec exists (L2 3x3 lattice — the approved net), creating it when the
     * tool is opened on an unbent picture. Creating here (not on selection) keeps opening
     * the tool byte-identical: MeshWarpSpec.toJson writes nothing for an identity pose.
     */
    @androidx.annotation.Nullable
    private com.fadcam.ui.faditor.transform.mesh.MeshWarpSpec bendEnsureSpec() {
        try {
            com.fadcam.ui.faditor.transform.mesh.MeshWarpSpec s = item.getMesh();
            if (s == null || s.topology() == null) {
                s = com.fadcam.ui.faditor.transform.mesh.MeshWarpSpec.lattice(
                        com.fadcam.ui.faditor.transform.mesh.LatticeTopology.L2);
                item.setMesh(s);
            }
            item.installMeshCurve();
            return item.getMesh();
        } catch (Exception e) {
            TransformDiag.log("bend ensureSpec threw " + e.getClass().getSimpleName());
            return null;
        }
    }

    /**
     * The pose to draw/drag: the track value at the playhead when present, else static.
     * Writes directly into {@code out} (caller-owned, at least arity long) — no allocation.
     */
    private int bendCurrentPose(
            @NonNull com.fadcam.ui.faditor.transform.mesh.MeshWarpSpec s,
            @NonNull float[] out) {
        try {
            int arity = s.arity();
            if (arity <= 0 || out.length < arity) return 0;
            long localMs = item.meshLocalTime(now());
            if (s.handlesAt(localMs, out)) return arity;
            float[] h = s.handles();
            if (h != null && h.length >= arity) {
                System.arraycopy(h, 0, out, 0, arity);
                return arity;
            }
        } catch (Exception ignored) { }
        return 0;
    }

    @androidx.annotation.Nullable
    private com.fadcam.ui.faditor.transform.mesh.MeshDeformer bendDeformerFor(
            @NonNull com.fadcam.ui.faditor.transform.mesh.MeshTopology topo) {
        com.fadcam.ui.faditor.transform.mesh.MeshDeformer d = bendDeformer;
        if (d == null || !d.supports(topo)) {
            d = com.fadcam.ui.faditor.transform.mesh.MeshTopologies.deformerFor(topo);
            if (d != null) bendDeformer = d;
        }
        return bendDeformer;
    }

    @Override
    public boolean bendHandlePosition(int i, @NonNull float[] h, @NonNull float[] out2) {
        try {
            com.fadcam.ui.faditor.transform.mesh.MeshWarpSpec s = item.getMesh();
            com.fadcam.ui.faditor.transform.mesh.MeshTopology topo =
                    (s == null || s.topology() == null) ? BEND_DEFAULT : s.topology();
            if (i < 0 || i >= topo.handleCount()) return false;
            int arity = topo.handleArity();
            if (arity <= 0 || arity > BEND_MAX_ARITY) return false;
            bendPose = bendFit(bendPose, arity);
            if (s != null && bendCurrentPose(s, bendPose) == arity) {
                return com.fadcam.ui.faditor.transform.mesh.MeshProjection.projectHandle(
                        topo, bendPose, i, h, out2);
            }
            // No spec yet (tool opened, nothing dragged): rest grid, zero nudges.
            java.util.Arrays.fill(bendPose, 0, arity, 0f);
            return com.fadcam.ui.faditor.transform.mesh.MeshProjection.projectHandle(
                    topo, bendPose, i, h, out2);
        } catch (Exception ignored) {
            return false;
        }
    }

    @Override
    public boolean bendDragTo(int i, @NonNull float[] hInv, float stageX, float stageY) {
        try {
            if (!Float.isFinite(stageX) || !Float.isFinite(stageY)) {
                return bendRefuse("stage point not finite");
            }
            com.fadcam.ui.faditor.transform.mesh.MeshWarpSpec s = bendEnsureSpec();
            if (s == null || s.topology() == null) return bendRefuse("no spec/topology");
            com.fadcam.ui.faditor.transform.mesh.MeshTopology topo = s.topology();
            if (i < 0 || i >= topo.handleCount()) {
                return bendRefuse("handle " + i + " outside 0.." + (topo.handleCount() - 1));
            }
            com.fadcam.ui.faditor.transform.mesh.MeshDeformer deformer =
                    bendDeformerFor(topo);
            if (deformer == null) return bendRefuse("no deformer for kind " + topo.kind());
            if (!deformer.supports(topo)) {
                return bendRefuse("deformer rejects kind " + topo.kind());
            }
            int arity = s.arity();
            if (arity <= 0 || arity > BEND_MAX_ARITY) return bendRefuse("arity " + arity);
            // EXACTLY the arity, never merely big enough — see the field note above; a longer
            // pose is what made this whole tool a no-op.
            bendPose = bendFit(bendPose, arity);
            bendCand = bendFit(bendCand, arity);
            if (bendCurrentPose(s, bendPose) != arity) {
                java.util.Arrays.fill(bendPose, 0, arity, 0f);
            }
            if (!com.fadcam.ui.faditor.transform.mesh.MeshProjection.dragToHandle(
                    topo, deformer, i, hInv, stageX, stageY, bendOut2)) {
                return bendRefuse("degenerate inverse homography");
            }
            System.arraycopy(bendPose, 0, bendCand, 0, arity);
            bendCand[i * 2] = bendOut2[0];
            bendCand[i * 2 + 1] = bendOut2[1];
            // The guard measures the SOLVED triangles, not the handles: bind first, or the
            // scratch has no rest shape and every fold would pass. Refusal simply stops the
            // drag — always recoverable, unlike rendering through a fold.
            bendScratch.bind(topo);
            if (!com.fadcam.ui.faditor.transform.mesh.MeshGuard.accepts(
                    topo, deformer, bendScratch, bendCand)) {
                return bendRefuse("guard: fold/crush at handle " + i);
            }
            float[] live = s.handles();
            if (live == null || live.length != arity) {
                return bendRefuse("live pose " + (live == null ? "null" : live.length)
                        + " != arity " + arity);
            }
            System.arraycopy(bendCand, 0, live, 0, arity);
            if (!bendAppliedSaid) {
                // One line per gesture, on the FIRST frame that actually deformed anything.
                bendAppliedSaid = true;
                TransformDiag.log("bend applied i=" + i + " du=" + bendOut2[0]
                        + " dv=" + bendOut2[1] + " arity=" + arity + " kind=" + topo.kind());
            }
            onChanged.run();
            return true;
        } catch (Exception e) {
            return bendRefuse("threw " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    @Override
    public void beginBendGesture() {
        // Ensure BEFORE the snapshot: the first bend's undo then restores "no bend at all".
        bendSaid = null;
        bendAppliedSaid = false;
        bendEnsureSpec();
        beginGesture();
    }

    @Override
    public void commitBendGesture(@NonNull String what) {
        try {
            // Armed: the pose at the playhead becomes ONE track key (local clock, like every
            // other animated property). Unarmed: the static handles already hold it — no track,
            // no drawer diamond for a static bend. Either way exactly one write, one undo.
            if (item.isArmed()) {
                com.fadcam.ui.faditor.transform.mesh.MeshWarpSpec s = item.getMesh();
                if (s != null && s.topology() != null) {
                    int arity = s.arity();
                    float[] live = s.handles();
                    if (arity > 0 && live != null && live.length == arity) {
                        long localMs = item.meshLocalTime(now());
                        s.ensureTrack().put(localMs, live,
                                com.fadcam.ui.faditor.transform.mesh.MeshPoseTrack
                                        .DEFAULT_EASING);
                        item.installMeshCurve();
                    }
                }
            }
        } catch (Exception e) {
            TransformDiag.log("bend commit threw " + e.getClass().getSimpleName());
        }
        // One line per gesture: did the drag actually leave a warp on the model? This is the
        // line SPEC O's repro was missing, and it is what "mesh" in project.json is written from.
        TransformDiag.log("bend commit hasMesh=" + item.hasMesh()
                + " armed=" + item.isArmed());
        commitGesture(what);
    }

}
