package com.fadcam.ui.faditor.avatar;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Bake-to-parameter-track (PLAN_AVATAR_STUDIO §MINED, binding doctrine): a
 * recorded avatar performance stored as RESOLVED driver params sampled over
 * time — the same plain name→value maps a {@link TrackingSource} emits and
 * {@link PuppetPoseResolver#resolve} consumes. Recording captures what the
 * smoothing pipeline PRODUCED (post One-Euro/viseme/life), so replay skips the
 * pipeline entirely and the webcam never re-runs at export: replayed params →
 * resolver → identical puppet, deterministic because the resolver's discrete
 * hysteresis lives in a caller-owned {@link PuppetPoseResolver.DiscreteState}
 * that a replay consumer creates fresh and steps in time order.
 *
 * <p>Sample times are track-relative milliseconds (0 = performance start).
 * {@link #sampleAt} lerps CONTINUOUS values between neighbors; a param missing
 * on one side of the bracket HOLDS the side that has it (tracking loss froze
 * the live value — the bake must not invent a fade to zero). Discrete
 * cell/z/flip switching stays the resolver's job at replay time, exactly as
 * live.</p>
 *
 * <p>Self-serializing like {@link AvatarRig} (schemaVersion + tolerant reads)
 * so a track can ride a project item or an {@code .avatar} bundle sidecar.
 * Pure Java + Gson — JVM-harness testable (tools/jvm-harness).</p>
 */
public final class AvatarParamTrack {

    public static final int SCHEMA_VERSION = 1;

    /** Params that are DISCRETE CLASS INDICES, not continuous values — replay
     *  steps them (hold-until-next-sample) instead of lerping. */
    private static final java.util.Set<String> STEP_PARAMS =
            java.util.Collections.singleton(SpectralVisemeAnalyzer.PARAM_VISEME);

    /** Parallel, timesMs strictly ascending. */
    private final List<Long> timesMs = new ArrayList<>();
    private final List<Map<String, Float>> samples = new ArrayList<>();

    /**
     * Append one sample. Non-monotonic stamps are nudged forward by 1ms (the
     * same guard the MediaPipe LIVE_STREAM feed applies) so a rewound clock
     * can never corrupt the ordering invariant. The map is copied.
     */
    public void add(long tMs, @NonNull Map<String, Float> params) {
        if (!timesMs.isEmpty()) {
            long last = timesMs.get(timesMs.size() - 1);
            if (tMs <= last) tMs = last + 1;
        }
        timesMs.add(tMs);
        samples.add(new HashMap<>(params));
    }

    public int size() {
        return timesMs.size();
    }

    public boolean isEmpty() {
        return timesMs.isEmpty();
    }

    /** Last sample time (= performance length for a 0-based track); 0 when empty. */
    public long durationMs() {
        return timesMs.isEmpty() ? 0 : timesMs.get(timesMs.size() - 1);
    }

    /**
     * Interpolated params at {@code tMs}: clamped to the first/last sample
     * outside the recorded range, exact at a sample, lerped between brackets
     * (key union; one-sided keys hold — see class doc). Empty map when the
     * track is empty (resolver reads missing params as 0 = neutral).
     */
    @NonNull
    public Map<String, Float> sampleAt(long tMs) {
        int n = timesMs.size();
        if (n == 0) return new HashMap<>();
        if (tMs <= timesMs.get(0)) return new HashMap<>(samples.get(0));
        if (tMs >= timesMs.get(n - 1)) return new HashMap<>(samples.get(n - 1));

        // Binary search for the bracketing pair [lo, hi).
        int lo = 0, hi = n - 1;
        while (hi - lo > 1) {
            int mid = (lo + hi) >>> 1;
            if (timesMs.get(mid) <= tMs) lo = mid;
            else hi = mid;
        }
        long t0 = timesMs.get(lo), t1 = timesMs.get(hi);
        if (t0 == tMs) return new HashMap<>(samples.get(lo));
        float f = (tMs - t0) / (float) (t1 - t0);

        Map<String, Float> a = samples.get(lo), b = samples.get(hi);
        Map<String, Float> out = new HashMap<>();
        for (Map.Entry<String, Float> e : a.entrySet()) {
            Float bv = b.get(e.getKey());
            // Discrete class params STEP (hold the earlier side) instead of
            // lerping — interpolating a viseme class index between takes would
            // pass through transient WRONG classes (2→5 visits 3 and 4), and
            // mouth visemes are hard snaps by doctrine.
            out.put(e.getKey(), bv == null || STEP_PARAMS.contains(e.getKey())
                    ? e.getValue()
                    : e.getValue() + (bv - e.getValue()) * f);
        }
        for (Map.Entry<String, Float> e : b.entrySet()) {
            if (!a.containsKey(e.getKey())) out.put(e.getKey(), e.getValue());
        }
        return out;
    }

    // ── Serialization (AvatarRig idiom: schemaVersion + tolerant reads) ──────

    @NonNull
    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("schemaVersion", SCHEMA_VERSION);
        JsonArray arr = new JsonArray();
        for (int i = 0; i < timesMs.size(); i++) {
            JsonObject s = new JsonObject();
            s.addProperty("t", timesMs.get(i));
            JsonObject p = new JsonObject();
            for (Map.Entry<String, Float> e : samples.get(i).entrySet()) {
                p.addProperty(e.getKey(), e.getValue());
            }
            s.add("p", p);
            arr.add(s);
        }
        o.add("samples", arr);
        return o;
    }

    /** Tolerant read: junk entries are skipped, order is re-normalized via add(). */
    @NonNull
    public static AvatarParamTrack fromJson(@Nullable JsonObject o) {
        AvatarParamTrack track = new AvatarParamTrack();
        if (o == null || !o.has("samples") || !o.get("samples").isJsonArray()) return track;
        for (JsonElement el : o.getAsJsonArray("samples")) {
            if (!el.isJsonObject()) continue;
            JsonObject s = el.getAsJsonObject();
            if (!s.has("t") || !s.has("p") || !s.get("p").isJsonObject()) continue;
            try {
                Map<String, Float> params = new HashMap<>();
                for (Map.Entry<String, JsonElement> e : s.getAsJsonObject("p").entrySet()) {
                    params.put(e.getKey(), e.getValue().getAsFloat());
                }
                track.add(s.get("t").getAsLong(), params);
            } catch (RuntimeException ignored) {
                // one bad sample must not sink the performance
            }
        }
        return track;
    }
}
