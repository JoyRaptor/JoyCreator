package com.fadcam.ui.faditor.motion;

import java.util.ArrayList;
import java.util.List;

/** Per-item inverse sampling data for a rigid export pass, in canvas pixel coordinates. */
public record RigidShutterPlan(SweptBounds bounds, List<Tap> taps, Trail trail) {
    public RigidShutterPlan {
        taps = List.copyOf(taps);
    }

    /** The camera shutter plan every older caller builds: no trail. */
    public RigidShutterPlan(SweptBounds bounds, List<Tap> taps) {
        this(bounds, taps, null);
    }

    /** The tap counts the smooth pass is built for; a plan uses the smallest that is enough. */
    public static final int[] TAP_PROGRAMS = {12, 24, 48, 64};

    /** Most taps a smooth plan carries (the largest program). */
    public static final int MAX_TAPS = 64;

    /** Most taps spent on the smear itself; the rest are left for Copies and the sharp pose. */
    public static final int MAX_SMEAR_TAPS = 48;

    /** Wanted gap between neighbouring taps, in canvas pixels: closer than this reads as one streak. */
    public static final double TAP_GAP_PX = 3.0;

    /** Dense look at the path, to place taps where the thing moves most. */
    static final int PATH_PROBES = 64;

    /**
     * STU-7 §K.2: a smooth plan's taps are real poses at real moments, oldest first, each with
     * its weight already scaled. {@code smooth[i]} says tap i is a smear tap whose edges soften
     * toward the next smear tap (a copy or the sharp pose stays crisp). {@code program} is the
     * tap count of the pass to draw it with.
     */
    public record Trail(MotionShutter.Look look, boolean[] smooth, int program) {}

    /**
     * A smooth plan: a stylised window (long trail, Fade, Copies), or a camera window whose six
     * taps would show as copies. Every tap is the thing where it really was at that moment, so a
     * swing or a spin is exact with no maths per pixel. Null when nothing moves.
     *
     * <p>Smear taps are placed by how far the thing's corners travel (dense where it moves
     * fast), as many as needed to keep neighbours about {@link #TAP_GAP_PX} apart, up to
     * {@link #MAX_SMEAR_TAPS}. Copies are evenly spread in time, since a ghost is a moment.</p>
     */
    public static RigidShutterPlan prepareTrail(ItemMotionSampler sampler, long centerUs,
                                                MotionShutter.Controls controls, long periodUs,
                                                MotionBreaks breaks, int width, int height) {
        return prepareTrail(sampler, centerUs, controls, periodUs, breaks, MotionBreaks.none(),
                width, height);
    }

