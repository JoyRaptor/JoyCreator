package com.fadcam.ui.faditor.timeline;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.FLog;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Accurate filmstrip frame extraction via ONE sequential MediaCodec decode sweep per source.
 *
 * <p>Rationale (road_map T1, FEEDBACK_20260703_timeline_fidelity.md): the old
 * {@code MediaMetadataRetriever.getFrameAtTime(..., OPTION_CLOSEST_SYNC)} path keyframe-snaps every
 * thumbnail. On long-GOP sources (screen recordings have multi-second GOPs) that means filmstrip
 * frames land far from their labelled time and consecutive tiles duplicate the same keyframe. This
 * class instead decodes forward from the start and, for each requested target timestamp, grabs the
 * FIRST decoded frame whose PTS &gt;= that target — the exact frame the user expects, never snapped.
 *
 * <p>Design constraints honoured:
 * <ul>
 *   <li>One {@link MediaExtractor}+{@link MediaCodec} pass per job. Never seeks per frame (fMP4
 *       FadCam recordings are not reliably seekable) — decodes forward from position 0.</li>
 *   <li>Holds at most a couple of decoded frames at once; releases codec/extractor in a finally.</li>
 *   <li>Returns {@code null} on ANY failure so the caller can fall back to the MMR path. Never
 *       throws to the caller, never crashes.</li>
 * </ul>
 */
final class FilmstripSweepExtractor {

    private static final String TAG = "FilmstripSweep";
    private static final long DEQUEUE_TIMEOUT_US = 10_000L;
    // Safety valve: bail if the decoder stalls (produces no output) for this many consecutive
    // drain attempts after input is exhausted, so a malformed stream can't hang the sweep forever.
    private static final int MAX_STALLED_DRAINS = 2000;

    private FilmstripSweepExtractor() {}

    /**
     * Sweeps {@code uri} once, returning one center-cropped square thumbnail (scaled to
     * {@code thumbSize}) per entry in {@code targetsUs}, in the same order.
     *
     * @param targetsUs target presentation timestamps in microseconds, ASCENDING. For each, the
     *                  first decoded frame with {@code pts >= target} is used.
     * @return a list of exactly {@code targetsUs.length} bitmaps on success, or {@code null} on any
     *         failure (unsupported codec, decode error, no decodable frames). The caller owns the
     *         returned bitmaps.
     */
    @Nullable
    static List<Bitmap> sweep(@NonNull Context ctx, @NonNull Uri uri,
                              @NonNull long[] targetsUs, int thumbSize) {
        if (targetsUs.length == 0) return null;

        MediaExtractor extractor = new MediaExtractor();
        MediaCodec codec = null;
        Bitmap lastFull = null; // most recent matched full-res frame, kept for tail-fill on early EOS
        List<Bitmap> out = new ArrayList<>(targetsUs.length);
        try {
            extractor.setDataSource(ctx, uri, null);
            int trackIndex = selectVideoTrack(extractor);
            if (trackIndex < 0) {
                FLog.w(TAG, "No video track in " + uri);
                return null;
            }
            extractor.selectTrack(trackIndex);
            MediaFormat format = extractor.getTrackFormat(trackIndex);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime == null) return null;

            int rotationDeg = 0;
            if (format.containsKey(MediaFormat.KEY_ROTATION)) {
                rotationDeg = format.getInteger(MediaFormat.KEY_ROTATION);
            }

            // Request a flexible YUV output so getOutputImage() yields YUV_420_888 on any device.
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);

            codec = MediaCodec.createDecoderByType(mime);
            codec.configure(format, null /* ByteBuffer mode — no output Surface */, null, 0);
            codec.start();

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false;
            int targetIdx = 0;
            int stalledDrains = 0;

