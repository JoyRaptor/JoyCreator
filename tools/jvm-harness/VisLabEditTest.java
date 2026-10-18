import com.fadcam.ui.faditor.ai.actions.ActionError;
import com.fadcam.ui.faditor.ai.actions.VisLabEdit;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Joybot's hands on a VisLab look (VisLabEdit): reading a look out, change lines, layer moves —
 * against VisLab's real types.json and built-in looks. Pure Java + Gson, off device.
 *
 * Run: bash tools/jvm-harness/run-vislab-edit.sh
 */
public class VisLabEditTest {

    static int pass = 0, fail = 0;

    static void check(String what, boolean ok, String detail) {
        if (ok) { pass++; System.out.println("PASS  " + what); }
        else { fail++; System.out.println("FAIL  " + what + "   " + detail); }
    }

    static void eq(String what, Object want, Object got) {
        check(what, String.valueOf(want).equals(String.valueOf(got)), "want=" + want + " got=" + got);
    }

    static String read(String p) throws Exception {
        return new String(Files.readAllBytes(new File(p).toPath()), StandardCharsets.UTF_8);
    }

    static String err(VisLabEdit e, JsonObject look, String line) {
        try {
            e.change(look, line);
            return "(no error)";
        } catch (ActionError x) {
            return x.getMessage();
        }
    }

    static JsonObject layer(JsonObject look, int i) {
        return look.getAsJsonArray("layers").get(i).getAsJsonObject();
    }

