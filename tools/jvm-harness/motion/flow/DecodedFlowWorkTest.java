package com.fadcam.ui.faditor.motion.flow;

import java.nio.file.Files;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** STU-7 C6: lazy pair decode, bidirectional confidence and cancellation fences. */
public final class DecodedFlowWorkTest {
    private int assertions;

    public static void main(String[] args) throws Exception {
        DecodedFlowWorkTest test = new DecodedFlowWorkTest();
        test.buildsTrustedPair();
        test.cancelsBetweenExpensiveStages();
        test.skipsDuplicateAndLongGap();
        test.assessesFlashAndWideMotion();
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
                (pts, width, height) -> image(width, height, pts == 100 ? 0 : 1),
                (from, to, fromPts, toPts) -> {
                    estimates.incrementAndGet();
                    cancelled.set(true);
                    return FlowField.zero(from.width, from.height, fromPts, toPts);
                }, FlowConfidence.Params.DEFAULT);
        check(duringEstimate.analyze(cancelled::get) == null, "cancelled estimate returns no pair");
        check(estimates.get() == 1, "cancellation prevents the reverse estimator");
    }

    private void skipsDuplicateAndLongGap() throws Exception {
        DecodedFlowWork duplicate = new DecodedFlowWork(key(),
                (pts, width, height) -> image(width, height, 0),
                (from, to, a, b) -> { throw new AssertionError("duplicate reached estimator"); },
                FlowConfidence.Params.DEFAULT);
        FlowCache.Pair still = duplicate.analyze(() -> false);
        check(still.has(FlowCache.Flag.DUPLICATE) && still.forward.u(3, 2) == 0
                && !still.has(FlowCache.Flag.LONG_GAP), "duplicate reports zero motion");

        FlowCache.Key strict = new FlowCache.Key("decoded-fixture", 0, 0, 0, 0, 0,
                100, 200, "cpu-r1", "high", 8, 6, 99);
        FlowCache.Pair gap = new DecodedFlowWork(strict, (pts, width, height) -> {
            throw new AssertionError("long gap reached decoder");
        }).analyze(() -> false);
        check(gap.has(FlowCache.Flag.LONG_GAP) && gap.forward.confidence(0, 0) == 0
                && gap.backward.confidence(0, 0) == 0, "long gap rejects motion in both directions");

        var dir = Files.createTempDirectory("stu7-assessed-flow");
        try {
            FlowCache cache = new FlowCache(dir.toFile());
            cache.put(strict, gap);
            check(cache.get(key()) == null, "different gap policy cannot reuse a rejected pair");
            check(cache.get(strict).has(FlowCache.Flag.LONG_GAP), "cache preserves assessment flags");
            try (var files = Files.list(dir)) {
                for (var file : files.toArray(java.nio.file.Path[]::new)) Files.delete(file);
            }
        } finally {
            Files.deleteIfExists(dir);
        }
    }

    private void assessesFlashAndWideMotion() {
        int w = 64, h = 48, dx = 8;
        float[] texture = new float[w * h], brighter = new float[w * h];
        for (int i = 0; i < texture.length; i++) {
            texture[i] = 0.2f + ((i * 31) & 63) * 0.008f;
            brighter[i] = texture[i] + 0.13f;
        }
        LumaImage from = new LumaImage(w, h, texture);
        FlowConfidence.Params cp = FlowConfidence.Params.DEFAULT;
        var stillWalk = FlowPairAssessment.assess(from, new LumaImage(w, h, brighter),
                FlowField.zero(w, h, 0, 1), FlowField.zero(w, h, 1, 0), cp);
        check(stillWalk.contains(FlowCache.Flag.FLASH) && stillWalk.size() == 1,
                "uniform light jump is a flash without inventing motion");

        float[] shifted = new float[w * h];
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            shifted[y * w + x] = from.at(x - dx, y);
        }
        var wide = FlowPairAssessment.assess(from, new LumaImage(w, h, shifted),
                translation(w, h, dx, 0, 1), translation(w, h, -dx, 1, 0), cp);
        check(wide.contains(FlowCache.Flag.POSSIBLE_WHIP_PAN), "wide coherent shift is a whip candidate");
        check(wide.contains(FlowCache.Flag.OCCLUSION) && !wide.contains(FlowCache.Flag.FLASH),
                "unpaired edges are flagged and a translated texture is not a flash");
        var mismatched = FlowPairAssessment.assess(from, new LumaImage(w, h, shifted),
                translation(w, h, dx, 0, 1), FlowField.zero(w, h, 1, 0), cp);
        check(mismatched.contains(FlowCache.Flag.OCCLUSION)
                && !mismatched.contains(FlowCache.Flag.POSSIBLE_WHIP_PAN),
                "inconsistent vectors cannot trigger a camera motion flag");
    }

    private static FlowField translation(int w, int h, float dx, long from, long to) {
        float[] u = new float[w * h];
        Arrays.fill(u, dx);
        return new FlowField(w, h, u, new float[w * h], null, from, to);
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
