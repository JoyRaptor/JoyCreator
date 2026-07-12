import com.fadcam.ui.faditor.avatar.SpectralVisemeAnalyzer;

/**
 * JVM harness for A3 v2's {@link SpectralVisemeAnalyzer}: synthesizes sine/
 * formant-region mixtures per viseme class (band-energy heuristic, see the
 * class javadoc), pushes them through {@code push(float[], int)}, and asserts
 * (1) each synthetic class converges to the expected classification, (2) the
 * classifier is STABLE under per-frame jitter (no flicker once locked), and
 * (3) silence converges back to REST. Pattern per ReplayMappingTest: plain
 * main(), check(), "ALL GREEN (n/n)".
 */
public class SpectralVisemeAnalyzerTest {

    private static int passed = 0, failed = 0;
    private static final int SR = 16000;
    private static final int FFT_SIZE = 1024;

    public static void main(String[] args) {
        classifiesAA();
        classifiesEE();
        classifiesOO();
        classifiesFricative();
        classifiesClosure();
        silenceIsRestFromStart();
        silenceReclaimsFromActiveClass();
        stableUnderJitterNoFlicker();
        weakBlipDoesNotFlipLockedClass();
        System.out.println(failed == 0 ? "ALL GREEN (" + passed + "/" + (passed + failed) + ")"
                : "FAILURES: " + failed + " (passed " + passed + ")");
        if (failed > 0) System.exit(1);
    }

    // ── Per-class convergence ────────────────────────────────────────────

    /** AA ("father"): F1-region tone ~800Hz, loud. */
    static void classifiesAA() {
        SpectralVisemeAnalyzer a = new SpectralVisemeAnalyzer();
        pushTone(a, new double[]{800}, new double[]{0.3}, 40, 1);
        check("AA: 800Hz loud tone -> AA",
                a.currentClassIndex() == SpectralVisemeAnalyzer.CLS_AA);
        check("AA: class name matches index", a.currentClassName().equals("AA"));
    }

    /** EE ("see"): F2-region tone ~2000Hz, loud. */
    static void classifiesEE() {
        SpectralVisemeAnalyzer a = new SpectralVisemeAnalyzer();
        pushTone(a, new double[]{2000}, new double[]{0.3}, 40, 2);
        check("EE: 2000Hz loud tone -> EE",
                a.currentClassIndex() == SpectralVisemeAnalyzer.CLS_EE);
    }

    /** OO ("boot"): both formants collapsed low, ~350Hz. */
    static void classifiesOO() {
        SpectralVisemeAnalyzer a = new SpectralVisemeAnalyzer();
        pushTone(a, new double[]{350}, new double[]{0.3}, 40, 3);
        check("OO: 350Hz loud tone -> OO",
                a.currentClassIndex() == SpectralVisemeAnalyzer.CLS_OO);
    }

    /** FRIC ("s"/"sh"/"f"): broadband high-frequency energy, no low content. */
    static void classifiesFricative() {
        SpectralVisemeAnalyzer a = new SpectralVisemeAnalyzer();
        pushTone(a, new double[]{4200, 5100, 5900, 6700, 7300},
                new double[]{0.12, 0.12, 0.12, 0.12, 0.12}, 40, 4);
        check("FRIC: broadband high-band noise -> FRIC",
                a.currentClassIndex() == SpectralVisemeAnalyzer.CLS_FRIC);
    }

    /** CLOSURE ("m"/"b"/"p"): quiet voiced hum — above REST floor, below the
     *  vowel-loudness range, low high-band content. */
    static void classifiesClosure() {
        SpectralVisemeAnalyzer a = new SpectralVisemeAnalyzer();
        pushTone(a, new double[]{400}, new double[]{0.02}, 40, 5);
        check("CLOSURE: quiet low hum -> CLOSURE",
                a.currentClassIndex() == SpectralVisemeAnalyzer.CLS_CLOSURE);
    }

    // ── Silence / REST ──────────────────────────────────────────────────

    static void silenceIsRestFromStart() {
        SpectralVisemeAnalyzer a = new SpectralVisemeAnalyzer();
        pushSilence(a, 10);
        check("silence from a fresh analyzer -> REST",
                a.currentClassIndex() == SpectralVisemeAnalyzer.CLS_REST);
    }

    static void silenceReclaimsFromActiveClass() {
        SpectralVisemeAnalyzer a = new SpectralVisemeAnalyzer();
        pushTone(a, new double[]{800}, new double[]{0.3}, 40, 6);
        check("setup: locked onto AA before silence test",
                a.currentClassIndex() == SpectralVisemeAnalyzer.CLS_AA);
        pushSilence(a, 40);
        check("silence after active speech converges back to REST",
                a.currentClassIndex() == SpectralVisemeAnalyzer.CLS_REST);
    }

