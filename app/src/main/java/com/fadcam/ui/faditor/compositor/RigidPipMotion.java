package com.fadcam.ui.faditor.compositor;

import com.fadcam.ui.faditor.keyframe.KeyframeSet;
import com.fadcam.ui.faditor.model.ChromaKey;
import com.fadcam.ui.faditor.model.Clip;
import com.fadcam.ui.faditor.motion.MotionBreaks;

/** The ordinary PiP shutter samples the PiP transform in the master editor clock. */
public final class RigidPipMotion {
    private RigidPipMotion() {}

    public static boolean supports(Clip clip) {
        KeyframeSet keys = clip.getOverlayTransform();
        return keys != null && keys.isAnimated() && !clip.hasActiveFx()
                && !ChromaKey.isActive(clip.getCompositing());
    }

    public static MotionBreaks fences(Clip clip, long pieceStartUs, long pieceEndUs) {
        long start = clip.getOverlayStartMs();
        long end = start + Math.max(0, clip.getTrimmedDurationMs());
        var fences = new MotionBreaks.Builder().add(pieceStartUs).add(pieceEndUs)
                .add(start * 1000L).add((end + 1L) * 1000L);
        for (var track : clip.getOverlayTransform().tracks()) fences.addSteps(track, 0L);
        return fences.build();
    }

    public static float opacity(Clip clip, long timeMs) {
        KeyframeSet keys = clip.getOverlayTransform();
        return keys == null ? 1f
                : Math.max(0f, Math.min(1f, keys.valueAt(KeyframeSet.OPACITY, timeMs, 1f)));
    }
}
