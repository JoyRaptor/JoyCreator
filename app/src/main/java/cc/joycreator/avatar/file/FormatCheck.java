package cc.joycreator.avatar.file;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Every rule of the shared character and scene format (tasks/avatar/AVA_FORMAT.md, B5). A port of {@code
 * AL.format.check} in {@code tools/avatarlab/al-format.js}: the same files must pass and the same planted faults must
 * fail (tasks/avatar/format-tests, run by {@code tools/jvm-harness/run-avatar-format.sh}). Each problem has a short code,
 * where it is, and one plain sentence; the user is only ever shown a sentence, never the code.
 */
public final class FormatCheck {

    /** One broken rule. */
    public static final class Problem {
        public final String code, where, say;

        Problem(String code, String where, String say) {
            this.code = code;
            this.where = where;
            this.say = say;
        }

        @Override
        public String toString() {
            return code + " @ " + where + ": " + say;
        }
    }

    private static final List<String> TYPES = Arrays.asList("group", "set", "art", "mirror");
    private static final List<String> PICKS = Arrays.asList("turn", "reach", "viseme", "default", "cycle", "dir", "trig");
    private static final List<String> STATES = Arrays.asList("behind", "off");
    private static final List<String> KINDS = Arrays.asList("back", "obj", "front", "cast");
    private static final List<String> SHAPES = Arrays.asList("16:9", "4:3", "1:1", "4:5", "9:16");
    private static final Pattern PIC_INSIDE = Pattern.compile("^data:image/(png|webp|jpeg);base64,.*", Pattern.DOTALL);
    private static final Pattern PIC_BESIDE = Pattern.compile("^[^/\\\\:]+\\.pictures/[^/\\\\:]+\\.(png|webp|jpg)$");
    private static final Pattern WAV_INSIDE = Pattern.compile("^data:audio/wav;base64,.*", Pattern.DOTALL);
    private static final Pattern WAV_BESIDE = Pattern.compile(".*\\.wav$", Pattern.DOTALL);

    private FormatCheck() {}

