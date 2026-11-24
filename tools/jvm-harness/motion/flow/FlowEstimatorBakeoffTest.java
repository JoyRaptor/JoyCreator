package com.fadcam.ui.faditor.motion.flow;

public final class FlowEstimatorBakeoffTest {
    private static final int WIDTH = 5;
    private static final long FROM_PTS_US = 33_367L;
    private static final long TO_PTS_US = 68_000L;
    private static int assertions;

    public static void main(String[] args) throws Exception {
        boolean reverseMotion = args.length > 0 && "--reverse-motion".equals(args[0]);
        measuresCompleteEstimatorAndScoresOnlyReferencePixels(reverseMotion);
        rejectsMismatchedPts();
        System.out.println("FlowEstimatorBakeoffTest: " + assertions + " assertions");
    }

    private static void measuresCompleteEstimatorAndScoresOnlyReferencePixels(boolean reverseMotion)
            throws Exception {
        var sample = sample();
        long[] stamps = {0, 900, 1_000, 1_100, 1_200, 1_700};
        int[] clockReads = {0};
        int[] calls = {0};
        var result = FlowEstimatorBakeoff.run("test", sample, 2, 3, (from, to, fromPts, toPts) -> {
            check(from == sample.from() && to == sample.to(), "decoded input identity was lost");
            check(fromPts == FROM_PTS_US && toPts == TO_PTS_US, "decoded PTS were lost");
            calls[0]++;
            return field(new float[]{reverseMotion ? -2 : 2, 0.25f, 100, Float.NaN, 500},
                    null, fromPts, toPts);
        }, () -> stamps[clockReads[0]++]);
        check(result.meanEndpointError() == 0.125, "wrong displacement must worsen endpoint error");
        check(result.p95EndpointError() == 0.25, "endpoint p95 did not use nearest rank");
        check(result.evaluatedPixels() == 2 && result.invalidPixels() == 1,
                "reference mask or invalid count was ignored");
        check(result.badOnePixelRate() == 1.0 / 3, "invalid vectors must count as bad pixels");
        check(result.meanStaticDrift() == 0.25, "static donor was not scored");
        check(calls[0] == 5 && clockReads[0] == 6, "warmup calls were not excluded from timing");
        check(result.medianNs() == 500 && result.p95Ns() == 900, "wrong timing percentiles");
        check(result.megapixelsPerSecond() == 10, "wrong throughput units");
        check(result.toTsv().split("\\t").length
                        == FlowEstimatorBakeoff.Result.tsvHeader().split("\\t").length,
                "TSV format diverged from its header");
    }

    private static void rejectsMismatchedPts() throws Exception {
        try {
            FlowEstimatorBakeoff.run("test", sample(), 0, 1,
                    (from, to, fromPts, toPts) -> field(new float[WIDTH], null, toPts, fromPts),
                    () -> 1);
            throw new AssertionError("wrong timestamps were accepted");
        } catch (IllegalArgumentException expected) {
            check("Estimator returned the wrong flow contract".equals(expected.getMessage()),
                    "wrong timestamps failed for the wrong reason");
        }
    }

    private static FlowEstimatorBakeoff.Sample sample() {
        return new FlowEstimatorBakeoff.Sample("reference", new LumaImage(WIDTH, 1, new float[WIDTH]),
                new LumaImage(WIDTH, 1, new float[WIDTH]),
                field(new float[]{2, 0, 0, 0, 0}, new float[]{1, 1, 0, 1, Float.NaN},
                        FROM_PTS_US, TO_PTS_US));
    }

    private static FlowField field(float[] u, float[] confidence, long fromPts, long toPts) {
        return new FlowField(WIDTH, 1, u, new float[WIDTH], confidence, fromPts, toPts);
    }

    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }
}
