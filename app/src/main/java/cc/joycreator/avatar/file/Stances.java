package cc.joycreator.avatar.file;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Stances (the pilot's {@code al-stance.js}): whole-body poses the character melts into at the press of a button (all
 * fours, sitting, a crouch). A stance is {@code { id, name, trig, time, bounce, d: { nodeId: { x, y, rot, sc, orbit } },
 * fours }}; each has its own button, so a take records it like any other. All fours is WORKED OUT from the art: the torso tips
 * forward about the hips until the hands reach the ground; the legs and tail stay put; the head rides down level.
 *
 * <p>Edits the live JSON of the character; the room wraps each call in one named undo step. Where a part is on the
 * picture comes from the caller ({@link Bounds}), because the drawing knows it. Not ported yet: guessing how high a hand
 * stands from its pixels (a walking hand uses its own {@code pick.walk.palm}, else 0).</p>
 */
public final class Stances {

    /** Where a part is at rest: [x0, y0, x1, y1], or null when it shows nothing. */
    public interface Bounds {
        double[] of(JSONObject node);
    }

    private Stances() {}

    // ── the tree ──────────────────────────────────────────────────────────────────────────────────

    private static final class Tree {
        final Map<String, JSONObject> byId = new HashMap<>();
        final Map<String, JSONObject> parent = new HashMap<>();
        final List<JSONObject> order = new ArrayList<>();

        Tree(JSONObject root) {
            walk(root, null);
        }

        private void walk(JSONObject n, JSONObject par) {
            order.add(n);
            byId.put(n.optString("id"), n);
            if (par != null) parent.put(n.optString("id"), par);
            JSONArray k = n.optJSONArray("kids");
            for (int i = 0; k != null && i < k.length(); i++) {
                JSONObject c = k.optJSONObject(i);
                if (c != null) walk(c, n);
            }
        }

        JSONObject firstRole(String role) {
            for (JSONObject n : order) if (role.equals(n.optString("role"))) return n;
            return null;
        }

        List<JSONObject> byRole(String role) {
            List<JSONObject> out = new ArrayList<>();
            for (JSONObject n : order) if (role.equals(n.optString("role"))) out.add(n);
            return out;
        }

        /** Does {@code n} move when {@code torso} moves? (It hangs from it, or sits inside it.) */
        boolean carried(JSONObject n, JSONObject torso) {
            JSONObject q = n;
            int guard = 0;
            while (q != null && guard++ < 40) {
                if (q == torso) return true;
                String at = q.optString("attach", "");
                if (!at.isEmpty()) {
                    if (at.equals(torso.optString("id"))) return true;
                    q = byId.get(at);
                    continue;
                }
                q = parent.get(q.optString("id"));
            }
            return false;
        }
    }

    private static JSONObject restKid(Tree t, JSONObject set) {
        if (set == null) return null;
        JSONObject pick = set.optJSONObject("pick");
        JSONObject k = pick == null ? null : t.byId.get(pick.optString("rest", ""));
        if (k == null || k.optJSONObject("limb") == null) {
            k = null;
            JSONArray kids = set.optJSONArray("kids");
            for (int i = 0; kids != null && i < kids.length(); i++) {
                JSONObject q = kids.optJSONObject(i);
                if (q != null && q.optJSONObject("limb") != null) {
                    k = q;
                    break;
                }
            }
        }
        return k;
    }

    private static double hyp(double x, double y) {
        return Math.sqrt(x * x + y * y);
    }

    private static double limbLen(JSONObject l) {
        return hyp(pt(l, "b", 0) - pt(l, "a", 0), pt(l, "b", 1) - pt(l, "a", 1)) + hyp(pt(l, "c", 0) - pt(l, "b", 0), pt(l, "c", 1) - pt(l, "b", 1));
    }

    private static double pt(JSONObject l, String which, int axis) {
        JSONObject p = l.optJSONObject(which);
        return p == null ? 0 : p.optDouble(axis == 0 ? "x" : "y", 0);
    }

