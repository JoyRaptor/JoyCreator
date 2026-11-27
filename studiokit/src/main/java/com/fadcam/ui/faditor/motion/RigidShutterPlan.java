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

    /** Most key poses a trail carries (two uniform rows each in the trail pass). */
    public static final int MAX_TRAIL_POSES = 7;

    /** Sub-steps the trail pass takes between neighbouring key poses for a Smear. */
    public static final int TRAIL_SUBSTEPS = 4;

    /**
     * STU-7 §K.2: how the trail pass draws {@link #taps} (the key poses, oldest first, raw Fade
     * weights). {@code smearScale} and {@code copyScale} turn those weights into a sum of one
     * for the smear between neighbours and for the poses alone; {@code sharp} is the weight of
     * the sharp pose (the last tap) that Mix keeps on top.
     */
    public record Trail(MotionShutter.Look look, double smearScale, double copyScale, double sharp,
                        Tap sharpTap) {}

    /**
     * A stylised window (long trail, Fade, Copies) from real earlier poses. Null when nothing
     * moves, as for the camera plan.
     */
    public static RigidShutterPlan prepareTrail(ItemMotionSampler sampler, long centerUs,
                                                MotionShutter.Controls controls, long periodUs,
                                                MotionBreaks breaks, int width, int height) {
        int poses = controls.look() == MotionShutter.Look.SMEAR
                ? MAX_TRAIL_POSES : Math.min(MAX_TRAIL_POSES, controls.copies());
        MotionShutter.Schedule schedule = controls.trailPoses(centerUs, periodUs, poses, breaks);
        long[] times = schedule.timesUs();
        if (times.length < 2) return null;
        ItemMotionSampler.Affine sharp = sampler.at(centerUs);
        if (!finite(sharp)) return null;
        ItemMotionSampler.Affine sharpInverse = invert(sharp);
        if (sharpInverse == null) return null;
        var all = new ArrayList<ItemMotionSampler.Affine>(times.length + 1);
        var taps = new ArrayList<Tap>(times.length);
        boolean differs = false;
        for (int i = 0; i < times.length; i++) {
            ItemMotionSampler.Affine pose = times[i] == centerUs ? sharp : sampler.at(times[i]);
            if (!finite(pose)) return null;
            ItemMotionSampler.Affine inverse = invert(pose);
            if (inverse == null) return null;   // a vanished pose would break the smear chain
            differs |= !pose.equals(sharp);
            all.add(pose);
            taps.add(new Tap(inverse, schedule.weights()[i]));
        }
        if (!differs) return null;
        all.add(sharp);
        double[] w = schedule.weights();
        double copies = 0;
        for (double x : w) copies += x;
        double smear = smearTotal(w);
        double mix = controls.mixPercent() / 100.0;
        double smearScale = 0, copyScale = 0;
        switch (controls.look()) {
            case SMEAR -> smearScale = smear > 0 ? mix / smear : 0;
            case COPIES -> copyScale = copies > 0 ? mix / copies : 0;
            case BOTH -> {
                smearScale = smear > 0 ? 0.5 * mix / smear : 0;
                copyScale = copies > 0 ? 0.5 * mix / copies : 0;
            }
        }
        // Rotations between poses sweep slightly outside the poses' own boxes: a wider halo.
        SweptBounds bounds = SweptBounds.ofPoses(all, width, height, 4.0);
        return new RigidShutterPlan(bounds, taps, new Trail(controls.look(), smearScale, copyScale,
                1.0 - mix, new Tap(sharpInverse, 1.0)));
    }

    /** What the trail pass sums for a Smear before scaling: eased steps between neighbours. */
    public static double smearTotal(double[] w) {
        double total = 0;
        for (int i = 0; i + 1 < w.length; i++) {
            for (int j = 0; j < TRAIL_SUBSTEPS; j++) {
                double t = (j + 0.5) / TRAIL_SUBSTEPS;
                total += w[i] + (w[i + 1] - w[i]) * t;
            }
        }
        return total;
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
