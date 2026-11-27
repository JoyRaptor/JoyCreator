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
        eq(7, plan.taps().size(), "with every pose");
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
        for (int i = 0; i < 7; i++) {
            ItemMotionSampler.Affine truth = MOVER.at(poses.timesUs()[i]);
            near(-truth.tx(), plan.taps().get(i).canvasToUv().tx(), 1e-6, "pose " + i + " is where it really was (x)");
            near(-truth.ty(), plan.taps().get(i).canvasToUv().ty(), 1e-6, "pose " + i + " is where it really was (y)");
        }
        // The poses follow the curve: the middle pose is above the straight line between ends.
        double ty3 = plan.taps().get(3).canvasToUv().ty();
        double ty0 = plan.taps().get(0).canvasToUv().ty();
        double ty6 = plan.taps().get(6).canvasToUv().ty();
        check(Math.abs(ty3 - (ty0 + ty6) / 2) > 1, "real earlier poses: the trail bends with the path");
        ItemMotionSampler square = t -> new ItemMotionSampler.Affine(32, 0, 0, 32,
                500 + (t - NOW) / (double) PERIOD * 40, 300);
        RigidShutterPlan sq = RigidShutterPlan.prepareTrail(square, NOW, trail, PERIOD,
                MotionBreaks.none(), 4000, 4000);
        // 8 frames x 40 px over 6 x 6 steps = 8.9 px a step = 0.28 of a 32 px square
        near(320.0 / 36 / 32, sq.trail().featherU(), 1e-6, "smear steps soften along the motion by one step");
        near(0.0, sq.trail().featherV(), 1e-9, "and stay crisp across it");
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
