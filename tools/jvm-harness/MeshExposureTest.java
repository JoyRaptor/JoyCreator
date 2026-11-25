import com.fadcam.ui.faditor.compositor.MeshExposure;
import com.fadcam.ui.faditor.keyframe.Easing;
import com.fadcam.ui.faditor.motion.MotionBreaks;
import com.fadcam.ui.faditor.motion.MotionShutter;
import com.fadcam.ui.faditor.transform.mesh.MeshBuffers;
import com.fadcam.ui.faditor.transform.mesh.MeshCurves;
import com.fadcam.ui.faditor.transform.mesh.MeshEngine;
import com.fadcam.ui.faditor.transform.mesh.MeshPoseTrack;
import com.fadcam.ui.faditor.transform.mesh.MeshWarpSpec;
import com.fadcam.ui.faditor.transform.mesh.PuppetTopology;

import java.util.Arrays;

public final class MeshExposureTest {
    private static final long START = 180_125;
    private static final long END = START + 2_000_000;
    private static final long HOLD_END = START + 500_000;
    private static final MotionShutter.Controls FULL = MotionShutter.Controls.NATURAL;

    public static void main(String[] args) {
        if (args.length == 1 && args[0].equals("--ignore-mesh-fences")) {
            heldDepthOwnsTheCrossing(true);
            return;
        }
        if (args.length == 1 && args[0].equals("--unsorted-mix")) {
            positivePhasePlaysInOrder(true);
            return;
        }
        heldDepthOwnsTheCrossing(false);
        positivePhasePlaysInOrder(false);
        stairsAndProducerFences();
        roundedTapsCoalesce();
        System.out.println("Mesh exposure checks passed");
    }

    private static void heldDepthOwnsTheCrossing(boolean unfenced) {
        PuppetTopology topology = new PuppetTopology(new float[][]{
                rectangle(0.30f, 0.20f, 0.66f, 0.78f),
                rectangle(0.68f, 0.37f, 0.94f, 0.61f)
        }, 3, new float[]{0.43f, 0.50f, 0.76f, 0.49f});
        MeshWarpSpec spec = new MeshWarpSpec(topology);
        spec.ensureTrack().setCurve(MeshCurves.APP_EASING);
        // A stationary torso, a travelling arm, and a held z-order change at the crossing.
        spec.track().put(0, new float[]{0, 0, -0.24f, 0}, "LINEAR");
        spec.track().put(1000, new float[]{0, 0, -0.44f, 0}, "LINEAR");
        MeshPoseTrack depth = spec.groupZTrack();
        depth.setCurve(MeshCurves.APP_EASING);
        depth.put(0, new float[]{0, -1}, "HOLD");
        depth.put(500, new float[]{0, 1}, "LINEAR");
        depth.put(1000, new float[]{0, 1}, "LINEAR");
        MeshExposure exposure = new MeshExposure(spec, START, END, new MotionBreaks.Builder());
        MeshEngine engine = new MeshEngine();
        long center = HOLD_END - 1;
        MotionShutter.Schedule taps = unfenced
                ? FULL.schedule(center, 40_000, 8, MotionBreaks.none(), MotionBreaks.none())
                : exposure.schedule(FULL, center, 40_000, 8, MotionBreaks.none());
        double torsoWeight = 0;
        float firstArmX = Float.NaN;
        int armVertex = topology.islandStart()[1];
        for (int i = 0; i < taps.timesUs().length; i++) {
            require(engine.updateForExposure(spec, exposure.localTimeUs(taps.timesUs()[i])),
                    "sample draws authored geometry");
            MeshBuffers buffers = engine.buffers();
            if (i == 0) firstArmX = buffers.positions[armVertex * 2];
            if (topPartAt(buffers, armVertex, .50f, .45f) == 0) torsoWeight += taps.weights()[i];
        }
        require(Math.abs(torsoWeight - 1) < 1e-12, "future arm depth leaked into a held crossing");
        require(Math.abs(engine.buffers().positions[armVertex * 2] - firstArmX) > 1e-4f,
                "arm geometry moves between taps");
        MotionShutter.Schedule after = exposure.schedule(FULL, HOLD_END, 40_000, 8, MotionBreaks.none());
        for (long time : after.timesUs()) {
            require(engine.updateForExposure(spec, exposure.localTimeUs(time)), "sample after the hold");
            require(topPartAt(engine.buffers(), armVertex, .50f, .45f) == 1,
                    "the exact depth key and later taps draw the arm on top");
        }
    }