    public static void main(String[] a) throws Exception {
        String base = "app/src/main/assets/vislab/";
        VisLabEdit e = new VisLabEdit(read(base + "types.json"));
        check("types parsed", e.types.size() >= 20, "n=" + e.types.size());
        check("finish knobs", e.finish.knobs.size() >= 20, "n=" + e.finish.knobs.size());
        check("signals", e.signals.containsKey("kick") && "Low mids".equals(e.signals.get("low")), e.signals.toString());

        JsonObject look = JsonParser.parseString(read(base + "scenes/disco-ocean.vis.json")).getAsJsonObject();
        String d = e.describe(look, null);
        check("describe names the look", d.contains("“Disco Ocean” (disco-ocean)"), d);
        check("describe lists set knobs with music", d.contains("ocean = 0.9 + bass 0.15"), d);
        check("describe finish", d.contains("bloom = 1.1"), d);
        System.out.println("  (overview " + d.length() + " chars)\n" + d);

        String one = e.describe(look, "Pads");
        check("one layer shows every knob with range", one.contains("speed “") && one.contains(" … "), one);
        check("enum shows options", one.contains("Woven ribbons | Launchpad pads"), one);
        check("layer-level knobs listed", one.contains("opacity “Opacity”"), one);
        System.out.println("  (one layer " + one.length() + " chars)");

        // Plain number, by layer name and knob id
        String s = e.change(look, "Pads.speed = 0.8");
        check("set number", s.contains("0.5 → 0.8"), s);
        eq("stored", 0.8, layer(look, 1).getAsJsonObject("p").get("speed").getAsDouble());

        // Music kept when only the number changes
        e.change(look, "Pads.ocean = 1.2");
        eq("music kept on number change", "{\"v\":1.2,\"mod\":{\"bass\":0.15}}", layer(look, 1).getAsJsonObject("p").get("ocean"));
        // Add music without changing the number, signal by label, layer by number
        e.change(look, "2.ocean = + Kick 0.3");
        eq("music added by label, number kept", "{\"v\":1.2,\"mod\":{\"bass\":0.15,\"kick\":0.3}}",
                layer(look, 1).getAsJsonObject("p").get("ocean"));
        // Still
        e.change(look, "Pads.ocean = 0.9 still");
        eq("still clears music", "0.9", layer(look, 1).getAsJsonObject("p").get("ocean"));
        // Signal with spaces in label
        e.change(look, "Pads.glow = 0.5 + low mids 0.2");
        check("two-word signal label", layer(look, 1).getAsJsonObject("p").getAsJsonObject("glow")
                .getAsJsonObject("mod").has("low"), look.toString());

        // Enum by option name (partial)
        s = e.change(look, "Pads.kind = woven");
        eq("enum partial", 0, layer(look, 1).getAsJsonObject("p").get("kind").getAsInt());
        check("enum summary in words", s.contains("Launchpad pads → Woven ribbons"), s);
        // Knob by label, on the Finish pass
        e.change(look, "finish.Bloom = 1.5 + kick 0.4");
        check("finish by label with music", look.getAsJsonObject("post").getAsJsonObject("p").get("bloom").isJsonObject(),
                look.getAsJsonObject("post").toString());
        // Layer-level fields
        e.change(look, "Night sky.opacity = 0.5");
        e.change(look, "Night sky.on = off");
        eq("opacity on layer itself", 0.5, layer(look, 0).get("opacity").getAsDouble());
        eq("hidden is boolean", false, layer(look, 0).get("on").getAsBoolean());
        check("hidden shows in describe", e.describe(look, null).contains("(hidden)"), "");
        // Echo
        e.change(look, "echo.on = on");
        e.change(look, "echo.decay = 0.95");
        eq("echo on", true, look.getAsJsonObject("echo").get("on").getAsBoolean());
        // Look fields
        e.change(look, "look.background = #101010");
        e.change(look, "look.palette = #FF0000, #00FF00 #0000FF, #FFFFFF, #000000");
        eq("palette five", 5, look.getAsJsonArray("palette").size());
        e.change(look, "Pads.palette = #FF0000 #00FF00");
        eq("layer palette", 2, layer(look, 1).getAsJsonArray("palette").size());

        // What a weak model copies back from describe() (Note 9, 10-17: "Palette #00F0FF …" was refused)
        e.change(look, "Palette #00F0FF #7A5CFF #FF2BD6 #FF9A3C #FFE14D");
        eq("bare palette line", "#00F0FF", look.getAsJsonArray("palette").get(0).getAsString());
        e.change(look, "background: #000000");
        eq("colon + bare background", "#000000", look.get("background").getAsString());
        e.change(look, "colours = #111111 #222222");
        eq("colours means palette", 2, look.getAsJsonArray("palette").size());
        e.change(look, "Pads.speed: 0.6");
        eq("colon separator", 0.6, layer(look, 1).getAsJsonObject("p").get("speed").getAsDouble());
        s = e.change(look, "ocean = 1.1");
        check("knob without layer when only one layer has it", s.startsWith("“Pads”"), s);
        check("ambiguous knob without layer asks which", err(e, look, "brightness = 1").contains("Say which layer"),
                err(e, look, "brightness = 1"));
        String head = e.describe(look, null);
        check("describe prints palette as a change line", head.contains("look.palette = #111111 #222222"), head);
        e.change(look, head.split("\n")[1].split(";")[0]);
        check("a copied describe line is a valid change", true, "");

        // Errors say how to fix
        check("unknown knob lists knobs", err(e, look, "Pads.wobble = 1").contains("Its knobs:"), err(e, look, "Pads.wobble = 1"));
        check("unknown layer lists layers", err(e, look, "Lasers.speed = 1").contains("Layers: 1 "), err(e, look, "Lasers.speed = 1"));
        check("no equals", err(e, look, "Pads.speed 2").contains("needs an ="), "");
        check("bad enum lists options", err(e, look, "Pads.kind = sparkly").contains("Woven ribbons, Launchpad pads"),
                err(e, look, "Pads.kind = sparkly"));
        check("music on a choice refused", err(e, look, "Pads.kind = 1 + kick 0.2").contains("choice"), "");
        check("bad signal named", err(e, look, "Pads.speed = 1 + wub 0.2").contains("Signals:"), "");
        check("layer number out of range", err(e, look, "9.speed = 1").contains("no layer 9"), err(e, look, "9.speed = 1"));

        // Layers
        int n = look.getAsJsonArray("layers").size();
        s = e.addLayer(look, "Lasers", "Beams", null);
        eq("added on top", n + 1, look.getAsJsonArray("layers").size());
        check("add summary", s.contains("“Beams” (Lasers) as layer " + (n + 1)), s);
        e.moveLayer(look, "Beams to back");
        eq("moved to back", "lasers", layer(look, 0).get("type").getAsString());
        e.change(look, "Beams.sweep = 1");
        e.removeLayer(look, "1");
        eq("removed", n, look.getAsJsonArray("layers").size());
        check("bad type lists types", errType(e, look).contains("Types:"), errType(e, look));

        String types = e.describeTypes(null);
        check("types list", types.contains("floor “Woven floor”"), types);
        System.out.println("  (types " + types.length() + " chars)");
        check("one type's knobs", e.describeTypes("Outrun").contains("Knobs:"), "");

        System.out.println("\n" + pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }

    static String errType(VisLabEdit e, JsonObject look) {
        try {
            e.addLayer(look, "hologram", null, null);
            return "(no error)";
        } catch (ActionError x) {
            return x.getMessage();
        }
    }
}
