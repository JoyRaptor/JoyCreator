import com.fadcam.ui.faditor.motion.ItemMotionSampler;
import com.fadcam.ui.faditor.motion.MotionBreaks;
import com.fadcam.ui.faditor.motion.MotionShutter;
import com.fadcam.ui.faditor.motion.MotionShutter.Controls;
import com.fadcam.ui.faditor.motion.MotionShutter.Look;
import com.fadcam.ui.faditor.motion.MotionShutter.Timing;
import com.fadcam.ui.faditor.motion.RigidShutterPlan;

/** STU-7 spec §K.2: long trails, Fade and Copies, built from real earlier poses. */
public final class TrailPlanTest {

    private static int checks;
    private static final long PERIOD = 41_667;   // 24 fps
    private static final long NOW = 2_000_000;

    /** A dot that moves 100 px a frame to the right, then (after NOW - 4 frames) curves upward. */
    private static final ItemMotionSampler MOVER = t -> {
        double frames = (t - NOW) / (double) PERIOD;
        double y = frames < -4 ? 0 : (frames + 4) * (frames + 4) * 5;
        return new ItemMotionSampler.Affine(1, 0, 0, 1, 500 + frames * 100, 300 - y);
    };

    public static void main(String[] args) {
        Controls trail = new Controls(8 * 360, Timing.TRAILING, 0, 100, 60, Look.SMEAR, 4);
        check(trail.isLongTrail() && trail.isStylised(), "8 frames back is a long, stylised trail");

        // Producers that cannot trail see the longest real shutter, two frames, ending now.
        MotionShutter.Schedule capped = trail.schedule(NOW, PERIOD, 6, MotionBreaks.none(), MotionBreaks.none());
        long earliest = capped.timesUs()[0];
        check(earliest >= NOW - 2 * PERIOD && earliest < NOW, "other producers cap a long trail at two frames: " + earliest);
        check(capped.timesUs()[capped.timesUs().length - 1] <= NOW, "ending at this frame");

        MotionShutter.Schedule poses = trail.trailPoses(NOW, PERIOD, 7, MotionBreaks.none());
        eq(7, poses.timesUs().length, "seven key poses");
        near(NOW - 8 * PERIOD, poses.timesUs()[0], 1, "the oldest is 8 frames back");
        near(NOW, poses.timesUs()[6], 1, "the newest is this frame (a window excludes its end)");
        near(0.4, poses.weights()[0], 1e-9, "Fade 60 %: the oldest weighs 0.4");
        near(1.0, poses.weights()[6], 1e-9, "the newest weighs 1");
        check(poses.weights()[3] > poses.weights()[1], "weights grow toward now");

        RigidShutterPlan plan = RigidShutterPlan.prepareTrail(MOVER, NOW, trail, PERIOD,
                MotionBreaks.none(), 4000, 4000);
        check(plan != null && plan.trail() != null, "a moving thing gets a trail plan");
        eq(RigidShutterPlan.MAX_TRAIL_POSES, plan.taps().size(), "a Smear carries the most key poses");
        double smear = RigidShutterPlan.smearTotal(weights(plan));
        check(smear * plan.trail().smearScale() > 1.5,
                "a long trail is a stroke, not the object's light spread thin: " + smear * plan.trail().smearScale());
        Controls shortSmear = Controls.NATURAL.withLook(50, Look.SMEAR, 4);
        RigidShutterPlan sp = RigidShutterPlan.prepareTrail(MOVER, NOW, shortSmear, PERIOD,
                MotionBreaks.none(), 4000, 4000);
        near(1.0, RigidShutterPlan.smearTotal(weights(sp)) * sp.trail().smearScale(), 1e-9,
                "a camera-length smear is exposure: it sums to one");
        near(0.0, plan.trail().copyScale(), 0, "and draws no copies");
        near(0.0, plan.trail().sharp(), 0, "Mix 100: no extra sharp pose");
        near(NOW - 8 * PERIOD, timeOf(plan, 0), 1, "the oldest key pose is 8 frames back");
        check(Math.abs(timeOf(plan, plan.taps().size() - 1) - NOW) <= 1, "the newest is now");
        // A thing that accelerates hard (x grows with the cube of time): key poses crowd late.
        ItemMotionSampler rocket = t -> {
            double f = (t - (NOW - 8 * PERIOD)) / (double) (8 * PERIOD);
            return new ItemMotionSampler.Affine(40, 0, 0, 40, 100 + 900 * f * f * f, 300);
        };
        RigidShutterPlan rp = RigidShutterPlan.prepareTrail(rocket, NOW, trail, PERIOD,
                MotionBreaks.none(), 4000, 4000);
        int late = 0;
        for (RigidShutterPlan.Tap tp : rp.taps()) if (-tp.canvasToUv().tx() * 40 > 100 + 900 * 0.125) late++;
        check(late > rp.taps().size() / 2, "more key poses where it moves faster: " + late + " of " + rp.taps().size());
        for (int i = 0; i < plan.taps().size(); i++) {
            ItemMotionSampler.Affine truth = MOVER.at(Math.round(timeOf(plan, i)));
            near(-truth.tx(), plan.taps().get(i).canvasToUv().tx(), 1e-6, "pose " + i + " is where it really was (x)");
        }
        swingFollowsTheArc(trail);
        // A square spinning 90 degrees a frame about its middle: 8 frames back is two whole laps.
        ItemMotionSampler spin = t -> {
            double a = (t - NOW) / (double) PERIOD * Math.PI / 2, c = Math.cos(a), sn = Math.sin(a);
            return new ItemMotionSampler.Affine(64 * c, -64 * sn, 64 * sn, 64 * c,
                    300 - 32 * c + 32 * sn, 300 - 32 * sn - 32 * c);
        };
        RigidShutterPlan sp2 = RigidShutterPlan.prepareTrail(spin, NOW, trail, PERIOD,
                MotionBreaks.none(), 4000, 4000);
        double laps = 0;
        for (RigidShutterPlan.Segment g : sp2.trail().segments()) {
            laps += g.turn();
            check(g.turn() > 0, "every segment turns the way it spins: " + g.turn());
            near(300, g.x(), 1e-6, "about the square's middle (x)");
            near(300, g.y(), 1e-6, "about the square's middle (y)");
        }
        near(4 * Math.PI, laps, 0.05, "two whole laps, counted, not folded into one turn");
        check(plan.bounds().left() == 0 && plan.bounds().right() >= 500,
                "the swept box covers the whole trail (clipped at the canvas edge): " + plan.bounds());

        Controls copies = trail.withLook(0, Look.COPIES, 3).withMix(50);
        RigidShutterPlan c = RigidShutterPlan.prepareTrail(MOVER, NOW, copies, PERIOD,
                MotionBreaks.none(), 4000, 4000);
        eq(3, c.taps().size(), "Copies 3: three ghosts");
        near(0.5, sum(weights(c)) * c.trail().copyScale(), 1e-9, "Mix 50: the copies weigh one half");
        near(0.5, c.trail().sharp(), 1e-9, "and the sharp pose the other half");
        near(0.0, c.trail().smearScale(), 0, "no smear for Copies");

        Controls both = trail.withLook(0, Look.BOTH, 4);
        RigidShutterPlan b = RigidShutterPlan.prepareTrail(MOVER, NOW, both, PERIOD,
                MotionBreaks.none(), 4000, 4000);
        near(0.5, sum(weights(b)) * b.trail().copyScale(), 1e-9, "Both: the copies weigh one half");
        check(RigidShutterPlan.smearTotal(weights(b)) * b.trail().smearScale() >= 0.5 - 1e-9,
                "and the stroke at least the other half");

        // A cut two frames back stops the trail there, never reaching across it.
        MotionBreaks cut = new MotionBreaks.Builder().add(NOW - 2 * PERIOD).build();
        MotionShutter.Schedule fenced = trail.trailPoses(NOW, PERIOD, 7, cut);
        check(fenced.timesUs()[0] >= NOW - 2 * PERIOD, "a cut fences the trail: " + fenced.timesUs()[0]);

        ItemMotionSampler still = t -> new ItemMotionSampler.Affine(1, 0, 0, 1, 10, 10);
        eq(null, RigidShutterPlan.prepareTrail(still, NOW, trail, PERIOD, MotionBreaks.none(), 100, 100),
                "nothing moves: no plan, the sharp route");

        // A short camera shutter with only Fade is stylised too, and stays inside its window.
        Controls fadedNatural = Controls.NATURAL.withLook(100, Look.SMEAR, 4);
        MotionShutter.Schedule fp = fadedNatural.trailPoses(NOW, PERIOD, 7, MotionBreaks.none());
        near(NOW - PERIOD / 4.0, fp.timesUs()[0], 1, "a faded 180 centred opens a quarter frame back");
        near(0.0, fp.weights()[0], 1e-9, "Fade 100 %: the oldest edge vanishes");
        System.out.println("TrailPlanTest: " + checks + " checks passed");
    }