            while (targetIdx < targetsUs.length) {
                if (!inputDone) {
                    int inIdx = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US);
                    if (inIdx >= 0) {
                        ByteBuffer ib = codec.getInputBuffer(inIdx);
                        int sampleSize = (ib == null) ? -1 : extractor.readSampleData(ib, 0);
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            long pts = extractor.getSampleTime();
                            codec.queueInputBuffer(inIdx, 0, sampleSize, pts, 0);
                            extractor.advance();
                        }
                    }
                }

                int outIdx = codec.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US);
                if (outIdx >= 0) {
                    boolean eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    boolean matched = false;
                    if (info.size > 0
                            && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                            && targetIdx < targetsUs.length
                            && info.presentationTimeUs >= targetsUs[targetIdx]) {
                        Bitmap full = null;
                        try {
                            Image img = codec.getOutputImage(outIdx);
                            if (img != null) {
                                full = imageToBitmap(img, rotationDeg);
                                img.close();
                            }
                        } catch (Exception e) {
                            FLog.w(TAG, "getOutputImage failed", e);
                        }
                        if (full != null) {
                            matched = true;
                            // One (possibly several) target(s) resolve to this frame.
                            while (targetIdx < targetsUs.length
                                    && info.presentationTimeUs >= targetsUs[targetIdx]) {
                                out.add(EditorTimelineView.centerCropSquare(full, thumbSize));
                                targetIdx++;
                            }
                            if (lastFull != null && !lastFull.isRecycled()) lastFull.recycle();
                            lastFull = full; // retain for possible tail-fill; recycled in finally
                        }
                    }
                    codec.releaseOutputBuffer(outIdx, false);
                    stalledDrains = 0;
                    if (matched) { /* progress made */ }
                    if (eos) break;
                } else if (outIdx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (inputDone && ++stalledDrains > MAX_STALLED_DRAINS) {
                        FLog.w(TAG, "Decoder stalled after EOS input; bailing");
                        break;
                    }
                }
                // INFO_OUTPUT_FORMAT_CHANGED / INFO_OUTPUT_BUFFERS_CHANGED: just loop again.
            }

            // If input/stream ended before every target was reached, fill the tail with the last
            // frame we did decode so no filmstrip tile is left blank (better than a hole; the
            // caller's disk cache still expects a full-length list).
            if (targetIdx < targetsUs.length && lastFull != null && !lastFull.isRecycled()) {
                while (targetIdx < targetsUs.length) {
                    out.add(EditorTimelineView.centerCropSquare(lastFull, thumbSize));
                    targetIdx++;
                }
            }

            if (out.size() != targetsUs.length) {
                // Could not produce a full strip — recycle partial output and signal fallback.
                for (Bitmap b : out) if (b != null && !b.isRecycled()) b.recycle();
                return null;
            }
            return out;
        } catch (Exception e) {
            FLog.w(TAG, "Sweep failed for " + uri, e);
            for (Bitmap b : out) if (b != null && !b.isRecycled()) b.recycle();
            return null;
        } finally {
            if (lastFull != null && !lastFull.isRecycled()) lastFull.recycle();
            if (codec != null) {
                try { codec.stop(); } catch (Exception ignored) {}
                try { codec.release(); } catch (Exception ignored) {}
            }
            try { extractor.release(); } catch (Exception ignored) {}
        }
    }

    private static int selectVideoTrack(@NonNull MediaExtractor extractor) {
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            MediaFormat f = extractor.getTrackFormat(i);
            String mime = f.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("video/")) return i;
        }
        return -1;
    }

    /**
     * Converts a decoded {@link Image} (YUV_420_888) to an ARGB {@link Bitmap}, applying the
     * source rotation. Uses NV21 + {@link YuvImage} JPEG as a portable, GL-free color conversion —
     * fine here since we only convert a handful of frames per source and downscale afterwards.
     */
    @Nullable
    private static Bitmap imageToBitmap(@NonNull Image image, int rotationDeg) {
        int w = image.getWidth();
        int h = image.getHeight();
        if (w <= 0 || h <= 0) return null;
        byte[] nv21 = yuv420ToNv21(image, w, h);
        YuvImage yuv = new YuvImage(nv21, ImageFormat.NV21, w, h, null);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        yuv.compressToJpeg(new Rect(0, 0, w, h), 90, baos);
        byte[] jpeg = baos.toByteArray();
        Bitmap bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
        if (bmp == null) return null;
        if (rotationDeg % 360 != 0) {
            Matrix m = new Matrix();
            m.postRotate(rotationDeg);
            Bitmap rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
            if (rotated != bmp) bmp.recycle();
            return rotated;
        }
        return bmp;
    }

    /**
     * Packs a YUV_420_888 {@link Image} into an NV21 byte array (Y plane followed by interleaved
     * V/U), honouring each plane's row/pixel stride so cropped or padded decoder buffers convert
     * correctly.
     */
    @NonNull
    private static byte[] yuv420ToNv21(@NonNull Image image, int width, int height) {
        int ySize = width * height;
        byte[] nv21 = new byte[ySize + ySize / 2];
        Image.Plane[] planes = image.getPlanes();

        // Luma plane.
        ByteBuffer yBuf = planes[0].getBuffer();
        int yRowStride = planes[0].getRowStride();
        int yPixStride = planes[0].getPixelStride();
        int pos = 0;
        if (yPixStride == 1 && yRowStride == width) {
            yBuf.get(nv21, 0, ySize);
            pos = ySize;
        } else {
            for (int row = 0; row < height; row++) {
                int rowStart = row * yRowStride;
                for (int col = 0; col < width; col++) {
                    nv21[pos++] = yBuf.get(rowStart + col * yPixStride);
                }
            }
        }

        // Chroma planes → NV21 wants V then U interleaved.
        ByteBuffer uBuf = planes[1].getBuffer();
        ByteBuffer vBuf = planes[2].getBuffer();
        int uvRowStride = planes[1].getRowStride();
        int uvPixStride = planes[1].getPixelStride();
        int cw = width / 2;
        int ch = height / 2;
        for (int row = 0; row < ch; row++) {
            int rowStart = row * uvRowStride;
            for (int col = 0; col < cw; col++) {
                int idx = rowStart + col * uvPixStride;
                nv21[pos++] = vBuf.get(idx);
                nv21[pos++] = uBuf.get(idx);
            }
        }
        return nv21;
    }
}
