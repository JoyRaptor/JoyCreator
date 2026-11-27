package cc.joycreator.avatar.file;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * A round head from what is already there (the pilot's {@code AL.guessBall}): the ball is the face drawing; eyes, nose, mouth
 * and brows are painted on it; hair, ears and horns stick out of it; anything unnamed is judged by where it sits. Every guess is
 * one tap to change on the part's own card.
 */
public final class Ball {
    private Ball() {}

    private static final String WRAP_ON = ",eyeL,eyeR,pupil,eyeball,blink,lidU,lidL,browL,browR,nose,mouth,jaw,";

    /** Fits the ball to the head and decides which parts are painted on ({@code wrap: on}) or stick out ({@code out}). False when there is no drawing to fit. */
    public static boolean guess(JSONObject doc, JSONObject hd, Stances.Bounds bounds) throws JSONException {
        boolean isSet = "set".equals(hd.optString("type"));
        java.util.List<JSONObject> views = new java.util.ArrayList<>();
        JSONArray kids = hd.optJSONArray("kids");
        if (isSet) {
            for (int i = 0; kids != null && i < kids.length(); i++) {
                JSONObject k = kids.optJSONObject(i);
                if (k != null && "group".equals(k.optString("type"))) views.add(k);
            }
        } else views.add(hd);
        JSONObject rest = null;
        if (isSet) {
            JSONObject pick = hd.optJSONObject("pick");
            String restId = pick == null ? "" : pick.optString("rest", "");
            for (JSONObject v : views) if (v.optString("id").equals(restId)) rest = v;
            if (rest == null && !views.isEmpty()) rest = views.get(0);
        } else rest = hd;
        if (rest == null) return false;
        JSONObject face = find(rest, "face", true);
        double[] b = face != null ? bounds.of(face) : null;
        if (b == null) b = bounds.of(rest);
        if (b == null) return false;
        JSONObject old = hd.optJSONObject("ball");
        if (old == null) old = new JSONObject();
        double cx = (b[0] + b[2]) / 2, cy = (b[1] + b[3]) / 2, rx = Math.max(8, (b[2] - b[0]) / 2), ry = Math.max(8, (b[3] - b[1]) / 2);
        JSONObject ball = new JSONObject().put("cx", cx).put("cy", cy).put("rx", rx).put("ry", ry)
                .put("yaw", old.has("yaw") ? old.optDouble("yaw") : 55).put("pitch", old.has("pitch") ? old.optDouble("pitch") : 32);
        if (!isSet && face != null) ball.put("shell", face.optString("id"));
        hd.put("ball", ball);
        for (JSONObject v : views) wrap(v, v, bounds, cx, cy, rx, ry);
        return true;
    }

    private static JSONObject find(JSONObject n, String role, boolean artOnly) {
        if (role.equals(n.optString("role")) && (!artOnly || "art".equals(n.optString("type")))) return n;
        JSONArray k = n.optJSONArray("kids");
        for (int i = 0; k != null && i < k.length(); i++) {
            JSONObject f = find(k.optJSONObject(i), role, artOnly);
            if (f != null) return f;
        }
        return null;
    }

    private static void wrap(JSONObject n, JSONObject view, Stances.Bounds bounds, double cx, double cy, double rx, double ry) throws JSONException {
        if (n != view) {
            String role = n.optString("role");
            if ("face".equals(role)) {
                n.remove("wrap");
                return;
            }
            if (WRAP_ON.contains("," + role + ",") && !role.isEmpty()) {
                n.put("wrap", "on");
                return;
            }
            if ("hair".equals(role)) {
                n.put("wrap", "out");
                return;
            }
            JSONArray k0 = n.optJSONArray("kids");
            if (!"group".equals(n.optString("type")) || k0 == null || k0.length() == 0) {
                double[] q = bounds.of(n);
                if (q != null) {
                    double nx = ((q[0] + q[2]) / 2 - cx) / rx, ny = ((q[1] + q[3]) / 2 - cy) / ry;
                    n.put("wrap", nx * nx + ny * ny < 0.72 ? "on" : "out");
                }
                return;
            }
        }
        JSONArray kids = n.optJSONArray("kids");
        for (int i = 0; kids != null && i < kids.length(); i++) wrap(kids.optJSONObject(i), view, bounds, cx, cy, rx, ry);
    }
}
