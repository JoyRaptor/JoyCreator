package com.fadcam.ui.faditor.transform;

import android.graphics.RectF;

import androidx.annotation.NonNull;

import com.fadcam.ui.faditor.model.Clip;
import com.fadcam.ui.faditor.model.SpineTransform;

/**
 * THE SECOND FADCAM-SHAPED FILE IN THIS PACKAGE: the adapter binding {@link TransformOverlayView}
 * to a SPINE (master) clip, the twin of {@link CornerPinTransformHost}.
 *
 * <p>Same surface, same vocabulary, same gestures — a selected spine clip gets the eight handles,
 * the floating spin arc and the two-finger scale-and-rotate that a selected image already has, so
 * there is nothing new for the user to learn and nothing new for the View to know. What changes is
 * only where the numbers land: an image writes position/size/rotation and a corner pin, a spine
 * clip writes {@link SpineTransform}'s six.</p>
 *
 * <h3>AFFINE ONLY. There is no spine corner pin, and there must not be one.</h3>
 * <p>Every distortion an image can author is drawn by a homography that BOTH surfaces already
 * have: {@code CornerPinImageView} in the preview, {@code ImageOverlayDraw} in the export. The
 * spine picture has no such pair — its preview path is the GL chain's full-frame passes and its
 * export path is the media3 effect chain, and neither carries a perspective term for the base
 * picture. So this host accepts move, per-axis scale, rotate and mirror, and REFUSES anything
 * else: {@link #writeQuad} returns false for a quad that is not a rotated, possibly-mirrored
 * rectangle, and the View's own rollback then simply stops the drag. The surface is additionally
 * put in {@code affineOnly} mode by the activity so the role ring never offers Tilt or Free in the
 * first place — refusing a gesture is the safety net, not the interface.</p>
 *
 * <p>That is a deliberate, stated limit rather than a half-built one. Authoring a distortion no
 * renderer can draw would look like it worked in the handles and vanish in the file, which is the
 * exact failure this project's preview/export rule exists to prevent.</p>
 *
 * <h3>Why the handles hug the PICTURE and not the canvas</h3>
 * <p>The transform acts on the whole canvas frame (see {@link SpineTransform}), but the part of it
 * the user can see is the fit-centred picture inside it — for a 9:16 clip on a 16:9 canvas, a tall
 * strip. Grabbing the canvas bounds would put the handles out in the empty black on either side of
 * the thing being moved. So {@link Bridge#basePictureHalfExtent} supplies that strip's half-size
 * in canvas NDC and every read and every write here is expressed against it. Because BOTH
 * directions use the same rectangle, the quad on screen and the pose in the model round-trip
 * exactly — a gesture that changes nothing writes nothing.</p>
 */
public final class SpineTransformHost implements TransformOverlayView.Host {

    /** Everything this adapter needs from the editor, and the whole list of it. */
    public interface Bridge {
        /** The spine clip being placed. */
        @NonNull Clip clip();

        /** The OUTPUT CANVAS in preview screen-space — the editor's {@code computeCanvasRect()}. */
        @NonNull RectF canvasRect();

        /**
         * Half-width and half-height of the clip's fit-centred picture as a fraction of the
         * canvas, i.e. its extent in canvas NDC. {1, 1} for a clip that fills the canvas.
         */
        void basePictureHalfExtent(@NonNull float[] outWH);

        /** Clip-local milliseconds at the playhead — the spine keyframes' own time base. */
        long clipLocalMs();

        /** The pose changed: re-run the live composite so the picture follows the handles. */
        void onSpineTransformChanged();

        /** One gesture, one undo step: {@code before} is the pose the whole gesture undoes to. */
        void commitSpineTransform(@NonNull Clip.SpineSnapshot before, @NonNull String what);
    }

    @NonNull private final Bridge bridge;

    private final float[] pose = new float[SpineTransform.POSE];
    private final float[] fwd = new float[9];
    private final float[] xy = new float[2];
    private final float[] half = new float[2];

    /** Captured at beginGesture: the pose every relative write is measured from. */
    private float startCx, startCy, startScale, startRot;
    /** The whole transform as it was when the gesture began — the ONE undo snapshot. */
    private Clip.SpineSnapshot before;