    /** The time a key pose belongs to, read back from MOVER's x (x = 500 + frames * 100). */
    private static double timeOf(RigidShutterPlan plan, int i) {
        double x = -plan.taps().get(i).canvasToUv().tx();
        return NOW + (x - 500) / 100.0 * PERIOD;
    }

    /**
     * A 200 x 20 bar swinging about its left end, 30 degrees a frame. Halfway between two key
     * poses the trail's step must be where the bar really was (on the arc), not on the straight
     * line between the two poses: at the bar's tip that line cuts inside the arc by many pixels.
     */
    private static void swingFollowsTheArc(Controls trail) {
        double pivotX = 600, pivotY = 400, step = Math.toRadians(30);
        ItemMotionSampler swing = t -> {
            double a = (t - NOW) / (double) PERIOD * step;
            double c = Math.cos(a), sn = Math.sin(a);
            // uv (0..1, 0..1) -> a 200 x 20 bar from the pivot, turned by a
            return new ItemMotionSampler.Affine(200 * c, -20 * sn, 200 * sn, 20 * c,
                    pivotX, pivotY);
        };
        RigidShutterPlan plan = RigidShutterPlan.prepareTrail(swing, NOW, trail, PERIOD,
                MotionBreaks.none(), 4000, 4000);
        check(plan != null && plan.trail().segments().size() == plan.taps().size() - 1,
                "one arc segment between each pair of key poses");
        double worstArc = 0, worstLine = 0;
        for (int i = 0; i < plan.trail().segments().size(); i++) {
            RigidShutterPlan.Segment seg = plan.trail().segments().get(i);
            check(!seg.slide(), "a swing is a turn, not a slide");
            ItemMotionSampler.Affine a = poseOf(plan.taps().get(i));
            ItemMotionSampler.Affine b = poseOf(plan.taps().get(i + 1));
            double angA = Math.atan2(a.yx(), a.xx()), angB = Math.atan2(b.yx(), b.xx());
            double mid = angA + wrap(angB - angA) / 2;
            double trueTipX = pivotX + 200 * Math.cos(mid), trueTipY = pivotY + 200 * Math.sin(mid);
            ItemMotionSampler.Affine half = RigidShutterPlan.stepPose(a, seg, 0.5);
            double arcX = half.xx() + half.tx(), arcY = half.yx() + half.ty();   // uv (1, 0) = the tip
            double lineX = 0.5 * (a.xx() + a.tx() + b.xx() + b.tx());
            double lineY = 0.5 * (a.yx() + a.ty() + b.yx() + b.ty());
            worstArc = Math.max(worstArc, Math.hypot(arcX - trueTipX, arcY - trueTipY));
            worstLine = Math.max(worstLine, Math.hypot(lineX - trueTipX, lineY - trueTipY));
        }
        System.out.println("swing: tip off the arc by " + worstArc + " px (a straight blend: " + worstLine + " px)");
        check(worstArc < 0.01, "halfway steps sit on the true arc: " + worstArc);
        check(worstLine > 2, "and a straight blend would not (so this test can fail): " + worstLine);
    }

