package com.fadcam.visualizer;

import androidx.annotation.NonNull;

import com.fadcam.ui.faditor.model.WaveformData;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Recording-side live PCM sampler (Visualizer Studio spec §2 Decision 3). Taps the SAME 16-bit
 * mono PCM buffer that is already handed to the recording's audio encoder (see
 * {@code ScreenRecordingPipeline#queueAudioData}) — never a second {@code AudioRecord} — and
 * maintains a small rolling window of per-bucket peak amplitude plus (only when the armed style
 * needs it) a coarse FFT band array, exposed in the exact shape
 * {@link com.fadcam.ui.faditor.model.WaveformData} wants so the existing
 * {@link com.fadcam.ui.faditor.waveform.WaveformStyleRenderer} can render it live and unchanged.
 *
 * <p><b>Concurrency:</b> single-writer ({@link #onPcm} on the audio thread) / single-reader
 * ({@link #snapshot} on the compositing/GL thread). Publication is lock-free: every bucket's
 * arrays are fully written <em>before</em> the volatile {@link #committed} counter is bumped, and
 * the reader only reads buckets with index {@code < committed}, so the volatile
 * write→read edge establishes happens-before for the array contents. No allocation per audio
 * chunk; the steady-state snapshot reuses scratch arrays (no per-frame allocation either).</p>
 */
public final class LiveAmplitudeSampler {

    /** Rolling bucket capacity of the ring (buckets older than this are overwritten). */
    private static final int RING = 512;
    /** Buckets exposed per snapshot window — comfortably covers any tier-1 bandCount + look-back. */
    private static final int WINDOW = 256;
    /** Coarse FFT size — cheap (radix-2, ~one per bucket ≈ 60/s). */
    private static final int FFT_N = 64;
    /** Usable magnitude bins (Nyquist half). */
    private static final int BANDS = FFT_N / 2;

    private final long bucketMs;
    private final boolean computeSpectrum;
    private final int bucketSamples; // PCM samples per committed bucket
    private final int decim;         // sample stride feeding the FFT window

    // ── Ring storage (audio thread writes, GL thread reads) ──────────────────
    private final float[] ampRing = new float[RING];
    private final float[][] bandRing; // [RING][BANDS] or null when spectrum is off
    private volatile long committed = 0L; // total buckets committed (monotonic) — the publish edge

    // ── Writer-only accumulation state (audio thread) ────────────────────────
    private int accCount = 0;
    private float accPeak = 0f;
    private final float[] fftWindow; // last FFT_N decimated samples (ring-filled)
    private int fftFill = 0;
    private int decimCounter = 0;
    private final float[] fftRe, fftIm; // FFT scratch, reused every bucket

    // ── Reader scratch (GL thread only), reused → no steady-state allocation ─
    private final float[] ampWindow = new float[WINDOW];
    private final float[][] bandWindow; // [WINDOW][BANDS] or empty
    private final float[][] emptySpectrum = new float[0][];
    private long snapshotAtMs = 0L;

    public LiveAmplitudeSampler(int sampleRate, long bucketMs, boolean computeSpectrum) {
        int sr = Math.max(8000, sampleRate);
        this.bucketMs = Math.max(4L, bucketMs);
        this.computeSpectrum = computeSpectrum;
        this.bucketSamples = Math.max(1, (int) (sr * this.bucketMs / 1000L));
        this.decim = Math.max(1, this.bucketSamples / FFT_N);
        this.bandRing = computeSpectrum ? new float[RING][BANDS] : null;
        this.bandWindow = computeSpectrum ? new float[WINDOW][BANDS] : null;
        this.fftWindow = computeSpectrum ? new float[FFT_N] : null;
        this.fftRe = computeSpectrum ? new float[FFT_N] : null;
        this.fftIm = computeSpectrum ? new float[FFT_N] : null;
    }

    public long bucketMs() {
        return bucketMs;
    }

    // ── Writer (audio thread) ────────────────────────────────────────────────

    /**
     * Feed one PCM chunk (16-bit little-endian mono). No allocation. The buffer is read through a
     * duplicate so the caller's position/limit (and any other tap) are undisturbed.
     */
    public void onPcm(@NonNull ByteBuffer pcm, int size) {
        if (size < 2) return;
        ByteBuffer b = pcm.duplicate();
        b.order(ByteOrder.LITTLE_ENDIAN);
        b.position(0);
        int samples = size / 2;
        for (int i = 0; i < samples; i++) {
            float v = b.getShort() / 32768f;
            float a = v < 0f ? -v : v;
            if (a > accPeak) accPeak = a;
            if (computeSpectrum) {
                if (++decimCounter >= decim) {
                    decimCounter = 0;
                    fftWindow[fftFill % FFT_N] = v;
                    fftFill++;
                }
            }
            if (++accCount >= bucketSamples) {
                commitBucket();
            }
        }
    }

    /** Publishes the accumulated bucket, then resets the accumulator. */
    private void commitBucket() {
        int idx = (int) (committed % RING);
        ampRing[idx] = accPeak;
        if (computeSpectrum) {
            computeBandsInto(bandRing[idx]);
        }
        committed++; // volatile write — publishes ampRing[idx]/bandRing[idx] to the reader
        accPeak = 0f;
        accCount = 0;
    }

    /** Coarse magnitude spectrum of the current decimated window into {@code out} (0..1). */
    private void computeBandsInto(@NonNull float[] out) {
        // Copy the ring-filled window in order (oldest → newest) into the FFT input.
        int base = fftFill; // next write slot; window is [base-FFT_N .. base)
        for (int k = 0; k < FFT_N; k++) {
            int src = (base + k) % FFT_N; // oldest-first
            fftRe[k] = fftWindow[src];
            fftImZero(k);
        }
        fft(fftRe, fftIm);
        for (int k = 0; k < BANDS; k++) {
            float mag = (float) Math.sqrt(fftRe[k] * fftRe[k] + fftIm[k] * fftIm[k]);
            float n = 2f * mag / FFT_N;
            out[k] = n > 1f ? 1f : n;
        }
    }

    private void fftImZero(int k) {
        fftIm[k] = 0f;
    }

    /** In-place iterative radix-2 Cooley–Tukey FFT (FFT_N is a power of two). */
    private static void fft(@NonNull float[] re, @NonNull float[] im) {
        int n = re.length;
        // Bit-reversal permutation.
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) {
                float tr = re[i]; re[i] = re[j]; re[j] = tr;
                float ti = im[i]; im[i] = im[j]; im[j] = ti;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double ang = -2.0 * Math.PI / len;
            float wr = (float) Math.cos(ang);
            float wi = (float) Math.sin(ang);
            for (int i = 0; i < n; i += len) {
                float curR = 1f, curI = 0f;
                for (int k = 0; k < (len >> 1); k++) {
                    int a = i + k;
                    int c = i + k + (len >> 1);
                    float tr = curR * re[c] - curI * im[c];
                    float ti = curR * im[c] + curI * re[c];
                    re[c] = re[a] - tr;
                    im[c] = im[a] - ti;
                    re[a] += tr;
                    im[a] += ti;
                    float nr = curR * wr - curI * wi;
                    curI = curR * wi + curI * wr;
                    curR = nr;
                }
            }
        }
    }

    // ── Reader (GL / compositing thread) ─────────────────────────────────────

    /** Elapsed audio time (ms) of the most recent {@link #snapshot}; use as {@code atMs}. */
    public long snapshotAtMs() {
        return snapshotAtMs;
    }

    /**
     * Build a {@link WaveformData} view over the current rolling window ending at the newest
     * committed bucket, with {@code startOffsetMs} aligned so the renderer's {@code atMs} (=
     * {@link #snapshotAtMs()}) lands on the newest bucket. Reuses scratch arrays in steady state;
     * allocates only during the first {@code WINDOW} buckets of warm-up.
     */
    @NonNull
    public WaveformData snapshot() {
        long c = committed; // volatile read — happens-before the array writes for buckets < c
        int len = (int) Math.min(c, (long) WINDOW);
        snapshotAtMs = c * bucketMs;
        if (len <= 0) {
            return new WaveformData(new float[]{0f}, emptySpectrum, Math.max(1, snapshotAtMs),
                    0L, bucketMs);
        }
        long firstBucket = c - len; // ≥ 0
        float[] amp;
        float[][] band;
        if (len == WINDOW) {
            amp = ampWindow;
            band = computeSpectrum ? bandWindow : emptySpectrum;
        } else {
            amp = new float[len];
            band = computeSpectrum ? new float[len][BANDS] : emptySpectrum;
        }
        for (int i = 0; i < len; i++) {
            int idx = (int) ((firstBucket + i) % RING);
            amp[i] = ampRing[idx];
            if (computeSpectrum) {
                System.arraycopy(bandRing[idx], 0, band[i], 0, BANDS);
            }
        }
        long startOffsetMs = firstBucket * bucketMs;
        long durationMs = Math.max(1, snapshotAtMs);
        return new WaveformData(amp, band, durationMs, startOffsetMs, bucketMs);
    }
}
