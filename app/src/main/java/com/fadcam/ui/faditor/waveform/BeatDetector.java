package com.fadcam.ui.faditor.waveform;

import androidx.annotation.NonNull;

/**
 * Offline beat / onset detection over an amplitude envelope — SPEC_AUDIO_UX_V1 row {@code D6}.
 *
 * <p><b>Why this exists.</b> Snapping cuts to music is the one thing every social editor has and
 * ours does not. Theirs are black boxes: CapCut finds beats and you take what it gives you. The
 * point of doing it here is that the result is an ORDINARY, EDITABLE MARKER LIST — you can drag
 * a beat that landed early, delete one it invented, and add the one it missed. A detector that
 * is right 90% of the time and correctable beats one that is right 95% of the time and final.</p>
 *
 * <p><b>Pure math, no Android, no I/O.</b> Input is an amplitude envelope that has already been
 * extracted (see {@link WaveformExtractor}), which means this class can be unit-tested off-device
 * by the jvm-harness the way {@code AudioClipEnvelopeTest} tests the envelope. It deliberately
 * does NOT reach into {@link Fft} — that file is being read by other work, and amplitude-domain
 * onset detection is enough for the job. A spectral (per-band) version can be layered on later
 * by feeding this the same shape from {@link BandWaveformExtractor}.</p>
 *
 * <p><b>The method,</b> which is the standard onset-detection pipeline and is named here so the
 * next reader does not have to reverse-engineer it:</p>
 * <ol>
 *   <li><b>Smooth</b> the envelope a little, so single-frame noise is not an onset.</li>
 *   <li><b>Rectified first difference</b> ("flux"): how much LOUDER this frame got. Drops are
 *       discarded — a note ending is not a beat.</li>
 *   <li><b>Adaptive threshold</b>: a local mean over a window, times a sensitivity factor. Fixed
 *       thresholds fail the moment a track has a quiet intro and a loud chorus, which is most
 *       music.</li>
 *   <li><b>Peak-pick</b> local maxima above that threshold, enforcing a minimum gap so one
 *       drum hit does not register three times.</li>
 *   <li><b>Tempo</b> by autocorrelating the flux against candidate periods; reported for the UI
 *       but never used to move a detected beat — an inferred grid overriding a measured onset is
 *       how these tools end up confidently wrong.</li>
 * </ol>
 */
public final class BeatDetector {

    private BeatDetector() {}

    /** Fastest plausible beat spacing. 300 BPM = 200ms; anything closer is one hit, not two. */
    private static final long MIN_BEAT_GAP_MS = 200L;
    /** Tempo search range, in BPM. Covers ballads through drum-and-bass. */
    private static final int MIN_BPM = 60, MAX_BPM = 200;
    /** Window for the adaptive threshold, in frames of envelope. */
    private static final int THRESHOLD_WINDOW = 24;

    /** What {@link #detect} found. */
    public static final class Result {
        /** Beat positions in ms, ascending. Never null; empty when nothing was found. */
        @NonNull public final long[] beatsMs;
        /** Best tempo estimate in BPM, or 0 when there was not enough signal to guess. */
        public final float bpm;

        Result(@NonNull long[] beatsMs, float bpm) {
            this.beatsMs = beatsMs;
            this.bpm = bpm;
        }
    }

