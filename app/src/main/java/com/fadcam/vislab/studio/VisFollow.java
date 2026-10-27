package com.fadcam.vislab.studio;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.ui.faditor.model.LinkFollower;
import com.fadcam.ui.faditor.model.LinkPose;
import com.fadcam.ui.faditor.model.WaveformOverlayInstance;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Where the look layers that follow a Studio object sit at one moment (roadmap R8: animate a
 * dummy and the logo inside the look goes with it).
 *
 * <p>The object's centre is measured from the visualizer's own centre, turned back by the
 * visualizer's rotation, and divided by half the box's short side: that is the look's scene unit
 * (the short side runs -1..+1), with y up. So a layer whose own knobs put it at the middle lands
 * exactly on the object, wherever the visualizer box is, however it is sized or turned. Size and
 * Turn follow how the object has grown and turned from its own resting values.</p>
 *
 * <p>Packed for {@code VisRenderer.setLayerFollow}: {@code [layerIndex, x, y, sizeMul, turnDeg]}.</p>
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
            // Frame units with the height = 1, y down, from the visualizer's centre.
            float dx = p.animatedCenterX(timelineMs) * aspect - cx;
            float dy = p.animatedCenterY(timelineMs) - cy;
            // Undo the box's turn (screen y is down, so a positive angle turns clockwise).
            float ux = dx * cos + dy * sin;
            float uy = -dx * sin + dy * cos;
            float restSize = 1f, restRot = 0f;
            if (p instanceof LinkFollower) {
                restSize = ((LinkFollower) p).getSizeFraction();
                restRot = ((LinkFollower) p).getRotationDeg();
            }
            float size = restSize > 1e-4f ? p.animatedSizeFraction(timelineMs) / restSize : 1f;
            // The look's Turn counts counter-clockwise (y up); the screen's, clockwise.
            float turn = -(p.animatedRotation(timelineMs) - restRot);
            out.add(new float[]{layer, ux / half, -uy / half, size, turn});
        }
        if (out.isEmpty()) return null;
        float[] packed = new float[out.size() * 5];
        for (int i = 0; i < out.size(); i++) System.arraycopy(out.get(i), 0, packed, i * 5, 5);
        return packed;
    }
}
