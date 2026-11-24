package com.fadcam.ui.faditor.motion.flow;

import java.util.Arrays;

/** Fast footage exposure tests with analytic translation, confidence and alpha controls. */
public final class FootageGatherTest {
    private static final int BLACK = 0xff000000;
    private static final int WHITE = 0xffffffff;
    private static final int RED = 0xffff0000;
    private static final int BLUE = 0xff0000ff;
    private static final long CENTER_PROJECT_US = 10_000;
    private static final long SOURCE_PAIR_US = 33_367;
    private int assertions;

    public static void main(String[] args) {
        FootageGatherTest test = new FootageGatherTest();
        test.staticAndMissingInputAreExact();
        test.linearExposureAndSourceClock();
        test.alphaDoesNotLeak();
        test.untrustedDonorsStaySharp();
        test.coarseMotionDoesNotBleedIntoStillSide();
        System.out.println("FootageGatherTest: " + test.assertions + " assertions");
    }

    private void staticAndMissingInputAreExact() {
        int[] frame = {0x00abcdef, BLACK, WHITE, RED};
        FlowField still = FlowField.zero(4, 1, 0, SOURCE_PAIR_US);
        check(blend(frame, still, null, SOURCE_PAIR_US, 1) == frame, "static frame is byte exact");
        check(blend(frame, null, null, SOURCE_PAIR_US, 1) == frame, "missing neighbour is sharp");
        FlowField moving = uniform(4, 1, 1f, 0, SOURCE_PAIR_US);
        check(blend(frame, moving, null, SOURCE_PAIR_US, 0) == frame, "freeze is sharp");
        check(blend(frame, moving, null, 0, 1) == frame, "closed shutter is sharp");
        FlowField subPixel = uniform(4, 1, 0.49f, 0, SOURCE_PAIR_US);
        check(blend(frame, subPixel, null, SOURCE_PAIR_US, 1) == frame,
                "sub-half-pixel travel is byte exact");
        int[] centred = FootageGather.reconstruct(frame, 4, 1, moving, null, CENTER_PROJECT_US,
                new long[]{CENTER_PROJECT_US - SOURCE_PAIR_US * 3 / 10,
                        CENTER_PROJECT_US + SOURCE_PAIR_US * 3 / 10},
                new double[]{1, 1}, 1);
        check(centred != frame, "centred shutter threshold uses the full exposure span");
        check(blend(frame, moving, null, 10 * SOURCE_PAIR_US, 1) == frame,
                "out-of-frame donors leave the centre");
    }

    private void linearExposureAndSourceClock() {
        int[] frame = {BLACK, BLACK, WHITE, BLACK, BLACK, BLACK, BLACK};
        FlowField forward = uniform(7, 1, 1f, 0, SOURCE_PAIR_US);
        int[] once = blend(frame, forward, null, SOURCE_PAIR_US, 1);
        check(once[3] == 0xffbcbcbc && once[1] == BLACK,
                "half black and white yields sRGB 188 in the physical direction");
        check(blend(frame, forward, null, SOURCE_PAIR_US, -1)[1] == 0xffbcbcbc,
                "reverse remaps the direction in source time");
        check(blend(frame, forward, null, SOURCE_PAIR_US, 2)[4] == 0xffbcbcbc,
                "2x source speed doubles spread");
        FlowField backward = uniform(7, 1, -1f, SOURCE_PAIR_US, 0);
        check(Arrays.equals(once, blend(frame, backward, null, SOURCE_PAIR_US, 1)),
                "backward vector and decreasing PTS preserve the physical sign");
        FlowField widerGap = uniform(7, 1, 1f, 0, SOURCE_PAIR_US * 2);
        check(blend(frame, widerGap, null, SOURCE_PAIR_US, 1)[3] == 0xff898989,
                "real PTS spacing controls fractional donor coverage");
        int[] unevenWeights = FootageGather.reconstruct(frame, 7, 1, forward, null,
                CENTER_PROJECT_US, new long[]{CENTER_PROJECT_US + SOURCE_PAIR_US, CENTER_PROJECT_US},
                new double[]{1, 3}, 1);
        check(unevenWeights[3] == 0xff898989, "unsorted exposure and sharp weights are normalized");
        check(frame[2] == WHITE && frame[3] == BLACK, "source pixels remain read-only");
    }

