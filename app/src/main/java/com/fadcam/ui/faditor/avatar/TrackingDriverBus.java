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

    /**
     * Mount and start a source. Any previous mount stops first (one source,
     * ever). {@code lifeSeed} seeds the deterministic life package.
     */
    public synchronized void start(@NonNull TrackingSource newSource, long lifeSeed) {
        stop();
        final TrackingParamPipeline pipeline = new TrackingParamPipeline(lifeSeed);
        final Object token = new Object();
        mountToken = token;
        source = newSource;
        newSource.start(frame -> {
            if (mountToken != token) return; // stale source — never republish
            Map<String, Float> params = pipeline.process(frame);
            latestTSeconds = frame.tSeconds;
            latest = Collections.unmodifiableMap(params);
        });
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
    }

    public boolean isActive() {
        return mountToken != null;
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
