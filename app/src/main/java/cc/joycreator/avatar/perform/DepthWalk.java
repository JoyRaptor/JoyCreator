package cc.joycreator.avatar.perform;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Walking toward the camera and away (the pilot's {@code tools/avatarlab/al-depth-walk.js}; the owner, 11-22: "like Character
 * Animator"). The walker walks its GROUND LINE toward a depth target ({@link Walker#tz}) with real steps; this says, for where
 * that ground line is, how big the character is drawn ({@link #s}), how far toward or away it may go ({@link #lim}), and which
 * drawing of its head faces the camera ({@link #front}: coming toward you it turns to it).
 *
 * <p>ALONE, on the plain stage, it grows from its own eye line down, as a person walking up to a camera held at eye height does:
 * the top of the drawing stays where it is and the feet come down the screen. From about two thirds of its size (away) to nearly
 * half as big again (close), as much as the camera's angle gives ({@code settings.floorAngle}, 45 degrees when not set: an
 * overhead camera sees no change of size). Only while depth is in use ({@link #prep}); otherwise the stage is exactly as before.
 * In a scene the scene's floor rule takes over (slice 3).</p>
 */
public final class DepthWalk {
    public static final double NEAR = 0.45, FAR = 0.35;        // alone: how much bigger it may come, how much smaller it may go
    public static final double ANGLE = 45, MAX_ANGLE = 75;     // the camera's angle where it starts, and how low it may go

    /** Its height from the top of its drawing to its ground line, and how strongly size follows depth. */
    public final double H, k;
    /** {away, toward}, its own units (away is negative). */
    public final double[] lim;
    /** The place on the turn pad of the head's drawing that faces the camera (the angle nearest the middle), or null. */
    public final Double front;

    private DepthWalk(double H, double k, Double front) {
        this.H = H;
        this.k = k;
        this.front = front;
        this.lim = new double[]{-FAR * H, NEAR * H};
    }

    /** How big it is drawn with its ground line at {@code base} (toward the camera is +). */
    public double s(double base) {
        return clamp(1 + k * base / H, 0.3, 3);
    }

    /** How much size changes with depth for a camera angle: 1 at the starting angle, 0 overhead (al-stick-depth.js). */
    public static double k(double deg) {
        return Math.sin(Math.toRadians(clamp(deg, 0, 89))) / Math.sin(Math.toRadians(ANGLE));
    }

    /** The plain stage's depth model for a character ({@code rootBounds}: its drawing's rest bounds, [x0, y0, x1, y1]). */
    public static DepthWalk plain(JSONObject ch, double[] rootBounds) {
        JSONObject set = ch.optJSONObject("settings");
        double fa = set != null && set.has("floorAngle") && !set.isNull("floorAngle") ? set.optDouble("floorAngle", ANGLE) : ANGLE;
        return new DepthWalk(tall(ch, rootBounds), k(fa), front(ch));
    }

    /** From the top of its drawing to its ground line (the walk's groundY, else the drawing's bottom). */
    static double tall(JSONObject ch, double[] b) {
        if (b == null) b = new double[]{0, 0, ch.optDouble("w", 512), ch.optDouble("h", 512)};
        JSONObject set = ch.optJSONObject("settings"), w = set == null ? null : set.optJSONObject("walk");
        double gy = w != null && w.has("groundY") && !w.isNull("groundY") ? w.optDouble("groundY") : b[3];
        return Math.max(20, gy - b[1]);
    }

    /** The head's angle nearest the middle of the turn pad (within 0.3): its across place, or null when it has none. */
    static Double front(JSONObject ch) {
        JSONObject hd = find(ch.optJSONObject("root"), "head");
        if (hd == null || !"set".equals(hd.optString("type"))) return null;
        JSONObject pk = hd.optJSONObject("pick");
        if (pk == null || !"turn".equals(pk.optString("by"))) return null;
        JSONArray kids = hd.optJSONArray("kids");
        Double best = null;
        double bd = 0.3;
        for (int i = 0; kids != null && i < kids.length(); i++) {
            JSONObject k = kids.optJSONObject(i);
            JSONArray at = k == null ? null : k.optJSONArray("at");
            if (at == null || at.length() < 2) continue;
            double d = Math.max(Math.abs(at.optDouble(0)), Math.abs(at.optDouble(1)));
            if (d <= bd) {
                bd = d;
                best = at.optDouble(0);
            }
        }
        return best;
    }

    /** The first node (depth first, as AL.firstRole) whose role is {@code role}. */
    private static JSONObject find(JSONObject n, String role) {
        if (n == null) return null;
        if (role.equals(n.optString("role"))) return n;
        JSONArray k = n.optJSONArray("kids");
        for (int i = 0; k != null && i < k.length(); i++) {
            JSONObject r = find(k.optJSONObject(i), role);
            if (r != null) return r;
        }
        return null;
    }

    /**
     * Once a frame, before the walker steps: alone on the plain stage the depth model is there only while depth is in use
     * ({@code want}: the face's Closer and further, Up close, or the joystick in perspective), or while it is still on its way
     * back. Then it is let go and the stage is exactly as before.
     */
    public static void prep(Walker w, boolean want, JSONObject ch, double[] rootBounds) {
        boolean still = w.z != null && (Math.abs(w.baseY) > 0.05 || (w.tz != null && Math.abs(w.tz) > 0.05));
        if (!want && !still) {
            if (w.z != null) {
                w.z = null;
                w.tz = null;
            }
            return;
        }
        if (w.z == null) w.z = plain(ch, rootBounds);
    }

    private static double clamp(double v, double a, double b) {
        return v < a ? a : v > b ? b : v;
    }
}
