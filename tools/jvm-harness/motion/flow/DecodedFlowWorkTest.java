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
        test.fencesDecodedSceneCut();
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
        check(!wide.contains(FlowCache.Flag.SCENE_CUT), "coherent whip motion does not lose its pair");
        check(wide.contains(FlowCache.Flag.OCCLUSION) && !wide.contains(FlowCache.Flag.FLASH),
                "unpaired edges are flagged and a translated texture is not a flash");
        var mismatched = FlowPairAssessment.assess(from, new LumaImage(w, h, shifted),
                translation(w, h, dx, 0, 1), FlowField.zero(w, h, 1, 0), cp);
        check(mismatched.contains(FlowCache.Flag.OCCLUSION)
                && !mismatched.contains(FlowCache.Flag.POSSIBLE_WHIP_PAN),
                "inconsistent vectors cannot trigger a camera motion flag");
    }

    private void fencesDecodedSceneCut() throws Exception {
        FlowCache.Key key = new FlowCache.Key("scene-cut", 0, 0, 0, 0, 0,
                116_000, 120_000, "cpu-r1", "high", 24, 20);
        int count = key.width * key.height;
        float[] earlier = new float[count], later = new float[count];
        float[] flashed = new float[count], compressedHighlights = new float[count];
        for (int i = 0; i < count; i++) {
            earlier[i] = ((i + i / key.width) & 1) == 0 ? .12f : .60f;
            later[i] = 1f - earlier[i];
            flashed[i] = earlier[i] + .28f;
            compressedHighlights[i] = earlier[i] * .55f + .43f;
        }
        var from = new LumaImage(key.width, key.height, earlier);
        var to = new LumaImage(key.width, key.height, later);
        DecodedFlowWork.Estimator unaligned = (a, b, fromUs, toUs) ->
                FlowField.zero(a.width, a.height, fromUs, toUs);
        var cut = new DecodedFlowWork(key, (pts, w, h) -> pts == key.fromPtsUs ? from : to,
                unaligned, FlowConfidence.Params.DEFAULT).analyze(() -> false);
        check(cut.crossesCut(), "large decoded change with collapsed trust is a scene cut");
        check(cut.forward.fromPtsUs == key.fromPtsUs && cut.forward.toPtsUs == key.toPtsUs
                        && cut.forward.confidence(0, 0) == 0 && cut.backward.confidence(0, 0) == 0,
                "scene cut preserves exact endpoints and rejects both directions");
        var forward = FlowField.zero(key.width, key.height, key.fromPtsUs, key.toPtsUs);
        var backward = FlowField.zero(key.width, key.height, key.toPtsUs, key.fromPtsUs);
        check(!FlowPairAssessment.assess(from, to, forward, backward, FlowConfidence.Params.DEFAULT)
                        .contains(FlowCache.Flag.SCENE_CUT),
                "large jump without a collapse in confidence does not fence the pair");
        var dark = new LumaImage(key.width, key.height, new float[count]);
        float[] dimmed = new float[count];
        Arrays.fill(dimmed, .02f);
        var dim = new LumaImage(key.width, key.height, dimmed);
        check(!FlowPairAssessment.assess(dark, dim, forward.withConfidence(new float[count]),
                        backward.withConfidence(new float[count]), FlowConfidence.Params.DEFAULT)
                        .contains(FlowCache.Flag.SCENE_CUT),
                "collapsed confidence in low light without a large jump does not fence the pair");
        for (float[] flashedLuma : new float[][]{flashed, compressedHighlights}) {
            var flash = new LumaImage(key.width, key.height, flashedLuma);
            for (boolean enteringFlash : new boolean[]{true, false}) {
                var flashPair = new DecodedFlowWork(key, (pts, w, h) ->
                        (pts == key.fromPtsUs) == enteringFlash ? from : flash,
                        unaligned, FlowConfidence.Params.DEFAULT).analyze(() -> false);
                check(flashPair.has(FlowCache.Flag.FLASH) && !flashPair.crossesCut(),
                        "large exposure change that keeps the image texture is a flash in either direction");
            }
        }
        var dir = Files.createTempDirectory("stu7-scene-cut-cache");
        try {
            FlowCache cache = new FlowCache(dir.toFile());
            cache.put(key, cut);
            check(new FlowCache(dir.toFile()).get(key).crossesCut(), "warm cache retains the scene-cut fence");
            try (var files = Files.list(dir)) {
                for (var file : files.toArray(java.nio.file.Path[]::new)) Files.delete(file);
            }
        } finally {
            Files.deleteIfExists(dir);
        }
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
