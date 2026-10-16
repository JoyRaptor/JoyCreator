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
        if (!o.hasMesh()) return false;
        float a = frameAspect > 0.01f ? frameAspect : 1f;
        float ia = imageAspect > 0.01f ? imageAspect : 1f;
        float size = o.animatedSizeFraction(t);
        return pin(o.getMesh(), pin, o.meshLocalTime(t), o.animatedCenterX(t), o.animatedCenterY(t),
                size * ia * o.animatedScaleX(t) / a, size * o.animatedScaleY(t),
                o.mirrorSignX(), o.mirrorSignY(), o.animatedRotation(t), a, out);
    }

    /** A sprite's pin: its cell is {@code cellAspect} wide per unit of height, flips mirror it. */
    public static int pinCount(@NonNull com.fadcam.ui.faditor.sprite.SpriteOverlayItem s) {
        MeshWarpSpec m = s.getMesh();
        return m == null || m.topology() == null || !s.hasMesh() ? 0 : m.topology().handleCount();
    }

    public static boolean pin(@NonNull com.fadcam.ui.faditor.sprite.SpriteOverlayItem s, int pin, long t,
                              float frameAspect, @NonNull float[] out) {
        if (!s.hasMesh()) return false;
        float a = frameAspect > 0.01f ? frameAspect : 1f;
        float ca = s.getCellAspect() > 0.01f ? s.getCellAspect() : 1f;
        float h = s.animatedSizeFraction(t);
        return pin(s.getMesh(), pin, s.meshLocalTime(t), s.animatedCenterX(t), s.animatedCenterY(t),
                h * ca / a, h, s.isFlipH() ? -1f : 1f, s.isFlipV() ? -1f : 1f,
                s.animatedRotation(t), a, out);
    }

    /** The shared maths: pin {@code pin} of {@code m} at local time, carried into the frame. */
    private static boolean pin(@NonNull MeshWarpSpec m, int pin, long localMs, float cx, float cy,
                               float wNorm, float hNorm, float mirrorX, float mirrorY, float rotDeg,
                               float a, @NonNull float[] out) {
        if (m.topology() == null) return false;
        MeshTopology topo = m.topology();
        if (pin < 0 || pin >= topo.handleCount()) return false;
        int comp = Math.max(2, topo.handleComponents());
        float[] pose = new float[topo.handleArity()];
        if (!m.handlesAt(localMs, pose)) {
            float[] st = m.handles();
            if (st != null) System.arraycopy(st, 0, pose, 0, Math.min(st.length, pose.length));
        }
        float u = topo.handleRestX(pin) + pose[pin * comp];
        float v = topo.handleRestY(pin) + pose[pin * comp + 1];
        pinToFrame(u, v, cx, cy, wNorm, hNorm, mirrorX, mirrorY, rotDeg, a, out);
        return true;
    }
}
