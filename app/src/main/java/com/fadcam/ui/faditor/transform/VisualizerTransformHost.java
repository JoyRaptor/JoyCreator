package com.fadcam.ui.faditor.transform;

import android.graphics.Matrix;
import android.graphics.RectF;

import androidx.annotation.NonNull;

import com.fadcam.ui.faditor.keyframe.KeyframeSet;
import com.fadcam.ui.faditor.model.WaveformOverlayInstance;

/**
 * The Studio VISUALISER on the shared transform box — the same box pictures, text and sprites use.
 *
 * <h3>Why this is its own host and not an {@link AffineTransformHost}</h3>
 * <p>A visualiser's size is TWO numbers, a width and a height, each a fraction of the matching canvas
 * side ({@code WaveformOverlayInstance.widthFraction / heightFraction}) — a wide strip is a normal
 * thing to want. The affine host carries ONE size and scales it uniformly, so a drag on a side handle
 * could never make a strip wider without making it taller. This host reads the box as a rotated
 * rectangle and writes width and height apart, which is what "free aspect" means here.
 *
 * <h3>What it writes, and how</h3>
 * <p>The same three cases the drawer's rows cover ({@code FaditorEditorActivity.vizMenuProp}), now for
 * a hand gesture:
 * <ul>
 *   <li><b>Linked</b> (a proxy parent moves it): the box is drawn where the parent puts it, so a
 *       drag is measured in that WORLD pose and turned into the visualiser's OWN pose with
 *       {@code worldToOwn / worldSizeToOwn / worldRotationToOwn} before anything is stored.</li>
 *   <li><b>Armed</b> (it has keyframes): the pose is KEYED at the playhead, never written over the
 *       statics, so a visualiser that flies across the frame does not lose its flight to a nudge.</li>
 *   <li><b>Plain</b>: the statics, through the model's own setters.</li>
 * </ul>
 * The model's clamps are the only limits: centre 0..1, size 0.05..1. They are applied here too, before
 * a KEY is written, because the key path stores what it is given.
 *
 * <h3>Undo</h3>
 * <p>This class never touches the undo stack. {@link Bridge#beginGesture} takes the one snapshot a
 * whole gesture undoes to and {@link Bridge#commitGesture} records it, however many writes the drag
 * made — one gesture, one undo step.
 *
 * <h3>Affine only, no flip, no bend</h3>
 * <p>The preview draws the visualiser as a rotated rectangle and so does the export; there is no
 * renderer for a distorted one. {@link #writeQuad} refuses anything that is not a rotated rectangle,
 * and the activity puts the view in affine-only mode so Tilt and Free are never offered.
 */
public final class VisualizerTransformHost implements TransformOverlayView.Host {

    /** What this host needs from the editor, and the complete list of it. */
    public interface Bridge {
        /** The canvas rect in the transform view's pixels — the frame the visualiser is drawn in. */
        @NonNull RectF canvasRect();

        /** The playhead on the overlay clock: what the picture is drawn at, and where a key lands. */
        long clockMs();

        /** Still on the timeline and showing at {@code t}? False and the box shows nothing. */
        boolean showsAt(long t);

        /** A gesture is starting: take the ONE snapshot the whole gesture will undo to. */
        void beginGesture();

        /** The gesture ended cleanly after changing something: record ONE undo step. */
        void commitGesture(@NonNull String what);

        /** A begun gesture ended without changing anything: drop its snapshot. */
        void cancelGesture();

        /** The pose was written: repaint the picture, the open drawer's rows and the box. */
        void posed();
    }

    /** The model's own floor and ceiling for a width or height fraction. */
    private static final float MIN_SIZE = 0.05f, MAX_SIZE = 1f;

    /** A turn smaller than this is float noise from reading a quad back, not a turn. */
    private static final float ROT_NOISE_DEG = 0.02f;

    private static final int POS = 1, SIZE = 2, ROT = 4;

    @NonNull private final WaveformOverlayInstance wf;
    @NonNull private final Bridge bridge;

    private final float[] own = new float[2];
    private final float[] cen = new float[2];

    /** The WORLD pose at the start of the gesture: every relative write is measured from it. */
    private float startCx, startCy, startW, startH, startRot;

    public VisualizerTransformHost(@NonNull WaveformOverlayInstance wf, @NonNull Bridge bridge) {
        this.wf = wf;
        this.bridge = bridge;
    }

