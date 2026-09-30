package com.fadcam.ui.faditor.transform.mesh;

import androidx.annotation.NonNull;

import com.fadcam.ui.faditor.model.TextOverlayItem;

/**
 * WHERE A PIN IS, in the picture (frame fractions), at a moment. The one place that answers it
 * for the link resolver, so "a prop follows a hand pin" (SPEC_20260924_LINKING, beyond
 * transforms) reads the same numbers the picture is drawn with.
 *
 * <p>A pin lives in the picture's own unit square (top-left origin): its rest position plus the
 * pose's nudge. The picture sits in the frame with its centre, height fraction, scale, rotation
 * and mirror; a point is carried from the one to the other exactly as {@code MeshPlacement} does:
 * offsets are measured in frame-HEIGHT units, so a rotation turns them without shearing (SPEC Q).
 *
 * <p>Deliberately first-slice: it follows centre, size, scale, rotation and mirror. A picture with
 * a corner pin, an off-centre rotation pivot or a motion preset drifts slightly from the drawn
 * dot; those come next. No Android imports, so a JVM check can pin the maths.
 */
public final class MeshPinPose {

    private MeshPinPose() {}

    /** How many pins this picture has (0 = no bend or puppet yet). */
    public static int pinCount(@NonNull TextOverlayItem o) {
        MeshWarpSpec m = o.getMesh();
        return m == null || m.topology() == null || !o.hasMesh() ? 0 : m.topology().handleCount();
    }

    /**
     * Carry the unit-square point ({@code u}, {@code v}) into the frame.
     *
     * @param wNorm the picture's width as a fraction of frame WIDTH
     * @param hNorm the picture's height as a fraction of frame HEIGHT
     * @param a     frame width / height
     */
    public static void pinToFrame(float u, float v, float cx, float cy, float wNorm, float hNorm,
                                  float mirrorX, float mirrorY, float rotDeg, float a,
                                  @NonNull float[] out) {
        float x = mirrorX * (u - 0.5f) * wNorm * a;      // frame-height units
        float y = mirrorY * (v - 0.5f) * hNorm;
        double r = Math.toRadians(rotDeg);
        float c = (float) Math.cos(r), s = (float) Math.sin(r);
        out[0] = cx + (x * c - y * s) / a;
        out[1] = cy + (x * s + y * c);
    }

    /**
     * Pin {@code pin} of {@code o} at timeline time {@code t}, into {@code out} (x, y).
     * False when there is no such pin (the caller falls back to the picture's centre).
     */
    public static boolean pin(@NonNull TextOverlayItem o, int pin, long t, float frameAspect,
                              float imageAspect, @NonNull float[] out) {
        MeshWarpSpec m = o.getMesh();
        if (m == null || m.topology() == null || !o.hasMesh()) return false;
        MeshTopology topo = m.topology();
        if (pin < 0 || pin >= topo.handleCount()) return false;
        int comp = Math.max(2, topo.handleComponents());
        float[] pose = new float[topo.handleArity()];
        if (!m.handlesAt(o.meshLocalTime(t), pose)) {
            float[] st = m.handles();
            if (st != null) System.arraycopy(st, 0, pose, 0, Math.min(st.length, pose.length));
        }
        float u = topo.handleRestX(pin) + pose[pin * comp];
        float v = topo.handleRestY(pin) + pose[pin * comp + 1];
        float a = frameAspect > 0.01f ? frameAspect : 1f;
        float ia = imageAspect > 0.01f ? imageAspect : 1f;
        float size = o.animatedSizeFraction(t);
        float wNorm = size * ia * o.animatedScaleX(t) / a;
        float hNorm = size * o.animatedScaleY(t);
        pinToFrame(u, v, o.animatedCenterX(t), o.animatedCenterY(t), wNorm, hNorm,
                o.mirrorSignX(), o.mirrorSignY(), o.animatedRotation(t), a, out);
        return true;
    }
}
