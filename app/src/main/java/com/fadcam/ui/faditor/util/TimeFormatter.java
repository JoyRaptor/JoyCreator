package com.fadcam.ui.faditor.util;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Locale;

/**
 * Utility for formatting time values in the editor UI.
 */
public final class TimeFormatter {

    private TimeFormatter() {
        // No instances
    }

    /**
     * Format milliseconds as MM:SS.
     *
     * @param ms milliseconds
     * @return formatted string like "01:23"
     */
    @NonNull
    public static String formatMmSs(long ms) {
        if (ms < 0) ms = 0;
        long totalSeconds = ms / 1000;
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        return String.format(Locale.US, "%02d:%02d", minutes, seconds);
    }

    /**
     * Format milliseconds as MM:SS.ms (with tenths).
     *
     * @param ms milliseconds
     * @return formatted string like "01:23.4"
     */
    @NonNull
    public static String formatMmSsTenths(long ms) {
        if (ms < 0) ms = 0;
        long totalSeconds = ms / 1000;
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        long tenths = (ms % 1000) / 100;
        return String.format(Locale.US, "%02d:%02d.%d", minutes, seconds, tenths);
    }

    /**
     * Format milliseconds as HH:MM:SS for long videos.
     *
     * @param ms milliseconds
     * @return formatted string like "01:23:45"
     */
    @NonNull
    public static String formatHhMmSs(long ms) {
        if (ms < 0) ms = 0;
        long totalSeconds = ms / 1000;
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds);
    }

    /**
     * Auto-format: uses HH:MM:SS if >= 1 hour, else MM:SS.
     */
    @NonNull
    public static String formatAuto(long ms) {
        if (ms >= 3600_000) {
            return formatHhMmSs(ms);
        }
        return formatMmSs(ms);
    }

    /**
     * SPEC_C: parse a typed frame timecode into milliseconds — {@code [h:]mm:ss[.fff]} or
     * plain seconds ({@code 4.5}, {@code 90}, optionally suffixed {@code s}).
     *
     * <p>Pure Java (androidx annotations only) so the off-device harness can pin it:
     * {@code bash tools/jvm-harness/run-frame-time.sh}.</p>
     *
     * @return milliseconds, or -1 for anything unparsable or negative — callers reject
     *         with an inline error rather than clamping a mistyped time silently.
     */
    public static long parseTimecodeMs(@Nullable String raw) {
        if (raw == null) return -1L;
        String s = raw.trim().toLowerCase(Locale.US);
        if (s.isEmpty()) return -1L;
        if (s.endsWith("s")) s = s.substring(0, s.length() - 1).trim();
        if (s.isEmpty() || s.contains("-")) return -1L;
        try {
            String[] parts = s.split(":");
            if (parts.length > 3) return -1L;
            String secPart = parts[parts.length - 1].trim();
            String secDigits = secPart;
            String fracDigits = null;
            int dot = secPart.indexOf('.');
            if (dot >= 0) {
                secDigits = secPart.substring(0, dot);
                fracDigits = secPart.substring(dot + 1);
            }
            if (secDigits.isEmpty()) return -1L;
            long seconds = Long.parseLong(secDigits);
            if (seconds < 0) return -1L;
            int fracMs = 0;
            if (fracDigits != null) {
                if (fracDigits.isEmpty()) return -1L;
                if (fracDigits.length() > 3) fracDigits = fracDigits.substring(0, 3);
                while (fracDigits.length() < 3) fracDigits = fracDigits + "0";
                fracMs = Integer.parseInt(fracDigits);
            }
            long minuteTotal = 0;
            for (int i = 0; i < parts.length - 1; i++) {
                long v = Long.parseLong(parts[i].trim());
                if (v < 0) return -1L;
                minuteTotal = minuteTotal * 60 + v;
            }
            return (minuteTotal * 60 + seconds) * 1000 + fracMs;
        } catch (NumberFormatException e) {
            return -1L;
        }
    }
}