    public SpineTransformHost(@NonNull Bridge bridge) {
        this.bridge = bridge;
    }

    // ── Reading ──────────────────────────────────────────────────────────

    private boolean readPose() {
        Clip c = bridge.clip();
        c.spinePoseAt(bridge.clipLocalMs(), pose);
        RectF r = bridge.canvasRect();
        if (r.width() <= 1f || r.height() <= 1f) return false;
        return SpineTransform.forward(pose, r.width() / r.height(), fwd);
    }

    /** Canvas NDC (y UP) → this view's pixels. */
    private void toPx(@NonNull RectF r, float nx, float ny, @NonNull float[] out) {
        out[0] = r.left + (nx * 0.5f + 0.5f) * r.width();
        out[1] = r.top + (0.5f - ny * 0.5f) * r.height();
    }

    /** This view's pixels → canvas NDC (y UP). */
    private static void toNdc(@NonNull RectF r, float px, float py, @NonNull float[] out) {
        out[0] = (px - r.left) / r.width() * 2f - 1f;
        out[1] = 1f - (py - r.top) / r.height() * 2f;
    }

    @Override
    public boolean readQuad(@NonNull float[] outQuad8) {
        if (!readPose()) return false;
        RectF r = bridge.canvasRect();
        bridge.basePictureHalfExtent(half);
        float pw = half[0], ph = half[1];
        if (pw <= 0.001f || ph <= 0.001f) return false;
        // TL, TR, BR, BL — the View's corner order, in a y-UP space, so "top" is +ph.
        float[] bx = {-pw, pw, pw, -pw};
        float[] by = {ph, ph, -ph, -ph};
        for (int i = 0; i < 4; i++) {
            SpineTransform.apply(fwd, bx[i], by[i], xy);
            toPx(r, xy[0], xy[1], xy);
            outQuad8[i * 2] = xy[0];
            outQuad8[i * 2 + 1] = xy[1];
        }
        return true;
    }

    @Override
    public void readPivot(@NonNull float[] outXY) {
        if (!readPose()) { outXY[0] = 0f; outXY[1] = 0f; return; }
        RectF r = bridge.canvasRect();
        SpineTransform.apply(fwd, 0f, 0f, xy);
        toPx(r, xy[0], xy[1], outXY);
    }

    /**
     * SPEC B — a spine clip has no stored rotation pivot (that is an image-overlay feature),
     * so the fold pivot IS the centre: the rotate handle's preview orbits the centre exactly
     * as it always did.
     */
    @Override
    public void readFoldPivot(@NonNull float[] outXY) {
        readPivot(outXY);
    }

    @Override
    public float currentRotationDeg() {
        bridge.clip().spinePoseAt(bridge.clipLocalMs(), pose);
        return pose[SpineTransform.ROT];
    }

    @Override
    @NonNull
    public RectF videoRect() { return bridge.canvasRect(); }

    // ── Writing ──────────────────────────────────────────────────────────

    @Override
    public void beginGesture() {
        Clip c = bridge.clip();
        c.spinePoseAt(bridge.clipLocalMs(), pose);
        startCx = pose[SpineTransform.CX];
        startCy = pose[SpineTransform.CY];
        startScale = pose[SpineTransform.SC];
        startRot = pose[SpineTransform.ROT];
        before = c.snapshotSpineTransform();
    }

    @Override
    public void writeTranslate(float dxPx, float dyPx) {
        RectF r = bridge.canvasRect();
        if (r.width() <= 1f || r.height() <= 1f) return;
        bridge.clip().setSpineCenter(startCx + dxPx / r.width(), startCy + dyPx / r.height());
        bridge.onSpineTransformChanged();
    }

