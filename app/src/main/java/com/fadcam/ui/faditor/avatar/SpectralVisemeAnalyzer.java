package com.fadcam.ui.faditor.avatar;

import androidx.annotation.NonNull;

/**
 * A3 v2 (PLAN_AVATAR_STUDIO): cheap spectral viseme classifier — the "5-8
 * viseme classes" tier that SHAPES the mouth while {@link AudioLevelViseme}
 * keeps driving the OPENING (see that class's javadoc). Pure Java, zero
 * Android imports (JVM-harness testable, mirrors the other math-only avatar
 * classes like {@link LifeSignals}).
 *
 * <h3>Heuristic (deliberately cheap — no TarsosDSP, no neural model)</h3>
 * Every {@link #FFT_SIZE}-sample (1024 @ 16kHz = 64ms) non-overlapping window
 * gets a Hann-windowed real FFT (radix-2, implemented below); the magnitude
 * spectrum is summed into four coarse bands that stand in for formant
 * regions without ever tracking a literal formant:
 * <ul>
 *   <li>{@code veryLow} ~250-500Hz — F1+F2 both collapse low for ROUNDED
 *       vowels (OO/"boot") — rounding acts like a low-pass filter.</li>
 *   <li>{@code low} ~600-1100Hz — F1 for OPEN vowels (AA/"father").</li>
 *   <li>{@code mid} ~1500-2800Hz — F2 for FRONT/spread vowels (EE/"see").</li>
 *   <li>{@code hi} ~3500-7500Hz — broadband turbulence noise of fricatives/
 *       sibilants (FRIC/"s","sh","f").</li>
 * </ul>
 * Overall frame loudness (time-domain RMS dB) gates two more classes below
 * the vowel bands: {@code REST} (silence floor) and {@code CLOSURE} (quiet
 * but voiced — bilabial/nasal closures like "m","b","p", too quiet to be a
 * vowel but not silent). This is 6 classes total, inside the plan's 5-8
 * budget, and each is a distinct MOUTH SHAPE an author can draw a sprite
 * cell for (visemeMap in {@link AvatarRig}).
 *
 * <h3>Stability (plan: visemes HARD-snap, must read crisp — no flicker)</h3>
 * Two independent smoothing layers, same idiom as {@link AudioLevelViseme}'s
 * attack/release:
 * <ol>
 *   <li>The four band RATIOS (and the loudness gate) are attack/release
 *       filtered BEFORE classification — a single noisy FFT frame can't flip
 *       the decision.</li>
 *   <li>The discrete class choice itself only commits to a challenger once
 *       its smoothed score beats the current class's by {@link #HYSTERESIS}
 *       AND a minimum dwell time has elapsed since the last commit — the
 *       same "per-part hysteresis" shape as {@code PuppetPoseResolver}'s
 *       discrete swaps, applied to audio instead of pose.</li>
 * </ol>
 *
 * <p>Not linguistically rigorous (real formant tracking needs LPC or a
 * proper formant tracker) — it is a deliberately cheap classifier tuned to
 * give a STABLE, plausible mouth-shape signal on phone-class CPU, per the
 * plan's "v2 cheap spectral" framing (TarsosDSP explicitly NOT used —
 * license/availability risk; this ~150-line FFT + band-energy heuristic is
 * the intended scope).</p>
 */
public final class SpectralVisemeAnalyzer {

    // ── Viseme classes (index == the "viseme" driver param value) ──────────
    public static final int CLS_REST = 0;
    public static final int CLS_AA = 1;    // open jaw ("father")
    public static final int CLS_EE = 2;    // wide/spread ("see")
    public static final int CLS_OO = 3;    // round ("boot")
    public static final int CLS_CLOSURE = 4; // bilabial/nasal ("m","b","p")
    public static final int CLS_FRIC = 5;  // fricative/sibilant ("s","f","sh")

    /** Index-ordered class names — also the convention {@code AvatarRig}'s
     *  {@code visemeMap} keys should use (visemeClass -> mouth cellIndex). */
    public static final String[] CLASS_NAMES = {
            "REST", "AA", "EE", "OO", "CLOSURE", "FRIC"
    };
    public static final int NUM_CLASSES = CLASS_NAMES.length;

    /** Driver param name the resolved class rides on (index as a float). */
    public static final String PARAM_VISEME = "viseme";

    // ── FFT / band config ───────────────────────────────────────────────────
    private static final int FFT_SIZE = 1024; // power of 2; ~64ms @ 16kHz

    private static final float BAND_VLOW_LO = 250f, BAND_VLOW_HI = 500f;
    private static final float BAND_LOW_LO = 600f, BAND_LOW_HI = 1100f;
    private static final float BAND_MID_LO = 1500f, BAND_MID_HI = 2800f;
    private static final float BAND_HI_LO = 3500f, BAND_HI_HI = 7500f;

