package com.fadcam.ui.faditor.keyframe;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * An animated scalar property: a sorted list of {@link Keyframe}s plus the
 * interpolation that turns "what is this property's value at time T?" into a
 * single float. One track per property (e.g. "x", "scale", "opacity").
 *
 * <p>Empty track → the consumer uses its static default. One keyframe → a
 * constant value. Before the first / after the last keyframe the value is held
 * (clamped), which is what users expect from an animation track.</p>
 */
public class KeyframeTrack {

    /** Property name this track drives, e.g. "x", "y", "scale", "opacity", "rotation". */
    @NonNull public final String property;

    @NonNull public final List<Keyframe> keyframes = new ArrayList<>();

    public KeyframeTrack(@NonNull String property) {
        this.property = property;
    }

    public boolean isEmpty() {
        return keyframes.isEmpty();
    }

    public boolean isAnimated() {
        return keyframes.size() >= 2;
    }

    /** Insert a keyframe, replacing any existing one at the same time, keeping order. */
    public void put(long timeMs, float value, @NonNull Easing easing) {
        for (int i = 0; i < keyframes.size(); i++) {
            if (keyframes.get(i).timeMs == timeMs) {
                keyframes.get(i).value = value;
                keyframes.get(i).easing = easing;
                return;
            }
        }
        keyframes.add(new Keyframe(timeMs, value, easing));
        Collections.sort(keyframes, (a, b) -> Long.compare(a.timeMs, b.timeMs));
    }

    public void removeAt(long timeMs) {
        for (int i = 0; i < keyframes.size(); i++) {
            if (keyframes.get(i).timeMs == timeMs) {
                keyframes.remove(i);
                return;
            }
        }
    }

    /**
     * The property's value at {@code timeMs}. Returns {@code fallback} only when
     * the track is empty; otherwise it interpolates between surrounding
     * keyframes (clamped to the first/last value outside the range).
     */
    public float valueAt(long timeMs, float fallback) {
        if (keyframes.isEmpty()) return fallback;
        if (keyframes.size() == 1) return keyframes.get(0).value;

        if (timeMs <= keyframes.get(0).timeMs) return keyframes.get(0).value;
        Keyframe last = keyframes.get(keyframes.size() - 1);
        if (timeMs >= last.timeMs) return last.value;

        for (int i = 0; i < keyframes.size() - 1; i++) {
            Keyframe a = keyframes.get(i);
            Keyframe b = keyframes.get(i + 1);
            if (timeMs >= a.timeMs && timeMs <= b.timeMs) {
                long span = b.timeMs - a.timeMs;
                if (span <= 0) return b.value;
                float t = (timeMs - a.timeMs) / (float) span;
                float eased = a.easing.apply(t);
                return a.value + (b.value - a.value) * eased;
            }
        }
        return last.value;
    }

    /**
     * Sub-frame evaluation for motion sampling. Integral inputs are delegated to the original
     * evaluator to preserve its boundary rules and exact float arithmetic.
     */
    public float valueAt(double timeMs, float fallback) {
        if (!Double.isFinite(timeMs)) throw new IllegalArgumentException("Non-finite keyframe time");
        if (timeMs == Math.rint(timeMs)) return valueAt((long) timeMs, fallback);

        for (int i = 1; i < keyframes.size(); i++) {
            Keyframe b = keyframes.get(i);
            if (timeMs >= b.timeMs) continue;
            Keyframe a = keyframes.get(i - 1);
            if (timeMs <= a.timeMs) return a.value;
            float progress = (float) ((timeMs - a.timeMs) / (b.timeMs - (double) a.timeMs));
            // Rounding to 1 in float before a discrete key would make HOLD jump early.
            float eased = a.easing.apply(Math.min(progress, Math.nextDown(1f)));
            return a.value + (b.value - a.value) * eased;
        }
        return keyframes.isEmpty() ? fallback : keyframes.get(keyframes.size() - 1).value;
    }

    @NonNull
    public KeyframeTrack copy() {
        KeyframeTrack t = new KeyframeTrack(property);
        for (Keyframe k : keyframes) t.keyframes.add(k.copy());
        return t;
    }
}