    @Override
    public void writeSimilarity(float factor, float deltaDeg, float cxPx, float cyPx) {
        RectF r = bridge.canvasRect();
        if (r.width() <= 1f || r.height() <= 1f) return;
        Clip c = bridge.clip();
        // Size and angle first, position last — the same order CornerPinTransformHost uses, and
        // for the same reason: the centre is quoted against a picture whose size this gesture is
        // in the middle of changing.
        c.setSpineScale(startScale * factor);
        c.setSpineRotationDeg(startRot + deltaDeg);
        c.setSpineCenter((cxPx - r.left) / r.width(), (cyPx - r.top) / r.height());
        bridge.onSpineTransformChanged();
    }

    @Override
    public void writeRotation(float deg) {
        bridge.clip().setSpineRotationDeg(deg);
        bridge.onSpineTransformChanged();
    }

    /**
     * A corner or edge drag, read back as a pose.
     *
     * <p>The View hands every shape gesture over as a destination QUAD, so this is where the
     * affine-only rule is actually enforced. The incoming quad is converted to canvas NDC, the
     * unique affine that carries the base picture rectangle onto it is solved from three of its
     * corners, and the result is then tested for being one this model can hold:</p>
     * <ol>
     *   <li>the fourth corner must land where that affine puts it — otherwise the quad is a
     *       trapezoid (a Free or a fold), which needs a perspective term nothing can draw;</li>
     *   <li>the affine's two axes, measured in the SQUARE metric, must stay perpendicular —
     *       otherwise it is a shear (a Tilt), which is likewise not a rotation-plus-scale.</li>
     * </ol>
     * <p>Failing either returns false, and the View rolls the quad back to its last good pose: the
     * drag visibly stops instead of the picture and the handles quietly parting company. That is
     * the same contract {@link CornerPinTransformHost#writeQuad} has for a pin beyond its range.
     * </p>
     *
     * <p>The decomposition is signed, so a MIRRORED quad decomposes to a negative axis scale
     * rather than being rejected — {@link #flip} produces exactly that and must round-trip.</p>
     */
    @Override
    public boolean writeQuad(@NonNull float[] quad8) {
        RectF r = bridge.canvasRect();
        if (r.width() <= 1f || r.height() <= 1f) return false;
        bridge.basePictureHalfExtent(half);
        float pw = half[0], ph = half[1];
        if (pw <= 0.001f || ph <= 0.001f) return false;

        float[] n = new float[8];
        for (int i = 0; i < 4; i++) {
            toNdc(r, quad8[i * 2], quad8[i * 2 + 1], xy);
            n[i * 2] = xy[0];
            n[i * 2 + 1] = xy[1];
        }
        // Base rect corners are TL(-pw, +ph), TR(+pw, +ph), BR(+pw, -ph), BL(-pw, -ph).
        // A maps base → destination; solve its two columns from the TL→TR and TL→BL edges.
        float ax = (n[2] - n[0]) / (2f * pw);          // A[0][0]
        float ay = (n[3] - n[1]) / (2f * pw);          // A[1][0]
        float bx = (n[0] - n[6]) / (2f * ph);          // A[0][1]
        float by = (n[1] - n[7]) / (2f * ph);          // A[1][1]
        float tx = n[0] - (ax * -pw + bx * ph);
        float ty = n[1] - (ay * -pw + by * ph);

        // (1) parallelogram test — where the affine puts BR against where the finger put it.
        float brx = ax * pw + bx * -ph + tx;
        float bry = ay * pw + by * -ph + ty;
        float span = Math.max(1e-4f, Math.max(Math.abs(n[2] - n[6]), Math.abs(n[3] - n[7])));
        if (Math.abs(brx - n[4]) > span * 0.02f || Math.abs(bry - n[5]) > span * 0.02f) {
            return false;
        }

        // (2) perpendicularity, in the SQUARE metric — NDC is anisotropic, so the test has to be
        // made where the rotation is defined or a plain scale on a 16:9 canvas reads as a shear.
        // SpineTransform.forward builds A = D2 * (R*S) * D1 with D1 = diag(aspect, 1) and
        // D2 = D1^-1, so recovering the rotation-and-scale part is R*S = D1 * A * D1^-1:
        // the top row multiplied by aspect and the left column divided by it.
        float aspect = r.width() / r.height();
        float m00 = ax;
        float m01 = bx * aspect;
        float m10 = ay / aspect;
        float m11 = by;
        float len0 = (float) Math.hypot(m00, m10);
        float len1 = (float) Math.hypot(m01, m11);
        if (len0 < 1e-5f || len1 < 1e-5f) return false;
        float dot = (m00 * m01 + m10 * m11) / (len0 * len1);
        if (Math.abs(dot) > 0.02f) return false;

        // Rotation from the first column; kx is taken POSITIVE and any mirror is carried by ky,
        // which keeps the angle continuous through a drag.
        float kx = len0;
        float c = m00 / kx, s = -m10 / kx;
        float rot = (float) Math.toDegrees(Math.atan2(s, c));
        // Signed ky: pick whichever of the two expressions is numerically the stronger.
        float ky = Math.abs(c) >= Math.abs(s) ? m11 / c : m01 / s;
        if (!isFinite(kx) || !isFinite(ky) || !isFinite(rot)) return false;
        if (Math.abs(kx) < SpineTransform.MIN_SCALE || Math.abs(ky) < SpineTransform.MIN_SCALE) {
            return false;
        }
        if (Math.abs(kx) > SpineTransform.MAX_SCALE || Math.abs(ky) > SpineTransform.MAX_SCALE) {
            return false;
        }

        Clip clip = bridge.clip();
        clip.spinePoseAt(bridge.clipLocalMs(), pose);
        float sc = pose[SpineTransform.SC];
        if (Math.abs(sc) < 1e-4f) sc = 1f;
        clip.setSpineScaleXY(kx / sc, ky / sc);
        clip.setSpineRotationDeg(rot);
        clip.setSpineCenter(0.5f + tx / 2f, 0.5f - ty / 2f);
        bridge.onSpineTransformChanged();
        return true;
    }