    private static void positivePhasePlaysInOrder(boolean unsorted) {
        MeshWarpSpec spec = MeshWarpSpec.lattice(2);
        MeshExposure exposure = new MeshExposure(spec, START, END, new MotionBreaks.Builder());
        MotionShutter.Controls mixed = new MotionShutter.Controls(180, MotionShutter.Timing.CUSTOM, 90, 75);
        long center = START + 500_311;
        MotionShutter.Schedule taps = unsorted
                ? mixed.schedule(center, 40_000, 7, MotionBreaks.none(), MotionBreaks.none())
                : exposure.schedule(mixed, center, 40_000, 7, MotionBreaks.none());
        long previous = Long.MIN_VALUE;
        for (long time : taps.timesUs()) {
            require(time > previous, "positive-phase Mix must visit samples in ascending order");
            previous = time;
        }
        require(taps.timesUs()[0] == center && taps.weights()[0] == .25, "sharp Mix contribution");
        require(Math.abs(Arrays.stream(taps.weights()).sum() - 1) < 1e-12,
                "normalized exposure and Mix weights");
    }

    private static void stairsAndProducerFences() {
        MeshWarpSpec spec = MeshWarpSpec.lattice(2);
        MeshPoseTrack poses = spec.ensureTrack();
        poses.setCurve(MeshCurves.APP_EASING);
        float[] rest = new float[spec.arity()];
        float[] offset = rest.clone();
        offset[0] = .2f;
        poses.put(-1, rest, Easing.STAIRS_4.name());
        poses.put(100, offset, Easing.LINEAR.name());
        long stair = START - 1000 + 25_250;
        long cell = stair + 300;
        MeshExposure exposure = new MeshExposure(spec, START, END,
                new MotionBreaks.Builder().add(cell));
        MotionShutter.Schedule atStair = exposure.schedule(FULL, stair, 40_000, 8,
                MotionBreaks.none());
        require(atStair.timesUs()[0] >= stair && atStair.timesUs()[7] < cell,
                "stair fence and producer cell fence intersect in project time");
        long cut = stair + 150;
        MotionShutter.Schedule atCut = exposure.schedule(FULL, cut, 40_000, 8,
                new MotionBreaks.Builder().add(cut).build());
        require(atCut.timesUs()[0] >= cut && atCut.timesUs()[7] < cell,
                "project separation intersects the producer fences");
        require(exposure.schedule(FULL, END, 40_000, 8, MotionBreaks.none()).timesUs().length == 0,
                "item end remains exclusive");

        MeshPoseTrack longStair = new MeshPoseTrack(1);
        longStair.setCurve(MeshCurves.APP_EASING);
        longStair.put(0, new float[]{0}, Easing.STAIRS_4.name());
        longStair.put(10_000_000, new float[]{4}, Easing.LINEAR.name());
        float[] sample = new float[1];
        require(longStair.valueAtMicros(2_499_999_999L, sample) && sample[0] == 0,
                "float conversion must preserve the microsecond before a long stair");
        require(longStair.valueAtMicros(2_500_000_000L, sample) && sample[0] == 1,
                "the new stair owns its exact first microsecond");
    }

    private static void roundedTapsCoalesce() {
        MeshExposure exposure = new MeshExposure(MeshWarpSpec.lattice(2), START, START + 1,
                new MotionBreaks.Builder());
        MotionShutter.Schedule schedule = exposure.schedule(FULL, START, 40_000, 32, MotionBreaks.none());
        require(schedule.timesUs().length == 1 && schedule.timesUs()[0] == START
                        && Math.abs(schedule.weights()[0] - 1) < 1e-12,
                "rounding several taps to one microsecond keeps the full coverage");
    }

    private static float[] rectangle(float x0, float y0, float x1, float y1) {
        return new float[]{x0, y0, x1, y0, x1, y1, x0, y1};
    }

    // One opaque coverage probe through the real back-to-front index list.
    private static int topPartAt(MeshBuffers b, int armStart, float x, float y) {
        int part = -1;
        for (int i = 0; i < b.indexCount(); i += 3) {
            int a = b.drawIndices[i] & 0xffff;
            int c = b.drawIndices[i + 1] & 0xffff;
            int d = b.drawIndices[i + 2] & 0xffff;
            float first = edge(b.positions, a, c, x, y);
            float second = edge(b.positions, c, d, x, y);
            float third = edge(b.positions, d, a, x, y);
            if ((first >= 0 && second >= 0 && third >= 0)
                    || (first <= 0 && second <= 0 && third <= 0)) {
                part = a < armStart ? 0 : 1;
            }
        }
        return part;
    }

    private static float edge(float[] positions, int a, int b, float x, float y) {
        float px = positions[a * 2], py = positions[a * 2 + 1];
        return (positions[b * 2] - px) * (y - py) - (positions[b * 2 + 1] - py) * (x - px);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
