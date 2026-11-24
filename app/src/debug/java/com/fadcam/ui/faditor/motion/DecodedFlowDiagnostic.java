package com.fadcam.ui.faditor.motion;

import android.content.res.AssetFileDescriptor;
import android.content.res.AssetManager;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.SystemClock;
import android.util.Log;

import com.fadcam.ui.faditor.motion.flow.DecodedFlowWork;
import com.fadcam.ui.faditor.motion.flow.FlowCache;
import com.fadcam.ui.faditor.motion.flow.FlowField;
import com.fadcam.ui.faditor.motion.flow.LumaImage;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Locale;

/** Verifies the decoded-pair analysis on consecutive P and B frames of the bundled clip. */
final class DecodedFlowDiagnostic {
    private static final String CLIP = "demo/card1.mp4";
    private static final int WIDTH = 240;
    private static final int HEIGHT = 135;
    private static final int DOWNSAMPLE = 4;
    private static final int FIRST_FRAME = 14;
    private static final long FIRST_PTS_US = 466_667;
    private static final long SECOND_PTS_US = 500_000;
    private static final long DEQUEUE_US = 10_000;
    private static final long DECODE_TIMEOUT_MS = 15_000;

    private DecodedFlowDiagnostic() {}

    static void run(AssetManager assets) throws Exception {
        long startMs = SystemClock.elapsedRealtime();
        Frame[] frames = readPair(assets);
        long decodedMs = SystemClock.elapsedRealtime();
        Frame first = frames[0];
        Frame second = frames[1];
        // The fixture's P and B frame timestamps are known independently of the decoder.
        // Container timescale conversion can round the fractional PTS either way.
        if (Math.abs(first.ptsUs - FIRST_PTS_US) > 1 || second.ptsUs != SECOND_PTS_US) {
            throw new IOException("Unexpected decoded PTS: " + first.ptsUs + ", " + second.ptsUs);
        }
        double lumaDifference = meanDifference(first.luma, second.luma);
        if (!(lumaDifference > 0)) throw new IOException("Decoded luma frames are identical");

        FlowCache.Key key = new FlowCache.Key("bundled:demo/card1.mp4", 0,
                0, 0, WIDTH * DOWNSAMPLE, HEIGHT * DOWNSAMPLE, first.ptsUs, second.ptsUs,
                "diagnostic-cpu-v1", "quarter", WIDTH, HEIGHT);
        DecodedFlowWork work = new DecodedFlowWork(key, (pts, width, height) -> {
            if (width != WIDTH || height != HEIGHT) {
                throw new IOException("Unexpected diagnostic analysis size");
            }
            if (pts == first.ptsUs) return first.luma;
            if (pts == second.ptsUs) return second.luma;
            throw new IOException("Requested PTS was not decoded: " + pts);
        });
        FlowCache.Pair pair = work.analyze(() -> Thread.currentThread().isInterrupted());
        checkInterrupted();
        if (pair == null || !matches(pair.forward, first.ptsUs, second.ptsUs)
                || !matches(pair.backward, second.ptsUs, first.ptsUs)) {
            throw new IOException("Analysis did not preserve decoded PTS and confidence");
        }
        Log.i(MotionFlowBenchActivity.TAG, String.format(Locale.ROOT,
                "DECODED_PASS: %dx%d P/B PTS=%d,%d lumaMAE=%.6f decode=%dms analysis=%dms",
                WIDTH, HEIGHT, first.ptsUs, second.ptsUs, lumaDifference,
                decodedMs - startMs, SystemClock.elapsedRealtime() - decodedMs));
    }

