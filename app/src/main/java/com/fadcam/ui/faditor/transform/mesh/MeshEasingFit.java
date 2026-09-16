package com.fadcam.ui.faditor.transform.mesh;

/**
 * AFTER THINNING, GIVE EACH SURVIVING KEY THE CURVE THE PERFORMANCE ACTUALLY HAD.
 *
 * <p>JoyRaptor, 2026-09-15, on keyframe reduction: <i>"we already have a lot of different types of
 * easing that it could look at and identify what it's closest to."</i> This is that.
 *
 * <h3>Why it matters more than it sounds</h3>
 * <p>Thinning a live take is not polish — a key per frame cannot be drawn inside a bar or hit with
 * a finger, so the tape design only works because the keys got thinned. But thinning throws away
 * the SHAPE between two survivors and replaces it with whatever the default easing happens to be.
 * A hand that whipped out and settled becomes a hand that glides, and the performance the user
 * gave is quietly not the one that plays back.
 *
 * <p>Fitting a curve recovers most of that for free: the samples are already in hand at the moment
 * of thinning, and every easing the app can express is a one-line function. Where nothing fits
 * better than a straight line, a straight line is what it writes — this is allowed to conclude
 * that a motion was linear, which is the answer that must never be rounded up into a flourish.
 *
 * <h3>Why it fits ONE curve per gap rather than per component</h3>
 * <p>A pose is every pin at once and a key carries a single easing name. Fitting each component
 * separately would produce an answer that cannot be stored, and picking one of them would be
 * arbitrary. So the error is summed across the components being fitted — which for a chain is the
 * whole limb, and a limb that eases as one unit is exactly what a limb does.
 *
 * <p>Android-free, so {@code tools/jvm-harness} can pin it.
 */
public final class MeshEasingFit {

    private MeshEasingFit() {}

    /**
     * The curves worth trying, in the order a tie is broken.
     *
     * <p>LINEAR is first deliberately: when two curves fit equally well the flatter claim wins,
     * so a motion that was merely straight is never described as a spring. The showy ones
     * (OVERSHOOT, BOUNCE, the springs) are here because a recorded hand really does overshoot,
     * and leaving them out would mean a whipped gesture could only ever be approximated by
     * EASE_OUT — which is the thinning artefact this class exists to remove.
     */
    public static final String[] CANDIDATES = {
            "LINEAR", "EASE_IN_OUT", "EASE_OUT", "EASE_IN",
            "EASE_OUT_EXPO", "EASE_IN_EXPO",
            "OVERSHOOT", "ANTICIPATE",
            "SPRING_SOFT", "SPRING", "SPRING_BOUNCY", "BOUNCE",
    };

    /**
     * How much better than LINEAR a curve has to be before it is believed.
     *
     * <p>Without a margin, sampling noise decides, and two takes of the same gesture come back
     * wearing different easings — which looks like the app being unpredictable rather than like
     * the performances differing. 12% is comfortably above the noise and well below the
     * difference between a glide and a whip.
     */
    private static final float MARGIN = 0.88f;

    /** Samples per gap. Nine interior points resolve an overshoot without costing anything real. */
    private static final int SAMPLES = 9;

    /**
     * Give every key in {@code [fromMs, toMs]} the easing that best describes what the ORIGINAL
     * dense recording did on its way to the next key.
     *
     * @param thinned    the track after simplification — the keys that survived
     * @param dense      the same range BEFORE simplification; the shape being recovered
     * @param components which components to judge by; null means all of them
     * @param curve      the app's easing implementation, so there is no second copy here
     * @return how many keys were given a curve other than the one they already had
     */
    public static int fit(MeshPoseTrack thinned, MeshPoseTrack dense, int[] components,
                          long fromMs, long toMs, MeshPoseTrack.Curve curve) {
        if (thinned == null || dense == null || curve == null) return 0;
        if (thinned.size() < 2 || dense.size() < 3) return 0;

        int arity = thinned.arity();
        if (arity <= 0 || dense.arity() != arity) return 0;
        int[] comps = components;
        if (comps == null) {
            comps = new int[arity];
            for (int i = 0; i < arity; i++) comps[i] = i;
        }

        long[] times = thinned.times();
        if (times == null || times.length < 2) return 0;

        float[] a = new float[arity], b = new float[arity];
        float[] want = new float[arity], got = new float[arity];
        int changed = 0;

        for (int k = 0; k < times.length - 1; k++) {
            long t0 = times[k], t1 = times[k + 1];
            if (t1 <= t0) continue;
            if (t0 < fromMs || t1 > toMs) continue;
            // A gap with nothing thinned out of it has no evidence either way, so it is left
            // exactly as the user or the recorder made it.
            if (!denseHasInteriorSamples(dense, t0, t1)) continue;
            if (!thinned.valueAt(t0, a) || !thinned.valueAt(t1, b)) continue;

            String best = null;
            float bestErr = Float.MAX_VALUE;
            float linearErr = Float.MAX_VALUE;

            for (String name : CANDIDATES) {
                float err = 0f;
                for (int s = 1; s <= SAMPLES; s++) {
                    float u = s / (float) (SAMPLES + 1);
                    long at = t0 + Math.round(u * (t1 - t0));
                    if (!dense.valueAt(at, want)) { err = Float.MAX_VALUE; break; }
                    float eased = curve.apply(name, u);
                    for (int c : comps) {
                        if (c < 0 || c >= arity) continue;
                        got[c] = a[c] + (b[c] - a[c]) * eased;
                        float d = got[c] - want[c];
                        err += d * d;
                    }
                }
                if ("LINEAR".equals(name)) linearErr = err;
                if (err < bestErr) { bestErr = err; best = name; }
            }

            // Believe a fancier curve only when it is MEANINGFULLY better than a straight line.
            if (best == null) continue;
            if (!"LINEAR".equals(best) && bestErr > linearErr * MARGIN) best = "LINEAR";

            for (MeshPoseTrack.Pose pose : thinned.poses()) {
                if (pose.timeMs != t0) continue;
                // A pose an animation preset owns is not ours to re-describe.
                if (pose.presetOwned) break;
                if (!best.equals(pose.easing)) { pose.easing = best; changed++; }
                break;
            }
        }
        return changed;
    }

    /** True when the dense recording holds at least one key strictly between these two. */
    private static boolean denseHasInteriorSamples(MeshPoseTrack dense, long t0, long t1) {
        for (MeshPoseTrack.Pose p : dense.poses()) {
            if (p.timeMs > t0 && p.timeMs < t1) return true;
        }
        return false;
    }
}
