package com.fadcam.ui.faditor.markers;

/** Timeline-clock boundary policy. Playback ownership and player operations stay in the Activity. */
public final class MarkerLoopPolicy {
    public static final long NO_RESTART = -1L;

    private MarkerLoopPolicy() { }

    public static final class Range {
        public final long inMs;
        public final long outMs;

        private Range(long inMs, long outMs) {
            this.inMs = inMs;
            this.outMs = outMs;
        }

        public long durationMs() { return outMs - inMs; }

        /** Out is exclusive, matching audio clip intervals and range-export semantics. */
        public boolean contains(long positionMs) { return positionMs >= inMs && positionMs < outMs; }
    }

    /**
     * Intersect saved metadata with the present timeline without altering the saved endpoints.
     * Invalid/empty/reversed ranges never loop, including a region wholly past the current end.
     */
    public static Range effectiveRange(MarkerWorkspace.Region region, long durationMs) {
        if (region == null || durationMs <= 0L || region.outMs <= region.inMs) return null;
        long inMs = Math.max(0L, region.inMs);
        long outMs = Math.min(durationMs, region.outMs);
        return outMs > inMs ? new Range(inMs, outMs) : null;
    }

    public static Range effectiveRange(MarkerWorkspace.State state, long durationMs) {
        return state == null ? null : effectiveRange(state.activeRegion(), durationMs);
    }

    public static long restartAt(long positionMs, long durationMs, MarkerWorkspace.State state,
                                 boolean playbackRequested, boolean gestureActive) {
        return restartAt(state, positionMs, durationMs, playbackRequested, gestureActive);
    }

    /**
     * Request a wrap only while transport playback is explicitly wanted. Pass the playback
     * request, not merely isPlaying(): a decoder can be buffering or naturally ended at Out.
     * Cancel that request for a user pause/scrub before calling this method. A region selection
     * or loop toggle by itself must never restart a paused editor. Seeking to the returned In
     * must also reset the Activity's image/audio-tail clocks, then explicitly resume its media.
     */
    public static long restartAt(MarkerWorkspace.State state, long positionMs, long durationMs,
                                 boolean playbackRequested, boolean gestureActive) {
        if (state == null || !state.loopEnabled || !playbackRequested || gestureActive) {
            return NO_RESTART;
        }
        Range range = effectiveRange(state.activeRegion(), durationMs);
        return range != null && !range.contains(positionMs) ? range.inMs : NO_RESTART;
    }
}
