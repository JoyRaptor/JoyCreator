package com.fadcam.ui.faditor.waveform;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.FLog;
import com.fadcam.ui.faditor.model.WaveformData;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Decodes an audio (or video's audio) source to PCM via MediaCodec and reduces it — streaming,
 * so memory stays flat even for a 30-minute track — into {@link WaveformData}: a peak amplitude
 * and a per-band FFT spectrum for each fixed-length time bucket. Results are cached on disk so a
 * project reopens instantly.
 *
 * <p>No third-party dependency (own {@link Fft}); the produced bitmap-free data feeds
 * {@link WaveformStyleRenderer} both in editor preview and at export.</p>
 */
public class WaveformExtractor {

    private static final String TAG = "WaveformExtractor";
    private static final int BUCKETS_PER_SEC = 60;   // visual time resolution (default tier)
    private static final int FFT_SIZE = 1024;        // power of two
    private static final int CACHE_VERSION = 3; // bumped: span-limited extraction (startOffset + bucketMs)
    /** Empty spectrum row used when a caller only needs amplitudes (timeline bars, W2). */
    private static final float[] NO_SPECTRUM = new float[0];

    /** endMs sentinel meaning "to the end of the source". */
    public static final long FULL_END = Long.MAX_VALUE;

    public interface Callback {
        void onReady(@NonNull WaveformData data);
        void onError(@NonNull String message);
        /** Decode progress 0..1 (only fired for a cache MISS, i.e. a real decode). */
        default void onProgress(float fraction) {}
    }

    /** Lightweight progress sink for the synchronous {@link #extract} path (e.g. export). */
    public interface ProgressListener {
        void onProgress(float fraction);
    }

    private final Context context;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    public WaveformExtractor(@NonNull Context context) {
        this.context = context.getApplicationContext();
    }

    /** Extract (or load from cache) on a background thread; callback on that thread. */
    public void extractAsync(@NonNull Uri uri, int bands, @NonNull Callback callback) {
        extractAsync(uri, bands, 0L, FULL_END, callback);
    }

    /**
     * Span-limited variant: only decodes the source window {@code [startMs, endMs]} (the slice a
     * trimmed clip actually uses), so placing a visualizer over a 10s clip in a 30-min source no
     * longer decodes the whole file. The returned {@link WaveformData} carries its
     * {@code startOffsetMs} so the renderer indexes by absolute source time.
     */
    public void extractAsync(@NonNull Uri uri, int bands, long startMs, long endMs,
                             @NonNull Callback callback) {
        extractAsync(uri, bands, startMs, endMs, BUCKETS_PER_SEC, true, callback);
    }

    /**
     * Density-parameterized variant (W2 timeline HD zoom): {@code bucketsPerSec} sets the time
     * resolution (default tier is {@value #BUCKETS_PER_SEC}); {@code withSpectrum=false} skips the
     * per-bucket FFT entirely (amplitude-only — the timeline bars never read the spectrum, and at
     * 200–400 buckets/sec the FFT would dominate the decode cost). Non-default requests cache under
     * their own key, so existing visualizer cache entries stay valid.
     */
    public void extractAsync(@NonNull Uri uri, int bands, long startMs, long endMs,
                             int bucketsPerSec, boolean withSpectrum, @NonNull Callback callback) {
        executor.execute(() -> {
            try {
                WaveformData cached = readCache(uri, bands, startMs, endMs, bucketsPerSec, withSpectrum);
                if (cached != null) {
                    callback.onReady(cached);
                    return;
                }
                WaveformData data = extract(uri, bands, startMs, endMs, bucketsPerSec, withSpectrum,
                        callback::onProgress);
                writeCache(uri, bands, startMs, endMs, bucketsPerSec, withSpectrum, data);
                callback.onReady(data);
            } catch (Exception e) {
                FLog.e(TAG, "Waveform extraction failed", e);
                callback.onError(e.getMessage() != null ? e.getMessage() : "extraction failed");
            }
        });
    }

    public void shutdown() {
        executor.shutdownNow();
    }

    /** Synchronous extraction of the full source. Call off the main thread. */
    @NonNull
    public WaveformData extract(@NonNull Uri uri, int bands) throws Exception {
        return extract(uri, bands, 0L, FULL_END, null);
    }

    /** Synchronous full-source extraction with optional progress reporting. */
    @NonNull
    public WaveformData extract(@NonNull Uri uri, int bands,
                                @Nullable ProgressListener progress) throws Exception {
        return extract(uri, bands, 0L, FULL_END, progress);
    }

    /**
     * Synchronous span-limited extraction. Decodes only {@code [startMs, endMs]} of the source
     * (use {@link #FULL_END} for {@code endMs} to mean end-of-source). Call off the main thread.
     */
    @NonNull
    public WaveformData extract(@NonNull Uri uri, int bands, long startMs, long endMs,
                                @Nullable ProgressListener progress) throws Exception {
        return extract(uri, bands, startMs, endMs, BUCKETS_PER_SEC, true, progress);
    }

    /** As above with explicit bucket density and optional FFT skip (see the async variant). */
    @NonNull
    public WaveformData extract(@NonNull Uri uri, int bands, long startMs, long endMs,
                                int bucketsPerSec, boolean withSpectrum,
                                @Nullable ProgressListener progress) throws Exception {
        bands = Math.max(1, bands);
        bucketsPerSec = Math.max(1, bucketsPerSec);
        long startUs = Math.max(0, startMs) * 1000L;
        long endUs = endMs >= FULL_END / 2 ? FULL_END : Math.max(startMs + 1, endMs) * 1000L;
        // Decode-side only (cache keys stay on the caller's uri): a seekable remuxed copy
        // makes span extraction actually span-limited — on a raw fMP4 the seek below lands
        // at 0 and a mid-file window decodes everything before it.
        uri = BandWaveformExtractor.resolveDecodeUri(context, uri);
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec codec = null;
        try {
            extractor.setDataSource(context, uri, null);
            int track = selectAudioTrack(extractor);
            if (track < 0) throw new IllegalStateException("no audio track");
            extractor.selectTrack(track);
            MediaFormat fmt = extractor.getTrackFormat(track);
            String mime = fmt.getString(MediaFormat.KEY_MIME);
            int sampleRate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            int channels = fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                    ? fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 1;
            long durationUs = fmt.containsKey(MediaFormat.KEY_DURATION)
                    ? fmt.getLong(MediaFormat.KEY_DURATION) : 0;
            if (startUs > 0) {
                extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            }

            codec = MediaCodec.createDecoderByType(mime);
            codec.configure(fmt, null, null, 0);
            codec.start();

            int bucketSamples = Math.max(1, Math.round(sampleRate / (float) bucketsPerSec));
            long bucketMs = Math.max(1, bucketSamples * 1000L / Math.max(1, sampleRate));
            List<Float> ampList = new ArrayList<>();
            List<float[]> specList = new ArrayList<>();
            float[] bucketBuf = new float[bucketSamples];
            int bucketFill = 0;
            float bucketPeak = 0f;
            float[] re = new float[FFT_SIZE];
            float[] im = new float[FFT_SIZE];
            long firstSampleUs = -1; // source time of bucket 0 (== seek landing, may be < startUs)
            long progSpanUs = (endUs != FULL_END ? endUs : durationUs) - startUs;

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false, outputDone = false;
            float lastReportedProgress = 0f;
            int drainStalls = 0; // consecutive empty output dequeues after input EOS
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
                    if (firstSampleUs < 0 && info.size > 0) firstSampleUs = info.presentationTimeUs;
                    if (progress != null && progSpanUs > 0) {
                        float f = Math.min(1f, (info.presentationTimeUs - startUs) / (float) progSpanUs);
                        if (f - lastReportedProgress >= 0.02f) {
                            lastReportedProgress = f;
                            progress.onProgress(Math.max(0f, f));
                        }
                    }
                    ByteBuffer ob = codec.getOutputBuffer(outIdx);
                    if (ob != null && info.size > 0) {
                        ob.order(ByteOrder.LITTLE_ENDIAN);
                        ShortBuffer sb = ob.asShortBuffer();
                        int shorts = info.size / 2;
                        int ch = Math.max(1, channels);
                        for (int i = 0; i + ch <= shorts; i += ch) {
                            float s = 0f;
                            for (int c = 0; c < ch; c++) s += sb.get(i + c);
                            float v = (s / ch) / 32768f;
                            bucketBuf[bucketFill++] = v;
                            float av = Math.abs(v);
                            if (av > bucketPeak) bucketPeak = av;
                            if (bucketFill >= bucketSamples) {
                                ampList.add(bucketPeak);
                                specList.add(withSpectrum
                                        ? computeBands(bucketBuf, bucketFill, re, im, bands)
                                        : NO_SPECTRUM);
                                bucketFill = 0;
                                bucketPeak = 0f;
                            }
                        }
                    }
                    codec.releaseOutputBuffer(outIdx, false);
                    drainStalls = 0;
                    // Span limit: once we've decoded past the requested end, the rest of the file is
                    // irrelevant — stop early (this is the whole point of span extraction).
                    if (endUs != FULL_END && info.presentationTimeUs > endUs) outputDone = true;
                } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat of = codec.getOutputFormat();
                    if (of.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                        channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    }
                } else if (inputDone) {
                    // Input EOS queued but the decoder isn't flagging output EOS (some codecs/files
                    // never do) — after ~2s of no output, assume we're done so we don't hang at ~99%.
                    if (++drainStalls > 200) {
                        FLog.w(TAG, "Waveform decode: no EOS after input end; stopping at "
                                + ampList.size() + " buckets");
                        break;
                    }
                }
            }
            if (bucketFill > 0) {
                ampList.add(bucketPeak);
                specList.add(withSpectrum
                        ? computeBands(bucketBuf, bucketFill, re, im, bands)
                        : NO_SPECTRUM);
            }

            long startOffsetMs = firstSampleUs >= 0 ? firstSampleUs / 1000 : Math.max(0, startMs);
            return normalize(ampList, specList, durationUs, startOffsetMs, bucketMs);
        } finally {
            if (codec != null) {
                try { codec.stop(); } catch (Exception ignored) { }
                try { codec.release(); } catch (Exception ignored) { }
            }
            try { extractor.release(); } catch (Exception ignored) { }
        }
    }

    private static int selectAudioTrack(@NonNull MediaExtractor extractor) {
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            MediaFormat f = extractor.getTrackFormat(i);
            String mime = f.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("audio/")) return i;
        }
        return -1;
    }

    /** Hann-windowed FFT of one bucket, reduced to {@code bands} averaged magnitudes. */
    private static float[] computeBands(@NonNull float[] buf, int n,
                                        @NonNull float[] re, @NonNull float[] im, int bands) {
        int N = re.length;
        for (int i = 0; i < N; i++) {
            float s = i < n ? buf[i] : 0f;
            float w = 0.5f * (1f - (float) Math.cos(2 * Math.PI * i / (N - 1)));
            re[i] = s * w;
            im[i] = 0f;
        }
        Fft.transform(re, im);
        int half = N / 2;
        float[] out = new float[bands];
        for (int b = 0; b < bands; b++) {
            int lo = (int) ((long) b * half / bands);
            int hi = (int) ((long) (b + 1) * half / bands);
            if (hi <= lo) hi = lo + 1;
            float sum = 0f;
            int cnt = 0;
            for (int k = lo; k < hi && k < half; k++) {
                sum += (float) Math.hypot(re[k], im[k]);
                cnt++;
            }
            out[b] = cnt > 0 ? sum / cnt : 0f;
        }
        return out;
    }

    @NonNull
    private static WaveformData normalize(@NonNull List<Float> ampList,
                                          @NonNull List<float[]> specList, long durationUs,
                                          long startOffsetMs, long bucketMs) {
        // Amplitude: normalize by a high percentile, not the absolute max, so a single
        // transient peak doesn't flatten the whole track to near-zero.
        float ampRef = percentile(ampList, 0.97f);
        if (ampRef < 1e-4f) {
            for (float a : ampList) ampRef = Math.max(ampRef, a);
        }
        if (ampRef < 1e-6f) ampRef = 1e-6f;
        float[] amps = new float[ampList.size()];
        for (int i = 0; i < amps.length; i++) amps[i] = clamp01(ampList.get(i) / ampRef);

        // Spectrum: normalize PER BAND (each band by its own loudest moment) so quiet bands
        // like treble are as lively as bass instead of being crushed by the global max.
        int bands = specList.isEmpty() ? 0 : specList.get(0).length;
        float[] bandRef = new float[bands];
        for (int b = 0; b < bands; b++) bandRef[b] = 1e-6f;
        for (float[] row : specList) {
            for (int b = 0; b < bands; b++) bandRef[b] = Math.max(bandRef[b], row[b]);
        }
        float[][] spec = new float[specList.size()][];
        for (int i = 0; i < spec.length; i++) {
            float[] row = specList.get(i);
            float[] nb = new float[bands];
            for (int b = 0; b < bands; b++) nb[b] = clamp01(row[b] / bandRef[b]);
            spec[i] = nb;
        }
        long durationMs = durationUs > 0 ? durationUs / 1000
                : (long) (amps.length * bucketMs);
        return new WaveformData(amps, spec, durationMs, startOffsetMs, bucketMs);
    }

    /** Value at percentile {@code p} (0..1) of the list. */
    private static float percentile(@NonNull List<Float> vals, float p) {
        if (vals.isEmpty()) return 0f;
        float[] arr = new float[vals.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = vals.get(i);
        java.util.Arrays.sort(arr);
        int idx = (int) Math.floor(p * (arr.length - 1));
        return arr[Math.max(0, Math.min(arr.length - 1, idx))];
    }

    private static float clamp01(float v) {
        return v < 0 ? 0 : (v > 1 ? 1 : v);
    }

    // ── Disk cache ───────────────────────────────────────────────────

    @NonNull
    private File cacheFile(@NonNull Uri uri, int bands, long startMs, long endMs,
                           int bucketsPerSec, boolean withSpectrum) {
        File dir = com.fadcam.ui.faditor.util.DurableCache.dir(context, "waveform");
        String span = (startMs <= 0 && endMs >= FULL_END / 2)
                ? "full" : (Math.max(0, startMs) + "-" + endMs);
        String key = Integer.toHexString(uri.toString().hashCode()) + "_" + bands + "_" + span;
        // Non-default density / amplitude-only requests get their own key SUFFIX so every
        // pre-existing default-tier cache entry keeps resolving (no version bump needed).
        if (bucketsPerSec != BUCKETS_PER_SEC || !withSpectrum) {
            key += "_bps" + bucketsPerSec + (withSpectrum ? "" : "_amp");
        }
        return new File(dir, key + ".bin");
    }

    @Nullable
    private WaveformData readCache(@NonNull Uri uri, int bands, long startMs, long endMs,
                                   int bucketsPerSec, boolean withSpectrum) {
        File f = cacheFile(uri, bands, startMs, endMs, bucketsPerSec, withSpectrum);
        if (!f.exists()) return null;
        try (DataInputStream in = new DataInputStream(
                new java.io.BufferedInputStream(new FileInputStream(f)))) {
            if (in.readInt() != CACHE_VERSION) return null;
            long durationMs = in.readLong();
            long startOffsetMs = in.readLong();
            long bucketMs = in.readLong();
            int buckets = in.readInt();
            int b = in.readInt();
            float[] amps = new float[buckets];
            for (int i = 0; i < buckets; i++) amps[i] = in.readFloat();
            float[][] spec = new float[buckets][b];
            for (int i = 0; i < buckets; i++) {
                for (int j = 0; j < b; j++) spec[i][j] = in.readFloat();
            }
            return new WaveformData(amps, spec, durationMs, startOffsetMs, bucketMs);
        } catch (Exception e) {
            FLog.w(TAG, "Waveform cache read failed; will re-extract", e);
            return null;
        }
    }

    private void writeCache(@NonNull Uri uri, int bands, long startMs, long endMs,
                            int bucketsPerSec, boolean withSpectrum, @NonNull WaveformData data) {
        File f = cacheFile(uri, bands, startMs, endMs, bucketsPerSec, withSpectrum);
        try (DataOutputStream out = new DataOutputStream(new FileOutputStream(f))) {
            out.writeInt(CACHE_VERSION);
            out.writeLong(data.durationMs);
            out.writeLong(data.startOffsetMs);
            out.writeLong(data.bucketMs);
            out.writeInt(data.bucketCount());
            out.writeInt(data.bandCount());
            for (float a : data.amplitudes) out.writeFloat(a);
            for (float[] row : data.spectrum) {
                for (float x : row) out.writeFloat(x);
            }
        } catch (Exception e) {
            FLog.w(TAG, "Waveform cache write failed", e);
        }
    }
}

