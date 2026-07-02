package com.fadcam.ui.faditor.util;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;

import androidx.annotation.NonNull;

import com.fadcam.FLog;

import java.nio.ByteBuffer;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Detects silent / dead-air spans in a clip's audio and returns the
 * "keep" ranges (the parts worth keeping) in source-time milliseconds.
 *
 * <p>Pure DSP — decodes PCM and thresholds amplitude. No ML. The editor turns
 * each keep range into a jump-cut clip, dropping the quiet gaps between them.</p>
 */
public class SilenceDetector {

    private static final String TAG = "SilenceDetector";

    /** Minimum quiet span (after padding) that we bother to cut, ms. */
    private static final long MIN_SILENCE_MS = 350;
    /** Air left on each side of a cut so speech doesn't get clipped, ms. */
    private static final long PAD_MS = 90;
    /** Drop keep ranges shorter than this (slivers), ms. */
    private static final long MIN_KEEP_MS = 120;

    public interface Callback {
        /**
         * @param keepRangesMs ordered [startMs,endMs] spans to keep (source time)
         * @param gapsRemoved  number of silent gaps removed
         * @param msSaved       total duration removed, ms
         */
        void onResult(@NonNull List<long[]> keepRangesMs, int gapsRemoved, long msSaved);
        void onError(@NonNull Exception e);
    }

    private final Context context;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    public SilenceDetector(@NonNull Context context) {
        this.context = context.getApplicationContext();
    }

    public void shutdown() {
        executor.shutdownNow();
    }

    /**
     * @param sensitivity 0..1 — higher removes more (treats louder audio as silence)
     */
    public void detect(@NonNull Uri uri, long inMs, long outMs,
                       float sensitivity, @NonNull Callback callback) {
        executor.execute(() -> {
            try {
                Result r = analyze(uri, inMs, outMs, sensitivity);
                runOnMain(() -> callback.onResult(r.keepRanges, r.gapsRemoved, r.msSaved));
            } catch (Exception e) {
                FLog.e(TAG, "Silence detection failed", e);
                runOnMain(() -> callback.onError(e));
            }
        });
    }

    private static class Result {
        List<long[]> keepRanges = new ArrayList<>();
        int gapsRemoved;
        long msSaved;
    }

    /** One amplitude sample at a source timestamp. */
    private static class Env {
        final long timeMs;
        final float amp; // 0..1
        Env(long t, float a) { timeMs = t; amp = a; }
    }

    @NonNull
    private Result analyze(@NonNull Uri uri, long inMs, long outMs, float sensitivity)
            throws Exception {
        // Map sensitivity to a linear amplitude threshold (~-36 dB … -22 dB).
        float threshold = 0.015f + Math.max(0f, Math.min(1f, sensitivity)) * (0.080f - 0.015f);

        List<Env> envelope = decodeEnvelope(uri, inMs, outMs);

        Result result = new Result();
        if (envelope.isEmpty()) {
            // Could not read audio — keep everything.
            result.keepRanges.add(new long[]{inMs, outMs});
            return result;
        }

        // Find removable silent gaps (quiet for >= MIN_SILENCE_MS after padding).
        List<long[]> removeGaps = new ArrayList<>();
        boolean inSilence = false;
        long silenceStart = 0;
        long lastTime = inMs;
        for (Env e : envelope) {
            boolean quiet = e.amp < threshold;
            if (quiet && !inSilence) {
                inSilence = true;
                silenceStart = e.timeMs;
            } else if (!quiet && inSilence) {
                inSilence = false;
                addGapIfLongEnough(removeGaps, silenceStart, e.timeMs);
            }
            lastTime = e.timeMs;
        }
        if (inSilence) {
            addGapIfLongEnough(removeGaps, silenceStart, Math.max(lastTime, outMs));
        }

        // Build keep ranges = [inMs,outMs] minus the removable gaps.
        long cursor = inMs;
        for (long[] gap : removeGaps) {
            if (gap[0] > cursor) {
                addKeep(result.keepRanges, cursor, gap[0]);
            }
            cursor = Math.max(cursor, gap[1]);
            result.gapsRemoved++;
            result.msSaved += gap[1] - gap[0];
        }
        if (cursor < outMs) {
            addKeep(result.keepRanges, cursor, outMs);
        }

        // Nothing meaningful kept (all silence?) — fall back to whole clip.
        if (result.keepRanges.isEmpty()) {
            result.keepRanges.add(new long[]{inMs, outMs});
            result.gapsRemoved = 0;
            result.msSaved = 0;
        }
        return result;
    }

