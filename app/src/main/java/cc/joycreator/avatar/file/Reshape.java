package cc.joycreator.avatar.file;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reshape with pins (a port of the data half of al-fit.js): a part gets pins, and a Shape that is always on (standing: "Reshaped")
 * or that comes with a stance's button ("In all fours"). Dragging a pin writes that shape's offset for the pin; the pins left alone
 * hold the rest still. A new pin must not move the part when it lands, in this stance or any other (dropPin).
 */
public final class Reshape {
    public static final String FIX = "fix";

    private Reshape() {
    }

    /** Where a spot of a part goes when its shapes are worn at these strengths (the solver's warpAt). */
    public interface Warp {
        double[] at(String nodeId, double x, double y, Map<String, Double> weights);
    }

    private static String newId(String prefix) {
        return prefix + Long.toString((long) (Math.random() * 60466176L), 36);
    }

    /** The shape this reshaping is kept in: the standing one, or the stance being edited. */
    public static String shapeId(String poseId) {
        return poseId == null ? FIX : "pose:" + poseId;
    }

    public static JSONObject fixShape(JSONObject n, String poseId) {
        JSONArray shp = n.optJSONArray("shapes");
        String id = shapeId(poseId);
        for (int i = 0; shp != null && i < shp.length(); i++) {
            JSONObject s = shp.optJSONObject(i);
            if (s != null && id.equals(s.optString("id"))) return s;
        }
        return null;
    }

    /** Make the part ready to be reshaped: a mesh and the shape. {@code pose}: the stance being edited, or null. */
    public static void on(JSONObject n, JSONObject pose) throws JSONException {
        JSONObject mesh = n.optJSONObject("mesh");
        if (mesh == null) n.put("mesh", mesh = new JSONObject().put("pins", new JSONArray()).put("quiet", true));
        if (mesh.optJSONArray("pins") == null) mesh.put("pins", new JSONArray());
        String pid = pose == null ? null : pose.optString("id");
        if (fixShape(n, pid) != null) return;
        JSONArray shp = n.optJSONArray("shapes");
        if (shp == null) n.put("shapes", shp = new JSONArray());
        JSONObject s = pose != null
                ? new JSONObject().put("id", shapeId(pid)).put("name", "In " + pose.optString("name")).put("sig", "trig:" + pose.optString("trig")).put("amt", 1).put("d", new JSONObject())
                : new JSONObject().put("id", FIX).put("name", "Reshaped").put("sig", "always").put("amt", 1).put("d", new JSONObject());
        JSONArray rebuilt = new JSONArray().put(s);                      // first in the list, as the pilot's unshift
        for (int i = 0; i < shp.length(); i++) rebuilt.put(shp.get(i));
        n.put("shapes", rebuilt);
    }

    /** The strengths a reshape is seen at: this shape, and every shape that is always on. */
    public static Map<String, Double> weights(JSONObject n, String poseId) {
        Map<String, Double> w = new HashMap<>();
        String sid = shapeId(poseId);
        w.put(sid, 1.0);
        JSONArray shp = n.optJSONArray("shapes");
        for (int i = 0; shp != null && i < shp.length(); i++) {
            JSONObject s = shp.optJSONObject(i);
            if (s != null && "always".equals(s.optString("sig"))) w.put(s.optString("id"), 1.0);
        }
        return w;
    }

    /** Which pose a pin belongs to: the one it was dropped in. (Older pins: standing, unless only a stance ever moved them.) */
    public static String pinHome(JSONObject n, JSONObject q) {
        String home = q.optString("home", "");
        if (!home.isEmpty()) return home;
        JSONArray shp = n.optJSONArray("shapes");
        String first = null;
        boolean fix = false;
        for (int i = 0; shp != null && i < shp.length(); i++) {
            JSONObject s = shp.optJSONObject(i), d = s == null ? null : s.optJSONObject("d");
            if (d != null && d.has(q.optString("id"))) {
                if (first == null) first = s.optString("id");
                if (FIX.equals(s.optString("id"))) fix = true;
            }
        }
        return first == null || fix ? FIX : first;
    }

    public static List<JSONObject> ownPins(JSONObject n, String poseId) {
        List<JSONObject> out = new ArrayList<>();
        JSONObject mesh = n.optJSONObject("mesh");
        JSONArray pins = mesh == null ? null : mesh.optJSONArray("pins");
        String sid = shapeId(poseId);
        for (int i = 0; pins != null && i < pins.length(); i++) {
            JSONObject q = pins.optJSONObject(i);
            if (q != null && sid.equals(pinHome(n, q))) out.add(q);
        }
        return out;
    }

    private static double r1(double v) {
        return Math.round(v * 10) / 10.0;
    }

