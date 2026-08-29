package com.fadcam.ui.faditor.waveform;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.FLog;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.ShortBuffer;
import java.nio.channels.FileChannel;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A flat, memory-mapped PCM copy of an audio source, baked once and kept on disk — the
 * substrate for After-Effects-style audio scrubbing ({@link
 * com.fadcam.ui.faditor.audio.ScrubEngine}).
 *
 * <p><b>Why this exists.</b> Scrubbing must never touch a decoder. Every stutter anyone has
 * ever heard while dragging a playhead is a decoder being asked to seek: a compressed source
 * has to rewind to the preceding sync sample and decode forward, which costs 50–300&nbsp;ms and
 * varies run to run. That is unusable for the thing scrubbing is FOR — finding the exact
 * instant a word starts. A flat file has no sync samples, so a seek is a pointer move.</p>
 *
 * <p><b>Format.</b> Mono, {@value #RATE}&nbsp;Hz, 16-bit little-endian, after an
 * {@value #HEADER_BYTES}-byte header. Deliberately NOT the source format:</p>
 * <ul>
 *   <li><b>Mono</b> — scrubbing is for locating events in time, not for judging a stereo
 *       image. Halves the size and the read cost.</li>
 *   <li><b>{@value #RATE}&nbsp;Hz</b> — an ~11&nbsp;kHz ceiling, which carries every
 *       consonant transient that tells you where a word begins. Sibilance above that is not
 *       what anyone aligns to.</li>
 *   <li><b>16-bit</b> — what {@link android.media.AudioTrack} wants, so playback is a
 *       {@code System.arraycopy}-class operation with no conversion.</li>
 * </ul>
 *
 * <p>That comes to about <b>2.6&nbsp;MB per minute</b> of audio: a 7-minute song costs ~18&nbsp;MB
 * and a 50-minute project ~132&nbsp;MB, sitting in {@link
 * com.fadcam.ui.faditor.util.DurableCache} beside the waveform peaks it complements.
 * {@code WaveformExtractor} reduces the same decode to ~60 peaks per second for DRAWING;
 * this keeps the samples themselves for HEARING. Both are baked from one decode pass in
 * principle — they are separate today because they cache independently and a project may
 * want one without the other.</p>
 *
 * <p><b>Downsampling is box-averaged, not decimated.</b> Dropping samples aliases high
 * frequencies down into the audible band as a metallic whistle, which is exactly the
 * artefact that makes cheap scrubbing sound broken. Averaging every input sample that falls
 * inside an output sample is a crude low-pass, costs one add per input sample, and removes
 * it.</p>
 *
 * <p><b>Degradation contract:</b> every failure path returns {@code null} rather than
 * throwing. A missing or unmappable sidecar means "no scrub audio", never a broken editor.</p>
 */
public final class PcmSidecar {

    private static final String TAG = "PcmSidecar";

    /** Output sample rate. Changing this invalidates every cached bake — bump {@link #VERSION}. */
    public static final int RATE = 22050;

    /** {@code 'P','S','C','1'} — guards against a truncated or foreign file. */
    private static final int MAGIC = 0x50534331;
    private static final int VERSION = 1;
    /** magic (4) + version (4). Frame count is derived from the file length, so it needs no field. */
    private static final int HEADER_BYTES = 8;

    /**
     * Refuse to memory-map anything larger than this. A 32-bit process (armeabi-v7a) has a
     * ~2–3&nbsp;GB address space that is already fragmented by the time an editor is running,
     * and a failed map must not be allowed to look like a crash. 256&nbsp;MB is ~96 minutes.
     */
    private static final long MAX_MAP_BYTES = 256L * 1024 * 1024;

    private static final ExecutorService BAKE_POOL = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "pcm-sidecar-bake");
        t.setPriority(Thread.MIN_PRIORITY);   // never compete with playback or the UI
        return t;
    });

    private PcmSidecar() {}

    // ── Handle ───────────────────────────────────────────────────────────────────────

    /**
     * An open, memory-mapped bake. Reads are pointer arithmetic — no I/O call per sample, and
     * the pages are evictable under memory pressure rather than counting against the heap.
     *
     * <p>Not thread-safe for concurrent {@link #frameAt} from multiple threads: the underlying
     * {@link ShortBuffer} carries a position. One handle serves one reader — in practice the
     * one {@code ScrubEngine} feeder thread.</p>
     */
    public static final class Handle implements OnsetDetector.Samples {
        private final ShortBuffer samples;
        /** Total mono frames in the bake. */
        public final int frames;

        // OnsetDetector.Samples. The dependency points THIS way on purpose: OnsetDetector is
        // pure Java so the JVM harness can pin its heuristics against synthetic signals, and
        // an adapter living over there would drag android.media onto its compile path.
        @Override public int count() { return frames; }
        @Override public float at(int index) { return frameAt(index); }

        private Handle(@NonNull ShortBuffer samples, int frames) {
            this.samples = samples;
            this.frames = frames;
        }

        /** Duration of the baked audio in ms. */
        public long durationMs() {
            return frames * 1000L / RATE;
        }

        /** Convert a source time to a frame index, clamped into the bake. */
        public int frameForMs(long ms) {
            long f = ms * RATE / 1000L;
            if (f < 0) return 0;
            if (f >= frames) return Math.max(0, frames - 1);
            return (int) f;
        }

        /**
         * One sample as a float in −1..1, or 0 outside the bake. Out-of-range reads return
         * silence rather than throwing: a scrub cursor legitimately runs off both ends while
         * a grain is being filled, and a bounds exception on the audio feeder thread would
         * kill playback for the rest of the session.
         */
        public float frameAt(int index) {
            if (index < 0 || index >= frames) return 0f;
            return samples.get(index) / 32768f;
        }

        /**
         * Linearly interpolated read at a fractional frame position — what a scrub at any
         * speed other than exactly 1× needs. Rounding to the nearest sample instead produces
         * a rough, gritty shuttle; interpolating costs one multiply and sounds like tape.
         */
        public float frameAt(double index) {
            int i = (int) Math.floor(index);
            float a = frameAt(i);
            float b = frameAt(i + 1);
            float t = (float) (index - i);
            return a + (b - a) * t;
        }
    }

    // ── Public API ───────────────────────────────────────────────────────────────────

    public interface BakeCallback {
        /** Bake finished (or was already cached) and the file is ready to {@link #open}. */
        void onBaked(@NonNull File file);
        /** Bake failed. Scrub audio is simply unavailable; nothing else is affected. */
        void onFailed(@NonNull String message);
        /** 0..1, fired only on a real decode (a cache hit reports nothing). */
        default void onProgress(float fraction) {}
    }

    /** True if a complete bake for this source already exists. */
    public static boolean isBaked(@NonNull Context context, @NonNull Uri uri) {
        File f = cacheFile(context, uri);
        return f.isFile() && f.length() > HEADER_BYTES;
    }

    /**
     * Bake {@code uri} to a sidecar if it is not already baked, on a low-priority background
     * thread. Idempotent and safe to call on every project open.
     */
    public static void bakeAsync(@NonNull Context context, @NonNull Uri uri,
                                 @NonNull BakeCallback callback) {
        final Context app = context.getApplicationContext();
        BAKE_POOL.execute(() -> {
            File out = cacheFile(app, uri);
            if (out.isFile() && out.length() > HEADER_BYTES) {
                callback.onBaked(out);
                return;
            }
            try {
                bake(app, uri, out, callback::onProgress);
                callback.onBaked(out);
            } catch (Exception e) {
                FLog.e(TAG, "PCM sidecar bake failed for " + uri, e);
                // A half-written file would pass the length check above forever.
                File tmp = tempFor(out);
                if (tmp.exists() && !tmp.delete()) FLog.w(TAG, "could not delete " + tmp);
                if (out.exists() && !out.delete()) FLog.w(TAG, "could not delete " + out);
                callback.onFailed(e.getMessage() != null ? e.getMessage() : "bake failed");
            }
        });
    }

    /**
     * Memory-map an existing bake. Returns {@code null} if it is missing, truncated, foreign,
     * from an older format version, or too large to map — every one of which means "no scrub
     * audio", not an error the caller has to handle.
     */
    @Nullable
    public static Handle open(@NonNull Context context, @NonNull Uri uri) {
        File f = cacheFile(context, uri);
        if (!f.isFile() || f.length() <= HEADER_BYTES) return null;
        if (f.length() > MAX_MAP_BYTES) {
            FLog.w(TAG, "sidecar too large to map (" + f.length() + " B): " + f.getName());
            return null;
        }
        try (RandomAccessFile raf = new RandomAccessFile(f, "r");
             FileChannel ch = raf.getChannel()) {
            ByteBuffer head = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.BIG_ENDIAN);
            if (ch.read(head, 0) != HEADER_BYTES) return null;
            head.flip();
            if (head.getInt() != MAGIC || head.getInt() != VERSION) {
                FLog.w(TAG, "stale or foreign sidecar, ignoring: " + f.getName());
                return null;
            }
            long dataBytes = f.length() - HEADER_BYTES;
            MappedByteBuffer map = ch.map(FileChannel.MapMode.READ_ONLY, HEADER_BYTES, dataBytes);
            map.order(ByteOrder.LITTLE_ENDIAN);
            return new Handle(map.asShortBuffer(), (int) (dataBytes / 2));
        } catch (Exception e) {
            FLog.e(TAG, "could not map sidecar " + f.getName(), e);
            return null;
        }
    }

    // ── Bake ─────────────────────────────────────────────────────────────────────────

    private interface Progress { void onProgress(float fraction); }

    /**
     * Decode {@code uri} to mono {@value #RATE}&nbsp;Hz 16-bit and stream it to {@code out}.
     *
     * <p>Written to a temp file and renamed on success, so an interrupted bake (the process
     * dying, the user leaving) can never leave a truncated file that {@link #isBaked} would
     * then believe. Memory stays flat regardless of source length — nothing accumulates.</p>
     */
    private static void bake(@NonNull Context context, @NonNull Uri uri, @NonNull File out,
                             @Nullable Progress progress) throws Exception {
        Uri decodeUri = BandWaveformExtractor.resolveDecodeUri(context, uri);
        File tmp = tempFor(out);
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec codec = null;
        BufferedOutputStream os = null;
        try {
            extractor.setDataSource(context, decodeUri, null);
            int track = selectAudioTrack(extractor);
            if (track < 0) throw new IllegalStateException("no audio track");
            extractor.selectTrack(track);
            MediaFormat fmt = extractor.getTrackFormat(track);
            String mime = fmt.getString(MediaFormat.KEY_MIME);
            if (mime == null) throw new IllegalStateException("no mime");
            int srcRate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            int channels = fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                    ? Math.max(1, fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)) : 1;
            long durationUs = fmt.containsKey(MediaFormat.KEY_DURATION)
                    ? fmt.getLong(MediaFormat.KEY_DURATION) : 0L;

            codec = MediaCodec.createDecoderByType(mime);
            codec.configure(fmt, null, null, 0);
            codec.start();

            //noinspection ResultOfMethodCallIgnored
            tmp.getParentFile().mkdirs();
            os = new BufferedOutputStream(new FileOutputStream(tmp), 1 << 16);
            os.write(new byte[]{
                    (byte) (MAGIC >>> 24), (byte) (MAGIC >>> 16), (byte) (MAGIC >>> 8), (byte) MAGIC,
                    (byte) (VERSION >>> 24), (byte) (VERSION >>> 16), (byte) (VERSION >>> 8), (byte) VERSION,
            });

            // Box-filter resample state. `inPerOut` input samples are averaged into each
            // output sample; `phase` carries the fractional remainder so the rate stays exact
            // over a long file instead of drifting a sample every few seconds.
            final double inPerOut = srcRate / (double) RATE;
            double phase = 0;
            double acc = 0;
            int accN = 0;

            byte[] outBuf = new byte[8192];
            int outFill = 0;

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false, outputDone = false;
            float lastReported = 0f;
            while (!outputDone) {
                if (!inputDone) {
                    int inIdx = codec.dequeueInputBuffer(10000);
                    if (inIdx >= 0) {
                        ByteBuffer ib = codec.getInputBuffer(inIdx);
                        int sz = ib != null ? extractor.readSampleData(ib, 0) : -1;
                        if (sz < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            codec.queueInputBuffer(inIdx, 0, sz, extractor.getSampleTime(), 0);
                            extractor.advance();
                        }
                    }
                }
                int outIdx = codec.dequeueOutputBuffer(info, 10000);
                if (outIdx >= 0) {
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true;
                    ByteBuffer ob = codec.getOutputBuffer(outIdx);
                    if (ob != null && info.size > 0) {
                        ob.order(ByteOrder.LITTLE_ENDIAN);
                        ShortBuffer sb = ob.asShortBuffer();
                        int shorts = info.size / 2;
                        for (int i = 0; i + channels <= shorts; i += channels) {
                            int mixed = 0;
                            for (int c = 0; c < channels; c++) mixed += sb.get(i + c);
                            acc += mixed / (double) channels;
                            accN++;
                            phase += 1.0;
                            if (phase >= inPerOut) {
                                phase -= inPerOut;
                                int v = (int) Math.round(acc / accN);
                                if (v > Short.MAX_VALUE) v = Short.MAX_VALUE;
                                if (v < Short.MIN_VALUE) v = Short.MIN_VALUE;
                                outBuf[outFill++] = (byte) v;
                                outBuf[outFill++] = (byte) (v >> 8);
                                if (outFill == outBuf.length) { os.write(outBuf, 0, outFill); outFill = 0; }
                                acc = 0;
                                accN = 0;
                            }
                        }
                    }
                    codec.releaseOutputBuffer(outIdx, false);
                    if (progress != null && durationUs > 0) {
                        float f = Math.min(1f, info.presentationTimeUs / (float) durationUs);
                        if (f - lastReported >= 0.02f) { lastReported = f; progress.onProgress(f); }
                    }
                } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    // Some decoders only report the true channel count here, and reading a
                    // stereo buffer as mono halves the pitch — an obvious, confusing artefact.
                    MediaFormat of = codec.getOutputFormat();
                    if (of.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                        channels = Math.max(1, of.getInteger(MediaFormat.KEY_CHANNEL_COUNT));
                    }
                }
            }
            if (outFill > 0) os.write(outBuf, 0, outFill);
            os.flush();
            os.close();
            os = null;

            if (out.exists() && !out.delete()) FLog.w(TAG, "could not replace " + out);
            if (!tmp.renameTo(out)) throw new IllegalStateException("rename failed: " + tmp);
        } finally {
            if (os != null) try { os.close(); } catch (Exception ignored) {}
            if (codec != null) { try { codec.stop(); } catch (Exception ignored) {} codec.release(); }
            extractor.release();
        }
    }

    private static int selectAudioTrack(@NonNull MediaExtractor ex) {
        for (int i = 0; i < ex.getTrackCount(); i++) {
            String m = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
            if (m != null && m.startsWith("audio/")) return i;
        }
        return -1;
    }

    // ── Cache location ───────────────────────────────────────────────────────────────

    @NonNull
    private static File cacheFile(@NonNull Context context, @NonNull Uri uri) {
        File dir = com.fadcam.ui.faditor.util.DurableCache.dir(context, "pcmscrub");
        String key = Integer.toHexString(uri.toString().hashCode()) + "_v" + VERSION;
        return new File(dir, key + ".pcm");
    }

    @NonNull
    private static File tempFor(@NonNull File out) {
        return new File(out.getParentFile(), out.getName() + ".part");
    }
}