    /** The problems of a character or scene document; empty means it follows every rule. */
    public static List<Problem> check(JSONObject doc) {
        List<Problem> bad = new ArrayList<>();
        if (doc == null) {
            bad.add(new Problem("not-json", "", "That is not a file this reads."));
            return bad;
        }
        String format = doc.optString("format", null);
        if (CharacterFile.SCENE.equals(format)) return checkScene(doc);
        if (!CharacterFile.CHARACTER.equals(format) && !CharacterFile.CHARACTER_OLD.equals(format))
            bad.add(new Problem("format", "format", "Unknown format: " + format));
        if (!(Js.isNum(Js.get(doc, "w")) && Js.dbl(Js.get(doc, "w")) > 0) || !(Js.isNum(Js.get(doc, "h")) && Js.dbl(Js.get(doc, "h")) > 0))
            bad.add(new Problem("size", "w,h", "The character has no size."));
        JSONObject root = Js.obj(Js.get(doc, "root"));
        if (root == null) {
            bad.add(new Problem("root", "root", "The character has no layers."));
            return bad;
        }
        final Map<String, JSONObject> ids = new LinkedHashMap<>();
        final int[] count = {0};
        walkTree(root, 0, ids, count, bad);

        JSONObject pics = Js.obj(Js.get(doc, "pictures"));
        Map<String, Boolean> trig = new LinkedHashMap<>();
        JSONArray triggers = Js.arr(Js.get(doc, "triggers"));
        for (int i = 0; triggers != null && i < triggers.length(); i++) {
            JSONObject t = Js.obj(Js.at(triggers, i));
            if (t == null || !Js.truthy(Js.get(t, "id"))) bad.add(new Problem("trigger", "?", "A button has no id."));
            else trig.put(t.optString("id"), true);
        }
        for (Map.Entry<String, JSONObject> en : ids.entrySet()) {
            String id = en.getKey();
            JSONObject n = en.getValue();
            String type = n.optString("type", null);
            if ("art".equals(type)) {
                if (!Js.isNum(Js.get(n, "x")) || !Js.isNum(Js.get(n, "y"))) bad.add(new Problem("art-xy", id, "Drawing " + id + " has no place."));
                String img = Js.str(Js.get(n, "img"));
                if (img == null || img.isEmpty()) bad.add(new Problem("art-img", id, "Drawing " + id + " has no picture."));
                else if (pics != null && !Js.truthy(Js.get(pics, img))) bad.add(new Problem("pic-missing", id, "Drawing " + id + "’s picture " + img + " is not in the file."));
            }
            if ("mirror".equals(type)) ref(ids, Js.get(n, "of"), id, "A mirrored angle", bad);
            if ("set".equals(type)) {
                JSONObject pk = Js.obj(Js.get(n, "pick"));
                if (pk == null) pk = new JSONObject();
                String by = Js.str(Js.get(pk, "by"));
                if (by != null && !by.isEmpty() && !PICKS.contains(by)) bad.add(new Problem("pick", id, "Part " + id + " swaps by an unknown rule: " + by));
                ref(ids, Js.get(pk, "rest"), id, "The resting drawing of " + id, bad);
                JSONArray kids = Js.arr(Js.get(n, "kids"));
                if ("turn".equals(by))
                    for (int i = 0; kids != null && i < kids.length(); i++) {
                        JSONObject k = Js.obj(Js.at(kids, i));
                        if (k == null) continue;
                        if (!TYPES.contains(k.optString("type", ""))) continue;
                        if (!Js.isXy(Js.get(k, "at"))) bad.add(new Problem("angle-at", k.optString("id"), "Head angle " + k.optString("id") + " has no dot on the pad."));
                    }
                if (Js.get(n, "bridges") != null && CharacterFile.CHARACTER.equals(format))
                    bad.add(new Problem("pairs-in-v2", id, "Part " + id + " still has pair-by-pair landmarks in a /2 file."));
                if (Js.get(n, "marks") != null) checkMarks(n, bad);
            }
            JSONArray clips = Js.arr(Js.get(n, "clips"));
            for (int i = 0; clips != null && i < clips.length(); i++) {
                JSONObject c = Js.obj(Js.at(clips, i));
                ref(ids, c == null ? null : Js.get(c, "by"), id, "A mask on " + id, bad);
            }
            JSONObject ride = Js.obj(Js.get(n, "ride"));
            if (ride != null) ref(ids, Js.get(ride, "of"), id, "What carries " + id, bad);
            Object nt = Js.get(n, "trig");
            if (Js.truthy(nt) && !trig.containsKey(String.valueOf(nt))) bad.add(new Problem("trig-ref", id, "Drawing " + id + "’s button " + nt + " is not in the file."));
        }
        JSONArray poses = Js.arr(Js.get(doc, "poses"));
        for (int i = 0; poses != null && i < poses.length(); i++) {
            JSONObject p = Js.obj(Js.at(poses, i));
            if (p == null || !Js.truthy(Js.get(p, "id")) || !Js.truthy(Js.get(p, "name"))) {
                bad.add(new Problem("pose", "poses[" + i + "]", "A stance has no name."));
                continue;
            }
            Object pt = Js.get(p, "trig");
            if (Js.truthy(pt) && !trig.containsKey(String.valueOf(pt))) bad.add(new Problem("trig-ref", p.optString("id"), "Stance " + p.optString("name") + "’s button is not in the file."));
            JSONObject d = Js.obj(Js.get(p, "d"));
            for (String k : Js.keys(d)) ref(ids, k, p.optString("id"), "Stance " + p.optString("name"), bad);
        }
        JSONArray pals = Js.arr(Js.get(doc, "palettes"));
        for (int i = 0; pals != null && i < pals.length(); i++) {
            JSONObject p = Js.obj(Js.at(pals, i));
            if (p == null || !Js.truthy(Js.get(p, "id")) || Js.arr(Js.get(p, "stops")) == null) {
                bad.add(new Problem("palette", "palettes[" + i + "]", "A colour set is damaged."));
                continue;
            }
            Object pt = Js.get(p, "trig");
            if (Js.truthy(pt) && !trig.containsKey(String.valueOf(pt))) bad.add(new Problem("trig-ref", p.optString("id"), "Colour set " + p.optString("name") + "’s button is not in the file."));
            JSONArray stops = Js.arr(Js.get(p, "stops"));
            for (int s = 0; s < stops.length(); s++) {
                JSONObject st = Js.obj(Js.at(stops, s));
                if (st == null || !Js.isNum(Js.get(st, "from")) || !Js.isNum(Js.get(st, "to")))
                    bad.add(new Problem("palette", p.optString("id"), "A colour in " + p.optString("name") + " has no hue."));
            }
        }
        if (Js.get(doc, "take") != null) checkTake(Js.get(doc, "take"), "take", bad);
        if (pics != null)
            for (String k : Js.keys(pics)) {
                String v = Js.str(pics.opt(k));
                if (v == null || !(PIC_INSIDE.matcher(v).matches() || PIC_BESIDE.matcher(v).matches()))
                    bad.add(new Problem("pic-form", k, "Picture " + k + " is neither inside the file nor beside it."));
            }
        return bad;
    }

