package cc.joycreator.avatar.solve;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * The face mask (FACE-2), as the solver reads it: the pilot's {@code al-mask.js} {@code bendFor} and {@code headSet}, and
 * {@code al-mask-poses.js} {@code swapsNow}, line for line. The mask is the head set's whole-drawing landmarks ({@code marks['']})
 * with each drawing's fitted turn and scale in {@code face.fit}, written when the mask is edited; here nothing is worked out but
 * the move: your face's movement ({@code mk.<n>.x/.y}, mask units) turned to the drawing's real turn and scaled, times
 * {@code face.gain}, plus each pose ({@code mp.<pose>}) times its carried offsets ({@code face.posed}).
 */
public final class MaskBend {
    private MaskBend() {}

    /** How a drawing bends now: from P to Q (head-set units). */
    static final class Bend {
        final List<double[]> P = new ArrayList<>(), Q = new ArrayList<>();
        boolean moved;
    }

    private static final double DG = Math.PI / 180;

    /** The turning head: a set picked by turn whose role is head, else the first set picked by turn (depth first). */
    public static JSONObject headSet(JSONObject root) {
        JSONObject h = find(root, true);
        return h != null ? h : find(root, false);
    }

    private static JSONObject find(JSONObject n, boolean head) {
        if (n == null) return null;
        JSONObject pk = n.optJSONObject("pick");
        if ("set".equals(n.optString("type")) && pk != null && "turn".equals(pk.optString("by")) && (!head || "head".equals(n.optString("role")))) return n;
        JSONArray k = n.optJSONArray("kids");
        for (int i = 0; k != null && i < k.length(); i++) {
            JSONObject r = find(k.optJSONObject(i), head);
            if (r != null) return r;
        }
        return null;
    }

    /** The drawings a strong pose shows instead ({@code face.swapped}: while {@code mp.<pose>} is over 0.5): set id to kid id. */
    static Map<String, String> swapsNow(JSONObject set, Signals s) {
        JSONObject f = set == null ? null : set.optJSONObject("face"), sw = f == null ? null : f.optJSONObject("swapped");
        if (sw == null) return null;
        Map<String, String> out = null;
        for (Iterator<String> it = sw.keys(); it.hasNext(); ) {
            String id = it.next();
            Double w = s.num("mp." + id);
            if (w == null || w <= 0.5) continue;
            JSONObject m = sw.optJSONObject(id);
            if (m == null) continue;
            for (Iterator<String> j = m.keys(); j.hasNext(); ) {
                String sid = j.next();
                if (out == null) out = new HashMap<>();
                out.put(sid, m.optString(sid));
            }
        }
        return out;
    }

    /** How drawing {@code kid} of the head set bends by the mask now, or null when it has no mask row or no fitted turn. */
    static Bend bendFor(JSONObject set, JSONObject kid, Signals s) {
        JSONObject marks = set.optJSONObject("marks"), mk = marks == null ? null : marks.optJSONObject("");
        JSONObject f = set.optJSONObject("face"), fitAll = f == null ? null : f.optJSONObject("fit");
        String kidId = kid.optString("id");
        JSONArray fit = fitAll == null ? null : fitAll.optJSONArray(kidId);
        JSONObject atAll = mk == null ? null : mk.optJSONObject("at");
        JSONArray row = atAll == null ? null : atAll.optJSONArray(kidId);
        if (fit == null || row == null) return null;
        double yaw = fit.optDouble(0) * DG, pitch = fit.optDouble(1) * DG, sx = fit.optDouble(2, 1), sy = fit.optDouble(3, 1);
        double cy = Math.cos(yaw), syw = Math.sin(yaw), cp = Math.cos(pitch), sp = Math.sin(pitch);
        double g = f.has("gain") && !f.isNull("gain") ? f.optDouble("gain", 1) : 1;
        JSONObject posed = f.optJSONObject("posed");
        JSONArray pts = mk.optJSONArray("pts");
        Bend b = new Bend();
        for (int i = 0; pts != null && i < pts.length(); i++) {
            JSONObject p = pts.optJSONObject(i);
            JSONArray q = row.optJSONArray(i);
            if (p == null || q == null) continue;
            String st = q.length() > 2 ? q.optString(2, null) : null;
            if ("off".equals(st) || "behind".equals(st)) continue;
            double dx = 0, dy = 0;
            int n = p.optInt("n");
            JSONArray face = p.optJSONArray("face");
            if (face != null && face.length() > 0) {
                Double mxv = s.num("mk." + n + ".x"), myv = s.num("mk." + n + ".y");
                double mx = mxv == null ? 0 : mxv, my = myv == null ? 0 : myv;
                if (mx != 0 || my != 0) {                   // the pilot's fwd(R3(yaw, pitch), [mx, my, 0])
                    double x = mx * cy, z = -mx * syw, y = my;
                    double vx = x, vy = y * cp - z * sp;
                    dx = vx * sx * g;
                    dy = vy * sy * g;
                }
            }
            if (posed != null) for (Iterator<String> it = posed.keys(); it.hasNext(); ) {
                String pid = it.next();
                Double wv = s.num("mp." + pid);
                if (wv == null || wv == 0) continue;
                JSONObject on = posed.optJSONObject(pid) == null ? null : posed.optJSONObject(pid).optJSONObject(kidId);
                JSONArray mv = on == null ? null : on.optJSONArray(String.valueOf(n));
                if (mv != null) {
                    dx += mv.optDouble(0) * wv;
                    dy += mv.optDouble(1) * wv;
                }
            }
            if (Math.abs(dx) > 0.01 || Math.abs(dy) > 0.01) b.moved = true;
            double qx = q.optDouble(0), qy = q.optDouble(1);
            b.P.add(new double[]{qx, qy});
            b.Q.add(new double[]{qx + dx, qy + dy});
        }
        return b.P.isEmpty() ? null : b;
    }
}
