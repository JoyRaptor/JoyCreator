import com.fadcam.ui.faditor.waveform.ShapedTape;

/**
 * JVM harness for the AV5 A1/A2 pure math: the quantized max-pool mip pyramid the tape renderer
 * reads from. Covers (1) quantization round-trip within 1/255, (2) max-pool correctness across
 * levels (peaks survive, never mean), (3) ceil halving + MIN_LEVEL_LEN stop, (4) level selection
 * floor(log2(framesPerPx)) clamped, (5) frame>>L index mapping consistency, (6) null-band passthrough.
 * Run like the other harnesses (javac against the app's built classes + gson + annotation-jvm).
 */
public class ShapedTapeTest {

    private static int passed = 0, failed = 0;

    public static void main(String[] args) {
        quantRoundTrip();
        maxPoolPairs();
        ceilHalvingAndStop();
        levelSelection();
        frameShiftConsistency();
        nullBandPassthrough();
        System.out.println(failed == 0 ? "ALL GREEN (" + passed + "/" + (passed + failed) + ")"
                : "FAILURES: " + failed + " (passed " + passed + ")");
        if (failed > 0) System.exit(1);
    }

    /** quant(v) round-trips within one 1/255 step across the range. */
    static void quantRoundTrip() {
        float maxErr = 0f;
        for (int i = 0; i <= 1000; i++) {
            float v = i / 1000f;
            byte q = ShapedTape.quant(v);
            float back = (q & 0xFF) / 255f;
            maxErr = Math.max(maxErr, Math.abs(back - v));
        }
        check("quant: round-trip <= 1/255", maxErr <= 1f / 255f + 1e-6f);
        check("quant: 0 -> 0", (ShapedTape.quant(0f) & 0xFF) == 0);
        check("quant: 1 -> 255", (ShapedTape.quant(1f) & 0xFF) == 255);
        check("quant: clamps negative", (ShapedTape.quant(-0.5f) & 0xFF) == 0);
        check("quant: clamps over-1", (ShapedTape.quant(2f) & 0xFF) == 255);
    }

    /** level L is the pairwise MAX of level L-1 (transients survive, not averaged away). */
    static void maxPoolPairs() {
        // A lone spike at index 3 must persist up every level, not be diluted.
        float[] band = new float[600];
        band[3] = 1.0f;
        byte[][] p = ShapedTape.buildBand(band);
        check("maxpool: level0 length == input", p[0].length == 600);
        check("maxpool: spike at L0", (p[0][3] & 0xFF) == 255);
        check("maxpool: spike survives L1", (p[1][1] & 0xFF) == 255); // idx 3>>1 == 1
        check("maxpool: spike survives L2", (p[2][0] & 0xFF) == 255); // idx 3>>2 == 0
        // A mean would have collapsed the isolated 255 toward 0; assert neighbours stayed 0.
        check("maxpool: non-spike bucket stays 0", (p[1][5] & 0xFF) == 0);

        // Explicit pair max: [0.2, 0.9] -> max 0.9.
        float[] two = {0.2f, 0.9f, 0.1f, 0.4f};
        byte[][] q = ShapedTape.buildBand(two);
        check("maxpool: pair0 max=0.9", (q[0][1] & 0xFF) >= (q[0][0] & 0xFF));
        // len 4 <= MIN_LEVEL_LEN so only level 0 exists.
        check("maxpool: short band single level", q.length == 1);
    }

    /** Halving uses ceil; building stops once a level is <= MIN_LEVEL_LEN (256). */
    static void ceilHalvingAndStop() {
        float[] band = new float[1000];
        byte[][] p = ShapedTape.buildBand(band);
        // 1000 -> 500 -> 250(<=256 stop). Levels: 0(1000),1(500),2(250).
        check("ceil: level count", p.length == 3);
        check("ceil: L1 len 500", p[1].length == 500);
        check("ceil: L2 len 250", p[2].length == 250);
        check("ceil: top level <= MIN_LEVEL_LEN", p[p.length - 1].length <= ShapedTape.MIN_LEVEL_LEN);

        // Odd length halves with ceil: 7 -> 4.
        float[] odd = new float[7 + 512]; // force >1 level; check an odd interior would ceil
        byte[][] o = ShapedTape.buildBand(new float[519]);
        check("ceil: 519 -> 260", o[1].length == 260);
    }

    /** levelFor picks floor(log2(framesPerPx)), clamped to available levels. */
    static void levelSelection() {
        float[] band = new float[4096];
        ShapedTape t = ShapedTape.build(new float[][]{band});
        check("levelFor: <1 -> 0", t.levelFor(0, 0.5f) == 0);
        check("levelFor: 1 -> 0", t.levelFor(0, 1f) == 0);
        check("levelFor: 1.9 -> 0", t.levelFor(0, 1.9f) == 0);
        check("levelFor: 2 -> 1", t.levelFor(0, 2f) == 1);
        check("levelFor: 3 -> 1", t.levelFor(0, 3f) == 1);
        check("levelFor: 4 -> 2", t.levelFor(0, 4f) == 2);
        check("levelFor: 8 -> 3", t.levelFor(0, 8f) == 3);
        // Clamp: huge framesPerPx cannot exceed the top level index.
        int top = t.levelCount(0) - 1;
        check("levelFor: clamps to top", t.levelFor(0, 1_000_000f) == top);
    }

    /** Reading level L at index frame>>L returns the max over the 2^L source frames. */
    static void frameShiftConsistency() {
        float[] band = new float[2048];
        // Ramp so each bucket's max is its last element.
        for (int i = 0; i < band.length; i++) band[i] = i / 2048f;
        ShapedTape t = ShapedTape.build(new float[][]{band});
        int L = 3; // covers 8 source frames per index
        byte[] lvl = t.level(0, L);
        // frame 100 -> index 12; the max over frames [96,104) is frame 103.
        int idx = 100 >> L;
        int got = lvl[idx] & 0xFF;
        int expect = ShapedTape.quant(103 / 2048f) & 0xFF;
        check("shift: level index holds bucket max", got == expect);
        check("shift: level length is ceil", lvl.length == ((2048 + (1 << L) - 1) >> L));
    }

    /** A null band (absent / off) stays null and reports hasBand false. */
    static void nullBandPassthrough() {
        ShapedTape t = ShapedTape.build(new float[][]{new float[300], null, new float[300], null});
        check("null: band0 present", t.hasBand(0));
        check("null: band1 absent", !t.hasBand(1));
        check("null: band3 absent", !t.hasBand(3));
        check("null: out-of-range absent", !t.hasBand(9));
        check("null: frameCount of present band", t.frameCount(0) == 300);
    }

    static void check(String name, boolean cond) {
        if (cond) { passed++; }
        else { failed++; System.out.println("FAIL: " + name); }
    }
}
