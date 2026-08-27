package com.fadcam.ui.faditor.export;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.FLog;

import java.nio.ByteBuffer;

/**
 * ONE forward MediaCodec decode per source, handing out frames in the order the export asks
 * for them. This is the export-side twin of {@code FilmstripSweepExtractor}, which solved the
 * same problem for the timeline filmstrip and which the export path never adopted.
 *
 * <p><b>Why this exists.</b> The frame sources it replaces called
 * {@code MediaMetadataRetriever.getFrameAtTime(t, OPTION_CLOSEST)} once per exported frame.
 * That call seeks back to the preceding sync sample and decodes forward to {@code t} EVERY
 * time, so a source with keyframes a second or two apart re-decodes dozens of frames to
 * produce one — thirty times per second of output. Measured on JoyRaptor's Note 9 (2026-08-27):
 * roughly 0.8 SECONDS per call, 275 calls, which was the entire multi-minute stall in the
 * middle of a 46-second export.
 *
 * <p>An export walks time FORWARD, so the seeking was pure waste: every frame it wanted was
 * the next one the decoder would have produced anyway. This class decodes forward once and
 * keeps the most recent frame, which turns N seek-and-decodes into one linear pass.
 *
 * <h3>Contract</h3>
 * <ul>
 *   <li>{@link #frameAt} returns the first decoded frame whose presentation time is at or
 *       after the requested time — the same rule {@code FilmstripSweepExtractor} uses, and
 *       never the keyframe-snapping that {@code OPTION_CLOSEST_SYNC} would give.</li>
 *   <li>The returned bitmap is <b>BORROWED</b>: it belongs to the reader and stays valid only
 *       until the next {@link #frameAt} or {@link #release}. Callers must not recycle it.</li>
 *   <li>Requests are expected to be non-decreasing. A backward jump is honoured by rebuilding
 *       the decoder and sweeping again; after {@link #MAX_REWINDS} of those the reader
 *       declares itself {@link #isDegraded() degraded} and returns null forever, so the caller
 *       can fall back to the retriever rather than have this class become the slow path it
 *       was written to remove.</li>
 *   <li>Never throws to the caller. Any failure degrades to null and the caller's fallback.</li>
 * </ul>
 *
 * <p>Memory stays at one decoded frame regardless of source length — the reason
 * {@code FilmstripSweepExtractor.sweep()} could not simply be called here: it returns every
 * requested bitmap at once, which at export resolution is far too much to hold.
 */
final class SequentialFrameReader {

    private static final String TAG = "SeqFrameReader";
    private static final long DEQUEUE_TIMEOUT_US = 10_000L;
    /** Safety valve: bail if the decoder produces nothing for this many drains after EOS input. */
    private static final int MAX_STALLED_DRAINS = 2000;
    /** After this many backward jumps the sweep is the wrong tool; hand back to the retriever. */
    private static final int MAX_REWINDS = 8;
    /**
     * Ceiling on the subsample factor in {@link #imageToBitmap}. A PiP scaled far down does not
     * need full-resolution pixels, but past 4x the nearest-neighbour sampling starts to show
     * on a slow zoom, so the saving stops here.
     */
    private static final int MAX_SUBSAMPLE = 4;

    private final Context context;
    private final Uri uri;

    @Nullable private MediaExtractor extractor;
    @Nullable private MediaCodec codec;
    private boolean started = false;
    private boolean inputDone = false;
    private boolean streamEnded = false;
    private boolean degraded = false;
    private int rotationDeg = 0;

    /** The frame currently in hand, and its source presentation time. */
    @Nullable private Bitmap current;
    private long currentPtsUs = Long.MIN_VALUE;
    /** The subsample factor {@link #current} was converted at, so a finer request re-decodes. */
    private int currentStep = 0;
    private long lastRequestUs = Long.MIN_VALUE;

    private int decodedFrames = 0;
    private int convertedFrames = 0;
    private int reuseHits = 0;
    private int rewinds = 0;

    SequentialFrameReader(@NonNull Context context, @NonNull Uri uri) {
        this.context = context.getApplicationContext();
        this.uri = uri;
    }

    boolean isDegraded() { return degraded; }

