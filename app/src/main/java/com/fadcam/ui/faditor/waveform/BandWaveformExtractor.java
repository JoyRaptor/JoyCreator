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

/**
 * Decodes an audio (or video's-audio) source to PCM via MediaCodec and, in the SAME streaming
 * pass, splits it into the four tape bands with cascaded {@link Biquad} IIR filters, reducing
 * each band to a per-hop RMS envelope ({@link BandedWaveformData}). This is the Android port of
 * the prototype's WebAudio {@code renderBand} + {@code rmsEnvelope}; it stays O(n) and flat in
 * memory (a few accumulators + the growing envelope lists), so a long track costs a one-time
 * few-seconds analysis, not a big allocation.
 *
 * <p>Results are disk-cached keyed by source + span + crossovers, so a project reopens instantly.
 * The decode skeleton intentionally mirrors {@link WaveformExtractor} (a later cleanup could share
 * a common PCM streamer); kept separate here so the visualizer's FFT path stays untouched.</p>
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

            // midTop crossover: voice band ends at presence-start when presence is on, else at high.
            double midTop = presenceOn ? presHz : highHz;
            // One independent cascaded chain per band (presence chain null when off).
            Biquad[][] chains = new Biquad[BandedWaveformData.BAND_COUNT][];
            chains[0] = Biquad.bandChain(0, sampleRate, lowHz, presHz, highHz, midTop);
            chains[1] = Biquad.bandChain(1, sampleRate, lowHz, presHz, highHz, midTop);
            chains[2] = presenceOn
                    ? Biquad.bandChain(2, sampleRate, lowHz, presHz, highHz, midTop) : null;
            chains[3] = Biquad.bandChain(3, sampleRate, lowHz, presHz, highHz, midTop);

            List<Float>[] envLists = newFloatLists(BandedWaveformData.BAND_COUNT);
            double[] sumSq = new double[BandedWaveformData.BAND_COUNT];
            int hopFill = 0;
            long firstSampleUs = -1;
            long progSpanUs = (endUs != FULL_END ? endUs : durationUs) - startUs;

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false, outputDone = false;
            boolean endedEarly = false;
            float lastReported = 0f;
            int drainStalls = 0;
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
                        int ch = Math.max(1, channels);
                        for (int i = 0; i + ch <= shorts; i += ch) {
                            float s = 0f;
                            for (int c = 0; c < ch; c++) s += sb.get(i + c);
                            float mono = (s / ch) / 32768f;
                            for (int b = 0; b < BandedWaveformData.BAND_COUNT; b++) {
                                if (chains[b] == null) continue;
                                float v = Biquad.processChain(chains[b], mono);
                                sumSq[b] += (double) v * v;
                            }
                            if (++hopFill >= HOP) {
                                for (int b = 0; b < BandedWaveformData.BAND_COUNT; b++) {
                                    if (chains[b] == null) continue;
                                    envLists[b].add((float) Math.sqrt(sumSq[b] / HOP));
                                    sumSq[b] = 0;
                                }
                                hopFill = 0;
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
                    if (++drainStalls > 200) {
                        FLog.w(TAG, "Band decode: no EOS after input end; stopping at "
                                + envLists[0].size() + " frames");
                        endedEarly = true;
                        break;
                    }
                }
            }
            // Flush a partial trailing hop so short spans still yield a final frame.
            if (hopFill > 0) {
                for (int b = 0; b < BandedWaveformData.BAND_COUNT; b++) {
                    if (chains[b] == null) continue;
                    envLists[b].add((float) Math.sqrt(sumSq[b] / hopFill));
                }
            }

            float[][] rms = new float[BandedWaveformData.BAND_COUNT][];
            for (int b = 0; b < BandedWaveformData.BAND_COUNT; b++) {
                rms[b] = chains[b] == null ? null : toArray(envLists[b]);
            }
            float envRate = sampleRate / (float) HOP;
            long startOffsetMs = firstSampleUs >= 0 ? firstSampleUs / 1000 : Math.max(0, startMs);
            long durationMs = durationUs > 0 ? durationUs / 1000
                    : (long) (envLists[0].size() / envRate * 1000);
            // Completeness sanity: even without the explicit early-break, envelopes covering
            // meaningfully less than the requested span (< 95%) mean the decode fell short.
            boolean coversSpan = true;
            if (progSpanUs > 0) {
                float coveredUs = envLists[0].size() / envRate * 1_000_000f;
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

    @SuppressWarnings("unchecked")
    private static List<Float>[] newFloatLists(int n) {
        List<Float>[] a = new List[n];
        for (int i = 0; i < n; i++) a[i] = new ArrayList<>();
        return a;
    }

    @NonNull
    private static float[] toArray(@NonNull List<Float> list) {
        float[] a = new float[list.size()];
        for (int i = 0; i < a.length; i++) a[i] = list.get(i);
        return a;
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
        File dir = new File(context.getCacheDir(), "waveform_bands");
        if (!dir.exists()) dir.mkdirs();
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
        try (DataInputStream in = new DataInputStream(new FileInputStream(f))) {
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
