package cc.joycreator.avatar.face;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Shoulder shrugs on the character (SHRUG-1; the pilot's {@code al-pose.js}): three Links, each arm up with its side's shrug, the
 * head down a little with both, sized from the drawing (the arm lifts six tenths of its length, the head sinks half that; the
 * owner, 11-22: "still too small"). The glide between the body's readings is the face capture's ({@code ShoulderFeed}).
 * Pure: the room makes the rig one undo step.
 */
public final class Shrugs {
    private Shrugs() {}

    public static final String SHRUG_L = "shrugL", SHRUG_R = "shrugR", SHRUG = "shrug";
    /** Marks a Link as the shrug's (3: today's size; older rounds were rebuilt once). */
    static final int MARK = 3;

    /** A drawing's size, for the rig (the room's rest bounds: [x0, y0, x1, y1], or null). */
    public interface Bounds {
        double[] of(JSONObject node);
    }

    /** The first node with this role (depth first), or null. */
    public static JSONObject firstRole(JSONObject n, String role) {
        if (n == null) return null;
        if (role.equals(n.optString("role"))) return n;
        JSONArray k = n.optJSONArray("kids");
        for (int i = 0; k != null && i < k.length(); i++) {
            JSONObject r = firstRole(k.optJSONObject(i), role);
            if (r != null) return r;
        }
        return null;
    }

    static boolean hasShrug(JSONObject n) {
        JSONArray l = n == null ? null : n.optJSONArray("links");
        for (int i = 0; l != null && i < l.length(); i++) if (l.optJSONObject(i) != null && l.optJSONObject(i).has("shrug")) return true;
        return false;
    }

    /** Has it the parts a shrug moves (an arm or the head)? */
    public static boolean rigs(JSONObject ch) {
        JSONObject r = ch.optJSONObject("root");
        return firstRole(r, "armL") != null || firstRole(r, "armR") != null || firstRole(r, "head") != null;
    }

    /** Would {@link #rig} give it a shrug now (the parts, and it never said no shrugs)? */
    public static boolean canRig(JSONObject ch) {
        return !ch.optBoolean("noShrug") && rigs(ch) && !has(ch);
    }

    /** Does a shrug move anything on it (its own shrug, one it would get, or any part's Link on a shrug)? */
    public static boolean drives(JSONObject ch) {
        return has(ch) || canRig(ch) || linksShrug(ch.optJSONObject("root"));
    }

    private static boolean linksShrug(JSONObject n) {
        if (n == null) return false;
        JSONArray l = n.optJSONArray("links");
        for (int i = 0; l != null && i < l.length(); i++) if (l.optJSONObject(i) != null && l.optJSONObject(i).optString("sig").startsWith(SHRUG)) return true;
        JSONArray k = n.optJSONArray("kids");
        for (int i = 0; k != null && i < k.length(); i++) if (linksShrug(k.optJSONObject(i))) return true;
        return false;
    }

    /** Is the character set up to shrug? */
    public static boolean has(JSONObject ch) {
        JSONObject r = ch.optJSONObject("root");
        return hasShrug(firstRole(r, "armL")) || hasShrug(firstRole(r, "armR")) || hasShrug(firstRole(r, "head"));
    }

    /** An arm's length: its limb's shoulder to hand, else the longer side of its drawing, else 60. */
    static double armLength(JSONObject n, Bounds b) {
        JSONObject k = n.optJSONObject("limb") != null ? n : null;
        JSONArray kids = n.optJSONArray("kids");
        for (int i = 0; k == null && kids != null && i < kids.length(); i++) if (kids.optJSONObject(i) != null && kids.optJSONObject(i).optJSONObject("limb") != null) k = kids.optJSONObject(i);
        if (k == null) {
            double[] bb = b.of(n);
            return bb == null ? 60 : Math.max(bb[2] - bb[0], bb[3] - bb[1]);
        }
        JSONObject l = k.optJSONObject("limb"), a = l.optJSONObject("a"), c = l.optJSONObject("c");
        if (a == null || c == null) return 60;
        return Math.hypot(c.optDouble("x") - a.optDouble("x"), c.optDouble("y") - a.optDouble("y"));
    }

    private static void strip(JSONObject n) throws JSONException {
        JSONArray l = n == null ? null : n.optJSONArray("links");
        if (l == null) return;
        JSONArray keep = new JSONArray();
        for (int i = 0; i < l.length(); i++) if (l.optJSONObject(i) == null || !l.optJSONObject(i).has("shrug")) keep.put(l.opt(i));
        n.put("links", keep);
    }

    /**
     * Gives the character its shrug (unless it said no shrugs, or it already has today's): each arm up with its side, the head down
     * a little with both. Returns whether it changed anything.
     */
    public static boolean rig(JSONObject ch, Bounds b) throws JSONException {
        if (ch.optBoolean("noShrug")) return false;
        JSONObject r = ch.optJSONObject("root"), armL = firstRole(r, "armL"), armR = firstRole(r, "armR"), head = firstRole(r, "head");
        if (has(ch)) {
            boolean old = false;
            for (JSONObject n : new JSONObject[]{armL, armR, head}) {
                JSONArray l = n == null ? null : n.optJSONArray("links");
                for (int i = 0; l != null && i < l.length(); i++) if (l.optJSONObject(i) != null && l.optJSONObject(i).has("shrug") && l.optJSONObject(i).optInt("shrug") < MARK) old = true;
            }
            if (!old) return false;
            for (JSONObject n : new JSONObject[]{armL, armR, head}) strip(n);
        }
        double lift = 0;
        int added = 0;
        if (armL != null) { double l = armLength(armL, b) * 0.6; lift = Math.max(lift, l); add(armL, SHRUG_L, -l); added++; }
        if (armR != null) { double l = armLength(armR, b) * 0.6; lift = Math.max(lift, l); add(armR, SHRUG_R, -l); added++; }
        if (head != null) {
            double[] hb = b.of(head);
            add(head, SHRUG, lift > 0 ? lift * 0.5 : (hb != null ? (hb[3] - hb[1]) * 0.05 : 5));
            added++;
        }
        return added > 0;
    }

    private static void add(JSONObject n, String sig, double amt) throws JSONException {
        JSONArray l = n.optJSONArray("links");
        if (l == null) { l = new JSONArray(); n.put("links", l); }
        l.put(new JSONObject().put("sig", sig).put("prop", "y").put("amt", Math.round(amt * 2) / 2.0).put("shrug", MARK));
    }

    /** Shrugs off ({@code noShrug}, the Links taken away) or on again (rigged). */
    public static void setOn(JSONObject ch, boolean on, Bounds b) throws JSONException {
        JSONObject r = ch.optJSONObject("root");
        if (on) {
            ch.remove("noShrug");
            rig(ch, b);
        } else {
            ch.put("noShrug", true);
            for (String role : new String[]{"armL", "armR", "head"}) strip(firstRole(r, role));
        }
    }

    /** The arms' lift (pixels, both arms) or the head's sink: the size of their shrug Links. */
    public static void setAmount(JSONObject ch, boolean arms, double px) throws JSONException {
        JSONObject r = ch.optJSONObject("root");
        for (String role : arms ? new String[]{"armL", "armR"} : new String[]{"head"}) {
            JSONArray l = firstRole(r, role) == null ? null : firstRole(r, role).optJSONArray("links");
            for (int i = 0; l != null && i < l.length(); i++) if (l.optJSONObject(i) != null && l.optJSONObject(i).has("shrug")) l.optJSONObject(i).put("amt", arms ? -px : px);
        }
    }

    /** The arms' lift or the head's sink now (pixels), or NaN with no shrug Links. */
    public static double amount(JSONObject ch, boolean arms) {
        JSONObject r = ch.optJSONObject("root");
        for (String role : arms ? new String[]{"armL", "armR"} : new String[]{"head"}) {
            JSONArray l = firstRole(r, role) == null ? null : firstRole(r, role).optJSONArray("links");
            for (int i = 0; l != null && i < l.length(); i++) if (l.optJSONObject(i) != null && l.optJSONObject(i).has("shrug")) return arms ? -l.optJSONObject(i).optDouble("amt") : l.optJSONObject(i).optDouble("amt");
        }
        return Double.NaN;
    }
}
