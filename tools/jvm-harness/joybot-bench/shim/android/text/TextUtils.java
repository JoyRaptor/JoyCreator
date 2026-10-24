package android.text;

/** Runtime shim of the TextUtils helpers the tools use. */
public class TextUtils {
    public static boolean isEmpty(CharSequence s) { return s == null || s.length() == 0; }

    public static boolean equals(CharSequence a, CharSequence b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        return a.toString().equals(b.toString());
    }

    public static String join(CharSequence delimiter, Iterable<?> tokens) {
        StringBuilder sb = new StringBuilder();
        for (Object t : tokens) {
            if (sb.length() > 0) sb.append(delimiter);
            sb.append(t);
        }
        return sb.toString();
    }

    public static String join(CharSequence delimiter, Object[] tokens) { return join(delimiter, java.util.Arrays.asList(tokens)); }

    public static String htmlEncode(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }
}
