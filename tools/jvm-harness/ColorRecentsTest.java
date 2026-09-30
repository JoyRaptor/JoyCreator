import com.fadcam.ui.faditor.tools.ColorRecents;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * D.02c — the app-wide colour history's list logic, without Android (the helpers are pure static methods).
 * Every expected value is derived in the check that uses it.
 */
public class ColorRecentsTest {

    private static int passed = 0, failed = 0;

    public static void main(String[] args) {
        thirteenDistinctKeepTwelveAndDropTheOldest();
        anExistingColourMovesToTheFrontWithoutDuplicating();
        aDifferenceInAlphaOnlyIsTheSameColour();
        theStoredFormatRoundTripsAndReadsTheOldEightColourValue();
        garbageInTheStoredStringIsSkipped();
        System.out.println(failed == 0
                ? "ALL GREEN (" + passed + "/" + (passed + failed) + ")"
                : "FAILURES: " + failed + " (passed " + passed + ")");
        if (failed > 0) System.exit(1);
    }

    static int c(int rgb) { return 0xFF000000 | rgb; }

    static void thirteenDistinctKeepTwelveAndDropTheOldest() {
        List<Integer> l = new ArrayList<>();
        for (int i = 1; i <= 13; i++) ColorRecents.pushInto(l, c(i));
        check("13 pushes leave MAX = 12", l.size() == ColorRecents.MAX && ColorRecents.MAX == 12);
        check("the newest (13) is first", l.get(0) == c(13));
        check("the oldest (1) is gone and 2 is last", !l.contains(c(1)) && l.get(11) == c(2));
    }

    static void anExistingColourMovesToTheFrontWithoutDuplicating() {
        List<Integer> l = new ArrayList<>(Arrays.asList(c(3), c(2), c(1)));
        ColorRecents.pushInto(l, c(1));
        check("pushing an existing colour moves it to the front", l.equals(Arrays.asList(c(1), c(3), c(2))));
    }

    static void aDifferenceInAlphaOnlyIsTheSameColour() {
        List<Integer> l = new ArrayList<>(Arrays.asList(c(0x336699), c(0x111111)));
        ColorRecents.pushInto(l, 0x80336699);   // same RGB, half alpha
        check("alpha-only difference: still two entries", l.size() == 2);
        check("...and the newest spelling wins, at the front", l.get(0) == 0x80336699 && l.get(1) == c(0x111111));
    }

    static void theStoredFormatRoundTripsAndReadsTheOldEightColourValue() {
        // What the picker wrote before this row: eight ARGB values, comma-separated Integer.toHexString, newest first.
        String old = "ffff0000,ff00ff00,ff0000ff,ffffff00,ff00ffff,ffff00ff,ff808080,ff000000";
        List<Integer> l = ColorRecents.parse(old);
        check("the old value parses to 8 colours", l.size() == 8);
        check("newest first: red", l.get(0) == 0xFFFF0000);
        check("last is black", l.get(7) == 0xFF000000);
        check("format is the inverse of parse", ColorRecents.format(l).equals(old));
        ColorRecents.pushInto(l, c(0x123456));
        check("a ninth colour is kept now (cap 12, was 8)", l.size() == 9 && l.get(0) == c(0x123456));
    }

    static void garbageInTheStoredStringIsSkipped() {
        List<Integer> l = ColorRecents.parse("ffff0000,zzz,,ff00ff00");
        check("garbage parts are skipped", l.size() == 2 && l.get(1) == 0xFF00FF00);
        check("null and empty give an empty list", ColorRecents.parse(null).isEmpty() && ColorRecents.parse("").isEmpty());
    }

    static void check(String what, boolean ok) {
        if (ok) passed++; else { failed++; System.out.println("FAIL: " + what); }
    }
}
