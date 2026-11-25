package com.fadcam.ui.faditor.motion;

import android.content.res.AssetManager;
import android.os.SystemClock;
import android.util.Log;

import com.fadcam.ui.faditor.motion.flow.DecodedFlowWork;
import com.fadcam.ui.faditor.motion.flow.ExactDecodedLuma;
import com.fadcam.ui.faditor.motion.flow.FlowCache;
import com.fadcam.ui.faditor.motion.flow.FlowField;
import com.fadcam.ui.faditor.motion.flow.LumaImage;
import com.fadcam.ui.faditor.motion.flow.MediaCodecLumaReader;

import java.io.IOException;
import java.util.Locale;

/** Verifies the decoded-pair analysis on consecutive P and B frames of the bundled clip. */
final class DecodedFlowDiagnostic {
    private static final String CLIP = "demo/card1.mp4";
    private static final int WIDTH = 240;
    private static final int HEIGHT = 135;
    private static final int DOWNSAMPLE = 4;
    private static final long FIRST_PTS_US = 466_667;
    private static final long SECOND_PTS_US = 500_000;

    private DecodedFlowDiagnostic() {}

    static void run(AssetManager assets) throws Exception {
        try (MediaCodecLumaReader reader = MediaCodecLumaReader.forAsset(assets, CLIP)) {
            long startMs = SystemClock.elapsedRealtime();
            // The known fractional P-frame time can round to either adjacent microsecond.
            ExactDecodedLuma.Frame first = reader.read(FIRST_PTS_US - 1, WIDTH, HEIGHT);
            if (Math.abs(first.ptsUs() - FIRST_PTS_US) > 1) {
                throw new IOException("Unexpected first decoded PTS: " + first.ptsUs());
            }
            long[] decodeMs = {SystemClock.elapsedRealtime() - startMs};
            FlowCache.Key key = new FlowCache.Key("bundled:" + CLIP, 0,
                    0, 0, WIDTH * DOWNSAMPLE, HEIGHT * DOWNSAMPLE, first.ptsUs(), SECOND_PTS_US,
                    "diagnostic-cpu-v1", "quarter", WIDTH, HEIGHT);
            DecodedFlowWork.Decoder exact = ExactDecodedLuma.adapter(reader);
            DecodedFlowWork work = new DecodedFlowWork(key, (pts, width, height) -> {
                long before = SystemClock.elapsedRealtime();
                LumaImage luma = exact.decode(pts, width, height);
                decodeMs[0] += SystemClock.elapsedRealtime() - before;
                if (pts == first.ptsUs() && luma != first.luma()) {
                    throw new IOException("Adjacent pair restarted the decoder");
                }
                return luma;
            });
            FlowCache.Pair pair = work.analyze(() -> Thread.currentThread().isInterrupted());
            long analysisMs = SystemClock.elapsedRealtime() - startMs - decodeMs[0];
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            if (pair == null || !matches(pair.forward, first.ptsUs(), SECOND_PTS_US)
                    || !matches(pair.backward, SECOND_PTS_US, first.ptsUs())) {
                throw new IOException("Analysis did not preserve decoded PTS and confidence");
            }
            double lumaDifference = meanDifference(first.luma(), exact.decode(SECOND_PTS_US, WIDTH, HEIGHT));
            if (!(lumaDifference > 0)) throw new IOException("Decoded luma frames are identical");
            Log.i(MotionFlowBenchActivity.TAG, String.format(Locale.ROOT,
                    "DECODED_PASS: %dx%d P/B PTS=%d,%d lumaMAE=%.6f decode=%dms analysis=%dms",
                    WIDTH, HEIGHT, first.ptsUs(), SECOND_PTS_US, lumaDifference, decodeMs[0], analysisMs));
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
