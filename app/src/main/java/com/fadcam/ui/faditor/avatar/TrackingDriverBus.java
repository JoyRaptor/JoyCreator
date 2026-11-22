package com.fadcam.ui.faditor.avatar;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * A2 (PLAN_AVATAR_STUDIO): the tracking mount point — the component a host
 * (Avatar Studio while editing; FloatingWebcamService while recording — never
 * both, the camera single-owner gotcha) mounts around ONE
 * {@link TrackingSource}. Threading is the plan's decoupling rule verbatim:
 * the source pushes raw frames on ITS thread, the
 * {@link TrackingParamPipeline} runs there (all filter state is confined to
 * that thread), and each result is published as an immutable snapshot the
 * render thread PULLS via {@link #latest()} at its own rate — tracker at
 * 15–30fps, render at vsync, no locks on the hot path.
 *
 * <p>Stale-source guard: {@link #stop()} invalidates the mounted source's
 * frames BEFORE stopping it, so a straggler frame from a dying tracker thread
 * can never republish after the host moved on (the same trap the PiP MMR
 * extraction/release lock closed).</p>
 */
public class TrackingDriverBus {

    @Nullable private TrackingSource source;
    /** Identity of the CURRENT mount — frames from any other source drop. */
    @Nullable private volatile Object mountToken;
    @Nullable private volatile Map<String, Float> latest;
    private volatile double latestTSeconds = Double.NaN;
    /** A2: a re-acquiring tracker asks the pipeline to SNAP before its next frame. */
    private volatile boolean resetRequested;

    /**
     * FACE-1: the face's calibration, settings and lost/found state. One per bus, kept across
     * mounts, so a neutral set once holds until the host lets the bus go.
     */
    @NonNull private final com.fadcam.ui.faditor.avatar.face.FaceStage face =
            new com.fadcam.ui.faditor.avatar.face.FaceStage();
    /** FACE-1: when the newest snapshot was published ({@link System#nanoTime}). */
    private volatile long latestNanos = Long.MIN_VALUE;
    /** FACE-1: camera picture to published snapshot, ms (smoothed); NaN when unknown. */
    private volatile float delayMs = Float.NaN;
    /** FACE-1: snapshots a second (smoothed). */
    private volatile float rate = 0f;

    /**
     * Mount and start a source. Any previous mount stops first (one source,
     * ever). {@code lifeSeed} seeds the deterministic life package.
     */
    public synchronized void start(@NonNull TrackingSource newSource, long lifeSeed) {
        stop();
        final TrackingParamPipeline pipeline = new TrackingParamPipeline(lifeSeed, face);
        final Object token = new Object();
        mountToken = token;
        source = newSource;
        resetRequested = false;
        newSource.start(frame -> {
            if (mountToken != token) return; // stale source — never republish
            // A2: after a tracking-loss gap the source flags a reset so One-Euro +
            // viseme SNAP to the re-acquired truth (LifeSignals keeps its schedule).
            // Consumed on the source thread, right before this frame is processed.
            if (resetRequested) {
                resetRequested = false;
                pipeline.reset();
            }
            Map<String, Float> params = pipeline.process(frame);
            long now = System.nanoTime();
            measure(frame, now);
            latestTSeconds = frame.tSeconds;
            latest = Collections.unmodifiableMap(params);
            latestNanos = now;
        });
    }

    /** FACE-1: running delivery rate and camera-to-snapshot delay (source thread). */
    private void measure(@NonNull TrackingFrame frame, long now) {
        long prev = latestNanos;
        if (prev != Long.MIN_VALUE && now > prev) {
            float r = 1e9f / (now - prev);
            rate = rate <= 0f ? r : rate + (r - rate) * 0.1f;
        }
        if (frame.captureNanos > 0 && now >= frame.captureNanos) {
            float d = (now - frame.captureNanos) / 1e6f;
            if (d < 2000f) delayMs = Float.isNaN(delayMs) ? d : delayMs + (d - delayMs) * 0.1f;
        }
    }

    /** FACE-1: the face stage (neutral, direction check, calibration, settings, found or not). */
    @NonNull
    public com.fadcam.ui.faditor.avatar.face.FaceStage face() {
        return face;
    }

    /** FACE-1: when {@link #latest()} was published ({@link System#nanoTime}); MIN_VALUE before any. */
    public long latestNanos() {
        return latestNanos;
    }

    /** FACE-1: snapshots per second, smoothed (0 before two). */
    public float rate() {
        return rate;
    }

    /** FACE-1: camera picture to snapshot in ms, smoothed; NaN when the source gives no capture time. */
    public float delayMs() {
        return delayMs;
    }

    /** Unmount: invalidate frames first, then release the source. Idempotent. */
    public synchronized void stop() {
        mountToken = null;
        if (source != null) {
            source.stop();
            source = null;
        }
        latest = null;
        latestTSeconds = Double.NaN;
        latestNanos = Long.MIN_VALUE;
        rate = 0f;
        delayMs = Float.NaN;
    }

    public boolean isActive() {
        return mountToken != null;
    }

    /**
     * A2: a re-acquiring tracker (MediaPipe face lost then found after &gt;~1s)
     * asks the mounted pipeline to SNAP rather than glide into the new pose —
     * resets One-Euro smoothing + the amplitude viseme, keeps LifeSignals
     * breathing. Idempotent; consumed once, on the source thread. No-op when no
     * source is mounted (the flag is cleared on the next {@link #start}).
     */
    public void requestReset() {
        resetRequested = true;
    }

    /** Newest smoothed param snapshot (immutable), or null before any frame. */
    @Nullable
    public Map<String, Float> latest() {
        return latest;
    }

    /** Timestamp of {@link #latest()} in source seconds; NaN when none. */
    public double latestTSeconds() {
        return latestTSeconds;
    }

    /**
     * Extract the {@code pinTarget.<partId>.x|.y} pairs from a param snapshot
     * into partId → [x,y] (view-normalized). The renderer feeds these to its
     * FABRIK hookup. Static + pure so bake replay can reuse it.
     */
    @NonNull
    public static Map<String, float[]> extractPinTargets(@NonNull Map<String, Float> params) {
        Map<String, float[]> out = new HashMap<>();
        for (Map.Entry<String, Float> e : params.entrySet()) {
            String k = e.getKey();
            if (!k.startsWith(TrackingFrame.PIN_TARGET_PREFIX)) continue;
            boolean isX = k.endsWith(TrackingFrame.PIN_TARGET_X);
            boolean isY = k.endsWith(TrackingFrame.PIN_TARGET_Y);
            if (!isX && !isY) continue;
            String partId = k.substring(TrackingFrame.PIN_TARGET_PREFIX.length(),
                    k.length() - 2);
            if (partId.isEmpty()) continue;
            float[] xy = out.get(partId);
            if (xy == null) {
                xy = new float[]{Float.NaN, Float.NaN};
                out.put(partId, xy);
            }
            xy[isX ? 0 : 1] = e.getValue();
        }
        // Drop half-pairs — a lone axis must not aim IK at NaN.
        out.entrySet().removeIf(en ->
                Float.isNaN(en.getValue()[0]) || Float.isNaN(en.getValue()[1]));
        return out;
    }
}
