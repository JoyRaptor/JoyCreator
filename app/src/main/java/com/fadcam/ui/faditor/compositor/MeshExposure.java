package com.fadcam.ui.faditor.compositor;

import com.fadcam.ui.faditor.keyframe.Easing;
import com.fadcam.ui.faditor.motion.MotionBreaks;
import com.fadcam.ui.faditor.motion.MotionShutter;
import com.fadcam.ui.faditor.transform.mesh.MeshPoseTrack;
import com.fadcam.ui.faditor.transform.mesh.MeshWarpSpec;

import java.util.Arrays;
import java.util.List;

/** A mesh item's shutter clock and authored discontinuities, built from a render snapshot. */
public final class MeshExposure {
    private final long startUs;
    private final long endUs;
    private final MotionBreaks itemBreaks;

    /**
     * Adds item and mesh fences to the caller's builder, which may already contain cell and
     * placement changes. Rebuild with the item snapshot when those inputs change.
     */
    public MeshExposure(MeshWarpSpec spec, long startUs, long endUs, MotionBreaks.Builder itemFences) {
        if (endUs <= startUs) throw new IllegalArgumentException("Invalid mesh item range");
        this.startUs = startUs;
        this.endUs = endUs;
        itemFences.add(startUs).add(endUs);
        addTrackBreaks(spec.track(), spec.arity(), itemFences);
        addTrackBreaks(spec.groupZTrackOrNull(), spec.groupCount(), itemFences);
        if (spec.topology() != null) {
            addTrackBreaks(spec.handleZTrackOrNull(), spec.topology().handleCount(), itemFences);
        }
        itemBreaks = itemFences.build();
    }

    /**
     * Taps in ascending project time for the compositor. Identical times share one draw and
     * equal their original total weight. Outside the item's lifetime there is nothing to draw.
     */
    public MotionShutter.Schedule schedule(MotionShutter.Controls controls, long centerUs,
                                           long periodUs, int taps, MotionBreaks projectBreaks) {
        if (centerUs < startUs || centerUs >= endUs) {
            return new MotionShutter.Schedule(new long[0], new double[0]);
        }
        MotionShutter.Schedule schedule = controls.schedule(centerUs, periodUs, taps,
                projectBreaks, itemBreaks);
        long[] times = schedule.timesUs();
        double[] weights = schedule.weights();
        // Partial Mix appends a sharp center tap, which can precede some or all of the exposure.
        int last = times.length - 1;
        if (last > 0 && times[last] < times[last - 1]) {
            moveTapEarlier(times, weights, last);
        }
        int unique = 0;
        for (int i = 0; i < times.length; i++) {
            if (unique > 0 && times[i] == times[unique - 1]) {
                weights[unique - 1] += weights[i];
            } else {
                times[unique] = times[i];
                weights[unique++] = weights[i];
            }
        }
        return unique == times.length ? schedule : new MotionShutter.Schedule(
                Arrays.copyOf(times, unique), Arrays.copyOf(weights, unique));
    }

    /** Clock accepted by MeshEngine.updateForExposure and MeshStampGl.renderExposureTap. */
    public long localTimeUs(long projectUs) { return Math.max(0, projectUs - startUs); }

    private void addTrackBreaks(MeshPoseTrack track, int arity, MotionBreaks.Builder fences) {
        if (track == null || track.arity() != arity || !track.hasCurve()) return;
        List<MeshPoseTrack.Pose> poses = track.poses();
        for (int i = 1; i < poses.size(); i++) {
            MeshPoseTrack.Pose left = poses.get(i - 1);
            MeshPoseTrack.Pose right = poses.get(i);
            Easing easing = Easing.fromName(left.easing);
            if (easing != Easing.HOLD && easing != Easing.STAIRS_4) continue;
            if (right.timeMs <= left.timeMs || Arrays.equals(left.values, right.values)) continue;
            addFence(fences, right.timeMs, 4, 0L, 0);
            if (easing == Easing.STAIRS_4) {
                for (int step = 1; step < 4; step++) {
                    addFence(fences, left.timeMs, 4 - step, right.timeMs, step);
                }
            }
        }
    }

    private void addFence(MotionBreaks.Builder fences, long leftMs, int leftWeight,
                          long rightMs, int rightWeight) {
        // Each quarter of a millisecond is 250 whole microseconds. Compute all stair fences
        // exactly, even when the item's offset is itself fractional.
        try {
            long weightedMs = Math.addExact(Math.multiplyExact(leftMs, leftWeight),
                    Math.multiplyExact(rightMs, rightWeight));
            long tick = Math.addExact(startUs, Math.multiplyExact(weightedMs, 250L));
            if (tick > startUs && tick < endUs) fences.add(tick);
        } catch (ArithmeticException ignored) {
            // A key outside the representable project clock cannot supply a shutter fence.
        }
    }

    private static void moveTapEarlier(long[] times, double[] weights, int at) {
        long time = times[at];
        double weight = weights[at];
        int i = at;
        while (i > 0 && times[i - 1] > time) {
            times[i] = times[i - 1];
            weights[i] = weights[i - 1];
            i--;
        }
        times[i] = time;
        weights[i] = weight;
    }
}