    private void alphaDoesNotLeak() {
        int[] frame = {0x000000ff, RED, 0x0000ff00, BLACK};
        FlowField forward = uniform(4, 1, 1f, 0, SOURCE_PAIR_US);
        int[] exposure = blend(frame, forward, null, SOURCE_PAIR_US, 1);
        check(exposure[1] == 0x80ff0000 && exposure[2] == 0x80ff0000,
                "transparent coloured texels reduce coverage without a colour fringe");
    }

    private void untrustedDonorsStaySharp() {
        int[] frame = {BLACK, BLUE, RED, RED, WHITE};
        float[] u = {0, 0, 2, 2, 2};
        float[] certainty = {1, 1, 1, 1, 1};
        FlowField mismatch = new FlowField(5, 1, u, new float[5], certainty, 0, SOURCE_PAIR_US);
        check(blend(frame, mismatch, null, SOURCE_PAIR_US, 1)[3] == RED,
                "a static donor cannot contaminate the moving red region");
        Arrays.fill(u, 2f);
        certainty[1] = 0;
        FlowField occluded = new FlowField(5, 1, u, new float[5], certainty, 0, SOURCE_PAIR_US);
        check(blend(frame, occluded, null, SOURCE_PAIR_US, 1)[3] == RED,
                "a zero-confidence donor gives its weight back to the centre");
        certainty[1] = 0.5f;
        FlowField uncertain = new FlowField(5, 1, u, new float[5], certainty, 0, SOURCE_PAIR_US);
        int color = blend(frame, uncertain, null, SOURCE_PAIR_US, 1)[3];
        check(color == 0xffe10089, "an uncertain donor softens toward the sharp colour");
        certainty[3] = Float.NaN;
        FlowField invalid = new FlowField(5, 1, u, new float[5], certainty, 0, SOURCE_PAIR_US);
        check(blend(frame, invalid, null, SOURCE_PAIR_US, 1)[3] == RED,
                "non-finite confidence is safely rejected");
    }

    private void coarseMotionDoesNotBleedIntoStillSide() {
        int[] frame = {gray(0), gray(8), gray(16), gray(24), gray(231), gray(232), gray(233), gray(234)};
        FlowField coarse = new FlowField(2, 1, new float[]{0, 0.5f}, new float[2], null,
                0, SOURCE_PAIR_US);
        LumaImage coarseLuma = new LumaImage(2, 1, new float[]{12 / 255f, 233 / 255f});
        int[] exposure = blend(frame, coarse, coarseLuma, SOURCE_PAIR_US, 1);
        check(Arrays.equals(Arrays.copyOf(exposure, 4), Arrays.copyOf(frame, 4)),
                "luma-aware upsampling keeps static pixels next to motion byte exact");
        check(exposure[7] == gray(233), "trusted coarse vector scales to the larger source grid");

        int[] edge = new int[12];
        for (int x = 0; x < edge.length; x++) edge[x] = gray(x < 4 ? 8 + x : 196 + x);
        FlowField edgeCoarse = new FlowField(3, 1, new float[]{0, -1, -1}, new float[3], null,
                0, SOURCE_PAIR_US);
        LumaImage edgeLuma = new LumaImage(3, 1,
                new float[]{10 / 255f, 201 / 255f, 205 / 255f});
        float[] fullU = new float[12];
        Arrays.fill(fullU, 4, fullU.length, -4);
        FlowField edgeFull = new FlowField(12, 1, fullU, new float[12], null,
                0, SOURCE_PAIR_US);
        int[] coarseExposure = blend(edge, edgeCoarse, edgeLuma, SOURCE_PAIR_US, 1);
        int[] fullExposure = blend(edge, edgeFull, null, SOURCE_PAIR_US, 1);
        check(coarseExposure[4] == fullExposure[4],
                "rejecting the other side of an edge does not lower trusted-side confidence");
    }

    private static int[] blend(int[] frame, FlowField flow, LumaImage coarseLuma,
                               long offsetUs, double speed) {
        return FootageGather.reconstruct(frame, frame.length, 1, flow, coarseLuma, CENTER_PROJECT_US,
                new long[]{CENTER_PROJECT_US, CENTER_PROJECT_US + offsetUs}, new double[]{1, 1}, speed);
    }

    private static FlowField uniform(int width, int height, float x, long from, long to) {
        float[] u = new float[width * height];
        Arrays.fill(u, x);
        return new FlowField(width, height, u, new float[u.length], null, from, to);
    }

    private static int gray(int channel) { return BLACK | channel * 0x010101; }

    private void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }
}