    private static void walkTree(JSONObject n, int d, Map<String, JSONObject> ids, int[] count, List<Problem> bad) {
        count[0]++;
        String nid = Js.str(Js.get(n, "id"));
        if (d > 40 || count[0] > 20000) {
            bad.add(new Problem("tangle", nid, "The layers are tangled."));
            return;
        }
        if (nid == null || nid.isEmpty()) bad.add(new Problem("id", "?", "A layer has no id."));
        else if (ids.containsKey(nid)) bad.add(new Problem("dup-id", nid, "Two layers share the id " + nid + "."));
        else ids.put(nid, n);
        String type = Js.str(Js.get(n, "type"));
        if (type == null || !TYPES.contains(type)) bad.add(new Problem("type", nid, "Layer " + nid + " has an unknown kind: " + type));
        Object kidsV = Js.get(n, "kids");
        if (kidsV != null && !(kidsV instanceof JSONArray)) bad.add(new Problem("kids", nid, "Layer " + nid + " is damaged."));
        if (kidsV instanceof JSONArray) {
            JSONArray kids = (JSONArray) kidsV;
            for (int i = 0; i < kids.length(); i++) {
                JSONObject k = Js.obj(Js.at(kids, i));
                if (k != null) walkTree(k, d + 1, ids, count, bad);
                else bad.add(new Problem("kids", nid, "Layer " + nid + " is damaged."));
            }
        }
    }

    private static void ref(Map<String, JSONObject> ids, Object id, String where, String what, List<Problem> bad) {
        if (id != null && !ids.containsKey(String.valueOf(id)))
            bad.add(new Problem("ref", where, what + " points at " + id + ", which is not in the file."));
    }

    private static void checkMarks(JSONObject set, List<Problem> bad) {
        Map<String, JSONObject> kidIds = new LinkedHashMap<>();
        JSONArray ks = Js.arr(Js.get(set, "kids"));
        for (int i = 0; ks != null && i < ks.length(); i++) {
            JSONObject k = Js.obj(Js.at(ks, i));
            if (k != null) kidIds.put(k.optString("id"), k);
        }
        JSONObject marks = Js.obj(Js.get(set, "marks"));
        if (marks == null) return;
        for (String pk : Js.keys(marks)) {
            JSONObject mk = Js.obj(marks.opt(pk));
            String where = set.optString("id") + (pk.isEmpty() ? "" : " › " + pk);
            JSONArray pts = mk == null ? null : Js.arr(Js.get(mk, "pts"));
            JSONObject at = mk == null ? null : Js.obj(Js.get(mk, "at"));
            if (pts == null || at == null) {
                bad.add(new Problem("marks", where, "The landmarks of " + where + " are damaged."));
                continue;
            }
            for (int i = 0; i < pts.length(); i++) {
                JSONObject p = Js.obj(Js.at(pts, i));
                Object n = p == null ? null : Js.get(p, "n");
                if (!(Js.isNum(n) && Js.dbl(n) == i + 1)) bad.add(new Problem("mark-n", where, "Landmark " + (i + 1) + " of " + where + " is numbered " + n + "."));
            }
            for (String a : Js.keys(at)) {
                if (!kidIds.containsKey(a)) bad.add(new Problem("mark-angle", where, "Landmarks of " + where + " are placed on " + a + ", which is not one of its drawings."));
                JSONArray row = Js.arr(at.opt(a));
                if (row == null || row.length() != pts.length()) {
                    bad.add(new Problem("mark-row", where, a + " does not place every landmark of " + where + " (" + (row == null ? "none" : String.valueOf(row.length())) + " of " + pts.length() + ")."));
                    continue;
                }
                int live = 0;
                for (int i = 0; i < row.length(); i++) {
                    JSONArray q = Js.arr(Js.at(row, i));
                    boolean ok = Js.isXy(q) && q.length() <= 3 && (q.length() < 3 || (Js.at(q, 2) instanceof String && STATES.contains(Js.at(q, 2))));
                    if (!ok) bad.add(new Problem("mark-place", where, "Landmark " + (i + 1) + " in " + a + " has a bad place."));
                    if (q != null && !"off".equals(Js.at(q, 2))) live++;
                }
                if (live == 0) bad.add(new Problem("mark-empty", where, "In " + a + " every landmark of " + where + " is \"not here\"."));
            }
            JSONArray ms = Js.arr(Js.get(mk, "meets"));
            for (int i = 0; ms != null && i < ms.length(); i++) {
                JSONObject m = Js.obj(Js.at(ms, i));
                Object mat = m == null ? null : Js.get(m, "at");
                if (m == null || !kidIds.containsKey(m.optString("a")) || !kidIds.containsKey(m.optString("b")) || !Js.isNum(mat) || Js.dbl(mat) < 0 || Js.dbl(mat) > 1)
                    bad.add(new Problem("meet", where, "A \"they meet\" setting on " + where + " is damaged."));
            }
        }
    }