    private static double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }

    private static String id(String prefix) {
        return prefix + Integer.toString(new Random().nextInt(Integer.MAX_VALUE), 36);
    }

    // ── stances ───────────────────────────────────────────────────────────────────────────────────

    public static JSONObject find(JSONObject doc, String id) {
        JSONArray ps = doc.optJSONArray("poses");
        for (int i = 0; ps != null && i < ps.length(); i++) {
            JSONObject p = ps.optJSONObject(i);
            if (p != null && id.equals(p.optString("id"))) return p;
        }
        return null;
    }

    public static JSONObject fours(JSONObject doc) {
        JSONObject w = settingsWalk(doc);
        return w != null && w.has("fours") ? find(doc, w.optString("fours")) : null;
    }

    private static JSONObject settingsWalk(JSONObject doc) {
        JSONObject s = doc.optJSONObject("settings");
        return s == null ? null : s.optJSONObject("walk");
    }

    /** A new, empty stance with its own button. */
    public static JSONObject add(JSONObject doc, String name) throws JSONException {
        JSONObject t = new JSONObject().put("id", id("t")).put("name", name);
        JSONObject p = new JSONObject().put("id", id("z")).put("name", name).put("trig", t.getString("id")).put("time", 0.6).put("d", new JSONObject());
        if (doc.optJSONArray("triggers") == null) doc.put("triggers", new JSONArray());
        if (doc.optJSONArray("poses") == null) doc.put("poses", new JSONArray());
        doc.getJSONArray("triggers").put(t);
        doc.getJSONArray("poses").put(p);
        return p;
    }

    /** A copy of a stance with a button of its own. */
    public static JSONObject copy(JSONObject doc, JSONObject from) throws JSONException {
        JSONObject p = add(doc, from.optString("name") + " copy");
        p.put("time", from.optDouble("time", 0.6));
        if (from.has("bounce")) p.put("bounce", from.optBoolean("bounce"));
        p.put("d", new JSONObject(from.optJSONObject("d") == null ? "{}" : from.optJSONObject("d").toString()));
        return p;
    }

    public static void remove(JSONObject doc, JSONObject p) throws JSONException {
        JSONArray ps = doc.optJSONArray("poses"), kept = new JSONArray();
        for (int i = 0; ps != null && i < ps.length(); i++) if (ps.optJSONObject(i) != p) kept.put(ps.get(i));
        doc.put("poses", kept);
        JSONArray ts = doc.optJSONArray("triggers"), keptT = new JSONArray();
        for (int i = 0; ts != null && i < ts.length(); i++) {
            JSONObject t = ts.optJSONObject(i);
            if (t == null || !t.optString("id").equals(p.optString("trig"))) keptT.put(ts.get(i));
        }
        doc.put("triggers", keptT);
        JSONObject w = settingsWalk(doc);
        if (w != null && p.optString("id").equals(w.optString("fours"))) w.remove("fours");
    }

    public static void rename(JSONObject doc, JSONObject p, String name) throws JSONException {
        p.put("name", name);
        JSONArray ts = doc.optJSONArray("triggers");
        for (int i = 0; ts != null && i < ts.length(); i++) {
            JSONObject t = ts.optJSONObject(i);
            if (t != null && t.optString("id").equals(p.optString("trig"))) t.put("name", name);
        }
    }

    /** Where a part's move, turn and size are kept for a stance: {x, y, rot, sc}. */
    public static JSONObject entry(JSONObject p, String nodeId) throws JSONException {
        JSONObject d = p.optJSONObject("d");
        if (d == null) {
            d = new JSONObject();
            p.put("d", d);
        }
        JSONObject e = d.optJSONObject(nodeId);
        if (e == null) {
            e = new JSONObject();
            d.put(nodeId, e);
        }
        return e;
    }

    /** How many parts of this stance were placed by hand on top of what was worked out. */
    public static int handMoves(JSONObject p) {
        int n = 0;
        JSONObject d = p == null ? null : p.optJSONObject("d");
        for (String id : Js.keys(d)) {
            JSONObject e = d.optJSONObject(id);
            if (e == null) continue;
            boolean moved;
            if (e.optBoolean("auto", false))
                moved = Math.abs(e.optDouble("x", 0) - e.optDouble("autoX", 0)) > 0.05 || Math.abs(e.optDouble("y", 0) - e.optDouble("autoY", 0)) > 0.05
                        || Math.abs(e.optDouble("rot", 0) - e.optDouble("autoRot", 0)) > 0.05 || (e.has("sc") && e.optDouble("sc", 1) != 1);
            else moved = e.optDouble("x", 0) != 0 || e.optDouble("y", 0) != 0 || e.optDouble("rot", 0) != 0 || (e.has("sc") && e.optDouble("sc", 1) != 1);
            if (moved) n++;
        }
        return n;
    }

    /** Every hand move in the stance forgotten: back to how it was worked out (what is in front of what is kept). */
    public static void clearHand(JSONObject p) throws JSONException {
        JSONObject d = p.optJSONObject("d");
        if (d == null) return;
        for (String id : Js.keys(d)) {
            JSONObject e = d.optJSONObject(id);
            if (e == null) continue;
            if (e.optBoolean("auto", false)) {
                e.put("x", e.optDouble("autoX", 0));
                e.put("y", e.optDouble("autoY", 0));
                e.put("rot", e.optDouble("autoRot", 0));
                e.remove("sc");
            } else {
                e.remove("x");
                e.remove("y");
                e.remove("rot");
                e.remove("sc");
                if (!e.has("z") && !e.has("orbit")) d.remove(id);
            }
        }
    }

    // ── all fours, worked out from the art ────────────────────────────────────────────────────────

    /** The parts all fours needs, or null: a torso, two legs that bend and at least one arm that bends, drawn from the side. */
    private static Object[] parts(Tree t, JSONObject doc, Bounds bounds) {
        JSONObject torso = t.firstRole("torso"), body = t.firstRole("body"), lL = t.firstRole("legL"), lR = t.firstRole("legR");
        JSONObject kl = restKid(t, lL), kr = restKid(t, lR);
        List<JSONObject> arms = new ArrayList<>();
        for (String r : new String[]{"armL", "armR"}) {
            JSONObject a = t.firstRole(r);
            if (a != null && "set".equals(a.optString("type")) && restKid(t, a) != null) arms.add(a);
        }
        if ((torso == null && body == null) || kl == null || kr == null || arms.isEmpty()) return null;
        JSONObject settings = doc.optJSONObject("settings");
        String fc = settings == null ? "" : settings.optString("facing", "");
        JSONObject head = t.firstRole("head");
        if (!fc.equals("left") && !fc.equals("right")) {
            double[] hb0 = head == null ? null : bounds.of(head), tb0 = bounds.of(torso != null ? torso : body);
            double hipX = (pt(kl.optJSONObject("limb"), "a", 0) + pt(kr.optJSONObject("limb"), "a", 0)) / 2;
            if (hb0 == null || tb0 == null || Math.abs((hb0[0] + hb0[2]) / 2 - hipX) < (tb0[2] - tb0[0]) * 0.18) return null;
        }
        return new Object[]{torso != null ? torso : body, lL, lR, arms, head, t.byRole("tail"), kl, kr};
    }

    /** Can this character go onto all fours? */
    public static boolean canFours(JSONObject doc, Bounds bounds) {
        JSONObject root = doc.optJSONObject("root");
        return root != null && parts(new Tree(root), doc, bounds) != null;
    }

    private static JSONObject o(double x, double y) throws JSONException {
        return new JSONObject().put("x", x).put("y", y);
    }

    /** Works the all-fours stance out from the art. {@code angle} (degrees of tip) is found when null. Returns the stance, or null. */
    @SuppressWarnings("unchecked")
    public static JSONObject makeFours(JSONObject doc, Double angle, Bounds bounds) throws JSONException {
        Tree t = new Tree(doc.getJSONObject("root"));
        Object[] P = parts(t, doc, bounds);
        if (P == null) return null;
        JSONObject torso = (JSONObject) P[0], lL = (JSONObject) P[1], lR = (JSONObject) P[2], head = (JSONObject) P[4], kl = ((JSONObject) P[6]).getJSONObject("limb"), kr = ((JSONObject) P[7]).getJSONObject("limb");
        List<JSONObject> arms = (List<JSONObject>) P[3], tails = (List<JSONObject>) P[5];
        JSONObject set = doc.getJSONObject("settings");
        JSONObject wk = set.optJSONObject("walk");
        if (wk == null) {
            wk = new JSONObject();
            set.put("walk", wk);
        }
        double hx = Math.round((pt(kl, "a", 0) + pt(kr, "a", 0)) / 2.0), hy = Math.round((pt(kl, "a", 1) + pt(kr, "a", 1)) / 2.0);
        double gy = wk.has("groundY") && wk.optDouble("groundY", 0) != 0 ? wk.optDouble("groundY") : Math.max(pt(kl, "c", 1), pt(kr, "c", 1));
        double sx = 0, sy = 0, len = 0;
        for (JSONObject a : arms) {
            JSONObject l = restKid(t, a).getJSONObject("limb");
            sx += pt(l, "a", 0) / arms.size();
            sy += pt(l, "a", 1) / arms.size();
            len += limbLen(l) / arms.size();
        }
        double[] hb = head == null ? null : bounds.of(head);
        String facing = set.optString("facing", "");
        final int dir = facing.equals("left") ? -1 : facing.equals("right") ? 1 : (hb != null && (hb[0] + hb[2]) / 2 < hx ? -1 : 1);
        double palm = len * 0.1, want = len * 0.9 + palm, A;
        if (angle == null) {
            A = 104;
            for (int tt = 40; tt <= 104; tt++) {
                double a = tt * Math.PI / 180 * dir, shoulderY = hy + Math.sin(a) * (sx - hx) + Math.cos(a) * (sy - hy);
                if (gy - shoulderY <= want) {
                    A = tt;
                    break;
                }
            }
        } else A = angle;
        double aRad = A * Math.PI / 180 * dir, shY = hy + Math.sin(aRad) * (sx - hx) + Math.cos(aRad) * (sy - hy);
        double drop = Math.max(0, Math.round(gy - shY - want));
        JSONObject p = fours(doc);
        if (p == null) p = add(doc, "All fours");
        JSONObject old = p.optJSONObject("d") == null ? new JSONObject() : p.getJSONObject("d");
        final double sgnA = A * dir;
        JSONObject d = new JSONObject();
        JSONObject fo = p.optJSONObject("fours");
        double was = fo != null && fo.has("angle") ? fo.optDouble("angle") * dir : sgnA;
        final double turn = (was - sgnA) * Math.PI / 180, ct = Math.cos(turn), sn = Math.sin(turn);
        for (String k : Js.keys(old)) {
            JSONObject e = old.optJSONObject(k);
            if (e != null && !e.optBoolean("auto", false)) d.put(k, e);
        }
        d.put(torso.getString("id"), keep(old, torso.getString("id"), new JSONObject().put("autoRot", sgnA).put("orbit", new JSONObject().put("x", hx).put("y", hy).put("deg", sgnA)), null, null, was, sgnA, ct, sn));
        List<JSONObject> back = new ArrayList<>();
        back.add(lL);
        back.add(lR);
        back.addAll(tails);
        for (JSONObject n : back) {
            if (n == null || !t.carried(n, torso)) continue;
            d.put(n.getString("id"), keep(old, n.getString("id"), new JSONObject().put("autoRot", -sgnA).put("orbit", new JSONObject().put("x", hx).put("y", hy).put("deg", -sgnA)), "pos", null, was, sgnA, ct, sn));
        }
        if (head != null) {
            if (t.carried(head, torso)) {
                if (head.optJSONObject("pivot") == null) {
                    double[] b = bounds.of(head);
                    if (b != null) head.put("pivot", o((b[0] + b[2]) / 2, b[3]));
                }
                d.put(head.getString("id"), keep(old, head.getString("id"), new JSONObject().put("autoRot", -sgnA), "pos", null, was, sgnA, ct, sn));
            } else {
                d.put(head.getString("id"), keep(old, head.getString("id"), new JSONObject().put("autoRot", 0).put("orbit", new JSONObject().put("x", hx).put("y", hy).put("deg", sgnA)), null, null, was, sgnA, ct, sn));
            }
        }
        double[] tb = bounds.of(torso);
        double tlen = tb != null ? Math.max(20, hy - tb[1]) : len, ca = Math.cos(aRad), sa = Math.sin(aRad);
        for (JSONObject a : arms) {
            JSONObject l = restKid(t, a).getJSONObject("limb");
            double alen = limbLen(l);
            JSONObject lk = Math.abs(pt(l, "a", 0) - pt(kl, "a", 0)) < Math.abs(pt(l, "a", 0) - pt(kr, "a", 0)) ? kl : kr;
            double far = Math.max(0, gy - pt(lk, "c", 1));
            boolean isFar = pt(lk, "c", 1) < Math.max(pt(kl, "c", 1), pt(kr, "c", 1)) - 1;
            JSONObject pick = a.optJSONObject("pick");
            JSONObject walk = pick == null ? null : pick.optJSONObject("walk");
            double apalm = walk != null && walk.has("palm") ? walk.optDouble("palm") : 0;
            double vx = pt(l, "a", 0) - hx, vy = pt(l, "a", 1) - hy, rx = hx + ca * vx - sa * vy, ry = hy + sa * vx + ca * vy;
            double tx = hx + dir * (tlen * 0.75 + (isFar ? Math.abs(pt(kl, "c", 0) - pt(kr, "c", 0)) * 0.45 : 0)), ty = gy + drop - far - apalm - alen * 0.9;
            double wx = tx - rx, wy = ty - ry;
            double sf = Math.max(0, Math.min(1, (A - 40) / 35));
            sf = sf * sf * (3 - 2 * sf);
            wx *= sf;
            wy *= sf;
            JSONObject base = new JSONObject().put("autoX", ca * wx + sa * wy).put("autoY", -sa * wx + ca * wy);
            boolean rides = t.carried(a, torso);
            if (!rides) {
                base.put("autoRot", sgnA).put("orbit", new JSONObject().put("x", hx).put("y", hy).put("deg", sgnA)).put("autoX", wx).put("autoY", wy);
            }
            JSONObject pv = a.optJSONObject("pivot");
            JSONObject arm = o(pt(l, "a", 0) - (pv == null ? 0 : pv.optDouble("x", 0)), pt(l, "a", 1) - (pv == null ? 0 : pv.optDouble("y", 0)));
            d.put(a.getString("id"), keep(old, a.getString("id"), base, rides ? "all" : null, arm, was, sgnA, ct, sn));
            if (pick != null && !pick.has("walk")) pick.put("walk", new JSONObject().put("lift", true));
        }
        p.put("d", d);
        p.put("fours", new JSONObject().put("hip", o(hx, hy)).put("angle", A).put("drop", drop).put("v", 2));
        if (!p.has("time") || p.optDouble("time", 0.6) == 0.6) p.put("time", 0.7);
        wk.put("fours", p.getString("id"));
        if (!wk.has("autoFours")) wk.put("autoFours", true);
        return p;
    }

    /** What was placed by hand stays where it was placed ON THE PICTURE (the pilot's {@code keep}); returns the part's entry. */
    private static JSONObject keep(JSONObject old, String id, JSONObject base, String ride, JSONObject arm, double was, double sgnA, double ct, double sn) throws JSONException {
        JSONObject o = old.optJSONObject(id);
        if (o == null) o = new JSONObject();
        if (!base.has("autoRot")) base.put("autoRot", 0);
        if (!base.has("autoX")) base.put("autoX", 0);
        if (!base.has("autoY")) base.put("autoY", 0);
        double hx = o.optDouble("x", 0) - o.optDouble("autoX", 0), hy = o.optDouble("y", 0) - o.optDouble("autoY", 0), hr = o.optDouble("rot", 0) - o.optDouble("autoRot", 0);
        if ("all".equals(ride) && Math.abs(hr) > 0.05) {
            double sc0 = o.has("sc") ? o.optDouble("sc", 1) : 1, vx = arm == null ? 0 : arm.optDouble("x"), vy = arm == null ? 0 : arm.optDouble("y");
            double r0 = hr * Math.PI / 180, hr1 = hr + was - sgnA, r1 = hr1 * Math.PI / 180;
            double dx0 = hx + sc0 * (Math.cos(r0) * vx - Math.sin(r0) * vy) - vx, dy0 = hy + sc0 * (Math.sin(r0) * vx + Math.cos(r0) * vy) - vy;
            double dx1 = ct * dx0 - sn * dy0, dy1 = sn * dx0 + ct * dy0;
            hx = dx1 - (sc0 * (Math.cos(r1) * vx - Math.sin(r1) * vy) - vx);
            hy = dy1 - (sc0 * (Math.sin(r1) * vx + Math.cos(r1) * vy) - vy);
            hr = hr1;
        } else if (ride != null && (hx != 0 || hy != 0)) {
            double qx = ct * hx - sn * hy;
            hy = sn * hx + ct * hy;
            hx = qx;
        }
        base.put("x", round1(base.optDouble("autoX") + hx));
        base.put("y", round1(base.optDouble("autoY") + hy));
        base.put("rot", round1(base.optDouble("autoRot") + hr));
        if (o.has("sc") && o.optDouble("sc", 1) != 1) base.put("sc", o.optDouble("sc"));
        if (o.has("z")) base.put("z", o.get("z"));
        base.put("auto", true);
        return base;
    }
}