    // ── Geometry shared with the editor's hit-test and snap code ─────────────────────────────

    /**
     * The visualiser's drawn rectangle, un-rotated, in the pixels of {@code canvas} — exactly where
     * {@code WaveformOverlayView} draws it (centre and size are fractions of the canvas, taken from
     * the WORLD pose, so a linked visualiser is where its parent puts it).
     */
    public static boolean frame(@NonNull WaveformOverlayInstance wf, @NonNull RectF canvas,
                                long t, @NonNull RectF out) {
        if (canvas.width() <= 0.5f || canvas.height() <= 0.5f) return false;
        float cx = canvas.left + wf.animatedCenterX(t) * canvas.width();
        float cy = canvas.top + wf.animatedCenterY(t) * canvas.height();
        float hw = wf.animatedWidthFraction(t) * canvas.width() / 2f;
        float hh = wf.animatedHeightFraction(t) * canvas.height() / 2f;
        if (hw < 0.25f || hh < 0.25f) return false;
        out.set(cx - hw, cy - hh, cx + hw, cy + hh);
        return true;
    }

    /** Is {@code (x, y)} on the visualiser's drawn box, turned the way the picture is turned? */
    public static boolean hit(@NonNull WaveformOverlayInstance wf, @NonNull RectF canvas, long t,
                              float x, float y) {
        RectF r = new RectF();
        if (!frame(wf, canvas, t, r)) return false;
        float rot = wf.animatedRotation(t);
        if (rot != 0f) {
            // Into the box's own frame, so a rotated box is grabbable where it IS, not only inside
            // the axis-aligned rectangle that happens to bound it.
            double rad = Math.toRadians(-rot);
            float cx = r.centerX(), cy = r.centerY();
            float dx = x - cx, dy = y - cy;
            float c = (float) Math.cos(rad), s = (float) Math.sin(rad);
            x = cx + dx * c - dy * s;
            y = cy + dx * s + dy * c;
        }
        return r.contains(x, y);
    }

    /** The axis-aligned bounds of the turned box — what "other objects" snap to. */
    public static boolean rotatedBounds(@NonNull WaveformOverlayInstance wf, @NonNull RectF canvas,
                                        long t, @NonNull RectF out) {
        if (!frame(wf, canvas, t, out)) return false;
        float rot = wf.animatedRotation(t);
        if (rot != 0f) {
            Matrix m = new Matrix();
            m.setRotate(rot, out.centerX(), out.centerY());
            m.mapRect(out);
        }
        return true;
    }

    // ── Reading ──────────────────────────────────────────────────────────────────────────────

    private long now() { return bridge.clockMs(); }

    @Override
    public boolean readQuad(@NonNull float[] outQuad8) {
        long t = now();
        if (!bridge.showsAt(t)) return false;
        RectF v = bridge.canvasRect();
        if (v.width() <= 0.5f || v.height() <= 0.5f) return false;
        float cx = v.left + wf.animatedCenterX(t) * v.width();
        float cy = v.top + wf.animatedCenterY(t) * v.height();
        float hw = wf.animatedWidthFraction(t) * v.width() / 2f;
        float hh = wf.animatedHeightFraction(t) * v.height() / 2f;
        if (hw < 0.25f || hh < 0.25f) return false;
        double th = Math.toRadians(wf.animatedRotation(t));
        float c = (float) Math.cos(th), s = (float) Math.sin(th);
        // TL, TR, BR, BL — the order the box expects.
        float[] xs = {-hw, hw, hw, -hw};
        float[] ys = {-hh, -hh, hh, hh};
        for (int i = 0; i < 4; i++) {
            outQuad8[i * 2] = cx + xs[i] * c - ys[i] * s;
            outQuad8[i * 2 + 1] = cy + xs[i] * s + ys[i] * c;
        }
        return true;
    }

    @Override
    public void readPivot(@NonNull float[] outXY) {
        long t = now();
        RectF v = bridge.canvasRect();
        if (!bridge.showsAt(t) || v.width() <= 0.5f || v.height() <= 0.5f) {
            outXY[0] = 0f;
            outXY[1] = 0f;
            return;
        }
        outXY[0] = v.left + wf.animatedCenterX(t) * v.width();
        outXY[1] = v.top + wf.animatedCenterY(t) * v.height();
    }

