package com.fadcam.joyfolder;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;
import java.util.regex.Pattern;

/**
 * Names a person can read. No codes, no UUIDs, nothing an SD card or a PC will refuse.
 *
 * <p>Dates are written year-month-day so that sorting by name is sorting by time, and with a dot
 * between hour and minute because a colon is illegal on SD cards and Windows.</p>
 */
public final class ReadableNames {

    private ReadableNames() {}

    /** Longest name we write, ending included. Leaves headroom under every file system's limit. */
    static final int MAX_LENGTH = 120;

    private static final String ILLEGAL = "\\/:*?\"<>|";

    /** A name safe on every file system Android can mount, or "Untitled" when nothing usable is left. */
    public static String sanitize(String name) {
        if (name == null) name = "";
        StringBuilder sb = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            sb.append(c < 0x20 || c == 0x7F || ILLEGAL.indexOf(c) >= 0 ? ' ' : c);
        }
        String s = sb.toString().replaceAll("\\s+", " ").trim();
        // Leading dots hide a file; trailing dots and spaces are stripped by Windows and SD cards.
        while (s.startsWith(".")) s = s.substring(1).trim();
        while (s.endsWith(".") || s.endsWith(" ")) s = s.substring(0, s.length() - 1);
        if (s.isEmpty()) s = "Untitled";
        if (s.length() > MAX_LENGTH) {
            String end = FileKind.endingOf(s);
            if (end.length() >= MAX_LENGTH / 2) end = "";
            s = s.substring(0, MAX_LENGTH - end.length()).trim() + end;
        }
        return s;
    }

    /** "2026-10-13 14.32" in the phone's time zone. */
    public static String stamp(long millis, TimeZone zone) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH.mm", Locale.US);
        f.setTimeZone(zone);
        return f.format(new Date(millis));
    }

    /** "Screen recording 2026-10-13 14.32.mp4". {@code ending} includes its dot. */
    public static String recording(String label, long millis, TimeZone zone, String ending) {
        return sanitize(label + " " + stamp(millis, zone) + ending);
    }

    private static final Pattern DIGITS = Pattern.compile("\\d{5,}");
    private static final Pattern UUID = Pattern.compile(
            "[0-9a-fA-F]{8}-?[0-9a-fA-F]{4}-?[0-9a-fA-F]{4}-?[0-9a-fA-F]{4}-?[0-9a-fA-F]{12}");
    private static final Pattern HEX = Pattern.compile("[0-9a-fA-F]{12,}");
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_-]{20,}");
    private static final Pattern GENERIC = Pattern.compile(
            "(?i)(image|img|photo|picture|video|file|download|untitled|unknown|share|shared|tmp|temp|output)[ _-]*\\d*");

    /**
     * True for names nobody chose: "1000004521", a UUID, a long hex or random token, or a generic
     * word like "image" or "download (3)". Camera names such as "IMG_20261013_143200" are left
     * alone: people recognise those.
     */
    public static boolean looksCodeLike(String base) {
        String b = base.trim().replaceAll("\\s*\\(\\d+\\)$", "");
        if (b.isEmpty()) return true;
        if (DIGITS.matcher(b).matches() || UUID.matcher(b).matches() || HEX.matcher(b).matches()) return true;
        if (GENERIC.matcher(b).matches()) return true;
        if (TOKEN.matcher(b).matches()) {
            int digits = 0;
            for (int i = 0; i < b.length(); i++) if (Character.isDigit(b.charAt(i))) digits++;
            return digits >= 4 && !b.contains("_20") && !b.contains("-20");
        }
        return false;
    }

    /**
     * The name a shared-in file is saved under. A human name is kept; a code-like one becomes
     * "From ChatGPT 2026-10-13 14.40.png" when Android tells us the app, else "Shared 2026-10-13 14.40.png".
     *
     * @param fallbackEnding used when the original name has no ending, e.g. ".png" from the MIME type
     */
    public static String forShareIn(String originalName, String sourceApp, long millis,
                                    TimeZone zone, String fallbackEnding) {
        String original = originalName == null ? "" : originalName.trim();
        String ending = FileKind.endingOf(original);
        if (ending.isEmpty() && fallbackEnding != null) ending = fallbackEnding;
        String base = original.isEmpty() ? "" : original.substring(0, original.length() - FileKind.endingOf(original).length());
        if (!looksCodeLike(base)) return sanitize(base + ending);
        String who = sourceApp == null || sourceApp.trim().isEmpty() ? "Shared" : "From " + sourceApp.trim();
        return sanitize(who + " " + stamp(millis, zone) + ending);
    }

    /**
     * {@code desired}, or "photo 2.jpg", "photo 3.jpg"… when the name is taken. Comparison ignores
     * case, as SD cards and most providers do.
     *
     * @param takenLowercase names already in the folder, lower-cased
     */
    public static String unique(Set<String> takenLowercase, String desired) {
        String name = sanitize(desired);
        if (!takenLowercase.contains(name.toLowerCase(Locale.ROOT))) return name;
        String end = FileKind.endingOf(name);
        String base = name.substring(0, name.length() - end.length());
        for (int n = 2; ; n++) {
            String candidate = base + " " + n + end;
            if (!takenLowercase.contains(candidate.toLowerCase(Locale.ROOT))) return candidate;
        }
    }
}