    /** {@link #prepareTrail} with the source's own fences (a camera window on a sprite). */
    public static RigidShutterPlan prepareTrail(ItemMotionSampler sampler, long centerUs,
                                                MotionShutter.Controls controls, long periodUs,
                                                MotionBreaks breaks, MotionBreaks sourceBreaks,
                                                int width, int height) {
        MotionShutter.Look look = controls.look();
        MotionShutter.Schedule dense = controls.trailPoses(centerUs, periodUs, PATH_PROBES, breaks,
                sourceBreaks);
        if (dense.timesUs().length < 2) return null;
        ItemMotionSampler.Affine sharp = sampler.at(centerUs);
        if (!finite(sharp)) return null;
        ItemMotionSampler.Affine sharpInverse = invert(sharp);
        if (sharpInverse == null) return null;
        ItemMotionSampler.Affine[] probe = new ItemMotionSampler.Affine[dense.timesUs().length];
        double[] run = new double[probe.length];
        for (int i = 0; i < probe.length; i++) {
            probe[i] = sampler.at(dense.timesUs()[i]);
            if (!finite(probe[i])) return null;
            if (i > 0) run[i] = run[i - 1] + travel(probe[i - 1], probe[i]);
        }
        double path = run[run.length - 1];
        if (path <= 1e-6) return null;

        double mix = controls.mixPercent() / 100.0;
        var taps = new ArrayList<Tap>();
        var smooth = new ArrayList<Boolean>();
        var poses = new ArrayList<ItemMotionSampler.Affine>();

        // the smear: enough taps to keep them TAP_GAP_PX apart, placed by travel
        if (look != MotionShutter.Look.COPIES) {
            int n = (int) Math.max(2, Math.min(MAX_SMEAR_TAPS, Math.ceil(path / TAP_GAP_PX) + 1));
            double[] at = byTravel(run, n);
            double[] w = new double[n];
            ItemMotionSampler.Affine[] p = new ItemMotionSampler.Affine[n];
            for (int i = 0; i < n; i++) {
                long t = Math.round(lerp(dense.timesUs(), at[i]));
                p[i] = t == centerUs ? sharp : sampler.at(t);
                if (!finite(p[i]) || invert(p[i]) == null) return null;
                w[i] = lerp(dense.weights(), at[i]);
            }
            double total = 0;
            for (double x : w) total += x;
            // A camera window spreads the thing's light over its path (weights sum to Mix). A long
            // trail is a stroke: about one thing's worth of cover per thing-width of path (the
            // pass clamps overlap to full cover), thinning by Fade.
            double scale = total > 0 ? mix / total : 0;
            if (controls.isLongTrail()) scale = Math.max(scale, mix * strokeScale(p, w, path));
            if (look == MotionShutter.Look.BOTH) scale *= 0.5;
            for (int i = 0; i < n; i++) {
                taps.add(new Tap(invert(p[i]), w[i] * scale));
                smooth.add(i + 1 < n);
                poses.add(p[i]);
            }
        }
        // Copies: ghosts at evenly spaced moments, crisp
        if (look != MotionShutter.Look.SMEAR) {
            int n = Math.min(controls.copies(), MAX_TAPS - taps.size() - 1);
            double[] at = evenly(probe.length, n);
            double[] w = new double[at.length];
            double total = 0;
            for (int i = 0; i < at.length; i++) total += (w[i] = lerp(dense.weights(), at[i]));
            double scale = total > 0 ? mix / total : 0;
            if (look == MotionShutter.Look.BOTH) scale *= 0.5;
            for (int i = 0; i < at.length; i++) {
                long t = Math.round(lerp(dense.timesUs(), at[i]));
                ItemMotionSampler.Affine pose = t == centerUs ? sharp : sampler.at(t);
                if (!finite(pose) || invert(pose) == null) return null;
                taps.add(new Tap(invert(pose), w[i] * scale));
                smooth.add(false);
                poses.add(pose);
            }
        }
        // Mix keeps the sharp thing on top
        if (mix < 1.0) {
            taps.add(new Tap(sharpInverse, 1.0 - mix));
            smooth.add(false);
        }
        poses.add(sharp);
        boolean[] soft = new boolean[smooth.size()];
        for (int i = 0; i < soft.length; i++) soft[i] = smooth.get(i);
        int program = TAP_PROGRAMS[TAP_PROGRAMS.length - 1];
        for (int size : TAP_PROGRAMS) if (size >= taps.size()) { program = size; break; }
        SweptBounds bounds = SweptBounds.ofPoses(poses, width, height, 4.0);
        return new RigidShutterPlan(bounds, taps, new Trail(look, soft, program));
    }

    /**
     * {@code n} positions along the probes (fractional probe indices, first 0, last the final
     * probe) at equal shares of the travel run: dense where it moves fast.
     */
    static double[] byTravel(double[] run, int n) {
        int m = run.length;
        double total = run[m - 1];
        if (total <= 1e-9) return evenly(m, n);
        double[] at = new double[Math.max(2, n)];
        int j = 0;
        for (int k = 0; k < at.length; k++) {
            double target = total * k / (at.length - 1);
            while (j < m - 2 && run[j + 1] < target) j++;
            double span = run[j + 1] - run[j];
            at[k] = j + (span <= 1e-12 ? 0 : Math.max(0, Math.min(1, (target - run[j]) / span)));
        }
        at[0] = 0;
        at[at.length - 1] = m - 1;
        return at;
    }

    /** {@code n} evenly spread probe positions, first and last included. */
    static double[] evenly(int count, int n) {
        double[] at = new double[Math.max(2, n)];
        for (int k = 0; k < at.length; k++) at[k] = (count - 1) * (double) k / (at.length - 1);
        return at;
    }

    /** {@code v} at fractional index {@code x}. */
    static double lerp(double[] v, double x) {
        int i = (int) Math.max(0, Math.min(v.length - 2, Math.floor(x)));
        double f = x - i;
        return v[i] + (v[i + 1] - v[i]) * f;
    }

    static double lerp(long[] v, double x) {
        int i = (int) Math.max(0, Math.min(v.length - 2, Math.floor(x)));
        double f = x - i;
        return v[i] + (v[i + 1] - v[i]) * f;
    }

    /**
     * Whether a camera shutter's own taps would show as separate copies: some corner moves more
     * than {@code px} canvas pixels between two of its taps. The pass then draws the camera
     * window from a smooth plan instead (same exposure, no copies).
     */
    public static boolean tapsWouldStep(ItemMotionSampler sampler, MotionShutter.Schedule schedule,
                                        double px) {
        long[] t = schedule.timesUs();
        ItemMotionSampler.Affine prev = null;
        for (long time : t) {
            ItemMotionSampler.Affine pose = sampler.at(time);
            if (!finite(pose)) return false;
            if (prev != null && travel(prev, pose) > px) return true;
            prev = pose;
        }
        return false;
    }

