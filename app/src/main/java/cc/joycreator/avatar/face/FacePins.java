package cc.joycreator.avatar.face;

import com.fadcam.ui.faditor.avatar.face.FacePoints;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Face pins (FACE-1): a point of your face pulls a pin of the character. The model half of the pilot's {@code al-facepins.js}
 * ({@code bind}, {@code unbind}, {@code strength}, the All pins dial, one piece), line for line, so a character made in either
 * reads the same: {@code ch.facePins = [{ id, point, node, pin, amt, made }]}, and on the node two Shapes that carry the point's
 * movement to the pin ({@code fp:<id>:x} following {@code pt.<point>.x}, {@code fp:<id>:y} following {@code pt.<point>.y}).
 * Pure: the room calls these inside one undo step each.
 */
public final class FacePins {
    private FacePins() {}

    /** A new pin starts at the face's own movement (the owner, round 5: the All pins dial scales them all). */
    public static final double START = 1;

    public static JSONArray list(JSONObject ch) {
        JSONArray a = ch.optJSONArray("facePins");
        return a == null ? new JSONArray() : a;
    }

    /** The face points the character's pins follow, each once. */
    public static int[] points(JSONObject ch) {
        JSONArray l = list(ch);
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < l.length(); i++) {
            int p = l.optJSONObject(i).optInt("point");
            if (!out.contains(p)) out.add(p);
        }
        int[] a = new int[out.size()];
        for (int i = 0; i < a.length; i++) a[i] = out.get(i);
        return a;
    }

    /** A point's name in the owner's words ("your right mouth corner", "face point 212"). */
    public static String nameOf(int pt) {
        for (Object[] r : FacePoints.NAMED) {
            if ((Integer) r[0] != pt) continue;
            int side = (Integer) r[1];
            return (side > 0 ? "your right " : side < 0 ? "your left " : "your ") + r[2];
        }
        return "face point " + pt;
    }

    /** The All pins dial ({@code settings.facePinGain}; 1 = as set). */
    public static double gain(JSONObject ch) {
        JSONObject s = ch.optJSONObject("settings");
        return s != null && s.has("facePinGain") && !s.isNull("facePinGain") ? s.optDouble("facePinGain", 1) : 1;
    }

    public static void setGain(JSONObject ch, JSONObject root, double g) throws JSONException {
        JSONObject s = ch.optJSONObject("settings");
        if (s == null) { s = new JSONObject(); ch.put("settings", s); }
        s.put("facePinGain", g);
        JSONArray l = list(ch);
        for (int i = 0; i < l.length(); i++) {
            JSONObject q = l.optJSONObject(i), n = node(root, q.optString("node"));
            JSONArray sh = n == null ? null : n.optJSONArray("shapes");
            for (int j = 0; sh != null && j < sh.length(); j++) {
                JSONObject x = sh.optJSONObject(j);
                if (x != null && q.optString("id").equals(x.optString("face"))) x.put("amt", (q.has("amt") ? q.optDouble("amt", 1) : 1) * g);
            }
        }
    }

    /**
     * ONE PIECE (the owner, round 5): a face pin tapped on any slice of a head goes on that head's whole drawing (the angle of a
     * turning head, or the head itself), whose pins bend it last as one sheet. Off ({@code settings.faceSheet} false): the slice.
     */
    public static JSONObject sheetOf(JSONObject ch, JSONObject n) {
        JSONObject s = ch.optJSONObject("settings");
        if (n == null || (s != null && s.has("faceSheet") && !s.optBoolean("faceSheet", true))) return n;
        JSONObject root = ch.optJSONObject("root");
        List<JSONObject> path = pathTo(root, n.optString("id"), new ArrayList<>());
        if (path == null) return n;
        for (int i = path.size() - 1; i >= 1; i--) {                    // the nearest angle of a turning set, the node itself included
            JSONObject p = path.get(i - 1), pk = p.optJSONObject("pick");
            if ("set".equals(p.optString("type")) && pk != null && "turn".equals(pk.optString("by"))) return path.get(i);
        }
        for (int i = path.size() - 1; i >= 0; i--) {
            JSONObject q = path.get(i);
            if ("head".equals(q.optString("role"))) return "art".equals(q.optString("type")) ? n : q;
        }
        return n;
    }

    /** A drawing made of slices bends as one piece, last (its mesh made if it has none). */
    public static void sheetMesh(JSONObject n) throws JSONException {
        JSONObject mesh = n.optJSONObject("mesh");
        if (mesh == null) { mesh = new JSONObject().put("pins", new JSONArray()).put("quiet", true); n.put("mesh", mesh); }
        if (mesh.optJSONArray("pins") == null) mesh.put("pins", new JSONArray());
        if (!"art".equals(n.optString("type")) && mesh.getJSONArray("pins").length() == 0) mesh.put("last", true);
    }

    /**
     * Binds face point {@code pt} to pin {@code pinId} of node {@code n}: its two Shapes and the record. {@code k}: face-size units
     * to the character's own (the pilot's: half the head's width, at least 20). Returns the new face pin's id.
     */
    public static String bind(JSONObject ch, JSONObject n, String pinId, int pt, boolean made, double k) throws JSONException {
        String id = "fp" + Long.toString((long) (Math.random() * 60466176L), 36), name = nameOf(pt);
        double amt = START * gain(ch);
        JSONArray sh = n.optJSONArray("shapes");
        if (sh == null) { sh = new JSONArray(); n.put("shapes", sh); }
        sh.put(new JSONObject().put("id", "fp:" + id + ":x").put("name", "Face pin: " + name + " (across)").put("sig", "pt." + pt + ".x").put("amt", amt)
                .put("d", new JSONObject().put(pinId, new JSONArray().put(k).put(0))).put("face", id));
        sh.put(new JSONObject().put("id", "fp:" + id + ":y").put("name", "Face pin: " + name + " (up and down)").put("sig", "pt." + pt + ".y").put("amt", amt)
                .put("d", new JSONObject().put(pinId, new JSONArray().put(0).put(k))).put("face", id));
        JSONArray l = ch.optJSONArray("facePins");
        if (l == null) { l = new JSONArray(); ch.put("facePins", l); }
        l.put(new JSONObject().put("id", id).put("point", pt).put("node", n.optString("id")).put("pin", pinId).put("amt", START).put("made", made));
        return id;
    }

    /** The pin's strength (both its Shapes, times the All pins dial). */
    public static void strength(JSONObject ch, String id, double v) throws JSONException {
        JSONObject q = find(ch, id), n = q == null ? null : node(ch.optJSONObject("root"), q.optString("node"));
        if (q == null || n == null) return;
        q.put("amt", v);
        JSONArray sh = n.optJSONArray("shapes");
        for (int j = 0; sh != null && j < sh.length(); j++) {
            JSONObject x = sh.optJSONObject(j);
            if (x != null && id.equals(x.optString("face"))) x.put("amt", v * gain(ch));
        }
    }

    /** Takes a face pin off, and its pin too when it was made for it and nothing else uses it. */
    public static void unbind(JSONObject ch, String id) throws JSONException {
        JSONObject q = find(ch, id);
        if (q == null) return;
        JSONObject n = node(ch.optJSONObject("root"), q.optString("node"));
        JSONArray l = list(ch), keep = new JSONArray();
        for (int i = 0; i < l.length(); i++) if (!id.equals(l.optJSONObject(i).optString("id"))) keep.put(l.optJSONObject(i));
        if (keep.length() == 0) ch.remove("facePins");
        else ch.put("facePins", keep);
        if (n == null) return;
        JSONArray sh = n.optJSONArray("shapes"), left = new JSONArray();
        for (int j = 0; sh != null && j < sh.length(); j++) if (!id.equals(sh.optJSONObject(j).optString("face"))) left.put(sh.optJSONObject(j));
        if (sh != null) n.put("shapes", left);
        String pin = q.optString("pin");
        boolean used = false;
        for (int j = 0; j < left.length(); j++) {
            JSONObject d = left.optJSONObject(j).optJSONObject("d");
            JSONArray v = d == null ? null : d.optJSONArray(pin);
            if (v != null && (v.optDouble(0) != 0 || v.optDouble(1) != 0)) used = true;
        }
        for (int i = 0; i < keep.length(); i++) if (pin.equals(keep.optJSONObject(i).optString("pin"))) used = true;
        JSONObject mesh = n.optJSONObject("mesh");
        if (q.optBoolean("made") && !used && mesh != null && mesh.optJSONArray("pins") != null) {
            JSONArray pins = mesh.getJSONArray("pins"), pk = new JSONArray();
            for (int i = 0; i < pins.length(); i++) if (!pin.equals(pins.optJSONObject(i).optString("id"))) pk.put(pins.optJSONObject(i));
            mesh.put("pins", pk);
            for (int j = 0; j < left.length(); j++) {
                JSONObject d = left.optJSONObject(j).optJSONObject("d");
                if (d != null) d.remove(pin);
            }
        }
    }

    /** How many face pins still bend a single slice of a head (made before one piece). */
    public static int onSlices(JSONObject ch) {
        JSONArray l = list(ch);
        int c = 0;
        for (int i = 0; i < l.length(); i++) {
            JSONObject n = node(ch.optJSONObject("root"), l.optJSONObject(i).optString("node"));
            if (n != null && sheetOf(ch, n) != n) c++;
        }
        return c;
    }

    static JSONObject find(JSONObject ch, String id) {
        JSONArray l = list(ch);
        for (int i = 0; i < l.length(); i++) if (id.equals(l.optJSONObject(i).optString("id"))) return l.optJSONObject(i);
        return null;
    }

    /** The node with this id under n (depth first), or null. */
    public static JSONObject node(JSONObject n, String id) {
        if (n == null) return null;
        if (id.equals(n.optString("id"))) return n;
        JSONArray k = n.optJSONArray("kids");
        for (int i = 0; k != null && i < k.length(); i++) {
            JSONObject r = node(k.optJSONObject(i), id);
            if (r != null) return r;
        }
        return null;
    }

    /** The nodes from the root down to the node with this id (both included), or null. */
    static List<JSONObject> pathTo(JSONObject n, String id, List<JSONObject> path) {
        if (n == null) return null;
        path.add(n);
        if (id.equals(n.optString("id"))) return path;
        JSONArray k = n.optJSONArray("kids");
        for (int i = 0; k != null && i < k.length(); i++) {
            List<JSONObject> r = pathTo(k.optJSONObject(i), id, path);
            if (r != null) return r;
        }
        path.remove(path.size() - 1);
        return null;
    }
}
