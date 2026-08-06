package com.fadcam.ui.faditor.sprite;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Locale;

/**
 * Duration parsing for the sequence import dialog (SPEC_IMAGE_SEQUENCE §3c) — <i>"accept the ways
 * people actually type time"</i>.
 *
 * <p>Handles {@code 10.5s}, {@code 10.5 sec}, {@code 10.5 seconds}, {@code 1/6 min},
 * {@code 00:00:10.5}, {@code 90f} (frames), {@code 2m30s}, plain numbers, and the obvious
 * neighbours of each. The spec's own assessment is the design brief: cheap to build,
 * disproportionately delightful.</p>
 *
 * <p>Pure and Android-free, so every accepted spelling is pinned in the JVM harness rather than
 * discovered by a user typing one of them.</p>
 */
public final class DurationParser {

    private DurationParser() {}

    /** Returned when the text cannot be read as a duration at all. */
    public static final long INVALID = -1L;

    /**
     * Parse {@code text} to milliseconds.
     *
     * @param fps frame rate used only by the {@code 90f} form. Pass the sequence's current rate;
     *            a non-positive value makes frame input unparseable rather than silently wrong.
     * @return milliseconds, or {@link #INVALID}
     */
    public static long parseMs(@Nullable String text, float fps) {
        if (text == null) return INVALID;
        String s = text.trim().toLowerCase(Locale.US);
        if (s.isEmpty()) return INVALID;
        // Tolerate the spacing and separators people actually produce, including a comma decimal
        // mark from a non-US keyboard.
        s = s.replace(",", ".").replace("_", "");

        try {
            if (s.contains(":")) return parseClock(s);

            // FRAMES: "90f", "90 frames". Checked before the unit walk because "f" would
            // otherwise be an unknown suffix, and before "s" so "90 frames" is not read as
            // seconds on its trailing 's'.
            String frameNum = stripSuffix(s, "frames", "frame", "f");
            if (frameNum != null) {
                if (fps <= 0f) return INVALID;
                double frames = parseNumber(frameNum);
                if (Double.isNaN(frames)) return INVALID;
                return Math.max(0, Math.round(frames * 1000.0 / fps));
            }

            // Compound "2m30s" / "1h2m3s" — scanned as value+unit pairs.
            Long compound = parseCompound(s);
            if (compound != null) return compound;

            // Single unit, longest suffix first so "ms" is not eaten by "s".
            Long single = parseSingleUnit(s);
            if (single != null) return single;

            // A bare number means seconds — the unit someone typing "10" into a duration field
            // means, and the one every preset in the dialog is expressed in.
            double n = parseNumber(s);
            if (Double.isNaN(n)) return INVALID;
            return Math.max(0, Math.round(n * 1000.0));
        } catch (Exception e) {
            return INVALID;
        }
    }

    /** {@code 00:00:10.5}, {@code 1:30}, {@code 1:02:03.25}. */
    private static long parseClock(@NonNull String s) {
        String[] parts = s.split(":");
        if (parts.length < 2 || parts.length > 3) return INVALID;
        double total = 0;
        for (String p : parts) {
            double v = parseNumber(p.trim());
            if (Double.isNaN(v) || v < 0) return INVALID;
            total = total * 60 + v;
        }
        return Math.max(0, Math.round(total * 1000.0));
    }

    /** Units, longest spelling first so a prefix never shadows a longer match. */
    private static final String[][] UNITS = {
            {"milliseconds", "1"}, {"millisecond", "1"}, {"millis", "1"}, {"msec", "1"}, {"ms", "1"},
            {"seconds", "1000"}, {"second", "1000"}, {"secs", "1000"}, {"sec", "1000"}, {"s", "1000"},
            {"minutes", "60000"}, {"minute", "60000"}, {"mins", "60000"}, {"min", "60000"}, {"m", "60000"},
            {"hours", "3600000"}, {"hour", "3600000"}, {"hrs", "3600000"}, {"hr", "3600000"}, {"h", "3600000"},
    };

    @Nullable
    private static Long parseSingleUnit(@NonNull String s) {
        for (String[] u : UNITS) {
            String num = stripSuffix(s, u[0]);
            if (num == null) continue;
            double n = parseNumber(num);
            if (Double.isNaN(n)) return INVALID;
            return Math.max(0, Math.round(n * Long.parseLong(u[1])));
        }
        return null;
    }

    /**
     * {@code 2m30s}, {@code 1h2m3s}. Returns null when the text is not a value+unit chain, so the
     * caller can fall through to the simpler forms.
     */
    @Nullable
    private static Long parseCompound(@NonNull String s) {
        double totalMs = 0;
        int i = 0, matched = 0;
        while (i < s.length()) {
            while (i < s.length() && s.charAt(i) == ' ') i++;
            int start = i;
            while (i < s.length() && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '.'
                    || s.charAt(i) == '/')) i++;
            if (i == start) return null;                   // no number where one must be
            String num = s.substring(start, i);
            while (i < s.length() && s.charAt(i) == ' ') i++;
            int uStart = i;
            while (i < s.length() && Character.isLetter(s.charAt(i))) i++;
            String unit = s.substring(uStart, i);
            if (unit.isEmpty()) return null;               // trailing bare number: not compound
            Long mult = multiplierFor(unit);
            if (mult == null) return null;
            double n = parseNumber(num);
            if (Double.isNaN(n)) return null;
            totalMs += n * mult;
            matched++;
        }
        // One pair is a single-unit value; let the simpler path own it so its rounding and its
        // error reporting are the only ones in play.
        return matched >= 2 ? Math.max(0, Math.round(totalMs)) : null;
    }

    @Nullable
    private static Long multiplierFor(@NonNull String unit) {
        for (String[] u : UNITS) {
            if (u[0].equals(unit)) return Long.parseLong(u[1]);
        }
        return null;
    }

    /**
     * The numeric part, accepting a FRACTION — {@code 1/6 min} is one of the spec's own examples,
     * and is how someone expresses "ten seconds" while thinking in minutes.
     */
    private static double parseNumber(@NonNull String s) {
        String t = s.trim();
        if (t.isEmpty()) return Double.NaN;
        int slash = t.indexOf('/');
        if (slash > 0) {
            try {
                double num = Double.parseDouble(t.substring(0, slash).trim());
                double den = Double.parseDouble(t.substring(slash + 1).trim());
                if (den == 0) return Double.NaN;
                return num / den;
            } catch (NumberFormatException e) {
                return Double.NaN;
            }
        }
        try {
            return Double.parseDouble(t);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    /** {@code text} minus a trailing {@code suffix}, or null when it does not end with one. */
    @Nullable
    private static String stripSuffix(@NonNull String text, @NonNull String... suffixes) {
        for (String suffix : suffixes) {
            if (text.length() > suffix.length() && text.endsWith(suffix)) {
                return text.substring(0, text.length() - suffix.length()).trim();
            }
        }
        return null;
    }

    /** Render {@code ms} the way the dialog's total-duration field should show it back. */
    @NonNull
    public static String formatMs(long ms) {
        if (ms < 0) return "";
        if (ms < 60_000) return String.format(Locale.US, "%.2fs", ms / 1000f);
        long totalSec = ms / 1000;
        long h = totalSec / 3600, m = (totalSec % 3600) / 60;
        float s = (ms % 60_000) / 1000f;
        if (h > 0) return String.format(Locale.US, "%d:%02d:%05.2f", h, m, s);
        return String.format(Locale.US, "%d:%05.2f", m, s);
    }
}
