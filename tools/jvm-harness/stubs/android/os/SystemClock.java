package android.os;

/** Harness stub: a settable monotonic clock so the baseline throttle is deterministic. */
public final class SystemClock {
    public static long NOW = 0L;
    public static long elapsedRealtime() { return NOW; }
    private SystemClock() {}
}
