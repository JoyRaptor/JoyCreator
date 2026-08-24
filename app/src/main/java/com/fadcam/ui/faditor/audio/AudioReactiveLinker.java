package com.fadcam.ui.faditor.audio;

import androidx.annotation.NonNull;

import com.fadcam.ui.faditor.model.VolumeKeyframe;

import java.util.ArrayList;
import java.util.List;

/**
 * Audio-reactive property links — SPEC_AUDIO_UX_V1 row {@code D8}.
 *
 * <p><b>What it does.</b> Turns the energy of an audio band into keyframes on any keyframable
 * property: a scale that pulses with the kick, an opacity that breathes with a pad, a rotation
 * driven by a hi-hat. The band envelope goes in; a sparse, ordinary, editable curve comes out.</p>
 *
 * <p><b>Why keyframes rather than a live binding.</b> Same reasoning as {@link Ducker}, and it
 * matters more here. A live "link" is invisible: the user sees a property moving and cannot tell
 * why, cannot nudge one beat that landed wrong without breaking the whole effect, and cannot
 * render it anywhere the link engine does not run. Baking to keyframes means the result is made
 * of the same material as everything else the user already knows how to drag. The generator is a
 * first draft with an opinion, not a black box.</p>
 *
 * <p><b>Sparsity is the whole problem.</b> A hundred keyframes a second is a correct answer and a
 * useless one. The output here is simplified with Ramer-Douglas-Peucker, which is the right tool
 * for this specific reason: it keeps the points that carry the SHAPE — the peak of every hit —
 * and discards the ones sitting on a straight run between them. Naive alternatives fail exactly
 * where it matters: fixed-interval sampling either misses transients or floods the timeline, and
 * emit-on-change thresholding drops the top of a fast peak, which is the one frame the user
 * actually wanted.</p>
 */
public final class AudioReactiveLinker {

    private AudioReactiveLinker() {}

    /** Tuning for {@link #link}. */
    public static final class Params {
        /** Property value when the band is silent. */
        public float outMin = 1.0f;
        /** Property value when the band is at its loudest in this clip. */
        public float outMax = 1.5f;

        /**
         * Rise time. Very short by design.
         *
         * <p>Attack and release are asymmetric because ears forgive a slow fall and notice a
         * late rise, and a symmetric filter fast enough to catch a transient makes the tail
         * chatter. But the rise must be nearly instant or the follower never REACHES the peak:
         * at 30ms (the first value tried here) a one-pole closes ~28% of the gap per 10ms
         * frame, while a drum hit has decayed away in about 25 frames — so the output topped
         * out at 1.26 of a 1.0-1.5 range and the beat visibly under-hit. Measured, not guessed;
         * see tools/jvm-harness/run-reactive.sh.</p>
         */
        public long attackMs = 5;

        /** Fall time. Longer than attack — see {@link #attackMs}. */
        public long releaseMs = 180;

        /**
         * Ignore band energy below this fraction of the clip's peak.
         *
         * <p>Without a floor, room tone and bleed drive the property continuously and the effect
         * never rests — the thing looks broken rather than reactive.</p>
         */
        public float noiseFloorFraction = 0.08f;

        /**
         * Simplification tolerance, as a fraction of the output range.
         *
         * <p>0.02 keeps a curve visually indistinguishable from the raw envelope while typically
         * cutting the keyframe count by more than an order of magnitude. Raise it for a coarser,
         * more hand-editable curve.</p>
         */
        public float simplifyTolerance = 0.02f;

        /** Never emit keyframes closer together than this, whatever the tolerance says. */
        public long minSpacingMs = 40;
    }

