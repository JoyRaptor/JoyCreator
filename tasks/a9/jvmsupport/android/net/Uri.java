package android.net;

/**
 * JVM-harness fake: android.jar's real Uri throws "Stub!" from every method and has a
 * package-private constructor, so model classes that merely STORE a Uri cannot be driven
 * off-device. This concrete no-op stands in when tests/a9/run-lane-parity.sh puts this
 * directory FIRST on -sourcepath. Never ship code that depends on it.
 */
public abstract class Uri implements Comparable<Uri> {

    public static Uri parse(String s) { return new StringUri(s); }

    public int compareTo(Uri o) { return 0; }
    public boolean isHierarchical() { return false; }
    public boolean isRelative() { return false; }
    public boolean isAbsolute() { return true; }
    public String getScheme() { return "file"; }
    public String getSchemeSpecificPart() { return null; }
    public String getAuthority() { return null; }
    public String getUserInfo() { return null; }
    public String getHost() { return null; }
    public int getPort() { return -1; }
    public String getPath() { return "/x.mp3"; }
    public String getQuery() { return null; }
    public String getFragment() { return null; }
    public String getEncodedPath() { return "/x.mp3"; }
    public String getEncodedQuery() { return null; }
    public String getEncodedFragment() { return null; }
    public String getEncodedUserInfo() { return null; }
    public String getLastPathSegment() { return "x.mp3"; }
    public String getQueryParameter(String key) { return null; }
    public String toString() { return "file:///x.mp3"; }

    private static final class StringUri extends Uri {
        private final String s;
        StringUri(String s) { this.s = s; }
        public String toString() { return s; }
    }
}