    private static void checkTake(Object takeV, String where, List<Problem> bad) {
        JSONObject t = Js.obj(takeV);
        Object fps = t == null ? null : Js.get(t, "fps"), n = t == null ? null : Js.get(t, "n");
        JSONObject ch = t == null ? null : Js.obj(Js.get(t, "ch"));
        if (t == null || !Js.isNum(fps) || !(Js.dbl(fps) > 0) || !Js.isNum(n) || Js.dbl(n) < 0 || Js.dbl(n) != Math.floor(Js.dbl(n)) || ch == null) {
            bad.add(new Problem("take", where, "The take is damaged."));
            return;
        }
        double tn = Js.dbl(n), tfps = Js.dbl(fps);
        for (String k : Js.keys(ch)) {
            JSONArray a = Js.arr(ch.opt(k));
            if (a == null || a.length() > tn + 1) bad.add(new Problem("take-lane", where + "." + k, "The take’s " + k + " is longer than the take."));
        }
        JSONObject v = Js.obj(Js.get(t, "voice"));
        if (v != null) {
            Object rate = Js.get(v, "rate"), vn = Js.get(v, "n");
            String wav = Js.str(Js.get(v, "wav"));
            if (!Js.isNum(rate) || !(Js.dbl(rate) > 0) || !Js.isNum(vn) || Js.dbl(vn) < 0 || wav == null
                    || !(WAV_INSIDE.matcher(wav).matches() || WAV_BESIDE.matcher(wav).matches()))
                bad.add(new Problem("voice", where, "The take’s voice is damaged."));
            else if (Math.abs(Js.dbl(vn) / Js.dbl(rate) - tn / tfps) > 1 / tfps + 0.001)
                bad.add(new Problem("voice-len", where, "The voice is not as long as the take (" + String.format(java.util.Locale.ROOT, "%.3f", Js.dbl(vn) / Js.dbl(rate))
                        + " s against " + String.format(java.util.Locale.ROOT, "%.3f", tn / tfps) + " s)."));
        }
    }

