package com.fadcam.ui.faditor.avatar;

import androidx.annotation.NonNull;

import java.util.HashMap;
import java.util.Map;

/**
 * A2 (PLAN_AVATAR_STUDIO): a bank of {@link OneEuroFilter}s keyed by driver
 * parameter name — the single smoothing stage between a tracker (MediaPipe /
 * ML Kit / scripted) and {@link PuppetPoseResolver#resolve}. The MINED rule is
 * One-Euro on ALL continuous tracking inputs; discrete/boolean triggers
 * (blink fired, hand entered) must NOT pass through here — smoothing a
 * threshold signal just delays it.
 *
 * <p>Filters are created on first sight of a param name and keep their state
 * across frames. {@link #reset()} drops all state (tracking loss / camera
 * restart — snap to the new truth, never glide across a gap).</p>
 */
public class DriverParamSmoother {

    private final float minCutoff;
    private final float beta;
    private final Map<String, OneEuroFilter> filters = new HashMap<>();

    public DriverParamSmoother() {
        this(OneEuroFilter.DEFAULT_MIN_CUTOFF, OneEuroFilter.DEFAULT_BETA);
    }

    public DriverParamSmoother(float minCutoff, float beta) {
        this.minCutoff = minCutoff;
        this.beta = beta;
    }

    /**
     * Smooth every param in {@code raw} in one pass. Returns a NEW map (the
     * tracker thread owns {@code raw}; the render thread reads the result).
     * Params absent this frame keep their filter state for when they return.
     */
    @NonNull
    public Map<String, Float> smooth(@NonNull Map<String, Float> raw, double tSeconds) {
        Map<String, Float> out = new HashMap<>(raw.size() * 2);
        for (Map.Entry<String, Float> e : raw.entrySet()) {
            Float v = e.getValue();
            if (v == null || v.isNaN()) continue; // a bad tracker sample never
                                                  // enters filter state
            OneEuroFilter f = filters.get(e.getKey());
            if (f == null) {
                f = new OneEuroFilter(minCutoff, beta, OneEuroFilter.DEFAULT_D_CUTOFF);
                filters.put(e.getKey(), f);
            }
            out.put(e.getKey(), f.filter(v, tSeconds));
        }
        return out;
    }

    /** Drop all filter state (tracking lost / camera swapped hosts). */
    public void reset() {
        filters.clear();
    }
}
