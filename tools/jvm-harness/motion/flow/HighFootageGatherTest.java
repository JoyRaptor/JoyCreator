package com.fadcam.ui.faditor.motion.flow;

import java.util.Arrays;
import java.util.EnumSet;

public final class HighFootageGatherTest {
    private static final int BLACK = 0xff000000;
    private static final int WHITE = 0xffffffff;
    private static final int RED = 0xffff0000;
    private static final int BLUE = 0xff0000ff;
    private static final long STEP = 40_000;
    private static final long CENTER_PROJECT = 1_000_000;
    private final boolean ignoreConfidence;
    private int assertions;

    public HighFootageGatherTest(boolean ignoreConfidence) {
        this.ignoreConfidence = ignoreConfidence;
    }

    public static void main(String[] args) {
        HighFootageGatherTest test = new HighFootageGatherTest(
                args.length > 0 && args[0].equals("--ignore-confidence"));
        test.staticAndSubpixelAreExact();
        test.centeredBidirectionalExposure();
        test.realPtsSpeedAndReverse();
        test.disoccludedDonorStaysSharp();
        test.rejectedDonorWeightReturnsToCentre();
        test.bilinearFootprintRejectsAnUntrustedTexel();
        test.neighbourEndpointStillChecksConfidence();
        test.warpSolvesBothComponentsAtTheSamePoint();
        test.alphaAndLinearLight();
        test.missingRangeStaysSharp();
        System.out.println("HighFootageGatherTest: " + test.assertions + " assertions");
    }

    private void staticAndSubpixelAreExact() {
        int[] pixels = {BLACK, RED, WHITE, BLUE, BLACK};
        var previous = frame(pixels.clone(), -STEP);
        var current = frame(pixels, 0);
        var next = frame(pixels.clone(), STEP);
        int[] exact = reconstruct(previous, current, next, pair(5, 0, -STEP, 0, 1),
                pair(5, 0, 0, STEP, 1), 1, taps(-STEP, 0, STEP), weights(1, 1, 1));
        check(exact == pixels, "static exposure returns the centre array");

        previous = frame(dot(5, 1, WHITE), -STEP);
        next = frame(dot(5, 3, WHITE), STEP);
        FlowCache.Pair slowBefore = pair(5, 0.24f, -STEP, 0, 1);
        FlowCache.Pair slowAfter = pair(5, 0.24f, 0, STEP, 1);
        check(reconstruct(previous, current, next, slowBefore, slowAfter, 1,
                taps(-STEP, 0, STEP), weights(1, 1, 1)) == pixels,
                "sub-half-pixel exposure stays byte exact");
        check(reconstruct(previous, current, next, pair(5, 0.3f, -STEP, 0, 1),
                pair(5, 0.3f, 0, STEP, 1), 1,
                taps(-STEP, 0, STEP), weights(1, 1, 1)) != pixels,
                "the full centred shutter span determines subpixel travel");
        check(reconstruct(previous, current, next, slowBefore, slowAfter, 0,
                taps(-STEP, 0, STEP), weights(1, 1, 1)) == pixels,
                "freeze stays byte exact");
    }

    private void centeredBidirectionalExposure() {
        int[] previous = dot(5, 1, WHITE);
        int[] current = dot(5, 2, WHITE);
        int[] next = dot(5, 3, WHITE);
        int[] result = reconstruct(frame(previous, -STEP), frame(current, 0), frame(next, STEP),
                pair(5, 1, -STEP, 0, 1), pair(5, 1, 0, STEP, 1), 1,
                taps(-STEP, 0, STEP), weights(1, 1, 1));
        check(result[1] == 0xff9c9c9c && result[2] == 0xff9c9c9c
                        && result[3] == 0xff9c9c9c,
                "previous, centre and next decoded frames all contribute");
        check(current[2] == WHITE && current[1] == BLACK, "decoded frames remain read-only");
    }

