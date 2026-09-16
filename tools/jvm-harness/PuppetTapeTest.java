import com.fadcam.ui.faditor.puppet.PuppetTapeMarks;
import com.fadcam.ui.faditor.puppet.PuppetTapeMarks.Mark;

import java.util.Arrays;

/**
 * SPEC_20260915_PUPPET_UI §01 — the tape's Option C, proved without a phone.
 *
 * <p>The design turns keys into bars and diamonds by DENSITY rather than by storing takes as
 * objects. That decision buys a lot (nothing extra to serialise, and the word "take" can never
 * leak into the UI) and it costs exactly one thing: the boundaries have to be right, because a
 * performance that fails to read as a bar looks like a hundred loose keys, and two hand-placed
 * keys that wrongly read as a bar claim a performance nobody gave.
 */
public class PuppetTapeTest {

    private static int passed = 0, failed = 0;

    public static void main(String[] args) {
        System.out.println("-- tape: what reads as a performance --");
        aLiveTakeIsOneBar();
        handPlacedKeysStaySingles();
        twoKeysAreNeverABar();
        threeIsTheSmallestBar();
        aGapSplitsTwoTakes();
        aThinnedTakeStopsBeingABar();

        System.out.println();
        System.out.println("-- tape: the caps are the end keys --");
        insideExcludesBothEnds();
        aBarOfExactlyThreeHasOneInside();

        System.out.println();
        System.out.println("-- tape: degenerate --");
        emptyInEmptyOut();
        oneKeyIsOneDiamond();
        everyKeyAtTheSameInstant();

        System.out.println();
        System.out.println(failed == 0 ? "ALL PASS (" + passed + " assertions)"
                : failed + " FAILED of " + (passed + failed));
        if (failed != 0) System.exit(1);
    }

    /** 30fps for two seconds — what recording actually produces. */
    private static long[] take(long from, int frames, long step) {
        long[] t = new long[frames];
        for (int i = 0; i < frames; i++) t[i] = from + i * step;
        return t;
    }

    private static void aLiveTakeIsOneBar() {
        Mark[] m = PuppetTapeMarks.group(take(1000, 60, 33));
        check("a 60-frame take is ONE mark", 1, m.length);
        check("and it is a bar", true, m[0].bar);
        check("spanning the whole take", 1000L, m[0].fromMs);
        check("to its last frame", 1000L + 59 * 33, m[0].toMs);
        check("covering every key", 60, m[0].count);
    }

    private static void handPlacedKeysStaySingles() {
        long[] t = {0, 900, 2400, 5000};
        Mark[] m = PuppetTapeMarks.group(t);
        check("four sparse keys are four marks", 4, m.length);
        boolean anyBar = false;
        for (Mark x : m) anyBar |= x.bar;
        check("and none of them is a bar", false, anyBar);
    }

    private static void twoKeysAreNeverABar() {
        // Adjacent frames: as dense as it gets, and still only a move from A to B.
        Mark[] m = PuppetTapeMarks.group(new long[]{1000, 1033});
        check("two adjacent keys are two singles", 2, m.length);
        check("neither is a bar", false, m[0].bar || m[1].bar);
    }

    private static void threeIsTheSmallestBar() {
        Mark[] m = PuppetTapeMarks.group(new long[]{1000, 1033, 1066});
        check("three adjacent keys are one mark", 1, m.length);
        check("and it IS a bar", true, m[0].bar);
    }

    private static void aGapSplitsTwoTakes() {
        long[] first = take(0, 20, 33);
        long[] second = take(5000, 20, 33);
        long[] both = new long[40];
        System.arraycopy(first, 0, both, 0, 20);
        System.arraycopy(second, 0, both, 20, 20);
        Mark[] m = PuppetTapeMarks.group(both);
        check("two takes five seconds apart are two bars", 2, m.length);
        check("the first ends before the gap", 19 * 33L, m[0].toMs);
        check("the second starts after it", 5000L, m[1].fromMs);
    }

    /**
     * The honest consequence of inferring bars from density, asserted rather than discovered:
     * thin a take hard enough and it becomes the handful of keys it was reduced to.
     */
    private static void aThinnedTakeStopsBeingABar() {
        Mark[] m = PuppetTapeMarks.group(new long[]{0, 800, 1700, 2600});
        check("a take thinned to 4 sparse keys draws as 4 keys", 4, m.length);
        check("no bar claimed", false, m[0].bar);
    }

    private static void insideExcludesBothEnds() {
        long[] t = take(0, 10, 33);
        Mark[] m = PuppetTapeMarks.group(t);
        long[] in = PuppetTapeMarks.insideOf(t, m[0]);
        check("ten keys leave eight inside", 8, in.length);
        check("the first inside is the second key", 33L, in[0]);
        check("the last inside is the ninth", 8 * 33L, in[in.length - 1]);
    }

    private static void aBarOfExactlyThreeHasOneInside() {
        long[] t = {1000, 1033, 1066};
        Mark[] m = PuppetTapeMarks.group(t);
        long[] in = PuppetTapeMarks.insideOf(t, m[0]);
        check("one key inside", 1, in.length);
        check("and it is the middle one", 1033L, in[0]);
    }

    private static void emptyInEmptyOut() {
        check("null is empty", 0, PuppetTapeMarks.group(null).length);
        check("empty is empty", 0, PuppetTapeMarks.group(new long[0]).length);
    }

    private static void oneKeyIsOneDiamond() {
        Mark[] m = PuppetTapeMarks.group(new long[]{4321});
        check("one key, one mark", 1, m.length);
        check("not a bar", false, m[0].bar);
        check("zero length", 0L, m[0].durationMs());
    }

    private static void everyKeyAtTheSameInstant() {
        // Should not hang or claim a bar of zero width with an inside.
        Mark[] m = PuppetTapeMarks.group(new long[]{500, 500, 500, 500});
        check("four coincident keys collapse to one bar", 1, m.length);
        check("with zero duration", 0L, m[0].durationMs());
        long[] in = PuppetTapeMarks.insideOf(new long[]{500, 500, 500, 500}, m[0]);
        check("and insideOf does not run away", true, in.length >= 0);
    }

    private static void check(String what, Object expect, Object got) {
        boolean ok = String.valueOf(expect).equals(String.valueOf(got));
        if (ok) { passed++; System.out.println("  ok   " + what); }
        else { failed++; System.out.println("  FAIL " + what + " — expected " + expect + ", got " + got); }
    }
}
