package com.fadcam.ui.faditor.export;

/**
 * When a forward-decoding frame reader may show the frame it already holds (Codex round 2, R2-FX-02).
 *
 * <p>Both PiP readers decode forward and answer a request for source time T with the first frame
 * stamped at or after T. Requests only move forward, so if the frame in hand is already at or past
 * T it IS that frame, and decoding another would skip ahead. The CPU reader
 * ({@link SequentialFrameReader}) always reused it; the GPU reader ({@link SurfaceFrameReader}),
 * which every unmasked PiP takes, pulled a fresh frame on every output frame. A 24 fps clip in a
 * 30 fps project therefore ran a quarter fast, reached its end early and froze on its last frame,
 * and a slowed PiP did the same far worse.</p>
 */
public final class DecodedFrameCursor {
    private DecodedFrameCursor() {}

    /** The frame stamped {@code heldPtsUs} answers a request for {@code targetUs}. */
    public static boolean holds(long heldPtsUs, long targetUs) {
        return heldPtsUs != Long.MIN_VALUE && heldPtsUs >= targetUs;
    }
}