    private void realPtsSpeedAndReverse() {
        int[] previous = dot(7, 1, WHITE);
        int[] current = dot(7, 3, WHITE);
        int[] next = dot(7, 5, WHITE);
        var before = pair(7, 2, -STEP, 0, 1);
        var after = pair(7, 2, 0, STEP, 1);
        int[] forward = reconstruct(frame(previous, -STEP), frame(current, 0), frame(next, STEP),
                before, after, 1, taps(0, STEP), weights(1, 1));
        int[] reverse = reconstruct(frame(previous, -STEP), frame(current, 0), frame(next, STEP),
                before, after, -1, taps(0, STEP), weights(1, 1));
        check(forward[5] == 0xffbcbcbc && forward[1] == BLACK,
                "positive speed follows the future decoded PTS");
        check(reverse[1] == 0xffbcbcbc && reverse[5] == BLACK,
                "negative speed follows the previous decoded PTS");
        int[] fast = reconstruct(frame(previous, -STEP), frame(current, 0), frame(next, STEP),
                before, after, 2, taps(0, STEP / 2), weights(1, 1));
        check(Arrays.equals(forward, fast), "source speed maps the project shutter through real PTS");
        int[] wideGap = reconstruct(frame(previous, -STEP), frame(current, 0), frame(next, STEP * 2),
                before, pair(7, 2, 0, STEP * 2, 1), 1, taps(0, STEP), weights(1, 1));
        check(wideGap[4] == 0xffbcbcbc && wideGap[5] == BLACK,
                "the real pair interval places a fractional shutter image between decoded frames");
    }

    private void disoccludedDonorStaysSharp() {
        int[] current = {BLACK, BLACK, RED, BLACK, BLACK};
        int[] next = {BLACK, BLUE, BLACK, BLACK, BLACK};
        float confidence = ignoreConfidence ? 1 : 0;
        FlowCache.Pair after = pair(5, 2, 0, STEP, confidence);
        int[] result = reconstruct(null, frame(current, 0), frame(next, STEP), null, after, 1,
                taps(0, STEP / 2), weights(1, 1));
        check(result[2] == RED, "disoccluded donor stays sharp");

        float[] forward = fill(5, 2), backward = fill(5, 0);
        FlowCache.Pair inconsistent = pair(forward, backward, 0, STEP, 1);
        result = reconstruct(null, frame(current, 0), frame(next, STEP), null, inconsistent, 1,
                taps(0, STEP / 2), weights(1, 1));
        check(result[2] == RED, "forward/backward mismatch is rejected even with confidence");
    }

    private void alphaAndLinearLight() {
        int transparentBlue = 0x000000ff;
        int[] previous = {RED, transparentBlue, transparentBlue};
        int[] current = {transparentBlue, RED, transparentBlue};
        int[] next = {transparentBlue, transparentBlue, RED};
        int[] result = reconstruct(frame(previous, -STEP), frame(current, 0), frame(next, STEP),
                pair(3, 1, -STEP, 0, 1), pair(3, 1, 0, STEP, 1), 1,
                taps(0, STEP), weights(1, 1));
        check(result[1] == 0x80ff0000 && result[2] == 0x80ff0000,
                "coverage warps with colour without transparent blue fringes");

        int[] black = {BLACK, BLACK, BLACK};
        int[] white = {BLACK, WHITE, BLACK};
        result = reconstruct(null, frame(black, 0), frame(white, STEP), null,
                pair(3, 1, 0, STEP, 1), 1, taps(0, STEP), weights(1, 1));
        check(result[1] == 0xffbcbcbc, "black and white average in linear light");
    }

    private void rejectedDonorWeightReturnsToCentre() {
        int[] current = dot(7, 2, WHITE);
        int[] next = dot(7, 4, WHITE);
        FlowCache.Pair motion = pair(7, 2, 0, STEP, 1);
        FlowCache.Pair uncertain = new FlowCache.Pair(motion.forward,
                motion.backward.withConfidence(new float[7]));
        int[] result = reconstruct(null, frame(current, 0), frame(next, STEP), null,
                uncertain, 1, taps(0, STEP / 2), weights(1, 1));
        check(result[3] == 0xff898989, "missing donor weight returns to the sharp centre");
    }

    private void neighbourEndpointStillChecksConfidence() {
        int[] current = dot(7, 2, WHITE);
        int[] next = dot(7, 4, WHITE);
        int[] result = reconstruct(null, frame(current, 0), frame(next, STEP), null,
                pair(7, 2, 0, STEP, 0), 1, taps(0, STEP), weights(1, 1));
        check(result == current, "a low-confidence neighbour is rejected at exact PTS");
    }

