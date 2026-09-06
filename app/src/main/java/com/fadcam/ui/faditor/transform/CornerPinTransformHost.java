package com.fadcam.ui.faditor.transform;

import android.graphics.Matrix;
import android.graphics.RectF;

import androidx.annotation.NonNull;

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
        float th = target.rotationDeg(t);
        double rad = Math.toRadians(th);
        float pc = (float) Math.cos(rad), ps = (float) Math.sin(rad);
        item.animatedCornerPin(t, off);
        float dX = item.pivotOffsetFromCentreX(box.width(), box.height(), off);
        float dY = item.pivotOffsetFromCentreY(box.width(), box.height(), off);
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
        float pvx = box.centerX(), pvy = box.centerY();
        float bcx = box.centerX(), bcy = box.centerY();
        if (folded) {
            double radP = Math.toRadians(th);
            float pc = (float) Math.cos(radP), ps = (float) Math.sin(radP);
            float dX = item.pivotOffsetFromCentreX(w, h, off);
            float dY = item.pivotOffsetFromCentreY(w, h, off);
            // The pivot point is fold-invariant; on the presented rect it sits at
            // centre + R(θ)·δ.
            pvx = bcx + pc * dX - ps * dY;
            pvy = bcy + ps * dX + pc * dY;
            // POSE box centre = the presented centre un-folded about the pivot.
            float dx = bcx - pvx, dy = bcy - pvy;
            bcx = pvx + uc * dx - us * dy;
            bcy = pvy + us * dx + pc * dy;
        }
        float[] baseX = {bcx - w / 2f, bcx + w / 2f, bcx + w / 2f, bcx - w / 2f};
        float[] baseY = {bcy - h / 2f, bcy - h / 2f, bcy + h / 2f, bcy - h / 2f};
        float[] next = new float[CornerPin.SIZE];
        // SPEC G — the pin lives in the UNMIRRORED box frame however the flags stand, so the
        // dragged (mirrored) quad is un-mirrored about the pose centre on the way in. The
        // pivot fold above is untouched: mirroring about the centre commutes with it (the
        // pivot offset cancels exactly). Unmirrored this is the same two lines.
        float smx = item.mirrorSignX(), smy = item.mirrorSignY();
        for (int i = 0; i < 4; i++) {
            float dx = quad8[i * 2] - pvx, dy = quad8[i * 2 + 1] - pvy;
            float ux = pvx + uc * dx - us * dy;
            float uy = pvy + us * dx + uc * dy;
            next[i * 2] = (smx * (ux - bcx) - (baseX[i] - bcx)) / w;
            next[i * 2 + 1] = (smy * (uy - bcy) - (baseY[i] - bcy)) / h;
        }
        if (!withinRange(next)) return false;
        // REFUSE, DO NOT CLAMP, when the homography will not solve. Clamping would let the finger
        // keep dragging while the picture silently stopped following it — the shape on screen and
        // the shape in the model would then be two different things, which is the whole class of
        // bug the overlay's rollback exists to avoid. Refusing makes the drag simply stop.
        if (!CornerPin.buildMatrix(probe, 0f, 0f, w, h, next)) return false;
        applyPin(next, t);
        return true;
    }

    /** Every offset inside {@link CornerPin#MAX_OFFSET} — the reach the tracks can serialise.
     * A 1e-4 dust allowance: a fold from flat lands at exactly ±MAX_OFFSET through float
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
            float dX = item.pivotOffsetFromCentreX(wN, hN, off);
            float dY = item.pivotOffsetFromCentreY(wN, hN, off);
            double rad = Math.toRadians(startRot + deltaDeg);
            float cs = (float) Math.cos(rad), sn = (float) Math.sin(rad);
            px = cxPx - (dX - (cs * dX - sn * dY));
            py = cyPx - (dY - (sn * dX + cs * dY));
        }
        target.moveTo((px - v.left) / v.width(), (py - v.top) / v.height(), t);
    }

    @Override
    public void writeRotation(float deg) { target.rotateTo(deg, now()); }

    @Override
    public void commitGesture(@NonNull String what) { tryNormalizeOnCommit(); target.commit(what); }

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
     * move/rotate/pinch commit never sprays keys it did not mean.
     */
    private void tryNormalizeOnCommit() {
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
        float bcx0 = box.centerX(), bcy0 = box.centerY();
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
        double rad0 = Math.toRadians(rot0);
        float c0 = (float) Math.cos(rad0), s0 = (float) Math.sin(rad0);
        float ncx = bcx0 + c0 * fit.tx - s0 * fit.ty;
        float ncy = bcy0 + s0 * fit.tx + c0 * fit.ty;
        float o0x = item.pivotOffsetFromCentreX(w, h, pins0);
        float o0y = item.pivotOffsetFromCentreY(w, h, pins0);
        float o1x = item.pivotOffsetFromCentreX(fit.newW, fit.newH, fit.residual);
        float o1y = item.pivotOffsetFromCentreY(fit.newW, fit.newH, fit.residual);
        double rad1 = Math.toRadians(newRot);
        float c1 = (float) Math.cos(rad1), s1 = (float) Math.sin(rad1);
        ncx += (o0x - (c0 * o0x - s0 * o0y)) - (o1x - (c1 * o1x - s1 * o1y));
        ncy += (o0y - (s0 * o0x + c0 * o0y)) - (o1y - (s1 * o1x + c1 * o1y));
        // The post-gesture pose, so the verify can roll back exactly the bake below while
        // keeping the gesture itself.
        TextOverlayItem.TransformSnapshot preBake = item.snapshotTransform();
        float mb0x = item.mirrorSignX(), mb0y = item.mirrorSignY();
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
            item.restoreTransform(preBake);
            onChanged.run();
        }
    }

    /**
     * Did the bake keep the picture? Both poses through the render equation —
     * screen = R(rot) . X + C + (I - R(rot)) . o, with the pin-aware pivot offset o — the
     * after-side re-read from the model so clamps and preset branches are accounted, not
     * assumed. Over one pixel of drift anywhere and the bake is rolled back.
     */
    private boolean verifyBake(long t, @NonNull RectF v,
                               float bcx0, float bcy0, float w0, float h0,
                               @NonNull float[] pins0, float rot0,
                               float size0, float sx0, float sy0,
                               float mb0x, float mb0y) {
        float o0x = item.pivotOffsetFromCentreX(w0, h0, pins0);
        float o0y = item.pivotOffsetFromCentreY(w0, h0, pins0);
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
        float oAx = item.pivotOffsetFromCentreX(wA, hA, pinsA);
        float oAy = item.pivotOffsetFromCentreY(wA, hA, pinsA);
        double r0 = Math.toRadians(rot0), rA = Math.toRadians(rotA);
        float c0 = (float) Math.cos(r0), s0 = (float) Math.sin(r0);
        float cA = (float) Math.cos(rA), sA = (float) Math.sin(rA);
        // The mirror rides the local corners on both sides (order of SPEC G: mirror, then
        // pin, then rotate). Before-side wears the pre-bake flags, after-side whatever the
        // bake left on the item — equal when the bake wrote nothing.
        float mAx = item.mirrorSignX(), mAy = item.mirrorSignY();
        float worst = 0f;
        for (int i = 0; i < 4; i++) {
            float qx0 = (i == 0 || i == 3) ? -w0 / 2f : w0 / 2f;
            float qy0 = (i < 2) ? -h0 / 2f : h0 / 2f;
            float lx0 = mb0x * (qx0 + pins0[i * 2] * w0);
            float ly0 = mb0y * (qy0 + pins0[i * 2 + 1] * h0);
            float px0 = bcx0 + c0 * lx0 - s0 * ly0 + (o0x - (c0 * o0x - s0 * o0y));
            float py0 = bcy0 + s0 * lx0 + c0 * ly0 + (o0y - (s0 * o0x + c0 * o0y));
            float qxA = (i == 0 || i == 3) ? -wA / 2f : wA / 2f;
            float qyA = (i < 2) ? -hA / 2f : hA / 2f;
            float lxA = mAx * (qxA + pinsA[i * 2] * wA);
            float lyA = mAy * (qyA + pinsA[i * 2 + 1] * hA);
            float pxA = cAx + cA * lxA - sA * lyA + (oAx - (cA * oAx - sA * oAy));
            float pyA = cAy + sA * lxA + cA * lyA + (oAy - (sA * oAx + cA * oAy));
            worst = Math.max(worst, (float) Math.hypot(pxA - px0, pyA - py0));
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
     * MIRROR THE PICTURE INSIDE ITS OWN BOX.
     *
     * <p>SPEC G: unarmed, this toggles the mirror flag and negates the pin on that axis —
     * reflecting the CURRENT destination quad, distortion included, exactly as the old pin
     * permutation did, but spending no budget and never refusing. The commit-time bake then
     * folds the affine part into the pose and the picture never moves.
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
            if (horizontal) {
                item.setFlipH(!item.isFlipH());
                for (int c = 0; c < 4; c++) off[c * 2] = -off[c * 2];
            } else {
                item.setFlipV(!item.isFlipV());
                for (int c = 0; c < 4; c++) off[c * 2 + 1] = -off[c * 2 + 1];
            }
            // Negation preserves magnitude, so the reach check cannot newly fail, and a
            // mirror of a solvable homography is solvable. Never refuses, spends nothing.
            if (!readBox(t)) return;
            if (!CornerPin.buildMatrix(probe, 0f, 0f, box.width(), box.height(), off)) return;
            applyPin(off, t);
            return;
        }
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

    /** Does this item carry any distortion right now? Drives the entry point's on/off look. */
    public boolean isDistorted() { return item.hasCornerPin() || item.hasMirror(); }
}
