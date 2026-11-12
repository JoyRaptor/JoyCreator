package cc.joycreator.textlab;

import androidx.annotation.NonNull;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.List;

/**
 * Knob values into the numbers a uniform takes. The twin of {@code TL.colour} and {@code flat} in
 * {@code tools/textlab-web/tl-gl.js}: a colour is "#rrggbb" or "#rrggbb*2.5" (a gain, for light
 * brighter than white) and becomes linear rgb; a list is flattened in order.
 */
public final class TlValues {

    private TlValues() {}

    /** "#rrggbb" or "#rrggbb*gain" to linear rgb. */
    @NonNull
    public static float[] colour(@NonNull String s) {
        int star = s.indexOf('*');
        String hex = star < 0 ? s : s.substring(0, star);
        double gain = star < 0 ? 1.0 : Double.parseDouble(s.substring(star + 1));
        int n = (int) Long.parseLong(hex.substring(1), 16);
        return new float[]{
                (float) (lin((n >> 16) & 255) * gain),
                (float) (lin((n >> 8) & 255) * gain),
                (float) (lin(n & 255) * gain)};
    }

    private static double lin(int b) {
        double v = b / 255.0;
        return v <= 0.04045 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
    }

    /**
     * Any value (a number, a colour string, a float[], a List or JSONArray of those) as one flat run of
     * floats.
     */
    @NonNull
    public static float[] flat(@NonNull Object v) {
        if (v instanceof float[]) return (float[]) v;
        List<Float> out = new ArrayList<>();
        add(v, out);
        float[] f = new float[out.size()];
        for (int i = 0; i < f.length; i++) f[i] = out.get(i);
        return f;
    }

    private static void add(Object v, List<Float> out) {
        if (v instanceof String) {
            for (float x : colour((String) v)) out.add(x);
        } else if (v instanceof Number) {
            out.add(((Number) v).floatValue());
        } else if (v instanceof Boolean) {
            out.add((Boolean) v ? 1f : 0f);
        } else if (v instanceof float[]) {
            for (float x : (float[]) v) out.add(x);
        } else if (v instanceof double[]) {
            for (double x : (double[]) v) out.add((float) x);
        } else if (v instanceof List) {
            for (Object x : (List<?>) v) add(x, out);
        } else if (v instanceof JSONArray) {
            JSONArray a = (JSONArray) v;
            for (int i = 0; i < a.length(); i++) add(a.opt(i), out);
        } else if (v instanceof Object[]) {
            for (Object x : (Object[]) v) add(x, out);
        }
    }

    /** A JSON number, or {@code dflt} when it is absent. */
    public static double num(Object v, double dflt) {
        return v instanceof Number ? ((Number) v).doubleValue() : dflt;
    }
}
