package com.fadcam;

/**
 * SPEC-ZB-scoped shadow of the shared harness {@code FLog} stub (run-clipwarp.sh ONLY).
 * Identical plus {@code v()}, which the {@code ProjectStorage} compile closure needs
 * ({@code FragmentedMp4Remuxer}). Still a no-op logger.
 */
public final class FLog {
    public static void v(String t, String m) {}
    public static void d(String t, String m) {}
    public static void i(String t, String m) {}
    public static void w(String t, String m) {}
    public static void w(String t, String m, Throwable x) {}
    public static void e(String t, String m) {}
    public static void e(String t, String m, Throwable x) {}
    private FLog() {}
}
