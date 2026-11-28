import com.fadcam.ui.faditor.motion.ItemMotionSampler;
import com.fadcam.ui.faditor.motion.MotionBreaks;
import com.fadcam.ui.faditor.motion.MotionShutter;
import com.fadcam.ui.faditor.motion.MotionShutter.Controls;
import com.fadcam.ui.faditor.motion.MotionShutter.Look;
import com.fadcam.ui.faditor.motion.MotionShutter.Timing;
import com.fadcam.ui.faditor.motion.RigidShutterPlan;

/**
 * STU-7 spec §K.2: smooth plans (long trails, Fade, Copies, fast camera windows) are plain taps,
 * each the thing where it really was, placed by travel and close enough to read as one streak.
 */
public final class TrailPlanTest {

    private static int checks;
    private static final long PERIOD = 41_667;   // 24 fps
    private static final long NOW = 2_000_000;

    /** A 40 px square sliding 30 px a frame. */
    private static final ItemMotionSampler SLIDE = t ->
            new ItemMotionSampler.Affine(40, 0, 0, 40, 500 + 30 * frames(t), 300);

    private static double frames(long t) { return (t - NOW) / (double) PERIOD; }

    public static void main(String[] args) {
        Controls streak = new Controls(8 * 360, Timing.TRAILING, 0, 100, 60, Look.SMEAR, 4);
        check(streak.isLongTrail() && streak.isStylised(), "8 frames back is a long, stylised trail");

        // Producers that cannot trail see the longest real shutter, two frames, ending now.
        MotionShutter.Schedule capped = streak.schedule(NOW, PERIOD, 6, MotionBreaks.none(), MotionBreaks.none());
        check(capped.timesUs()[0] >= NOW - 2 * PERIOD && capped.timesUs()[0] < NOW, "others cap at two frames");

        RigidShutterPlan plan = RigidShutterPlan.prepareTrail(SLIDE, NOW, streak, PERIOD, MotionBreaks.none(), 4000, 4000);
        check(plan != null && plan.trail() != null, "a moving thing gets a smooth plan");
        int n = plan.taps().size();
        // 8 frames x 30 px = 240 px of travel at 3 px a tap = 81 taps, capped at 48
        eq(RigidShutterPlan.MAX_SMEAR_TAPS, n, "taps grow with travel, up to the cap");
        near(500 - 240, x(plan, 0), 1e-6, "the oldest tap is where it was 8 frames ago");
        near(500, x(plan, n - 1), 0.01, "the newest is where it is now");
        for (int i = 0; i < n; i++) {
            double fr = (x(plan, i) - 500) / 30.0;
            near(SLIDE.at(NOW + Math.round(fr * PERIOD)).tx(), x(plan, i), 0.01, "tap " + i + " is a real pose");
        }
        check(plan.trail().program() >= n, "drawn by a program with room for every tap");
        RigidShutterPlan preview = RigidShutterPlan.prepareTrail(SLIDE, NOW, streak, PERIOD,
                MotionBreaks.none(), MotionBreaks.none(), 4000, 4000, 11);
        eq(11, preview.taps().size(), "the live preview caps the smear taps");
        eq(12, preview.trail().program(), "and so draws with the smallest program");
        near(500 - 240, x(preview, 0), 1e-6, "still from 8 frames ago");
        double sum = 0;
        for (var tap : plan.taps()) sum += tap.weight();
        check(sum > 1.5, "a long trail is a stroke, not the thing's light spread thin: " + sum);
        check(plan.taps().get(0).weight() < plan.taps().get(n - 1).weight(), "Fade 60: the oldest tap weighs less");
        check(plan.trail().smooth()[0] && !plan.trail().smooth()[n - 1],
                "smear taps soften toward the next one; the newest has no next");

        // A short slide: the gap rule, and camera exposure
        Controls heavy = new Controls(270, Timing.CENTERED, 0, 100);
        RigidShutterPlan cam = RigidShutterPlan.prepareTrail(SLIDE, NOW, heavy, PERIOD, MotionBreaks.none(), 4000, 4000);
        int cn = cam.taps().size();
        // 270 degrees = 0.75 frame = 22.5 px: ceil(22.5 / 3) + 1 = 9 taps
        eq(9, cn, "only as many taps as the travel needs");
        for (int i = 1; i < cn; i++) {
            check(Math.abs(x(cam, i) - x(cam, i - 1)) <= RigidShutterPlan.TAP_GAP_PX + 1e-6, "neighbours within the gap");
        }
        double csum = 0;
        for (var tap : cam.taps()) csum += tap.weight();
        near(1.0, csum, 1e-9, "a camera-length window is exposure: weights sum to one");
        eq(12, cam.trail().program(), "the smallest program that holds it");

        // Crowding: something that accelerates gets its taps where it moves fast
        ItemMotionSampler rocket = t -> {
            double f = (t - (NOW - 8 * PERIOD)) / (double) (8 * PERIOD);
            return new ItemMotionSampler.Affine(40, 0, 0, 40, 100 + 900 * f * f * f, 300);
        };
        RigidShutterPlan rp = RigidShutterPlan.prepareTrail(rocket, NOW, streak, PERIOD, MotionBreaks.none(), 4000, 4000);
        int late = 0;
        for (int i = 0; i < rp.taps().size(); i++) if (x(rp, i) > 100 + 900 * 0.125) late++;
        check(late > rp.taps().size() / 2, "more taps where it moves faster: " + late + " of " + rp.taps().size());

        swingTapsOnTheArc(streak);

        // Copies, Both, Mix
        Controls copies = streak.withLook(0, Look.COPIES, 3).withMix(50);
        RigidShutterPlan c = RigidShutterPlan.prepareTrail(SLIDE, NOW, copies, PERIOD, MotionBreaks.none(), 4000, 4000);
        eq(4, c.taps().size(), "Copies 3 plus the sharp pose for Mix 50");
        double ghosts = c.taps().get(0).weight() + c.taps().get(1).weight() + c.taps().get(2).weight();
        near(0.5, ghosts, 1e-9, "Mix 50: the ghosts weigh one half");
        near(0.5, c.taps().get(3).weight(), 1e-9, "and the sharp pose the other half");
        check(!c.trail().smooth()[0], "ghosts stay crisp");
        near(500 - 120, x(c, 1), 0.01, "the middle ghost is 4 frames back");
        Controls both = streak.withLook(0, Look.BOTH, 5);
        RigidShutterPlan b = RigidShutterPlan.prepareTrail(SLIDE, NOW, both, PERIOD, MotionBreaks.none(), 4000, 4000);
        eq(RigidShutterPlan.MAX_SMEAR_TAPS + 5, b.taps().size(), "Both: the smear and five ghosts");
        eq(64, b.trail().program(), "in the largest program");

        // A cut two frames back stops the trail there
        MotionBreaks cut = new MotionBreaks.Builder().add(NOW - 2 * PERIOD).build();
        RigidShutterPlan fenced = RigidShutterPlan.prepareTrail(SLIDE, NOW, streak, PERIOD, cut, 4000, 4000);
        check(x(fenced, 0) >= 500 - 60 - 0.01, "a cut fences the trail: " + x(fenced, 0));

        ItemMotionSampler still = t -> new ItemMotionSampler.Affine(40, 0, 0, 40, 10, 10);
        eq(null, RigidShutterPlan.prepareTrail(still, NOW, streak, PERIOD, MotionBreaks.none(), 100, 100),
                "nothing moves: no plan, the sharp route");

        // A faded camera shutter opens where the camera does
        Controls fadedNatural = Controls.NATURAL.withLook(100, Look.SMEAR, 4);
        RigidShutterPlan fp = RigidShutterPlan.prepareTrail(SLIDE, NOW, fadedNatural, PERIOD, MotionBreaks.none(), 4000, 4000);
        near(500 - 7.5, x(fp, 0), 0.01, "a faded 180 centred opens a quarter frame back");
        near(0.0, fp.taps().get(0).weight(), 1e-9, "Fade 100: the oldest edge vanishes");

        // The camera taps of a fast move would step; of a slow one, not
        MotionShutter.Schedule six = Controls.NATURAL.schedule(NOW, PERIOD, 6, MotionBreaks.none(), MotionBreaks.none());
        check(RigidShutterPlan.tapsWouldStep(SLIDE, six, 2.0), "30 px a frame: 2.5 px between six taps steps past 2 px");
        check(!RigidShutterPlan.tapsWouldStep(SLIDE, six, 3.0), "but not past 3 px");
        System.out.println("TrailPlanTest: " + checks + " checks passed");
    }