    /** The rules of a scene file, and of every character it carries. */
    public static List<Problem> checkScene(JSONObject doc) {
        List<Problem> bad = new ArrayList<>();
        if (!CharacterFile.SCENE.equals(doc.optString("format", null))) bad.add(new Problem("format", "format", "Unknown format: " + doc.optString("format", null)));
        if (!Js.truthy(Js.get(doc, "uid")) || Js.str(Js.get(doc, "uid")) == null) bad.add(new Problem("uid", "uid", "The scene has no id."));
        String shape = Js.str(Js.get(doc, "shape"));
        if (shape == null || !SHAPES.contains(shape)) bad.add(new Problem("shape", "shape", "The frame has an unknown shape: " + shape));
        JSONArray items = Js.arr(Js.get(doc, "items"));
        if (items == null) {
            bad.add(new Problem("items", "items", "The scene has no list of things."));
            return bad;
        }
        Map<String, JSONObject> ids = new LinkedHashMap<>();
        Map<String, Boolean> shots = new LinkedHashMap<>();
        JSONArray shotList = Js.arr(Js.get(doc, "shots"));
        for (int i = 0; shotList != null && i < shotList.length(); i++) {
            JSONObject s = Js.obj(Js.at(shotList, i));
            if (s == null || !Js.truthy(Js.get(s, "id")) || !Js.isNum(Js.get(s, "x")) || !Js.isNum(Js.get(s, "y")) || !(Js.isNum(Js.get(s, "z")) && Js.dbl(Js.get(s, "z")) > 0))
                bad.add(new Problem("shot", s == null ? null : s.optString("id"), "A kept shot is damaged."));
            else shots.put(s.optString("id"), true);
        }
        JSONObject pictures = Js.obj(Js.get(doc, "pictures"));
        JSONObject characters = Js.obj(Js.get(doc, "characters"));
        for (int i = 0; i < items.length(); i++) {
            String w = "items[" + i + "]";
            JSONObject it = Js.obj(Js.at(items, i));
            if (it == null || !Js.truthy(Js.get(it, "id"))) {
                bad.add(new Problem("item-id", w, "A thing in the scene has no id."));
                continue;
            }
            String id = it.optString("id");
            if (ids.containsKey(id)) bad.add(new Problem("dup-id", id, "Two things share the id " + id + "."));
            ids.put(id, it);
            String kind = it.optString("kind", "");
            if (!KINDS.contains(kind)) bad.add(new Problem("kind", id, id + " is an unknown kind of thing: " + Js.get(it, "kind")));
            if (!Js.isNum(Js.get(it, "x")) || !Js.isNum(Js.get(it, "y"))) bad.add(new Problem("item-xy", id, id + " has no place."));
            String label = Js.truthy(Js.get(it, "name")) ? it.optString("name") : id;
            JSONArray frames = Js.arr(Js.get(it, "frames"));
            boolean made = Js.truthy(Js.get(it, "made"));
            if ("cast".equals(kind)) {
                if (!Js.truthy(Js.get(it, "uid")) && !Js.truthy(Js.get(it, "stock")) && !(characters != null && Js.truthy(Js.get(characters, id))))
                    bad.add(new Problem("cast-src", id, "Character " + label + " says neither where it lives nor carries itself."));
            } else if (!made && !Js.truthy(Js.get(it, "img")) && !(frames != null && frames.length() > 0)) {
                bad.add(new Problem("item-pic", id, label + " has no picture."));
            }
            if (pictures != null) {
                List<Object> ps = new ArrayList<>();
                ps.add(Js.get(it, "img"));
                for (int f = 0; frames != null && f < frames.length(); f++) ps.add(Js.at(frames, f));
                for (Object p : ps)
                    if (Js.truthy(p) && !made && !Js.truthy(Js.get(pictures, String.valueOf(p))))
                        bad.add(new Problem("pic-missing", id, "The picture " + p + " of " + label + " is not in the file."));
            }
        }
        JSONArray cam = Js.arr(Js.get(doc, "cam"));
        for (int i = 0; cam != null && i < cam.length(); i++) {
            JSONObject c = Js.obj(Js.at(cam, i));
            Object ct = c == null ? null : Js.get(c, "t");
            String shot = c == null ? null : Js.str(Js.get(c, "shot"));
            if (c == null || !Js.isNum(ct) || Js.dbl(ct) < 0 || shot == null) {
                bad.add(new Problem("cam", "cam[" + i + "]", "A cut on the camera lane is damaged."));
                continue;
            }
            if (!shot.matches("^(wide|two|free)$") && !shot.startsWith("close:") && !shots.containsKey(shot))
                bad.add(new Problem("cam-shot", "cam[" + i + "]", "The camera lane cuts to a shot that is not in the scene: " + shot));
            if (i > 0) {
                JSONObject prev = Js.obj(Js.at(cam, i - 1));
                Object pt = prev == null ? null : Js.get(prev, "t");
                if (Js.isNum(pt) && Js.dbl(ct) < Js.dbl(pt)) bad.add(new Problem("cam-order", "cam[" + i + "]", "The camera lane is out of time order."));
            }
        }
        JSONObject takes = Js.obj(Js.get(doc, "takes"));
        for (String id : Js.keys(takes)) {
            JSONObject it = ids.get(id);
            if (it == null || !"cast".equals(it.optString("kind", "")))
                bad.add(new Problem("take-who", id, "A take belongs to " + id + ", which is not a character in the scene."));
            else checkTake(takes.opt(id), "takes." + id, bad);
        }
        for (String id : Js.keys(characters)) {
            if (!ids.containsKey(id)) bad.add(new Problem("char-who", id, "A carried character " + id + " is not in the scene."));
            for (Problem b : check(Js.obj(characters.opt(id)))) bad.add(new Problem(b.code, id + " › " + b.where, b.say));
        }
        return bad;
    }
}
