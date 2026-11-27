import cc.joycreator.avatar.solve.CharacterSolver;
import cc.joycreator.avatar.solve.Item;
import cc.joycreator.avatar.solve.Pics;
import cc.joycreator.avatar.solve.Signals;
import cc.joycreator.avatar.solve.SolveOut;
import org.json.JSONArray;
import org.json.JSONObject;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * X2 (AVA-1): the phone's CharacterSolver against the web pilot's own solver. The pilot wrote golden files (tasks/avatar/golden/,
 * AL.golden in tools/avatarlab/al-golden.js): for each case, the signals and every drawn piece's vertices after 8 frames at 30 a
 * second with a fresh solver. This runs the Java port on the same character and the same signals and checks the same pieces come
 * out in the same order, with the same opacity, every vertex within 0.5 px.
 *
 * Then the planted faults: each one changes the character or the expectation a little, and the check must FAIL on it (a check
 * that cannot fail proves nothing). Run: bash tools/jvm-harness/run-avatar-solve.sh
 */
public class CharacterSolverTest {
    static final double TOL = 0.5;
    static int pass, fail;

    public static void main(String[] a) throws Exception {
        String topps = read("tools/avatarlab/characters/topps.character.json"), squares = read("tasks/avatar/golden/squares.character.json");
        JSONObject gT = new JSONObject(read("tasks/avatar/golden/topps.golden.json")), gS = new JSONObject(read("tasks/avatar/golden/squares.golden.json"));

        /* the parity itself */
        for (String[] r : run("Topps", topps, gT, null)) report(r);
        for (String[] r : run("squares", squares, gS, null)) report(r);
        String gadget = read("tasks/avatar/golden/gadget.character.json");
        for (String[] r : run("gadget", gadget, new JSONObject(read("tasks/avatar/golden/gadget.golden.json")), null)) report(r);
        String pip = read("tasks/avatar/golden/pip.character.json");
        for (String[] r : run("Pip", pip, new JSONObject(read("tasks/avatar/golden/pip.golden.json")), null)) report(r);
        /* FACE-2: Topps wearing the face mask (the mask guessed by the pilot, a pose, a swap): the mask pass, mid-turn too */
        JSONObject maskPatch = new JSONObject(read("tasks/avatar/golden/topps-mask.patch.json"));
        String toppsMask = masked(topps, maskPatch);
        JSONObject gM = new JSONObject(read("tasks/avatar/golden/topps-mask.golden.json"));
        for (String[] r : run("Topps with the face mask", toppsMask, gM, null)) report(r);
        /* more pairs from the command line: <character.json> <golden.json> ... (the owner's own characters stay out of the repository) */
        for (int i = 0; i + 1 < a.length; i += 2) for (String[] r : run(Paths.get(a[i]).getFileName().toString(), read(a[i]), new JSONObject(read(a[i + 1])), null)) report(r);

        /* planted faults: each must make at least one case fail */
        fault("Topps: every drawing of the right arm solved from a shoulder pin 3 px off", topps, gT, ch -> { for (JSONObject l : limbsOf(ch, "armR")) l.getJSONObject("a").put("x", l.getJSONObject("a").getDouble("x") + 3); });
        fault("Topps: the expected picture one vertex 0.6 px off (the 0.5 px bar)", topps, nudged(gT, 0.6), null);
        final String mHead = maskPatch.getString("head"), mQuarter = maskPatch.getString("quarter");
        fault("Topps mask: the mask pass forgotten (your face moves nothing)", toppsMask, gM, ch -> findKid(ch, mHead).getJSONObject("face").remove("fit"));
        fault("Topps mask: the drawing's own turn ignored (the quarter drawing taken as front-on)", toppsMask, gM, ch -> findKid(ch, mHead).getJSONObject("face").getJSONObject("fit").getJSONArray(mQuarter).put(0, 0));
        fault("Topps mask: the poses forgotten", toppsMask, gM, ch -> findKid(ch, mHead).getJSONObject("face").remove("posed"));
        fault("Topps mask: a strong pose swaps nothing", toppsMask, gM, ch -> findKid(ch, mHead).getJSONObject("face").remove("swapped"));
        fault("Topps mask: the gain doubled", toppsMask, gM, ch -> findKid(ch, mHead).getJSONObject("face").put("gain", 2));
        fault("squares: the landmark that is BEHIND in B treated as NOT HERE", squares, gS, ch -> headMarks(ch).getJSONObject("at").getJSONArray("kB").put(4, new JSONArray("[220,280,\"off\"]")));
        fault("squares: the landmark that is NOT HERE in B counted as here", squares, gS, ch -> headMarks(ch).getJSONObject("at").getJSONArray("kB").put(5, new JSONArray("[500,500]")));
        fault("squares: \"They meet\" between A and C forgotten", squares, gS, ch -> headMarks(ch).remove("meets"));
        fault("gadget: the round head's turn ignored (yaw 0)", gadget, new JSONObject(read("tasks/avatar/golden/gadget.golden.json")), ch -> findKid(ch, "ballhead").getJSONObject("ball").put("yaw", 0));
        fault("gadget: the hand no longer rides the hose arm", gadget, new JSONObject(read("tasks/avatar/golden/gadget.golden.json")), ch -> findKid(ch, "hands").remove("ride"));
        fault("squares: the mirrored angle points at C instead of B", squares, gS, ch -> findKid(ch, "kD").put("of", "kC"));

        System.out.println((fail == 0 ? "PASSED " : "FAILED ") + pass + " checks, " + fail + " failures");
        System.exit(fail == 0 ? 0 : 1);
    }

