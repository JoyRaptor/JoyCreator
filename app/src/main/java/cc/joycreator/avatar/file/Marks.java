package cc.joycreator.avatar.file;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Editing a head's landmarks (the owner's one-numbered-set-per-piece model, AVA_FORMAT.md B2.1): a port of the
 * {@code AL.marks} functions of {@code tools/avatarlab/al-format.js}. A place is {@code [x, y]}, {@code [x, y, "behind"]}
 * or {@code [x, y, "off"]}. Every function edits the live JSON of a set node (so the room's edits stay in the file as the
 * pilot would write them); the room wraps each call in one named undo step.
 */
public final class Marks {
    private Marks() {}

    /** "Not here": takes no part. A missing place counts as not here. */
    public static boolean off(JSONArray q) {
        return q == null || "off".equals(Js.at(q, 2));
    }

    /** "here", "behind" or "off". */
    public static String stateOf(JSONArray q) {
        if (q == null) return "off";
        Object s = Js.at(q, 2);
        return "off".equals(s) || "behind".equals(s) ? (String) s : "here";
    }

    public static boolean has(JSONObject set) {
        JSONObject m = Js.obj(Js.get(set, "marks"));
        if (m == null) return false;
        for (String k : Js.keys(m)) {
            JSONObject mk = Js.obj(m.opt(k));
            JSONArray pts = mk == null ? null : Js.arr(Js.get(mk, "pts"));
            if (pts != null && pts.length() > 0) return true;
        }
        return false;
    }

    /** One piece's landmarks ("" = the whole drawing), made when {@code create}. */
    public static JSONObject piece(JSONObject set, String part, boolean create) throws JSONException {
        String pk = part == null ? "" : part;
        JSONObject all = Js.obj(Js.get(set, "marks"));
        if (all == null) {
            if (!create) return null;
            all = new JSONObject();
            set.put("marks", all);
        }
        JSONObject mk = Js.obj(all.opt(pk));
        if (mk == null && create) {
            mk = new JSONObject().put("pts", new JSONArray()).put("at", new JSONObject());
            all.put(pk, mk);
        }
        return mk;
    }

    /** A drawing's row; made on first use with every point "not here", parked where {@code from} has it (else at 0, 0). */
    public static JSONArray row(JSONObject mk, String id, String from) throws JSONException {
        JSONObject at = mk.getJSONObject("at");
        JSONArray have = Js.arr(at.opt(id));
        if (have != null) return have;
        JSONArray src = from == null ? null : Js.arr(at.opt(from));
        JSONArray pts = mk.getJSONArray("pts");
        JSONArray row = new JSONArray();
        for (int i = 0; i < pts.length(); i++) {
            JSONArray q = Js.arr(Js.at(src, i));
            row.put(new JSONArray().put(q == null ? 0 : q.optDouble(0)).put(q == null ? 0 : q.optDouble(1)).put("off"));
        }
        at.put(id, row);
        return row;
    }

    /**
     * A new landmark, placed where {@code places} says (drawing id to [x, y]); in every other drawing "not here", parked
     * at the first place. Returns its index.
     */
    public static int add(JSONObject mk, Map<String, double[]> places) throws JSONException {
        List<String> ids = new ArrayList<>(places.keySet());
        double[] first = places.get(ids.get(0));
        JSONObject at = mk.getJSONObject("at");
        for (String id : ids) {
            String from = null;
            for (String o : ids) if (!o.equals(id) && at.opt(o) != null) from = o;
            row(mk, id, from);
        }
        JSONArray pts = mk.getJSONArray("pts");
        pts.put(new JSONObject().put("n", pts.length() + 1));
        for (String id : Js.keys(at)) {
            double[] p = places.get(id);
            at.getJSONArray(id).put(p != null ? new JSONArray().put(p[0]).put(p[1]) : new JSONArray().put(first[0]).put(first[1]).put("off"));
        }
        return pts.length() - 1;
    }

