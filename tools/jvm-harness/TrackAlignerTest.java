import com.fadcam.ui.faditor.waveform.TrackAligner;

/**
 * D7 — clap-sync alignment, off device.
 *
 * <p>Every case builds two envelopes whose true offset is known by construction, so "did it find
 * the offset" is measured rather than eyeballed. The cases that matter are the ones where a
 * naive cross-correlation quietly does the wrong thing: two takes at different LOUDNESS (which
 * lets the louder one dominate), and two takes with NOTHING in common (where the honest answer
 * is a refusal, not a confident number).</p>
 *
 * <p>This harness earned its keep on the first run: it reported -4930ms at confidence 1.0 for
 * takes whose true offset was 800ms, which a casual listen would probably have excused.</p>
 */
public class TrackAlignerTest {
    static int fails = 0;
    static void check(boolean c, String n) {
        System.out.println((c ? "PASS  " : "FAIL  ") + n);
        if (!c) fails++;
    }

    /** Envelope with a clap at {@code clapFrame} over a quiet floor. */
    static int[] take(int len, int clapFrame, int amp, int floor) {
        int[] e = new int[len];
        for (int i = 0; i < len; i++) e[i] = floor;
        if (clapFrame >= 0 && clapFrame < len) {
            e[clapFrame] = amp;
            if (clapFrame + 1 < len) e[clapFrame + 1] = amp / 2;
            if (clapFrame + 2 < len) e[clapFrame + 2] = amp / 3;
        }
        // A little structure either side, so a match is a match of SHAPE, not of one spike.
        for (int i = 0; i < len; i++) {
            if (i % 17 == 0) e[i] = Math.min(amp, e[i] + floor * 2);
        }
        return e;
    }

    public static void main(String[] args) {
        final int FPS = 100;                 // 10ms frames
        final long MAX = 5_000;

        // ── 1. B's clap is 80 frames (800ms) LATER than A's ───────────────────────────
        int[] a = take(600, 100, 200, 5);
        int[] b = take(600, 180, 200, 5);
        TrackAligner.Result r = TrackAligner.align(a, b, FPS, MAX);
        System.out.println("      offset=" + r.offsetMs + "ms conf=" + r.confidence);
        check(Math.abs(r.offsetMs - 800) <= 20, "finds an 800ms offset within 20ms");
        check(r.isUsable(), "a real match is reported as usable");

        // ── 2. The other direction ────────────────────────────────────────────────────
        TrackAligner.Result rr = TrackAligner.align(b, a, FPS, MAX);
        System.out.println("      reversed offset=" + rr.offsetMs + "ms");
        check(Math.abs(rr.offsetMs + 800) <= 20, "reversing the arguments negates the offset");

        // ── 3. DIFFERENT LOUDNESS — what defeats un-normalised correlation ────────────
        int[] quiet = take(600, 180, 30, 1);   // same shape, ~7x quieter
        TrackAligner.Result rq = TrackAligner.align(a, quiet, FPS, MAX);
        System.out.println("      loud-vs-quiet offset=" + rq.offsetMs + "ms conf=" + rq.confidence);
        check(Math.abs(rq.offsetMs - 800) <= 20, "loudness difference does not move the answer");

        // ── 4. Already aligned ────────────────────────────────────────────────────────
        TrackAligner.Result r0 = TrackAligner.align(a, take(600, 100, 200, 5), FPS, MAX);
        check(Math.abs(r0.offsetMs) <= 20, "identical takes report ~zero offset");

        // ── 5. NOTHING IN COMMON — must refuse, not guess ─────────────────────────────
        int[] noiseA = new int[600], noiseB = new int[600];
        long seed = 12345;
        for (int i = 0; i < 600; i++) {
            seed = seed * 6364136223846793005L + 1442695040888963407L;
            noiseA[i] = (int) ((seed >>> 40) % 50);
            seed = seed * 6364136223846793005L + 1442695040888963407L;
            noiseB[i] = (int) ((seed >>> 40) % 50);
        }
        TrackAligner.Result rn = TrackAligner.align(noiseA, noiseB, FPS, MAX);
        System.out.println("      unrelated conf=" + rn.confidence
                + " (usable=" + rn.isUsable() + ")");
        check(!rn.isUsable(), "unrelated takes are REFUSED rather than aligned on a guess");
        // The separation MIN_CONFIDENCE is drawn through. If this ever narrows, the threshold
        // was tuned to a fixture rather than to a real gap.
        check(r.confidence - rn.confidence > 0.5f,
                "real-vs-unrelated confidence gap stays wide (>0.5)");

        // ── 6. Bounded search: an offset beyond the bound is not invented ─────────────
        TrackAligner.Result rb = TrackAligner.align(a, b, FPS, 200);  // true offset is 800ms
        System.out.println("      bounded(200ms) offset=" + rb.offsetMs);
        check(Math.abs(rb.offsetMs) <= 200, "search stays inside the bound it was given");

        // ── 7. Degenerate input must not throw ───────────────────────────────────────
        check(TrackAligner.align(new int[0], b, FPS, MAX).confidence == 0f, "empty A is safe");
        check(TrackAligner.align(a, new int[0], FPS, MAX).confidence == 0f, "empty B is safe");
        check(TrackAligner.align(a, b, 0, MAX).confidence == 0f, "zero frame-rate is safe");
        check(TrackAligner.align(a, b, FPS, 0).confidence == 0f, "zero max-offset is safe");

        System.out.println(fails == 0 ? "ALL PASS" : (fails + " FAILED"));
        if (fails != 0) System.exit(1);
    }
}
