package com.fadcam.ui.faditor.model;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.ui.faditor.keyframe.KeyframeSet;

/**
 * THE single authority for "what shape is this mask at time t" — animated mask parameters and
 * the object LINK, resolved in one place that both renderers call.
 *
 * <p><b>Why one evaluator and not two.</b> This is the {@code ChromaKey} /
 * {@code VolumeEnvelope} / {@code featherRadiusPx} discipline applied again, and for the reason
 * recorded against the Volume row: a control whose export ignores it is a control that lies, and
 * a preview that animates a mask on a different curve than the file is WORSE than one that does
 * not animate at all, because it looks correct. {@code MaskPathBuilder} calls this before it
 * builds a path, so neither the preview nor the export can obtain mask geometry without going
 * through it — the parity is structural, not a convention someone has to remember.</p>
 *
 * <p><b>Costs nothing when nothing animates.</b> A spec with no {@code maskKeys} and no linked
 * shape is returned by IDENTITY, so every project that has never touched this allocates nothing
 * and takes the exact code path it always did.</p>
 *
 * <p>Android-free so the JVM harness can reach it.</p>
 */
public final class MaskAnimator {

    private MaskAnimator() {}

    // Track names. Prefixed, because these live in the SAME namespace style as a clip's
    // transform tracks and an unprefixed "x" would be one careless copy-paste away from a mask
    // key being read as the object's position.
    public static final String CX = "maskCx";
    public static final String CY = "maskCy";
    public static final String W = "maskW";
    public static final String H = "maskH";
    public static final String CORNER = "maskCorner";
    public static final String ROTATION = "maskRotation";
    public static final String FEATHER = "maskFeather";

    /** Every animatable mask property, in the order the drawer lists them. */
    public static final String[] KEYS = {CX, CY, W, H, CORNER, ROTATION, FEATHER};

    /**
     * The spec to build mask geometry from at {@code timelineMs}.
     *
     * @param spec       the authored spec; {@code null} passes straight through
     * @param objectKf   the ITEM's own transform (a PiP's {@code overlayTransform}), needed only
     *                   by linked masks; {@code null} means "the object never moves"
     * @param timelineMs ABSOLUTE timeline ms — the base both mask keys and a PiP's transform
     *                   keys are stored in
     * @param frameW     pixel width of the space the mask is drawn into
     * @param frameH     pixel height of the same, so a rotation in normalised coordinates can be
     *                   done in the pixel space it will actually be seen in
     * @return the input spec itself when nothing animates and nothing is linked, otherwise a
     *         resolved copy. Never null unless {@code spec} was.
     */
    @Nullable
    public static CompositingSpec resolve(@Nullable CompositingSpec spec,
                                          @Nullable KeyframeSet objectKf,
                                          long timelineMs, float frameW, float frameH) {
        if (spec == null || !spec.hasMasks()) return spec;
        boolean animates = spec.hasMaskKeys();
        boolean linked = spec.hasLinkedMask();
        if (!animates && !linked) return spec;

        CompositingSpec out = spec.copy();
        CompositingSpec.MaskShape m = out.masks.get(0);

        if (animates) {
            KeyframeSet k = spec.maskKeys;
            m.cx = clamp01(k.valueAt(CX, timelineMs, m.cx));
            m.cy = clamp01(k.valueAt(CY, timelineMs, m.cy));
            // Never zero: a zero-size shape makes an empty Path, and an empty mask silently
            // means "no mask at all" rather than "a mask you cannot see" — two very different
            // pictures for the same authored value.
            m.w = clamp(k.valueAt(W, timelineMs, m.w), MIN_SIZE, 1f);
            m.h = clamp(k.valueAt(H, timelineMs, m.h), MIN_SIZE, 1f);
            m.corner = clamp01(k.valueAt(CORNER, timelineMs, m.corner));
            m.rotationDeg = k.valueAt(ROTATION, timelineMs, m.rotationDeg);
            out.maskFeather = clamp01(k.valueAt(FEATHER, timelineMs, spec.maskFeather));
        }

        if (m.linkedToObject) applyLink(m, objectKf, timelineMs, frameW, frameH);
        return out;
    }

