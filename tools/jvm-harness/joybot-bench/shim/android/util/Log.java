package android.util;

/** Runtime shim: printed to stderr when JOYBOT_BENCH_LOG is set, otherwise dropped. */
public final class Log {
    private static final boolean ON = System.getenv("JOYBOT_BENCH_LOG") != null;

    private Log() {}

    private static int out(String level, String tag, String msg, Throwable t) {
        if (!ON) return 0;
        System.err.println(level + "/" + tag + ": " + msg + (t != null ? " — " + t : ""));
        return 0;
    }

    public static int v(String tag, String msg) { return out("V", tag, msg, null); }
    public static int v(String tag, String msg, Throwable t) { return out("V", tag, msg, t); }
    public static int d(String tag, String msg) { return out("D", tag, msg, null); }
    public static int d(String tag, String msg, Throwable t) { return out("D", tag, msg, t); }
    public static int i(String tag, String msg) { return out("I", tag, msg, null); }
    public static int i(String tag, String msg, Throwable t) { return out("I", tag, msg, t); }
    public static int w(String tag, String msg) { return out("W", tag, msg, null); }
    public static int w(String tag, String msg, Throwable t) { return out("W", tag, msg, t); }
    public static int w(String tag, Throwable t) { return out("W", tag, "", t); }
    public static int e(String tag, String msg) { return out("E", tag, msg, null); }
    public static int e(String tag, String msg, Throwable t) { return out("E", tag, msg, t); }
    public static int wtf(String tag, String msg) { return out("WTF", tag, msg, null); }
    public static int wtf(String tag, String msg, Throwable t) { return out("WTF", tag, msg, t); }
    public static boolean isLoggable(String tag, int level) { return ON; }

    public static String getStackTraceString(Throwable t) {
        java.io.StringWriter w = new java.io.StringWriter();
        if (t != null) t.printStackTrace(new java.io.PrintWriter(w));
        return w.toString();
    }
}
