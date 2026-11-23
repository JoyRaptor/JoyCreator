package com.fadcam.ui.faditor.motion;

import java.util.ArrayDeque;

/** Pairs a latched preview texture with the decoded frame's editor timestamp. */
public final class PreviewMotionClock {
    private static final int MAX_PENDING = 32;

    public record Frame(long editorTimeUs, long periodUs) {}

    private record Pending(long releaseTimeNs, Frame frame) {}

    private final ArrayDeque<Pending> pending = new ArrayDeque<>(MAX_PENDING);
    private final MotionFrameCadence cadence = new MotionFrameCadence();
    private long generation;
    private Pending latched;

    /** Route or time discontinuity. Callbacks from an older listener no longer match. */
    public synchronized long reset() {
        pending.clear();
        latched = null;
        cadence.reset();
        return ++generation;
    }

    /** The release time identifies the texture. The decoded presentation time measures the shutter. */
    public synchronized void onFrame(long epoch, long releaseTimeNs, long editorTimeUs) {
        if (epoch != generation) return;
        Pending last = pending.peekLast();
        if (last != null && last.releaseTimeNs == releaseTimeNs) return;
        if (latched != null && latched.releaseTimeNs == releaseTimeNs) return;
        Frame frame = new Frame(editorTimeUs, cadence.observe(editorTimeUs));
        if (pending.size() == MAX_PENDING) pending.removeFirst();
        pending.addLast(new Pending(releaseTimeNs, frame));
    }

    /** A repeated draw of the same texture reuses the original exposure. */
    public synchronized Frame latch(long textureTimestampNs) {
        if (latched != null && latched.releaseTimeNs == textureTimestampNs) return latched.frame;
        for (Pending candidate : pending) {
            if (candidate.releaseTimeNs != textureTimestampNs) continue;
            while (pending.removeFirst() != candidate) {
                // SurfaceTexture may skip previously delivered frames.
            }
            latched = candidate;
            return candidate.frame;
        }
        return null;
    }
}
