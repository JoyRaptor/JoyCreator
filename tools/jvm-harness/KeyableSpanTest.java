import com.fadcam.ui.faditor.model.KeyableSpan;

/**
 * The "is there anywhere to put this keyframe" predicate, pinned off device.
 *
 * <p>Written against the device measurement that created it: PiP {@code 6126e8b4} has span
 * [5501, 13629] and a tap at ph=4973 wrote a key anyway. Every check below is a sentence about
 * what a user's finger does, not about the implementation.</p>
 */
public class KeyableSpanTest {
    static int fails = 0;
    static void check(boolean c, String n) {
        System.out.println((c ? "PASS  " : "FAIL  ") + n);
        if (!c) fails++;
    }

    public static void main(String[] a) {
        // The exact numbers off the device, which is what makes this a regression test and not
        // an invented case: this tap MUST be refused.
        final long S = 5501L, E = 13629L;
        check(!KeyableSpan.contains(S, E, 4973), "the measured bug: ph=4973 on span [5501,13629] is REFUSED");
        check(KeyableSpan.contains(S, E, 6000), "a playhead in the middle of the span is accepted");

        // Both ends are CLOSED. The last frame is a pose people key on purpose.
        check(KeyableSpan.contains(S, E, S, 0), "exactly the first ms is inside (zero slop)");
        check(KeyableSpan.contains(S, E, E, 0), "exactly the last ms is inside (zero slop)");

        // Just past the ends with NO slop — these are what prove the boundary is where it says
        // it is. With the default slop they would both pass, so zero slop is the discriminator.
        check(!KeyableSpan.contains(S, E, S - 1, 0), "one ms before the start is outside (zero slop)");
        check(!KeyableSpan.contains(S, E, E + 1, 0), "one ms after the end is outside (zero slop)");

        // The slop is real and symmetric — a finger that parks 30ms short of the start still
        // means "the start".
        check(KeyableSpan.contains(S, E, S - 30), "30ms before the start is INSIDE with default slop");
        check(KeyableSpan.contains(S, E, E + 30), "30ms after the end is INSIDE with default slop");
        check(!KeyableSpan.contains(S, E, S - 41), "41ms before the start is outside (past the 40ms slop)");
        check(!KeyableSpan.contains(S, E, E + 41), "41ms after the end is outside (past the 40ms slop)");
        check(KeyableSpan.contains(S, E, S - 200, 250), "a caller may widen the slop");

        // A degenerate span has nowhere to put anything — answering "yes" here walks straight
        // back into the bug.
        check(!KeyableSpan.contains(1000, 1000, 1000), "a zero-length span accepts nothing, not even its own ms");
        check(!KeyableSpan.contains(2000, 1000, 1500), "an inverted span accepts nothing");
        check(!KeyableSpan.contains(0, 0, 0), "an empty span at the origin accepts nothing");

        // A span starting at 0 is the common case for a master-length object; the origin must
        // not be special-cased into a refusal.
        check(KeyableSpan.contains(0, 5000, 0), "ms 0 of a span that starts at 0 is inside");
        check(KeyableSpan.contains(0, 5000, 5000), "the last ms of a span that starts at 0 is inside");
        check(!KeyableSpan.contains(0, 5000, 5041), "past the end of a span that starts at 0 is outside");

        // A negative playhead cannot be legal for a span that starts at 0 — the guard must not
        // be fooled by arithmetic that goes below zero.
        check(!KeyableSpan.contains(0, 5000, -100), "a negative playhead is outside a [0,5000] span");

        // Bad arguments must never make a LEGAL tap illegal: negative slop clamps to 0 rather
        // than shrinking the span inward.
        check(KeyableSpan.contains(S, E, 6000, -500), "negative slop does not shrink the span");
        check(KeyableSpan.contains(S, E, S, -500), "negative slop still admits the exact start");

        System.out.println(fails == 0 ? "ALL GREEN" : (fails + " FAILED"));
        if (fails != 0) System.exit(1);
    }
}
