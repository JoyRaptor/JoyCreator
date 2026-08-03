import com.fadcam.ui.faditor.model.AnchorMath;

/**
 * Rider-attachment arithmetic (tasks/PLAN_LAYERS_UX_ADDENDUM.md §4A, PLAN_TIMELINE_MANIPULATION_V1 §2.0).
 *
 * Every check here maps to a CONFIRMED breakage from the 2026-08-03 adversarial audit of §4A draft 1,
 * or to a decision that audit forced. The open-end block is the important one: draft 1 would have
 * corrupted every open-ended item on any leftward ripple, silently, in a way that only shows up as
 * siblings being exiled to their own lanes on the NEXT project load.
 *
 * Compile+run (from the repo root; annotation stubs are not even needed — this class is primitive-only):
 *   javac -d tools/jvm-harness/out-anchor \
 *       app/src/main/java/com/fadcam/ui/faditor/model/AnchorMath.java \
 *       tools/jvm-harness/AnchorMathTest.java
 *   java -cp tools/jvm-harness/out-anchor AnchorMathTest
 */
public class AnchorMathTest {

    static int fails = 0;
    static int checks = 0;

    static void check(boolean c, String n) {
        checks++;
        System.out.println((c ? "PASS  " : "FAIL  ") + n);
        if (!c) fails++;
    }

    static void eq(long actual, long expected, String n) {
        check(actual == expected, n + "  (got " + actual + ", want " + expected + ")");
    }

    /** The naive implementation draft 1 would have shipped — kept ONLY to prove the test discriminates. */
    static long naiveShiftEnd(long endMs, long deltaMs) {
        return endMs + deltaMs;
    }

