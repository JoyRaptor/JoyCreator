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
 *   <li>every distortion — scale, tilt, free, fold, flip — → the EIGHT {@link CornerPin} tracks,
 *       drawn in the preview by {@code CornerPinImageView} / the GL chain's pin uniforms and in the
 *       export by {@code ImageOverlayDraw} through the SAME {@link CornerPin#buildMatrix}.</li>
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
        for (int i = 0; i < 4; i++) {
            float px = baseX[i] + off[i * 2] * w;
            float py = baseY[i] + off[i * 2 + 1] * h;
            float dx = px - cx, dy = py - cy;
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
        float[] baseY = {bcy - h / 2f, bcy - h / 2f, bcy + h / 2f, bcy + h / 2f};
        float[] next = new float[CornerPin.SIZE];
        for (int i = 0; i < 4; i++) {
            float dx = quad8[i * 2] - pvx, dy = quad8[i * 2 + 1] - pvy;
            float ux = pvx + uc * dx - us * dy;
            float uy = pvy + us * dx + uc * dy;
            next[i * 2] = (ux - baseX[i]) / w;
            next[i * 2 + 1] = (uy - baseY[i]) / h;
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

    /** Every offset inside {@link CornerPin#MAX_OFFSET} — the reach the tracks can serialise. */
    private static boolean withinRange(float[] pin) {
        for (float v : pin) {
            if (Float.isNaN(v) || Float.isInfinite(v)) return false;
            if (Math.abs(v) > CornerPin.MAX_OFFSET) return false;
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
    public void commitGesture(@NonNull String what) { target.commit(what); }

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
     * <p>Expressed purely as a corner-pin permutation, which is why there is no new model field, no
     * new preview branch and no new export branch for it: a mirror is the homography that sends the
     * picture's top-left to the box's top-right, and the pin is exactly a homography on four
     * corners. Reflecting the CURRENT destination quad rather than swapping corner slots is what
     * makes it correct on an already-distorted picture and exactly involutive on any picture — two
     * flips return the original offsets to the float.</p>
     *
     * <p>Horizontal: {@code dx' = s - dx} with {@code s = +1} on the left-hand corners and
     * {@code -1} on the right-hand ones. Vertical is the same statement about {@code dy} and the
     * top/bottom corners. Both leave the other axis alone.</p>
     */
    @Override
    public void flip(boolean horizontal) {
        long t = now();
        item.animatedCornerPin(t, off);
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
    public boolean isDistorted() { return item.hasCornerPin(); }
}
