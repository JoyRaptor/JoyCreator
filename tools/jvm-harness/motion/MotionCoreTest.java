import com.fadcam.ui.faditor.keyframe.Easing;
import com.fadcam.ui.faditor.keyframe.KeyframeTrack;
import com.fadcam.ui.faditor.motion.MotionBreaks;
import com.fadcam.ui.faditor.motion.MotionFrameCadence;
import com.fadcam.ui.faditor.motion.MotionShutter;
import com.fadcam.ui.faditor.motion.VelocityPack;

import java.util.Random;

public final class MotionCoreTest {
    private int assertions;
    private final String mutant;
    private final MotionShutter.FrameRate fps = new MotionShutter.FrameRate(30_000, 1001);

    private MotionCoreTest(String mutant) { this.mutant = mutant; }

    public static void main(String[] args) {
        MotionCoreTest test = new MotionCoreTest(args.length == 0 ? "" : args[0]);
        test.shutter();
        test.cadence();
        test.breaks();
        test.keyframes();
        test.velocity();
        System.out.println("MotionCoreTest: " + test.assertions + " assertions");
    }

    private void shutter() {
        check(Math.abs(fps.periodUs() - 100_100.0 / 3.0) < 1e-9, "NTSC frame period");
        check(Math.abs(new MotionShutter.FrameRate(24_000, 1001).periodUs() - 125_125.0 / 3.0)
                < 1e-9, "24000/1001 frame period");
        expectLong(180, MotionShutter.Preset.NATURAL.angle, "Natural angle");
        expectLong(-90, (long) MotionShutter.Preset.NATURAL.phase(), "Natural phase");
        expectLong(90, MotionShutter.Preset.CRISP.angle, "Crisp angle");
        expectLong(270, MotionShutter.Preset.HEAVY.angle, "Heavy angle");
        expectLong(-180, (long) MotionShutter.Preset.TRAILING.phase(), "Trailing phase");
        long center = 100_000;
        var box = schedule(center, MotionShutter.Preset.NATURAL, MotionShutter.Shape.BOX, 2, MotionBreaks.none());
        long first = mutant.equals("--wrong-clock") ? box.timesUs()[0] / 1000 * 1000 : box.timesUs()[0];
        expectLong(95_829, first, "sub-millisecond box midpoint");
        expectLong(104_171, box.timesUs()[1], "symmetric box midpoint");
        check(box.weights()[0] == 0.5 && box.weights()[1] == 0.5, "box weights");
        var soft = schedule(center, MotionShutter.Preset.NATURAL, MotionShutter.Shape.SOFT, 3, MotionBreaks.none());
        check(soft.weights()[1] > soft.weights()[0], "soft center is heavier");
        check(Math.abs(soft.weights()[0] + soft.weights()[1] + soft.weights()[2] - 1.0) < 1e-12,
                "soft weights normalized");
        var trailing = schedule(center, MotionShutter.Preset.TRAILING, MotionShutter.Shape.BOX, 2, MotionBreaks.none());
        check(trailing.timesUs()[1] < center, "trailing stays behind playhead");
        var single = schedule(center, MotionShutter.Preset.TRAILING, MotionShutter.Shape.SOFT, 1, MotionBreaks.none());
        expectLong(center, single.timesUs()[0], "single sample is t");
        var closed = MotionShutter.sample(center, fps, -1, 180, MotionShutter.Shape.BOX, 8, MotionBreaks.none());
        expectLong(center, closed.timesUs()[0], "closed shutter is t");
        check(MotionShutter.sample(center, fps, 721, -360, MotionShutter.Shape.BOX, 2,
                MotionBreaks.none()).timesUs()[1] == Math.round(center + fps.periodUs() / 2), "angle capped at 720");
    }

    private void cadence() {
        var cadence = new MotionFrameCadence();
        expectLong(0, cadence.observe(900_000), "first frame has no cadence history");
        long period = cadence.observe(916_667);
        if (mutant.equals("--wrong-fixed-export-rate")) period = 33_333;
        expectLong(16_667, period, "export shutter reads the 60 fps presentation interval");
        var measured = new MotionShutter.FrameRate(1_000_000, (int) period);
        var schedule = MotionShutter.sample(916_667, measured, 180, -90,
                MotionShutter.Shape.BOX, 2, MotionBreaks.none());
        expectLong(914_584, schedule.timesUs()[0], "shorter 60 fps exposure");
        var fenced = new MotionBreaks.Builder().add(917_000).build();
        var beforeCut = MotionShutter.sample(916_667, measured, 180, -90,
                MotionShutter.Shape.BOX, 6, fenced);
        for (long sample : beforeCut.timesUs()) check(sample < 917_000, "measured cadence still stops at cut");
        expectLong(41_708, cadence.observe(958_375), "variable cadence keeps exact microseconds");
        expectLong(0, cadence.observe(958_375), "duplicate timestamp");
        expectLong(0, cadence.observe(17_000), "time moved backwards");
        expectLong(33_333, cadence.observe(50_333), "cadence recovered after seek");
        expectLong(0, cadence.observe(1_900_000), "a long gap is not a frame period");
        expectLong(50_000, cadence.observe(1_950_000), "cadence recovered after gap");
        cadence.reset();
        expectLong(0, cadence.observe(1_983_334), "flush forgets the previous frame");
        expectLong(33_334, cadence.observe(2_016_668), "fractional NTSC timestamp alternation");
    }

