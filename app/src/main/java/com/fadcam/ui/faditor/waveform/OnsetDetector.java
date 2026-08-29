package com.fadcam.ui.faditor.waveform;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Finds the instants where sound STARTS — the consonant edges a spoken word begins on.
 *
 * <p><b>Why this exists.</b> Fixing a sloppy transcript means dragging a word to where it
 * actually happens. Dragging it to a pixel is guesswork; dragging it to a detected onset is
 * exact. This turns "aim carefully at a waveform" into "let go near it and it snaps", which
 * is the difference between the surgical tool the editor already has and something usable on
 * a transcript with hundreds of misplaced words.</p>
 *
 * <p><b>Pure, and deliberately so.</b> No {@code android.*} imports: it reads samples through
 * {@link Samples}, exactly as {@code CaptionFit} takes a text {@code Measurer}, so the JVM
 * harness in {@code tools/jvm-harness} can exercise it against synthetic signals with known
 * answers. Detection heuristics that cannot be tested get tuned by superstition.</p>
 *
 * <h3>Method</h3>
 * A speech onset is a fast RISE in high-frequency energy — the burst of a /t/ or /k/, the
 * friction of an /s/. Three steps, one pass each, all O(n):
 * <ol>
 *   <li><b>Pre-emphasis.</b> {@code y[n] = x[n] − 0.97·x[n−1]}, a one-multiply high-pass that
 *       lifts consonants over vowels. Without it the detector locks onto vowel peaks, which
 *       arrive a syllable AFTER the word starts — the error would be consistent, invisible,
 *       and wrong in the direction that matters most.</li>
 *   <li><b>Energy envelope</b> at {@value #HOP_MS} ms resolution, then the RECTIFIED first
 *       difference: rises count, falls do not. A word ending is not a word starting.</li>
 *   <li><b>Adaptive peak-picking</b> against a local median (see {@link #MEDIAN_WINDOW}). A
 *       fixed threshold fails on exactly the material this is for: a quiet verse and a loud
 *       chorus in one file need different bars, and a global one either floods the quiet part
 *       with noise or finds nothing in it.</li>
 * </ol>
 *
 * <p>Cost is a few multiply-adds per sample: ~9.2M samples for a 7-minute song at
 * {@link PcmSidecar#RATE}, well under a second, once per source. Output is a few thousand
 * longs — cheap to hold for the session.</p>
 */
public final class OnsetDetector {

    /** Random access to mono samples in −1..1. {@link PcmSidecar.Handle} satisfies this. */
    public interface Samples {
        int count();
        float at(int index);
    }

    /** Envelope resolution. 10 ms is finer than any onset a human can place by hand. */
    private static final int HOP_MS = 10;

    /**
     * Minimum gap between reported onsets. Two detections closer than this are one event —
     * a plosive commonly produces a burst and a voicing edge a few ms apart, and reporting
     * both would make a snap target ambiguous exactly where precision matters.
     */
    private static final int MIN_GAP_MS = 45;

    /**
     * Local-median half-window, in hops. ±40 hops is ±400 ms — long enough to average over a
     * word or two so a single loud consonant does not raise the bar against its own
     * neighbours, short enough to track a verse-to-chorus level change.
     */
    private static final int MEDIAN_WINDOW = 40;

    /**
     * A rise must exceed {@code median * THRESHOLD_FACTOR} to count. Tuned toward
     * over-detection on purpose: a spurious onset costs the user a snap 20 ms off, a missed
     * one costs them the manual placement this feature exists to remove.
     */
    private static final float THRESHOLD_FACTOR = 2.2f;

    /**
     * Absolute floor, so digital silence and dither do not generate onsets. Any real speech
     * transient clears this by orders of magnitude.
     */
    private static final float NOISE_FLOOR = 1e-5f;

    private OnsetDetector() {}

    // NOTE: there is deliberately NO `of(PcmSidecar.Handle)` adapter here. Referencing
    // PcmSidecar from this file would drag android.media onto the compile path and this class
    // could no longer be built by the JVM harness — which is the entire reason it takes a
    // callback. The coupling lives on the Android side instead: PcmSidecar.Handle implements
    // Samples. Dependencies point from the impure class to the pure one, never back.

    /**
     * Detect onsets across the whole source.
     *
     * @param samples    the audio
     * @param sampleRate frames per second
     * @return onset times in ms, ascending, never null (empty for silence or too-short input)
     */
    @NonNull
    public static long[] detect(@NonNull Samples samples, int sampleRate) {
        final int n = samples.count();
        final int hop = Math.max(1, sampleRate * HOP_MS / 1000);
        if (n < hop * 4 || sampleRate <= 0) return new long[0];

        // ── 1+2. Pre-emphasised energy per hop, in ONE pass. ────────────────────────────
        final int hops = n / hop;
        final float[] energy = new float[hops];
        float prev = 0f;
        int idx = 0;
        for (int h = 0; h < hops; h++) {
            float sum = 0f;
            final int end = (h + 1) * hop;
            for (; idx < end; idx++) {
                float x = samples.at(idx);
                float y = x - 0.97f * prev;   // pre-emphasis
                prev = x;
                sum += y * y;                 // energy, not amplitude: squaring favours
                                              // transients over steady tone
            }
            energy[h] = sum / hop;
        }

        // Rectified first difference — rises only.
        final float[] flux = new float[hops];
        for (int h = 1; h < hops; h++) {
            float d = energy[h] - energy[h - 1];
            flux[h] = d > 0f ? d : 0f;
        }

        // ── 3. Adaptive peak-picking. ──────────────────────────────────────────────────
        final List<Long> out = new ArrayList<>();
        final int minGapHops = Math.max(1, MIN_GAP_MS / HOP_MS);
        int lastAccepted = -minGapHops - 1;
        final float[] window = new float[MEDIAN_WINDOW * 2 + 1];

        for (int h = 1; h < hops - 1; h++) {
            float v = flux[h];
            if (v < NOISE_FLOOR) continue;
            // Local maximum. Without this every hop on a rising slope fires and one onset
            // becomes a smear.
            if (v < flux[h - 1] || v < flux[h + 1]) continue;
            if (h - lastAccepted < minGapHops) continue;

            int lo = Math.max(0, h - MEDIAN_WINDOW);
            int hi = Math.min(hops - 1, h + MEDIAN_WINDOW);
            int count = hi - lo + 1;
            System.arraycopy(flux, lo, window, 0, count);
            float median = medianOf(window, count);
            float threshold = Math.max(NOISE_FLOOR, median * THRESHOLD_FACTOR);
            if (v <= threshold) continue;

            // Report the START of the rise, not its peak. The peak is where the transient is
            // LOUDEST; the word begins where it starts climbing, typically 1-3 hops earlier.
            // Snapping to the peak would place every word consistently late — the exact
            // failure this detector exists to prevent.
            int startHop = h;
            while (startHop > 0 && flux[startHop - 1] > threshold * 0.25f
                    && startHop > h - 6) {
                startHop--;
            }
            out.add((long) startHop * HOP_MS);
            lastAccepted = h;
        }

        long[] result = new long[out.size()];
        for (int i = 0; i < result.length; i++) result[i] = out.get(i);
        return result;
    }

    /**
     * Median of {@code buf[0..count)}. Partial selection sort to the midpoint — the window is
     * 81 entries and this runs once per candidate peak, so a full sort would be wasted work
     * on the half of the array nobody reads. Sorts a scratch copy in place; the caller's
     * buffer is scratch by contract.
     */
    private static float medianOf(@NonNull float[] buf, int count) {
        int mid = count / 2;
        for (int i = 0; i <= mid; i++) {
            int min = i;
            for (int j = i + 1; j < count; j++) if (buf[j] < buf[min]) min = j;
            float t = buf[i]; buf[i] = buf[min]; buf[min] = t;
        }
        return buf[mid];
    }

    // ── Snapping ────────────────────────────────────────────────────────────────────────

    /**
     * The onset nearest {@code ms}, or {@code ms} unchanged when none is within
     * {@code toleranceMs}.
     *
     * <p>Returning the input unchanged rather than the nearest onset at any distance is the
     * whole contract: a magnet that always fires takes control away from the user, and the
     * one time they genuinely mean to place a word in silence, a snap that drags it somewhere
     * else reads as the app fighting them.</p>
     *
     * @param onsets ascending, as returned by {@link #detect}
     */
    public static long snap(@NonNull long[] onsets, long ms, long toleranceMs) {
        if (onsets.length == 0 || toleranceMs <= 0) return ms;
        int lo = 0, hi = onsets.length - 1;
        while (lo < hi) {                       // binary search: this runs per drag frame
            int mid = (lo + hi) >>> 1;
            if (onsets[mid] < ms) lo = mid + 1; else hi = mid;
        }
        long best = onsets[lo];
        long bestDist = Math.abs(best - ms);
        if (lo > 0) {                           // the predecessor may be nearer
            long d = Math.abs(onsets[lo - 1] - ms);
            if (d < bestDist) { best = onsets[lo - 1]; bestDist = d; }
        }
        return bestDist <= toleranceMs ? best : ms;
    }
}
