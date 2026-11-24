package cc.joycreator.avatar.file;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/** Small helpers so the format code reads like the pilot's `al-format.js` (loose JSON in, plain answers out). */
final class Js {
    private Js() {}

    /** The value, or null when the key is missing or JSON null. */
    static Object get(JSONObject o, String k) {
        if (o == null) return null;
        Object v = o.opt(k);
        return v == JSONObject.NULL ? null : v;
    }

    static Object at(JSONArray a, int i) {
        if (a == null) return null;
        Object v = a.opt(i);
        return v == JSONObject.NULL ? null : v;
    }

    static boolean isNum(Object v) {
        if (!(v instanceof Number)) return false;
        double d = ((Number) v).doubleValue();
        return !Double.isNaN(d) && !Double.isInfinite(d);
    }

    static double dbl(Object v) {
        return ((Number) v).doubleValue();
    }

    static String str(Object v) {
        return v instanceof String ? (String) v : null;
    }

    static JSONObject obj(Object v) {
        return v instanceof JSONObject ? (JSONObject) v : null;
    }

    static JSONArray arr(Object v) {
        return v instanceof JSONArray ? (JSONArray) v : null;
    }

    /** `Array.isArray(v) && v.length >= 2 && num(v[0]) && num(v[1])` */
    static boolean isXy(Object v) {
        JSONArray a = arr(v);
        return a != null && a.length() >= 2 && isNum(at(a, 0)) && isNum(at(a, 1));
    }

    /** JavaScript truthiness for the few places the pilot's code relies on it (a missing, empty or zero value is false). */
    static boolean truthy(Object v) {
        if (v == null) return false;
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof Number) return dbl(v) != 0 && !Double.isNaN(dbl(v));
        if (v instanceof String) return !((String) v).isEmpty();
        return true;
    }

    static JSONObject copy(JSONObject o) {
        try {
            return new JSONObject(o.toString());
        } catch (org.json.JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    static String round2(double v) {
        return Double.toString(Math.round(v * 100) / 100.0);
    }

    static double round(double v) {
        return Math.round(v * 100) / 100.0;
    }

    static double dist(double[] a, double[] b) {
        return Math.hypot(a[0] - b[0], a[1] - b[1]);
    }

    /** `Object.keys(o)` in insertion order. */
    static java.util.List<String> keys(JSONObject o) {
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        if (o == null) return out;
        for (Iterator<String> it = o.keys(); it.hasNext(); ) out.add(it.next());
        return out;
    }

    static Map<String, JSONObject> newIndex() {
        return new LinkedHashMap<>();
    }

    /** The document as text with its keys in order, so two files that mean the same give the same text. */
    static String canonical(Object v) {
        StringBuilder sb = new StringBuilder();
        canon(v, sb);
        return sb.toString();
    }

    private static void canon(Object v, StringBuilder sb) {
        if (v instanceof JSONObject) {
            JSONObject o = (JSONObject) v;
            java.util.List<String> ks = keys(o);
            java.util.Collections.sort(ks);
            sb.append('{');
            boolean first = true;
            for (String k : ks) {
                if (!first) sb.append(',');
                first = false;
                sb.append(JSONObject.quote(k)).append(':');
                canon(o.opt(k), sb);
            }
            sb.append('}');
        } else if (v instanceof JSONArray) {
            JSONArray a = (JSONArray) v;
            sb.append('[');
            for (int i = 0; i < a.length(); i++) {
                if (i > 0) sb.append(',');
                canon(a.opt(i), sb);
            }
            sb.append(']');
        } else if (v instanceof Number) {
            double d = ((Number) v).doubleValue();
            if (d == Math.rint(d) && Math.abs(d) < 1e15) sb.append((long) d);
            else sb.append(d);
        } else if (v instanceof String) {
            sb.append(JSONObject.quote((String) v));
        } else {
            sb.append(v == null || v == JSONObject.NULL ? "null" : String.valueOf(v));
        }
    }
}