    /** No stored pivot on a visualiser: it turns about its centre, so the fold pivot IS the centre. */
    @Override
    public void readFoldPivot(@NonNull float[] outXY) { readPivot(outXY); }

    @Override
    public float currentRotationDeg() {
        return TransformQuad.norm180(wf.animatedRotation(now()));
    }

    @Override
    @NonNull
    public RectF videoRect() { return bridge.canvasRect(); }

    // ── Gesture lifecycle ────────────────────────────────────────────────────────────────────

    private void captureStart() {
        long t = now();
        startCx = wf.animatedCenterX(t);
        startCy = wf.animatedCenterY(t);
        startW = wf.animatedWidthFraction(t);
        startH = wf.animatedHeightFraction(t);
        startRot = TransformQuad.norm180(wf.animatedRotation(t));
    }

    @Override
    public void beginGesture() {
        captureStart();
        bridge.beginGesture();
    }

    /** A pinch took over from a drag that already moved it: re-read the start, no new snapshot. */
    @Override
    public void rebaseGesture() { captureStart(); }

    @Override
    public void commitGesture(@NonNull String what) { bridge.commitGesture(what); }

    @Override
    public void cancelGesture() { bridge.cancelGesture(); }

    // ── Writing ──────────────────────────────────────────────────────────────────────────────

    /**
     * Store a WORLD pose — linked, armed or plain (see the class note). {@code parts} says which of
     * position, size and rotation this write is about; the rest is left exactly as it is, so a move
     * never re-keys the rotation and a turn never re-keys the size.
     */
    private void store(long t, int parts, float wcx, float wcy, float ww, float wh, float wrot) {
        float ocx = wcx, ocy = wcy, ow = ww, oh = wh, orot = wrot;
        if (wf.isLinked()) {
            // The finger is in the picture; a follower stores its own pose.
            if ((parts & POS) != 0) {
                wf.worldToOwn(wcx, wcy, t, own);
                ocx = own[0];
                ocy = own[1];
            }
            if ((parts & SIZE) != 0) {
                ow = wf.worldSizeToOwn(ww, t);
                oh = wf.worldSizeToOwn(wh, t);
            }
            if ((parts & ROT) != 0) orot = wf.worldRotationToOwn(wrot, t);
        }
        // The model's clamps, applied before a key too: a key is stored as given.
        ocx = TransformQuad.clamp(ocx, 0f, 1f);
        ocy = TransformQuad.clamp(ocy, 0f, 1f);
        ow = TransformQuad.clamp(ow, MIN_SIZE, MAX_SIZE);
        oh = TransformQuad.clamp(oh, MIN_SIZE, MAX_SIZE);

        if (wf.isArmed()) {
            if ((parts & POS) != 0) {
                wf.addPropertyKeyframeAt(KeyframeSet.X, t, ocx);
                wf.addPropertyKeyframeAt(KeyframeSet.Y, t, ocy);
            }
            if ((parts & SIZE) != 0) {
                wf.addPropertyKeyframeAt(WaveformOverlayInstance.VIZ_WIDTH, t, ow);
                wf.addPropertyKeyframeAt(WaveformOverlayInstance.VIZ_HEIGHT, t, oh);
            }
            if ((parts & ROT) != 0) wf.addPropertyKeyframeAt(KeyframeSet.ROTATION, t, orot);
        } else {
            if ((parts & POS) != 0) wf.setCenter(ocx, ocy);
            if ((parts & SIZE) != 0) wf.setSize(ow, oh);
            if ((parts & ROT) != 0) wf.setRotationDeg(orot);
        }
    }