    interface Edit { void on(JSONObject ch) throws Exception; }

    static void fault(String what, String chText, JSONObject golden, Edit e) throws Exception {
        JSONObject ch = new JSONObject(chText);
        if (e != null) e.on(ch);
        boolean caught = false; String first = "";
        for (String[] r : run("fault", ch.toString(), golden, null)) if (r[0].equals("FAIL")) { caught = true; if (first.isEmpty()) first = r[1]; }
        report(new String[] {caught ? "OK" : "FAIL", "planted fault caught: " + what + (caught ? "  [" + first + "]" : "  (NOT caught)")});
    }

    static void report(String[] r) {
        if (r[0].equals("OK")) pass++; else fail++;
        System.out.println((r[0].equals("OK") ? "ok   " : "FAIL ") + r[1]);
    }

    /** Runs every case of a golden file; one result line per case. */
    /** A character with the face mask put on its head set (topps-mask.patch.json: the head's mask landmarks and face data). */
    static String masked(String chText, JSONObject patch) throws Exception {
        JSONObject ch = new JSONObject(chText), head = findKid(ch, patch.getString("head"));
        JSONObject marks = head.optJSONObject("marks");
        if (marks == null) { marks = new JSONObject(); head.put("marks", marks); }
        marks.put("", patch.getJSONObject("marks"));
        head.put("face", patch.getJSONObject("face"));
        return ch.toString();
    }

    static List<String[]> run(String label, String chText, JSONObject golden, Object unused) throws Exception {
        List<String[]> out = new ArrayList<>();
        JSONObject ch = new JSONObject(chText);
        Pics pics = new PngPics(ch);
        JSONArray cases = golden.getJSONArray("cases");
        int frames = golden.optInt("frames", 8); double dt = golden.optDouble("dt", 1 / 30.0);
        for (int ci = 0; ci < cases.length(); ci++) {
            JSONObject cs = cases.getJSONObject(ci);
            CharacterSolver sv = new CharacterSolver(new JSONObject(chText.equals(ch.toString()) ? chText : ch.toString()), pics);
            CharacterSolver.Opt opt = new CharacterSolver.Opt(); opt.live = true; opt.dt = dt;
            Signals S = Signals.fromJson(cs.getJSONObject("signals"));
            SolveOut o = null;
            for (int i = 0; i < frames; i++) { S.t = i * dt; o = sv.eval(S, opt); }
            JSONArray want = cs.getJSONArray("items");
            String name = label + " / " + cs.getString("name");
            if (o.items.size() != want.length()) { out.add(new String[] {"FAIL", name + ": " + o.items.size() + " pieces drawn, the pilot drew " + want.length() + " " + ids(o.items) + " vs " + ids(want)}); continue; }
            double worst = 0; String where = "", bad = null;
            for (int i = 0; i < want.length() && bad == null; i++) {
                JSONObject w = want.getJSONObject(i); Item it = o.items.get(i);
                if (!w.getString("id").equals(it.id) || !w.optString("img").equals(it.img)) { bad = "piece " + i + " is " + it.id + ", the pilot drew " + w.getString("id") + " there"; break; }
                if (Math.abs(w.getDouble("a") - Math.round(it.a * 100) / 100.0) > 0.011) { bad = it.id + " opacity " + it.a + ", the pilot " + w.getDouble("a"); break; }
                if (w.optBoolean("ghost") != it.ghost) { bad = it.id + " ghost differs"; break; }
                String wu = w.isNull("under") ? null : w.optString("under", null);
                if (!(wu == null ? it.under == null : wu.equals(it.under))) { bad = it.id + " under " + it.under + ", the pilot " + wu; break; }
                JSONArray v = w.getJSONArray("v");
                if (v.length() != it.v.length) { bad = it.id + " has " + it.v.length / 2 + " vertices, the pilot " + v.length() / 2; break; }
                for (int k = 0; k < v.length(); k++) { double d = Math.abs(v.getDouble(k) - it.v[k]); if (d > worst) { worst = d; where = it.id + " vertex " + k / 2; } }
            }
            if (bad != null) { out.add(new String[] {"FAIL", name + ": " + bad}); continue; }
            String line = name + ": " + want.length() + " pieces, worst vertex " + String.format("%.3f", worst) + " px (" + where + ")";
            out.add(new String[] {worst <= TOL ? "OK" : "FAIL", line});
        }
        return out;
    }