    /**
     * A NEW PIN, on a part that may already be reshaped, here or in another pose. The part must not move when the pin lands: in every
     * shape that already bends the part, the new pin is given the place that shape was already carrying that spot to. (lx, ly) is the
     * spot under the finger, as drawn now.
     */
    public static JSONObject dropPin(JSONObject n, String poseId, double lx, double ly, Warp warp) throws JSONException {
        String sid = shapeId(poseId), nid = n.optString("id");
        JSONArray shp = n.optJSONArray("shapes");
        JSONObject mesh = n.getJSONObject("mesh");
        JSONArray pins = mesh.getJSONArray("pins");
        Map<String, Double> W = new HashMap<>();
        for (int i = 0; shp != null && i < shp.length(); i++) {
            JSONObject s = shp.optJSONObject(i);
            if (s != null && (sid.equals(s.optString("id")) || "always".equals(s.optString("sig")))) W.put(s.optString("id"), 1.0);
        }
        double x = lx, y = ly;
        for (int i = 0; i < 6; i++) {                                      // the spot of the drawing that is under the finger
            double[] w = warp.at(nid, x, y, W);
            x += lx - w[0];
            y += ly - w[1];
        }
        x = Math.round(x);
        y = Math.round(y);
        JSONObject q = new JSONObject().put("id", newId("p")).put("name", "Pin " + (pins.length() + 1)).put("x", x).put("y", y).put("home", sid);
        Map<String, double[]> ride = new HashMap<>();
        double sx = 0, sy = 0;
        for (int i = 0; shp != null && i < shp.length(); i++) {
            JSONObject s = shp.optJSONObject(i), d = s == null ? null : s.optJSONObject("d");
            if (s == null || sid.equals(s.optString("id")) || d == null || d.length() == 0) continue;
            Map<String, Double> one = new HashMap<>();
            one.put(s.optString("id"), 1.0);
            double[] w1 = warp.at(nid, x, y, one);
            double dx = r1(w1[0] - x), dy = r1(w1[1] - y);
            if (dx != 0 || dy != 0) {
                ride.put(s.optString("id"), new double[]{dx, dy});
                if (W.containsKey(s.optString("id"))) {
                    sx += dx;
                    sy += dy;
                }
            }
        }
        JSONObject cur = fixShape(n, poseId);
        double[] here = warp.at(nid, x, y, W);
        double ox = r1(here[0] - x - sx), oy = r1(here[1] - y - sy);
        pins.put(q);
        for (int i = 0; shp != null && i < shp.length(); i++) {
            JSONObject s = shp.optJSONObject(i);
            double[] r = s == null ? null : ride.get(s.optString("id"));
            if (r != null) s.getJSONObject("d").put(q.getString("id"), new JSONArray().put(r[0]).put(r[1]));
        }
        if (cur != null && (ox != 0 || oy != 0)) {
            JSONObject d = cur.optJSONObject("d");
            if (d == null) cur.put("d", d = new JSONObject());
            d.put(q.getString("id"), new JSONArray().put(ox).put(oy));
        }
        return q;
    }

    /** Take out the last pin dropped in THIS pose (a stance cannot take out a pin the standing shape is made with). */
    public static JSONObject removePin(JSONObject n, String poseId) throws JSONException {
        List<JSONObject> own = ownPins(n, poseId);
        if (own.isEmpty()) return null;
        JSONObject q = own.get(own.size() - 1);
        JSONArray pins = n.getJSONObject("mesh").getJSONArray("pins"), kept = new JSONArray();
        for (int i = 0; i < pins.length(); i++) if (pins.get(i) != q) kept.put(pins.get(i));
        n.getJSONObject("mesh").put("pins", kept);
        JSONArray shp = n.optJSONArray("shapes");
        for (int i = 0; shp != null && i < shp.length(); i++) {
            JSONObject d = shp.getJSONObject(i).optJSONObject("d");
            if (d != null) d.remove(q.optString("id"));
        }
        return q;
    }

    /** Every pin back to where it was dropped, in this shape. */
    public static void startAgain(JSONObject n, String poseId) throws JSONException {
        JSONObject fs = fixShape(n, poseId);
        if (fs != null) fs.put("d", new JSONObject());
    }

    /** Where a pin is drawn for reshaping: where the OTHER shapes on show put it, plus this shape's offset. */
    public static double[] under(JSONObject n, JSONObject q, String poseId) {
        double bx = q.optDouble("x"), by = q.optDouble("y");
        Map<String, Double> W = weights(n, poseId);
        JSONArray shp = n.optJSONArray("shapes");
        String sid = shapeId(poseId);
        for (int i = 0; shp != null && i < shp.length(); i++) {
            JSONObject o = shp.optJSONObject(i);
            if (o == null || sid.equals(o.optString("id"))) continue;
            Double w = W.get(o.optString("id"));
            JSONObject dd = o.optJSONObject("d");
            JSONArray d = w == null || w == 0 || dd == null ? null : dd.optJSONArray(q.optString("id"));
            if (d != null) {
                bx += d.optDouble(0) * w;
                by += d.optDouble(1) * w;
            }
        }
        return new double[]{bx, by};
    }

    /** Where the pin is shown now: under + this shape's offset. */
    public static double[] shownAt(JSONObject n, JSONObject q, String poseId) {
        double[] b = under(n, q, poseId);
        JSONObject fs = fixShape(n, poseId), d = fs == null ? null : fs.optJSONObject("d");
        JSONArray o = d == null ? null : d.optJSONArray(q.optString("id"));
        return o == null ? b : new double[]{b[0] + o.optDouble(0), b[1] + o.optDouble(1)};
    }

    /** A pin was dragged to (x, y) in the part's own space: this shape's offset is what it takes to get there. */
    public static void dragTo(JSONObject n, String poseId, String pinId, double x, double y) throws JSONException {
        JSONObject fs = fixShape(n, poseId);
        JSONArray pins = n.getJSONObject("mesh").getJSONArray("pins");
        JSONObject q = null;
        for (int i = 0; i < pins.length(); i++) if (pinId.equals(pins.getJSONObject(i).optString("id"))) q = pins.getJSONObject(i);
        if (fs == null || q == null) return;
        double[] b = under(n, q, poseId);
        JSONObject d = fs.optJSONObject("d");
        if (d == null) fs.put("d", d = new JSONObject());
        d.put(pinId, new JSONArray().put(r1(x - b[0])).put(r1(y - b[1])));
    }
}