    /**
     * Map a band envelope onto a property curve.
     *
     * @param bandEnvelope     per-frame energy for ONE band of the driving audio.
     * @param framesPerSecond  frame rate of that envelope.
     * @param sourceStartMs    where the envelope's first frame sits on the TIMELINE.
     * @param targetStartMs    where the driven object starts on the TIMELINE.
     * @param targetDurationMs the driven object's duration.
     * @return keyframes in TARGET-LOCAL ms, ascending. Empty when the band says nothing —
     *         which is a real answer, not a failure.
     */
    @NonNull
    public static List<VolumeKeyframe> link(
            @NonNull int[] bandEnvelope, double framesPerSecond,
            long sourceStartMs, long targetStartMs, long targetDurationMs,
            @NonNull Params p) {

        List<VolumeKeyframe> out = new ArrayList<>();
        if (bandEnvelope.length < 2 || framesPerSecond <= 0 || targetDurationMs <= 0) return out;
        // A zero-width output range is "do not drive this property". Returning nothing leaves
        // the property genuinely untouched rather than pinning a flat line over whatever the
        // user had already drawn there.
        if (p.outMax == p.outMin) return out;

        int peak = 0;
        for (int v : bandEnvelope) peak = Math.max(peak, Math.abs(v));
        if (peak <= 0) return out;                       // silent band: nothing to react to

        // The floor is measured from the band's OWN quiet level, not from zero, and there has
        // to be real contrast between quiet and loud before anything is driven at all.
        //
        // Scaling the floor off the peak alone makes a CONSTANT band -- a steady tone, hum, or
        // room noise in that band -- normalise to 1.0 everywhere, pinning the property at its
        // maximum for the whole clip and never resting. That is not a reactive effect, it is a
        // stuck one, and it looks like a bug rather than a feature. The harness caught exactly
        // this (constant tone drove the property to 1.5 and held).
        //
        // This is the same mistake, in the same shape, that Ducker made with speech detection:
        // a threshold relative to the peak cannot tell "loud" from "unchanging".
        final int quiet = percentile10(bandEnvelope);
        if (peak - quiet < peak * MIN_CONTRAST) return out;

        final double msPerFrame = 1000.0 / framesPerSecond;
        final double floor = quiet + (peak - quiet) * (double) p.noiseFloorFraction;
        final double span = peak - floor;
        if (span <= 0) return out;

        // 1. Normalise past the floor, then asymmetric-smooth. One-pole per direction: the
        //    coefficient is the fraction of the gap closed per frame, so a longer time constant
        //    means a smaller step.
        final double aUp = coeff(p.attackMs, msPerFrame);
        final double aDown = coeff(p.releaseMs, msPerFrame);
        float[] level = new float[bandEnvelope.length];
        double s = 0;
        for (int i = 0; i < bandEnvelope.length; i++) {
            double norm = (Math.abs(bandEnvelope[i]) - floor) / span;
            if (norm < 0) norm = 0;
            if (norm > 1) norm = 1;
            s += (norm > s ? aUp : aDown) * (norm - s);
            level[i] = (float) s;
        }

        // 2. Map to the property range, in TARGET-LOCAL time. Frames outside the target are
        //    dropped here rather than later, so simplification only ever sees points that will
        //    actually be emitted — otherwise it spends its error budget on discarded samples.
        List<long[]> tsRaw = new ArrayList<>();
        List<Float> vsRaw = new ArrayList<>();
        for (int i = 0; i < level.length; i++) {
            long localMs = sourceStartMs + Math.round(i * msPerFrame) - targetStartMs;
            if (localMs < 0 || localMs > targetDurationMs) continue;
            tsRaw.add(new long[]{localMs});
            vsRaw.add(p.outMin + level[i] * (p.outMax - p.outMin));
        }
        if (tsRaw.size() < 2) return out;

        long[] ts = new long[tsRaw.size()];
        float[] vs = new float[vsRaw.size()];
        for (int i = 0; i < ts.length; i++) {
            ts[i] = tsRaw.get(i)[0];
            vs[i] = vsRaw.get(i);
        }

        // 3. Simplify, then enforce minimum spacing.
        boolean[] keep = new boolean[ts.length];
        keep[0] = true;
        keep[ts.length - 1] = true;
        double tol = Math.abs(p.outMax - p.outMin) * p.simplifyTolerance;
        rdp(ts, vs, 0, ts.length - 1, tol, keep);

        long lastMs = Long.MIN_VALUE;
        float lastV = 0f;
        for (int i = 0; i < ts.length; i++) {
            if (!keep[i]) continue;
            boolean isLast = (i == ts.length - 1);
            // Minimum spacing thins FLAT runs; it must not thin a transient. Applied to every
            // close-together point regardless of value, it displaced the peak of each hit: a
            // beat at 1000ms emitted its rest point at 990ms and could not emit again until
            // 1050ms, so the peak landed 50ms LATE and the ramp between them read 1.198 at
            // 1020ms instead of the 1.40 the follower had actually reached. Fifty milliseconds
            // is visible on a beat-synced pulse, and it is the one thing this feature exists to
            // get right.
            //
            // So a point is dropped for spacing only when it is ALSO close in value to the last
            // one emitted. Clutter is many keyframes saying the same thing; a fast rise is not
            // clutter.
            if (!isLast && lastMs != Long.MIN_VALUE
                    && ts[i] - lastMs < p.minSpacingMs
                    && Math.abs(vs[i] - lastV) < tol) {
                continue;
            }
            out.add(new VolumeKeyframe(ts[i], vs[i]));
            lastMs = ts[i];
            lastV = vs[i];
        }
        return out;
    }

