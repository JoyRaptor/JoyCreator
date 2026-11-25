package com.fadcam.ui.faditor.compositor;

public final class MeshExposureGlPolicyTest {
    private static int checks;

    public static void main(String[] args) {
        int boundaryPixels = (int) (MeshExposureGlPolicy.WORKING_LIMIT_BYTES
                / MeshExposureGlPolicy.WORKING_BYTES_PER_PIXEL);
        check(MeshExposureGlPolicy.admits(boundaryPixels, 1,
                new long[]{10, 20}, new double[]{0.5, 0.5}), "budget boundary must draw");
        check(!MeshExposureGlPolicy.admits(boundaryPixels + 1, 1,
                new long[]{10, 20}, new double[]{0.5, 0.5}), "over-budget frame must decline");
        check(!MeshExposureGlPolicy.admits(64, 64,
                new long[]{10}, new double[]{1.0}), "one tap must use the sharp path");
        check(!MeshExposureGlPolicy.admits(64, 64,
                new long[]{10, 10}, new double[]{0.5, 0.5}), "one effective pose must use sharp path");
        check(!MeshExposureGlPolicy.admits(64, 64,
                new long[]{10, 20}, new double[]{1.0, 0.0}), "zero-weight pose must not open GL");

        expectInvalid(() -> MeshExposureGlPolicy.admits(64, 64,
                new long[]{20, 10}, new double[]{0.5, 0.5}), "unordered taps");
        expectInvalid(() -> MeshExposureGlPolicy.admits(64, 64,
                new long[]{10, 20}, new double[]{0.5, -0.5}), "negative weight");
        expectInvalid(() -> MeshExposureGlPolicy.admits(64, 64,
                new long[]{10, 20}, new double[]{0.0, 0.0}), "empty effective schedule");
        expectInvalid(() -> MeshExposureGlPolicy.admits(64, 64,
                new long[]{10}, new double[]{1.0, 0.0}), "mismatched arrays");
        expectInvalid(() -> MeshExposureGlPolicy.admits(0, 64,
                new long[]{10, 20}, new double[]{0.5, 0.5}), "invalid size");

        long[] tooManyTimes = new long[MeshExposureGlPolicy.MAX_TAPS + 1];
        double[] tooManyWeights = new double[tooManyTimes.length];
        for (int i = 0; i < tooManyTimes.length; i++) {
            tooManyTimes[i] = i;
            tooManyWeights[i] = 1.0;
        }
        expectInvalid(() -> MeshExposureGlPolicy.admits(64, 64, tooManyTimes, tooManyWeights),
                "tap ceiling");
        System.out.println("MeshExposureGlPolicyTest: " + checks + " checks passed");
    }

    private static void expectInvalid(Runnable action, String message) {
        try {
            action.run();
            throw new AssertionError(message + " was accepted");
        } catch (IllegalArgumentException expected) {
            checks++;
        }
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