    private static ItemMotionSampler.Affine poseOf(RigidShutterPlan.Tap tap) {
        ItemMotionSampler.Affine i = tap.canvasToUv();
        double det = i.xx() * i.yy() - i.xy() * i.yx();
        double xx = i.yy() / det, xy = -i.xy() / det, yx = -i.yx() / det, yy = i.xx() / det;
        return new ItemMotionSampler.Affine(xx, xy, yx, yy, -(xx * i.tx() + xy * i.ty()), -(yx * i.tx() + yy * i.ty()));
    }

    private static double wrap(double a) {
        while (a > Math.PI) a -= 2 * Math.PI;
        while (a < -Math.PI) a += 2 * Math.PI;
        return a;
    }

    private static double[] weights(RigidShutterPlan plan) {
        double[] w = new double[plan.taps().size()];
        for (int i = 0; i < w.length; i++) w[i] = plan.taps().get(i).weight();
        return w;
    }

    private static double sum(double[] w) {
        double t = 0;
        for (double x : w) t += x;
        return t;
    }

    private static void eq(Object want, Object got, String what) {
        checks++;
        if (want == null ? got != null : !want.equals(got)) {
            throw new AssertionError(what + ": expected " + want + " got " + got);
        }
    }

    private static void near(double want, double got, double tol, String what) {
        checks++;
        if (Math.abs(want - got) > tol) throw new AssertionError(what + ": expected " + want + " got " + got);
    }

    private static void check(boolean ok, String what) {
        checks++;
        if (!ok) throw new AssertionError(what);
    }
}
