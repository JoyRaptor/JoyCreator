package com.fadcam.ui.faditor.motion;

import java.util.ArrayList;
import java.util.List;

/** Per-item inverse sampling data for a rigid export pass, in canvas pixel coordinates. */
public record RigidShutterPlan(SweptBounds bounds, List<Tap> taps) {
    public RigidShutterPlan {
        taps = List.copyOf(taps);
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