    /**
     * The frame at {@code sourceUs}, or null if this reader cannot serve it.
     *
     * @param maxOutW an upper bound on how wide this frame will be DRAWN. Anything larger is
     *                thrown away by the destination rect, so the frame is converted at a
     *                subsample factor that still covers it — on a small PiP that is the
     *                difference between touching two million pixels and touching a hundred
     *                thousand. Pass 0 to convert at full resolution.
     * @param maxOutH as {@code maxOutW}, for height.
     * @return a BORROWED bitmap valid until the next call, or null.
     */
    @Nullable
    Bitmap frameAt(long sourceUs, int maxOutW, int maxOutH) {
        if (degraded) return null;
        try {
            if (lastRequestUs != Long.MIN_VALUE && sourceUs < lastRequestUs) {
                // Backward jump. The sweep can only go forward, so start it over.
                if (++rewinds > MAX_REWINDS) {
                    FLog.w(TAG, "Too many backward seeks (" + rewinds + ") for "
                            + uri.getLastPathSegment() + " - this source is not being read"
                            + " sequentially; degrading to the caller's retriever.");
                    degraded = true;
                    releaseDecoder();
                    return null;
                }
                releaseDecoder();
            }
            lastRequestUs = sourceUs;

            if (!started && !open()) { degraded = true; return null; }

            int step = subsampleFor(maxOutW, maxOutH);
            // Already past the request: the frame in hand IS the first one at/after it, unless
            // it was converted coarser than this request needs.
            if (current != null && !current.isRecycled() && currentPtsUs >= sourceUs
                    && currentStep <= step) {
                reuseHits++;
                return current;
            }
            if (streamEnded) {
                // Nothing further will decode; the last frame is the best answer there is.
                return (current != null && !current.isRecycled()) ? current : null;
            }
            return decodeForwardTo(sourceUs, step);
        } catch (Exception e) {
            FLog.w(TAG, "Sequential read failed for " + uri + "; degrading to retriever", e);
            degraded = true;
            releaseDecoder();
            return null;
        }
    }

    private int subsampleFor(int maxOutW, int maxOutH) {
        if (maxOutW <= 0 || maxOutH <= 0 || current == null) {
            // No hint, or nothing decoded yet to measure against - full resolution.
            return 1;
        }
        int srcW = current.getWidth() * Math.max(1, currentStep);
        int srcH = current.getHeight() * Math.max(1, currentStep);
        int step = Math.min(srcW / Math.max(1, maxOutW), srcH / Math.max(1, maxOutH));
        return Math.max(1, Math.min(MAX_SUBSAMPLE, step));
    }

