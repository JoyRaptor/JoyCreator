package cc.joycreator.avatar.solve;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Reading the character's JSON the way the pilot's JavaScript reads it: a missing value is "absent", a JSON null is absent
 * too ({@code x == null} in JS is true for both), and "truthy" means what it means there (0, "", false and absent are false).
 */
final class J {
    private J() {}

    /** A number, or null when absent, null or not a number. */
    static Double num(JSONObject o, String k) {
        if (o == null) return null;
        Object v = o.opt(k);
        return v instanceof Number ? ((Number) v).doubleValue() : null;
    }

    /** A number, or the fallback ({@code o.k == null ? def : o.k}). */
    static double d(JSONObject o, String k, double def) {
        Double v = num(o, k);
        return v == null ? def : v;
    }

    /** {@code o.k || 0}. */
    static double z(JSONObject o, String k) { return d(o, k, 0); }

    static String s(JSONObject o, String k) {
        if (o == null) return null;
        Object v = o.opt(k);
        return v instanceof String ? (String) v : null;
    }

    static JSONObject o(JSONObject o, String k) { return o == null ? null : o.optJSONObject(k); }

    static JSONArray a(JSONObject o, String k) { return o == null ? null : o.optJSONArray(k); }

    static boolean has(JSONObject o, String k) { return o != null && o.has(k) && !o.isNull(k); }

    /** JavaScript truthiness of {@code o.k}. */
    static boolean t(JSONObject o, String k) {
        if (o == null) return false;
        return truthy(o.opt(k));
    }

    static boolean truthy(Object v) {
        if (v == null || v == JSONObject.NULL) return false;
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof Number) { double d = ((Number) v).doubleValue(); return d != 0 && !Double.isNaN(d); }
        if (v instanceof String) return !((String) v).isEmpty();
        return true;
    }

    /** {@code o.k === false}. */
    static boolean isFalse(JSONObject o, String k) { return o != null && Boolean.FALSE.equals(o.opt(k)); }

    /** A point record { x, y }. */
    static double[] pt(JSONObject o, String k) {
        JSONObject p = o(o, k);
        return p == null ? null : new double[] {p.optDouble("x", 0), p.optDouble("y", 0)};
    }

    /** A two-number array [x, y]. */
    static double[] xy(JSONObject o, String k) {
        JSONArray a = a(o, k);
        return a == null || a.length() < 2 ? null : new double[] {a.optDouble(0, 0), a.optDouble(1, 0)};
    }

    static double x(JSONObject p) { return p.optDouble("x", 0); }

    static double y(JSONObject p) { return p.optDouble("y", 0); }
}
