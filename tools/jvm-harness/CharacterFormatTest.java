import cc.joycreator.avatar.file.CharacterFile;
import cc.joycreator.avatar.file.FormatCheck;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Avatar Studio's file format on the JVM: the SAME checks as {@code node tasks/avatar/format-tests/run.mjs}, on the same
 * files, with the same 37 planted faults. Run: bash tools/jvm-harness/run-avatar-format.sh
 */
public class CharacterFormatTest {
    static int pass, fail;
    static final String DIR = "tasks/avatar/format-tests/";

    static void ok(boolean cond, String what, String detail) {
        if (cond) pass++;
        else {
            fail++;
            System.out.println("FAIL " + what + (detail == null || detail.isEmpty() ? "" : "\n     " + detail));
        }
    }

    static JSONObject load(String name) throws Exception {
        return new JSONObject(new String(Files.readAllBytes(new File(DIR + name).toPath()), StandardCharsets.UTF_8));
    }

    static JSONObject copy(JSONObject o) throws Exception {
        return new JSONObject(o.toString());
    }

    static String say(List<FormatCheck.Problem> l) {
        StringBuilder sb = new StringBuilder();
        for (FormatCheck.Problem p : l) sb.append(p).append("\n     ");
        return sb.toString();
    }

    static JSONObject kid(JSONObject o, int i) throws Exception {
        return o.getJSONArray("kids").getJSONObject(i);
    }

    static boolean near(double[] p, double x, double y) {
        return p != null && Math.abs(p[0] - x) < 1e-6 && Math.abs(p[1] - y) < 1e-6;
    }

    static boolean near(JSONArray a, double x, double y) {
        return Math.abs(a.optDouble(0) - x) < 1e-6 && Math.abs(a.optDouble(1) - y) < 1e-6;
    }