    private static Frame[] readPair(AssetManager assets) throws IOException, InterruptedException {
        MediaExtractor extractor = new MediaExtractor();
        try {
            try (AssetFileDescriptor source = assets.openFd(CLIP)) {
                extractor.setDataSource(
                        source.getFileDescriptor(), source.getStartOffset(), source.getLength());
            }
            MediaFormat format = selectVideo(extractor);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
            MediaCodec codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME));
            try {
                codec.configure(format, null, null, 0);
                codec.start();
                return decodePair(codec, extractor);
            } finally {
                codec.release();
            }
        } finally {
            extractor.release();
        }
    }

    private static MediaFormat selectVideo(MediaExtractor extractor) throws IOException {
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            MediaFormat format = extractor.getTrackFormat(i);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("video/")) {
                extractor.selectTrack(i);
                return format;
            }
        }
        throw new IOException("Bundled fixture has no video track");
    }

    private static Frame[] decodePair(MediaCodec codec, MediaExtractor extractor)
            throws IOException, InterruptedException {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        Frame[] pair = new Frame[2];
        long deadline = SystemClock.elapsedRealtime() + DECODE_TIMEOUT_MS;
        boolean inputEnded = false;
        int framesSeen = 0;
        while (SystemClock.elapsedRealtime() < deadline) {
            checkInterrupted();
            if (!inputEnded) inputEnded = feedInput(codec, extractor);
            int output = codec.dequeueOutputBuffer(info, DEQUEUE_US);
            if (output < 0) continue;
            try {
                boolean isFrame = info.size > 0
                        && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0;
                int frameIndex = isFrame ? framesSeen++ : -1;
                if (frameIndex >= FIRST_FRAME) {
                    int pairIndex = frameIndex - FIRST_FRAME;
                    try (Image image = codec.getOutputImage(output)) {
                        if (image == null) throw new IOException("Decoder returned no YUV image");
                        pair[pairIndex] = new Frame(info.presentationTimeUs, quarterLuma(image));
                    }
                    if (pairIndex == pair.length - 1) return pair;
                }
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
            } finally {
                codec.releaseOutputBuffer(output, false);
            }
        }
        throw new IOException("Decoder did not produce the two fixture frames");
    }

    private static boolean feedInput(MediaCodec codec, MediaExtractor extractor) throws IOException {
        int input = codec.dequeueInputBuffer(DEQUEUE_US);
        if (input < 0) return false;
        ByteBuffer buffer = codec.getInputBuffer(input);
        if (buffer == null) throw new IOException("Missing codec input buffer");
        int size = extractor.readSampleData(buffer, 0);
        if (size < 0) {
            codec.queueInputBuffer(input, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
            return true;
        }
        codec.queueInputBuffer(input, 0, size, extractor.getSampleTime(), 0);
        extractor.advance();
        return false;
    }

    private static LumaImage quarterLuma(Image image) throws IOException {
        Rect crop = image.getCropRect();
        if (image.getFormat() != ImageFormat.YUV_420_888
                || crop.width() != WIDTH * DOWNSAMPLE || crop.height() != HEIGHT * DOWNSAMPLE) {
            throw new IOException("Expected the fixture's visible 960x540 flexible YUV frame");
        }
        Image.Plane plane = image.getPlanes()[0];
        ByteBuffer bytes = plane.getBuffer();
        int rowStride = plane.getRowStride();
        int pixelStride = plane.getPixelStride();
        int origin = bytes.position() + crop.top * rowStride + crop.left * pixelStride;
        float[] luma = new float[WIDTH * HEIGHT];
        for (int y = 0; y < HEIGHT; y++) {
            int row = origin + y * DOWNSAMPLE * rowStride;
            for (int x = 0; x < WIDTH; x++) {
                int left = row + x * DOWNSAMPLE * pixelStride;
                int total = 0;
                for (int dy = 0; dy < DOWNSAMPLE; dy++) {
                    int start = left + dy * rowStride;
                    for (int dx = 0; dx < DOWNSAMPLE; dx++) {
                        total += bytes.get(start + dx * pixelStride) & 0xff;
                    }
                }
                luma[y * WIDTH + x] = total / (255f * DOWNSAMPLE * DOWNSAMPLE);
            }
        }
        return new LumaImage(WIDTH, HEIGHT, luma);
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

    private static void checkInterrupted() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
    }

    private record Frame(long ptsUs, LumaImage luma) {}
}