    @Override
    public boolean writeQuad(@NonNull float[] q) {
        if (!TransformQuad.isValid(q)) return false;
        RectF v = bridge.canvasRect();
        if (v.width() <= 0f || v.height() <= 0f) return false;

        float topX = q[2] - q[0], topY = q[3] - q[1];
        float rightX = q[4] - q[2], rightY = q[5] - q[3];
        float bottomX = q[6] - q[4], bottomY = q[7] - q[5];
        float leftX = q[0] - q[6], leftY = q[1] - q[7];
        float lenTop = (float) Math.hypot(topX, topY);
        float lenRight = (float) Math.hypot(rightX, rightY);
        if (lenTop < 1f || lenRight < 1f) return false;

        // A ROTATED RECTANGLE OR NOTHING. Opposite edges equal and opposite (a parallelogram), then
        // adjacent edges at a right angle (not a shear). The tolerance is the affine host's, so a
        // long edge's rounding does not refuse a drag the finger made honestly.
        float span = Math.max(1e-4f, Math.max(Math.abs(q[2] - q[6]), Math.abs(q[3] - q[7])));
        float tol = span * 0.02f;
        if (Math.abs(topX + bottomX) > tol || Math.abs(topY + bottomY) > tol
                || Math.abs(rightX + leftX) > tol || Math.abs(rightY + leftY) > tol) return false;
        float dot = (topX * rightX + topY * rightY) / (lenTop * lenRight);
        if (Math.abs(dot) > 0.02f) return false;

        // Width and height are read APART — that is the free aspect.
        float wPx = (lenTop + (float) Math.hypot(bottomX, bottomY)) * 0.5f;
        float hPx = (lenRight + (float) Math.hypot(leftX, leftY)) * 0.5f;
        TransformQuad.centroid(q, cen);

        // atan2 jumps a full turn across its branch cut: take the equivalent angle nearest the start.
        float ang = (float) Math.toDegrees(Math.atan2(topY, topX));
        while (ang - startRot > 180f) ang -= 360f;
        while (ang - startRot < -180f) ang += 360f;
        ang = TransformQuad.norm180(ang);
        boolean turned = Math.abs(TransformQuad.norm180(ang - startRot)) >= ROT_NOISE_DEG;

        store(now(), POS | SIZE | (turned ? ROT : 0),
                (cen[0] - v.left) / v.width(), (cen[1] - v.top) / v.height(),
                wPx / v.width(), hPx / v.height(), ang);
        bridge.posed();
        return true;
    }

    @Override
    public void writeTranslate(float dxPx, float dyPx) {
        RectF v = bridge.canvasRect();
        if (v.width() <= 0f || v.height() <= 0f) return;
        store(now(), POS, startCx + dxPx / v.width(), startCy + dyPx / v.height(), 0f, 0f, 0f);
        bridge.posed();
    }

    @Override
    public void writeSimilarity(float factor, float deltaDeg, float cxPx, float cyPx) {
        RectF v = bridge.canvasRect();
        if (v.width() <= 0f || v.height() <= 0f) return;
        // One factor for both sides keeps the strip's shape, so hold it where NEITHER side would
        // leave the model's range — clamping each side alone would bend the aspect at the limit.
        float lo = MIN_SIZE / Math.max(Math.min(startW, startH), 1e-4f);
        float hi = MAX_SIZE / Math.max(Math.max(startW, startH), 1e-4f);
        float f = hi < lo ? 1f : TransformQuad.clamp(factor, lo, hi);
        boolean turned = Math.abs(deltaDeg) >= ROT_NOISE_DEG;
        store(now(), POS | SIZE | (turned ? ROT : 0),
                (cxPx - v.left) / v.width(), (cyPx - v.top) / v.height(),
                startW * f, startH * f, TransformQuad.norm180(startRot + deltaDeg));
        bridge.posed();
    }

    @Override
    public void writeRotation(float deg) {
        store(now(), ROT, 0f, 0f, 0f, 0f, TransformQuad.norm180(deg));
        bridge.posed();
    }

    /**
     * Back to the middle of the frame, upright. KEEPS SIZE, siding with text, pictures and sprites: a
     * strip's width and height are authored work with no canonical default to return to. Position and
     * turn lose their keys, as the others do — "reset" means plain, not "plain at this one moment".
     */
    @Override
    public void resetObjectGeometry() {
        KeyframeSet ks = wf.getKeyframes();
        ks.removeProperty(KeyframeSet.X);
        ks.removeProperty(KeyframeSet.Y);
        ks.removeProperty(KeyframeSet.ROTATION);
        wf.setCenter(0.5f, 0.5f);
        wf.setRotationDeg(0f);
        bridge.posed();
    }

    /** A visualiser cannot mirror its box: the surface must not offer a gesture nothing draws. */
    @Override
    public boolean supportsFlip() { return false; }

    @Override
    public void flip(boolean horizontal) { /* see supportsFlip */ }
}
