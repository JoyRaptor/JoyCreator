package com.fadcam.ui.faditor.motion.flow;

import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Sequential flexible-YUV decoder for C6 background flow analysis.
 *
 * <p>The returned frame carries the codec's real presentation timestamp. Callers adapt it through
 * {@link ExactDecodedLuma#adapter}; a seek request that lands on a different frame therefore fails
 * instead of poisoning a cache entry labelled with the requested time. Frames skipped while
 * decoding are released without conversion, and only one analysis-sized luma image is retained.
 * Crop and container rotation are applied together, producing luma in upright coordinates.
 */
public final class MediaCodecLumaReader implements ExactDecodedLuma.Reader, AutoCloseable {
    private static final long DEQUEUE_US = 10_000L;
    private static final long REQUEST_TIMEOUT_MS = 30_000L;
    private static final int MAX_STALLED_DRAINS = 2_000;

    private final Context context;
    private final Uri uri;
    private final boolean seekToFirstRequest;
    @Nullable private MediaExtractor extractor;
    @Nullable private MediaCodec codec;
    private boolean inputEnded;
    private boolean closed;
    private int rotationDegrees;
    private long lastOutputPtsUs = Long.MIN_VALUE;
    @Nullable private ExactDecodedLuma.Frame lastFrame;

    /** Safe default for fragmented recordings: decode from the head and never make a blocking seek. */
    public MediaCodecLumaReader(@NonNull Context context, @NonNull Uri uri) {
        this(context, uri, false);
    }

    /**
     * @param seekToFirstRequest only for sources already known to have a trustworthy seek table.
     */
    public MediaCodecLumaReader(@NonNull Context context, @NonNull Uri uri,
                                boolean seekToFirstRequest) {
        this.context = context.getApplicationContext();
        this.uri = uri;
        this.seekToFirstRequest = seekToFirstRequest;
    }

    @Override
    public synchronized ExactDecodedLuma.Frame read(long requestedPtsUs, int width, int height)
            throws Exception {
        requireOpen();
        if (requestedPtsUs < 0 || width <= 0 || height <= 0) {
            throw new IllegalArgumentException("invalid decoded luma request");
        }
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
        // Consecutive analysis pairs share their boundary frame. Reuse its analysis-sized luma
        // instead of restarting the sequential decoder at every pair boundary.
        if (lastFrame != null && lastFrame.ptsUs() == requestedPtsUs
                && lastFrame.luma().width == width && lastFrame.luma().height == height) {
            return lastFrame;
        }
        // A worker can reprioritise to an earlier visible pair. Rebuild rather than reuse a future
        // decoder state; this keeps exact PTS semantics and never retains old frame pixels.
        if (lastOutputPtsUs != Long.MIN_VALUE && requestedPtsUs <= lastOutputPtsUs) resetDecoder();
        if (codec == null) open(requestedPtsUs);
        try {
            lastFrame = decodeTo(requestedPtsUs, width, height);
            return lastFrame;
        } catch (Exception failure) {
            resetDecoder();
            throw failure;
        }
    }

    private void open(long firstPtsUs) throws IOException {
        MediaExtractor candidateExtractor = new MediaExtractor();
        MediaCodec candidateCodec = null;
        try {
            candidateExtractor.setDataSource(context, uri, null);
            MediaFormat format = selectVideo(candidateExtractor);
            if (seekToFirstRequest && firstPtsUs > 0) {
                candidateExtractor.seekTo(firstPtsUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            }
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime == null) throw new IOException("video track has no MIME type");
            int rotation = format.containsKey(MediaFormat.KEY_ROTATION)
                    ? Math.floorMod(format.getInteger(MediaFormat.KEY_ROTATION), 360) : 0;
            if (rotation % 90 != 0) throw new IOException("unsupported video rotation: " + rotation);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
            candidateCodec = MediaCodec.createDecoderByType(mime);
            candidateCodec.configure(format, null, null, 0);
            candidateCodec.start();
            extractor = candidateExtractor;
            codec = candidateCodec;
            rotationDegrees = rotation;
            inputEnded = false;
        } catch (Exception failure) {
            if (candidateCodec != null) try { candidateCodec.release(); } catch (Exception ignored) { }
            try { candidateExtractor.release(); } catch (Exception ignored) { }
            if (failure instanceof IOException io) throw io;
            throw new IOException("could not open source for flow analysis", failure);
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
        throw new IOException("source has no video track");
    }

    private ExactDecodedLuma.Frame decodeTo(long requestedPtsUs, int width, int height)
            throws IOException, InterruptedException {
        MediaExtractor ex = extractor;
        MediaCodec c = codec;
        if (ex == null || c == null) throw new IOException("decoder is not open");
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        long deadline = SystemClock.elapsedRealtime() + REQUEST_TIMEOUT_MS;
        int stalled = 0;
        while (SystemClock.elapsedRealtime() < deadline) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            if (!inputEnded) inputEnded = feedInput(c, ex);
            int output = c.dequeueOutputBuffer(info, DEQUEUE_US);
            if (output < 0) {
                if (inputEnded && output == MediaCodec.INFO_TRY_AGAIN_LATER
                        && ++stalled > MAX_STALLED_DRAINS) break;
                continue;
            }
            try {
                boolean frame = info.size > 0
                        && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0;
                if (frame) {
                    lastOutputPtsUs = info.presentationTimeUs;
                    if (info.presentationTimeUs >= requestedPtsUs) {
                        try (Image image = c.getOutputImage(output)) {
                            if (image == null) throw new IOException("decoder returned no YUV image");
                            return new ExactDecodedLuma.Frame(info.presentationTimeUs,
                                    luma(image, width, height, rotationDegrees));
                        }
                    }
                }
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
            } finally {
                c.releaseOutputBuffer(output, false);
            }
        }
        throw new IOException("decoder did not reach requested PTS " + requestedPtsUs);
    }

    private static boolean feedInput(MediaCodec codec, MediaExtractor extractor) throws IOException {
        int input = codec.dequeueInputBuffer(DEQUEUE_US);
        if (input < 0) return false;
        ByteBuffer buffer = codec.getInputBuffer(input);
        if (buffer == null) throw new IOException("missing codec input buffer");
        int size = extractor.readSampleData(buffer, 0);
        if (size < 0) {
            codec.queueInputBuffer(input, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
            return true;
        }
        codec.queueInputBuffer(input, 0, size, extractor.getSampleTime(), 0);
        extractor.advance();
        return false;
    }

    private static LumaImage luma(Image image, int width, int height, int rotationDegrees)
            throws IOException {
        if (image.getFormat() != ImageFormat.YUV_420_888) {
            throw new IOException("decoder output is not flexible YUV");
        }
        Rect crop = image.getCropRect();
        Image.Plane plane = image.getPlanes()[0];
        ByteBuffer bytes = plane.getBuffer();
        int origin = bytes.position() + crop.top * plane.getRowStride()
                + crop.left * plane.getPixelStride();
        try {
            return YPlaneLuma.boxDownsample(bytes, origin, plane.getRowStride(),
                    plane.getPixelStride(), crop.width(), crop.height(), width, height, rotationDegrees);
        } catch (IllegalArgumentException invalid) {
            throw new IOException("invalid decoded Y plane", invalid);
        }
    }

    @Override public synchronized void close() { closed = true; resetDecoder(); }

    private void resetDecoder() {
        MediaCodec oldCodec = codec; codec = null;
        MediaExtractor oldExtractor = extractor; extractor = null;
        inputEnded = false;
        rotationDegrees = 0;
        lastOutputPtsUs = Long.MIN_VALUE;
        lastFrame = null;
        if (oldCodec != null) {
            try { oldCodec.stop(); } catch (Exception ignored) { }
            try { oldCodec.release(); } catch (Exception ignored) { }
        }
        if (oldExtractor != null) try { oldExtractor.release(); } catch (Exception ignored) { }
    }

    private void requireOpen() {
        if (closed) throw new IllegalStateException("decoded luma reader has been closed");
    }
}