    static Map<String, Double> w(Object... kv) {
        Map<String, Double> m = new java.util.LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], ((Number) kv[i + 1]).doubleValue());
        return m;
    }

    /** every pair landmark of the /1 file must come back out of /2 -> /1, within the join distance, with its "they meet" */
    static List<String> pairsKept(JSONObject v1, JSONObject back) throws Exception {
        final List<String> miss = new ArrayList<>();
        final Map<String, JSONObject> sets = new HashMap<>();
        collect(back.getJSONObject("root"), sets);
        List<JSONObject> v1sets = new ArrayList<>();
        collectList(v1.getJSONObject("root"), v1sets);
        for (JSONObject n : v1sets) {
            JSONArray bridges = n.optJSONArray("bridges");
            if (bridges == null) continue;
            for (int bi = 0; bi < bridges.length(); bi++) {
                JSONObject br = bridges.getJSONObject(bi);
                JSONArray bs = sets.get(n.getString("id")).optJSONArray("bridges");
                for (int qi = 0; qi < br.getJSONArray("pts").length(); qi++) {
                    JSONObject q = br.getJSONArray("pts").getJSONObject(qi);
                    List<JSONObject> got = new ArrayList<>();
                    for (int i = 0; bs != null && i < bs.length(); i++) {
                        JSONObject b = bs.getJSONObject(i);
                        if (b.optString("part", "").equals(br.optString("part", ""))
                                && ((b.getString("a").equals(br.getString("a")) && b.getString("b").equals(br.getString("b")))
                                || (b.getString("a").equals(br.getString("b")) && b.getString("b").equals(br.getString("a"))))) got.add(b);
                    }
                    boolean hit = false;
                    for (JSONObject b : got) {
                        boolean rev = !b.getString("a").equals(br.getString("a"));
                        for (int pi = 0; pi < b.getJSONArray("pts").length(); pi++) {
                            JSONObject p = b.getJSONArray("pts").getJSONObject(pi);
                            JSONArray pa = rev ? p.getJSONArray("b") : p.getJSONArray("a"), pb = rev ? p.getJSONArray("a") : p.getJSONArray("b");
                            if (Math.hypot(pa.getDouble(0) - q.getJSONArray("a").getDouble(0), pa.getDouble(1) - q.getJSONArray("a").getDouble(1)) <= CharacterFile.JOIN_PX
                                    && Math.hypot(pb.getDouble(0) - q.getJSONArray("b").getDouble(0), pb.getDouble(1) - q.getJSONArray("b").getDouble(1)) <= CharacterFile.JOIN_PX)
                                hit = true;
                        }
                    }
                    if (!hit) miss.add(br.optString("part", "whole") + " " + br.getString("a") + "-" + br.getString("b") + " " + q);
                    double m0 = br.has("meet") ? br.getDouble("meet") : 0.5;
                    if (!got.isEmpty()) {
                        JSONObject b1 = got.get(0);
                        double m1 = b1.getString("a").equals(br.getString("a")) ? b1.getDouble("meet") : 1 - b1.getDouble("meet");
                        if (Math.abs(m0 - m1) > 1e-9) miss.add("meet " + br.getString("a") + "-" + br.getString("b") + " " + m0 + " became " + m1);
                    }
                }
            }
        }
        return miss;
    }

    static void collect(JSONObject n, Map<String, JSONObject> sets) {
        if ("set".equals(n.optString("type"))) sets.put(n.optString("id"), n);
        JSONArray k = n.optJSONArray("kids");
        for (int i = 0; k != null && i < k.length(); i++) collect(k.optJSONObject(i), sets);
    }

    static void collectList(JSONObject n, List<JSONObject> out) {
        if ("set".equals(n.optString("type"))) out.add(n);
        JSONArray k = n.optJSONArray("kids");
        for (int i = 0; k != null && i < k.length(); i++) collectList(k.optJSONObject(i), out);
    }

    interface Plant {
        void on(JSONObject o) throws Exception;
    }

    static class Fault {
        final String code;
        final JSONObject base;
        final Plant plant;

        Fault(String code, JSONObject base, Plant plant) {
            this.code = code;
            this.base = base;
            this.plant = plant;
        }
    }

    public static void main(String[] args) throws Exception {
        File[] listed = new File(DIR).listFiles((d, n) -> n.endsWith(".json"));
        Arrays.sort(listed);
        for (File lf : listed) {
            String f = lf.getName();
            JSONObject doc = load(f);
            boolean isScene = CharacterFile.SCENE.equals(doc.optString("format"));
            ok(FormatCheck.check(doc).isEmpty(), f + ": follows the rules", say(FormatCheck.check(doc)));
            CharacterFile.Upgraded up = isScene ? CharacterFile.upgradeScene(doc) : CharacterFile.upgrade(doc);
            ok(FormatCheck.check(up.doc).isEmpty(), f + ": follows the rules after upgrade", say(FormatCheck.check(up.doc)));
            CharacterFile.Upgraded up2 = isScene ? CharacterFile.upgradeScene(up.doc) : CharacterFile.upgrade(up.doc);
            ok(CharacterFile.canonical(up2.doc).equals(CharacterFile.canonical(up.doc)), f + ": upgrading twice changes nothing", null);
            ok(CharacterFile.canonical(new JSONObject(up.doc.toString(2))).equals(CharacterFile.canonical(up.doc)), f + ": survives being written and read as text", null);
            if (!isScene && CharacterFile.CHARACTER_OLD.equals(doc.optString("format"))) {
                List<String> miss = pairsKept(doc, CharacterFile.downgrade(up.doc));
                ok(miss.isEmpty(), f + ": every landmark pair and \"they meet\" comes back from /2 to /1", miss.toString());
            }
            if (isScene) {
                JSONObject cs = up.doc.optJSONObject("characters");
                for (String id : (cs == null ? new ArrayList<String>() : keys(cs)))
                    ok(CharacterFile.CHARACTER.equals(cs.getJSONObject(id).optString("format")), f + ": a carried character is upgraded with its scene", null);
            }
            List<String> a = keys(up.doc), b = keys(doc);
            a.remove("format");
            b.remove("format");
            java.util.Collections.sort(a);
            java.util.Collections.sort(b);
            ok(a.equals(b), f + ": upgrade keeps every top-level field", b + " -> " + a);
        }

        // the character that ships in the app (Topps) must open: rules before and after the in-memory upgrade
        {
            JSONObject topps = new JSONObject(new String(Files.readAllBytes(new File("app/src/main/assets/avatar/topps.character.json").toPath()), StandardCharsets.UTF_8));
            ok(FormatCheck.check(topps).isEmpty(), "Topps (shipped asset): follows the rules", say(FormatCheck.check(topps)));
            ok(FormatCheck.check(CharacterFile.upgrade(topps).doc).isEmpty(), "Topps (shipped asset): follows the rules after upgrade", say(FormatCheck.check(CharacterFile.upgrade(topps).doc)));
            JSONObject pip = new JSONObject(new String(Files.readAllBytes(new File("app/src/main/assets/avatar/pip.character.json").toPath()), StandardCharsets.UTF_8));
            ok(FormatCheck.check(pip).isEmpty(), "Pip (shipped asset): follows the rules", say(FormatCheck.check(pip)));
            ok(FormatCheck.check(CharacterFile.upgrade(pip).doc).isEmpty(), "Pip (shipped asset): follows the rules after upgrade", say(FormatCheck.check(CharacterFile.upgrade(pip).doc)));
        }

        // the "separate pictures" door builds a file that follows the rules
        {
            List<cc.joycreator.avatar.file.LooseCharacter.Pic> pics = new ArrayList<>();
            for (int i = 0; i < 6; i++) pics.add(new cc.joycreator.avatar.file.LooseCharacter.Pic("Picture " + (i + 1), 100 + i, 80, "data:image/png;base64,AAAA"));
            JSONObject made = cc.joycreator.avatar.file.LooseCharacter.build("Test", pics);
            ok(FormatCheck.check(made).isEmpty(), "separate pictures: the new character follows the rules", say(FormatCheck.check(made)));
            ok(FormatCheck.check(CharacterFile.upgrade(made).doc).isEmpty(), "separate pictures: and after upgrade", null);
            ok(made.getInt("h") == 512 && made.getJSONObject("pictures").length() == 6, "separate pictures: 6 pictures on a 4-wide grid make 2 rows", made.getInt("h") + "");
        }

        // the conversion, by numbers (v1-three-angles)
        {
            JSONObject doc = load("v1-three-angles.character.json");
            CharacterFile.Upgraded u = CharacterFile.upgrade(doc);
            JSONObject head = kid(u.doc.getJSONObject("root"), 0);
            JSONObject whole = head.getJSONObject("marks").getJSONObject(""), frill = head.getJSONObject("marks").getJSONObject("frill");
            JSONObject wat = whole.getJSONObject("at"), fat = frill.getJSONObject("at");
            ok(!head.has("bridges"), "three angles: no pairs left in /2", null);
            ok(whole.getJSONArray("pts").length() == 4, "three angles: 7 pairs of the whole head become 4 numbered landmarks", whole.getJSONArray("pts").toString());
            ok(near(wat.getJSONArray("LQ").getJSONArray(0), 100.5, 120.5), "three angles: the nose matched to two neighbours is ONE landmark", wat.getJSONArray("LQ").toString());
            ok(near(wat.getJSONArray("UP").getJSONArray(1), 128, 190.5), "three angles: the chin in Upward, marked twice 1 px apart, is averaged", wat.getJSONArray("UP").toString());
            ok(near(wat.getJSONArray("LQ").getJSONArray(2), 150, 200) && near(wat.getJSONArray("UP").getJSONArray(2), 128, 191)
                    && "off".equals(wat.getJSONArray("FR").getJSONArray(2).opt(2)), "three angles: a chin that disagrees (40 px off) is split into its own landmark, \"not here\" in Frontal", wat.toString());
            ok("off".equals(wat.getJSONArray("FR").getJSONArray(3).opt(2)) && near(wat.getJSONArray("FR").getJSONArray(3), 65, 70), "three angles: a landmark only Left Quarter and Upward have is \"not here\" in Frontal, parked between its places", wat.getJSONArray("FR").toString());
            ok(frill.getJSONArray("pts").length() == 2 && near(fat.getJSONArray("FR").getJSONArray(0), 60, 40) && "off".equals(fat.getJSONArray("FR").getJSONArray(1).opt(2)), "three angles: the frill keeps its own landmarks (3 pairs -> 2 points)", frill.toString());
            JSONArray meets = whole.optJSONArray("meets");
            ok(meets != null && meets.length() == 1 && "LQ".equals(meets.getJSONObject(0).optString("a")) && "FR".equals(meets.getJSONObject(0).optString("b"))
                    && Math.abs(meets.getJSONObject(0).optDouble("at") - 0.4) < 1e-9 && !frill.has("meets"), "three angles: the one non-default \"they meet\" is kept, on the whole head only", null);
            ok("own".equals(head.getJSONObject("warpWith").optString("frill")), "three angles: the frill still warps by its own landmarks", null);
            boolean said = false;
            for (String s : u.report) if (s.contains("second became a landmark of its own")) said = true;
            ok(said, "three angles: the split is said in the report", u.report.toString());
            ok(!wat.has("RQ"), "three angles: the mirrored angle has no row of its own", null);
            List<double[]> b1 = CharacterFile.blend(head, "", w("FR", 0.5, "UP", 0.5), null);
            ok(near(b1.get(0), 128, 110), "blend: half way Frontal to Upward, the nose is half way", String.valueOf(b1.get(0)));
            ok(near(b1.get(2), 128, 191), "blend: a point \"not here\" in Frontal takes Upward's place alone", String.valueOf(b1.get(2)));
            ok(CharacterFile.blend(head, "", w("FR", 1), null).get(3) == null, "blend: a point \"not here\" in the only angle is no point at all", null);
            List<double[]> rq = CharacterFile.blend(head, "", w("RQ", 1), null);
            ok(near(rq.get(0), 155.5, 120.5), "blend: the mirrored angle reads its source, mirrored about the axis", rq.get(0) == null ? "null" : rq.get(0)[0] + "," + rq.get(0)[1]);
            JSONObject v2c = load("v2-crest-hidden.character.json");
            JSONObject crest = kid(v2c.getJSONObject("root"), 1);
            List<double[]> c1 = CharacterFile.blend(crest, "", w("FR", 0.5, "LP", 0.5), null);
            ok(near(c1.get(4), 134, 3), "blend: a tip folded behind in profile still steers (half way)", String.valueOf(c1.get(4)));
            ok(near(c1.get(6), 186, 10), "blend: a tip \"not here\" in profile is Frontal's alone", String.valueOf(c1.get(6)));
            ok(near(CharacterFile.blend(crest, "", w("RP", 1), null).get(0), 196, 12), "blend: the mirrored profile places tip 1 mirrored", null);
            ok(CharacterFile.blend(crest, "", w("RP", 1), null).get(4) != null, "blend: a mirrored \"behind\" point is still a place", null);
        }

        // planted faults: each must be caught by its own rule
        JSONObject crestDoc = load("v2-crest-hidden.character.json"), scene = load("two-on-a-meadow.scene.json");
        List<Fault> faults = new ArrayList<>();
        faults.add(new Fault("dup-id", crestDoc, o -> kid(o.getJSONObject("root"), 0).put("id", "head")));
        faults.add(new Fault("type", crestDoc, o -> kid(o.getJSONObject("root"), 0).put("type", "sprite")));
        faults.add(new Fault("ref", crestDoc, o -> kid(kid(o.getJSONObject("root"), 1), 2).put("of", "nobody")));
        faults.add(new Fault("ref", crestDoc, o -> kid(o.getJSONObject("root"), 1).getJSONObject("pick").put("rest", "gone")));
        faults.add(new Fault("pic-missing", crestDoc, o -> kid(o.getJSONObject("root"), 0).put("img", "p9")));
        faults.add(new Fault("trig-ref", crestDoc, o -> kid(kid(o.getJSONObject("root"), 2), 1).put("trig", "t9")));
        faults.add(new Fault("trig-ref", crestDoc, o -> o.getJSONArray("poses").getJSONObject(0).put("trig", "t9")));
        faults.add(new Fault("ref", crestDoc, o -> o.getJSONArray("poses").getJSONObject(0).getJSONObject("d").put("ghost", new JSONObject().put("x", 1))));
        faults.add(new Fault("angle-at", crestDoc, o -> kid(kid(o.getJSONObject("root"), 1), 0).remove("at")));
        faults.add(new Fault("mark-row", crestDoc, o -> marks(o, 1).getJSONObject("at").getJSONArray("LP").remove(marks(o, 1).getJSONObject("at").getJSONArray("LP").length() - 1)));
        faults.add(new Fault("mark-place", crestDoc, o -> marks(o, 1).getJSONObject("at").getJSONArray("LP").put(0, new JSONArray().put(1).put(2).put("hidden"))));
        faults.add(new Fault("mark-place", crestDoc, o -> marks(o, 1).getJSONObject("at").getJSONArray("FR").put(0, new JSONArray().put(1))));
        faults.add(new Fault("mark-angle", crestDoc, o -> marks(o, 1).getJSONObject("at").put("XX", marks(o, 1).getJSONObject("at").getJSONArray("LP"))));
        faults.add(new Fault("mark-n", crestDoc, o -> marks(o, 1).getJSONArray("pts").getJSONObject(2).put("n", 7)));
        faults.add(new Fault("mark-empty", crestDoc, o -> marks(o, 2).getJSONObject("at").put("m1", new JSONArray()
                .put(new JSONArray().put(0).put(0).put("off")).put(new JSONArray().put(0).put(0).put("off")))));
        faults.add(new Fault("meet", crestDoc, o -> marks(o, 1).getJSONArray("meets").getJSONObject(0).put("at", 1.5)));
        faults.add(new Fault("pairs-in-v2", crestDoc, o -> kid(o.getJSONObject("root"), 1).put("bridges", new JSONArray())));
        faults.add(new Fault("take-lane", crestDoc, o -> o.getJSONObject("take").getJSONObject("ch").put("turnX", new JSONArray().put(0).put(0).put(0).put(0).put(0).put(0))));
        faults.add(new Fault("voice-len", crestDoc, o -> o.getJSONObject("take").getJSONObject("voice").put("n", 300)));
        faults.add(new Fault("voice", crestDoc, o -> o.getJSONObject("take").getJSONObject("voice").put("wav", "hello")));
        faults.add(new Fault("take", crestDoc, o -> o.getJSONObject("take").put("fps", 0)));
        faults.add(new Fault("palette", crestDoc, o -> o.getJSONArray("palettes").getJSONObject(0).getJSONArray("stops").getJSONObject(0).remove("from")));
        faults.add(new Fault("pic-form", crestDoc, o -> o.getJSONObject("pictures").put("p1", "C:/Users/someone/art.png")));
        faults.add(new Fault("size", crestDoc, o -> o.put("w", 0)));
        faults.add(new Fault("format", crestDoc, o -> o.put("format", "joycreator.character/9")));
        faults.add(new Fault("tangle", crestDoc, o -> {
            JSONObject n = o.getJSONObject("root");
            for (int i = 0; i < 45; i++) {
                JSONObject k = new JSONObject().put("id", "deep" + i).put("type", "group").put("kids", new JSONArray());
                n.getJSONArray("kids").put(k);
                n = k;
            }
        }));
        faults.add(new Fault("shape", scene, o -> o.put("shape", "21:9")));
        faults.add(new Fault("dup-id", scene, o -> o.getJSONArray("items").getJSONObject(1).put("id", "k1")));
        faults.add(new Fault("kind", scene, o -> o.getJSONArray("items").getJSONObject(1).put("kind", "prop")));
        faults.add(new Fault("cast-src", scene, o -> o.getJSONObject("characters").remove("k3")));
        faults.add(new Fault("item-pic", scene, o -> o.getJSONArray("items").getJSONObject(4).remove("img")));
        faults.add(new Fault("pic-missing", scene, o -> o.getJSONArray("items").getJSONObject(1).getJSONArray("frames").put("f9")));
        faults.add(new Fault("cam-shot", scene, o -> o.getJSONArray("cam").getJSONObject(2).put("shot", "h9")));
        faults.add(new Fault("cam-order", scene, o -> o.getJSONArray("cam").getJSONObject(1).put("t", 5)));
        faults.add(new Fault("take-who", scene, o -> o.getJSONObject("takes").put("k2", o.getJSONObject("takes").get("k3"))));
        faults.add(new Fault("char-who", scene, o -> o.getJSONObject("characters").put("k9", o.getJSONObject("characters").get("k3"))));
        faults.add(new Fault("ref", scene, o -> kid(o.getJSONObject("characters").getJSONObject("k3").getJSONObject("root"), 0)
                .put("clips", new JSONArray().put(new JSONObject().put("by", "none")))));
        for (Fault f : faults) {
            JSONObject o = copy(f.base);
            f.plant.on(o);
            List<FormatCheck.Problem> got = FormatCheck.check(o);
            boolean hit = false;
            for (FormatCheck.Problem p : got) if (p.code.equals(f.code)) hit = true;
            ok(hit, "planted fault \"" + f.code + "\" is caught", got.isEmpty() ? "nothing was reported" : say(got));
        }
        ok(FormatCheck.check(crestDoc).isEmpty() && FormatCheck.check(scene).isEmpty(), "the planted faults did not touch the test files", null);

        System.out.println((fail > 0 ? "FAILED " : "PASSED ") + pass + " checks, " + fail + " failures (" + faults.size() + " planted faults, " + listed.length + " test files)");
        System.exit(fail > 0 ? 1 : 0);
    }

    static JSONObject marks(JSONObject o, int kidIndex) throws Exception {
        return kid(o.getJSONObject("root"), kidIndex).getJSONObject("marks").getJSONObject("");
    }

    static List<String> keys(JSONObject o) {
        List<String> l = new ArrayList<>();
        for (java.util.Iterator<String> it = o.keys(); it.hasNext(); ) l.add(it.next());
        return l;
    }
}