    static String ids(List<Item> l) { StringBuilder b = new StringBuilder("["); for (Item i : l) b.append(i.id).append(' '); return b.append(']').toString(); }

    static String ids(JSONArray l) { StringBuilder b = new StringBuilder("["); for (int i = 0; i < l.length(); i++) b.append(l.optJSONObject(i).optString("id")).append(' '); return b.append(']').toString(); }

    static JSONObject nudged(JSONObject g, double by) throws Exception {
        JSONObject c = new JSONObject(g.toString());
        JSONArray v = c.getJSONArray("cases").getJSONObject(0).getJSONArray("items").getJSONObject(0).getJSONArray("v");
        v.put(0, v.getDouble(0) + by);
        return c;
    }

    static JSONObject limbOf(JSONObject ch, String role) throws Exception {
        JSONObject[] hit = {null};
        walk(ch.optJSONObject("root"), n -> { if (hit[0] == null && role.equals(n.optString("role"))) { JSONArray k = n.optJSONArray("kids"); if (k != null) for (int i = 0; i < k.length() && hit[0] == null; i++) { JSONObject q = k.optJSONObject(i); if (q != null && q.optJSONObject("limb") != null) hit[0] = q.optJSONObject("limb"); } } });
        if (hit[0] == null) throw new IllegalStateException("no " + role + " limb");
        return hit[0];
    }

    static List<JSONObject> limbsOf(JSONObject ch, String role) {
        List<JSONObject> out = new ArrayList<>();
        walk(ch.optJSONObject("root"), n -> { if (role.equals(n.optString("role"))) walk(n, q -> { if (q.optJSONObject("limb") != null) out.add(q.optJSONObject("limb")); }); });
        return out;
    }

    static JSONObject headMarks(JSONObject ch) throws Exception { return findKid(ch, "head").getJSONObject("marks").getJSONObject(""); }

    static JSONObject findKid(JSONObject ch, String id) {
        JSONObject[] hit = {null};
        walk(ch.optJSONObject("root"), n -> { if (id.equals(n.optString("id"))) hit[0] = n; });
        return hit[0];
    }

    interface V { void at(JSONObject n); }

    static void walk(JSONObject n, V v) {
        v.at(n);
        JSONArray k = n.optJSONArray("kids");
        if (k != null) for (int i = 0; i < k.length(); i++) { JSONObject q = k.optJSONObject(i); if (q != null) walk(q, v); }
    }

    static String read(String p) throws Exception { return new String(Files.readAllBytes(Paths.get(p)), StandardCharsets.UTF_8); }

    /** The character's pictures decoded on the PC (the phone decodes them with BitmapFactory). */
    static final class PngPics implements Pics {
        final Map<String, int[]> size = new HashMap<>();
        final Map<String, byte[]> alpha = new HashMap<>();

        PngPics(JSONObject ch) throws Exception {
            JSONObject pics = ch.optJSONObject("pictures");
            if (pics == null) return;
            for (java.util.Iterator<String> it = pics.keys(); it.hasNext(); ) {
                String k = it.next(), u = pics.getString(k);
                int c = u.indexOf(',');
                BufferedImage im = ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(u.substring(c + 1))));
                if (im == null) continue;
                int w = im.getWidth(), h = im.getHeight();
                byte[] al = new byte[w * h];
                for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) al[y * w + x] = (byte) ((im.getRGB(x, y) >>> 24) & 0xff);
                size.put(k, new int[] {w, h}); alpha.put(k, al);
            }
        }

        public boolean has(String img) { return size.containsKey(img); }

        public int w(String img) { return size.get(img)[0]; }

        public int h(String img) { return size.get(img)[1]; }

        public int ver(String img) { return 1; }

        public byte[] alpha(String img) { return alpha.get(img); }
    }
}