    // ── Stability (no flicker) ───────────────────────────────────────────

    /** A realistic noisy mic feed: AA tone + small per-frame broadband jitter.
     *  Once locked, later frames (well past the settling window) must never
     *  leave AA — this is the "stable class signal, no flicker" requirement. */
    static void stableUnderJitterNoFlicker() {
        SpectralVisemeAnalyzer a = new SpectralVisemeAnalyzer();
        long lcg = 42;
        boolean sawSettle = false;
        int flickersAfterSettle = 0;
        int lastClass = -1;
        for (int frame = 0; frame < 80; frame++) {
            float[] pcm = new float[FFT_SIZE];
            for (int n = 0; n < FFT_SIZE; n++) {
                double t = n / (double) SR;
                double tone = 0.3 * Math.sin(2 * Math.PI * 800 * t);
                lcg = lcg * 0x5DEECE66DL + 0xBL;
                float jitter = (((lcg >>> 17) & 0x7FFFFFFF) / (float) (1L << 31) * 2f - 1f) * 0.02f;
                pcm[n] = (float) tone + jitter;
            }
            a.push(pcm, SR);
            if (frame >= 8) { // past the envelope settling window
                if (!sawSettle) {
                    sawSettle = a.currentClassIndex() == SpectralVisemeAnalyzer.CLS_AA;
                    lastClass = a.currentClassIndex();
                } else if (a.currentClassIndex() != lastClass) {
                    flickersAfterSettle++;
                    lastClass = a.currentClassIndex();
                }
            }
        }
        check("jitter: settled onto AA by frame 8", sawSettle);
        check("jitter: zero class changes after settling (no flicker)", flickersAfterSettle == 0);
    }

    /** A single quiet/ambiguous frame in the middle of a solidly-locked AA
     *  stream must not flip the committed class — the envelope + hysteresis
     *  absorb one-off outliers rather than reacting on the spot. */
    static void weakBlipDoesNotFlipLockedClass() {
        SpectralVisemeAnalyzer a = new SpectralVisemeAnalyzer();
        pushTone(a, new double[]{800}, new double[]{0.3}, 30, 7); // lock AA solidly
        check("setup: locked AA before the blip",
                a.currentClassIndex() == SpectralVisemeAnalyzer.CLS_AA);

        // One quiet ambiguous frame: low amplitude broadband hiss, well below
        // a full vowel's loudness — not enough to beat AA's smoothed score by
        // the hysteresis margin in a single 64ms window.
        float[] blip = new float[FFT_SIZE];
        long lcg = 99;
        for (int n = 0; n < FFT_SIZE; n++) {
            lcg = lcg * 0x5DEECE66DL + 0xBL;
            blip[n] = (((lcg >>> 17) & 0x7FFFFFFF) / (float) (1L << 31) * 2f - 1f) * 0.01f;
        }
        a.push(blip, SR);
        check("one weak ambiguous frame does not flip the locked class",
                a.currentClassIndex() == SpectralVisemeAnalyzer.CLS_AA);

        // Resume the AA tone — should still read AA (never actually left).
        pushTone(a, new double[]{800}, new double[]{0.3}, 5, 7);
        check("AA resumes cleanly after the blip",
                a.currentClassIndex() == SpectralVisemeAnalyzer.CLS_AA);
    }

    // ── Synthesis helpers ─────────────────────────────────────────────────

    /** Continuous-phase multi-tone sine mixture, {@code frames} windows of
     *  {@link #FFT_SIZE} samples each, pushed one window at a time. */
    static void pushTone(SpectralVisemeAnalyzer a, double[] freqsHz, double[] amps,
                          int frames, long seed) {
        double[] phase = new double[freqsHz.length];
        for (int f = 0; f < frames; f++) {
            float[] pcm = new float[FFT_SIZE];
            for (int n = 0; n < FFT_SIZE; n++) {
                double sum = 0;
                for (int k = 0; k < freqsHz.length; k++) {
                    sum += amps[k] * Math.sin(phase[k]);
                    phase[k] += 2 * Math.PI * freqsHz[k] / SR;
                }
                pcm[n] = (float) sum;
            }
            a.push(pcm, SR);
        }
    }

    static void pushSilence(SpectralVisemeAnalyzer a, int frames) {
        float[] pcm = new float[FFT_SIZE]; // all zero
        for (int f = 0; f < frames; f++) a.push(pcm, SR);
    }

    // ── check ────────────────────────────────────────────────────────────

    static void check(String name, boolean ok) {
        if (ok) { passed++; System.out.println("  ✓ " + name); }
        else { failed++; System.out.println("  ✗ FAIL " + name); }
    }
}
