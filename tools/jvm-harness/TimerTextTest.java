import com.fadcam.ui.faditor.model.TimerSpec;
import com.fadcam.ui.faditor.model.TimerText;

import static com.fadcam.ui.faditor.model.TimerSpec.Basis.ABSOLUTE;
import static com.fadcam.ui.faditor.model.TimerSpec.Basis.RELATIVE;
import static com.fadcam.ui.faditor.model.TimerSpec.Direction.COUNT_DOWN;
import static com.fadcam.ui.faditor.model.TimerSpec.Direction.COUNT_UP;
import static com.fadcam.ui.faditor.model.TimerSpec.Precision.FRAMES;
import static com.fadcam.ui.faditor.model.TimerSpec.Precision.MILLIS;
import static com.fadcam.ui.faditor.model.TimerSpec.Precision.NONE;

/**
 * Pins SPEC_TIMER_OBJECT's arithmetic and formatting off-device.
 *
 * Run:
 *   javac -nowarn -d tools/jvm-harness/out4 \
 *     app/src/main/java/com/fadcam/ui/faditor/model/TimerSpec.java \
 *     app/src/main/java/com/fadcam/ui/faditor/model/TimerText.java \
 *     tools/jvm-harness/TimerTextTest.java
 *   java -cp tools/jvm-harness/out4 TimerTextTest
 */
public class TimerTextTest {

    static int pass = 0, fail = 0;

    static void eq(String what, String want, String got) {
        if (want.equals(got)) { pass++; System.out.println("PASS  " + what + " -> " + got); }
        else { fail++; System.out.println("FAIL  " + what + "\n      want=" + want + " got=" + got); }
    }

    static void eqL(String what, long want, long got) {
        if (want == got) { pass++; System.out.println("PASS  " + what + " -> " + got); }
        else { fail++; System.out.println("FAIL  " + what + "\n      want=" + want + " got=" + got); }
    }

    static TimerSpec spec(TimerSpec.Direction d, TimerSpec.Basis b,
            boolean h, boolean m, boolean s, TimerSpec.Precision p) {
        return new TimerSpec(d, b, h, m, s, p);
    }