    /** A 200 x 20 bar swinging about its left end, 30 degrees a frame: every tap's tip is on the arc. */
    private static void swingTapsOnTheArc(Controls trail) {
        double pivotX = 600, pivotY = 400;
        ItemMotionSampler swing = t -> {
            double a = frames(t) * Math.toRadians(30), c = Math.cos(a), sn = Math.sin(a);
            return new ItemMotionSampler.Affine(200 * c, -20 * sn, 200 * sn, 20 * c, pivotX, pivotY);
        };
        RigidShutterPlan plan = RigidShutterPlan.prepareTrail(swing, NOW, trail, PERIOD, MotionBreaks.none(), 4000, 4000);
        double worst = 0;
        for (var tap : plan.taps()) {
            ItemMotionSampler.Affine pose = poseOf(tap);
            double tipX = pose.xx() + pose.tx(), tipY = pose.yx() + pose.ty();
            worst = Math.max(worst, Math.abs(Math.hypot(tipX - pivotX, tipY - pivotY) - 200));
        }
        System.out.println("swing: worst tap tip off the arc " + worst + " px over " + plan.taps().size() + " taps");
        check(worst < 1e-6, "every tap is a real pose, so the swing's tip stays on its arc");
    }

    private static double x(RigidShutterPlan plan, int i) { return poseOf(plan.taps().get(i)).tx(); }

    private static ItemMotionSampler.Affine poseOf(RigidShutterPlan.Tap tap) {
        ItemMotionSampler.Affine i = tap.canvasToUv();
        double det = i.xx() * i.yy() - i.xy() * i.yx();
        double xx = i.yy() / det, xy = -i.xy() / det, yx = -i.yx() / det, yy = i.xx() / det;
        return new ItemMotionSampler.Affine(xx, xy, yx, yy, -(xx * i.tx() + xy * i.ty()), -(yx * i.tx() + yy * i.ty()));
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