    /**
     * Minimum loud-to-quiet contrast before a band is considered to be saying anything.
     *
     * <p>Below this the band is some steady thing and driving a property from it produces a
     * stuck effect, not a reactive one. 10% sits far under any real percussive content and far
     * above a merely noisy band.</p>
     */
    private static final double MIN_CONTRAST = 0.10;

    /**
     * The band's quiet level: the 10th percentile of |amplitude|.
     *
     * <p>A percentile rather than the minimum, so a single dropout frame cannot drag the floor
     * to zero and hand back the measured-from-zero behaviour this exists to avoid.</p>
     */
    private static int percentile10(@NonNull int[] env) {
        int[] sorted = new int[env.length];
        for (int i = 0; i < env.length; i++) sorted[i] = Math.abs(env[i]);
        java.util.Arrays.sort(sorted);
        return sorted[Math.min(sorted.length - 1, sorted.length / 10)];
    }

    /** Fraction of the remaining gap a one-pole filter closes each frame. */
    private static double coeff(long timeConstantMs, double msPerFrame) {
        if (timeConstantMs <= 0) return 1.0;
        return 1.0 - Math.exp(-msPerFrame / timeConstantMs);
    }

    /**
     * Ramer-Douglas-Peucker over (time, value), marking the points worth keeping.
     *
     * <p>Iterative rather than recursive on purpose: a long envelope at 100 fps is tens of
     * thousands of points, and the recursive form stack-overflows on precisely the input this
     * feature is for — a long music track.</p>
     */
    private static void rdp(@NonNull long[] ts, @NonNull float[] vs,
                            int first, int last, double tol, @NonNull boolean[] keep) {
        java.util.ArrayDeque<int[]> stack = new java.util.ArrayDeque<>();
        stack.push(new int[]{first, last});
        while (!stack.isEmpty()) {
            int[] seg = stack.pop();
            int a = seg[0], b = seg[1];
            if (b <= a + 1) continue;
            double dt = ts[b] - ts[a];
            double dv = vs[b] - vs[a];
            double worst = -1;
            int worstIdx = -1;
            for (int i = a + 1; i < b; i++) {
                // Vertical distance to the chord, which is the right measure here: the axes are
                // time and a property value with no common unit, so a perpendicular distance
                // would mix seconds with opacity and change meaning whenever the zoom changes.
                double onChord = dt == 0 ? vs[a] : vs[a] + dv * ((ts[i] - ts[a]) / dt);
                double d = Math.abs(vs[i] - onChord);
                if (d > worst) { worst = d; worstIdx = i; }
            }
            if (worstIdx >= 0 && worst > tol) {
                keep[worstIdx] = true;
                stack.push(new int[]{a, worstIdx});
                stack.push(new int[]{worstIdx, b});
            }
        }
    }
}
