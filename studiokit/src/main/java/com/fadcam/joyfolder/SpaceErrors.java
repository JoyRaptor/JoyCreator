package com.fadcam.joyfolder;

import java.util.Locale;

/**
 * FILE-1, large share-ins: a copy that runs out of room must say so in plain words, not in an operating-system line
 * ("write failed: ENOSPC (No space left on device)"). Pure Java so it is tested off the device.
 */
public final class SpaceErrors {
    private SpaceErrors() {}

    /** True when this failure, or anything it was caused by, is the phone running out of storage. */
    public static boolean isOutOfSpace(Throwable t) {
        for (int depth = 0; t != null && depth < 8; depth++, t = t.getCause()) {
            String m = t.getMessage();
            if (m == null) continue;
            String s = m.toLowerCase(Locale.US);
            if (s.contains("enospc") || s.contains("no space left") || s.contains("not enough space")
                    || s.contains("not enough storage") || s.contains("insufficient storage")
                    || s.contains("disk full") || s.contains("quota exceeded") || s.contains("edquot")) {
                return true;
            }
        }
        return false;
    }

    /**
     * A sentence for the person. Out of room says so and that nothing was half-saved; anything else keeps the system's own
     * words (never null).
     */
    public static String plain(Throwable t) {
        if (isOutOfSpace(t)) {
            return "the phone ran out of room, so nothing half-finished was kept. Free some space and share again";
        }
        String m = t == null ? null : t.getMessage();
        return m == null || m.trim().isEmpty() ? "an unknown problem" : m;
    }
}
