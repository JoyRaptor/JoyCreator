import com.fadcam.vislab.audio.Features;
import com.fadcam.vislab.audio.Signals;
import com.fadcam.vislab.model.CompiledScene;
import com.fadcam.vislab.model.KnobSet;
import com.fadcam.vislab.model.VisKnob;
import com.fadcam.vislab.model.VisScene;
import com.fadcam.vislab.model.VisType;
import com.fadcam.vislab.model.VisTypes;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Set;

/**
 * Every built-in look must load, compile and point only at knobs, types and shaders that
 * exist; the knob maths must match the pilot's evalKnob; slots must not collide.
 */
public class VisLooksTest {

    static int fails = 0;

    static void check(boolean ok, String what) {
        if (!ok) {
            fails++;
            System.out.println("  FAIL " + what);
        }
    }

    static String read(String p) throws Exception {
        return new String(Files.readAllBytes(Paths.get(p)), StandardCharsets.UTF_8);
    }

    public static void main(String[] args) throws Exception {
        String base = "app/src/main/assets/vislab/";
        VisTypes types = VisTypes.parse(read(base + "types.json"));
        check(types.types.size() >= 24, "types.json has the primitives (" + types.types.size() + ")");

        // 1. Every shader a type names exists; no two knobs of one type share a slot.
        Set<String> shaders = new HashSet<>();
        for (File f : new File(base + "shaders").listFiles()) shaders.add(f.getName());
        for (VisType t : types.types.values()) {
            for (String s : t.shaderFiles()) check(shaders.contains(s), t.id + " shader " + s);
            Set<Integer> slots = new HashSet<>();
            for (VisKnob k : t.knobs) {
                if (k.slot < 0) continue;
                check(slots.add(k.slot), t.id + " slot collision at knob " + k.id);
                check(k.min <= k.def && k.def <= k.max || k.isChoice(), t.id + "." + k.id + " default inside its range");
            }
            for (Object inst : t.instances) {
                if (inst instanceof String) check(t.indexOf((String) inst) >= 0, t.id + " instance knob " + inst);
            }
        }

        // 2. Every built-in look loads, every layer type and knob name it uses exists.
        JsonObject index = JsonParser.parseString(read(base + "scenes/index.json")).getAsJsonObject();
        int looks = 0;
        Signals sig = new Signals();
        Features f = new Features();
        f.bass = 0.8f; f.kick = 1f;
        sig.set(f, 1.0);
        for (com.google.gson.JsonElement e : index.getAsJsonArray("scenes")) {
            String file = e.getAsString();
            VisScene s = VisScene.parse(read(base + "scenes/" + file));
            check(s.id().length() > 0 && file.startsWith(s.id()), file + " id matches its file name");
            CompiledScene cs = new CompiledScene(s, types, null);
            check(cs.layers.size() == s.layers().size(), file + " every layer has a known type");
            for (com.google.gson.JsonElement le : s.layers()) {
                JsonObject lo = le.getAsJsonObject();
                VisType t = types.type(lo.get("type").getAsString());
                if (t == null) continue;
                if (lo.has("p")) for (String k : lo.getAsJsonObject("p").keySet()) check(t.indexOf(k) >= 0, file + " " + t.id + " knob '" + k + "'");
            }
            if (s.json().has("post") && s.json().getAsJsonObject("post").has("p"))
                for (String k : s.json().getAsJsonObject("post").getAsJsonObject("p").keySet()) check(types.post.indexOf(k) >= 0, file + " post knob '" + k + "'");
            if (s.json().has("echo") && s.json().getAsJsonObject("echo").has("p"))
                for (String k : s.json().getAsJsonObject("echo").getAsJsonObject("p").keySet()) check(types.echo.indexOf(k) >= 0, file + " echo knob '" + k + "'");
            for (CompiledScene.Layer L : cs.layers) {
                L.eval(sig);
                for (float v : L.knobs.params) check(!Float.isNaN(v), file + " NaN knob");
            }
            check(s.palette() != null, file + " has a palette");
            // A shipped look must need no fixing: otherwise its copy in the folder reads as
            // "customised" forever and never follows app updates.
            java.util.List<String> fixes = com.fadcam.vislab.model.LookValidator.fix(s.json().deepCopy(), types);
            check(fixes.isEmpty(), file + " needs no fixing " + fixes);
            // Round trip keeps the tree.
            check(VisScene.parse(s.toJson()).toJson().equals(s.toJson()), file + " round trip");
            looks++;
        }
        check(looks >= 20, "built-in looks (" + looks + ")");

        // 3. Knob maths = the pilot's evalKnob.
        VisType spec = types.type("spectrum");
        JsonObject raw = JsonParser.parseString("{\"height\": {\"v\": 0.5, \"mod\": {\"kick\": 0.25, \"nope\": 3}}, \"count\": 32}").getAsJsonObject();
        KnobSet ks = KnobSet.compile(spec.knobs, raw);
        ks.eval(sig);
        VisKnob h = spec.knob("height");
        float want = 0.5f + 1f * 0.25f * (h.max - h.min);
        check(Math.abs(ks.value(spec, "height", -1) - want) < 1e-5, "height = base + kick x amount x range (" + ks.value(spec, "height", -1) + " vs " + want + ")");
        check(ks.params[h.slot] == ks.value(spec, "height", -1), "height lands in its slot");
        check(ks.value(spec, "count", -1) == 32f, "plain number knob");
        check(ks.value(spec, "glow", -1) == spec.knob("glow").def, "missing knob = default");
        ks.eval(null);
        check(ks.value(spec, "height", -1) == 0.5f, "no music = base only");
        JsonObject wild = JsonParser.parseString("{\"height\": {\"v\": 0.5, \"mod\": {\"kick\": 50}}}").getAsJsonObject();
        KnobSet kw = KnobSet.compile(spec.knobs, wild);
        kw.eval(sig);
        check(kw.value(spec, "height", -1) <= h.max + (h.max - h.min) + 1e-5, "music overshoot capped at one range");
        check(VisKnob.parseSlot("10x") == 40 && VisKnob.parseSlot("3w") == 15 && VisKnob.parseSlot("12x") == -1 && VisKnob.parseSlot(null) == -1, "slot parsing");

        // 4. A messy look (as Joybot or a hand edit might send) is fixed, never fatal.
        JsonObject messy = JsonParser.parseString("{\"id\":\"x\",\"palette\":[\"#ff0000\",\"nope\"],\"background\":\"blue\","
                + "\"layers\":[{\"type\":\"spectrum\",\"opacity\":3,\"p\":{\"height\":99,\"mode\":2.6,\"bogus\":1,"
                + "\"glow\":{\"v\":0.5,\"mod\":{\"kick\":9,\"vibes\":1}}}},{\"type\":\"warp-drive\"},7],"
                + "\"post\":{\"p\":{\"bloom\":\"lots\"}}}").getAsJsonObject();
        java.util.List<String> notes = com.fadcam.vislab.model.LookValidator.fix(messy, types);
        JsonObject sp = messy.getAsJsonArray("layers").get(0).getAsJsonObject();
        // What it does not know it keeps (a newer Joy Creator wrote it); broken things it fixes.
        check(messy.getAsJsonArray("layers").size() == 2, "non-object layer removed, unknown type KEPT");
        check(messy.getAsJsonArray("layers").get(1).getAsJsonObject().get("type").getAsString().equals("warp-drive"), "the unknown layer is untouched");
        check(sp.get("opacity").getAsFloat() == 1f, "opacity clamped to 1");
        check(sp.getAsJsonObject("p").get("height").getAsFloat() == spec.knob("height").max, "height clamped to max");
        check(sp.getAsJsonObject("p").get("mode").getAsFloat() == 3f, "choice rounded to an option");
        check(sp.getAsJsonObject("p").has("bogus"), "unknown knob kept");
        JsonObject gm = sp.getAsJsonObject("p").getAsJsonObject("glow").getAsJsonObject("mod");
        check(gm.get("kick").getAsFloat() == 2f && gm.has("vibes"), "music link capped; unknown signal kept");
        check(messy.get("background").isJsonNull(), "bad background becomes see-through");
        check(messy.getAsJsonArray("palette").get(1).getAsString().equals("#808080"), "bad palette colour becomes grey");
        check(!messy.getAsJsonObject("post").getAsJsonObject("p").has("bloom"), "non-number knob back to default");
        check(notes.size() >= 8, "every fix is reported (" + notes.size() + ")");
        CompiledScene fixed = new CompiledScene(new VisScene(messy), types, null);
        check(fixed.layers.size() == 1, "fixed look compiles");

        // 4b. A words layer saved with the old single "mode" scene becomes path + behaviour + letters.
        JsonObject oldWords = JsonParser.parseString("{\"id\":\"old\",\"layers\":[{\"type\":\"words\",\"p\":{\"mode\":10,\"size\":0.2}}]}").getAsJsonObject();
        com.fadcam.vislab.model.LookValidator.fix(oldWords, types);
        JsonObject wp = oldWords.getAsJsonArray("layers").get(0).getAsJsonObject().getAsJsonObject("p");
        check(!wp.has("mode") && wp.get("path").getAsInt() == 7 && wp.get("behaviour").getAsInt() == 4 && wp.get("letters").getAsInt() == 4,
                "old fireworks scene -> scatter + burst + sparks (" + wp + ")");
        check(Math.abs(wp.get("size").getAsFloat() - 0.26f) < 1e-4, "old fireworks size kept (1.3x)");

        // 5. Colour: sRGB hex to linear.
        float[] c = VisScene.hexToLinear("#808080", new float[3], 0);
        check(Math.abs(c[0] - 0.2158605f) < 1e-4, "srgb 128 -> linear 0.2159");
        check(VisScene.hexToLinear("#F00", new float[3], 0)[0] == 1f, "short hex");
        check(VisScene.hexToLinear("not a colour", new float[3], 0)[1] == 0f, "bad hex reads as black");

        // 6. The controls every layer has (types.json "layer"): Reactivity scales the music links,
        // Place reads back, out-of-range values are clamped.
        JsonObject lay = JsonParser.parseString("{\"id\":\"y\",\"layers\":["
                + "{\"type\":\"spectrum\",\"react\":0,\"turn\":30,\"size\":0.5,\"p\":{\"height\":{\"v\":0.5,\"mod\":{\"kick\":0.25}}}},"
                + "{\"type\":\"spectrum\",\"react\":2,\"p\":{\"height\":{\"v\":0.5,\"mod\":{\"kick\":0.25}}}},"
                + "{\"type\":\"spectrum\",\"opacity\":0.4,\"soft\":7,\"turn\":{\"v\":10,\"mod\":{\"kick\":0.5}}}]}").getAsJsonObject();
        java.util.List<String> layFix = com.fadcam.vislab.model.LookValidator.fix(lay, types);
        check(layFix.size() == 1 && lay.getAsJsonArray("layers").get(2).getAsJsonObject().get("soft").getAsFloat() == 1f,
                "softness 7 clamped to 1, nothing else touched " + layFix);
        CompiledScene lc = new CompiledScene(new VisScene(lay), types, null);
        for (CompiledScene.Layer L : lc.layers) L.eval(sig);
        check(lc.layers.get(0).knobs.value(spec, "height", -1) == 0.5f, "Reactivity 0: the music link is still");
        float doubled = 0.5f + 2f * 1f * 0.25f * (h.max - h.min);
        check(Math.abs(lc.layers.get(1).knobs.value(spec, "height", -1) - doubled) < 1e-5,
                "Reactivity 2: the music link doubled (" + lc.layers.get(1).knobs.value(spec, "height", -1) + " vs " + doubled + ")");
        check(lc.layers.get(0).turn() == 30f && lc.layers.get(0).size() == 0.5f && lc.layers.get(0).x() == 0f, "Place reads back");
        check(lc.layers.get(1).size() == 1f && lc.layers.get(1).turn() == 0f && lc.layers.get(1).soft() == 0f, "untouched layer sits as drawn");
        check(lc.layers.get(2).opacity() == 0.4f, "opacity still read");
        check(lc.layers.get(2).turn() > 10f, "a layer's own Turn can follow the music (" + lc.layers.get(2).turn() + ")");

        // 7. A speed with a music link ("rate"): the shader's TIME x slot is the distance travelled,
        // so a kick speeds it up for the kick, it never jumps by the whole elapsed time.
        VisType words = types.type("words");
        check(words.knob("spin").rate && words.knob("hueSpin").rate && !words.knob("size").rate, "speed knobs are marked rate");
        JsonObject spun = JsonParser.parseString("{\"spin\": {\"v\": 0.05, \"mod\": {\"kick\": 0.03}}}").getAsJsonObject();
        KnobSet sk = KnobSet.compile(words.knobs, spun);
        int spinSlot = words.knob("spin").slot;
        Features calm = new Features();
        Signals s0 = new Signals();
        s0.set(calm, 100.0);
        sk.eval(s0);
        check(Math.abs(100.0 * sk.params[spinSlot] - 5.0) < 1e-3, "starts as if it always ran at its own speed (" + 100.0 * sk.params[spinSlot] + ")");
        Signals s1 = new Signals();
        s1.set(f, 100.1);   // a kick for a tenth of a second
        sk.eval(s1);
        float kickSpeed = 0.05f + 1f * 0.03f * words.knob("spin").range();
        double want7 = 5.0 + kickSpeed * 0.1;
        check(Math.abs(100.1 * sk.params[spinSlot] - want7) < 1e-3, "a kick adds speed x its length, not x the time since the start ("
                + 100.1 * sk.params[spinSlot] + " vs " + want7 + ")");
        check(sk.values[words.indexOf("spin")] == kickSpeed, "editors still see the speed itself");
        KnobSet sk2 = KnobSet.compile(words.knobs, spun);
        sk2.carryFrom(sk);
        sk2.eval(s1);
        check(Math.abs(sk2.params[spinSlot] - sk.params[spinSlot]) < 1e-6, "an edit carries the distance on, no jump");

        System.out.println("VisLooksTest: " + types.types.size() + " primitives, " + looks + " looks, "
                + (fails == 0 ? "PASS" : fails + " FAIL"));
        if (fails > 0) System.exit(1);
    }
}