    public static void main(String[] a) {
        // ── 1. Prefix sums ────────────────────────────────────────────────────────────────
        long[] spans = {1000, 2000, 500};
        long[] starts = AnchorMath.startsFromSpans(spans);
        check(starts.length == 3 && starts[0] == 0 && starts[1] == 1000 && starts[2] == 3000,
                "startsFromSpans: [1000,2000,500] -> [0,1000,3000]");
        eq(AnchorMath.startsFromSpans(new long[0]).length, 0, "startsFromSpans: empty is empty");

        // ── 2. Host resolution is HALF-OPEN: a seam-exact start binds to the LATER clip ────
        eq(AnchorMath.hostIndexForStart(starts, spans, 0), 0, "host: t=0 -> clip 0");
        eq(AnchorMath.hostIndexForStart(starts, spans, 999), 0, "host: t=999 -> clip 0");
        eq(AnchorMath.hostIndexForStart(starts, spans, 1000), 1,
                "host: SEAM t=1000 -> clip 1 (LATER), not clip 0");
        eq(AnchorMath.hostIndexForStart(starts, spans, 3000), 2, "host: second seam -> clip 2");
        eq(AnchorMath.hostIndexForStart(starts, spans, 3499), 2, "host: last ms of last clip");
        eq(AnchorMath.hostIndexForStart(starts, spans, 3500), AnchorMath.NO_HOST,
                "host: exactly at the end -> NO_HOST (absolute time, not clamped to the last clip)");
        eq(AnchorMath.hostIndexForStart(starts, spans, 99999), AnchorMath.NO_HOST,
                "host: past the end -> NO_HOST");
        eq(AnchorMath.hostIndexForStart(starts, spans, -1), AnchorMath.NO_HOST,
                "host: negative time -> NO_HOST");

        // A zero-length clip occupies no instant and must not swallow the seam.
        long[] zSpans = {1000, 0, 500};
        long[] zStarts = AnchorMath.startsFromSpans(zSpans);
        eq(AnchorMath.hostIndexForStart(zStarts, zSpans, 1000), 2,
                "host: zero-span clip is skipped, seam falls to the next real clip");

        // ── 3. THE SENTINEL. This is the block that matters. ──────────────────────────────
        eq(AnchorMath.shiftEnd(AnchorMath.OPEN_END, -500), AnchorMath.OPEN_END,
                "shiftEnd: OPEN_END survives a NEGATIVE delta exactly");
        eq(AnchorMath.shiftEnd(AnchorMath.OPEN_END, +500), AnchorMath.OPEN_END,
                "shiftEnd: OPEN_END survives a positive delta exactly");
        eq(AnchorMath.shiftEnd(AnchorMath.OPEN_END, 0), AnchorMath.OPEN_END,
                "shiftEnd: OPEN_END survives a zero delta exactly");
        eq(AnchorMath.shiftEnd(5000, -500), 4500, "shiftEnd: a closed end shifts normally");
        eq(AnchorMath.shiftEnd(100, -5000), 0, "shiftEnd: a closed end floors at 0");

        // CONTROL — prove the check above can actually fail. If the naive version passed these,
        // the test would be measuring nothing (LEDGER: "check the control can discriminate").
        check(naiveShiftEnd(AnchorMath.OPEN_END, -500) != AnchorMath.OPEN_END,
                "CONTROL: the naive end-shift DOES corrupt the sentinel (so the test discriminates)");
        check(naiveShiftEnd(AnchorMath.OPEN_END, -500) > 0,
                "CONTROL: and it corrupts it into a POSITIVE value, which is why it is silent");
        check(naiveShiftEnd(AnchorMath.OPEN_END, +1) < 0,
                "CONTROL: a positive delta OVERFLOWS to negative — the other half of the same bug");

        // ── 4. Start shift, and the floor that should never fire ──────────────────────────
        eq(AnchorMath.shiftStart(1500, -1000), 500, "shiftStart: normal leftward shift");
        eq(AnchorMath.shiftStart(1500, +1000), 2500, "shiftStart: normal rightward shift");
        eq(AnchorMath.shiftStart(100, -5000), 0, "shiftStart: floors at 0 rather than going negative");

        // The invariant that makes the floor unreachable in practice: an item inside host H
        // shifted by H's own delta lands at or after H's new start.
        boolean floorUnreachable = true;
        for (long hostStart = 0; hostStart <= 5000; hostStart += 250) {
            for (long delta = -hostStart; delta <= 2000; delta += 250) {
                long itemStart = hostStart + 10;            // inside the host
                long newHostStart = hostStart + delta;      // never negative by construction
                if (AnchorMath.shiftStart(itemStart, delta) < newHostStart) floorUnreachable = false;
            }
        }
        check(floorUnreachable,
                "shiftStart: an item paired with its OWN host's delta never needs the floor");

        // ── 5. Truncation — the visualizer policy, kept distinct from SHIFT_ONLY ──────────
        eq(AnchorMath.truncatedEnd(AnchorMath.OPEN_END, 1000, 2000), 3000,
                "truncatedEnd: an open-ended rider clamps to the host's end");
        eq(AnchorMath.truncatedEnd(2500, 1000, 2000), 2500,
                "truncatedEnd: a closed end inside the host is untouched");
        eq(AnchorMath.truncatedEnd(9999, 1000, 2000), 3000,
                "truncatedEnd: a closed end past the host is clamped");

        // ── 6. Offset round-trip stability — the walk-forward bug this clamp prevents ─────
        eq(AnchorMath.offsetWithinHost(1500, 1000, 2000), 500, "offsetWithinHost: plain case");
        eq(AnchorMath.offsetWithinHost(500, 1000, 2000), 0,
                "offsetWithinHost: a start before the host clamps to 0");
        eq(AnchorMath.offsetWithinHost(9999, 1000, 2000), 1999,
                "offsetWithinHost: clamps to span-1, NOT span");

        // The property that clamp exists for: re-resolving a stored offset must return the SAME
        // host. An offset of exactly `span` would land on the next clip under the half-open rule,
        // so the attachment would walk forward one clip per save/load cycle.
        long[] rtSpans = {1000, 2000, 500};
        long[] rtStarts = AnchorMath.startsFromSpans(rtSpans);
        boolean roundTripStable = true;
        for (int host = 0; host < rtSpans.length; host++) {
            for (long raw : new long[]{-9999, 0, 1, rtSpans[host] / 2, rtSpans[host] - 1,
                                       rtSpans[host], rtSpans[host] + 1, 999999}) {
                long off = AnchorMath.offsetWithinHost(rtStarts[host] + raw,
                        rtStarts[host], rtSpans[host]);
                if (AnchorMath.hostIndexForStart(rtStarts, rtSpans,
                        rtStarts[host] + off) != host) roundTripStable = false;
            }
        }
        check(roundTripStable,
                "offsetWithinHost: a stored offset ALWAYS re-resolves to the same host (no walk-forward)");

        System.out.println();
        System.out.println(fails == 0
                ? ("ALL PASS — " + checks + " checks")
                : (fails + " FAILED of " + checks));
        if (fails != 0) System.exit(1);
    }
}
