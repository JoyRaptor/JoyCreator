package com.fadcam.ui.faditor.util;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * The ONE way a typed time or duration becomes milliseconds, anywhere in the app.
 *
 * <p>SPEC_IMAGE_SEQUENCE §3c: "Accept the ways people actually type time: {@code 10.5s},
 * {@code 10.5 sec}, {@code 10.5 seconds}, {@code 1/6 min}, {@code 00:00:10.5}, {@code 90f}
 * (frames), {@code 2m30s}."
 *
 * <p>It replaces {@code FaditorEditorActivity.parseTimeToMs}, which accepted only {@code ss},
 * {@code m:ss} and {@code h:mm:ss} — those remain valid here, so nothing a user could type
 * before stops working.
 *
 * <p><b>Why one class rather than a parser per dialog.</b> JoyRaptor's rule for the trim controls
 * applies to input too: the same thing must behave the same way everywhere, "so that people can
 * start seeing design language instead of something that's fractured". A second parser is how
 * two dialogs come to disagree about what {@code 2m30s} means.
 *
 * <p>Pure logic with no Android dependency, so the accepted grammar is pinned off-device by
 * {@code tools/jvm-harness/FlexibleTimeTest.java} using the spec's own examples as cases.
 */
public final class FlexibleTimeParser {

    private FlexibleTimeParser() { }

    /** Returned for anything not understood. Callers must treat it as "reject", never as 0. */
    public static final long INVALID = -1L;

    /** Frame rate assumed when a caller has no project fps to hand. */
    public static final float DEFAULT_FPS = 30f;

    /** @see #parseToMs(String, float) */
    public static long parseToMs(@Nullable String text) {
        return parseToMs(text, DEFAULT_FPS);
    }

    /**
     * Parse a typed time/duration to milliseconds.
     *
     * @param text what the user typed; null/blank is {@link #INVALID}
     * @param fps  frames per second, used only by the {@code f} unit
     * @return milliseconds (>= 0), or {@link #INVALID}
     */
    public static long parseToMs(@Nullable String text, float fps) {
        if (text == null) return INVALID;
        // Normalise the typography people actually produce: NBSP from copy/paste, a comma
        // decimal separator from a European keyboard, and any case.
        String t = text.trim().toLowerCase(java.util.Locale.US)
                .replace('\u00a0', ' ')
                .replace(',', '.');
        if (t.isEmpty()) return INVALID;
        if (t.indexOf(':') >= 0) return parseClock(t);
        return parseUnits(t, fps);
    }

    /**
     * {@code ss} / {@code m:ss} / {@code h:mm:ss}, decimals allowed on the last field — the
     * grammar the old parseTimeToMs accepted, preserved exactly.
     */
    private static long parseClock(@NonNull String t) {
        String[] parts = t.split(":", -1);
        if (parts.length > 3) return INVALID;
        double seconds = 0;
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i].trim();
            if (p.isEmpty()) return INVALID;
            double v = number(p);
            if (Double.isNaN(v) || v < 0) return INVALID;
            // Only the final field may carry a fraction: "1.5:30" is a typo, not a time.
            if (i < parts.length - 1 && v != Math.floor(v)) return INVALID;
            seconds = seconds * 60 + v;
        }
        return Math.round(seconds * 1000.0);
    }

    /**
     * Unit forms: one or more {@code <number><unit>} pairs ({@code 2m30s}, {@code 1h2m3s}),
     * a single value with a unit ({@code 10.5 seconds}, {@code 90f}, {@code 1/6 min}), or a
     * bare number, which means SECONDS — matching what a bare number always meant here.
     */
    private static long parseUnits(@NonNull String t, float fps) {
        double totalMs = 0;
        boolean sawAny = false;
        int i = 0;
        final int n = t.length();
        while (i < n) {
            while (i < n && t.charAt(i) == ' ') i++;
            if (i >= n) break;

            int numStart = i;
            while (i < n && (Character.isDigit(t.charAt(i)) || t.charAt(i) == '.'
                    || t.charAt(i) == '/')) {
                i++;
            }
            if (i == numStart) return INVALID;          // a unit with no number in front of it
            double value = number(t.substring(numStart, i));
            if (Double.isNaN(value) || value < 0) return INVALID;

            while (i < n && t.charAt(i) == ' ') i++;
            int unitStart = i;
            while (i < n && t.charAt(i) >= 'a' && t.charAt(i) <= 'z') i++;
            String unit = t.substring(unitStart, i);

            double msPerUnit = millisPerUnit(unit, fps);
            if (Double.isNaN(msPerUnit)) return INVALID;
            totalMs += value * msPerUnit;
            sawAny = true;
        }
        if (!sawAny) return INVALID;
        if (totalMs < 0 || Double.isInfinite(totalMs) || Double.isNaN(totalMs)) return INVALID;
        return Math.round(totalMs);
    }

    /** Milliseconds one unit is worth, or NaN if the suffix is not a unit we accept. */
    private static double millisPerUnit(@NonNull String unit, float fps) {
        switch (unit) {
            // Bare number = seconds. Preserves the old parser's behaviour for "10".
            case "":
            case "s": case "sec": case "secs": case "second": case "seconds":
                return 1000.0;
            case "ms": case "milli": case "millis": case "millisecond": case "milliseconds":
                return 1.0;
            case "m": case "min": case "mins": case "minute": case "minutes":
                return 60_000.0;
            case "h": case "hr": case "hrs": case "hour": case "hours":
                return 3_600_000.0;
            case "f": case "frame": case "frames":
                // A frame is only meaningful against a rate; a nonsensical fps would otherwise
                // yield Infinity and read as a colossal duration.
                if (fps <= 0f || Float.isNaN(fps)) return Double.NaN;
                return 1000.0 / fps;
            default:
                return Double.NaN;
        }
    }

    /**
     * A decimal ({@code 10.5}) or a vulgar fraction ({@code 1/6}) — the spec asks for
     * {@code 1/6 min} explicitly. NaN when it is neither.
     */
    private static double number(@NonNull String s) {
        int slash = s.indexOf('/');
        try {
            if (slash >= 0) {
                if (s.indexOf('/', slash + 1) >= 0) return Double.NaN;   // "1/2/3"
                String a = s.substring(0, slash).trim();
                String b = s.substring(slash + 1).trim();
                if (a.isEmpty() || b.isEmpty()) return Double.NaN;
                double den = Double.parseDouble(b);
                if (den == 0) return Double.NaN;
                return Double.parseDouble(a) / den;
            }
            if (s.isEmpty() || ".".equals(s)) return Double.NaN;
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }
}
