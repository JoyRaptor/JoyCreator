package com.fadcam.ui.faditor.model;

import androidx.annotation.Nullable;

/**
 * THE single authority for "what gain does this volume envelope have at time t".
 *
 * <p><b>Why it is here and not next to the audio processor.</b> The envelope now has THREE
 * readers: the export's {@code VolumeAudioProcessor} (which resamples it per audio buffer), the
 * live preview (which sets a player volume per tick), and the menu's Volume row (which draws the
 * value under the playhead). Before this class the interpolation existed only inside the
 * processor, so giving the preview an envelope would have meant a second copy of the curve — and
 * a preview that fades at a different rate than the file is worse than one that does not fade at
 * all, because it looks correct. Same reasoning as {@code ChromaKey} and
 * {@code CompositingSpec.featherRadiusPx}.</p>
 *
 * <p>Android-free on purpose, so the JVM harness can reach it.</p>
 */
public final class VolumeEnvelope {

    private VolumeEnvelope() {}

    /**
     * Linear gain at {@code ms}, clamped flat beyond the first and last key.
     *
     * <p>Clamping rather than extrapolating is deliberate: an envelope authored across the
     * middle of a clip must not imply a negative gain before it starts, and a listener hears an
     * extrapolated ramp as a click.</p>
     *
     * @param times   key times, ascending, in the same domain the caller measures {@code ms} in
     * @param vols    gain at each key, parallel to {@code times}
     * @param ms      the time to evaluate at
     * @param flat    the value to return when there is no usable envelope
     */
    public static float gainAt(@Nullable long[] times, @Nullable float[] vols,
                               long ms, float flat) {
        if (times == null || vols == null || times.length == 0
                || times.length != vols.length) {
            return flat;
        }
        if (times.length == 1) return vols[0];
        if (ms <= times[0]) return vols[0];
        int last = times.length - 1;
        if (ms >= times[last]) return vols[last];
        for (int i = 0; i < last; i++) {
            if (ms >= times[i] && ms <= times[i + 1]) {
                long span = times[i + 1] - times[i];
                // A zero/negative span means two keys share a time — a step, and the LATER
                // value wins so the curve stays a function of time rather than of list order.
                if (span <= 0) return vols[i + 1];
                float frac = (ms - times[i]) / (float) span;
                return vols[i] + (vols[i + 1] - vols[i]) * frac;
            }
        }
        return vols[last];
    }
}