    /** Opens extractor+codec at the head of the source. Returns false if it cannot. */
    private boolean open() {
        MediaExtractor ex = new MediaExtractor();
        try {
            ex.setDataSource(context, uri, null);
            int track = -1;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                String m = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
                if (m != null && m.startsWith("video/")) { track = i; break; }
            }
            if (track < 0) {
                FLog.w(TAG, "No video track in " + uri);
                try { ex.release(); } catch (Exception ignored) { }
                return false;
            }
            ex.selectTrack(track);
            MediaFormat format = ex.getTrackFormat(track);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime == null) { try { ex.release(); } catch (Exception ignored) { } return false; }
            rotationDeg = format.containsKey(MediaFormat.KEY_ROTATION)
                    ? format.getInteger(MediaFormat.KEY_ROTATION) : 0;
            // Flexible YUV so getOutputImage() yields YUV_420_888 on every device.
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
            MediaCodec c = MediaCodec.createDecoderByType(mime);
            c.configure(format, null /* ByteBuffer mode */, null, 0);
            c.start();
            extractor = ex;
            codec = c;
            started = true;
            inputDone = false;
            streamEnded = false;
            return true;
        } catch (Exception e) {
            FLog.w(TAG, "Could not open " + uri + " for sequential reading", e);
            try { ex.release(); } catch (Exception ignored) { }
            return false;
        }
    }

    /**
     * Pumps the decoder until a frame at/after {@code targetUs} appears, converts it, and
     * makes it {@link #current}. Only the MATCHING frame is converted - the ones passed on the
     * way are released without ever becoming bitmaps, which is what makes the linear pass
     * cheap.
     */
    @Nullable
    private Bitmap decodeForwardTo(long targetUs, int step) {
        MediaCodec c = codec;
        MediaExtractor ex = extractor;
        if (c == null || ex == null) return null;
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        int stalledDrains = 0;

        while (true) {
            if (!inputDone) {
                int inIdx = c.dequeueInputBuffer(DEQUEUE_TIMEOUT_US);
                if (inIdx >= 0) {
                    ByteBuffer ib = c.getInputBuffer(inIdx);
                    int size = (ib == null) ? -1 : ex.readSampleData(ib, 0);
                    if (size < 0) {
                        c.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        inputDone = true;
                    } else {
                        c.queueInputBuffer(inIdx, 0, size, ex.getSampleTime(), 0);
                        ex.advance();
                    }
                }
            }

            int outIdx = c.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US);
            if (outIdx >= 0) {
                boolean eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                boolean isFrame = info.size > 0
                        && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0;
                if (isFrame) decodedFrames++;
                Bitmap made = null;
                if (isFrame && info.presentationTimeUs >= targetUs) {
                    try {
                        Image img = c.getOutputImage(outIdx);
                        if (img != null) {
                            made = imageToBitmap(img, step);
                            img.close();
                        }
                    } catch (Exception e) {
                        FLog.w(TAG, "getOutputImage failed", e);
                    }
                }
                c.releaseOutputBuffer(outIdx, false);
                stalledDrains = 0;
                if (made != null) {
                    convertedFrames++;
                    if (current != null && !current.isRecycled()) current.recycle();
                    current = made;
                    currentPtsUs = info.presentationTimeUs;
                    currentStep = step;
                    return current;
                }
                if (eos) {
                    streamEnded = true;
                    return (current != null && !current.isRecycled()) ? current : null;
                }
            } else if (outIdx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (inputDone && ++stalledDrains > MAX_STALLED_DRAINS) {
                    FLog.w(TAG, "Decoder stalled after EOS input for " + uri.getLastPathSegment());
                    streamEnded = true;
                    return (current != null && !current.isRecycled()) ? current : null;
                }
            }
            // INFO_OUTPUT_FORMAT_CHANGED / INFO_OUTPUT_BUFFERS_CHANGED: loop again.
        }
    }

    /**
     * YUV_420_888 to ARGB, sampling every {@code step}-th pixel and applying the source
     * rotation, with no JPEG round trip.
     *
     * <p>{@code FilmstripSweepExtractor} converts through {@code YuvImage.compressToJpeg} plus
     * {@code BitmapFactory.decodeByteArray}, which is fine for the handful of frames a
     * filmstrip needs but costs tens of milliseconds apiece - thirty times a second that is
     * back to being the bottleneck. This walks the planes directly instead, and at
     * {@code step > 1} it never even reads the pixels it is going to drop.
     */
    @Nullable
    private Bitmap imageToBitmap(@NonNull Image image, int step) {
        int w = image.getWidth();
        int h = image.getHeight();
        if (w <= 0 || h <= 0) return null;
        int s = Math.max(1, step);
        int outW = Math.max(1, w / s);
        int outH = Math.max(1, h / s);

        Image.Plane[] planes = image.getPlanes();
        ByteBuffer yBuf = planes[0].getBuffer();
        ByteBuffer uBuf = planes[1].getBuffer();
        ByteBuffer vBuf = planes[2].getBuffer();
        int yRow = planes[0].getRowStride();
        int yPix = planes[0].getPixelStride();
        int uvRow = planes[1].getRowStride();
        int uvPix = planes[1].getPixelStride();

        int[] argb = new int[outW * outH];
        int p = 0;
        for (int oy = 0; oy < outH; oy++) {
            int sy = oy * s;
            int yLine = sy * yRow;
            int uvLine = (sy >> 1) * uvRow;
            for (int ox = 0; ox < outW; ox++) {
                int sx = ox * s;
                int Y = (yBuf.get(yLine + sx * yPix) & 0xFF) - 16;
                if (Y < 0) Y = 0;
                int uvIdx = uvLine + (sx >> 1) * uvPix;
                int U = (uBuf.get(uvIdx) & 0xFF) - 128;
                int V = (vBuf.get(uvIdx) & 0xFF) - 128;
                // BT.601 limited-range, the same matrix YuvImage's JPEG path applies.
                int y1192 = 1192 * Y;
                int r = (y1192 + 1634 * V) >> 10;
                int g = (y1192 - 833 * V - 400 * U) >> 10;
                int b = (y1192 + 2066 * U) >> 10;
                if (r < 0) r = 0; else if (r > 255) r = 255;
                if (g < 0) g = 0; else if (g > 255) g = 255;
                if (b < 0) b = 0; else if (b > 255) b = 255;
                argb[p++] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
        }
        Bitmap bmp = Bitmap.createBitmap(argb, outW, outH, Bitmap.Config.ARGB_8888);
        if (rotationDeg % 360 != 0) {
            android.graphics.Matrix m = new android.graphics.Matrix();
            m.postRotate(rotationDeg);
            Bitmap rotated = Bitmap.createBitmap(bmp, 0, 0, outW, outH, m, true);
            if (rotated != bmp) bmp.recycle();
            return rotated;
        }
        return bmp;
    }

    private void releaseDecoder() {
        if (codec != null) {
            try { codec.stop(); } catch (Exception ignored) { }
            try { codec.release(); } catch (Exception ignored) { }
            codec = null;
        }
        if (extractor != null) {
            try { extractor.release(); } catch (Exception ignored) { }
            extractor = null;
        }
        if (current != null && !current.isRecycled()) current.recycle();
        current = null;
        currentPtsUs = Long.MIN_VALUE;
        currentStep = 0;
        started = false;
        inputDone = false;
        streamEnded = false;
    }

    void release() {
        if (decodedFrames + convertedFrames + reuseHits > 0) {
            FLog.i(TAG, "SEQ_FRAMES " + uri.getLastPathSegment()
                    + " decoded=" + decodedFrames
                    + " converted=" + convertedFrames
                    + " reused=" + reuseHits
                    + " rewinds=" + rewinds
                    + (degraded ? " DEGRADED" : ""));
        }
        releaseDecoder();
    }
}