    /** Smallest mask a user can end up with by animating; see {@link #resolve}. */
    public static final float MIN_SIZE = 0.005f;

    /**
     * Move {@code m} with the object — the LINKED reading of a mask.
     *
     * <p>The mask's authored numbers are the pose it had when the link was switched ON, against
     * the object pose recorded at that same moment ({@code linkBase*}). At time t the object has
     * moved by some delta, and the mask is carried by exactly that delta: scaled about the
     * object's centre, rotated about it, and translated with it.</p>
     *
     * <p><b>The rotation is done in PIXELS, not in normalised coordinates.</b> x is normalised by
     * the frame's width and y by its height, so on any non-square frame those are different
     * units — rotating the offset directly in them shears the mask, and the error is zero at 0°
     * and 180° and largest at 45°, which is exactly the shape of a bug that passes a casual look
     * and fails on the diagonal.</p>
     */
    private static void applyLink(@NonNull CompositingSpec.MaskShape m,
                                  @Nullable KeyframeSet objectKf,
                                  long timelineMs, float frameW, float frameH) {
        float baseScale = Math.max(0.001f, m.linkBaseScale);
        float ox = objectKf == null ? m.linkBaseX : objectKf.valueAt(CompositingKeys.X, timelineMs, m.linkBaseX);
        float oy = objectKf == null ? m.linkBaseY : objectKf.valueAt(CompositingKeys.Y, timelineMs, m.linkBaseY);
        float os = objectKf == null ? baseScale : objectKf.valueAt(CompositingKeys.SCALE, timelineMs, baseScale);
        float orot = objectKf == null ? m.linkBaseRotDeg
                : objectKf.valueAt(CompositingKeys.ROTATION, timelineMs, m.linkBaseRotDeg);

        float ds = Math.max(0.001f, os) / baseScale;
        float drot = orot - m.linkBaseRotDeg;

        // Offset from the object's centre at author time, taken into pixels so the rotation is
        // a rotation and not a shear.
        float pw = frameW > 0f ? frameW : 1f;
        float ph = frameH > 0f ? frameH : 1f;
        float offX = (m.cx - m.linkBaseX) * pw;
        float offY = (m.cy - m.linkBaseY) * ph;

        double rad = Math.toRadians(drot);
        double cos = Math.cos(rad), sin = Math.sin(rad);
        float rx = (float) ((offX * cos - offY * sin) * ds);
        float ry = (float) ((offX * sin + offY * cos) * ds);

        m.cx = clamp01(ox + rx / pw);
        m.cy = clamp01(oy + ry / ph);
        m.w = clamp(m.w * ds, MIN_SIZE, 1f);
        m.h = clamp(m.h * ds, MIN_SIZE, 1f);
        m.rotationDeg = m.rotationDeg + drot;
    }

    /**
     * The object's transform track names, restated here rather than imported from
     * {@code KeyframeSet} only to keep this file readable next to the mask ones — they ARE
     * {@code KeyframeSet.X}/{@code Y}/{@code SCALE}/{@code ROTATION} and the harness pins that.
     */
    public static final class CompositingKeys {
        public static final String X = KeyframeSet.X;
        public static final String Y = KeyframeSet.Y;
        public static final String SCALE = KeyframeSet.SCALE;
        public static final String ROTATION = KeyframeSet.ROTATION;
        private CompositingKeys() {}
    }

    private static float clamp01(float v) { return clamp(v, 0f, 1f); }

    private static float clamp(float v, float lo, float hi) {
        if (Float.isNaN(v)) return lo;
        return Math.max(lo, Math.min(hi, v));
    }
}