    /** Time-domain RMS dB gates (20*log10(rms), rms of [-1..1] samples). */
    private static final float REST_DB = -45f;    // at/below: silence
    private static final float CLOSURE_DB = -30f; // at/below (above REST): quiet-voiced

    /** Feature-smoothing attack/release (seconds) — same asymmetry idea as
     *  AudioLevelViseme: react fast, settle slower. */
    private static final double FEATURE_ATTACK_S = 0.03;
    private static final double FEATURE_RELEASE_S = 0.10;

    /** Discrete commit margin + minimum dwell before a class swap sticks. */
    private static final float HYSTERESIS = 0.12f;
    private static final double MIN_DWELL_S = 0.06;

    // ── Frame accumulation ──────────────────────────────────────────────────
    private final float[] frameBuf = new float[FFT_SIZE];
    private int fill = 0;

    // ── Smoothed features ───────────────────────────────────────────────────
    private float dbSmoothed = REST_DB;
    private float rVlowSmoothed, rLowSmoothed, rMidSmoothed, rHiSmoothed;

    // ── Discrete state ───────────────────────────────────────────────────────
    private int currentClass = CLS_REST;
    private double elapsedSeconds = 0;
    private double lastCommitSeconds = -1e9;

    /** Push more PCM (float samples in [-1..1], mono). Buffers internally into
     *  {@link #FFT_SIZE}-sample windows; runs the classifier once per full
     *  window (may run 0, 1, or several times per call depending on chunk
     *  size — a real AudioRecord read buffer rarely aligns to FFT_SIZE). */
    public void push(float[] pcm, int sampleRate) {
        if (pcm == null || pcm.length == 0 || sampleRate <= 0) return;
        int i = 0;
        while (i < pcm.length) {
            int room = FFT_SIZE - fill;
            int take = Math.min(room, pcm.length - i);
            System.arraycopy(pcm, i, frameBuf, fill, take);
            fill += take;
            i += take;
            if (fill >= FFT_SIZE) {
                processFrame(sampleRate);
                fill = 0;
            }
        }
    }

    /** Convenience overload for raw 16-bit PCM (AudioRecord's native format). */
    public void push(short[] pcm, int sampleRate) {
        if (pcm == null || pcm.length == 0) return;
        float[] f = new float[pcm.length];
        for (int i = 0; i < pcm.length; i++) f[i] = pcm[i] / 32768f;
        push(f, sampleRate);
    }

    /** Current committed viseme class index — stable across calls between
     *  window boundaries (only {@link #processFrame} can change it). */
    public int currentClassIndex() { return currentClass; }

    @NonNull
    public String currentClassName() { return CLASS_NAMES[currentClass]; }

    /** Latest time-domain RMS level in dB (same convention as the amplitude
     *  tier: silence sits well below 0dB). Usable to feed {@code audioDb}. */
    public float currentDb() { return dbSmoothed; }

    public void reset() {
        fill = 0;
        dbSmoothed = REST_DB;
        rVlowSmoothed = rLowSmoothed = rMidSmoothed = rHiSmoothed = 0f;
        currentClass = CLS_REST;
        elapsedSeconds = 0;
        lastCommitSeconds = -1e9;
    }

    // ── Per-window analysis ──────────────────────────────────────────────────

    private void processFrame(int sampleRate) {
        double dt = FFT_SIZE / (double) sampleRate;
        elapsedSeconds += dt;

        // Time-domain RMS dB (independent of the FFT — cheap loudness gate).
        double sumSq = 0;
        for (int n = 0; n < FFT_SIZE; n++) sumSq += frameBuf[n] * (double) frameBuf[n];
        float rms = (float) Math.sqrt(sumSq / FFT_SIZE);
        float dbNow = 20f * (float) Math.log10(Math.max(rms, 1e-6f));

        // Hann-windowed real FFT.
        float[] re = new float[FFT_SIZE];
        float[] im = new float[FFT_SIZE];
        for (int n = 0; n < FFT_SIZE; n++) {
            double w = 0.5 - 0.5 * Math.cos(2 * Math.PI * n / (FFT_SIZE - 1));
            re[n] = (float) (frameBuf[n] * w);
            im[n] = 0f;
        }
        fft(re, im);

        double binHz = sampleRate / (double) FFT_SIZE;
        double eVlow = 0, eLow = 0, eMid = 0, eHi = 0;
        int half = FFT_SIZE / 2;
        for (int b = 1; b <= half; b++) { // skip DC (b=0)
            double freq = b * binHz;
            double mag2 = re[b] * (double) re[b] + im[b] * (double) im[b];
            if (freq >= BAND_VLOW_LO && freq <= BAND_VLOW_HI) eVlow += mag2;
            else if (freq >= BAND_LOW_LO && freq <= BAND_LOW_HI) eLow += mag2;
            else if (freq >= BAND_MID_LO && freq <= BAND_MID_HI) eMid += mag2;
            else if (freq >= BAND_HI_LO && freq <= BAND_HI_HI) eHi += mag2;
        }
        double tracked = eVlow + eLow + eMid + eHi + 1e-9;
        float rVlowNow = (float) (eVlow / tracked);
        float rLowNow = (float) (eLow / tracked);
        float rMidNow = (float) (eMid / tracked);
        float rHiNow = (float) (eHi / tracked);

        dbSmoothed = envelope(dbSmoothed, dbNow, dt);
        rVlowSmoothed = envelope(rVlowSmoothed, rVlowNow, dt);
        rLowSmoothed = envelope(rLowSmoothed, rLowNow, dt);
        rMidSmoothed = envelope(rMidSmoothed, rMidNow, dt);
        rHiSmoothed = envelope(rHiSmoothed, rHiNow, dt);

        classify();
    }

