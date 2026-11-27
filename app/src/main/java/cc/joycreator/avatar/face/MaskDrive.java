package cc.joycreator.avatar.face;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The face mask (FACE-2) driven by your face: the pilot's {@code al-mask.js} {@code watch} and {@code drive} and
 * {@code al-mask-poses.js} {@code weights}, line for line. From the face points' lanes ({@code pt.<i>.x/.y}, already eased) to the
 * mask's: {@code mp.<pose>} (how much of each pose your face holds, 0 to 1.5, by non-negative least squares) and
 * {@code mk.<n>.x/.y} (what the poses do not explain, mask units, times {@code face.direct}). With Mirror on the character mirrors
 * you: its right side follows your left. Mask units: the eyes are 0.9 apart. Pure: the room hands it the head set.
 */
public final class MaskDrive {
    private MaskDrive() {}

    /** Each face point and its partner on the other side of the face (the mirror's choice). */
    static final Map<Integer, Integer> PARTNER = new HashMap<>();

    static {
        int[][] pairs = {{107, 336}, {70, 300}, {33, 263}, {133, 362}, {205, 425}, {61, 291}, {172, 397}};
        for (int[] p : pairs) {
            PARTNER.put(p[0], p[1]);
            PARTNER.put(p[1], p[0]);
        }
    }

    /** The mask's points (its whole-drawing landmarks), or null when the head wears no mask. */
    static JSONArray points(JSONObject set) {
        JSONObject marks = set == null ? null : set.optJSONObject("marks"), mk = marks == null ? null : marks.optJSONObject("");
        return mk == null ? null : mk.optJSONArray("pts");
    }

    /** The face points the mask follows, both sides of each (so the camera's mirror can choose). */
    public static int[] watch(JSONObject set) {
        JSONArray pts = points(set);
        List<Integer> out = new ArrayList<>();
        for (int i = 0; pts != null && i < pts.length(); i++) {
            JSONArray f = pts.optJSONObject(i) == null ? null : pts.optJSONObject(i).optJSONArray("face");
            for (int k = 0; f != null && k < f.length(); k++) {
                int a = f.optInt(k);
                Integer b = PARTNER.get(a);
                if (!out.contains(a)) out.add(a);
                if (b != null && !out.contains(b)) out.add(b);
            }
        }
        int[] a = new int[out.size()];
        for (int i = 0; i < a.length; i++) a[i] = out.get(i);
        return a;
    }

    /** Mask units per unit of the face points: the eyes' middles are 0.9 apart on the mask ({@code rest}: the neutral's points). */
    static double unitOf(float[] rest) {
        if (rest == null || rest.length < 478 * 3) return 0.75;
        double lx = (rest[33 * 3] + rest[133 * 3]) / 2, ly = (rest[33 * 3 + 1] + rest[133 * 3 + 1]) / 2;
        double rx = (rest[263 * 3] + rest[362 * 3]) / 2, ry = (rest[263 * 3 + 1] + rest[362 * 3 + 1]) / 2;
        double d = Math.hypot(lx - rx, ly - ry);
        return d > 1e-6 ? 0.9 / d : 0.75;
    }

