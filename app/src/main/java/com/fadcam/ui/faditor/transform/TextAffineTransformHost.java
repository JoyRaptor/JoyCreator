package com.fadcam.ui.faditor.transform;

import android.graphics.RectF;

import androidx.annotation.NonNull;

import com.fadcam.ui.faditor.model.TextOverlayItem;
import com.fadcam.ui.faditor.overlay.PreviewHandlesOverlay;

/**
 * Affine-only host for TEXT overlays (non-image TextOverlayItem).
 *
 * <p>Reads the box from the existing {@link PreviewHandlesOverlay.Target} so the box math,
 * inset handling and below/above layer search are not duplicated, and writes back through the
 * SAME target so armed/keyframe, preset, travel-clamp and drawer-refresh branches are reused.
 * The only new work is {@link #writeQuad}, which decomposes an affine quad into centre /
 * uniform-scale / rotation and refuses non-affine shapes (shear, trapezoid, fold).
 *
 * <p>Affine-only because text has no pinned render path in either surface (see SPEC D).
 */
public final class TextAffineTransformHost implements TransformOverlayView.Host {

    public interface Playhead { long timelineMs(); }

    @NonNull private final TextOverlayItem item;
    @NonNull private final PreviewHandlesOverlay.Target target;
    @NonNull private final Playhead playhead;
    @NonNull private final Runnable onChanged;

    private final RectF box = new RectF();
    private final float[] tmp = new float[2];

    private float startCx, startCy, startSize, startRot;
    private float startW, startH;
    private float startAngle;

    public TextAffineTransformHost(@NonNull TextOverlayItem item,
                                   @NonNull PreviewHandlesOverlay.Target target,
                                   @NonNull Playhead playhead,
                                   @NonNull Runnable onChanged) {
        this.item = item;
        this.target = target;
        this.playhead = playhead;
        this.onChanged = onChanged;
    }

    private long now() { return playhead.timelineMs(); }

    private boolean readBox(long t) {
        if (!target.frame(t, box)) return false;
        return box.width() > 0.5f && box.height() > 0.5f;
    }

    @Override
    public boolean readQuad(@NonNull float[] outQuad8) {
        long t = now();
        if (!readBox(t)) return false;
        float cx = box.centerX(), cy = box.centerY();
        float w = box.width(), h = box.height();
        float rot = target.rotationDeg(t);
        double rad = Math.toRadians(rot);
        float cs = (float) Math.cos(rad), sn = (float) Math.sin(rad);
        float[] bx = {box.left, box.right, box.right, box.left};
        float[] by = {box.top, box.top, box.bottom, box.bottom};
        for (int i = 0; i < 4; i++) {
            float dx = bx[i] - cx, dy = by[i] - cy;
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

    @Override
    public void readFoldPivot(@NonNull float[] outXY) {
        readPivot(outXY);
    }

    @Override
    public float currentRotationDeg() { return target.rotationDeg(now()); }

    @Override
    @NonNull
    public RectF videoRect() { return target.videoRect(); }

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
            // angle of top edge at grab time
            float dx = box.right - box.left; // w before rotation, but box is unrotated
            // For unrotated box, top edge is horizontal, angle = startRot
            startAngle = startRot;
        } else {
            startW = 0f; startH = 0f; startAngle = startRot;
        }
        target.beginGesture();
    }

