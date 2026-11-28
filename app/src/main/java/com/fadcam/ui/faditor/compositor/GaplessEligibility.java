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

    /**
     * Plain cuts, NORMAL loops, PING_PONG loops whose reversed copy is baked, and image clips; no transitions. Two or more
     * clips, or ONE clip with a loop extension: the engine's first job (07-02) was crossing cuts, so it asked for two
     * clips, and when loops and the true ping-pong reverse were added to it a lone looped clip was never let in. It fell
     * back to the legacy preview, which replays a ping-pong forward (STU-F7, Note 9, 11-28). One plain clip keeps the
     * legacy path: it has nothing to gain.
     */
    public static boolean eligible(@Nullable Timeline timeline, @Nullable ReversedReady reversed) {
        if (timeline == null) return false;
        int count = timeline.getClipCount();
        if (count == 0) return false;
        if (count == 1) {
            Clip only = timeline.getClip(0);
            if (only == null || only.isImageClip() || !only.hasLoopExtension()) return false;
        }
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
