import com.fadcam.ui.faditor.util.FlexibleTimeParser;

/**
 * Pins the grammar of SPEC_IMAGE_SEQUENCE §3c. The spec's own examples are the first cases,
 * verbatim, so the test fails if the shipped parser drifts from the written promise.
 *
 * Also pins the OLD parseTimeToMs grammar (ss / m:ss / h:mm:ss), because this class replaces it
 * app-wide: anything a user could type into the jump-to-time box before must still work.
 */
public class FlexibleTimeTest {
    static int pass = 0, fail = 0;

    static void eq(String input, long expectedMs) { eq(input, 30f, expectedMs); }

    static void eq(String input, float fps, long expectedMs) {
        long got = FlexibleTimeParser.parseToMs(input, fps);
        if (got == expectedMs) { pass++; }
        else { fail++; System.out.println("FAIL  " + q(input) + " -> " + got + ", expected " + expectedMs); }
    }

    static void invalid(String input) {
        long got = FlexibleTimeParser.parseToMs(input, 30f);
        if (got == FlexibleTimeParser.INVALID) { pass++; }
        else { fail++; System.out.println("FAIL  " + q(input) + " should be INVALID, got " + got); }
    }

    static String q(String s) { return s == null ? "null" : "\"" + s + "\""; }

    public static void main(String[] args) {
        // ── The spec's examples, verbatim ────────────────────────────────────────────────
        eq("10.5s", 10_500);
        eq("10.5 sec", 10_500);
        eq("10.5 seconds", 10_500);
        eq("1/6 min", 10_000);              // a sixth of 60s
        eq("00:00:10.5", 10_500);
        eq("90f", 30f, 3_000);              // 90 frames at 30fps
        eq("2m30s", 150_000);

        // ── The grammar this replaces must keep working ──────────────────────────────────
        eq("10", 10_000);                   // bare number = seconds
        eq("1:30", 90_000);
        eq("1:00:00", 3_600_000);
        eq("0", 0);
        eq("90", 90_000);                   // NOT frames: no unit means seconds

        // ── Frames depend on the rate, which is the whole point of passing one ───────────
        eq("90f", 24f, 3_750);
        eq("1f", 25f, 40);
        eq("30 frames", 30f, 1_000);
        eq("10f", 30f, 333);                // 10 frames at 30fps, rounded
        // fps<=0 is not a rate — and that is a property of the ARGUMENT, not of the string.
        if (FlexibleTimeParser.parseToMs("10f", 0f) != FlexibleTimeParser.INVALID) {
            fail++; System.out.println("FAIL  frames at fps=0 must be INVALID");
        } else { pass++; }

        // ── Units, long and short, and compounds ─────────────────────────────────────────
        eq("500ms", 500);
        eq("1h", 3_600_000);
        eq("1 hour", 3_600_000);
        eq("2 minutes", 120_000);
        eq("1h2m3s", 3_723_000);
        eq("1m 30s", 90_000);
        eq("3/4 s", 750);

        // 'm' vs 'ms' is a real hazard: longest-match must win or "500ms" reads as 500 minutes.
        eq("500m", 30_000_000);
        eq("500ms", 500);

        // ── Typography people actually produce ───────────────────────────────────────────
        eq("  10.5s  ", 10_500);
        eq("10,5s", 10_500);                // comma decimal separator
        eq("10.5\u00a0s", 10_500);          // non-breaking space from a paste
        eq("10.5S", 10_500);                // case

        // ── Rejections. INVALID must never be confused with 0. ───────────────────────────
        invalid(null);
        invalid("");
        invalid("   ");
        invalid("abc");
        invalid("s");                       // unit with no number
        invalid("10x");                     // unknown unit
        invalid("1/0 min");                 // divide by zero
        invalid("1/2/3 s");
        invalid("1.5:30");                  // fraction in a non-final clock field
        invalid("1:2:3:4");                 // too many clock fields
        invalid("1::2");
        invalid(".");

        System.out.println((fail == 0 ? "FLEXIBLETIME OK" : "FLEXIBLETIME FAILED")
                + " — " + pass + " passed, " + fail + " failed");
        if (fail != 0) System.exit(1);
    }
}