    public static void main(String[] args) {
        final int FPS = 30;

        // ── The user's own example, verbatim ─────────────────────────────────
        // "trim comes in at 5s and out at 10s, toggled to countdown: once the playhead
        //  is at the beginning of the tape it starts at 5 and counts down to zero."
        TimerSpec relDown = spec(COUNT_DOWN, RELATIVE, false, true, true, NONE);
        eq("countdown@in-point reads the tape length",
                "0:05", TimerText.format(relDown, 5000, 5000, 10000, 60000, FPS));
        eq("countdown@out-point reads zero",
                "0:00", TimerText.format(relDown, 10000, 5000, 10000, 60000, FPS));
        eq("countdown mid-tape rounds UP (2.5s left shows 3)",
                "0:03", TimerText.format(relDown, 7500, 5000, 10000, 60000, FPS));
        eq("countdown holds the full value for its whole first second",
                "0:05", TimerText.format(relDown, 5001, 5000, 10000, 60000, FPS));
        eq("countdown shows 1 until the very last instant",
                "0:01", TimerText.format(relDown, 9999, 5000, 10000, 60000, FPS));

        // ── Count-up is the mirror image: floor, so the in-point reads 0 ─────
        TimerSpec relUp = spec(COUNT_UP, RELATIVE, false, true, true, NONE);
        eq("count-up@in-point reads zero",
                "0:00", TimerText.format(relUp, 5000, 5000, 10000, 60000, FPS));
        eq("count-up floors (2.5s in shows 2)",
                "0:02", TimerText.format(relUp, 7500, 5000, 10000, 60000, FPS));
        eq("count-up@out-point reads the tape length",
                "0:05", TimerText.format(relUp, 10000, 5000, 10000, 60000, FPS));

        // ── THE TRAP: endMs defaults to Long.MAX_VALUE on a fresh overlay ────
        // end-start would overflow to a negative/nonsense span. Must fall back to the
        // project's end instead.
        eqL("unbounded tape resolves to the project end",
                20000, TimerText.effectiveEndMs(5000, Long.MAX_VALUE, 20000));
        eq("countdown on an UNBOUNDED tape uses project end (15s span)",
                "0:15", TimerText.format(relDown, 5000, 5000, Long.MAX_VALUE, 20000, FPS));
        eq("unbounded tape AND zero-duration project degrades to 0, not garbage",
                "0:00", TimerText.format(relDown, 5000, 5000, Long.MAX_VALUE, 0, FPS));
        eq("tape running past the project end clamps to it",
                "0:10", TimerText.format(relDown, 5000, 5000, 999999, 15000, FPS));

        // ── Clamping outside the tape ───────────────────────────────────────
        eq("before the in-point clamps to the full span",
                "0:05", TimerText.format(relDown, 0, 5000, 10000, 60000, FPS));
        eq("after the out-point clamps to zero",
                "0:00", TimerText.format(relDown, 99999, 5000, 10000, 60000, FPS));
        eq("count-up before the in-point clamps to zero",
                "0:00", TimerText.format(relUp, 0, 5000, 10000, 60000, FPS));
        eq("zero-length tape reads zero",
                "0:00", TimerText.format(relDown, 5000, 5000, 5000, 60000, FPS));

        // ── ABSOLUTE basis = the project's own timing ───────────────────────
        TimerSpec absUp = spec(COUNT_UP, ABSOLUTE, false, true, true, NONE);
        TimerSpec absDown = spec(COUNT_DOWN, ABSOLUTE, false, true, true, NONE);
        eq("absolute count-up reads the playhead",
                "0:07", TimerText.format(absUp, 7500, 5000, 10000, 20000, FPS));
        eq("absolute count-up ignores the tape entirely",
                "0:07", TimerText.format(absUp, 7500, 0, 1, 20000, FPS));
        eq("absolute countdown reads project remaining",
                "0:13", TimerText.format(absDown, 7500, 5000, 10000, 20000, FPS));
        eq("absolute countdown at the project end reads zero",
                "0:00", TimerText.format(absDown, 20000, 0, 1, 20000, FPS));

        // ── Sub-second precision truncates (both directions) ────────────────
        TimerSpec downMs = spec(COUNT_DOWN, RELATIVE, false, true, true, MILLIS);
        eq("countdown with MILLIS does NOT round up",
                "0:02.500", TimerText.format(downMs, 7500, 5000, 10000, 60000, FPS));
        TimerSpec downFr = spec(COUNT_DOWN, RELATIVE, false, true, true, FRAMES);
        eq("countdown with FRAMES at 30fps (500ms = frame 15)",
                "0:02.15", TimerText.format(downFr, 7500, 5000, 10000, 60000, FPS));
        eq("frames never display a whole second (999ms @30 = 29)",
                "0:00.29", TimerText.render(downFr, 999, 30));
        eq("frames respect a non-30 rate (999ms @60 = 59)",
                "0:00.59", TimerText.render(downFr, 999, 60));
        eq("fps<=0 falls back to 30 rather than dividing by zero",
                "0:00.29", TimerText.render(downFr, 999, 0));

        // ── Field selection: the largest enabled unit absorbs the overflow ──
        TimerSpec ms_ = spec(COUNT_UP, RELATIVE, false, true, true, NONE);
        eq("hiding hours rolls them into minutes (1h02m -> 62:00)",
                "62:00", TimerText.render(ms_, 3_720_000L, FPS));
        TimerSpec hms = spec(COUNT_UP, RELATIVE, true, true, true, NONE);
        eq("hours shown splits properly",
                "1:02:00", TimerText.render(hms, 3_720_000L, FPS));
        TimerSpec sOnly = spec(COUNT_UP, RELATIVE, false, false, true, NONE);
        eq("seconds only absorbs minutes (90s -> 90)",
                "90", TimerText.render(sOnly, 90_000L, FPS));
        TimerSpec mOnly = spec(COUNT_UP, RELATIVE, false, true, false, NONE);
        eq("minutes only",
                "1", TimerText.render(mOnly, 90_000L, FPS));
        TimerSpec sFrames = spec(COUNT_UP, RELATIVE, false, false, true, FRAMES);
        eq("seconds + frames",
                "90.15", TimerText.render(sFrames, 90_500L, FPS));

        // ── Degenerate specs must still render something ────────────────────
        TimerSpec empty = spec(COUNT_UP, RELATIVE, false, false, false, NONE);
        eq("a spec with no fields falls back to seconds instead of \"\"",
                "5", TimerText.render(empty, 5000L, FPS));
        eqL("hasAnyField() reports the degenerate spec", 0, empty.hasAnyField() ? 1 : 0);

        // ── Countdown rounding at coarser resolutions ───────────────────────
        TimerSpec mDown = spec(COUNT_DOWN, RELATIVE, false, true, false, NONE);
        eq("minute-resolution countdown rounds up to the next minute",
                "2", TimerText.render(mDown, 61_000L, FPS));
        eq("minute-resolution countdown exact boundary does not over-round",
                "1", TimerText.render(mDown, 60_000L, FPS));

        // ── null spec = an ordinary text overlay ────────────────────────────
        if (TimerText.format(null, 0, 0, 1, 1, FPS) == null) {
            pass++; System.out.println("PASS  null spec returns null (ordinary text overlay)");
        } else { fail++; System.out.println("FAIL  null spec must return null"); }

        // ── copy() must not share mutable state ─────────────────────────────
        TimerSpec a = spec(COUNT_DOWN, RELATIVE, false, true, true, NONE);
        TimerSpec b = a.copy();
        b.setDirection(COUNT_UP);
        eqL("copy() is a deep copy (original unchanged)",
                1, a.getDirection() == COUNT_DOWN ? 1 : 0);
        eqL("equals() distinguishes a mutated copy", 0, a.equals(b) ? 1 : 0);
        eqL("equals() matches an untouched copy", 1, a.equals(a.copy()) ? 1 : 0);

        System.out.println();
        System.out.println(fail == 0 ? "ALL PASS (" + pass + ")"
                : fail + " FAILED of " + (pass + fail));
        if (fail != 0) System.exit(1);
    }
}