    @Override
    public boolean writeQuad(@NonNull float[] quad8) {
        if (!TransformQuad.isValid(quad8)) return false;
        if (startW < 0.5f || startH < 0.5f) return false;
        long t = now();
        // --- affine validation: parallelogram + perpendicularity ---
        // Vectors: top = TR-TL, right = BR-TR
        float tlx = quad8[0], tly = quad8[1];
        float trx = quad8[2], try_ = quad8[3];
        float brx = quad8[4], bry = quad8[5];
        float blx = quad8[6], bly = quad8[7];
        float topX = trx - tlx, topY = try_ - tly;
        float rightX = brx - trx, rightY = bry - try_;
        float bottomX = blx - brx, bottomY = bly - bry;
        float leftX = tlx - blx, leftY = tly - bly;

        // Parallelogram: TL + top + right should be BR
        float expBrX = tlx + topX + rightX;
        float expBrY = tly + topY + rightY;
        float span = Math.max(1e-4f, Math.max(Math.abs(trx - blx), Math.abs(try_ - bly)));
        if (Math.abs(expBrX - brx) > span * 0.02f || Math.abs(expBrY - bry) > span * 0.02f) {
            // Check also other corner: TL + top - left? Simpler: require opposite edges equal
            // Use bottom vs top, left vs right
            if (Math.abs(topX + bottomX) > span * 0.02f || Math.abs(topY + bottomY) > span * 0.02f) return false;
            if (Math.abs(rightX + leftX) > span * 0.02f || Math.abs(rightY + leftY) > span * 0.02f) return false;
        }
        // Perpendicularity: adjacent edges dot ~0
        float lenTop = (float) Math.hypot(topX, topY);
        float lenRight = (float) Math.hypot(rightX, rightY);
        if (lenTop < 1f || lenRight < 1f) return false;
        float dot = (topX * rightX + topY * rightY) / (lenTop * lenRight);
        if (Math.abs(dot) > 0.02f) return false;

        // Extract centre, rotation, scale
        float[] cen = tmp;
        TransformQuad.centroid(quad8, cen);
        float cx = cen[0], cy = cen[1];
        float curW = (lenTop + (float)Math.hypot(bottomX, bottomY)) * 0.5f;
        float curH = (lenRight + (float)Math.hypot(leftX, leftY)) * 0.5f;
        float factorW = curW / Math.max(1f, startW);
        float factorH = curH / Math.max(1f, startH);
        // Heuristic for uniform scale: if one axis barely changed, use the other
        float factor;
        boolean wIdle = Math.abs(factorW - 1f) < 0.015f;
        boolean hIdle = Math.abs(factorH - 1f) < 0.015f;
        if (wIdle && !hIdle) factor = factorH;
        else if (hIdle && !wIdle) factor = factorW;
        else factor = (factorW + factorH) * 0.5f;
        factor = TransformQuad.clamp(factor, TransformQuad.MIN_FACTOR, TransformQuad.MAX_FACTOR);
        float newSize = Math.max(0.02f, startSize * factor);
        float newAngle = (float) Math.toDegrees(Math.atan2(topY, topX));
        // Normalise delta to avoid 360 jump, but we have absolute angle
        // Keep winding consistent with start: choose nearest equivalent
        while (newAngle - startAngle > 180f) newAngle -= 360f;
        while (newAngle - startAngle < -180f) newAngle += 360f;
        RectF v = target.videoRect();
        if (v.width() <= 0f || v.height() <= 0f) return false;
        // Order: size/angle first, position last (see CornerPinTransformHost)
        target.scaleTo(newSize, t);
        target.rotateTo(newAngle, t);
        float normCx = TransformQuad.clamp((cx - v.left) / v.width(), -2f, 3f);
        float normCy = TransformQuad.clamp((cy - v.top) / v.height(), -2f, 3f);
        target.moveTo(normCx, normCy, t);
        // SPEC J: the three target writes above already asked for a GL resync each
        // (textHandlesTarget → refreshTextAfterHandleWrite → requestGlPreviewResync,
        // coalesced to one rebuild per frame), but the host contract is that EVERY
        // write path ends in onChanged, so a future target that stops refreshing
        // cannot silently reintroduce JoyRaptor's stale-picture bug. Coalesced: free.
        onChanged.run();
        return true;
    }

    @Override
    public void writeTranslate(float dxPx, float dyPx) {
        RectF v = target.videoRect();
        if (v.width() <= 0f || v.height() <= 0f) return;
        target.moveTo(startCx + dxPx / v.width(), startCy + dyPx / v.height(), now());
        // SPEC J — every write path ends in onChanged. The target's own writes already
        // refresh (moveTo → refreshTextAfterHandleWrite → coalesced GL resync); this is the
        // host-layer backstop so a future target that stops refreshing cannot silently
        // reintroduce the stale-picture bug. Coalesced: free.
        onChanged.run();
    }

    @Override
    public void writeSimilarity(float factor, float deltaDeg, float cxPx, float cyPx) {
        long t = now();
        RectF v = target.videoRect();
        if (v.width() <= 0f || v.height() <= 0f) return;
        target.scaleTo(Math.max(0.02f, startSize * factor), t);
        target.rotateTo(startRot + deltaDeg, t);
        target.moveTo((cxPx - v.left) / v.width(), (cyPx - v.top) / v.height(), t);
        onChanged.run();   // SPEC J — every write path ends in onChanged (coalesced)
    }

    @Override
    public void writeRotation(float deg) {
        target.rotateTo(deg, now());
        onChanged.run();   // SPEC J — every write path ends in onChanged (coalesced)
    }

    @Override
    public void commitGesture(@NonNull String what) { target.commit(what); }

    @Override
    public void resetObjectGeometry() {
        // Keep size (image convention) — text size is authored work with no canonical default.
        com.fadcam.ui.faditor.keyframe.KeyframeSet ks = item.getKeyframes();
        if (ks != null) {
            ks.removeProperty(com.fadcam.ui.faditor.keyframe.KeyframeSet.X);
            ks.removeProperty(com.fadcam.ui.faditor.keyframe.KeyframeSet.Y);
            ks.removeProperty(com.fadcam.ui.faditor.keyframe.KeyframeSet.ROTATION);
        }
        item.setCenter(0.5f, 0.5f);
        item.setRotationDeg(0f);
        // Clear any stale corner pin (should be flat for text, but defensively)
        item.clearCornerPin();
        if (item.getKeyframes() != null) {
            for (String tr : com.fadcam.ui.faditor.model.CornerPin.tracks()) {
                item.getKeyframes().removeProperty(tr);
            }
        }
        onChanged.run();
    }

    @Override
    public void flip(boolean horizontal) {
        // No honest render path for flipped text (TextBoxView has no mirror) — inert.
    }
}
