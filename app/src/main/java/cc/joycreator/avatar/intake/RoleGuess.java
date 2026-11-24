package cc.joycreator.avatar.intake;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * What a layer IS, worked out from its name (Character Animator's names and everyday ones): the naming half of the
 * pilot's {@code al-autorig.js} ({@code roleOf}, {@code sideOf}, {@code clean}). Pins and other pixel guesses are not
 * here; they come with the solver. A guess is only a name on the layer: the person can change it with one tap.
 */
public final class RoleGuess {
    private RoleGuess() {}

    /** Run-together names are split into their words first ("LeftArm2" becomes "left arm 2"), then lower-cased. */
    static String lc(String s) {
        if (s == null) return "";
        String t = s.replaceAll("([a-z0-9])([A-Z])", "$1 $2").replaceAll("([A-Za-z])(\\d)", "$1 $2");
        return t.toLowerCase(Locale.ROOT);
    }

    /** The name without a leading "+" (a layer that moves free), squeezed, or "Layer" when nothing is left. */
    public static String clean(String s) {
        String t = (s == null ? "" : s).replaceAll("^\\s*\\+\\s*", "").replaceAll("\\s+", " ").trim();
        return t.isEmpty() ? "Layer" : t;
    }

    private static boolean has(String s, String re) {
        return Pattern.compile(re).matcher(s).find();
    }

    /** "L", "R" or null: which side a name says. */
    public static String sideOf(String name) {
        String s = " " + lc(name).replaceAll("[_\\-.]", " ") + " ";
        if (has(s, " (left|l|lft) ")) return "L";
        if (has(s, " (right|r|rgt) ")) return "R";
        return null;
    }

    /** The role a name suggests (head, armL, mouth ...), or null. */
    public static String roleOf(String name, String parentRole, String inheritedSide) {
        String s = lc(name).replaceAll("[_\\-.]", " ");
        String side = sideOf(name);
        if (side == null) side = inheritedSide;
        if (has(s, "pupil|iris")) return "pupil";
        if (has(s, "eyeball|eye white|sclera")) return "eyeball";
        if (has(s, "\\blids?\\b|eyelid")) return has(s, "lower|bottom|under|cheek") ? "lidL" : "lidU";
        if (has(s, "blink|eye closed|closed eye")) return "blink";
        if (has(s, "brow")) return "R".equals(side) ? "browR" : "browL";
        if (has(s, "\\beyes?\\b")) return "R".equals(side) ? "eyeR" : "eyeL";
        if ("mouth".equals(parentRole)) return null;
        if (has(s, "mouth|lips")) return "mouth";
        if (has(s, "\\b(jaw|chin)\\b")) return "jaw";
        if (has(s, "nose|snout|muzzle|beak")) return "nose";
        if (has(s, "hair|bangs?\\b|frill|crest|\\bears?\\b|horn|antenna|mane|feather|hat\\b")) return "hair";
        if (has(s, "\\bhead\\b")) return "head";
        if (has(s, "\\bface\\b|skull")) return "face";
        if (has(s, "\\barms?\\b|\\barm ") || has(s, "\\barm")) return "R".equals(side) ? "armR" : "armL";
        if (has(s, "\\b(legs?|foot|feet)\\b")) return "R".equals(side) ? "legR" : "legL";
        if (has(s, "\\btail\\b")) return "tail";
        if (has(s, "\\b(torso|chest|belly)\\b")) return "torso";
        if (has(s, "\\bbody\\b")) return "body";
        return null;
    }
}
