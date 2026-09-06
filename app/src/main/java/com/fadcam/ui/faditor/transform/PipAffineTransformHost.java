package com.fadcam.ui.faditor.transform;

import android.graphics.RectF;

import androidx.annotation.NonNull;

import com.fadcam.ui.faditor.compositor.OverlayVideoPreviewView;
import com.fadcam.ui.faditor.keyframe.KeyframeSet;
import com.fadcam.ui.faditor.model.Clip;
import com.fadcam.ui.faditor.overlay.PreviewHandlesOverlay;

/**
 * Affine-only host for PiP video clips (Clip.isOverlayClip).
 *
 * <p>Same structure as {@link TextAffineTransformHost}: reads the unrotated box via the existing
 * Pip target, decomposes affine quads, refuses shear/trapezoid, and delegates writes through the
 * target so armed/keyframe, budget and undo branches are reused.
 *
 * <p>Affine-only because PipFrameOverlay (export) and FxPreviewTextureView.Pip (preview) have no
 * corner-pin channel — a pinned PiP would preview flat and export flat while the handles showed a
 * distorted quad, i.e. the top-severity preview/export divergence.
 */
public final class PipAffineTransformHost implements TransformOverlayView.Host {

    public interface Playhead { long timelineMs(); }

    @NonNull private final Clip clip;
    @NonNull private final PreviewHandlesOverlay.Target target;
    @NonNull private final Playhead playhead;
    @NonNull private final Runnable onChanged;

    private final RectF box = new RectF();
    private final float[] tmp = new float[2];

    private float startCx, startCy, startSize, startRot;
    private float startW, startH;

    public PipAffineTransformHost(@NonNull Clip clip,
                                  @NonNull PreviewHandlesOverlay.Target target,
                                  @NonNull Playhead playhead,
                                  @NonNull Runnable onChanged) {
        this.clip = clip;
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
    public void readFoldPivot(@NonNull float[] outXY) { readPivot(outXY); }

    @Override
    public float currentRotationDeg() { return target.rotationDeg(now()); }

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
            startW = 0f; startH = 0f;
        }
        target.beginGesture();
    }

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

        float expBrX = tlx + topX + rightX;
        float expBrY = tly + topY + rightY;
        float span = Math.max(1e-4f, Math.max(Math.abs(trx - blx), Math.abs(try_ - bly)));
        if (Math.abs(expBrX - brx) > span * 0.02f || Math.abs(expBrY - bry) > span * 0.02f) {
            if (Math.abs(topX + bottomX) > span * 0.02f || Math.abs(topY + bottomY) > span * 0.02f) return false;
            if (Math.abs(rightX + leftX) > span * 0.02f || Math.abs(rightY + leftY) > span * 0.02f) return false;
        }
        float lenTop = (float) Math.hypot(topX, topY);
        float lenRight = (float) Math.hypot(rightX, rightY);
        if (lenTop < 1f || lenRight < 1f) return false;
        float dot = (topX * rightX + topY * rightY) / (lenTop * lenRight);
        if (Math.abs(dot) > 0.02f) return false;

        float[] cen = tmp;
        TransformQuad.centroid(quad8, cen);
        float cx = cen[0], cy = cen[1];
        float curW = (lenTop + (float)Math.hypot(bottomX, bottomY)) * 0.5f;
        float curH = (lenRight + (float)Math.hypot(leftX, leftY)) * 0.5f;
        float factorW = curW / Math.max(1f, startW);
        float factorH = curH / Math.max(1f, startH);
        float factor;
        boolean wIdle = Math.abs(factorW - 1f) < 0.015f;
        boolean hIdle = Math.abs(factorH - 1f) < 0.015f;
        if (wIdle && !hIdle) factor = factorH;
        else if (hIdle && !wIdle) factor = factorW;
        else factor = (factorW + factorH) * 0.5f;
        factor = TransformQuad.clamp(factor, TransformQuad.MIN_FACTOR, TransformQuad.MAX_FACTOR);
        float newSize = Math.max(0.02f, startSize * factor);
        float newAngle = (float) Math.toDegrees(Math.atan2(topY, topX));
        while (newAngle - startRot > 180f) newAngle -= 360f;
        while (newAngle - startRot < -180f) newAngle += 360f;
        RectF v = target.videoRect();
        if (v.width() <= 0f || v.height() <= 0f) return false;
        target.scaleTo(newSize, t);
        target.rotateTo(newAngle, t);
        float normCx = TransformQuad.clamp((cx - v.left) / v.width(), -2f, 3f);
        float normCy = TransformQuad.clamp((cy - v.top) / v.height(), -2f, 3f);
        target.moveTo(normCx, normCy, t);
        return true;
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
        target.scaleTo(Math.max(0.02f, startSize * factor), t);
        target.rotateTo(startRot + deltaDeg, t);
        target.moveTo((cxPx - v.left) / v.width(), (cyPx - v.top) / v.height(), t);
    }

    @Override
    public void writeRotation(float deg) { target.rotateTo(deg, now()); }

    @Override
    public void commitGesture(@NonNull String what) { target.commit(what); }

    @Override
    public void resetObjectGeometry() {
        KeyframeSet kf = clip.getOverlayTransform();
        if (kf == null) {
            kf = new KeyframeSet();
            clip.setOverlayTransform(kf);
        }
        // Clear position/scale/rotation tracks, keep opacity
        kf.removeProperty(KeyframeSet.X);
        kf.removeProperty(KeyframeSet.Y);
        kf.removeProperty(KeyframeSet.SCALE);
        kf.removeProperty(KeyframeSet.ROTATION);
        // Write defaults at t=0 so the object returns to its creation pose
        kf.getOrCreate(KeyframeSet.X).put(0L, OverlayVideoPreviewView.DEFAULT_X, com.fadcam.ui.faditor.keyframe.Easing.LINEAR);
        kf.getOrCreate(KeyframeSet.Y).put(0L, OverlayVideoPreviewView.DEFAULT_Y, com.fadcam.ui.faditor.keyframe.Easing.LINEAR);
        kf.getOrCreate(KeyframeSet.SCALE).put(0L, OverlayVideoPreviewView.DEFAULT_SCALE, com.fadcam.ui.faditor.keyframe.Easing.LINEAR);
        kf.getOrCreate(KeyframeSet.ROTATION).put(0L, 0f, com.fadcam.ui.faditor.keyframe.Easing.LINEAR);
        onChanged.run();
    }

    @Override
    public void flip(boolean horizontal) {
        // No honest render path for mirrored PiP (scale clamp, no negative texture) — inert.
    }
}
