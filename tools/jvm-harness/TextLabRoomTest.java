import cc.joycreator.textlab.TextLookShelf;
import cc.joycreator.textlab.TlLookFiles;
import cc.joycreator.textlab.TlParts;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The TextLab room's logic off the phone, against the real parts.json and looks: which slider groups a look shows,
 * pieces added and taken away (Fire included), the progress a look plays, the frame fitting of an export, the
 * room's undo, and the shelf rule "the person's copy of a built-in look stands in its place".
 * Usage: bash tools/jvm-harness/run-textlabroom.sh
 */
public final class TextLabRoomTest {

    private static int pass, fail;

    static void check(String what, boolean ok, Object detail) {
        if (ok) {
            pass++;
            System.out.println("PASS  " + what);
        } else {
            fail++;
            System.out.println("FAIL  " + what + "   " + detail);
        }
    }

    static JSONObject read(String path) throws Exception {
        return new JSONObject(new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8));
    }

    public static void main(String[] a) throws Exception {
        String assets = "app/src/main/assets/textlab/";
        TlParts parts = new TlParts(read(assets + "parts.json"));
        JSONObject steel = read(assets + "looks/dark-steel.text.json");
        JSONObject blaze = read(assets + "looks/blaze.text.json");
        JSONObject neon = read(assets + "looks/neon-bar.text.json");

        // ---- the catalogue
        check("37 slider groups", parts.slots.size() == 37, parts.slots.size());
        check("every knob has a known group", parts.knobs.values().stream().allMatch(k -> parts.slots.contains(k.slot)), "");
        check("a piece for every special group, plus Fire", parts.pieces.size() == 26, parts.pieces.size());

        // ---- which groups a look shows
        List<String> s = parts.slotsFor(steel);
        check("Dark Steel shows Depth", s.contains("Depth"), s);
        check("Dark Steel has no Fire tab", !s.contains("Fire"), s);
        check("Dark Steel has no special groups", !s.contains("Rust") && !s.contains("Tube"), s);
        List<String> b = parts.slotsFor(blaze);
        check("Blaze shows its Fire tab (\"fire\": true)", b.contains("Fire"), b);
        check("Blaze shows its Ember tab (feature EMBER)", b.contains("Ember"), b);
        List<String> n = parts.slotsFor(neon);
        check("a neon tube look hides Depth / Coats / Material / Light / Sides",
                !n.contains("Depth") && !n.contains("Coats") && !n.contains("Material") && !n.contains("Light") && !n.contains("Sides"), n);
        check("a neon tube look shows Tube", n.contains("Tube"), n);

        // ---- pieces
        JSONObject fired = TlParts.withFeature(steel, TlParts.FIRE, true);
        check("adding Fire sets \"fire\": true", fired.optBoolean("fire", false), fired.opt("fire"));
        check("adding Fire leaves the features list alone", fired.optJSONArray("features").length() == 0, fired.optJSONArray("features"));
        check("Fire is then a feature of the look", TlParts.features(fired).contains(TlParts.FIRE), TlParts.features(fired));
        check("the Fire tab appears", parts.slotsFor(fired).contains("Fire"), parts.slotsFor(fired));
        JSONObject unfired = TlParts.withFeature(fired, TlParts.FIRE, false);
        check("taking Fire away removes it", !unfired.has("fire"), unfired.opt("fire"));
        JSONObject rusty = TlParts.withFeature(steel, "RUST", true);
        check("adding Rust adds the feature", TlParts.features(rusty).contains("RUST"), TlParts.features(rusty));
        check("the Rust tab appears", parts.slotsFor(rusty).contains("Rust"), parts.slotsFor(rusty));
        check("the look itself is not changed", TlParts.features(steel).isEmpty(), TlParts.features(steel));
        JSONObject both = TlParts.withFeature(TlParts.withFeature(steel, "RUST", true), "BURN", true);
        JSONArray fb = both.optJSONArray("features");
        check("features stay sorted", fb.length() == 2 && fb.optString(0).equals("BURN") && fb.optString(1).equals("RUST"), fb);
        check("taking Rust away keeps Burn",
                TlParts.features(TlParts.withFeature(both, "RUST", false)).equals(new java.util.HashSet<>(Arrays.asList("BURN"))), "");

        // ---- progress
        check("Dark Steel plays no progress", TlParts.progress(steel) == null, "");
        String[] pb = TlParts.progress(TlParts.withFeature(TlParts.withFeature(steel, "FRONT", true), "BURN", true));
        check("a burning front is \"Burned away\" on frontGone", pb != null && pb[1].equals("frontGone") && pb[2].equals("Burned away"),
                pb == null ? "null" : String.join(",", pb));
        String[] ps = TlParts.progress(TlParts.withFeature(steel, "SHATTER", true));
        check("a shatter is \"Broken\" on shatter", ps != null && ps[1].equals("shatter"), "");
        check("the progress bar's ends (front)", Math.abs(TlParts.progressValue("frontGone", 0) - 0.02) < 1e-9
                && Math.abs(TlParts.progressValue("frontGone", 1) - 1.10) < 1e-9, "");

        // ---- the room's undo (one press, one step)
        Class<?> undoC = Class.forName("cc.joycreator.textlab.TlUndo");
        java.lang.reflect.Constructor<?> uc = undoC.getDeclaredConstructor();
        uc.setAccessible(true);
        Object undo = uc.newInstance();
        java.lang.reflect.Method before = undoC.getDeclaredMethod("before", String.class, String.class, String.class);
        java.lang.reflect.Method un = undoC.getDeclaredMethod("undo", String.class, String.class);
        java.lang.reflect.Method re = undoC.getDeclaredMethod("redo", String.class, String.class);
        before.setAccessible(true);
        un.setAccessible(true);
        re.setAccessible(true);
        before.invoke(undo, "A", "w", "first");
        before.invoke(undo, "A", "w", "same again");   // nothing changed since: not a second step
        before.invoke(undo, "B", "w", "second");
        Object s1 = un.invoke(undo, "C", "w");
        Object s2 = un.invoke(undo, "B", "w");
        Object s3 = un.invoke(undo, "A", "w");
        check("undo goes back step by step (B, then A, then nothing)", look(s1).equals("B") && look(s2).equals("A") && s3 == null,
                look(s1) + "," + look(s2) + "," + s3);
        Object r1 = re.invoke(undo, "A", "w");
        check("redo comes forward again", r1 != null && look(r1).equals("B"), r1 == null ? "null" : look(r1));

        // ---- the shelf: the person's copy wins
        JSONObject copy = new JSONObject(steel.toString());
        copy.put(TlLookFiles.REPLACES, "dark-steel");
        TextLookShelf.Item bSteel = new TextLookShelf.Item("asset:textlab/looks/dark-steel.text.json", "Dark Steel", "TextLab",
                TextLookShelf.From.BUILT_IN, steel, "textlab/looks/dark-steel.text.json");
        TextLookShelf.Item bBlaze = new TextLookShelf.Item("asset:textlab/looks/blaze.text.json", "Blaze", "TextLab",
                TextLookShelf.From.BUILT_IN, blaze, "textlab/looks/blaze.text.json");
        TextLookShelf.Item mine = new TextLookShelf.Item("folder:Supplies/Looks/Dark Steel.text.json", "Dark Steel", "My looks",
                TextLookShelf.From.MINE, copy, "Supplies/Looks/Dark Steel.text.json");
        TextLookShelf.Item other = new TextLookShelf.Item("folder:Supplies/Looks/HOT.text.json", "HOT", "My looks",
                TextLookShelf.From.MINE, blaze, "Supplies/Looks/HOT.text.json");
        List<TextLookShelf.Item> m = TlLookFiles.merged(Arrays.asList(bSteel, bBlaze), new ArrayList<>(Arrays.asList(other, mine)));
        check("the copy stands where the built-in was", m.size() == 3 && m.get(0) == mine && m.get(1) == bBlaze && m.get(2) == other,
                names(m));
        check("a copy of a built-in can go back to the original", TlLookFiles.isCopyOfBuiltIn(mine), "");
        check("another own look cannot", !TlLookFiles.isCopyOfBuiltIn(other), "");
        check("a look goes to Trash with its moving preview",
                TlLookFiles.withSheet("Supplies/Looks/HOT.text.json").equals(Arrays.asList("Supplies/Looks/HOT.text.json",
                        "Supplies/Looks/HOT.5x4.sprite.png")), TlLookFiles.withSheet("Supplies/Looks/HOT.text.json"));

        // ---- an export's frame: margins widened to the frame's shape, the floor kept under the words
        Class<?> lf = Class.forName("cc.joycreator.textlab.LookFrame");
        java.lang.reflect.Method framed = lf.getDeclaredMethod("framed", JSONObject.class, double[].class, double.class, double.class);
        framed.setAccessible(true);
        JSONObject wide = (JSONObject) framed.invoke(null, steel, new double[]{8, 2, 100}, 16.0 / 9, 0.3);
        JSONArray wm = wide.getJSONArray("margin");
        double addW = (2 * 16.0 / 9 - 8) / 2;   // negative: an 8 x 2 field is wider than 16:9, so height is added
        check("a field wider than the frame gets room above and below", wm.getDouble(1) > 1.0 && wm.getDouble(0) == 1.0, wm);
        JSONObject tall = (JSONObject) framed.invoke(null, steel, new double[]{8, 2, 100}, 9.0 / 16, 0.3);
        JSONArray tm = tall.getJSONArray("margin");
        double more = (8 / (9.0 / 16) - 2) / 2;
        check("a tall frame adds the same room top and bottom", Math.abs(tm.getDouble(1) - (1.0 + more)) < 1e-9
                && Math.abs(tm.getDouble(3) - (1.0 + more)) < 1e-9, tm);
        double fl = tall.getJSONObject("p").getDouble("floorLine");
        check("the floor stays the same distance under the words", Math.abs(fl * (2 + 2 * more) - (0.3 * 2 + more)) < 1e-9, fl);
        JSONObject sq = (JSONObject) framed.invoke(null, steel, new double[]{2, 4, 100}, 1.0, 0.3);
        JSONArray sm = sq.getJSONArray("margin");
        check("a field taller than square gets room at the sides", Math.abs(sm.getDouble(0) - 2.0) < 1e-9
                && Math.abs(sm.getDouble(2) - 2.0) < 1e-9, sm);
        check("room at the sides leaves the floor alone", sq.getJSONObject("p").getDouble("floorLine") == steel.getJSONObject("p").getDouble("floorLine"), "");
        check("the look itself is not changed by framing", steel.getJSONArray("margin").getDouble(1) == 1.0, "");
        if (addW > 99) System.out.println("unused");

        System.out.println();
        System.out.println(pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }

    static String look(Object step) throws Exception {
        java.lang.reflect.Field f = step.getClass().getDeclaredField("look");
        f.setAccessible(true);
        return (String) f.get(step);
    }

    static String names(List<TextLookShelf.Item> m) {
        StringBuilder b = new StringBuilder();
        for (TextLookShelf.Item it : m) b.append(it.name).append('/').append(it.from).append(' ');
        return b.toString();
    }
}