    /** Takes landmark i out of every drawing; the rest are numbered again. An empty piece goes, and so do empty marks. */
    public static void remove(JSONObject set, String part, int i) throws JSONException {
        String pk = part == null ? "" : part;
        JSONObject mk = piece(set, pk, false);
        if (mk == null) return;
        JSONArray pts = mk.getJSONArray("pts");
        if (i < 0 || i >= pts.length()) return;
        pts.remove(i);
        JSONObject at = mk.getJSONObject("at");
        for (String id : Js.keys(at)) at.getJSONArray(id).remove(i);
        for (int k = 0; k < pts.length(); k++) pts.getJSONObject(k).put("n", k + 1);
        if (pts.length() == 0) clear(set, pk);
    }

    public static void clear(JSONObject set, String part) {
        JSONObject all = Js.obj(Js.get(set, "marks"));
        if (all == null) return;
        all.remove(part == null ? "" : part);
        if (all.length() == 0) set.remove("marks");
    }

    /** Here, behind or not here, for landmark i in one drawing. */
    public static void setState(JSONObject mk, String id, int i, String state) throws JSONException {
        JSONArray r = Js.arr(mk.getJSONObject("at").opt(id));
        JSONArray q = r == null ? null : Js.arr(Js.at(r, i));
        if (q == null) return;
        JSONArray n = new JSONArray().put(q.optDouble(0)).put(q.optDouble(1));
        if ("behind".equals(state) || "off".equals(state)) n.put(state);
        r.put(i, n);
    }

    public static void move(JSONObject mk, String id, int i, double x, double y) throws JSONException {
        JSONArray r = Js.arr(mk.getJSONObject("at").opt(id));
        JSONArray q = r == null ? null : Js.arr(Js.at(r, i));
        if (q == null) return;
        JSONArray n = new JSONArray().put(x).put(y);
        if (q.length() > 2) n.put(q.get(2));
        r.put(i, n);
    }

    /** Where drawings a and b meet on the way between them, as a share of the way from a to b (0.5 when never set). */
    public static double meet(JSONObject mk, String a, String b) {
        JSONArray ms = Js.arr(Js.get(mk, "meets"));
        for (int i = 0; ms != null && i < ms.length(); i++) {
            JSONObject m = Js.obj(Js.at(ms, i));
            if (m == null) continue;
            if (a.equals(m.optString("a")) && b.equals(m.optString("b"))) return m.optDouble("at", 0.5);
            if (a.equals(m.optString("b")) && b.equals(m.optString("a"))) return 1 - m.optDouble("at", 0.5);
        }
        return 0.5;
    }

    public static void setMeet(JSONObject mk, String a, String b, double v) throws JSONException {
        JSONArray old = Js.arr(Js.get(mk, "meets"));
        JSONArray ms = new JSONArray();
        for (int i = 0; old != null && i < old.length(); i++) {
            JSONObject m = Js.obj(Js.at(old, i));
            if (m == null) continue;
            boolean same = (a.equals(m.optString("a")) && b.equals(m.optString("b"))) || (a.equals(m.optString("b")) && b.equals(m.optString("a")));
            if (!same) ms.put(m);
        }
        if (Math.abs(v - 0.5) > 1e-6) ms.put(new JSONObject().put("a", a).put("b", b).put("at", v));
        if (ms.length() > 0) mk.put("meets", ms);
        else mk.remove("meets");
    }

    /** A copied drawing starts with every landmark exactly where its source has it (same states), in every piece. */
    public static void copyRows(JSONObject set, String from, String to) throws JSONException {
        JSONObject all = Js.obj(Js.get(set, "marks"));
        if (all == null) return;
        for (String k : Js.keys(all)) {
            JSONObject mk = Js.obj(all.opt(k));
            JSONObject at = mk == null ? null : Js.obj(Js.get(mk, "at"));
            JSONArray src = at == null ? null : Js.arr(at.opt(from));
            if (src == null) continue;
            JSONArray copy = new JSONArray();
            for (int i = 0; i < src.length(); i++) {
                JSONArray q = Js.arr(Js.at(src, i));
                JSONArray c = new JSONArray();
                for (int j = 0; q != null && j < q.length(); j++) c.put(q.get(j));
                copy.put(c);
            }
            at.put(to, copy);
        }
    }
}