    /** Attack/release exponential smoothing (rising = attack, falling = release
     *  — mirrors AudioLevelViseme's asymmetric envelope). */
    private static float envelope(float prev, float target, double dt) {
        double tau = target > prev ? FEATURE_ATTACK_S : FEATURE_RELEASE_S;
        double a = tau <= 0 ? 1.0 : 1.0 - Math.exp(-dt / tau);
        return prev + (float) ((target - prev) * a);
    }

    private void classify() {
        int target;
        float targetScore;
        if (dbSmoothed <= REST_DB) {
            target = CLS_REST;
            targetScore = 1f;
        } else if (dbSmoothed <= CLOSURE_DB && rHiSmoothed < 0.35f) {
            target = CLS_CLOSURE;
            targetScore = 1f;
        } else {
            // Argmax over the four band ratios -> vowel/fricative shape.
            target = CLS_AA;
            targetScore = rLowSmoothed;
            if (rVlowSmoothed > targetScore) { target = CLS_OO; targetScore = rVlowSmoothed; }
            if (rMidSmoothed > targetScore) { target = CLS_EE; targetScore = rMidSmoothed; }
            if (rHiSmoothed > targetScore) { target = CLS_FRIC; targetScore = rHiSmoothed; }
        }

        if (target == currentClass) return;

        float currentScore = scoreOf(currentClass);
        boolean dwellOk = (elapsedSeconds - lastCommitSeconds) >= MIN_DWELL_S;
        if (dwellOk && targetScore > currentScore + HYSTERESIS) {
            currentClass = target;
            lastCommitSeconds = elapsedSeconds;
        }
        // else: challenger not convincing enough yet / too soon — stick (the
        // "STABLE class signal, no flicker" requirement).
    }

    private float scoreOf(int cls) {
        switch (cls) {
            case CLS_REST: return dbSmoothed <= REST_DB ? 1f : 0f;
            case CLS_CLOSURE: return dbSmoothed <= CLOSURE_DB ? 1f : 0f;
            case CLS_OO: return rVlowSmoothed;
            case CLS_AA: return rLowSmoothed;
            case CLS_EE: return rMidSmoothed;
            case CLS_FRIC: return rHiSmoothed;
            default: return 0f;
        }
    }

    // ── Radix-2 Cooley-Tukey FFT (in-place, iterative) ──────────────────────
    // Standard textbook implementation; FFT_SIZE (1024) is a power of 2 so no
    // padding/trimming logic is needed. Pure math, no allocation beyond the
    // caller-provided re/im arrays.
    private static void fft(float[] re, float[] im) {
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
        // Iterative Cooley-Tukey.
        for (int len = 2; len <= n; len <<= 1) {
            double ang = -2 * Math.PI / len;
            float wr = (float) Math.cos(ang), wi = (float) Math.sin(ang);
            for (int i = 0; i < n; i += len) {
                float curWr = 1f, curWi = 0f;
                for (int k = 0; k < len / 2; k++) {
                    int a = i + k, b = i + k + len / 2;
                    float ur = re[a], ui = im[a];
                    float vr = re[b] * curWr - im[b] * curWi;
                    float vi = re[b] * curWi + im[b] * curWr;
                    re[a] = ur + vr; im[a] = ui + vi;
                    re[b] = ur - vr; im[b] = ui - vi;
                    float nwr = curWr * wr - curWi * wi;
                    float nwi = curWr * wi + curWi * wr;
                    curWr = nwr; curWi = nwi;
                }
            }
        }
    }
}
