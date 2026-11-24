package com.fadcam.ui.faditor.motion.flow;

import android.content.res.AssetManager;

import androidx.annotation.Nullable;
import androidx.media3.common.util.GlUtil;

import java.io.IOException;

/**
 * Gate and lifetime seam for C6 Fast footage preview.
 *
 * <p>The host calls this immediately after the ungraded OES frame has been copied into its
 * upright 2D staging texture. It must pass
 * {@code LaunchGate.hidden(context, FastFootagePreviewOwner.LAUNCH_KEY)} for the launch decision.
 * A hidden, project-disabled, item-disabled, still, frozen, or incomplete request
 * makes no GL call and clears temporal history. Re-enabling therefore starts sharp and cannot
 * reuse a frame observed while the feature was unavailable.
 *
 * <p>{@link FastFootageGatherGl} consumes the packed flow and renders into a separate colour
 * target. The CPU {@link FootageGather} supplies the reference pixel math.
 */
public final class FastFootagePreviewOwner {
    public static final String LAUNCH_KEY = "studio_motion_blur";

    /** One borrowed estimate. Valid only until the next advancing observation or release. */
    public record Frame(LiveFlowGl.Estimate estimate, double sourceSpeed) {
        public Frame {
            if (estimate == null || !Double.isFinite(sourceSpeed) || sourceSpeed == 0.0) {
                throw new IllegalArgumentException("Invalid Fast footage preview frame");
            }
        }

        /** Scales the backward flow for a signed project-time tap offset from the frame centre. */
        public double scaleForOffset(double projectTimeOffsetUs) {
            double pairUs = (double) estimate.previousPtsUs() - estimate.currentPtsUs();
            return pairUs == 0.0 ? 0.0 : (projectTimeOffsetUs * sourceSpeed) / pairUs;
        }
    }

    private final LiveFlowGl liveFlow;
    private boolean observing;
    private boolean released;

    public FastFootagePreviewOwner(AssetManager assets, long maxGapUs) {
        liveFlow = new LiveFlowGl(assets, maxGapUs);
    }

    /**
     * Observes one already-staged source frame when every C6 gate is open.
     *
     * @param launchHidden exact result of {@code LaunchGate.hidden(context, LAUNCH_KEY)}
     * @param projectEnabled the project's Motion Blur master tick
     * @param clipEnabled the current footage clip's Motion Blur tick
     * @param videoSource false for image/still masters
     * @return a flow frame after a continuous neighbour exists, otherwise {@code null} (sharp)
     */
    @Nullable
    public Frame observe(boolean launchHidden, boolean projectEnabled, boolean clipEnabled,
                         boolean videoSource, double sourceSpeed, int stagedTexture,
                         long sourcePtsUs, long continuityEpoch,
                         @Nullable LiveFlowGl.SourceGeometry geometry)
            throws IOException, GlUtil.GlException {
        requireOpen();
        boolean enabled = !launchHidden && projectEnabled && clipEnabled && videoSource
                && Double.isFinite(sourceSpeed) && sourceSpeed != 0.0;
        if (!enabled || geometry == null || stagedTexture <= 0) {
            reset();
            return null;
        }
        observing = true;
        LiveFlowGl.Estimate estimate = liveFlow.observe(
                stagedTexture, sourcePtsUs, continuityEpoch, geometry);
        return estimate == null ? null : new Frame(estimate, sourceSpeed);
    }

    /** Flush after seek/cut metadata changes before the next staged frame. */
    public void reset() {
        requireOpen();
        if (observing) liveFlow.reset();
        observing = false;
    }

    /** Safe to call twice on the owning GL thread. */
    public void release() throws GlUtil.GlException {
        if (released) return;
        released = true;
        observing = false;
        liveFlow.release();
    }

    private void requireOpen() {
        if (released) throw new IllegalStateException("Fast footage preview has been released");
    }
}