    private void breaks() {
        var builder = new MotionBreaks.Builder().add(100_000).add(102_000)
                .exclude(150_000, 160_000).exclude(156_000, 170_000);
        var cuts = mutant.equals("--wrong-cuts") ? MotionBreaks.none() : builder.build();
        var before = schedule(99_999, MotionShutter.Preset.NATURAL, MotionShutter.Shape.BOX, 12, cuts);
        for (long sample : before.timesUs()) check(sample < 100_000, "previous shot cannot see the cut");
        var after = schedule(100_000, MotionShutter.Preset.NATURAL, MotionShutter.Shape.SOFT, 12, cuts);
        for (long sample : after.timesUs()) check(sample >= 100_000 && sample < 102_000,
                "cut timestamp belongs to the new shot");
        check(Math.abs(java.util.Arrays.stream(after.weights()).sum() - 1.0) < 1e-12, "clipped weights normalize");
        var gap = schedule(160_000, MotionShutter.Preset.NATURAL, MotionShutter.Shape.BOX, 12, cuts);
        expectLong(1, gap.timesUs().length, "no valid exposure in removed gap");
        expectLong(160_000, gap.timesUs()[0], "no valid exposure falls back to t");
        var afterGap = schedule(170_000, MotionShutter.Preset.NATURAL, MotionShutter.Shape.BOX, 12, cuts);
        for (long sample : afterGap.timesUs()) check(sample >= 170_000, "removed gap is half-open");
        var offWindow = schedule(100_000, MotionShutter.Preset.TRAILING, MotionShutter.Shape.BOX, 12, cuts);
        expectLong(1, offWindow.timesUs().length, "trailing shutter blocked at cut");
        var hold = new KeyframeTrack("x");
        hold.put(0, 0, Easing.HOLD);
        hold.put(100, 1, Easing.STAIRS_4);
        hold.put(200, 2, Easing.LINEAR);
        var steps = new MotionBreaks.Builder().addSteps(hold, 3_000).build();
        var beforeHold = steps.splitWindow(102_999, 99_000, 109_000);
        expectLong(102_999, beforeHold.lastValidUs(), "HOLD fence");
        var beforeStair = steps.splitWindow(127_999, 126_000, 129_000);
        expectLong(127_999, beforeStair.lastValidUs(), "stair fence");
    }

    private void keyframes() {
        var random = new Random(0x5eED);
        for (Easing easing : Easing.values()) {
            var track = new KeyframeTrack("x");
            exact(3.5f, track.valueAt(8.25, 3.5f), "empty fallback");
            for (int key = 0; key < 12; key++) {
                track.put(key * 27L - 35, random.nextFloat() * 100 - 50, easing);
            }
            for (long time = -40; time < 275; time++) {
                exact(track.valueAt(time, -1), track.valueAt((double) time, -1), "integer parity " + easing);
            }
        }
        var linear = new KeyframeTrack("x");
        linear.put(10, 0, Easing.LINEAR);
        linear.put(11, 40, Easing.LINEAR);
        float quarter = mutant.equals("--wrong-keyframe") ? linear.valueAt(10L, 0) : linear.valueAt(10.25, 0);
        exact(10f, quarter, "quarter millisecond interpolation");
        var hold = new KeyframeTrack("x");
        hold.put(0, 0, Easing.HOLD);
        hold.put(3_600_000, 1, Easing.LINEAR);
        exact(0f, hold.valueAt(3_599_999.999, 0), "HOLD must not round up before its key");
        exact(1f, hold.valueAt(3_600_000.0, 0), "HOLD advances at its key");
    }

    private void velocity() {
        int saturated = VelocityPack.pack(-1000, 1000, 900);
        check(VelocityPack.xPixels(saturated) == -128.0, "negative velocity edge");
        check(VelocityPack.yPixels(saturated) == 127.9375, "positive velocity edge");
        expectLong(255, VelocityPack.order(saturated), "unsigned order maximum");
        expectLong(0, VelocityPack.order(VelocityPack.pack(0, 0, -5)), "unsigned order minimum");
        for (int value = -2048; value <= 2047; value++) {
            double x = value / 16.0;
            double y = (-1 - value) / 16.0;
            int rgba = VelocityPack.pack(x, y, value & 255);
            double unpacked = mutant.equals("--wrong-pack") ? VelocityPack.yPixels(rgba) : VelocityPack.xPixels(rgba);
            check(unpacked == x && VelocityPack.yPixels(rgba) == y, "all signed 12-bit values round trip");
            expectLong(value & 255, VelocityPack.order(rgba), "order round trip");
        }
    }

    private MotionShutter.Schedule schedule(long center, MotionShutter.Preset preset,
                                             MotionShutter.Shape shape, int count, MotionBreaks cuts) {
        return MotionShutter.sample(center, fps, preset.angle, preset.phase(), shape, count, cuts);
    }

    private void exact(float expected, float actual, String message) {
        expectLong(Float.floatToIntBits(expected), Float.floatToIntBits(actual), message);
    }

    private void expectLong(long expected, long actual, String message) {
        check(expected == actual, message + ": expected " + expected + ", got " + actual);
    }

    private void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }
}