    /** How far the thing's farthest corner moved between two poses, in canvas pixels. */
    static double travel(ItemMotionSampler.Affine a, ItemMotionSampler.Affine b) {
        double best = 0;
        for (int u = 0; u <= 1; u++) {
            for (int v = 0; v <= 1; v++) {
                double ax = a.xx() * u + a.xy() * v + a.tx(), ay = a.yx() * u + a.yy() * v + a.ty();
                double bx = b.xx() * u + b.xy() * v + b.tx(), by = b.yx() * u + b.yy() * v + b.ty();
                best = Math.max(best, Math.hypot(bx - ax, by - ay));
            }
        }
        return best;
    }

    /**
     * Weight per tap that makes a stroke: one thing's worth of cover for every thing-width the
     * path covers. Taps per thing-width = size / gap; each weighs 1 / that (and 1 / the mean Fade
     * weight, so Fade thins the stroke instead of dimming all of it).
     */
    static double strokeScale(ItemMotionSampler.Affine[] poses, double[] w, double path) {
        double size = 0;
        for (ItemMotionSampler.Affine p : poses) {
            size += 0.5 * (Math.hypot(p.xx(), p.yx()) + Math.hypot(p.xy(), p.yy()));
        }
        size /= Math.max(1, poses.length);
        double mean = 0;
        for (double x : w) mean += x;
        mean /= Math.max(1, w.length);
        if (size <= 1e-6 || mean <= 1e-9) return 0;
        double gap = path / Math.max(1, w.length - 1);
        double perWidth = Math.max(1.0, size / gap);
        return 1.0 / (perWidth * mean);
    }

    /** Maps a top-left canvas pixel to a top-left source UV with its shutter weight. */
    public record Tap(ItemMotionSampler.Affine canvasToUv, double weight) {}

    /**
     * Null preserves the existing sharp route for an unchanged or unusable pose.
     * A zero-area subframe has transparent coverage, so omitting its tap does not
     * redistribute its weight to the remaining subframes.
     */
    public static RigidShutterPlan prepare(ItemMotionSampler sampler, long centerUs,
                                           MotionShutter.Schedule schedule,
                                           int width, int height, double haloPx) {
        long[] times = schedule.timesUs();
        double[] weights = schedule.weights();
        if (times.length != weights.length) throw new IllegalArgumentException("Mismatched shutter samples");
        ItemMotionSampler.Affine sharp = sampler.at(centerUs);
        if (!finite(sharp)) return null;
        var poses = new ArrayList<ItemMotionSampler.Affine>(times.length);
        var taps = new ArrayList<Tap>(times.length);
        boolean differs = false;
        for (int i = 0; i < times.length; i++) {
            double weight = weights[i];
            if (weight == 0.0) continue;
            ItemMotionSampler.Affine pose = times[i] == centerUs ? sharp : sampler.at(times[i]);
            if (!finite(pose)) return null;
            differs |= !pose.equals(sharp);
            ItemMotionSampler.Affine inverse = invert(pose);
            if (inverse != null) {
                poses.add(pose);
                taps.add(new Tap(inverse, weight));
            }
        }
        if (!differs) return null;
        return new RigidShutterPlan(SweptBounds.ofPoses(poses, width, height, haloPx), taps);
    }

    public static RigidShutterPlan sharp(ItemMotionSampler sampler, long timeUs,
                                         int width, int height) {
        ItemMotionSampler.Affine pose = sampler.at(timeUs);
        if (!finite(pose)) return null;
        ItemMotionSampler.Affine inverse = invert(pose);
        if (inverse == null) return null;
        return new RigidShutterPlan(SweptBounds.ofPoses(List.of(pose), width, height, 1),
                List.of(new Tap(inverse, 1)));
    }

    private static ItemMotionSampler.Affine invert(ItemMotionSampler.Affine a) {
        double determinant = a.xx() * a.yy() - a.xy() * a.yx();
        if (!Double.isFinite(determinant) || determinant == 0.0) return null;
        double xx = a.yy() / determinant;
        double xy = -a.xy() / determinant;
        double yx = -a.yx() / determinant;
        double yy = a.xx() / determinant;
        var result = new ItemMotionSampler.Affine(xx, xy, yx, yy,
                -(xx * a.tx() + xy * a.ty()), -(yx * a.tx() + yy * a.ty()));
        return finite(result) ? result : null;
    }

    private static boolean finite(ItemMotionSampler.Affine a) {
        return Double.isFinite(a.xx()) && Double.isFinite(a.xy()) && Double.isFinite(a.yx())
                && Double.isFinite(a.yy()) && Double.isFinite(a.tx()) && Double.isFinite(a.ty());
    }
}
