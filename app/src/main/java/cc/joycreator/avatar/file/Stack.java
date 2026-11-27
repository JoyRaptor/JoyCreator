package cc.joycreator.avatar.file;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * "In front or behind" for a whole part or a part in a stance (a port of the logic of al-stack.js): the parts as runs of the
 * picture's own draw list, back to front, and the word a step writes: {@code poses[].d[id].z} in a stance, {@code zo} on a whole
 * part. A word is {how: under|over|back|front, of: id, at, tip}. A layer inside a part is reordered among its neighbours instead
 * (that is the room's restack, not this).
 */
public final class Stack {
    private Stack() {
    }

    /** A change to the stack, made inside one undo step. */
    public interface Step {
        void apply(JSONObject character, String poseId, JSONObject node, List<String> drawn) throws JSONException;
    }

    /** One run of the draw list belonging to one part. */
    public static final class Run {
        public final JSONObject unit;
        public int n = 1;

        Run(JSONObject u) {
            unit = u;
        }
    }

    public static final class Read {
        public boolean whole;
        public final List<Run> runs = new ArrayList<>();

        /** Index of the first run that is this part, or -1. */
        public int mine(JSONObject n) {
            for (int i = 0; i < runs.size(); i++) if (runs.get(i).unit == n) return i;
            return -1;
        }
    }

    private static final String[][] ZONE_ROLES = {{"head", "face"}, {"torso", "body"}, {"armR"}, {"armL"}, {"legR"}, {"legL"}, {"tail"}};

    private static void index(JSONObject n, JSONObject par, Map<String, JSONObject> by, Map<String, JSONObject> parent, List<JSONObject> order) {
        by.put(n.optString("id"), n);
        if (par != null) parent.put(n.optString("id"), par);
        order.add(n);
        JSONArray k = n.optJSONArray("kids");
        for (int i = 0; k != null && i < k.length(); i++) {
            JSONObject c = k.optJSONObject(i);
            if (c != null) index(c, n, by, parent, order);
        }
    }

    private static boolean isLimb(JSONObject n) {
        JSONObject pick = n.optJSONObject("pick");
        return "set".equals(n.optString("type")) && pick != null && "reach".equals(pick.optString("by")) && pick.has("hand");
    }

    /** The parts that are stacked against each other: the zones (head, body, arms, legs, tail) and every limb. */
    public static List<JSONObject> units(JSONObject root, List<JSONObject> order) {
        List<JSONObject> u = new ArrayList<>();
        for (String[] roles : ZONE_ROLES) {
            for (String role : roles) {
                JSONObject f = null;
                for (JSONObject n : order) if (role.equals(n.optString("role"))) {
                    f = n;
                    break;
                }
                if (f != null) {
                    if (!u.contains(f)) u.add(f);
                    break;
                }
            }
        }
        for (JSONObject n : order) if (isLimb(n) && !u.contains(n)) u.add(n);
        return u;
    }

    /** The parts as runs of the draw list (ids of what is drawn, back to front, no ghosts), as seen from part {@code nodeId}. */
    public static Read read(JSONObject ch, List<String> drawn, String nodeId) {
        Map<String, JSONObject> by = new HashMap<>(), parent = new HashMap<>();
        List<JSONObject> order = new ArrayList<>();
        JSONObject root = ch.optJSONObject("root");
        Read r = new Read();
        if (root == null) return r;
        index(root, null, by, parent, order);
        JSONObject n = by.get(nodeId);
        if (n == null) return r;
        Map<String, JSONObject> units = new HashMap<>();
        Map<String, Boolean> spine = new HashMap<>();
        for (JSONObject u : units(root, order)) {
            units.put(u.optString("id"), u);
            JSONObject p = parent.get(u.optString("id"));
            while (p != null) {
                spine.put(p.optString("id"), true);
                p = parent.get(p.optString("id"));
            }
        }
        r.whole = owner(by, parent, units, spine, nodeId) == n;
        for (String id : drawn) {
            JSONObject u = owner(by, parent, units, spine, id);
            if (u == null) continue;
            Run last = r.runs.isEmpty() ? null : r.runs.get(r.runs.size() - 1);
            if (last != null && last.unit == u) last.n++;
            else r.runs.add(new Run(u));
        }
        return r;
    }

    private static JSONObject owner(Map<String, JSONObject> by, Map<String, JSONObject> parent, Map<String, JSONObject> units, Map<String, Boolean> spine, String id) {
        JSONObject q = by.get(id), top = null;
        while (q != null) {
            if (units.containsKey(q.optString("id"))) return q;
            if (!spine.containsKey(q.optString("id"))) top = q;
            q = parent.get(q.optString("id"));
        }
        return top;
    }

    /** Where the order of this part is kept right now: the stance's word, or the part's own. */
    public static JSONObject getZ(JSONObject ch, String poseId, JSONObject n) {
        if (poseId != null) {
            JSONObject p = Stances.find(ch, poseId), d = p == null ? null : p.optJSONObject("d");
            JSONObject e = d == null ? null : d.optJSONObject(n.optString("id"));
            return e == null ? null : e.optJSONObject("z");
        }
        return n.optJSONObject("zo");
    }

    private static boolean bare(JSONObject e) {
        return !e.has("auto") && !e.optBoolean("auto") && e.optDouble("x", 0) == 0 && e.optDouble("y", 0) == 0 && e.optDouble("rot", 0) == 0
                && (!e.has("sc") || e.optDouble("sc", 1) == 1) && !e.has("orbit");
    }

    /**
     * THE NEWEST WORD WINS: another part may already say the opposite about this one, and left alone the two fight and the button
     * does nothing. So the older one lets go of just the piece that contradicts.
     */
    public static void settle(JSONObject ch, JSONObject pose, JSONObject n, JSONObject z) throws JSONException {
        if (z == null || "back".equals(z.optString("how"))) return;
        String nid = n.optString("id");
        if (pose != null) {
            JSONObject d = pose.optJSONObject("d");
            if (d == null) return;
            List<String> ids = new ArrayList<>();
            for (java.util.Iterator<String> it = d.keys(); it.hasNext(); ) ids.add(it.next());
            for (String id : ids) {
                JSONObject e = d.optJSONObject(id);
                if (id.equals(nid) || e == null || e.optJSONObject("z") == null) continue;
                if (!fix(e.getJSONObject("z"), id, nid, z)) {
                    e.remove("z");
                    if (bare(e)) d.remove(id);
                }
            }
        } else {
            JSONObject root = ch.optJSONObject("root");
            Map<String, JSONObject> by = new HashMap<>(), parent = new HashMap<>();
            List<JSONObject> order = new ArrayList<>();
            if (root == null) return;
            index(root, null, by, parent, order);
            for (JSONObject q : order) {
                if (q == n || q.optJSONObject("zo") == null) continue;
                if (!fix(q.getJSONObject("zo"), q.optString("id"), nid, z)) q.remove("zo");
            }
        }
    }

    /** Returns false when the word must go. */
    private static boolean fix(JSONObject tz, String id, String nid, JSONObject z) {
        if (!nid.equals(tz.optString("of")) || (z.has("of") && !z.optString("of").equals(id))) return true;
        String how = tz.optString("how");
        if ("under".equals(z.optString("how"))) return !"under".equals(how);   // it cannot be behind this one while this one is behind it
        if ("over".equals(how)) return false;
        if ("under".equals(how) && tz.has("tip")) tz.remove("tip");             // its hand no longer comes in front
        return true;
    }

    /** Write a word (or null to forget it): the stance's, or the part's own. */
    public static void setZ(JSONObject ch, String poseId, JSONObject n, JSONObject z) throws JSONException {
        JSONObject p = poseId == null ? null : Stances.find(ch, poseId);
        settle(ch, p, n, z);
        if (p != null) {
            JSONObject d = p.optJSONObject("d");
            if (d == null) p.put("d", d = new JSONObject());
            JSONObject e = d.optJSONObject(n.optString("id"));
            if (e == null) d.put(n.optString("id"), e = new JSONObject());
            if (z != null) e.put("z", z);
            else {
                e.remove("z");
                if (bare(e)) d.remove(n.optString("id"));
            }
        } else if (z != null) n.put("zo", z);
        else n.remove("zo");
    }

    /** One step past a part: behind it if {@code back}, in front of it if not. */
    public static void past(JSONObject ch, String poseId, JSONObject n, JSONObject target, boolean back) throws JSONException {
        JSONObject z = getZ(ch, poseId, n), nz = new JSONObject().put("how", back ? "under" : "over").put("of", target.optString("id"));
        if (z != null && z.has("at")) nz.put("at", z.get("at"));
        if (back && z != null && z.has("tip")) nz.put("tip", z.get("tip"));
        setZ(ch, poseId, n, nz);
    }

    /** A step along the row: -1 back one, +1 forward one. False when there is nowhere to go. */
    public static boolean step(JSONObject ch, String poseId, List<String> drawn, JSONObject n, int dir) throws JSONException {
        if (dir == 0) return false;
        Read r = read(ch, drawn, n.optString("id"));
        int i = r.mine(n);
        if (i < 0) return false;
        if (dir > 0) while (i + 1 < r.runs.size() && r.runs.get(i + 1).unit == n) i++;
        int j = i + dir;
        while (j >= 0 && j < r.runs.size() && r.runs.get(j).unit == n) j += dir;
        if (j < 0 || j >= r.runs.size()) return false;
        past(ch, poseId, n, r.runs.get(j).unit, dir < 0);
        return true;
    }
}
