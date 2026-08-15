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
    /**
     * Wall-clock ceiling for the whole seek probe in {@link #sweepBySeek}. Sized so that a source
     * with a real index (standard MP4: imported assets, Faditor exports, and — once the fMP4 index
     * lands — FadCam recordings) finishes its 60 probe seeks comfortably, while an indexless
     * fragmented source is rejected in about a second instead of grinding for minutes.
     */
    private static final long PROBE_BUDGET_NS = 1_500_000_000L; // 1.5s

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
        // Try the cheap path first. It returns null (having consumed only seeks, no decoding)
        // whenever the source cannot be seeked reliably, which is the case the linear sweep
        // below exists for. See sweepBySeek for why this ordering is safe.
        List<Bitmap> seeked = sweepBySeek(ctx, uri, targetsUs, thumbSize);
        if (seeked != null) return seeked;
        return sweepLinear(ctx, uri, targetsUs, thumbSize);
    }

    /**
     * Original strategy: ONE forward decode from position 0, grabbing frames as the targets go by.
     * Correct on any source, including ones that cannot seek — but it decodes the WHOLE file,
     * because the last target sits near the end of it.
     *
     * <p>Measured cost on the Note 20 (2026-08-15), 46-minute source, 60 targets: two of these
     * running concurrently held ~100% CPU each for 19+ minutes without finishing, on a PAUSED
     * editor. That was the ~200% idle CPU JoyRaptor reported as his phone getting hot. It is now the
     * FALLBACK, used only when {@link #sweepBySeek} reports the source is not seekable.
     */
    @Nullable
    private static List<Bitmap> sweepLinear(@NonNull Context ctx, @NonNull Uri uri,
                                            @NonNull long[] targetsUs, int thumbSize) {
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

    /**
     * Fast strategy: for each target, seek to the sync sample at/before it and decode forward only
     * as far as the target. Decodes ~one GOP per target instead of the entire file.
     *
     * <p><b>Accuracy is unchanged.</b> Both paths return "the first decoded frame whose pts &gt;=
     * target". This one simply reaches that frame from the nearest keyframe instead of from
     * position 0. The frames are the same frames — this is not a return to the
     * {@code OPTION_CLOSEST_SYNC} keyframe-snapping that {@link #sweepLinear} was written to
     * replace, which showed the keyframe INSTEAD of the requested frame.
     *
     * <p><b>Why the class originally refused to seek.</b> FadCam records fragmented MP4 without
     * sidx boxes, and such files do not seek reliably. Rather than assume, this PROBES: it seeks
     * to every target and inspects where the extractor actually landed, before decoding anything.
     * Seeking without decoding is cheap, so a source that fails the probe has cost us almost
     * nothing and falls back to the linear sweep with identical output.
     *
     * <p>The probe rejects three ways a seek can betray us:
     * <ul>
     *   <li>no sample at the seek position at all ({@code getSampleTime() < 0});</li>
     *   <li>the extractor landed AFTER the target — not a "previous sync" at all, so decoding
     *       forward from there would skip the frame we want;</li>
     *   <li>the seeks silently collapse toward the start. This is the dangerous one: if every
     *       seek lands at ~0 (no index, or a single keyframe at the head), then "decode one GOP
     *       per target" quietly becomes "decode from 0, sixty times" — sixty times WORSE than the
     *       linear sweep. Requiring the last target's sync point to be genuinely late in the file
     *       catches that before any decoding happens.</li>
     * </ul>
     *
     * @return a full-length strip, or {@code null} if this source should use the linear sweep.
     */
    @Nullable
    private static List<Bitmap> sweepBySeek(@NonNull Context ctx, @NonNull Uri uri,
                                            @NonNull long[] targetsUs, int thumbSize) {
        // ── Container gate: never call seekTo() on a fragmented MP4 ──────────────────────
        // A fragmented MP4 written without sidx boxes has no index to seek by, so the platform
        // extractor falls back to SCANNING the file for the sync sample — an uninterruptible
        // call that, on a 46-minute recording, does not return in any useful time (measured: >45s
        // for ONE seek, thread at 98%). No timeout can rescue us once that call starts, so the
        // decision has to be made BEFORE it. This read is a sequential 64KB sniff for a 'moof'
        // box — the same definitive marker FragmentedMp4Remuxer.needsRemux uses.
        if (isFragmentedMp4(ctx, uri)) {
            FLog.d(TAG, "Fragmented MP4 (no seek index) — linear sweep: "
                    + uri.getLastPathSegment());
            return null;
        }
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec codec = null;
        List<Bitmap> out = new ArrayList<>(targetsUs.length);
        try {
            extractor.setDataSource(ctx, uri, null);
            int trackIndex = selectVideoTrack(extractor);
            if (trackIndex < 0) return null;
            extractor.selectTrack(trackIndex);

            // ── Probe (no decoding), on a WALL-CLOCK BUDGET ──────────────────────────────
            // The budget catches sources whose seeks are merely SLOW. It cannot catch sources
            // whose seeks effectively never return, because the deadline is only tested after
            // seekTo() hands control back — and seekTo() is uninterruptible. Device-proven on the
            // Note 20 (2026-08-15): against a 46-minute fragmented FadCam recording, a probe of
            // the single final target had still not returned after 45 seconds, with this thread
            // pegged at 98%. That is why the container gate above runs FIRST and is not optional:
            // it is the only check that happens before we are committed to a blocking call.
            final long probeDeadlineNs = System.nanoTime() + PROBE_BUDGET_NS;
            final int lastIdx = targetsUs.length - 1;
            long[] syncUs = new long[targetsUs.length];

            extractor.seekTo(targetsUs[lastIdx], MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            long lastLanded = extractor.getSampleTime();
            if (System.nanoTime() > probeDeadlineNs) {
                FLog.d(TAG, "Seek probe exceeded its budget on the final target — this source has"
                        + " no usable index; linear sweep: " + uri.getLastPathSegment());
                return null;
            }
            if (lastLanded < 0 || lastLanded > targetsUs[lastIdx]) {
                FLog.d(TAG, "Not seekable (final target landed at " + lastLanded
                        + "), using linear sweep: " + uri.getLastPathSegment());
                return null;
            }
            // Seeks that silently collapse toward the start would turn "one GOP per target" into
            // "decode from 0, sixty times" — sixty times WORSE than one linear pass.
            if (targetsUs[lastIdx] > 0 && lastLanded < targetsUs[lastIdx] / 2) {
                FLog.d(TAG, "Seeks collapse toward start (final target " + targetsUs[lastIdx]
                        + "us -> sync " + lastLanded + "us); linear sweep is cheaper: "
                        + uri.getLastPathSegment());
                return null;
            }
            syncUs[lastIdx] = lastLanded;
            for (int i = 0; i < lastIdx; i++) {
                extractor.seekTo(targetsUs[i], MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
                long landed = extractor.getSampleTime();
                if (landed < 0 || landed > targetsUs[i]) {
                    FLog.d(TAG, "Not seekable (target " + i + " landed at " + landed
                            + "), using linear sweep: " + uri.getLastPathSegment());
                    return null;
                }
                if (System.nanoTime() > probeDeadlineNs) {
                    FLog.d(TAG, "Seek probe exceeded its budget at target " + i
                            + "; linear sweep: " + uri.getLastPathSegment());
                    return null;
                }
                syncUs[i] = landed;
            }

            MediaFormat format = extractor.getTrackFormat(trackIndex);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime == null) return null;
            int rotationDeg = format.containsKey(MediaFormat.KEY_ROTATION)
                    ? format.getInteger(MediaFormat.KEY_ROTATION) : 0;
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
            codec = MediaCodec.createDecoderByType(mime);
            codec.configure(format, null, null, 0);
            codec.start();

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            int targetIdx = 0;
            int seeks = 0;
            while (targetIdx < targetsUs.length) {
                final long groupSync = syncUs[targetIdx];
                final int groupStart = targetIdx;
                // Consecutive targets sharing a sync sample share ONE seek + forward decode.
                extractor.seekTo(targetsUs[targetIdx], MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
                codec.flush();
                seeks++;
                boolean inputDone = false;
                int stalledDrains = 0;
                Bitmap groupFrame = null;

                // Decode forward until the targets sharing this sync point are all satisfied.
                while (targetIdx < targetsUs.length && syncUs[targetIdx] == groupSync) {
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
                                codec.queueInputBuffer(inIdx, 0, sampleSize,
                                        extractor.getSampleTime(), 0);
                                extractor.advance();
                            }
                        }
                    }
                    int outIdx = codec.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US);
                    if (outIdx >= 0) {
                        boolean eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                        if (info.size > 0
                                && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
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
                                while (targetIdx < targetsUs.length
                                        && syncUs[targetIdx] == groupSync
                                        && info.presentationTimeUs >= targetsUs[targetIdx]) {
                                    out.add(EditorTimelineView.centerCropSquare(full, thumbSize));
                                    targetIdx++;
                                }
                                if (groupFrame != null && !groupFrame.isRecycled()
                                        && groupFrame != full) {
                                    groupFrame.recycle();
                                }
                                groupFrame = full;
                            }
                        }
                        codec.releaseOutputBuffer(outIdx, false);
                        stalledDrains = 0;
                        if (eos) break;
                    } else if (outIdx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        if (inputDone && ++stalledDrains > MAX_STALLED_DRAINS) {
                            FLog.w(TAG, "Decoder stalled after EOS input; abandoning seek sweep");
                            break;
                        }
                    }
                }
                // centerCropSquare can hand back its argument, so never recycle a bitmap that
                // ended up in `out` — that would blank the tile it was produced for.
                if (groupFrame != null && !groupFrame.isRecycled() && !out.contains(groupFrame)) {
                    groupFrame.recycle();
                }
                if (targetIdx == groupStart) {
                    // No progress on this group — bail rather than re-seek the same spot forever.
                    FLog.w(TAG, "Seek sweep made no progress at target " + targetIdx
                            + "; falling back to linear");
                    for (Bitmap b : out) if (b != null && !b.isRecycled()) b.recycle();
                    return null;
                }
            }

            if (out.size() != targetsUs.length) {
                for (Bitmap b : out) if (b != null && !b.isRecycled()) b.recycle();
                return null;
            }
            FLog.d(TAG, "Seek sweep OK for " + uri.getLastPathSegment() + ": "
                    + out.size() + " tiles from " + seeks + " seeks (linear would have decoded"
                    + " the whole source)");
            return out;
        } catch (Exception e) {
            FLog.w(TAG, "Seek sweep failed for " + uri + "; falling back to linear", e);
            for (Bitmap b : out) if (b != null && !b.isRecycled()) b.recycle();
            return null;
        } finally {
            if (codec != null) {
                try { codec.stop(); } catch (Exception ignored) {}
                try { codec.release(); } catch (Exception ignored) {}
            }
            try { extractor.release(); } catch (Exception ignored) {}
        }
    }

    /**
     * True if {@code uri} is a fragmented MP4 — i.e. a {@code moof} box appears in its first 64KB.
     * Mirrors {@code FragmentedMp4Remuxer.needsRemux}, but reads through the ContentResolver so it
     * works for {@code content://} sources too, and answers for a Uri rather than a File.
     *
     * <p>Deliberately fails CLOSED: any read error returns true, sending the caller to the linear
     * sweep. The linear sweep is slow but correct on every source; guessing "seekable" wrongly
     * costs an uninterruptible multi-minute stall.
     */
    private static boolean isFragmentedMp4(@NonNull Context ctx, @NonNull Uri uri) {
        try (java.io.InputStream in = ctx.getContentResolver().openInputStream(uri)) {
            if (in == null) return true;
            byte[] buf = new byte[65536];
            int n = 0;
            while (n < buf.length) {
                int r = in.read(buf, n, buf.length - n);
                if (r < 0) break;
                n += r;
            }
            for (int i = 0; i + 4 <= n; i++) {
                if (buf[i] == 'm' && buf[i + 1] == 'o' && buf[i + 2] == 'o' && buf[i + 3] == 'f') {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            FLog.w(TAG, "Could not sniff container for " + uri + "; assuming fragmented", e);
            return true;
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
