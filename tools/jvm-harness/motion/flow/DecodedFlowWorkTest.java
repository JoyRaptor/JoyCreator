package com.fadcam.ui.faditor.motion.flow;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** STU-7 C6: lazy pair decode, bidirectional confidence and cancellation fences. */
public final class DecodedFlowWorkTest {
    private int assertions;

    public static void main(String[] args) throws Exception {
        DecodedFlowWorkTest test = new DecodedFlowWorkTest();
        test.buildsTrustedPair();
        test.cancelsBetweenExpensiveStages();
        System.out.println("DecodedFlowWorkTest: " + test.assertions + " assertions");
    }

    private void buildsTrustedPair() throws Exception {
        AtomicInteger decodes = new AtomicInteger();
        AtomicInteger estimates = new AtomicInteger();
        FlowCache.Key key = key();
        DecodedFlowWork work = new DecodedFlowWork(key, (pts, width, height) -> {
            decodes.incrementAndGet();
            return image(width, height, pts == key.fromPtsUs ? 0 : 1);
        }, (from, to, fromPts, toPts) -> {
            estimates.incrementAndGet();
            return FlowField.zero(from.width, from.height, fromPts, toPts);
        }, FlowConfidence.Params.DEFAULT);
        FlowCache.Pair pair = work.analyze(() -> false);
        check(decodes.get() == 2, "one analysis decodes only its two source frames");
        check(estimates.get() == 2, "forward and backward estimates both run");
        check(pair.forward.hasConfidence() && pair.backward.hasConfidence(),
                "both directions carry confidence");
        check(pair.forward.fromPtsUs == 100 && pair.backward.fromPtsUs == 200,
                "the two directions retain real source PTS");
    }

    private void cancelsBetweenExpensiveStages() throws Exception {
        for (int stopAfterDecode = 0; stopAfterDecode <= 2; stopAfterDecode++) {
            AtomicInteger decodes = new AtomicInteger();
            AtomicInteger estimates = new AtomicInteger();
            AtomicBoolean cancelled = new AtomicBoolean();
            int stop = stopAfterDecode;
            DecodedFlowWork work = new DecodedFlowWork(key(), (pts, width, height) -> {
                int count = decodes.incrementAndGet();
                if (count == stop) cancelled.set(true);
                return image(width, height, count);
            }, (from, to, fromPts, toPts) -> {
                estimates.incrementAndGet();
                return FlowField.zero(from.width, from.height, fromPts, toPts);
            }, FlowConfidence.Params.DEFAULT);
            if (stop == 0) cancelled.set(true);
            check(work.analyze(cancelled::get) == null, "cancelled decode stage returns no pair");
            check(estimates.get() == 0, "cancelled decode stage starts no estimator");
        }

        AtomicInteger estimates = new AtomicInteger();
        AtomicBoolean cancelled = new AtomicBoolean();
        DecodedFlowWork duringEstimate = new DecodedFlowWork(key(),
                (pts, width, height) -> image(width, height, 0),
                (from, to, fromPts, toPts) -> {
                    estimates.incrementAndGet();
                    cancelled.set(true);
                    return FlowField.zero(from.width, from.height, fromPts, toPts);
                }, FlowConfidence.Params.DEFAULT);
        check(duringEstimate.analyze(cancelled::get) == null, "cancelled estimate returns no pair");
        check(estimates.get() == 1, "cancellation prevents the reverse estimator");
    }

    private static FlowCache.Key key() {
        return new FlowCache.Key("decoded-fixture", 0, 0, 0, 0, 0,
                100, 200, "cpu-r1", "high", 8, 6);
    }

    private static LumaImage image(int width, int height, int seed) {
        float[] pixels = new float[width * height];
        for (int i = 0; i < pixels.length; i++) pixels[i] = ((i * 31 + seed * 17) & 255) / 255f;
        return new LumaImage(width, height, pixels);
    }

    private void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }
}
