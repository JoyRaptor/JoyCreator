import com.fadcam.ui.faditor.transcript.CaptionAnimator;
import com.fadcam.ui.faditor.transcript.CaptionAnimator.Granularity;

/**
 * What a "letter" IS, when the text is not ASCII.
 *
 * <p>JoyRaptor, 2026-08-15: a row of 🕯 animated correctly at BLOCK granularity and "turned into
 * question marks on little white diamonds" the moment he chose letter-level animation. U+1F56F is
 * outside the BMP, so it is a surrogate PAIR — and {@code splitUnits} emitted one unit per
 * {@code char}, handing the renderer two half-characters. An unpaired surrogate has no glyph, so
 * it drew tofu.</p>
 *
 * <p>Every assertion below FAILS against the per-char splitter. Deliberately its own file rather
 * than an addition to CaptionAnimatorTest: that file imports CaptionStyle, which reaches
 * CaptionStyleStore, which needs SharedPreferences and org.json — a stub chain none of these
 * assertions touch. This one imports the animator and nothing else, which is why it can run.</p>
 */
public class GraphemeUnitsTest {

    static int passed, failed;

    public static void main(String[] args) {
        String candles = "🕯🕯🕯";
        int[][] cl = CaptionAnimator.splitUnits(candles, Granularity.LETTER);
        ok("three candles are THREE letter units, not six half-characters", cl.length == 3);
        ok("...and each unit spans the whole surrogate pair",
                cl.length == 3 && cl[0][1] - cl[0][0] == 2);
        ok("...so every unit is a complete, drawable string", wellFormed(candles, cl));

        // The clusters a plain Character.charCount loop would still tear apart.
        ok("an emoji plus its variation selector is one unit",
                CaptionAnimator.splitUnits("🕯️", Granularity.LETTER).length == 1);
        ok("a base letter plus a combining accent is one unit",
                CaptionAnimator.splitUnits("e\u0301", Granularity.LETTER).length == 1);

        // NOT ASSERTED: a regional-indicator flag pair.
        //
        // It splits into two units under this JVM and does not on a device, and neither result
        // is this code's doing: java.text.BreakIterator on the desktop JDK predates UAX #29's
        // extended grapheme clusters for regional indicators, while Android's is ICU-backed and
        // joins them. Asserting either answer would pin the HARNESS's platform rather than the
        // app's, and a green tick here would be a guarantee about devices this file cannot see.
        //
        // The same caveat covers newer ZWJ sequences on old releases: what counts as one cluster
        // is the platform's ICU version, and on minSdk 24 that is older than the emoji people
        // type today. The surrogate-pair, variation-selector and combining-mark cases above are
        // the ones every level agrees on — and the ones that were actually broken. Flags and
        // multi-person ZWJ emoji at LETTER granularity want a device to confirm.

        // The plain path must be untouched — this is the regression guard on the fix itself.
        int[][] ascii = CaptionAnimator.splitUnits("ab c", Granularity.LETTER);
        ok("ASCII still splits per character", ascii.length == 3);
        ok("...and whitespace still belongs to no unit", wellFormed("ab c", ascii));
        ok("BLOCK is still one unit over emoji",
                CaptionAnimator.splitUnits(candles, Granularity.BLOCK).length == 1);
        ok("WORD keeps a run of emoji whole",
                CaptionAnimator.splitUnits(candles, Granularity.WORD).length == 1);

        System.out.println(failed == 0
                ? "ALL GREEN (" + passed + "/" + (passed + failed) + ")"
                : "FAILURES: " + failed + " (passed " + passed + ")");
        if (failed > 0) System.exit(1);
    }

    /**
     * No unit may begin on a low surrogate or end on a high one. That is the property the tofu
     * violated, so it is the property worth asserting — a unit COUNT alone would pass for a
     * splitter that got the count right and the boundaries wrong.
     */
    static boolean wellFormed(String text, int[][] units) {
        for (int[] u : units) {
            if (Character.isLowSurrogate(text.charAt(u[0]))) return false;
            if (Character.isHighSurrogate(text.charAt(u[1] - 1))) return false;
        }
        return true;
    }

    static void ok(String what, boolean cond) {
        if (cond) { passed++; System.out.println("  ok   " + what); }
        else { failed++; System.out.println("  FAIL " + what); }
    }
}
