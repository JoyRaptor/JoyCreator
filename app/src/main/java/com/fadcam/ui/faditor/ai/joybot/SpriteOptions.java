package com.fadcam.ui.faditor.ai.joybot;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Locale;

/**
 * The words Joybot's {@code set_sprite_options} accepts (audit BOT-A31): mouth shapes in the
 * names SpriteLab and the lip-sync use ({@code SpectralVisemeAnalyzer.CLASS_NAMES}: REST, AA, EE,
 * OO, CLOSURE, FRIC) plus the everyday spellings a model writes ("ah", "mbp", "f/v"), and a key
 * colour as "#RRGGBB" or "none". Pure Java, tested in IdeaEngineTest.
 */
public final class SpriteOptions {
    private SpriteOptions() { }

    public static final String[] VISEMES = {"REST", "AA", "EE", "OO", "CLOSURE", "FRIC"};

    /** The SpriteLab name of a mouth shape, or null when it isn't one. */
    @Nullable
    public static String viseme(@Nullable String said) {
        if (said == null) return null;
        String s = said.trim().toUpperCase(Locale.US).replaceAll("[^A-Z]", "");
        for (String v : VISEMES) if (v.equals(s)) return v;
        switch (s) {
            case "NEUTRAL": case "IDLE": case "SILENT": case "SILENCE": case "X": return "REST";
            case "A": case "AH": case "AI": case "OPEN": return "AA";
            case "E": case "I": case "EH": case "EEE": case "SMILE": return "EE";
            case "O": case "U": case "OH": case "OOO": case "W": case "WQ": case "ROUND": return "OO";
            case "M": case "B": case "P": case "MBP": case "CLOSED": case "SHUT": return "CLOSURE";
            case "F": case "V": case "FV": case "S": case "Z": case "SH": case "TH": return "FRIC";
            default: return null;
        }
    }

    /**
     * A key colour as SpriteLab stores it: opaque ARGB, or 0 for "no key". Null when it can't be read
     * (the caller says so instead of guessing).
     */
    @Nullable
    public static Integer keyColour(@Nullable String said) {
        if (said == null) return null;
        String s = said.trim().toLowerCase(Locale.US);
        if (s.equals("none") || s.equals("off") || s.equals("no")) return 0;
        if (s.equals("green")) return 0xFF00FF00;
        if (s.equals("blue")) return 0xFF0000FF;
        if (s.equals("magenta") || s.equals("pink")) return 0xFFFF00FF;
        if (s.equals("white")) return 0xFFFFFFFF;
        if (s.equals("black")) return 0xFF000000;
        if (s.startsWith("#")) s = s.substring(1);
        if (!s.matches("[0-9a-f]{6}")) return null;
        return 0xFF000000 | Integer.parseInt(s, 16);
    }

    /** Tolerance in SpriteLab's range (0 to 0.5) from a percent (0 to 50), or {@code current} when not given. */
    public static float tolerance(@Nullable Double percent, float current) {
        if (percent == null || percent.isNaN()) return current > 0f ? current : 0.12f;
        return (float) Math.max(0.0, Math.min(0.5, percent / 100.0));
    }
}
