package com.fadcam.ui.faditor.motion.flow;

import java.nio.ByteBuffer;

/** Proves crop-origin, row/pixel stride and non-divisible box coverage for decoded Y planes. */
public final class YPlaneLumaTest {
    public static void main(String[] args) {
        byte[] storage = new byte[80];
        int origin = 7, rowStride = 13, pixelStride = 2;
        for (int y = 0; y < 4; y++) {
            for (int x = 0; x < 5; x++) storage[origin + y * rowStride + x * pixelStride] = (byte) (10 * y + x);
        }
        ByteBuffer plane = ByteBuffer.wrap(storage);
        plane.position(3); // The crop origin is absolute and must not silently use this position.
        LumaImage two = YPlaneLuma.boxDownsample(plane, origin, rowStride, pixelStride, 5, 4, 2, 2);
        near(two.at(0, 0), average(0, 1, 10, 11) / 255f, "top-left box");
        near(two.at(1, 0), average(2, 3, 4, 12, 13, 14) / 255f, "non-divisible right box");
        near(two.at(0, 1), average(20, 21, 30, 31) / 255f, "bottom-left box");
        near(two.at(1, 1), average(22, 23, 24, 32, 33, 34) / 255f, "bottom-right box");

        LumaImage same = YPlaneLuma.boxDownsample(plane, origin, rowStride, pixelStride, 5, 4, 5, 4);
        for (int y = 0; y < 4; y++) for (int x = 0; x < 5; x++) {
            near(same.at(x, y), (10 * y + x) / 255f, "one sample " + x + "," + y);
        }
        reject(() -> YPlaneLuma.boxDownsample(plane, origin, rowStride, pixelStride, 5, 4, 6, 4),
                "analysis never invents upsampled pixels");
        reject(() -> YPlaneLuma.boxDownsample(ByteBuffer.wrap(new byte[8]), 0, 5, 1, 5, 2, 2, 1),
                "short planes are rejected before an indexed read");
        System.out.println("YPlaneLumaTest: PASS");
    }

    private static float average(int... values) {
        int sum = 0; for (int value : values) sum += value;
        return sum / (float) values.length;
    }

    private static void near(float actual, float expected, String message) {
        if (Math.abs(actual - expected) > 1e-6f) throw new AssertionError(message + ": " + actual + " != " + expected);
    }

    private static void reject(Runnable action, String message) {
        try { action.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError(message);
    }
}
