package cc.joycreator.avatar.intake;

import cc.joycreator.avatar.file.CharacterFile;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;
import java.util.Random;

/**
 * The "Pin one whole drawing" door (the pilot's intake.wholeFrom and pin wizard): a finished drawing in one piece becomes a
 * character whose body is that drawing, with pins on the head, hands, hips, feet and tail so it bends. The data half; the
 * wizard that asks for each pin is {@code ui/PinWizard}.
 */
public final class WholeDrawing {
    private WholeDrawing() {
    }

    /** The wizard's steps: role, the word on the Skip button, and what to tap. */
    public static final String[][] STEPS = {
            {"head", "Head", "the middle of the head"},
            {"handL", "Left hand", "its left hand (on your right if it faces you)"},
            {"handR", "Right hand", "its right hand"},
            {"hips", "Hips", "the hips. This pin holds the body still"},
            {"footL", "Left foot", "its left foot"},
            {"footR", "Right foot", "its right foot"},
            {"tail", "Tail tip", "the tip of the tail, if it has one"}};

    /** One pin the wizard collected: its step, and where on the drawing (the drawing's own pixels). */
    public static final class Pin {
        public final String role, name;
        public final double lx, ly;

        public Pin(String role, String name, double lx, double ly) {
            this.role = role;
            this.name = name;
            this.lx = lx;
            this.ly = ly;
        }
    }

    private static String id(String p) {
        return p + Long.toString((long) (new Random().nextDouble() * 60466176L), 36);
    }

    /** A new character with the drawing as its body, no pins yet (they are added by {@link #pin}). {@code dataUrl}: the drawing as a PNG. */
    public static JSONObject build(String name, int w, int h, String dataUrl) throws JSONException {
        int cw = (int) Math.round(w * 1.7), ch = (int) Math.round(h * 1.35);
        String img = id("i");
        JSONObject art = new JSONObject().put("id", id("n")).put("name", name).put("type", "art").put("role", "torso").put("img", img)
                .put("x", Math.round((cw - w) / 2.0)).put("y", Math.round((ch - h) / 2.0)).put("mesh", new JSONObject().put("pins", new JSONArray()));
        JSONObject root = new JSONObject().put("id", id("r")).put("name", name).put("type", "group").put("kids", new JSONArray().put(art));
        JSONObject settings = new JSONObject().put("facing", "front").put("autoFlip", false).put("flipAt", 0.4).put("blinkAuto", true).put("blinkEvery", 3.6)
                .put("breathe", false).put("breathRate", 0.28).put("idle", false).put("eyesLead", true);
        return new JSONObject().put("format", CharacterFile.CHARACTER).put("name", name).put("w", cw).put("h", ch).put("root", root)
                .put("triggers", new JSONArray()).put("settings", settings).put("poses", new JSONArray()).put("palettes", new JSONArray())
                .put("pictures", new JSONObject().put(img, dataUrl));
    }

    /** The drawing's own node. */
    public static JSONObject art(JSONObject doc) {
        return doc.optJSONObject("root").optJSONArray("kids").optJSONObject(0);
    }

    /** Put the wizard's pins on the drawing (replacing any): each lands where it was tapped on the picture. */
    public static void pin(JSONObject doc, List<Pin> pins) throws JSONException {
        JSONObject art = art(doc);
        JSONArray out = new JSONArray();
        for (Pin p : pins)
            out.put(new JSONObject().put("id", id("p")).put("name", p.name).put("role", p.role).put("x", art.optDouble("x") + p.lx).put("y", art.optDouble("y") + p.ly));
        art.put("mesh", new JSONObject().put("pins", out));
    }
}
