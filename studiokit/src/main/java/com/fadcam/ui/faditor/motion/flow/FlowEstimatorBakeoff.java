package com.fadcam.ui.faditor.motion.flow;

import java.util.Arrays;
import java.util.Locale;

/**
 * Deterministic quality and throughput accounting shared by the C6 estimator bake-off.
 *
 * <p>The estimator itself owns decode/upload/readback decisions. Consequently the caller can put
 * the timer around the complete GPU path (including its readback) and the complete DIS path on the
 * same decoded pair. Reference confidence is an evaluation mask: pixels below 0.5 are excluded.
 * This class does not choose a winner; it emits the measurements needed for the two Note 9 runs.
 */
public final class FlowEstimatorBakeoff {
    @FunctionalInterface
    public interface Estimator {
        FlowField estimate(LumaImage from, LumaImage to, long fromPtsUs, long toPtsUs) throws Exception;
    }

    @FunctionalInterface
    public interface NanoClock { long now(); }

    /** One decoded pair and its timestamp-aligned high-speed reference flow. */
    public record Sample(String name, LumaImage from, LumaImage to, FlowField reference) {
        public Sample {
            if (!usableLabel(name) || from == null || to == null || reference == null
                    || from.width != to.width || from.height != to.height
                    || from.width != reference.width || from.height != reference.height
                    || reference.fromPtsUs == reference.toPtsUs) {
                throw new IllegalArgumentException("Invalid estimator bake-off sample");
            }
        }
    }

    /** Stable columns for checked-in or copied device evidence. Times include the caller's route. */
    public record Result(String estimator, String sample, int width, int height,
                         long medianNs, long p95Ns, double megapixelsPerSecond,
                         int evaluatedPixels, int invalidPixels, double meanEndpointError,
                         double p95EndpointError, double badOnePixelRate,
                         double meanStaticDrift) {
        public static String tsvHeader() {
            return "estimator\tsample\twidth\theight\tmedian_ns\tp95_ns\tmpix_s\tevaluated"
                    + "\tinvalid\tmean_epe\tp95_epe\tbad_1px_rate\tmean_static_drift";
        }

        public String toTsv() {
            return String.format(Locale.ROOT,
                    "%s\t%s\t%d\t%d\t%d\t%d\t%.4f\t%d\t%d\t%.5f\t%.5f\t%.5f\t%.5f",
                    estimator, sample, width, height, medianNs, p95Ns, megapixelsPerSecond,
                    evaluatedPixels, invalidPixels, meanEndpointError, p95EndpointError,
                    badOnePixelRate, meanStaticDrift);
        }
    }

    private FlowEstimatorBakeoff() {}

    public static Result run(String estimatorName, Sample sample, int warmups, int iterations,
                             Estimator estimator, NanoClock clock) throws Exception {
        if (!usableLabel(estimatorName) || sample == null || estimator == null || clock == null
                || warmups < 0 || iterations <= 0) {
            throw new IllegalArgumentException("Invalid estimator bake-off run");
        }
        for (int i = 0; i < warmups; i++) estimate(sample, estimator);

        long[] elapsed = new long[iterations];
        FlowField result = null;
        for (int i = 0; i < iterations; i++) {
            long before = clock.now();
            result = estimate(sample, estimator);
            long duration = clock.now() - before;
            if (duration < 0) throw new IllegalStateException("Bake-off clock moved backwards");
            elapsed[i] = duration;
        }
        Arrays.sort(elapsed);
        long median = percentile(elapsed, 0.50);
        long p95 = percentile(elapsed, 0.95);
        Quality quality = quality(result, sample.reference());
        double throughput = median == 0 ? Double.POSITIVE_INFINITY
                : ((double) sample.from().width * sample.from().height * 1_000.0) / median;
        return new Result(estimatorName, sample.name(), sample.from().width, sample.from().height,
                median, p95, throughput, quality.evaluated, quality.invalid, quality.mean,
                quality.p95, quality.badOneRate, quality.staticDrift);
    }

    private static boolean usableLabel(String label) {
        return label != null && !label.isBlank() && label.indexOf('\t') < 0
                && label.indexOf('\n') < 0 && label.indexOf('\r') < 0;
    }

    private static FlowField estimate(Sample sample, Estimator estimator) throws Exception {
        FlowField field = estimator.estimate(sample.from(), sample.to(),
                sample.reference().fromPtsUs, sample.reference().toPtsUs);
        if (field == null || field.width != sample.from().width || field.height != sample.from().height
                || field.fromPtsUs != sample.reference().fromPtsUs
                || field.toPtsUs != sample.reference().toPtsUs) {
            throw new IllegalArgumentException("Estimator returned the wrong flow contract");
        }
        return field;
    }

    private static Quality quality(FlowField actual, FlowField reference) {
        double[] errors = new double[actual.width * actual.height];
        int count = 0, invalid = 0, bad = 0, staticCount = 0;
        double sum = 0, staticSum = 0;
        for (int y = 0; y < actual.height; y++) {
            for (int x = 0; x < actual.width; x++) {
                if (!(reference.confidence(x, y) >= 0.5f)) continue;
                double au = actual.u(x, y), av = actual.v(x, y);
                double ru = reference.u(x, y), rv = reference.v(x, y);
                if (!Double.isFinite(ru) || !Double.isFinite(rv)) continue;
                if (!Double.isFinite(au) || !Double.isFinite(av)) {
                    invalid++;
                    continue;
                }
                double error = Math.hypot(au - ru, av - rv);
                errors[count++] = error;
                sum += error;
                if (error > 1.0) bad++;
                if (Math.hypot(ru, rv) < 0.25) {
                    staticSum += Math.hypot(au, av);
                    staticCount++;
                }
            }
        }
        if (count == 0) {
            return new Quality(0, invalid, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY,
                    1.0, Double.POSITIVE_INFINITY);
        }
        Arrays.sort(errors, 0, count);
        return new Quality(count, invalid, sum / count,
                errors[percentileIndex(count, 0.95)], (double) (bad + invalid) / (count + invalid),
                staticCount == 0 ? 0 : staticSum / staticCount);
    }

    private static long percentile(long[] sorted, double fraction) {
        return sorted[percentileIndex(sorted.length, fraction)];
    }

    private static int percentileIndex(int count, double fraction) {
        return Math.max(0, (int) Math.ceil(fraction * count) - 1);
    }

    private record Quality(int evaluated, int invalid, double mean, double p95,
                           double badOneRate, double staticDrift) {}
}