    private void bilinearFootprintRejectsAnUntrustedTexel() {
        int width = 7;
        int[] current = dot(width, 2, WHITE);
        int[] next = dot(width, 3, WHITE);
        FlowCache.Pair motion = pair(width, 1, 0, STEP, 1);
        float[] confidence = fill(width, 1);
        confidence[2] = 0;
        FlowCache.Pair uncertain = new FlowCache.Pair(motion.forward.withConfidence(confidence),
                motion.backward.withConfidence(new float[width]));
        int[] result = reconstruct(null, frame(current, 0), frame(next, STEP), null,
                uncertain, 1, taps(STEP / 2), weights(1));
        check(result[3] == BLACK, "bilinear filtering cannot leak an occluded bright texel");
    }

    private void warpSolvesBothComponentsAtTheSamePoint() {
        int size = 8;
        int[] current = new int[size * size], next = new int[current.length];
        Arrays.fill(current, BLACK);
        Arrays.fill(next, BLACK);
        float[] u = new float[current.length], backU = new float[u.length];
        for (int y = 0; y < size; y++) {
            current[y * size + 2] = WHITE;
            Arrays.fill(u, y * size, (y + 1) * size, y * 0.5f);
            Arrays.fill(backU, y * size, (y + 1) * size, (1 - y) * 0.5f);
        }
        FlowCache.Pair shear = new FlowCache.Pair(
                new FlowField(size, size, u, fill(u.length, 1), null, 0, STEP),
                new FlowField(size, size, backU, fill(u.length, -1), null, STEP, 0));
        int[] result = reconstruct(null, new HighFootageGather.SourceFrame(current, size, size, 0, null),
                new HighFootageGather.SourceFrame(next, size, size, STEP, null),
                null, shear, 1, taps(STEP / 2), weights(1));
        // Inverting the half-step at (3,3) gives (2.375,2.5). The current white stripe therefore
        // contributes 0.625 of its bilinear weight, then takes half of the two-frame exposure.
        check(result[3 * size + 3] == 0xff989898, "a shear uses both components of the same donor");
    }

    private void missingRangeStaysSharp() {
        int[] current = dot(5, 2, WHITE);
        int[] next = dot(5, 3, WHITE);
        int[] result = reconstruct(null, frame(current, 0), frame(next, STEP), null,
                pair(5, 1, 0, STEP, 1), 1, taps(-STEP, 0), weights(1, 1));
        check(result == current, "tap outside the supplied neighbour pair stays sharp");
        FlowCache.Pair motion = pair(5, 1, 0, STEP, 1);
        for (FlowCache.Flag flag : new FlowCache.Flag[]{FlowCache.Flag.LONG_GAP, FlowCache.Flag.DUPLICATE}) {
            FlowCache.Pair rejected = new FlowCache.Pair(motion.forward, motion.backward, EnumSet.of(flag));
            result = reconstruct(null, frame(current, 0), frame(next, STEP), null,
                    rejected, 1, taps(0, STEP), weights(1, 1));
            check(result == current, flag + " never donates across the exposure");
        }
    }

    private static int[] reconstruct(HighFootageGather.SourceFrame previous,
                                     HighFootageGather.SourceFrame current,
                                     HighFootageGather.SourceFrame next,
                                     FlowCache.Pair before, FlowCache.Pair after, double speed,
                                     long[] relativeTimes, double[] weights) {
        long[] project = relativeTimes.clone();
        for (int i = 0; i < project.length; i++) project[i] += CENTER_PROJECT;
        return HighFootageGather.reconstruct(previous, current, next, before, after,
                CENTER_PROJECT, project, weights, speed);
    }

    private static HighFootageGather.SourceFrame frame(int[] argb, long pts) {
        return new HighFootageGather.SourceFrame(argb, argb.length, 1, pts, null);
    }

    private static FlowCache.Pair pair(int width, float displacement, long from, long to,
                                       float confidence) {
        return pair(fill(width, displacement), fill(width, -displacement), from, to, confidence);
    }

    private static FlowCache.Pair pair(float[] forward, float[] backward, long from, long to,
                                       float confidence) {
        float[] conf = fill(forward.length, confidence);
        return new FlowCache.Pair(new FlowField(forward.length, 1, forward, new float[forward.length],
                conf, from, to), new FlowField(backward.length, 1, backward,
                new float[backward.length], conf, to, from));
    }

    private static float[] fill(int n, float value) {
        float[] out = new float[n];
        Arrays.fill(out, value);
        return out;
    }

    private static int[] dot(int width, int x, int color) {
        int[] out = new int[width];
        Arrays.fill(out, BLACK);
        out[x] = color;
        return out;
    }

    private static long[] taps(long... values) { return values; }
    private static double[] weights(double... values) { return values; }

    private void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }
}
