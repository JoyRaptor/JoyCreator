package android.util;

/** Stand-in for the JVM harness: the room's code logs; here the lines are dropped. */
public final class Log {
    private Log() {}
    public static int w(String tag, String msg) { return 0; }
    public static int w(String tag, String msg, Throwable t) { return 0; }
    public static int d(String tag, String msg) { return 0; }
    public static int i(String tag, String msg) { return 0; }
    public static int e(String tag, String msg) { return 0; }
    public static int e(String tag, String msg, Throwable t) { return 0; }
}
