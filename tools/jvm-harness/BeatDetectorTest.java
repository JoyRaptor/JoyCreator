import com.fadcam.ui.faditor.waveform.BeatDetector;

/**
 * D6 — beat/onset detection, off device.
 *
 * <p>Beat detection is easy to write and easy to fool yourself about: run it on one song, see
 * markers that look plausible, declare victory. So every check here is against a SYNTHETIC
 * envelope whose true beat positions are known by construction, which makes "did it find the
 * beats" a measurement rather than an impression.</p>
 *
 * <p>The two failure modes that matter to a user are covered explicitly: a quiet intro followed
 * by a loud chorus (which is what defeats a fixed threshold), and one drum hit registering as a
 * little cluster of three (which is what defeats a detector with no minimum gap).</p>
 */
public class BeatDetectorTest {
    static int fails = 0;
    static void check(boolean c, String n) {
        System.out.println((c ? "PASS  " : "FAIL  ") + n);
        if (!c) fails++;
    }

    /** 100 frames/sec envelope with a sharp hit every {@code periodMs}, amplitude {@code amp}. */
    static int[] pulses(int lengthMs, int periodMs, int amp, int fps) {
        int n = lengthMs * fps / 1000;
        int[] e = new int[n];
        int stride = periodMs * fps / 1000;
        for (int i = 0; i < n; i++) e[i] = 4;                    // quiet floor
        for (int i = 0; i < n; i += stride) {
            e[i] = amp;                                          // attack
            if (i + 1 < n) e[i + 1] = amp / 2;                   // decay
            if (i + 2 < n) e[i + 2] = amp / 4;
        }
        return e;
    }

    /** How many of the expected beat times were matched within tolerance. */
    static int matched(long[] got, int periodMs, int lengthMs, long tolMs) {
        int hit = 0;
        for (int t = 0; t < lengthMs; t += periodMs) {
            for (long g : got) if (Math.abs(g - t) <= tolMs) { hit++; break; }
        }
        return hit;
    }

    public static void main(String[] args) {
        final int FPS = 100;

        // ── 1. A steady 120 BPM pulse (500ms apart) ────────────────────────────────────
        int[] steady = pulses(10_000, 500, 200, FPS);
        BeatDetector.Result r = BeatDetector.detect(steady, FPS, 1.0f);
        int expected = 10_000 / 500;
        int hit = matched(r.beatsMs, 500, 10_000, 60);
        System.out.println("      steady: found " + r.beatsMs.length + " beats, matched "
                + hit + "/" + expected + ", bpm=" + Math.round(r.bpm));
        check(hit >= expected - 2, "120 BPM pulse: finds at least all-but-two of the beats");
        check(r.beatsMs.length <= expected + 2, "120 BPM pulse: does not invent extra beats");
        check(Math.abs(r.bpm - 120f) < 8f, "120 BPM pulse: tempo estimate within 8 BPM");

        // ── 2. Quiet intro, loud chorus — what kills a FIXED threshold ─────────────────
        int[] dyn = pulses(10_000, 500, 200, FPS);
        for (int i = 0; i < dyn.length / 2; i++) dyn[i] = Math.max(1, dyn[i] / 8); // soft half
        BeatDetector.Result rd = BeatDetector.detect(dyn, FPS, 1.0f);
        int quietHits = 0;
        for (long g : rd.beatsMs) if (g < 5000) quietHits++;
        System.out.println("      dynamic: " + quietHits + " beats found in the QUIET half");
        check(quietHits >= 5, "adaptive threshold still finds beats in a quiet intro");

        // ── 3. One hit must not become a cluster ───────────────────────────────────────
        int[] single = new int[300];
        for (int i = 0; i < single.length; i++) single[i] = 4;
        single[100] = 220; single[101] = 180; single[102] = 140; single[103] = 90;
        BeatDetector.Result rs = BeatDetector.detect(single, FPS, 1.0f);
        System.out.println("      single hit: " + rs.beatsMs.length + " beat(s)");
        check(rs.beatsMs.length == 1, "one drum hit registers as exactly ONE beat");

        // ── 4. Silence invents nothing ────────────────────────────────────────────────
        int[] silence = new int[500];
        BeatDetector.Result rz = BeatDetector.detect(silence, FPS, 1.0f);
        check(rz.beatsMs.length == 0, "pure silence yields no beats");

        // ── 5. Sensitivity actually does something, in the right direction ────────────
        BeatDetector.Result loose = BeatDetector.detect(steady, FPS, 0.4f);
        BeatDetector.Result tight = BeatDetector.detect(steady, FPS, 2.5f);
        System.out.println("      sensitivity: loose=" + loose.beatsMs.length
                + " balanced=" + r.beatsMs.length + " tight=" + tight.beatsMs.length);
        // HONEST LIMIT: on a clean synthetic pulse train the peaks tower so far over the
        // adaptive threshold that sensitivity changes nothing — this run prints 19/19/19. So
        // this check proves the ORDERING is not inverted; it does NOT prove the knob is
        // effective. That needs real music, which belongs in a device pass, not here.
        check(loose.beatsMs.length >= tight.beatsMs.length,
                "sensitivity ordering not inverted (inert on clean pulses — see note)");

        // ── 6. Snapping: reaches only as far as it is told ────────────────────────────
        long[] grid = {0, 500, 1000, 1500};
        check(BeatDetector.snap(grid, 520, 100) == 500, "snap pulls 520 -> 500 within tolerance");
        check(BeatDetector.snap(grid, 700, 100) == 700, "snap LEAVES 700 alone beyond tolerance");
        check(BeatDetector.snap(new long[0], 700, 100) == 700, "snap with no beats is identity");

        // ── 7. Degenerate input must not throw ────────────────────────────────────────
        check(BeatDetector.detect(new int[0], FPS, 1f).beatsMs.length == 0, "empty envelope is safe");
        check(BeatDetector.detect(steady, 0, 1f).beatsMs.length == 0, "zero frame-rate is safe");

        System.out.println(fails == 0 ? "ALL PASS" : (fails + " FAILED"));
        if (fails != 0) System.exit(1);
    }
}
