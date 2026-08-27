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
     * How far into a source the first request must land before it is worth seeking to reach it.
     * Below this the linear decode is already about to arrive and a seek only risks landing
     * somewhere unhelpful.
     */
    private static final long SEEK_WORTH_IT_US = 1_500_000L;
    /**
     * Floor on the converted size, as a fraction of the source. A PiP scaled far down does not
     * need full-resolution pixels, but past this the nearest-neighbour sampling starts to show
     * on a slow zoom, so the saving stops here.
     */
    private static final float MIN_CONVERT_FRACTION = 0.25f;

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
    /** The width {@link #current} was converted at, so a request needing MORE detail re-decodes. */
    private int currentOutW = 0;
    private long lastRequestUs = Long.MIN_VALUE;

    private int decodedFrames = 0;
    private int convertedFrames = 0;
    private int reuseHits = 0;
    private int rewinds = 0;
    /** Where the linear pass actually spends itself: pumping the codec vs converting pixels. */
    private long pumpNanos = 0L;
    private long convertNanos = 0L;
    /** Reused across frames by {@link #imageToBitmap}; see its note on why this matters. */
    @Nullable private byte[] yArr;
    @Nullable private byte[] uArr;
    @Nullable private byte[] vArr;
    @Nullable private int[] argbArr;
    @Nullable private Bitmap scratch;
    /** Source geometry, learned in {@link #open}. Raw = as coded; display = after rotation. */
    private int sourceW = 0, sourceH = 0, displayW = 0, displayH = 0;
    /** Width the last conversion produced BEFORE rotation, which is what wantW is measured in. */
    private int lastConvertOutW = 0;
    /** Whether the one permitted forward seek (to the first frame actually wanted) has run. */
    private boolean triedInitialSeek = false;
    /** Cached container sniff; null until asked. See {@link #isFragmented}. */
    @Nullable private Boolean fragmented;

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

            int wantW = wantWidthFor(maxOutW, maxOutH);
            // Already past the request: the frame in hand IS the first one at/after it, unless
            // it was converted coarser than this request needs.
            if (current != null && !current.isRecycled() && currentPtsUs >= sourceUs
                    && currentOutW >= wantW) {
                reuseHits++;
                return current;
            }
            if (streamEnded) {
                // Nothing further will decode; the last frame is the best answer there is.
                return (current != null && !current.isRecycled()) ? current : null;
            }
            return decodeForwardTo(sourceUs, wantW);
        } catch (Exception e) {
            FLog.w(TAG, "Sequential read failed for " + uri + "; degrading to retriever", e);
            degraded = true;
            releaseDecoder();
            return null;
        }
    }

    /**
     * The width to convert at, from the caller's ceiling on how large the frame will be DRAWN.
     * Zero means "as wide as the source" and is what the first frame of a clip gets, before
     * there is a decoded frame to measure the source against.
     */
    private int wantWidthFor(int maxOutW, int maxOutH) {
        if (maxOutW <= 0 || maxOutH <= 0 || displayW <= 0 || displayH <= 0) return 0;
        // The frame is drawn FIT inside the caller's box, so the limiting axis decides. The box
        // is in canvas space, hence display (post-rotation) dimensions here and the RAW width
        // in the answer, which is the space the conversion loop works in.
        float f = Math.min(maxOutW / (float) displayW, maxOutH / (float) displayH);
        if (f >= 1f) return sourceW;                     // drawn at or above 1:1 - no saving
        if (f < MIN_CONVERT_FRACTION) f = MIN_CONVERT_FRACTION;
        return Math.max(1, Math.round(sourceW * f));
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
            sourceW = format.containsKey(MediaFormat.KEY_WIDTH)
                    ? format.getInteger(MediaFormat.KEY_WIDTH) : 0;
            sourceH = format.containsKey(MediaFormat.KEY_HEIGHT)
                    ? format.getInteger(MediaFormat.KEY_HEIGHT) : 0;
            boolean quarterTurn = ((rotationDeg % 360) + 360) % 360 % 180 == 90;
            displayW = quarterTurn ? sourceH : sourceW;
            displayH = quarterTurn ? sourceW : sourceH;
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
    private Bitmap decodeForwardTo(long targetUs, int wantW) {
        MediaCodec c = codec;
        MediaExtractor ex = extractor;
        if (c == null || ex == null) return null;
        // ONE FORWARD SEEK, TO WHERE THE WORK ACTUALLY STARTS. A reader is built per clip
        // ITEM, and an item late in the timeline still opened its source at frame zero and
        // decoded everything before its own window to get there. Measured on the Note 9: the
        // reader serving the black-spacer item decoded 764 frames to hand out 323.
        //
        // Only on the first decode, only forwards, and only when the container can be trusted
        // to seek. FadCam's own recordings are fragmented MP4 without sidx, where seekTo()
        // SCANS the file - an uninterruptible call measured at >45s for ONE seek on a
        // 46-minute source. That is why the sniff below runs BEFORE the seek and not as a
        // recovery afterwards; FilmstripSweepExtractor learned this the same way.
        if (!triedInitialSeek) {
            triedInitialSeek = true;
            if (targetUs > SEEK_WORTH_IT_US && !isFragmented()) {
                try {
                    ex.seekTo(targetUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
                    long landed = ex.getSampleTime();
                    if (landed < 0 || landed > targetUs) {
                        // Landed past what we want, or nowhere: rewind by rebuilding, since
                        // seeking back is exactly the call that is not trustworthy here.
                        FLog.d(TAG, "Initial seek landed at " + landed + " for target "
                                + targetUs + "; decoding from the start instead");
                        releaseDecoder();
                        triedInitialSeek = true;
                        if (!open()) return null;
                        ex = extractor;
                        c = codec;
                        if (ex == null || c == null) return null;
                    }
                } catch (Exception e) {
                    FLog.w(TAG, "Initial seek failed; decoding from the start", e);
                }
            }
        }

        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        int stalledDrains = 0;
        final long pumpStart = System.nanoTime();

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
                            long t0 = System.nanoTime();
                            made = imageToBitmap(img, wantW);
                            convertNanos += System.nanoTime() - t0;
                            img.close();
                        }
                    } catch (Exception e) {
                        FLog.w(TAG, "getOutputImage failed", e);
                    }
                }
                c.releaseOutputBuffer(outIdx, false);
                stalledDrains = 0;
                if (made != null) {
                    pumpNanos += System.nanoTime() - pumpStart;
                    convertedFrames++;
                    // `made` may BE the reused scratch bitmap, so identity-check before
                    // recycling — recycling it here would destroy the frame being returned.
                    if (current != null && current != made && !current.isRecycled()) {
                        current.recycle();
                    }
                    current = made;
                    currentPtsUs = info.presentationTimeUs;
                    currentOutW = lastConvertOutW;
                    return current;
                }
                if (eos) {
                    pumpNanos += System.nanoTime() - pumpStart;
                    streamEnded = true;
                    return (current != null && !current.isRecycled()) ? current : null;
                }
            } else if (outIdx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (inputDone && ++stalledDrains > MAX_STALLED_DRAINS) {
                    FLog.w(TAG, "Decoder stalled after EOS input for " + uri.getLastPathSegment());
                    pumpNanos += System.nanoTime() - pumpStart;
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
     *
     * <p><b>The planes are bulk-copied into byte arrays first, and this is the whole trick.</b>
     * The first version of this method read the decoder's direct ByteBuffers a pixel at a time
     * with {@code get(index)}. Measured on the Note 9 (2026-08-27) that cost 216ms per
     * 1080p frame and was 93% of the reader's entire runtime — it had simply replaced the
     * retriever as the bottleneck. Each of those calls is a bounds-checked read into off-heap
     * memory that the JIT will not fold into the loop, and there were six million of them per
     * frame. Three bulk {@code get(byte[])} copies cost one memcpy each, after which the same
     * arithmetic runs on plain Java arrays the JIT can actually optimise.
     *
     * <p>The output bitmap and both working arrays are reused between frames, so a long PiP
     * allocates once rather than thirty times a second.
     */
    @Nullable
    private Bitmap imageToBitmap(@NonNull Image image, int wantW) {
        int w = image.getWidth();
        int h = image.getHeight();
        if (w <= 0 || h <= 0) return null;
        // Sample straight to the size this frame will be DRAWN at. An earlier version stepped
        // by an integer factor, which on the common case - a 1080-wide source drawn into a
        // 720-wide export - rounded down to a factor of ONE and converted every pixel of a
        // frame that was about to be shrunk by more than half. Fixed-point stepping has no
        // such cliff: the loop touches exactly as many pixels as the destination has.
        int outW = (wantW > 0 && wantW < w) ? wantW : w;
        int outH = Math.max(1, Math.round(h * (outW / (float) w)));
        lastConvertOutW = outW;
        final int xStep = (int) (((long) w << 16) / outW);
        final int yStep = (int) (((long) h << 16) / outH);

        Image.Plane[] planes = image.getPlanes();
        ByteBuffer yBuf = planes[0].getBuffer();
        ByteBuffer uBuf = planes[1].getBuffer();
        ByteBuffer vBuf = planes[2].getBuffer();
        int yRow = planes[0].getRowStride();
        int yPix = planes[0].getPixelStride();
        int uvRow = planes[1].getRowStride();
        int uvPix = planes[1].getPixelStride();

        int yLen = yBuf.remaining();
        int uLen = uBuf.remaining();
        int vLen = vBuf.remaining();
        if (yArr == null || yArr.length < yLen) yArr = new byte[yLen];
        if (uArr == null || uArr.length < uLen) uArr = new byte[uLen];
        if (vArr == null || vArr.length < vLen) vArr = new byte[vLen];
        yBuf.get(yArr, 0, yLen);
        uBuf.get(uArr, 0, uLen);
        vBuf.get(vArr, 0, vLen);
        final byte[] yA = yArr, uA = uArr, vA = vArr;

        int pixels = outW * outH;
        if (argbArr == null || argbArr.length < pixels) argbArr = new int[pixels];
        final int[] argb = argbArr;

        int p = 0;
        for (int oy = 0; oy < outH; oy++) {
            int sy = (oy * yStep) >> 16;
            if (sy >= h) sy = h - 1;
            int yLine = sy * yRow;
            int uvLine = (sy >> 1) * uvRow;
            int sxFixed = 0;
            for (int ox = 0; ox < outW; ox++, sxFixed += xStep) {
                int sx = sxFixed >> 16;
                if (sx >= w) sx = w - 1;
                int yIdx = yLine + sx * yPix;
                int uvIdx = uvLine + (sx >> 1) * uvPix;
                if (yIdx >= yLen || uvIdx >= uLen || uvIdx >= vLen) { argb[p++] = 0xFF000000; continue; }
                int Y = (yA[yIdx] & 0xFF) - 16;
                if (Y < 0) Y = 0;
                int U = (uA[uvIdx] & 0xFF) - 128;
                int V = (vA[uvIdx] & 0xFF) - 128;
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

        if (scratch == null || scratch.isRecycled()
                || scratch.getWidth() != outW || scratch.getHeight() != outH) {
            if (scratch != null && !scratch.isRecycled()) scratch.recycle();
            scratch = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888);
        }
        scratch.setPixels(argb, 0, outW, 0, 0, outW, outH);
        if (rotationDeg % 360 != 0) {
            android.graphics.Matrix m = new android.graphics.Matrix();
            m.postRotate(rotationDeg);
            // A fresh bitmap, because the rotation changes the dimensions; the caller
            // identity-checks before recycling so the scratch survives.
            return Bitmap.createBitmap(scratch, 0, 0, outW, outH, m, true);
        }
        return scratch;
    }

    /**
     * True if this source is a fragmented MP4 - i.e. a {@code moof} box appears in its first
     * 64KB. Mirrors {@code FilmstripSweepExtractor.isFragmentedMp4}, and fails CLOSED for the
     * same reason: any read error answers "fragmented", which costs a linear decode, whereas
     * guessing "seekable" wrongly costs an uninterruptible multi-minute stall.
     */
    private boolean isFragmented() {
        if (fragmented != null) return fragmented;
        boolean result = true;
        try (java.io.InputStream in = context.getContentResolver().openInputStream(uri)) {
            if (in != null) {
                byte[] buf = new byte[65536];
                int n = 0;
                while (n < buf.length) {
                    int r = in.read(buf, n, buf.length - n);
                    if (r < 0) break;
                    n += r;
                }
                result = false;
                for (int i = 0; i + 4 <= n; i++) {
                    if (buf[i] == 'm' && buf[i + 1] == 'o' && buf[i + 2] == 'o'
                            && buf[i + 3] == 'f') {
                        result = true;
                        break;
                    }
                }
            }
        } catch (Exception e) {
            FLog.w(TAG, "Could not sniff container for " + uri + "; assuming fragmented", e);
        }
        fragmented = result;
        return result;
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
        // `current` is often the scratch itself, so recycle by identity, once each.
        if (current != null && current != scratch && !current.isRecycled()) current.recycle();
        if (scratch != null && !scratch.isRecycled()) scratch.recycle();
        current = null;
        scratch = null;
        currentPtsUs = Long.MIN_VALUE;
        currentOutW = 0;
        lastConvertOutW = 0;
        triedInitialSeek = false;
        sourceW = sourceH = displayW = displayH = 0;
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
                    + " pumpMs=" + (pumpNanos / 1_000_000L)
                    + " convertMs=" + (convertNanos / 1_000_000L)
                    + (degraded ? " DEGRADED" : ""));
        }
        releaseDecoder();
    }
}