    /**
     * One frame: the mask's lanes into {@code out} (any old {@code mp.} lanes there are dropped first). {@code lanes}: the face
     * points' eased lanes; {@code mirrored}: Mirror on (the default); {@code rest}: the neutral face's points, or null.
     */
    public static void drive(JSONObject set, Map<String, Double> lanes, boolean mirrored, float[] rest, Map<String, Double> out) {
        JSONArray pts = points(set);
        if (pts == null) return;
        double k = unitOf(rest);
        Map<String, double[]> raw = new LinkedHashMap<>();
        for (int i = 0; i < pts.length(); i++) {
            JSONObject p = pts.optJSONObject(i);
            JSONArray f = p == null ? null : p.optJSONArray("face");
            if (f == null || f.length() == 0) continue;
            double sx = 0, sy = 0;
            int n = 0;
            for (int q = 0; q < f.length(); q++) {
                int a = f.optInt(q);
                Integer b = PARTNER.get(a);
                int j = mirrored && b != null ? b : a;
                Double x = lanes.get("pt." + j + ".x"), y = lanes.get("pt." + j + ".y");
                if (x != null) {
                    sx += x;
                    sy += y == null ? 0 : y;
                    n++;
                }
            }
            raw.put(String.valueOf(p.optInt("n")), new double[]{n > 0 ? sx / n * k : 0, n > 0 ? sy / n * k : 0});
        }
        out.keySet().removeIf(key -> key.startsWith("mp."));
        JSONObject f = set.optJSONObject("face");
        List<JSONObject> poses = new ArrayList<>();
        JSONArray pl = f == null ? null : f.optJSONArray("poses");
        for (int i = 0; pl != null && i < pl.length(); i++) {
            JSONObject q = pl.optJSONObject(i);
            if (q != null && q.optJSONObject("face") != null) poses.add(q);
        }
        Map<String, double[]> left = raw;
        if (!poses.isEmpty()) {
            Map<String, Double> w = new LinkedHashMap<>();
            left = weights(poses, raw, w);
            for (Map.Entry<String, Double> e : w.entrySet()) out.put("mp." + e.getKey(), e.getValue());
        }
        double dg = f == null || !f.has("direct") || f.isNull("direct") ? 1 : f.optDouble("direct", 1);
        double scale = poses.isEmpty() ? 1 : dg;
        for (String n : raw.keySet()) {
            double[] v = left.get(n);
            out.put("mk." + n + ".x", v == null ? 0 : v[0] * scale);
            out.put("mk." + n + ".y", v == null ? 0 : v[1] * scale);
        }
    }

    /**
     * How much of each pose your face holds (non-negative, at most 1.5: 30 passes of coordinate descent, as the pilot's) into
     * {@code w}; returns what the poses do not explain, per point.
     */
    static Map<String, double[]> weights(List<JSONObject> poses, Map<String, double[]> m, Map<String, Double> w) {
        Map<String, Integer> keys = new LinkedHashMap<>();
        for (JSONObject p : poses) for (Iterator<String> it = p.optJSONObject("face").keys(); it.hasNext(); ) keys.putIfAbsent(it.next(), 0);
        for (String n : m.keySet()) keys.putIfAbsent(n, 0);
        List<String> ns = new ArrayList<>(keys.keySet());
        double[][] C = new double[poses.size()][ns.size() * 2];
        for (int i = 0; i < poses.size(); i++) {
            JSONObject pf = poses.get(i).optJSONObject("face");
            for (int j = 0; j < ns.size(); j++) {
                JSONArray q = pf.optJSONArray(ns.get(j));
                C[i][j * 2] = q == null ? 0 : q.optDouble(0);
                C[i][j * 2 + 1] = q == null ? 0 : q.optDouble(1);
            }
        }
        double[] r = new double[ns.size() * 2], wt = new double[poses.size()], cc = new double[poses.size()];
        for (int j = 0; j < ns.size(); j++) {
            double[] q = m.get(ns.get(j));
            r[j * 2] = q == null ? 0 : q[0];
            r[j * 2 + 1] = q == null ? 0 : q[1];
        }
        for (int i = 0; i < poses.size(); i++) for (double v : C[i]) cc[i] += v * v;
        for (int it = 0; it < 30; it++) for (int i = 0; i < poses.size(); i++) {
            if (cc[i] < 1e-9) continue;
            double dot = 0;
            for (int j = 0; j < r.length; j++) dot += C[i][j] * r[j];
            double nw = Math.max(0, Math.min(1.5, wt[i] + dot / cc[i])), dw = nw - wt[i];
            if (dw == 0) continue;
            wt[i] = nw;
            for (int j = 0; j < r.length; j++) r[j] -= dw * C[i][j];
        }
        for (int i = 0; i < poses.size(); i++) w.put(poses.get(i).optString("id"), wt[i]);
        Map<String, double[]> rest = new LinkedHashMap<>();
        for (int j = 0; j < ns.size(); j++) rest.put(ns.get(j), new double[]{r[j * 2], r[j * 2 + 1]});
        return rest;
    }
}
