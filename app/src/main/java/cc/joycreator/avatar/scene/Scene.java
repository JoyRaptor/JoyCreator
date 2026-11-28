package cc.joycreator.avatar.scene;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * A scene document and the acts on it (a port of the pilot's {@code al-scene.js}, format {@code joycreator.scene/1},
 * tasks/avatar/AVA_FORMAT.md B3). It holds the document as plain JSON, so what it does not know travels untouched (format rule 2).
 * Every change goes through {@link #edit}: one step of the scene's own undo, whatever it touches ("one press, one undo").
 * Keys that start with an underscore are worked out while it is on the stage and are never kept or undone.
 */
public final class Scene {
    public static final String FORMAT = "joycreator.scene/1";
    public static final String[] SET_KEYS = {"meadow", "room", "night", "studio"};
    public static final String[] SET_NAMES = {"Meadow", "Room", "Night", "Studio"};
    /** The air's colour for each set, so far things take a little of it: haze. */
    public static final double[][] HAZE = {{0.80, 0.91, 0.97}, {0.90, 0.85, 0.76}, {0.10, 0.13, 0.27}, {0.16, 0.16, 0.19}};
    private static final double[] CAST_X = {0.5, 0.28, 0.72, 0.4, 0.6, 0.16, 0.84};

    public JSONObject doc;
    private final List<String[]> past = new ArrayList<>(), future = new ArrayList<>();   // {snapshot, label}
    private int ids;

    public Scene(JSONObject doc) {
        this.doc = doc;
        try {
            if (!doc.has("floor")) doc.put("floor", new JSONObject().put("near", 0.93).put("far", 0.62).put("shrink", 0.5));
            if (!doc.has("shots")) doc.put("shots", new JSONArray());
            if (!doc.has("cam")) doc.put("cam", new JSONArray());
            if (!doc.has("items")) doc.put("items", new JSONArray());
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A new scene, on one of the sets that come with the room, or none (the plain dark stage) when {@code set} is not one. */
    public static Scene blank(String uid, String name, String set) {
        try {
            JSONObject d = new JSONObject().put("format", FORMAT).put("uid", uid).put("name", name == null ? "New scene" : name)
                    .put("shape", "16:9").put("safe", "off").put("wind", 0.5).put("haze", 0.5)
                    .put("floor", new JSONObject().put("near", 0.93).put("far", 0.62).put("shrink", 0.5))
                    .put("items", new JSONArray()).put("shots", new JSONArray()).put("cam", new JSONArray());
            Scene s = new Scene(d);
            int i = setIndex(set);
            if (i >= 0) d.getJSONArray("items").put(new JSONObject().put("id", s.newId("k")).put("kind", "back").put("name", SET_NAMES[i]).put("made", SET_KEYS[i]).put("x", 0).put("y", 0).put("s", 1));
            return s;
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    public static int setIndex(String set) {
        for (int i = 0; i < SET_KEYS.length; i++) if (SET_KEYS[i].equals(set)) return i;
        return -1;
    }

    private boolean hasShot(String id) {
        JSONArray a = shots();
        for (int i = 0; a != null && i < a.length(); i++) if (id.equals(a.optJSONObject(i) == null ? null : a.optJSONObject(i).optString("id", null))) return true;
        return false;
    }

    /** A fresh id that no thing and no shot in this scene uses. */
    public String newId(String prefix) {
        String id;
        do id = prefix + Integer.toString(++ids, 36); while (item(id) != null || hasShot(id));
        return id;
    }

    // ── reading ──
    public String uid() {
        return doc.optString("uid", "");
    }

    public String name() {
        return doc.optString("name", "");
    }

    public String shape() {
        return doc.optString("shape", "16:9");
    }

    public String safe() {
        return doc.optString("safe", "off");
    }

    public JSONArray items() {
        return doc.optJSONArray("items");
    }

    public JSONArray shots() {
        return doc.optJSONArray("shots");
    }

    public JSONArray cam() {
        return doc.optJSONArray("cam");
    }

    public JSONObject floor() {
        return doc.optJSONObject("floor");
    }

    public double near() {
        return floor().optDouble("near", 0.93);
    }

    public double far() {
        return floor().optDouble("far", 0.62);
    }

    public double shrink() {
        return floor().optDouble("shrink", 0.5);
    }

    public double[] frame() {
        return SceneGeometry.frame(shape());
    }

    public JSONObject item(String id) {
        JSONArray a = items();
        for (int i = 0; a != null && i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o != null && id != null && id.equals(o.optString("id", null))) return o;
        }
        return null;
    }

    public List<JSONObject> cast() {
        List<JSONObject> l = new ArrayList<>();
        JSONArray a = items();
        for (int i = 0; a != null && i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o != null && "cast".equals(o.optString("kind"))) l.add(o);
        }
        return l;
    }

    /** How big a thing is drawn where it stands now. */
    public double depth(double y) {
        return SceneGeometry.depth(near(), far(), shrink(), y);
    }

    /** The y a thing stands on for "how far back" 0 (the front of the floor) to 100 (the back); the pilot's slider and tool. */
    public double yForBack(double back) {
        return Math.round(SceneGeometry.H * (near() - back / 100 * (near() - far())));
    }

    public double backOf(double y) {
        double span = near() - far();
        return span == 0 ? 0 : (near() - y / SceneGeometry.H) / span * 100;
    }

    // ── undo: one step per edit ──
    /** The document as text without what is only worked out while it is on the stage (names that begin with an underscore). */
    public String text() {
        return strip(doc).toString();
    }

    private static Object strip(Object v) {
        try {
            if (v instanceof JSONObject) {
                JSONObject o = (JSONObject) v, c = new JSONObject();
                for (Iterator<String> it = o.keys(); it.hasNext(); ) {
                    String k = it.next();
                    if (k.startsWith("_")) continue;
                    c.put(k, strip(o.opt(k)));
                }
                return c;
            }
            if (v instanceof JSONArray) {
                JSONArray a = (JSONArray) v, c = new JSONArray();
                for (int i = 0; i < a.length(); i++) c.put(strip(a.opt(i)));
                return c;
            }
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
        return v;
    }

    public interface Change {
        void apply(JSONObject doc) throws JSONException;
    }

    /** Change the scene as one undoable step. */
    public void edit(String label, Change fn) {
        snap(label == null ? "Change" : label);
        try {
            fn.apply(doc);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A snapshot before a drag (the finger changes the numbers as it goes); the drag is then one step. */
    public void snap(String label) {
        past.add(new String[]{text(), label});
        if (past.size() > 60) past.remove(0);
        future.clear();
    }

    public String undoLabel() {
        return past.isEmpty() ? null : past.get(past.size() - 1)[1];
    }

    public String redoLabel() {
        return future.isEmpty() ? null : future.get(future.size() - 1)[1];
    }

    public int undoDepth() {
        return past.size();
    }

    /** Undo (or redo); returns the step's label, null when there is nothing. The scene keeps its id. */
    public String undo(boolean redo) {
        List<String[]> from = redo ? future : past, to = redo ? past : future;
        if (from.isEmpty()) return null;
        String[] e = from.remove(from.size() - 1);
        to.add(new String[]{text(), e[1]});
        try {
            String keep = uid();
            doc = new JSONObject(e[0]);
            doc.put("uid", keep);
        } catch (JSONException x) {
            throw new IllegalStateException(x);
        }
        return e[1];
    }

    // ── the acts: each is one plain method; the buttons call them and so does Joybot ──
    public static String kindName(String kind) {
        switch (kind) {
            case "back": return "Backdrop";
            case "obj": return "Object";
            case "front": return "Foreground";
            case "cast": return "Character";
            default: return kind;
        }
    }

    /**
     * Pictures into the scene: kind back, obj or front; several pictures as one object are a loop. {@code spread} (0 to 1) says where
     * along the frame an object is put; {@code firstH} is the first picture's height. Returns the new thing.
     */
    public JSONObject addPictures(String kind, List<String> imgIds, int firstH, String name, double spread) {
        if (imgIds.isEmpty()) return null;
        JSONObject[] out = new JSONObject[1];
        edit("Add " + kindName(kind).toLowerCase(), d -> {
            JSONObject it = new JSONObject().put("id", newId("k")).put("kind", kind).put("name", name == null ? kindName(kind) : name).put("img", imgIds.get(0)).put("x", 0).put("y", 0).put("s", 1);
            if (imgIds.size() > 1) it.put("frames", new JSONArray(imgIds)).put("fps", 8);
            if ("obj".equals(kind)) {
                double[] fr = frame();
                it.put("x", Math.round(fr[0] + fr[2] * (0.2 + 0.6 * spread))).put("y", Math.round(SceneGeometry.H * 0.86))
                        .put("s", Math.round(SceneGeometry.clamp(0.34 * SceneGeometry.H / Math.max(1, firstH), 0.05, 4) * 100) / 100.0);
            }
            JSONArray a = d.getJSONArray("items");
            if ("back".equals(kind)) {                       // a backdrop goes after the last backdrop (behind everything else)
                int at = 0;
                for (int i = 0; i < a.length(); i++) if ("back".equals(a.getJSONObject(i).optString("kind"))) at = i + 1;
                JSONArray n = new JSONArray();
                for (int i = 0; i < a.length(); i++) {
                    if (i == at) n.put(it);
                    n.put(a.get(i));
                }
                if (at >= a.length()) n.put(it);
                d.put("items", n);
            } else a.put(it);
            out[0] = it;
        });
        return out[0];
    }

    /** A character onto the stage: from the shelf ({@code uid}) or one that comes with the room ({@code stock}). */
    public JSONObject addCast(String name, String uid, String stock) {
        JSONObject[] out = new JSONObject[1];
        int n = cast().size();
        edit("Add a character", d -> {
            double[] fr = frame();
            JSONObject it = new JSONObject().put("id", newId("k")).put("kind", "cast").put("name", name == null ? "Character" : name)
                    .put("x", Math.round(fr[0] + fr[2] * CAST_X[n % 7])).put("y", Math.round(SceneGeometry.H * (0.9 - 0.02 * (n % 3)))).put("s", 1);
            if (uid != null) it.put("uid", uid);
            else it.put("stock", stock == null ? "topps" : stock);
            d.getJSONArray("items").put(it);
            out[0] = it;
        });
        return out[0];
    }

    /** Take a thing out. Its camera cut to a close-up goes too; the caller puts the camera back on Wide if it was there. */
    public boolean remove(String id) {
        JSONObject it = item(id);
        if (it == null) return false;
        edit("Remove " + it.optString("name"), d -> {
            JSONArray a = d.getJSONArray("items"), n = new JSONArray(), cams = d.getJSONArray("cam"), nc = new JSONArray();
            for (int i = 0; i < a.length(); i++) if (!id.equals(a.getJSONObject(i).optString("id"))) n.put(a.get(i));
            for (int i = 0; i < cams.length(); i++) if (!("close:" + id).equals(cams.getJSONObject(i).optString("shot"))) nc.put(cams.get(i));
            d.put("items", n).put("cam", nc);
        });
        return true;
    }

    /** One more of this, beside it. Returns the copy. */
    public JSONObject copy(String id) {
        JSONObject it = item(id);
        if (it == null) return null;
        JSONObject[] out = new JSONObject[1];
        edit("Another " + it.optString("name"), d -> {
            JSONObject n = (JSONObject) strip(it);
            n.put("id", newId("k")).put("x", Math.round(it.optDouble("x", 0) + 90));
            JSONArray a = d.getJSONArray("items"), r = new JSONArray();
            for (int i = 0; i < a.length(); i++) {
                r.put(a.get(i));
                if (a.get(i) == it) r.put(n);
            }
            d.put("items", r);
            out[0] = n;
        });
        return out[0];
    }

    private static boolean settable(String k) {
        return k.equals("x") || k.equals("y") || k.equals("s") || k.equals("flip") || k.equals("sway") || k.equals("fps") || k.equals("name") || k.equals("par");
    }

    /** Change what a thing is: any of x, y, s, flip, sway, fps, name, par. One undo step. */
    public boolean set(String id, Map<String, Object> what, String label) {
        JSONObject it = item(id);
        if (it == null) return false;
        edit(label == null ? "Change " + it.optString("name") : label, d -> {
            for (Map.Entry<String, Object> e : what.entrySet()) if (settable(e.getKey())) it.put(e.getKey(), e.getValue());
        });
        return true;
    }

    public void shape(String shape) {
        edit("Frame " + shape, d -> d.put("shape", shape));
    }

    public void margins(String safe) {
        edit("Margins", d -> d.put("safe", safe));
    }

    public void rename(String name) {
        String n = name == null ? "" : name.trim();
        if (n.isEmpty()) return;
        edit("Rename the scene", d -> d.put("name", n));
    }

    /** The set behind everything. {@code imgId} is the new picture's id (the room paints the set into it). */
    public void setSet(String key, String imgId) {
        int i = setIndex(key);
        if (i < 0) return;
        edit("Set: " + SET_NAMES[i], d -> {
            JSONArray a = d.getJSONArray("items");
            JSONObject it = null;
            for (int j = 0; j < a.length(); j++) if (a.getJSONObject(j).has("made")) {
                it = a.getJSONObject(j);
                break;
            }
            if (it == null) {
                it = new JSONObject().put("id", newId("k")).put("kind", "back").put("x", 0).put("y", 0).put("s", 1);
                JSONArray n = new JSONArray().put(it);
                for (int j = 0; j < a.length(); j++) n.put(a.get(j));
                d.put("items", n);
            }
            it.put("made", SET_KEYS[i]).put("name", SET_NAMES[i]).put("img", imgId);
        });
    }

    /** No set: the plain dark stage behind. */
    public void clearSet() {
        edit("No set", d -> {
            JSONArray a = d.getJSONArray("items"), n = new JSONArray();
            for (int j = 0; j < a.length(); j++) if (!a.getJSONObject(j).has("made")) n.put(a.get(j));
            d.put("items", n);
        });
    }

    public JSONObject made() {
        JSONArray a = items();
        for (int i = 0; i < a.length(); i++) if (a.optJSONObject(i) != null && a.optJSONObject(i).has("made")) return a.optJSONObject(i);
        return null;
    }

    /** Keep a view as a shot of this scene (x, y, z in stage units). Returns the shot. */
    public JSONObject keepShot(String name, double x, double y, double z) {
        JSONObject[] out = new JSONObject[1];
        edit("Keep this shot", d -> {
            JSONArray a = d.getJSONArray("shots");
            out[0] = new JSONObject().put("id", newId("h")).put("name", name == null ? "Shot " + (a.length() + 1) : name)
                    .put("x", Math.round(x)).put("y", Math.round(y)).put("z", Math.round(z * 100) / 100.0);
            a.put(out[0]);
        });
        return out[0];
    }

    public void dropShot(String id) {
        edit("Remove a shot", d -> {
            JSONArray a = d.getJSONArray("shots"), n = new JSONArray(), cams = d.getJSONArray("cam"), nc = new JSONArray();
            for (int i = 0; i < a.length(); i++) if (!id.equals(a.getJSONObject(i).optString("id"))) n.put(a.get(i));
            for (int i = 0; i < cams.length(); i++) if (!id.equals(cams.getJSONObject(i).optString("shot"))) nc.put(cams.get(i));
            d.put("shots", n).put("cam", nc);
        });
    }

    public void clearCam() {
        edit("Clear the camera lane", d -> d.put("cam", new JSONArray()));
    }
}
