package com.fadcam.ui.faditor.waveform;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.FLog;
import com.fadcam.ui.faditor.model.BandedWaveformData;

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
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Decodes an audio (or video's-audio) source to PCM via MediaCodec and splits it into the four
 * tape bands with cascaded {@link Biquad} IIR filters, reducing each band to a per-hop RMS
 * envelope ({@link BandedWaveformData}). This is the Android port of the prototype's WebAudio
 * {@code renderBand} + {@code rmsEnvelope}; it stays O(n) and flat in memory.
 *
 * <p>PERF (F9, PERF_SPEC_LONGFILE_20260718 — a 45-min source took minutes single-threaded):
 * <ul>
 *   <li><b>Chunked mode</b> — when the container seeks reliably (verified by a probe seek),
 *   the span splits into up to {@link #PARALLELISM} ranges, each decoded + filtered on its own
 *   worker with its own codec; a {@link #PREROLL_MS} pre-roll warms the IIR state at each
 *   boundary so the stitched envelope is visually identical to a serial pass.</li>
 *   <li><b>Pipelined mode</b> — raw FadCam fMP4 has no index, so the platform extractor can't
 *   seek it: one sequential decode, but the band DSP runs on the worker pool in double-buffered
 *   batches so filtering overlaps decoding instead of serializing with it.</li>
 *   <li>If a validated <b>remuxed copy</b> of a file source exists (FragmentedMp4Remuxer output
 *   is atomically renamed only after validation), decode THAT — same audio stream, but seekable,
 *   which upgrades raw recordings to chunked mode. Cache keys stay on the ORIGINAL uri.</li>
 *   <li>Inner loop: bulk {@code ShortBuffer.get(short[])} instead of per-sample gets, and
 *   primitive growable arrays instead of boxed {@code ArrayList<Float>} (a 45-min run boxed
 *   ~4M Floats — a measurable slice of the editor's GC churn).</li>
 * </ul></p>
 *
 * <p>Results are disk-cached keyed by source + span + crossovers, so a project reopens
 * instantly. The decode skeleton intentionally mirrors {@link WaveformExtractor} (a later
 * cleanup could share a common PCM streamer); kept separate here so the visualizer's FFT path
 * stays untouched.</p>
 */
public class BandWaveformExtractor {

    private static final String TAG = "BandWaveformExtractor";
    private static final int HOP = 128;          // samples per envelope frame (~345 fps @ 44.1k)
    private static final int CACHE_VERSION = 1;

    /** endMs sentinel meaning "to the end of the source". */
    public static final long FULL_END = Long.MAX_VALUE;

    /** Default crossover frequencies (Hz): bass|voice|upper-voice|highs = 110 / 950 / 6300. */
    public static final int DEFAULT_LOW = 110;
    public static final int DEFAULT_PRES = 950;
    public static final int DEFAULT_HIGH = 6300;

    /** Pre-roll decoded through the filters before a chunk's first accumulated sample so the
     *  IIR state is warm at the boundary. The lowest corner (110Hz) settles in ~10ms; 300ms is
     *  deliberate overkill and still costs <1% of a chunk. */
    private static final int PREROLL_MS = 300;
    /** Minimum span worth one parallel chunk — below ~45s the extra codec setup (~100-300ms
     *  per instance) eats the win, and short spans are fast anyway. */
    private static final long PARALLEL_MIN_SPAN_MS = 45_000L;
    /** Mono samples per DSP batch handed to band workers in pipelined mode (~1.4s @ 48k). */
    private static final int BATCH_SAMPLES = 1 << 16;

    private static final int PARALLELISM =
            Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors() - 2));

    /** Shared workers: chunk decodes in chunked mode, band DSP in pipelined mode. One
     *  extraction runs at a time (the outer per-instance executor is single-threaded), so the
     *  two uses never compete. Daemon threads — app-lifetime pool. */
    private static final ExecutorService WORKERS = Executors.newFixedThreadPool(PARALLELISM, r -> {
        Thread t = new Thread(r, "band-wave-worker");
        t.setDaemon(true);
        return t;
    });

    public interface Callback {
        void onReady(@NonNull BandedWaveformData data);
        void onError(@NonNull String message);
        default void onProgress(float fraction) {}
    }

    private final Context context;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    public BandWaveformExtractor(@NonNull Context context) {
        this.context = context.getApplicationContext();
    }

    public void shutdown() {
        executor.shutdownNow();
    }

    /**
     * Extract (or load from cache) on a background thread; callback fires on that thread.
     *
     * @param startMs/endMs span to analyze (use {@link #FULL_END} for whole source).
     * @param lowHz/presHz/highHz crossovers; {@code presenceOn=false} skips the presence band.
     */
    public void extractAsync(@NonNull Uri uri, long startMs, long endMs,
                             int lowHz, int presHz, int highHz, boolean presenceOn,
                             @NonNull Callback callback) {
        executor.execute(() -> {
            try {
                BandedWaveformData cached = readCache(uri, startMs, endMs, lowHz, presHz, highHz,
                        presenceOn);
                if (cached != null) {
                    callback.onReady(cached);
                    return;
                }
                BandedWaveformData data = extract(uri, startMs, endMs, lowHz, presHz, highHz,
                        presenceOn, callback::onProgress);
                if (data.complete) {
                    writeCache(uri, startMs, endMs, lowHz, presHz, highHz, presenceOn, data);
                } else {
                    // Show what we have, but never persist a partial extraction — a cached
                    // prefix renders as flat stripes forever (2026-07-16). Next open re-tries.
                    FLog.w(TAG, "Band waveform extraction incomplete — displayed but NOT cached");
                }
                callback.onReady(data);
            } catch (Exception e) {
                FLog.e(TAG, "Band waveform extraction failed", e);
                callback.onError(e.getMessage() != null ? e.getMessage() : "extraction failed");
            }
        });
    }

    /** Synchronous extraction. Call off the main thread. */
    @NonNull
    public BandedWaveformData extract(@NonNull Uri uri, long startMs, long endMs,
                                      int lowHz, int presHz, int highHz, boolean presenceOn,
                                      @Nullable WaveformExtractor.ProgressListener progress)
            throws Exception {
        long t0 = System.nanoTime();
        Uri decodeUri = resolveDecodeUri(context, uri);
        Probe probe = probe(decodeUri);
        long clampedStartMs = Math.max(0, startMs);
        long spanEndMs = endMs >= FULL_END / 2
                ? (probe.durationUs > 0 ? probe.durationUs / 1000 : -1) : endMs;
        long spanMs = spanEndMs - clampedStartMs;
        int chunks = (int) Math.max(1, Math.min(PARALLELISM, spanMs / PARALLEL_MIN_SPAN_MS));

        BandedWaveformData out = null;
        String mode = "pipelined";
        if (probe.seekable && spanEndMs > 0 && chunks >= 2) {
            try {
                out = extractChunked(decodeUri, probe, clampedStartMs, spanEndMs, chunks,
                        lowHz, presHz, highHz, presenceOn, progress);
                mode = "chunked×" + chunks;
            } catch (Exception e) {
                FLog.w(TAG, "Chunked band extraction failed — falling back to pipelined", e);
            }
        }
        if (out == null) {
            out = extractPipelined(decodeUri, clampedStartMs, endMs,
                    lowHz, presHz, highHz, presenceOn, progress);
        }
        FLog.d(TAG, "Band extraction " + mode + " span=" + spanMs + "ms took "
                + ((System.nanoTime() - t0) / 1_000_000) + "ms complete=" + out.complete);
        return out;
    }

    /**
     * Decode-side substitution ONLY (never a cache key): if a validated remuxed copy of a
     * file source exists, use it — identical audio stream, but indexed, so chunked mode works
     * on raw FadCam recordings once the background remux has landed. Shared with
     * {@link WaveformExtractor}, whose mid-file span extractions otherwise decode a raw fMP4
     * from byte 0 because its seek lands at the start.
     */
    @NonNull
    static Uri resolveDecodeUri(@NonNull Context context, @NonNull Uri uri) {
        try {
            if ("file".equals(uri.getScheme()) && uri.getPath() != null) {
                File src = new File(uri.getPath());
                if (src.isFile()) {
                    File remuxed = new com.fadcam.playback.FragmentedMp4Remuxer(context)
                            .getRemuxedFile(src);
                    if (remuxed.isFile() && remuxed.length() > 0) {
                        FLog.d(TAG, "Band extraction decoding from remuxed copy: "
                                + remuxed.getName());
                        return Uri.fromFile(remuxed);
                    }
                }
            }
        } catch (Exception ignored) {
            // Any surprise (no extension, etc.) just means: decode the original.
        }
        return uri;
    }

    // ── Probe ────────────────────────────────────────────────────────

    private static final class Probe {
        long durationUs;
        int sampleRate = 44100;
        boolean seekable;
    }

    /** One cheap open: duration + sample rate + a VERIFIED mid-file seek. A raw FadCam fMP4
     *  has no index — the platform extractor lands at/near 0, and a "chunk" there would decode
     *  from the file start (O(n²) across workers) — so chunked mode requires proof. */
    @NonNull
    private Probe probe(@NonNull Uri uri) throws Exception {
        Probe p = new Probe();
        MediaExtractor mx = new MediaExtractor();
        try {
            mx.setDataSource(context, uri, null);
            int track = selectAudioTrack(mx);
            if (track < 0) throw new IllegalStateException("no audio track");
            mx.selectTrack(track);
            MediaFormat fmt = mx.getTrackFormat(track);
            p.sampleRate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            p.durationUs = fmt.containsKey(MediaFormat.KEY_DURATION)
                    ? fmt.getLong(MediaFormat.KEY_DURATION) : 0;
            if (p.durationUs > 0) {
                long mid = p.durationUs / 2;
                mx.seekTo(mid, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
                long got = mx.getSampleTime();
                p.seekable = got >= 0 && Math.abs(got - mid) < 10_000_000L;
            }
        } finally {
            try { mx.release(); } catch (Exception ignored) { }
        }
        return p;
    }

    // ── Chunked mode (seekable sources) ──────────────────────────────

    private interface SampleProgress {
        void accumulated(int samples);
    }

    /** One worker's stitched-range result. */
    private static final class Range {
        FloatList[] env;
        long firstSampleUs = -1;
        boolean complete;
    }

    @NonNull
    private BandedWaveformData extractChunked(@NonNull Uri uri, @NonNull Probe probe,
                                              long startMs, long spanEndMs, int chunks,
                                              int lowHz, int presHz, int highHz,
                                              boolean presenceOn,
                                              @Nullable WaveformExtractor.ProgressListener progress)
            throws Exception {
        final int rate = probe.sampleRate;
        final long baseSample = Math.round(startMs * (double) rate / 1000.0);
        final long totalSamples = Math.round((spanEndMs - startMs) * (double) rate / 1000.0);
        // HOP-aligned chunk grid so every interior boundary lands exactly on a hop boundary.
        final long perChunk = ((totalSamples / chunks) / HOP + 1) * HOP;

        final AtomicLong accumulated = new AtomicLong();
        final float[] lastReported = {0f};
        List<Future<Range>> futures = new ArrayList<>();
        for (int i = 0; i < chunks; i++) {
            final long gs = baseSample + i * perChunk;
            final long ge = Math.min(baseSample + totalSamples, gs + perChunk);
            if (gs >= ge) break;
            final boolean isLast = ge == baseSample + totalSamples;
            futures.add(WORKERS.submit(() -> extractRange(uri, gs, ge, isLast,
                    lowHz, presHz, highHz, presenceOn, n -> {
                        if (progress == null) return;
                        float f = Math.min(1f, accumulated.addAndGet(n) / (float) totalSamples);
                        synchronized (lastReported) {
                            if (f - lastReported[0] >= 0.02f) {
                                lastReported[0] = f;
                                progress.onProgress(f);
                            }
                        }
                    })));
        }
        List<Range> parts = new ArrayList<>();
        try {
            for (Future<Range> f : futures) parts.add(f.get());
        } catch (Exception e) {
            for (Future<Range> f : futures) f.cancel(true);
            throw e;
        }

        boolean complete = true;
        for (Range r : parts) complete &= r.complete;
        float[][] rms = new float[BandedWaveformData.BAND_COUNT][];
        for (int b = 0; b < BandedWaveformData.BAND_COUNT; b++) {
            if (parts.get(0).env[b] == null) continue;
            int len = 0;
            for (Range r : parts) len += r.env[b].n;
            float[] band = new float[len];
            int off = 0;
            for (Range r : parts) {
                System.arraycopy(r.env[b].a, 0, band, off, r.env[b].n);
                off += r.env[b].n;
            }
            rms[b] = band;
        }
        float envRate = rate / (float) HOP;
        long firstUs = parts.get(0).firstSampleUs;
        long startOffsetMs = firstUs >= 0 ? firstUs / 1000 : startMs;
        long durationMs = probe.durationUs / 1000;
        int frames = rms[0] != null ? rms[0].length : 0;
        float coveredUs = frames / envRate * 1_000_000f;
        boolean coversSpan = coveredUs >= (spanEndMs - startMs) * 1000f * 0.95f;
        return new BandedWaveformData(rms, envRate, durationMs, startOffsetMs,
                new int[]{lowHz, presHz, highHz}, presenceOn, complete && coversSpan);
    }

    /**
     * Decode + filter one sample range [gateStartSample, gateEndSample) with its own
     * extractor/codec. Seeks {@link #PREROLL_MS} early and runs pre-gate samples through the
     * filters WITHOUT accumulating, so the IIR history is warm when accumulation starts.
     * Interior ranges drop a trailing partial hop (<2.9ms) — the next range starts its own
     * hop exactly at the boundary, so the stitch drift is bounded at one sub-frame per seam.
     */
    @NonNull
    private Range extractRange(@NonNull Uri uri, long gateStartSample, long gateEndSample,
                               boolean isLast, int lowHz, int presHz, int highHz,
                               boolean presenceOn, @Nullable SampleProgress progress)
            throws Exception {
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
            long gateStartUs = Math.round(gateStartSample * 1_000_000.0 / sampleRate);
            if (gateStartUs > 0) {
                extractor.seekTo(Math.max(0, gateStartUs - PREROLL_MS * 1000L),
                        MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            }
            codec = MediaCodec.createDecoderByType(mime);
            codec.configure(fmt, null, null, 0);
            codec.start();

            double midTop = presenceOn ? presHz : highHz;
            Biquad[][] chains = new Biquad[BandedWaveformData.BAND_COUNT][];
            chains[0] = Biquad.bandChain(0, sampleRate, lowHz, presHz, highHz, midTop);
            chains[1] = Biquad.bandChain(1, sampleRate, lowHz, presHz, highHz, midTop);
            chains[2] = presenceOn
                    ? Biquad.bandChain(2, sampleRate, lowHz, presHz, highHz, midTop) : null;
            chains[3] = Biquad.bandChain(3, sampleRate, lowHz, presHz, highHz, midTop);

            Range out = new Range();
            out.env = new FloatList[BandedWaveformData.BAND_COUNT];
            int sizeHint = (int) Math.min(1 << 22, (gateEndSample - gateStartSample) / HOP + 8);
            for (int b = 0; b < BandedWaveformData.BAND_COUNT; b++) {
                if (chains[b] != null) out.env[b] = new FloatList(sizeHint);
            }
            double[] sumSq = new double[BandedWaveformData.BAND_COUNT];
            int hopFill = 0;
            short[] pcm = new short[4096];
            final double samplesPerUs = sampleRate / 1_000_000.0;

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false, outputDone = false;
            boolean endedEarly = false, reachedEnd = false, eos = false;
            int drainStalls = 0;
            while (!outputDone) {
                if (Thread.currentThread().isInterrupted()) {
                    endedEarly = true;
                    break;
                }
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
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputDone = true;
                        eos = true;
                    }
                    if (out.firstSampleUs < 0 && info.size > 0) {
                        out.firstSampleUs = info.presentationTimeUs;
                    }
                    ByteBuffer ob = codec.getOutputBuffer(outIdx);
                    if (ob != null && info.size > 0) {
                        ob.order(ByteOrder.LITTLE_ENDIAN);
                        ShortBuffer sb = ob.asShortBuffer();
                        int shorts = info.size / 2;
                        if (pcm.length < shorts) pcm = new short[shorts];
                        sb.get(pcm, 0, shorts);
                        int ch = Math.max(1, channels);
                        int frames = shorts / ch;
                        long bufStart = Math.round(info.presentationTimeUs * samplesPerUs);
                        int accFrom = (int) Math.max(0, Math.min(frames,
                                gateStartSample - bufStart));
                        int accTo = (int) Math.max(0, Math.min(frames,
                                gateEndSample - bufStart));
                        float invCh = 1f / (ch * 32768f);
                        int idx = 0;
                        for (int i = 0; i < accFrom; i++) {  // pre-roll: warm filters, no emit
                            float s = 0f;
                            for (int c = 0; c < ch; c++) s += pcm[idx++];
                            float mono = s * invCh;
                            for (Biquad[] chain : chains) {
                                if (chain != null) Biquad.processChain(chain, mono);
                            }
                        }
                        for (int i = accFrom; i < accTo; i++) {
                            float s = 0f;
                            for (int c = 0; c < ch; c++) s += pcm[idx++];
                            float mono = s * invCh;
                            for (int b = 0; b < chains.length; b++) {
                                if (chains[b] == null) continue;
                                float v = Biquad.processChain(chains[b], mono);
                                sumSq[b] += (double) v * v;
                            }
                            if (++hopFill >= HOP) {
                                for (int b = 0; b < chains.length; b++) {
                                    if (chains[b] == null) continue;
                                    out.env[b].add((float) Math.sqrt(sumSq[b] / HOP));
                                    sumSq[b] = 0;
                                }
                                hopFill = 0;
                            }
                        }
                        if (progress != null && accTo > accFrom) {
                            progress.accumulated(accTo - accFrom);
                        }
                        if (bufStart + frames >= gateEndSample) reachedEnd = true;
                    }
                    codec.releaseOutputBuffer(outIdx, false);
                    drainStalls = 0;
                    if (reachedEnd) outputDone = true;
                } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat of = codec.getOutputFormat();
                    if (of.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                        channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    }
                } else if (inputDone) {
                    // ~15s of patience at the 10ms dequeue timeout — see pipelined-mode note.
                    if (++drainStalls > 1500) {
                        FLog.w(TAG, "Band decode: no EOS after input end; range stopped early");
                        endedEarly = true;
                        break;
                    }
                }
            }
            if (isLast && hopFill > 0) {  // interior seams drop the partial hop by design
                for (int b = 0; b < chains.length; b++) {
                    if (chains[b] == null) continue;
                    out.env[b].add((float) Math.sqrt(sumSq[b] / hopFill));
                }
            }
            out.complete = !endedEarly && (reachedEnd || eos);
            return out;
        } finally {
            if (codec != null) {
                try { codec.stop(); } catch (Exception ignored) { }
                try { codec.release(); } catch (Exception ignored) { }
            }
            try { extractor.release(); } catch (Exception ignored) { }
        }
    }

    // ── Pipelined mode (unseekable sources) ──────────────────────────

    /**
     * Sequential decode (the only option without an index), but the band DSP runs on
     * {@link #WORKERS} in double-buffered batches: while the four band tasks chew batch N,
     * the decode thread is already filling batch N+1. Envelope semantics are identical to
     * the original serial loop (accumulation starts at the first decoded sample).
     */
    @NonNull
    private BandedWaveformData extractPipelined(@NonNull Uri uri, long startMs, long endMs,
                                                int lowHz, int presHz, int highHz,
                                                boolean presenceOn,
                                                @Nullable WaveformExtractor.ProgressListener progress)
            throws Exception {
        long startUs = Math.max(0, startMs) * 1000L;
        long endUs = endMs >= FULL_END / 2 ? FULL_END : Math.max(startMs + 1, endMs) * 1000L;
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

            // midTop crossover: voice band ends at presence-start when presence is on, else high.
            double midTop = presenceOn ? presHz : highHz;
            int sizeHint = durationUs > 0
                    ? (int) Math.min(1 << 22, durationUs / 1_000_000L * sampleRate / HOP + 64)
                    : 1 << 14;
            BandCtx[] ctxs = new BandCtx[BandedWaveformData.BAND_COUNT];
            ctxs[0] = new BandCtx(Biquad.bandChain(0, sampleRate, lowHz, presHz, highHz, midTop), sizeHint);
            ctxs[1] = new BandCtx(Biquad.bandChain(1, sampleRate, lowHz, presHz, highHz, midTop), sizeHint);
            ctxs[2] = presenceOn
                    ? new BandCtx(Biquad.bandChain(2, sampleRate, lowHz, presHz, highHz, midTop), sizeHint)
                    : null;
            ctxs[3] = new BandCtx(Biquad.bandChain(3, sampleRate, lowHz, presHz, highHz, midTop), sizeHint);

            float[] filling = new float[BATCH_SAMPLES];
            float[] busy = new float[BATCH_SAMPLES];
            int fillN = 0;
            List<Future<?>> inflight = new ArrayList<>();
            short[] pcm = new short[4096];
            long firstSampleUs = -1;
            long progSpanUs = (endUs != FULL_END ? endUs : durationUs) - startUs;

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false, outputDone = false;
            boolean endedEarly = false;
            float lastReported = 0f;
            int drainStalls = 0;
            while (!outputDone) {
                if (Thread.currentThread().isInterrupted()) {
                    endedEarly = true;
                    break;
                }
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
                        if (f - lastReported >= 0.02f) {
                            lastReported = f;
                            progress.onProgress(Math.max(0f, f));
                        }
                    }
                    ByteBuffer ob = codec.getOutputBuffer(outIdx);
                    if (ob != null && info.size > 0) {
                        ob.order(ByteOrder.LITTLE_ENDIAN);
                        ShortBuffer sb = ob.asShortBuffer();
                        int shorts = info.size / 2;
                        if (pcm.length < shorts) pcm = new short[shorts];
                        sb.get(pcm, 0, shorts);
                        int ch = Math.max(1, channels);
                        int frames = shorts / ch;
                        float invCh = 1f / (ch * 32768f);
                        int idx = 0;
                        for (int i = 0; i < frames; i++) {
                            float s = 0f;
                            for (int c = 0; c < ch; c++) s += pcm[idx++];
                            filling[fillN++] = s * invCh;
                            if (fillN == BATCH_SAMPLES) {
                                for (Future<?> f : inflight) f.get();
                                inflight.clear();
                                float[] tmp = busy;
                                busy = filling;
                                filling = tmp;
                                final float[] batch = busy;
                                final int n = fillN;
                                for (BandCtx c : ctxs) {
                                    if (c == null) continue;
                                    final BandCtx cc = c;
                                    inflight.add(WORKERS.submit(() -> cc.process(batch, n)));
                                }
                                fillN = 0;
                            }
                        }
                    }
                    codec.releaseOutputBuffer(outIdx, false);
                    drainStalls = 0;
                    if (endUs != FULL_END && info.presentationTimeUs > endUs) outputDone = true;
                } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat of = codec.getOutputFormat();
                    if (of.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                        channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    }
                } else if (inputDone) {
                    // 200→1500 (~15s at the 10ms dequeue timeout): under editor load a long
                    // decode legitimately stalls >2s; bailing early is what left tapes 70%
                    // covered. Background thread — patience is free here.
                    if (++drainStalls > 1500) {
                        FLog.w(TAG, "Band decode: no EOS after input end; stopping at "
                                + (ctxs[0].env.n) + " frames");
                        endedEarly = true;
                        break;
                    }
                }
            }
            // Drain the pipeline: last in-flight batch, then the partial fill, then tail hops.
            for (Future<?> f : inflight) f.get();
            inflight.clear();
            if (fillN > 0) {
                for (BandCtx c : ctxs) {
                    if (c != null) c.process(filling, fillN);
                }
            }
            for (BandCtx c : ctxs) {
                if (c != null) c.flushTail();
            }

            float[][] rms = new float[BandedWaveformData.BAND_COUNT][];
            for (int b = 0; b < BandedWaveformData.BAND_COUNT; b++) {
                rms[b] = ctxs[b] == null ? null : ctxs[b].env.toArray();
            }
            float envRate = sampleRate / (float) HOP;
            long startOffsetMs = firstSampleUs >= 0 ? firstSampleUs / 1000 : Math.max(0, startMs);
            long durationMs = durationUs > 0 ? durationUs / 1000
                    : (long) (ctxs[0].env.n / envRate * 1000);
            // Completeness sanity: envelopes covering meaningfully less than the requested
            // span (< 95%) mean the decode fell short.
            boolean coversSpan = true;
            if (progSpanUs > 0) {
                float coveredUs = ctxs[0].env.n / envRate * 1_000_000f;
                coversSpan = coveredUs >= progSpanUs * 0.95f;
            }
            return new BandedWaveformData(rms, envRate, durationMs, startOffsetMs,
                    new int[]{lowHz, presHz, highHz}, presenceOn,
                    !endedEarly && coversSpan);
        } finally {
            if (codec != null) {
                try { codec.stop(); } catch (Exception ignored) { }
                try { codec.release(); } catch (Exception ignored) { }
            }
            try { extractor.release(); } catch (Exception ignored) { }
        }
    }

    // ── DSP plumbing ─────────────────────────────────────────────────

    /** One band's filter chain + envelope accumulator. In pipelined mode exactly one worker
     *  task touches an instance at a time (batches are strictly ordered by the decode thread),
     *  so the mutable hop state needs no synchronization. */
    private static final class BandCtx {
        final Biquad[] chain;
        final FloatList env;
        double sumSq;
        int hopFill;

        BandCtx(Biquad[] chain, int sizeHint) {
            this.chain = chain;
            this.env = new FloatList(sizeHint);
        }

        void process(float[] batch, int n) {
            double ss = sumSq;
            int hf = hopFill;
            for (int i = 0; i < n; i++) {
                float v = Biquad.processChain(chain, batch[i]);
                ss += (double) v * v;
                if (++hf >= HOP) {
                    env.add((float) Math.sqrt(ss / HOP));
                    ss = 0;
                    hf = 0;
                }
            }
            sumSq = ss;
            hopFill = hf;
        }

        void flushTail() {
            if (hopFill > 0) env.add((float) Math.sqrt(sumSq / hopFill));
        }
    }

    /** Growable primitive float list — the boxed ArrayList&lt;Float&gt; this replaces allocated
     *  ~4M Floats on a 45-min extraction. */
    private static final class FloatList {
        float[] a;
        int n;

        FloatList(int cap) {
            a = new float[Math.max(16, cap)];
        }

        void add(float v) {
            if (n == a.length) a = java.util.Arrays.copyOf(a, n + (n >> 1) + 16);
            a[n++] = v;
        }

        float[] toArray() {
            return java.util.Arrays.copyOf(a, n);
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

    // ── Disk cache ───────────────────────────────────────────────────

    @NonNull
    private File cacheFile(@NonNull Uri uri, long startMs, long endMs,
                           int lowHz, int presHz, int highHz, boolean presenceOn) {
        File dir = com.fadcam.ui.faditor.util.DurableCache.dir(context, "waveform_bands");
        String span = (startMs <= 0 && endMs >= FULL_END / 2)
                ? "full" : (Math.max(0, startMs) + "-" + endMs);
        String key = Integer.toHexString(uri.toString().hashCode()) + "_" + span
                + "_" + lowHz + "-" + presHz + "-" + highHz + (presenceOn ? "p" : "");
        return new File(dir, key + ".bin");
    }

    @Nullable
    private BandedWaveformData readCache(@NonNull Uri uri, long startMs, long endMs,
                                         int lowHz, int presHz, int highHz, boolean presenceOn) {
        File f = cacheFile(uri, startMs, endMs, lowHz, presHz, highHz, presenceOn);
        if (!f.exists()) return null;
        try (DataInputStream in = new DataInputStream(
                new java.io.BufferedInputStream(new FileInputStream(f)))) {
            if (in.readInt() != CACHE_VERSION) return null;
            long durationMs = in.readLong();
            long startOffsetMs = in.readLong();
            float envRate = in.readFloat();
            int low = in.readInt(), pres = in.readInt(), high = in.readInt();
            boolean pOn = in.readBoolean();
            float[][] rms = new float[BandedWaveformData.BAND_COUNT][];
            for (int b = 0; b < BandedWaveformData.BAND_COUNT; b++) {
                int len = in.readInt(); // -1 == null band
                if (len < 0) { rms[b] = null; continue; }
                float[] band = new float[len];
                for (int i = 0; i < len; i++) band[i] = in.readFloat();
                rms[b] = band;
            }
            // Coverage self-heal (round 2, 2026-07-16): entries written BEFORE the
            // incomplete-extraction guard may hold only a prefix of the span. If the
            // envelopes cover <95% of the requested range, discard so it re-extracts —
            // otherwise the tape flatlines forever with no analyzing indicator.
            int frames = 0;
            for (float[] band : rms) { if (band != null) { frames = band.length; break; } }
            long spanMs = (endMs >= FULL_END / 2 ? Math.max(1, durationMs) : endMs) - startMs;
            if (spanMs > 0 && envRate > 0) {
                float coveredMs = frames / envRate * 1000f;
                if (coveredMs < spanMs * 0.95f) {
                    FLog.w(TAG, "Band cache covers " + (int) coveredMs + "/" + spanMs
                            + "ms — stale partial entry, deleting for re-extract");
                    try { in.close(); } catch (Exception ignored) { }
                    f.delete();
                    return null;
                }
            }
            return new BandedWaveformData(rms, envRate, durationMs, startOffsetMs,
                    new int[]{low, pres, high}, pOn);
        } catch (Exception e) {
            FLog.w(TAG, "Band cache read failed; will re-extract", e);
            return null;
        }
    }

    private void writeCache(@NonNull Uri uri, long startMs, long endMs,
                            int lowHz, int presHz, int highHz, boolean presenceOn,
                            @NonNull BandedWaveformData data) {
        File f = cacheFile(uri, startMs, endMs, lowHz, presHz, highHz, presenceOn);
        try (DataOutputStream out = new DataOutputStream(new FileOutputStream(f))) {
            out.writeInt(CACHE_VERSION);
            out.writeLong(data.durationMs);
            out.writeLong(data.startOffsetMs);
            out.writeFloat(data.envRate);
            out.writeInt(data.crossoversHz[0]);
            out.writeInt(data.crossoversHz[1]);
            out.writeInt(data.crossoversHz[2]);
            out.writeBoolean(data.presenceOn);
            for (int b = 0; b < BandedWaveformData.BAND_COUNT; b++) {
                float[] band = data.band(b);
                if (band == null) { out.writeInt(-1); continue; }
                out.writeInt(band.length);
                for (float v : band) out.writeFloat(v);
            }
        } catch (Exception e) {
            FLog.w(TAG, "Band cache write failed", e);
        }
    }
}
