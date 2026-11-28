package com.fadcam.ui.faditor.compositor;

import androidx.annotation.Nullable;

import com.fadcam.ui.faditor.model.Clip;
import com.fadcam.ui.faditor.model.Timeline;

/**
 * Which timelines the gapless preview engine ({@link MasterPlaybackEngine}) serves, as a pure rule so it can be tested
 * without a player. The engine's {@code isEligible} is this.
 */
public final class GaplessEligibility {
    private GaplessEligibility() {}

    /** Whether the baked reversed copy of a ping-pong clip is ready (null: unknown, so not ready). */
    public interface ReversedReady { boolean ready(Clip c); }

    /** Today's rule: two or more clips, no transitions; loops only with their reverse ready. */
    public static boolean eligible(@Nullable Timeline timeline, @Nullable ReversedReady reversed) {
        if (timeline == null) return false;
        int count = timeline.getClipCount();
        if (count < 2) return false;
        if (timeline.getTransitions() != null && !timeline.getTransitions().isEmpty()) return false;
        for (int i = 0; i < count; i++) {
            Clip c = timeline.getClip(i);
            if (c == null) return false;
            if (c.isImageClip()) {
                if (c.getSourceUri() == null) return false;
                continue;
            }
            int loopMode = c.getLoopMode();
            if (loopMode == Clip.LOOP_MODE_OFF || loopMode == Clip.LOOP_MODE_NORMAL) continue;
            if (loopMode == Clip.LOOP_MODE_PING_PONG) {
                if (reversed != null && reversed.ready(c)) continue;
                return false;
            }
            return false;
        }
        return true;
    }
}
