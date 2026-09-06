import com.fadcam.ui.faditor.keyframe.KeyframeSet;

/**
 * The typed rotation field's grammar (SPEC A — rotation beyond one full turn), pinned off
 * device. Every check is a sentence about what JoyRaptor asked to type, not about the
 * implementation: whole degrees past 360, negative degrees, and "16x" for sixteen turns.
 *
 * <p>The winding rule behind them: 370° and 10° are the same POSE but a different ANIMATION,
 * so the parser must hand back the number AS TYPED — no folding into 0..360 anywhere.</p>
 */
public class RotationInputTest {
    static int fails = 0;
    static void eq(float want, Float got, String n) {
        boolean c = got != null && Math.abs(got - want) < 1e-4f;
        System.out.println((c ? "PASS  " : "FAIL  ") + n
                + (c ? "" : "  (got " + got + ", want " + want + ")"));
        if (!c) fails++;
    }
    static void reject(String s, String n) {
        boolean c = KeyframeSet.parseRotationInput(s) == null;
        System.out.println((c ? "PASS  " : "FAIL  ") + n + " rejected  (input: \"" + s + "\")");
        if (!c) fails++;
    }

    public static void main(String[] a) {
        // Plain degrees, including everything the old slider window refused.
        eq(720f,   KeyframeSet.parseRotationInput("720"),   "720 stores 720, not 0");
        eq(-45f,   KeyframeSet.parseRotationInput("-45"),   "-45 stores -45, not 315");
        eq(1080f,  KeyframeSet.parseRotationInput("1080"),  "1080 stores 1080");
        eq(0f,     KeyframeSet.parseRotationInput("0"),     "0 is 0");
        eq(360f,   KeyframeSet.parseRotationInput("360"),   "360 stays 360 — one full turn is a real value, not a fold to 0");
        eq(90f,    KeyframeSet.parseRotationInput("+90"),   "explicit + is accepted");
        eq(22.5f,  KeyframeSet.parseRotationInput("22.5"),  "fractional degrees are accepted");
        eq(-0.5f,  KeyframeSet.parseRotationInput("-.5"),   "-.5 is a number");

        // The multiplier form: whole turns, exactly as asked.
        eq(5760f,  KeyframeSet.parseRotationInput("16x"),   "16x = sixteen turns = 5760");
        eq(5760f,  KeyframeSet.parseRotationInput("16X"),   "16X (capital) = 5760 too");
        eq(5760f,  KeyframeSet.parseRotationInput("16 x"),  "16 x (space) = 5760 too");
        eq(360f,   KeyframeSet.parseRotationInput("1x"),    "1x = one turn = 360");
        eq(-720f,  KeyframeSet.parseRotationInput("-2x"),   "-2x = two turns backwards = -720");
        eq(900f,   KeyframeSet.parseRotationInput("2.5x"),  "2.5x = two and a half turns = 900");
        eq(0f,     KeyframeSet.parseRotationInput("0x"),    "0x is 0 degrees");
        eq(5760f,  KeyframeSet.parseRotationInput(" 16x "), "surrounding whitespace is ignored");

        // Garbage is rejected — and the caller keeps the old value on null (house rule),
        // so a rejected field can never silently write 0 over a value JoyRaptor set.
        reject(null,  "null text");
        reject("",    "empty");
        reject("   ", "whitespace only");
        reject("x",   "bare x");
        reject("abc", "letters");
        reject("1e9", "exponent form — parseFloat would silently read this as 1e9, we refuse it");
        reject("--5", "double sign");
        reject("+-5", "mixed sign");
        reject("1.2.3", "two dots");
        reject("16 x y", "trailing junk");
        reject("720°", "the degree sign itself is not typed — the field seeds plain digits");
        reject("1,5", "comma is not a decimal dot here");

        // Values so large they leave float must not become Infinity and poison a track.
        reject("1" + "0".repeat(42), "an overflow of digits is refused, not stored as Infinity");
        reject("1" + "0".repeat(42) + "x", "overflowing turns too");

        System.out.println(fails == 0 ? "ALL GREEN" : (fails + " FAILED"));
        if (fails != 0) System.exit(1);
    }
}
