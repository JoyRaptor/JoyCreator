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
    public static final int MAX_TRAIL_POSES = 12;

    /** Steps the trail pass takes between neighbouring key poses for a Smear. */
    public static final int TRAIL_SUBSTEPS = 4;

    /** Dense look at the path, to place the key poses where the thing moves most. */
    static final int PATH_PROBES = 48;

    /** Below this turn (radians) between key poses a segment is a plain slide. */
    static final double SLIDE_TURN = 0.01;

    /**
     * STU-7 §K.2: how the trail pass draws {@link #taps} (the key poses, oldest first, raw Fade
     * weights). {@code smearScale} and {@code copyScale} turn those weights into the smear's and
     * the copies' share; {@code sharp} is the weight of the sharp pose that Mix keeps on top.
     * {@code segments} has one entry per pair of neighbouring poses: how to get from one to the
     * next as a turn about a fixed point, so the smear follows arcs (see {@link Segment}).
     */
    public record Trail(MotionShutter.Look look, double smearScale, double copyScale, double sharp,
                        Tap sharpTap, List<Segment> segments) {
        public Trail {
            segments = List.copyOf(segments);
        }
    }

    /**
     * The motion from one key pose to the next as a similarity about a fixed point: in complex
     * numbers z' = p + r (z - p) with r = e^(logScale + i turn). Part of it, r^t, is the same turn
     * and zoom about the same point, so the steps in between trace the true arc of a swing or a
     * spin (a straight line for a plain slide). A slide (no turn, no zoom) is stored as
     * {@code (dx, dy, 0, 0)}.
     */
    public record Segment(double x, double y, double logScale, double turn) {
        public boolean slide() { return logScale == 0.0 && turn == 0.0; }

        /** Canvas point {@code z} moved back by fraction {@code t} of this segment. */
        public double[] back(double zx, double zy, double t) {
            if (slide()) return new double[]{zx - t * x, zy - t * y};
            double k = Math.exp(-t * logScale), a = -t * turn;
            double c = Math.cos(a), s = Math.sin(a), dx = zx - x, dy = zy - y;
            return new double[]{x + k * (c * dx - s * dy), y + k * (s * dx + c * dy)};
        }
    }

    /**
     * A stylised window (long trail, Fade, Copies) from real earlier poses. Null when nothing
     * moves, as for the camera plan.
     *
     * <p>Smear: up to {@link #MAX_TRAIL_POSES} key poses, placed by how far the thing's corners
     * travel (dense where it moves fast, so the swinging end of an arm gets more of them).
     * Copies and Both: {@code copies} poses evenly spread in time, since a ghost is a moment.</p>
     */
    public static RigidShutterPlan prepareTrail(ItemMotionSampler sampler, long centerUs,
                                                MotionShutter.Controls controls, long periodUs,
                                                MotionBreaks breaks, int width, int height) {
        boolean smearOnly = controls.look() == MotionShutter.Look.SMEAR;
        MotionShutter.Schedule dense = controls.trailPoses(centerUs, periodUs, PATH_PROBES, breaks);
        if (dense.timesUs().length < 2) return null;
        ItemMotionSampler.Affine sharp = sampler.at(centerUs);
        if (!finite(sharp)) return null;
        ItemMotionSampler.Affine sharpInverse = invert(sharp);
        if (sharpInverse == null) return null;

        ItemMotionSampler.Affine[] probe = new ItemMotionSampler.Affine[dense.timesUs().length];
        for (int i = 0; i < probe.length; i++) {
            probe[i] = sampler.at(dense.timesUs()[i]);
            if (!finite(probe[i])) return null;
        }
        int[] pick = smearOnly ? byTravel(probe, MAX_TRAIL_POSES)
                : evenly(probe.length, Math.min(MAX_TRAIL_POSES, controls.copies()));
        // The turn so far at each probe, laps included: neighbouring probes are close enough
        // that each one's own turn is under half a circle, so a fast spin keeps its direction.
        double[] turned = new double[probe.length];
        for (int i = 1; i < probe.length; i++) {
            turned[i] = turned[i - 1] + segment(probe[i - 1], probe[i], Double.NaN).turn();
        }

        var all = new ArrayList<ItemMotionSampler.Affine>(pick.length + 1);
        var taps = new ArrayList<Tap>(pick.length);
        double[] w = new double[pick.length];
        boolean differs = false;
        for (int i = 0; i < pick.length; i++) {
            long t = dense.timesUs()[pick[i]];
            ItemMotionSampler.Affine pose = t == centerUs ? sharp : sampler.at(t);
            if (!finite(pose)) return null;
            ItemMotionSampler.Affine inverse = invert(pose);
            if (inverse == null) return null;   // a vanished pose would break the smear chain
            differs |= !pose.equals(sharp);
            all.add(pose);
            w[i] = dense.weights()[pick[i]];
            taps.add(new Tap(inverse, w[i]));
        }
        if (!differs) return null;
        var segments = new ArrayList<Segment>(pick.length - 1);
        for (int i = 0; i + 1 < all.size(); i++) {
            segments.add(segment(all.get(i), all.get(i + 1), turned[pick[i + 1]] - turned[pick[i]]));
        }
        all.add(sharp);

        double copies = 0;
        for (double x : w) copies += x;
        double smear = smearTotal(w);
        double mix = controls.mixPercent() / 100.0;
        double smearScale = 0, copyScale = 0;
        // A camera shutter spreads the object's light over its path (exposure: weights sum to
        // one). A long trail is a stylised stroke: as solid as the object, thinning by Fade, so
        // its samples weigh one object's worth per object width along the path (the pass clamps
        // any overlap to full coverage).
        double stroke = controls.isLongTrail() ? strokeScale(all.subList(0, all.size() - 1), w) : 0;
        switch (controls.look()) {
            case SMEAR -> smearScale = smear > 0 ? Math.max(mix / smear, mix * stroke) : 0;
            case COPIES -> copyScale = copies > 0 ? mix / copies : 0;
            case BOTH -> {
                smearScale = smear > 0 ? 0.5 * Math.max(mix / smear, mix * stroke) : 0;
                copyScale = copies > 0 ? 0.5 * mix / copies : 0;
            }
        }
        // The arcs between poses bulge outside the poses' own boxes: bound the steps too.
        var swept = new ArrayList<ItemMotionSampler.Affine>(all);
        for (int i = 0; i < segments.size(); i++) {
            for (int j = 1; j < TRAIL_SUBSTEPS; j++) {
                swept.add(stepPose(all.get(i), segments.get(i), (double) j / TRAIL_SUBSTEPS));
            }
        }
        SweptBounds bounds = SweptBounds.ofPoses(swept, width, height, 4.0);
        return new RigidShutterPlan(bounds, taps, new Trail(controls.look(), smearScale, copyScale,
                1.0 - mix, new Tap(sharpInverse, 1.0), segments));
    }

    /** {@code from} moved forward by fraction {@code t} of {@code seg} (uv to canvas). */
    public static ItemMotionSampler.Affine stepPose(ItemMotionSampler.Affine from, Segment seg, double t) {
        // forward = R^t o from; R^t maps z to p + r^t (z - p), i.e. back() with -t
        double[] o = seg.back(from.tx(), from.ty(), -t);
        double[] ex = seg.back(from.tx() + from.xx(), from.ty() + from.yx(), -t);
        double[] ey = seg.back(from.tx() + from.xy(), from.ty() + from.yy(), -t);
        return new ItemMotionSampler.Affine(ex[0] - o[0], ey[0] - o[0], ex[1] - o[1], ey[1] - o[1],
                o[0], o[1]);
    }

    /**
     * The move from pose {@code a} to pose {@code b} (both uv to canvas) as a {@link Segment}.
     * {@code laps} is the turn counted along the path (laps included), or NaN to take the
     * shortest turn the two poses alone suggest.
     */
    static Segment segment(ItemMotionSampler.Affine a, ItemMotionSampler.Affine b, double laps) {
        ItemMotionSampler.Affine ai = invert(a);
        // R = b o a^-1, canvas to canvas
        double xx = b.xx() * ai.xx() + b.xy() * ai.yx();
        double xy = b.xx() * ai.xy() + b.xy() * ai.yy();
        double yx = b.yx() * ai.xx() + b.yy() * ai.yx();
        double yy = b.yx() * ai.xy() + b.yy() * ai.yy();
        double tx = b.xx() * ai.tx() + b.xy() * ai.ty() + b.tx();
        double ty = b.yx() * ai.tx() + b.yy() * ai.ty() + b.ty();
        // its nearest similarity: r = ((xx + yy) + i (yx - xy)) / 2
        double re = 0.5 * (xx + yy), im = 0.5 * (yx - xy);
        double turn = Math.atan2(im, re);
        double logScale = Math.log(Math.max(1e-9, Math.hypot(re, im)));
        if (!Double.isNaN(laps)) {
            // same end pose, but the way round the path really went
            turn += 2 * Math.PI * Math.round((laps - turn) / (2 * Math.PI));
        }
        if (Math.abs(turn) < SLIDE_TURN && Math.abs(logScale) < SLIDE_TURN) {
            return new Segment(tx, ty, 0.0, 0.0);
        }
        // fixed point p = c / (1 - r); a whole lap leaves r at 1 and no point to find: the
        // middle of the thing is the best guess (a spin about itself)
        double dr = 1 - re, di = -im, den = dr * dr + di * di;
        if (den < 1e-12) {
            return new Segment(a.tx() + 0.5 * (a.xx() + a.xy()), a.ty() + 0.5 * (a.yx() + a.yy()),
                    logScale, turn);
        }
        double px = (tx * dr + ty * di) / den, py = (ty * dr - tx * di) / den;
        return new Segment(px, py, logScale, turn);
    }

    /** {@code n} of {@code count} indices, first and last included, by how far corners travel. */
    static int[] byTravel(ItemMotionSampler.Affine[] probe, int n) {
        int m = probe.length;
        double[] at = new double[m];
        for (int i = 1; i < m; i++) at[i] = at[i - 1] + travel(probe[i - 1], probe[i]);
        double total = at[m - 1];
        if (total <= 1e-9) return evenly(m, Math.min(n, m));
        int[] pick = new int[Math.min(n, m)];
        int j = 0;
        for (int k = 0; k < pick.length; k++) {
            double target = total * k / (pick.length - 1);
            while (j < m - 1 && at[j] < target) j++;
            int lo = Math.max(k == 0 ? 0 : pick[k - 1] + 1, 0);
            int hi = m - (pick.length - k);
            pick[k] = Math.max(lo, Math.min(hi, j));
        }
        pick[0] = 0;
        pick[pick.length - 1] = m - 1;
        return pick;
    }

    static int[] evenly(int count, int n) {
        int[] pick = new int[Math.max(2, Math.min(n, count))];
        for (int k = 0; k < pick.length; k++) {
            pick[k] = (int) Math.round((count - 1) * (double) k / (pick.length - 1));
        }
        return pick;
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
     * What the trail pass sums for a Smear before scaling: {@link #TRAIL_SUBSTEPS} evenly spaced
     * steps from each pose toward the next, then the newest pose itself, so the smear runs the
     * whole way from the oldest place to where the thing is now.
     */
    public static double smearTotal(double[] w) {
        double total = 0;
        for (int i = 0; i + 1 < w.length; i++) {
            for (int j = 0; j < TRAIL_SUBSTEPS; j++) {
                double t = (double) j / TRAIL_SUBSTEPS;
                total += w[i] + (w[i + 1] - w[i]) * t;
            }
        }
        return w.length == 0 ? 0 : total + w[w.length - 1];
    }

    /**
     * Weight per sample that makes a stroke: one object's worth of coverage for every object
     * width the path covers. Samples per object width = samples x size / path length; each then
     * weighs 1 / that (and 1 / the mean Fade weight, so Fade thins it rather than dims it all).
     */
    static double strokeScale(List<ItemMotionSampler.Affine> poses, double[] w) {
        double path = 0, size = 0;
        for (int i = 0; i < poses.size(); i++) {
            ItemMotionSampler.Affine p = poses.get(i);
            size += 0.5 * (Math.hypot(p.xx(), p.yx()) + Math.hypot(p.xy(), p.yy()));
            if (i > 0) path += travel(poses.get(i - 1), p);
        }
        size /= Math.max(1, poses.size());
        double mean = 0;
        for (double x : w) mean += x;
        mean /= Math.max(1, w.length);
        int samples = (w.length - 1) * TRAIL_SUBSTEPS + 1;
        if (path <= 1e-6 || size <= 1e-6 || mean <= 1e-9) return 0;
        double perWidth = Math.max(1.0, samples * size / path);
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
