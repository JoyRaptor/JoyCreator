import com.fadcam.ui.faditor.motion.ItemMotionSampler;
import com.fadcam.ui.faditor.motion.MotionShutter;
import com.fadcam.ui.faditor.motion.RigidShutterPlan;
import com.fadcam.ui.faditor.motion.SweptBounds;

public final class RigidShutterPlanTest {
    public static void main(String[] args) {
        var schedule = new MotionShutter.Schedule(new long[]{10, 30}, new double[]{0.25, 0.75});
        var a = new ItemMotionSampler.Affine(0, -8, 6, 0, 29, 10);
        var b = new ItemMotionSampler.Affine(-10, 0, 0, 14, 34, 13);
        var sharp = new ItemMotionSampler.Affine(6, 0, 0, 8, 24, 14);
        ItemMotionSampler motion = at -> at == 10 ? a : at == 30 ? b : sharp;
        var pass = RigidShutterPlan.prepare(motion, 20, schedule, 60, 50, 1);
        check(pass != null, "moving texture produces a shutter plan");
        check(pass.bounds().equals(new SweptBounds(20, 9, 35, 28)), "bounds cover both subframes");
        var first = args.length > 0 ? a : pass.taps().get(0).canvasToUv();
        check(close(first.x(a.x(.4, .6), a.y(.4, .6)), .4)
                && close(first.y(a.x(.4, .6), a.y(.4, .6)), .6),
                "rotation maps a canvas pixel back to its UV");
        var second = pass.taps().get(1).canvasToUv();
        check(close(second.x(b.x(.2, .7), b.y(.2, .7)), .2)
                && close(second.y(b.x(.2, .7), b.y(.2, .7)), .7), "reflection keeps the UV orientation");
        check(RigidShutterPlan.prepare(at -> sharp, 20, schedule, 60, 50, 1) == null,
                "stationary texture stays on the sharp path");
        var collapsed = RigidShutterPlan.prepare(at -> at == 10
                ? new ItemMotionSampler.Affine(0, 0, 0, 0, 25, 25) : sharp,
                20, schedule, 60, 50, 1);
        check(collapsed.taps().size() == 1 && collapsed.taps().get(0).weight() == 0.75,
                "zero-area sample reduces accumulated coverage");

        System.out.println("RigidShutterPlanTest: inverse UV, reflected quad, scissor and coverage passed");
    }

    private static boolean close(double a, double b) { return Math.abs(a - b) < 1e-9; }

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
}
