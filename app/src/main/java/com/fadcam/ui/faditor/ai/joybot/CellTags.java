package com.fadcam.ui.faditor.ai.joybot;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The rules for Joybot's sprite cell TAGS ("mouth", "blink", "hand"), the second half of STATE
 * section 9 #12 (audit AN-05). Names say what one cell is; tags group cells across the sheet so a
 * puppet or lip-sync can find "every mouth". The SpriteLab screen already writes tags by hand
 * (lowercase, no repeats: {@code SpriteSheetEditorActivity}'s tag chips); Joybot follows the same
 * spelling so both land in one vocabulary. Pure Java, tested in IdeaEngineTest.
 */
public final class CellTags {
    private CellTags() { }

    /** How a request changes a cell's tags. */
    public enum Mode { ADD, SET, REMOVE }

    @NonNull
    public static Mode modeOf(@Nullable String s) {
        if (s == null) return Mode.ADD;
        switch (s.trim().toLowerCase(Locale.US)) {
            case "set": case "replace": return Mode.SET;
            case "remove": case "delete": case "clear": return Mode.REMOVE;
            default: return Mode.ADD;
        }
    }

    /** One tag as SpriteLab spells it: trimmed, lowercase, inner spaces kept single; "" if blank. */
    @NonNull
    public static String clean(@Nullable String tag) {
        if (tag == null) return "";
        return tag.trim().toLowerCase(Locale.US).replaceAll("\\s+", " ").replaceAll("^#", "");
    }

    /** "mouth, open" or ["mouth","open"] (already split by the caller) to clean tags, no repeats. */
    @NonNull
    public static List<String> parse(@NonNull List<String> raw) {
        List<String> out = new ArrayList<>();
        for (String r : raw) {
            for (String part : r.split(",")) {
                String t = clean(part);
                if (!t.isEmpty() && !out.contains(t)) out.add(t);
            }
        }
        return out;
    }

    /** {@code current} after the request, in a stable order (existing first, new ones after). */
    @NonNull
    public static List<String> apply(@NonNull List<String> current, @NonNull List<String> given, @NonNull Mode mode) {
        List<String> out = new ArrayList<>();
        switch (mode) {
            case SET:
                out.addAll(given);
                break;
            case REMOVE:
                for (String t : current) if (!given.contains(clean(t))) out.add(t);
                // "clear" with no tags given empties the cell's tags.
                if (given.isEmpty()) out.clear();
                break;
            default:
                out.addAll(current);
                for (String t : given) if (!out.contains(t)) out.add(t);
                break;
        }
        return out;
    }
}
