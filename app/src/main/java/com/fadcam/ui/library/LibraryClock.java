package com.fadcam.ui.library;

import java.util.Locale;

/**
 * A running time as the lobby writes it under the last project: "0:47", "12:03", "1:02:03". Pure,
 * so it is tested on a plain JVM ({@code tools/library-tests}).
 */
public final class LibraryClock {

    private LibraryClock() {}

    /** m:ss, or h:mm:ss from the hour; seconds are cut, not rounded, as in the lobby. */
    public static String of(long ms) {
        long total = Math.max(0L, ms) / 1000L;
        long h = total / 3600L, m = (total / 60L) % 60L, s = total % 60L;
        return h > 0 ? String.format(Locale.US, "%d:%02d:%02d", h, m, s)
                     : String.format(Locale.US, "%d:%02d", m, s);
    }
}
