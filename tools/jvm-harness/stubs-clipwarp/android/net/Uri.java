package android.net;

/**
 * Test-only {@code Uri} for the off-device harness (SPEC ZB's run-clipwarp.sh ONLY).
 *
 * <p>NOT app code and NOT on the shared stubs path: it lives in
 * {@code tools/jvm-harness/stubs-clipwarp}, which only that one runner puts on its
 * sourcepath. Real {@code Clip} stores its source {@code Uri} without dereferencing it,
 * but {@code ProjectStorage} calls {@code toString()}, {@code parse()} and
 * {@code getPath()} on the serialise path, and the SDK android.jar throws
 * {@code Stub!} for all three — so a round-trip test needs a working stand-in.
 *
 * <p>Deliberately minimal: verbatim strings round-trip, {@code file://} paths expose a
 * path. Anything fancier is out of scope — this proves persistence, not URIs.
 */
public class Uri {
    private final String value;

    private Uri(String v) { this.value = v; }

    public static Uri parse(String s) { return new Uri(s); }

    public static Uri fromFile(java.io.File f) {
        return new Uri("file://" + f.getAbsolutePath());
    }

    public String getPath() {
        if (value == null) return null;
        String s = value;
        int q = s.indexOf('?');
        if (q >= 0) s = s.substring(0, q);
        if (s.startsWith("file://")) s = s.substring("file://".length());
        return s.startsWith("/") ? s : "/" + s;
    }

    /** Scheme of a {@code scheme://...} string, or null. Needed by AssetResolver's signature. */
    public String getScheme() {
        if (value == null) return null;
        int c = value.indexOf(':');
        return c > 0 ? value.substring(0, c) : null;
    }

    /** Text after the last '/'. Needed by AssetResolver's signature; never called by the test. */
    public String getLastPathSegment() {
        String p = getPath();
        if (p == null) return null;
        int s = p.lastIndexOf('/');
        return s >= 0 ? p.substring(s + 1) : p;
    }

    @Override
    public String toString() { return value; }
}
