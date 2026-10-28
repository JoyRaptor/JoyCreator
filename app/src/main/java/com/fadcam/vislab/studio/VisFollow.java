package com.fadcam.vislab.studio;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.ui.faditor.model.LinkFollower;
import com.fadcam.ui.faditor.model.LinkPose;
import com.fadcam.ui.faditor.model.WaveformOverlayInstance;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Where the look layers that follow a Studio object sit at one moment (roadmap R8: animate a
 * dummy and the logo inside the look goes with it).
 *
 * <p>The object's centre is measured from the visualizer's own centre, turned back by the
 * visualizer's rotation, and divided by half the box's short side: that is the look's scene unit
 * (the short side runs -1..+1), with y up.</p>
 *
 * <p>Two ways to follow. SNAPS TO IT (the default): the layer's Place across / up becomes the
 * object's position, so a layer whose own knobs put it at the middle lands exactly on the object;
 * Size and Turn follow how the object has grown and turned from its own resting values. KEEPS ITS
 * PLACE: the layer stays where the look draws it and moves only by how far the object has moved,
 * grown and turned since the choice was made ({@link WaveformOverlayInstance#getVisFollowBase()}).</p>
 *
 * <p>Packed for {@code VisRenderer.setLayerFollow}: {@code [layerIndex, x, y, sizeMul, turnDeg, by]}.</p>
 */
public final class VisFollow {

    private VisFollow() {}

    /**
     * @param frameAspect frame width / height (positions are fractions of each)
     * @return the follows for the look shown at {@code timelineMs}, or null when there are none
     */
    @Nullable
    public static float[] at(@NonNull WaveformOverlayInstance o, long timelineMs, float frameAspect) {
        Map<String, LinkPose> refs = o.getVisFollowRefs();
        if (refs.isEmpty()) return null;
        String look = o.visLookAt(timelineMs);
        if (look == null) return null;
        String prefix = look + "/";
        float aspect = frameAspect > 0.01f ? frameAspect : 16f / 9f;
        float cx = o.animatedCenterX(timelineMs) * aspect;
        float cy = o.animatedCenterY(timelineMs);
        double th = Math.toRadians(o.animatedRotation(timelineMs));
        float cos = (float) Math.cos(th), sin = (float) Math.sin(th);
        float half = Math.min(o.animatedWidthFraction(timelineMs) * aspect,
                o.animatedHeightFraction(timelineMs)) / 2f;
        if (half < 1e-4f) return null;
        List<float[]> out = new ArrayList<>();
        for (Map.Entry<String, LinkPose> e : refs.entrySet()) {
            if (!e.getKey().startsWith(prefix)) continue;
            int layer;
            try {
                layer = Integer.parseInt(e.getKey().substring(prefix.length()));
            } catch (NumberFormatException bad) {
                continue;
            }
            LinkPose p = e.getValue();
            float[] here = inLook(p.animatedCenterX(timelineMs), p.animatedCenterY(timelineMs),
                    aspect, cx, cy, cos, sin, half);
            float size = p.animatedSizeFraction(timelineMs);
            float rot = p.animatedRotation(timelineMs);
            float[] base = parse(o.getVisFollowBase().get(e.getKey()));
            if (base != null) {
                // Keeps its place: moved by what the object did since its pose was taken.
                float[] then = inLook(base[0], base[1], aspect, cx, cy, cos, sin, half);
                out.add(new float[]{layer, here[0] - then[0], here[1] - then[1],
                        base[2] > 1e-4f ? size / base[2] : 1f, -(rot - base[3]), 1f});
                continue;
            }
            float restSize = 1f, restRot = 0f;
            if (p instanceof LinkFollower) {
                restSize = ((LinkFollower) p).getSizeFraction();
                restRot = ((LinkFollower) p).getRotationDeg();
            }
            // The look's Turn counts counter-clockwise (y up); the screen's, clockwise.
            out.add(new float[]{layer, here[0], here[1],
                    restSize > 1e-4f ? size / restSize : 1f, -(rot - restRot), 0f});
        }
        if (out.isEmpty()) return null;
        float[] packed = new float[out.size() * 6];
        for (int i = 0; i < out.size(); i++) System.arraycopy(out.get(i), 0, packed, i * 6, 6);
        return packed;
    }

    /** A frame position (fractions) in the look's scene units, from the box's centre, y up. */
    @NonNull
    private static float[] inLook(float fx, float fy, float aspect, float cx, float cy,
                                  float cos, float sin, float half) {
        // Frame units with the height = 1, y down, from the visualizer's centre.
        float dx = fx * aspect - cx;
        float dy = fy - cy;
        // Undo the box's turn (screen y is down, so a positive angle turns clockwise).
        float ux = dx * cos + dy * sin;
        float uy = -dx * sin + dy * cos;
        return new float[]{ux / half, -uy / half};
    }

    /** The pose a "keeps its place" follow starts from: the object as it is at {@code timelineMs}. */
    @NonNull
    public static String capture(@NonNull LinkPose p, long timelineMs) {
        return String.format(Locale.ROOT, "%.5f,%.5f,%.5f,%.3f", p.animatedCenterX(timelineMs),
                p.animatedCenterY(timelineMs), p.animatedSizeFraction(timelineMs), p.animatedRotation(timelineMs));
    }

    /** {@code [across, up, size, turn]} from {@link #capture}, or null for none or a bad one. */
    @Nullable
    static float[] parse(@Nullable String s) {
        if (s == null) return null;
        String[] v = s.split(",");
        if (v.length != 4) return null;
        try {
            return new float[]{Float.parseFloat(v[0]), Float.parseFloat(v[1]),
                    Float.parseFloat(v[2]), Float.parseFloat(v[3])};
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
