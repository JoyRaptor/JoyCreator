package android.net;

/** Runtime shim: an address string (file, content, http, or a bare path), parsed on demand. */
public class Uri implements Comparable<Uri> {

    public static final Uri EMPTY = new Uri("");
    private final String s;

    private Uri(String s) { this.s = s; }

    public static Uri parse(String s) { return new Uri(s); }
    public static Uri fromFile(java.io.File f) { return new Uri("file://" + f.getAbsolutePath().replace('\\', '/')); }

    public static Uri fromParts(String scheme, String ssp, String fragment) {
        return new Uri(scheme + ":" + ssp + (fragment != null ? "#" + fragment : ""));
    }

    public static String encode(String s) {
        try { return java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20"); } catch (Exception e) { return s; }
    }

    public static String decode(String s) {
        try { return java.net.URLDecoder.decode(s.replace("+", "%2B"), "UTF-8"); } catch (Exception e) { return s; }
    }

    public String getScheme() {
        int i = s.indexOf(':');
        int slash = s.indexOf('/');
        return i > 0 && (slash < 0 || i < slash) ? s.substring(0, i) : null;
    }

    private String rest() {
        String sc = getScheme();
        return sc == null ? s : s.substring(sc.length() + 1);
    }

    public String getSchemeSpecificPart() { return rest(); }

    public String getAuthority() {
        String r = rest();
        if (!r.startsWith("//")) return null;
        int e = r.indexOf('/', 2);
        return e < 0 ? r.substring(2) : r.substring(2, e);
    }

    public String getHost() { return getAuthority(); }

    public String getPath() {
        String r = rest();
        if (r.startsWith("//")) {
            int e = r.indexOf('/', 2);
            r = e < 0 ? "" : r.substring(e);
        }
        int q = r.indexOf('?');
        if (q >= 0) r = r.substring(0, q);
        int h = r.indexOf('#');
        if (h >= 0) r = r.substring(0, h);
        return decode(r);
    }

    public String getEncodedPath() { return getPath(); }

    public String getLastPathSegment() {
        String p = getPath();
        if (p == null || p.isEmpty()) return null;
        while (p.endsWith("/") && p.length() > 1) p = p.substring(0, p.length() - 1);
        return p.substring(p.lastIndexOf('/') + 1);
    }

    public java.util.List<String> getPathSegments() {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String part : getPath().split("/")) if (!part.isEmpty()) out.add(part);
        return out;
    }

    public String getQuery() {
        int q = s.indexOf('?');
        return q < 0 ? null : s.substring(q + 1);
    }

    public String getQueryParameter(String key) {
        String q = getQuery();
        if (q == null) return null;
        for (String kv : q.split("&")) {
            int e = kv.indexOf('=');
            if (e > 0 && kv.substring(0, e).equals(key)) return decode(kv.substring(e + 1));
        }
        return null;
    }

    public String getFragment() {
        int h = s.indexOf('#');
        return h < 0 ? null : s.substring(h + 1);
    }

    public boolean isAbsolute() { return getScheme() != null; }
    public boolean isRelative() { return !isAbsolute(); }
    public boolean isHierarchical() { return true; }
    public Uri normalizeScheme() { return this; }
    public Builder buildUpon() { return new Builder(s); }

    @Override public String toString() { return s; }
    @Override public boolean equals(Object o) { return o instanceof Uri && ((Uri) o).s.equals(s); }
    @Override public int hashCode() { return s.hashCode(); }
    @Override public int compareTo(Uri o) { return s.compareTo(o.s); }

    public static Uri withAppendedPath(Uri base, String pathSegment) {
        return new Uri(base.s.endsWith("/") ? base.s + pathSegment : base.s + "/" + pathSegment);
    }

    public static final class Builder {
        private String s;

        public Builder() { s = ""; }
        Builder(String s) { this.s = s; }

        public Builder scheme(String scheme) { s = scheme + ":" + s; return this; }
        public Builder path(String p) { s = s + p; return this; }
        public Builder appendPath(String p) { s = s.endsWith("/") ? s + p : s + "/" + p; return this; }

        public Builder appendQueryParameter(String k, String v) {
            s = s + (s.contains("?") ? "&" : "?") + encode(k) + "=" + encode(v);
            return this;
        }

        public Uri build() { return new Uri(s); }
    }
}
