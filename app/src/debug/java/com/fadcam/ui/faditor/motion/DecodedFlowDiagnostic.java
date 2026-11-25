package com.fadcam.ui.faditor.motion;

import android.content.res.AssetManager;
import android.os.SystemClock;
import android.util.Log;

import com.fadcam.ui.faditor.motion.flow.DecodedFlowWork;
import com.fadcam.ui.faditor.motion.flow.ExactDecodedLuma;
import com.fadcam.ui.faditor.motion.flow.FlowAnalyzer;
import com.fadcam.ui.faditor.motion.flow.FlowCache;
import com.fadcam.ui.faditor.motion.flow.FlowField;
import com.fadcam.ui.faditor.motion.flow.LumaImage;
import com.fadcam.ui.faditor.motion.flow.MediaCodecLumaReader;
import com.fadcam.ui.faditor.motion.flow.IndexedFlowQueue;
import com.fadcam.ui.faditor.motion.flow.VideoPresentationIndex;

import java.io.File;
import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Verifies the decoded-pair analysis on consecutive P and B frames of the bundled clip. */
final class DecodedFlowDiagnostic {
    private static final String CLIP = "demo/card1.mp4";
    private static final int WIDTH = 240;
    private static final int HEIGHT = 135;
    private static final int DOWNSAMPLE = 4;
    private static final long FIRST_PTS_US = 466_667;
    private static final long SECOND_PTS_US = 500_000;

    private DecodedFlowDiagnostic() {}

    static void run(AssetManager assets, File cacheRoot) throws Exception {
        try (MediaCodecLumaReader reader = MediaCodecLumaReader.forAsset(assets, CLIP)) {
            var index = reader.scanPresentationIndex(() -> Thread.currentThread().isInterrupted());
            if (index == null) throw new IOException("Bundled clip has no presentation index");
            long startMs = SystemClock.elapsedRealtime();
            // The known fractional P-frame time can round to either adjacent microsecond.
            ExactDecodedLuma.Frame first = reader.read(FIRST_PTS_US - 1, WIDTH, HEIGHT);
            if (Math.abs(first.ptsUs() - FIRST_PTS_US) > 1) {
                throw new IOException("Unexpected first decoded PTS: " + first.ptsUs());
            }
            var next = index.next(first.ptsUs(), 0, Long.MAX_VALUE, 40_000);
            if (next == null || next.toPtsUs() != SECOND_PTS_US) {
                throw new IOException("Extractor display neighbours did not match the decoded fixture");
            }
            long[] decodeMs = {SystemClock.elapsedRealtime() - startMs};
            var source = new IndexedFlowQueue.Source("bundled:" + CLIP + ":" + java.util.UUID.randomUUID(),
                    0, 0, 0, WIDTH * DOWNSAMPLE, HEIGHT * DOWNSAMPLE,
                    "diagnostic-cpu-v1", "quarter", WIDTH, HEIGHT, 40_000);
            var cache = new FlowCache(new File(cacheRoot, "motion-flow-diagnostic"), 16L << 20);
            DecodedFlowWork.Decoder exact = ExactDecodedLuma.adapter(reader);
            FlowCache.Pair pair = runQueued(cache, index, source, next, false, (pts, width, height) -> {
                long before = SystemClock.elapsedRealtime();
                LumaImage luma = exact.decode(pts, width, height);
                decodeMs[0] += SystemClock.elapsedRealtime() - before;
                if (pts == first.ptsUs() && luma != first.luma()) {
                    throw new IOException("Adjacent pair restarted the decoder");
                }
                return luma;
            });
            long analysisMs = SystemClock.elapsedRealtime() - startMs - decodeMs[0];
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            if (pair == null || !matches(pair.forward, first.ptsUs(), SECOND_PTS_US)
                    || !matches(pair.backward, SECOND_PTS_US, first.ptsUs())) {
                throw new IOException("Analysis did not preserve decoded PTS and confidence");
            }
            double lumaDifference = meanDifference(first.luma(), exact.decode(SECOND_PTS_US, WIDTH, HEIGHT));
            if (!(lumaDifference > 0)) throw new IOException("Decoded luma frames are identical");
            runQueued(cache, index, source, next, true, (pts, width, height) -> {
                throw new IOException("Warm replay unexpectedly decoded footage");
            });
            Log.i(MotionFlowBenchActivity.TAG, String.format(Locale.ROOT,
                    "DECODED_PASS: %dx%d P/B PTS=%d,%d lumaMAE=%.6f decode=%dms analysis=%dms",
                    WIDTH, HEIGHT, first.ptsUs(), SECOND_PTS_US, lumaDifference, decodeMs[0], analysisMs));
        }
    }

    private static FlowCache.Pair runQueued(FlowCache cache, VideoPresentationIndex index,
            IndexedFlowQueue.Source source, VideoPresentationIndex.Pair expected, boolean warm,
            DecodedFlowWork.Decoder decoder) throws Exception {
        var result = new CompletableFuture<FlowCache.Pair>();
        try (var analyzer = new FlowAnalyzer(cache, new FlowAnalyzer.Listener() {
            @Override public void ready(long generation, FlowCache.Key key, FlowCache.Pair pair,
                                        boolean fromCache) {
                if (generation == 1 && key.fromPtsUs == expected.fromPtsUs()
                        && key.toPtsUs == expected.toPtsUs() && fromCache == warm) result.complete(pair);
                else result.completeExceptionally(new IOException("Unexpected indexed queue result"));
            }

            @Override public void failed(long generation, FlowCache.Key key, Exception problem) {
                result.completeExceptionally(problem);
            }
        })) {
            var queue = new IndexedFlowQueue(analyzer, index, source, decoder, 1);
            queue.plan(expected.fromPtsUs(), expected.fromPtsUs(), expected.toPtsUs(),
                    expected.fromPtsUs(), expected.toPtsUs() + 1);
            return result.get(45, TimeUnit.SECONDS);
        }
    }

    private static double meanDifference(LumaImage first, LumaImage second) {
        double total = 0;
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                total += Math.abs(first.at(x, y) - second.at(x, y));
            }
        }
        return total / (WIDTH * HEIGHT);
    }

    private static boolean matches(FlowField field, long fromPts, long toPts) {
        return field.fromPtsUs == fromPts && field.toPtsUs == toPts
                && field.width == WIDTH && field.height == HEIGHT && field.hasConfidence();
    }
}
