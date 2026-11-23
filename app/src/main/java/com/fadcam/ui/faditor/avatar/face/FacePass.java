package com.fadcam.ui.faditor.avatar.face;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.ui.faditor.avatar.AvatarParamTrack;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * FACE-1: recording the face into a take in passes, the way the web pilot records every channel
 * (al-inputs.js, "takes"): a pass writes only the lanes it records ({@link FaceSignals#LANES}:
 * head, mouth, mood, blink) over the span it ran, crossing in and out of the old take over a
 * quarter of a second; everything else is the same afterwards, sample for sample. The result is a
 * new {@link AvatarParamTrack}, so one Undo (swap the take back) takes the whole pass back.
 *
 * <p>And editable afterwards, lane by lane, without recording again: {@link #clearLane},
 * {@link #scaleLane} (a stronger or softer smile), {@link #smoothLane} and {@link #shiftLane} (nudge
 * the mouth earlier or later against the voice). Pure Java.</p>
 */
public final class FacePass {

    /** The hand-over at each end of a pass (the pilot's FADE). */
    public static final long FADE_MS = 250;

    @Nullable private final AvatarParamTrack before;
    @NonNull private final Set<String> lanes;
    @NonNull private final AvatarParamTrack live = new AvatarParamTrack();

    /**
     * @param before the take so far (null for a first pass)
     * @param lanes  the lanes this pass records ({@link FaceSignals#LANE_HEAD} etc.)
     */
    public FacePass(@Nullable AvatarParamTrack before, @NonNull Collection<String> lanes) {
        this.before = before;
        this.lanes = new HashSet<>(lanes);
    }

    /** One live frame at take time {@code tMs} (frames must come in time order). */
    public void add(long tMs, @NonNull Map<String, Float> face) {
        Map<String, Float> mine = new HashMap<>();
        for (Map.Entry<String, Float> e : face.entrySet()) {
            if (owns(e.getKey())) mine.put(e.getKey(), e.getValue());
        }
        live.add(tMs, mine);
    }

    public boolean isEmpty() { return live.isEmpty(); }

    /** The take with this pass written in. */
    @NonNull
    public AvatarParamTrack finish() {
        if (live.isEmpty()) return before != null ? before.copy() : new AvatarParamTrack();
        if (before == null || before.isEmpty()) return live.copy();
        long t0 = live.timeMsAt(0), t1 = live.timeMsAt(live.size() - 1);
        TreeSet<Long> times = new TreeSet<>();
        for (int i = 0; i < before.size(); i++) times.add(before.timeMsAt(i));
        for (int i = 0; i < live.size(); i++) times.add(live.timeMsAt(i));
        // Exact corners of the hand-over so the cross-fade lands where it says.
        times.add(Math.max(0, t0 - FADE_MS));
        times.add(t1 + FADE_MS);

        AvatarParamTrack out = new AvatarParamTrack();
        for (long t : times) {
            Map<String, Float> old = before.sampleAt(t);
            float k = weight(t, t0, t1);
            if (k > 0f) {
                Map<String, Float> now = live.sampleAt(Math.max(t0, Math.min(t1, t)));
                for (Map.Entry<String, Float> e : now.entrySet()) {
                    String n = e.getKey();
                    Float ov = old.get(n);
                    float o = ov == null ? 0f : ov;
                    float v = FaceSignals.isStep(n) ? (k >= 0.5f ? e.getValue() : o) : o + (e.getValue() - o) * k;
                    old.put(n, v);
                }
            }
            out.add(t, old);
        }
        return out;
    }

    /** 1 inside the pass, 0 outside, a smooth quarter-second hand-over on both sides. */
    static float weight(long t, long t0, long t1) {
        if (t >= t0 && t <= t1) return 1f;
        long d = t < t0 ? t0 - t : t - t1;
        if (d >= FADE_MS) return 0f;
        float k = 1f - d / (float) FADE_MS;
        return k * k * (3f - 2f * k);
    }

    private boolean owns(@NonNull String name) {
        String lane = FaceSignals.laneOf(name);
        return lane != null && lanes.contains(lane);
    }

    // ── editing afterwards, lane by lane ──

    /** The take without one lane (it reads as rest). */
    @NonNull
    public static AvatarParamTrack clearLane(@NonNull AvatarParamTrack take, @NonNull String lane) {
        return rewrite(take, lane, (n, v, t) -> null);
    }

    /** One lane stronger (gain > 1) or softer (gain < 1), kept within its range. */
    @NonNull
    public static AvatarParamTrack scaleLane(@NonNull AvatarParamTrack take, @NonNull String lane, float gain) {
        return rewrite(take, lane, (n, v, t) -> {
            if (FaceSignals.isStep(n)) return v;
            float lo = FaceSignals.isSigned(n) ? -1f : 0f;
            return Math.max(lo, Math.min(1f, v * gain));
        });
    }

    /** One lane calmer: each value averaged with its neighbours within ±{@code halfMs}. */
    @NonNull
    public static AvatarParamTrack smoothLane(@NonNull AvatarParamTrack take, @NonNull String lane, long halfMs) {
        final AvatarParamTrack src = take;
        return rewrite(take, lane, (n, v, t) -> {
            if (FaceSignals.isStep(n) || halfMs <= 0) return v;
            float sum = 0f;
            int k = 0;
            for (int i = -4; i <= 4; i++) {
                Float s = src.sampleAt(t + i * halfMs / 4).get(n);
                if (s != null) { sum += s; k++; }
            }
            return k == 0 ? v : sum / k;
        });
    }

    /** One lane moved in time: + plays it later, - earlier (the ends hold). */
    @NonNull
    public static AvatarParamTrack shiftLane(@NonNull AvatarParamTrack take, @NonNull String lane, long ms) {
        final AvatarParamTrack src = take;
        return rewrite(take, lane, (n, v, t) -> src.sampleAt(t - ms).get(n));
    }

    private interface Edit {
        @Nullable Float apply(@NonNull String name, float value, long tMs);
    }

    @NonNull
    private static AvatarParamTrack rewrite(@NonNull AvatarParamTrack take, @NonNull String lane, @NonNull Edit edit) {
        AvatarParamTrack out = new AvatarParamTrack();
        for (int i = 0; i < take.size(); i++) {
            long t = take.timeMsAt(i);
            Map<String, Float> s = take.sampleAtIndex(i), w = new HashMap<>();
            for (Map.Entry<String, Float> e : s.entrySet()) {
                String n = e.getKey();
                if (!lane.equals(FaceSignals.laneOf(n))) { w.put(n, e.getValue()); continue; }
                Float v = edit.apply(n, e.getValue(), t);
                if (v != null) w.put(n, v);
            }
            out.add(t, w);
        }
        return out;
    }
}