    private static boolean isFinite(float v) {
        return !Float.isNaN(v) && !Float.isInfinite(v);
    }

    @Override
    public void commitGesture(@NonNull String what) {
        Clip.SpineSnapshot b = before;
        before = null;
        if (b != null) bridge.commitSpineTransform(b, what);
    }

    // ── The two disjoint resets ──────────────────────────────────────────

    /**
     * RESET THE OBJECT — back to the plain fit-centre.
     *
     * <p><b>This one DOES reset size</b>, which is the single place it departs from
     * {@link CornerPinTransformHost#resetObjectGeometry}'s reasoning. That method keeps an image's
     * size because an overlay has no canonical size to return to and a silent size jump would read
     * as the reset having deleted work. A spine clip does have one: fit-centred is what it was
     * before anybody touched it and what every clip in the project still is. Leaving a scale
     * behind would make "reset" produce a state the user could not have reached any other way.</p>
     *
     * <p>Keyframes go too, statics and tracks alike — leaving the tracks would put the pose
     * straight back on the next playhead tick, which reads as the reset not working. Handle roles
     * are not touched: those are {@code HandleModel.resetHelpers}' territory.</p>
     */
    @Override
    public void resetObjectGeometry() {
        bridge.clip().clearSpineTransform();
        bridge.onSpineTransformChanged();
    }

    /**
     * MIRROR THE PLACEMENT, as a negative axis scale.
     *
     * <p>One number, already inside the matrix both renderers build from
     * {@link SpineTransform#forward} — so there is no new model field, no new preview branch and
     * no new export branch for it, and two flips return the original value to the float.</p>
     *
     * <p>Note this mirrors the clip's PLACEMENT on the canvas, which is not the same thing as the
     * Rotate/Flip tool's {@code flipHorizontal}: that one mirrors the SOURCE, upstream of the crop,
     * and still does. Both are meaningful and they compose the way the chain says they do.</p>
     */
    @Override
    public void flip(boolean horizontal) {
        Clip c = bridge.clip();
        c.spinePoseAt(bridge.clipLocalMs(), pose);
        if (horizontal) {
            c.setSpineScaleXY(-pose[SpineTransform.SX], pose[SpineTransform.SY]);
        } else {
            c.setSpineScaleXY(pose[SpineTransform.SX], -pose[SpineTransform.SY]);
        }
        bridge.onSpineTransformChanged();
    }
}