    private void addGapIfLongEnough(@NonNull List<long[]> gaps, long start, long end) {
        long s = start + PAD_MS;
        long e = end - PAD_MS;
        if (e - s >= MIN_SILENCE_MS) {
            gaps.add(new long[]{s, e});
        }
    }

    private void addKeep(@NonNull List<long[]> keeps, long start, long end) {
        if (end - start >= MIN_KEEP_MS) {
            keeps.add(new long[]{start, end});
        }
    }

    /**
     * Decode the audio between {@code inMs} and {@code outMs} into a coarse
     * amplitude envelope (one sample per decoded frame, ~20 ms resolution).
     */
    @NonNull
    private List<Env> decodeEnvelope(@NonNull Uri uri, long inMs, long outMs)
            throws Exception {
        List<Env> envelope = new ArrayList<>();
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec codec = null;
        try {
            extractor.setDataSource(context, uri, null);

            int trackIndex = -1;
            MediaFormat inputFormat = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String m = format.getString(MediaFormat.KEY_MIME);
                if (m != null && m.startsWith("audio/")) {
                    trackIndex = i;
                    inputFormat = format;
                    break;
                }
            }
            if (trackIndex < 0 || inputFormat == null) {
                return envelope; // no audio track
            }

            extractor.selectTrack(trackIndex);
            extractor.seekTo(inMs * 1000L, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);

            String mime = inputFormat.getString(MediaFormat.KEY_MIME);
            if (mime == null) return envelope;

            codec = MediaCodec.createDecoderByType(mime);
            codec.configure(inputFormat, null, null, 0);
            codec.start();

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false;
            boolean outputDone = false;
            long timeoutUs = 10_000;

            while (!outputDone) {
                if (!inputDone) {
                    int inIdx = codec.dequeueInputBuffer(timeoutUs);
                    if (inIdx >= 0) {
                        ByteBuffer inBuf = codec.getInputBuffer(inIdx);
                        int size = inBuf == null ? -1 : extractor.readSampleData(inBuf, 0);
                        if (size < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            long pts = extractor.getSampleTime();
                            codec.queueInputBuffer(inIdx, 0, size, pts, 0);
                            extractor.advance();
                            // Once we're past the range, stop feeding and flush EOS;
                            // the output-side break below ends the loop.
                            if (pts > outMs * 1000L) {
                                int eosIdx = codec.dequeueInputBuffer(timeoutUs);
                                if (eosIdx >= 0) {
                                    codec.queueInputBuffer(eosIdx, 0, 0, 0,
                                            MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                                }
                                inputDone = true;
                            }
                        }
                    }
                }

                int outIdx = codec.dequeueOutputBuffer(info, timeoutUs);
                if (outIdx >= 0) {
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputDone = true;
                    }
                    long timeMs = info.presentationTimeUs / 1000L;
                    ByteBuffer outBuf = codec.getOutputBuffer(outIdx);
                    if (outBuf != null && info.size > 0 && timeMs >= inMs && timeMs <= outMs) {
                        outBuf.position(info.offset);
                        outBuf.limit(info.offset + info.size);
                        ShortBuffer sb = outBuf.asShortBuffer();
                        int peak = 0;
                        while (sb.hasRemaining()) {
                            int a = Math.abs(sb.get());
                            if (a > peak) peak = a;
                        }
                        envelope.add(new Env(timeMs, peak / 32768f));
                    }
                    codec.releaseOutputBuffer(outIdx, false);
                    if (timeMs > outMs) {
                        outputDone = true; // past the range — done
                    }
                }
            }
            return envelope;
        } finally {
            if (codec != null) {
                try { codec.stop(); } catch (Exception ignored) {}
                try { codec.release(); } catch (Exception ignored) {}
            }
            extractor.release();
        }
    }

    private void runOnMain(@NonNull Runnable r) {
        new android.os.Handler(android.os.Looper.getMainLooper()).post(r);
    }
}