    /**
     * Detect beats in an amplitude envelope.
     *
     * @param envelope         per-frame amplitude, any positive scale (0..255 peaks are fine).
     * @param framesPerSecond  how many envelope entries cover one second.
     * @param sensitivity      1.0 = balanced. Lower finds more beats, higher finds fewer. The
     *                         UI should expose this, because "too many" and "too few" are the
     *                         only two complaints anyone ever has about beat detection.
     */
    @NonNull
    public static Result detect(@NonNull int[] envelope, double framesPerSecond,
                                float sensitivity) {
        if (envelope.length < 8 || framesPerSecond <= 0) {
            return new Result(new long[0], 0f);
        }
        final int n = envelope.length;
        final double msPerFrame = 1000.0 / framesPerSecond;

        // 1. Smooth (3-tap moving average). Cheap, and enough to stop a single spiky frame
        //    from reading as an onset.
        float[] smooth = new float[n];
        for (int i = 0; i < n; i++) {
            int a = envelope[Math.max(0, i - 1)];
            int b = envelope[i];
            int c = envelope[Math.min(n - 1, i + 1)];
            smooth[i] = (a + b + c) / 3f;
        }

        // 2. Rectified first difference. Only INCREASES matter: a beat is energy arriving.
        float[] flux = new float[n];
        for (int i = 1; i < n; i++) {
            float d = smooth[i] - smooth[i - 1];
            flux[i] = d > 0 ? d : 0f;
        }

        // 3. Adaptive threshold: local mean over a window, scaled. A fixed threshold breaks on
        //    any track with a quiet intro, which is most of them.
        float[] threshold = new float[n];
        for (int i = 0; i < n; i++) {
            int lo = Math.max(0, i - THRESHOLD_WINDOW / 2);
            int hi = Math.min(n - 1, i + THRESHOLD_WINDOW / 2);
            float sum = 0f;
            for (int j = lo; j <= hi; j++) sum += flux[j];
            float mean = sum / (hi - lo + 1);
            threshold[i] = mean * (1.5f * Math.max(0.05f, sensitivity)) + 0.5f;
        }

        // 4. Peak-pick: a local maximum above threshold, no closer than MIN_BEAT_GAP_MS to the
        //    beat before it. Without the gap a single kick registers as a little cluster.
        int minGapFrames = (int) Math.max(1, Math.round(MIN_BEAT_GAP_MS / msPerFrame));
        long[] scratch = new long[n];
        int count = 0;
        int lastIdx = -minGapFrames - 1;
        for (int i = 1; i < n - 1; i++) {
            if (flux[i] < threshold[i]) continue;
            if (flux[i] < flux[i - 1] || flux[i] < flux[i + 1]) continue;
            if (i - lastIdx < minGapFrames) {
                // Too close to the previous beat. Keep whichever is STRONGER, so a soft
                // pre-echo cannot displace the real hit.
                if (count > 0 && flux[i] > flux[lastIdx]) {
                    scratch[count - 1] = Math.round(i * msPerFrame);
                    lastIdx = i;
                }
                continue;
            }
            scratch[count++] = Math.round(i * msPerFrame);
            lastIdx = i;
        }

        long[] beats = new long[count];
        System.arraycopy(scratch, 0, beats, 0, count);
        return new Result(beats, estimateBpm(flux, msPerFrame));
    }

    /**
     * Tempo by autocorrelating the flux against each candidate period.
     *
     * <p>Reported to the UI, never used to MOVE a beat. An inferred grid that overrides a
     * measured onset is precisely how a beat tool becomes confidently wrong on the one bar
     * where the drummer pushed — and the user has no way to tell it it is wrong.</p>
     */
    private static float estimateBpm(@NonNull float[] flux, double msPerFrame) {
        float best = 0f;
        int bestLag = 0;
        for (int bpm = MIN_BPM; bpm <= MAX_BPM; bpm++) {
            int lag = (int) Math.round((60000.0 / bpm) / msPerFrame);
            if (lag < 2 || lag >= flux.length) continue;
            float sum = 0f;
            for (int i = lag; i < flux.length; i++) sum += flux[i] * flux[i - lag];
            // Normalise by overlap length, or long lags are penalised for having fewer terms.
            float score = sum / (flux.length - lag);
            if (score > best) { best = score; bestLag = lag; }
        }
        if (bestLag <= 0 || best <= 0f) return 0f;
        return (float) (60000.0 / (bestLag * msPerFrame));
    }

    /**
     * Snap {@code timeMs} to the nearest beat within {@code toleranceMs}, or return it unchanged.
     *
     * <p>Returning the input on a miss rather than the nearest beat regardless is deliberate:
     * snapping that reaches arbitrarily far is snapping the user cannot escape, and there is no
     * modifier key on a phone to hold down to defeat it.</p>
     */
    public static long snap(@NonNull long[] beatsMs, long timeMs, long toleranceMs) {
        if (beatsMs.length == 0 || toleranceMs <= 0) return timeMs;
        int lo = 0, hi = beatsMs.length - 1, best = 0;
        long bestDist = Long.MAX_VALUE;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            long d = Math.abs(beatsMs[mid] - timeMs);
            if (d < bestDist) { bestDist = d; best = mid; }
            if (beatsMs[mid] < timeMs) lo = mid + 1; else hi = mid - 1;
        }
        return bestDist <= toleranceMs ? beatsMs[best] : timeMs;
    }
}
