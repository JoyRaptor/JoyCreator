import com.fadcam.ui.faditor.transcript.WordSyncRipple;
import com.fadcam.ui.faditor.transcript.WordSyncRipple.Mode;

import java.util.Arrays;

/**
 * Ripple / stretch arithmetic for SPEC_20260829_WORD_SYNC §3.4.
 *
 * <p>This is the part of Word Sync that moves a hundred words on one gesture, so it is
 * exactly the part that must not be checked by dragging on a phone and squinting.
 *
 *   bash tools/jvm-harness/run-wordsync.sh
 */
public class WordSyncRippleTest {

    static int failures = 0;

    static void check(boolean cond, String msg) {
        System.out.println((cond ? "  PASS  " : "  FAIL  ") + msg);
        if (!cond) failures++;
    }

    static boolean ascending(long[] a) {
        for (int i = 1; i < a.length; i++) {
            if (a[i] < a[i - 1] + WordSyncRipple.MIN_GAP_MS) return false;
        }
        return true;
    }

    public static void main(String[] args) {
        System.out.println("WordSyncRipple");

        long[] base = {1000, 2000, 3000, 4000, 5000};

        // ONE — only the dragged word moves, and the source array is not touched.
        long[] one = WordSyncRipple.apply(base, 2, 3200, Mode.ONE, 10000);
        check(Arrays.equals(one, new long[]{1000, 2000, 3200, 4000, 5000}), "ONE moves only the dragged word");
        check(base[2] == 3000, "input array is never mutated (undo needs the old values)");

        // ONE is fenced by its neighbours: a wild drag cannot reorder the transcript.
        long[] past = WordSyncRipple.apply(base, 2, 99000, Mode.ONE, 10000);
        check(past[2] == 4000 - WordSyncRipple.MIN_GAP_MS, "ONE clamps below the next word (got " + past[2] + ")");
        long[] before = WordSyncRipple.apply(base, 2, -5000, Mode.ONE, 10000);
        check(before[2] == 2000 + WordSyncRipple.MIN_GAP_MS, "ONE clamps above the previous word");

        // RIPPLE — the fix for "the whole thing starts late". Constant offset, everything after.
        long[] rip = WordSyncRipple.apply(base, 1, 2500, Mode.RIPPLE, 10000);
        check(Arrays.equals(rip, new long[]{1000, 2500, 3500, 4500, 5500}), "RIPPLE shifts all later words equally (" + Arrays.toString(rip) + ")");
        check(rip[0] == 1000, "RIPPLE never touches words BEFORE the drag");

        // RIPPLE backwards.
        long[] ripBack = WordSyncRipple.apply(base, 1, 1500, Mode.RIPPLE, 10000);
        check(Arrays.equals(ripBack, new long[]{1000, 1500, 2500, 3500, 4500}), "RIPPLE works backwards too");

        // STRETCH — the fix for "it drifts further out as it goes". The anchor STAYS PUT.
        long[] st = WordSyncRipple.apply(base, 1, 2500, Mode.STRETCH, 5000);
        check(st[1] == 2500, "STRETCH moves the dragged word");
        check(st[4] == 5000, "STRETCH leaves a word ON the anchor exactly where it was (got " + st[4] + ")");
        // old span 2000->5000 = 3000; new span 2500->5000 = 2500; scale 5/6.
        // word at 3000 is 1000 past the drag -> 2500 + 833 = 3333
        check(Math.abs(st[2] - 3333) <= 1, "STRETCH redistributes proportionally (got " + st[2] + ", want ~3333)");
        check(Math.abs(st[3] - 4167) <= 1, "STRETCH scales the far word less (got " + st[3] + ", want ~4167)");
        check(ascending(st), "STRETCH output stays ordered: " + Arrays.toString(st));

        // A word already past the anchor is not the stretch's business.
        long[] pastAnchor = {1000, 2000, 3000, 9000};
        long[] pa = WordSyncRipple.apply(pastAnchor, 1, 2500, Mode.STRETCH, 5000);
        check(pa[3] == 9000, "STRETCH leaves words beyond the anchor alone");

        // Degenerate: dragging onto or past the anchor leaves no span to scale. Must not
        // divide by zero, and must not collapse every later word onto the anchor.
        long[] onAnchor = WordSyncRipple.apply(base, 1, 5000, Mode.STRETCH, 5000);
        check(ascending(onAnchor), "drag ONTO the anchor degrades safely: " + Arrays.toString(onAnchor));
        long[] beyond = WordSyncRipple.apply(base, 1, 6000, Mode.STRETCH, 5000);
        check(ascending(beyond), "drag PAST the anchor degrades safely: " + Arrays.toString(beyond));

        // Dense passage: proportional scaling can round neighbours onto one millisecond.
        long[] dense = {1000, 1020, 1040, 1060, 1080, 5000};
        long[] d = WordSyncRipple.apply(dense, 0, 900, Mode.STRETCH, 5000);
        check(ascending(d), "dense words keep MIN_GAP after rounding: " + Arrays.toString(d));

        // Edges.
        long[] last = WordSyncRipple.apply(base, 4, 5500, Mode.RIPPLE, 10000);
        check(last[4] == 5500, "dragging the LAST word works");
        long[] first = WordSyncRipple.apply(base, 0, 500, Mode.RIPPLE, 10000);
        check(Arrays.equals(first, new long[]{500, 1500, 2500, 3500, 4500}), "dragging the FIRST word ripples everything");
        check(Arrays.equals(WordSyncRipple.apply(base, 9, 1, Mode.RIPPLE, 10000), base), "out-of-range index is a no-op");
        check(WordSyncRipple.apply(new long[0], 0, 1, Mode.RIPPLE, 10).length == 0, "empty transcript is a no-op");

        // anchorFor: the next PINNED word wins, otherwise the fallback.
        boolean[] pins = {false, false, false, true, false};
        check(WordSyncRipple.anchorFor(base, pins, 1, 99999) == 4000, "anchorFor finds the next pinned word");
        check(WordSyncRipple.anchorFor(base, pins, 3, 99999) == 99999, "anchorFor falls back past the last pin");

        System.out.println(failures == 0 ? "\nALL PASS" : "\n" + failures + " FAILURE(S)");
        if (failures != 0) System.exit(1);
    }
}
