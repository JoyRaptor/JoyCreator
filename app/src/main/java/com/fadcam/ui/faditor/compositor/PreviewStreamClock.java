package com.fadcam.ui.faditor.compositor;

import androidx.media3.common.C;

import com.fadcam.ui.faditor.export.VolumeAudioProcessor;

/**
 * Tells a preview clip's volume processor where on the clip's own clock its sound resumes after
 * the audio sink flushes it (Codex round 2, lead W1).
 *
 * <p>The preview player flushes its processors on every seek and on every speed nudge from drift
 * correction. The processor counted frames from zero after each flush, so a fade-out heard from
 * the middle of a clip started over from the first key, and a drift nudge mid-fade made the
 * level jump back. Export never flushes mid-item, so the file was right and the preview was not.</p>
 *
 * <p>How: the sink hands every buffer over with its presentation time. Those times carry a
 * constant of the player's own (renderer offset, clipping), so the clock learns it once after
 * each SEEK: the first buffer after the sink's flush is the place the player was asked to seek
 * to. A flush with no seek behind it (a speed nudge) keeps the constant, and the buffer being
 * handled is exactly where the processors restart.</p>
 *
 * <p>Threads: {@link #seekingTo} on the app thread; the rest on the playback thread.</p>
 */
public final class PreviewStreamClock implements VolumeAudioProcessor.StreamClock {

    private volatile long seekTargetClipUs = 0L;
    private volatile boolean anchorPending = true;
    private volatile long ptsMinusClipUs = 0L;
    private volatile long lastPtsUs = C.TIME_UNSET;

    /** The player is about to seek to {@code clipUs} on the clip's clock (window time). */
    public void seekingTo(long clipUs) {
        seekTargetClipUs = Math.max(0L, clipUs);
    }

    /** The sink was flushed for a seek or a reset: the next buffer re-learns the constant. */
    public void onSinkFlush() {
        anchorPending = true;
        lastPtsUs = C.TIME_UNSET;
    }

    /** The sink is handling a buffer stamped {@code presentationTimeUs}. */
    public void onBuffer(long presentationTimeUs) {
        if (anchorPending) {
            ptsMinusClipUs = presentationTimeUs - seekTargetClipUs;
            anchorPending = false;
        }
        lastPtsUs = presentationTimeUs;
    }

    @Override
    public long nextInputClipUs() {
        long pts = lastPtsUs;
        if (pts == C.TIME_UNSET) return C.TIME_UNSET;
        return Math.max(0L, pts - ptsMinusClipUs);
    }
}
