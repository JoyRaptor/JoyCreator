import com.fadcam.ui.faditor.waveform.OnsetDetector;

/**
 * Onset detection against SYNTHETIC signals whose answers are known by construction.
 *
 * <p>This is the reason {@code OnsetDetector} takes a {@code Samples} callback instead of a
 * {@code PcmSidecar.Handle}: a detector tuned by listening is a detector tuned by
 * superstition. Every case here states the right answer up front and fails if the algorithm
 * disagrees.</p>
 *
 *   bash tools/jvm-harness/run-onset.sh
 */
public class OnsetDetectorTest {

    static final int RATE = 22050;
    static int failures = 0;

    static void check(boolean cond, String msg) {
        if (cond) {
            System.out.println("  PASS  " + msg);
        } else {
            System.out.println("  FAIL  " + msg);
            failures++;
        }
    }

    /** Samples backed by a plain array. */
    static OnsetDetector.Samples of(final float[] a) {
        return new OnsetDetector.Samples() {
            @Override public int count() { return a.length; }
            @Override public float at(int i) { return (i < 0 || i >= a.length) ? 0f : a[i]; }
        };
    }

    /**
     * A consonant-like burst: alternating sign is the highest frequency the rate can carry,
     * which is what the detector's pre-emphasis is built to favour. 25 ms long, hard attack.
     */
    static void burst(float[] buf, long atMs, float amp) {
        int start = (int) (atMs * RATE / 1000);
        int len = RATE * 25 / 1000;
        for (int i = 0; i < len && start + i < buf.length; i++) {
            float decay = 1f - (i / (float) len);
            buf[start + i] += ((i % 2 == 0) ? amp : -amp) * decay;
        }
    }

    /** Deterministic low-level noise — a real recording is never digitally silent. */
    static void noise(float[] buf, float amp) {
        long s = 12345L;
        for (int i = 0; i < buf.length; i++) {
            s = s * 6364136223846793005L + 1442695040888963407L;
            buf[i] += ((s >> 40) / (float) (1 << 23)) * amp;
        }
    }

    static boolean near(long[] got, long wantMs, long tolMs) {
        for (long g : got) if (Math.abs(g - wantMs) <= tolMs) return true;
        return false;
    }

    public static void main(String[] args) {
        System.out.println("OnsetDetector");

        // 1. Digital silence produces nothing. A detector that fires on silence would put a
        //    snap target under every word in a gap.
        float[] silence = new float[RATE * 3];
        long[] r1 = OnsetDetector.detect(of(silence), RATE);
        check(r1.length == 0, "silence yields no onsets (got " + r1.length + ")");

        // 2. One burst at a known time is found at that time.
        float[] one = new float[RATE * 3];
        burst(one, 1000, 0.6f);
        long[] r2 = OnsetDetector.detect(of(one), RATE);
        check(r2.length >= 1, "single burst detected (got " + r2.length + ")");
        check(r2.length >= 1 && near(r2, 1000, 25), "found at ~1000ms (got " + str(r2) + ")");

        // 3. Five evenly spaced bursts: five onsets, no more. Over-firing on one event is the
        //    failure that makes a snap target ambiguous.
        float[] five = new float[RATE * 4];
        for (int i = 0; i < 5; i++) burst(five, 500 + i * 600L, 0.5f);
        long[] r3 = OnsetDetector.detect(of(five), RATE);
        check(r3.length == 5, "five bursts -> five onsets (got " + r3.length + ": " + str(r3) + ")");
        boolean all = true;
        for (int i = 0; i < 5; i++) if (!near(r3, 500 + i * 600L, 25)) all = false;
        check(all, "all five at their true times");

        // 4. THE CASE THAT MATTERS: a quiet passage and a loud one in the same file. A fixed
        //    threshold either floods the loud half or misses the quiet half. Both must be found.
        float[] mixed = new float[RATE * 6];
        noise(mixed, 0.004f);
        burst(mixed, 800, 0.15f);    // quiet passage
        burst(mixed, 1600, 0.15f);
        burst(mixed, 3800, 0.85f);   // loud passage
        burst(mixed, 4600, 0.85f);
        long[] r4 = OnsetDetector.detect(of(mixed), RATE);
        check(near(r4, 800, 30) && near(r4, 1600, 30), "quiet bursts found (" + str(r4) + ")");
        check(near(r4, 3800, 30) && near(r4, 4600, 30), "loud bursts found");

        // 5. The onset is the START of the rise, not its peak. A slow 120ms swell reported at
        //    its peak would place every word consistently LATE — the one error direction that
        //    defeats the purpose.
        float[] swell = new float[RATE * 3];
        int s0 = RATE * 1000 / 1000, sl = RATE * 120 / 1000;
        for (int i = 0; i < sl; i++) {
            float a = 0.8f * (i / (float) sl);
            swell[s0 + i] = ((i % 2 == 0) ? a : -a);
        }
        long[] r5 = OnsetDetector.detect(of(swell), RATE);
        check(r5.length >= 1, "swell detected");
        if (r5.length >= 1) {
            long first = r5[0];
            check(first < 1000 + 60, "swell onset near its START, not its peak (got " + first + "ms)");
        }

        // 6. snap(): magnets inside tolerance, hands back control outside it.
        long[] onsets = {1000, 2000, 5000};
        check(OnsetDetector.snap(onsets, 1020, 100) == 1000, "snaps to 1000 from 1020");
        check(OnsetDetector.snap(onsets, 1980, 100) == 2000, "snaps back to 2000 from 1980");
        check(OnsetDetector.snap(onsets, 3000, 100) == 3000, "leaves 3000 alone (no onset near)");
        check(OnsetDetector.snap(onsets, 1500, 600) == 2000, "picks the NEARER of two in range");
        check(OnsetDetector.snap(new long[0], 1234, 100) == 1234, "empty onset list is a no-op");
        check(OnsetDetector.snap(onsets, 1020, 0) == 1020, "zero tolerance disables snapping");
        check(OnsetDetector.snap(onsets, 900, 100) == 1000, "snaps forward from before the first");

        // 7. Zoom-scaled snap tolerance: constant in PIXELS, clamped in ms.
        check(OnsetDetector.snapToleranceMs(0.5) == 40, "zoomed way in -> floor 40ms");
        check(OnsetDetector.snapToleranceMs(5.0) == 60, "5ms/px -> 60ms (12px of forgiveness)");
        check(OnsetDetector.snapToleranceMs(50.0) == 120, "zoomed way out -> ceiling 120ms");
        check(OnsetDetector.snapToleranceMs(0) == 40, "degenerate zoom is safe");
        check(OnsetDetector.snapToleranceMs(Double.NaN) == 40, "NaN zoom is safe");
        long a = OnsetDetector.snapToleranceMs(3.0), b = OnsetDetector.snapToleranceMs(8.0);
        check(a <= b, "tolerance grows with ms-per-pixel (" + a + " <= " + b + ")");

        System.out.println(failures == 0
                ? "\nALL PASS"
                : "\n" + failures + " FAILURE(S)");
        if (failures != 0) System.exit(1);
    }

    static String str(long[] a) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < a.length; i++) { if (i > 0) sb.append(","); sb.append(a[i]); }
        return sb.append("]").toString();
    }
}
