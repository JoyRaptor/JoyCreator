import com.fadcam.ui.faditor.util.TimeFormatter;

/**
 * SPEC_C_SINGLE_FRAME — the typed frame-timecode grammar, pinned off device.
 *
 * The parser is the only piece of the single-frame export that is pure Java; everything
 * else needs a phone. These cases are the ones a typo or a paste can actually produce:
 * the formats the pre-fill uses, plain seconds, suffixed seconds, hour forms, and the
 * garbage that must be REJECTED (never clamped to the nearest edge — spec acceptance #4).
 *
 * bash tools/jvm-harness/run-frame-time.sh
 */
public class FrameTimecodeTest {

    private static int failures = 0;

    public static void main(String[] args) {
        // The formats the dialog pre-fills and the formats a user types.
        expect("00:00.0", 0L);
        expect("01:23.4", 83_400L);
        expect("01:23", 83_000L);
        expect("1:23.4", 83_400L);
        expect("4.5", 4_500L);
        expect("4.5s", 4_500L);
        expect("90", 90_000L);
        expect("90s", 90_000L);
        expect("1:02:03.25", 3_723_250L);
        expect("01:02:03.999", 3_723_999L);
        // Millisecond precision is capped at 3 digits, not discarded.
        expect("1.123456", 1_123L);
        // Whitespace tolerance.
        expect(" 01:23.4 ", 83_400L);

        // Garbage must be rejected (parser returns -1; the dialog shows an inline error).
        expectRejected(null);
        expectRejected("");
        expectRejected("   ");
        expectRejected("abc");
        expectRejected("1:2:3:4");
        expectRejected("-5");
        expectRejected("-0:05");
        expectRejected("01:23.");
        expectRejected(".5");
        expectRejected(":30");
        expectRejected("1:2a:3");
        expectRejected("1e3");
        expectRejected("0x10");

        if (failures > 0) {
            System.out.println("FAIL: " + failures + " case(s)");
            System.exit(1);
        }
        System.out.println("FrameTimecodeTest: all cases pass");
    }

    private static void expect(String input, long expectedMs) {
        long got = TimeFormatter.parseTimecodeMs(input);
        if (got != expectedMs) {
            failures++;
            System.out.println("FAIL parse(\"" + input + "\") = " + got
                    + ", expected " + expectedMs);
        }
    }

    private static void expectRejected(String input) {
        long got = TimeFormatter.parseTimecodeMs(input);
        if (got != -1L) {
            failures++;
            System.out.println("FAIL parse(\"" + input + "\") = " + got
                    + ", expected rejection (-1)");
        }
    }
}
