package cc.joycreator.avatar.intake;

import cc.joycreator.avatar.file.LooseCharacter;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;
import java.util.Random;

/**
 * What the cut drawings become (the pilot's cutter, its "These are the ..." and three ways of grouping): separate drawings that
 * are each told what they are; ONE part that swaps (the first is its usual look, each of the others gets a button); or the frames
 * of a loop that plays through them. The structure half; the drawings arrive as PNG texts.
 */
public final class SheetParts {
    private SheetParts() {
    }

    /** The part a drawing is: the pilot's zones. key, label, role. */
    public static final String[][] ZONES = {
            {"head", "Head", "head"}, {"body", "Body", "torso"}, {"armL", "Left arm", "armL"}, {"armR", "Right arm", "armR"},
            {"legL", "Left leg", "legL"}, {"legR", "Right leg", "legR"}, {"tail", "Tail", "tail"}, {"extra", "Extras", "prop"}};

    public static final String PARTS = "parts", SWAP = "swap", LOOP = "loop";

    public static String label(String zone) {
        for (String[] z : ZONES) if (z[0].equals(zone)) return z[1];
        return "Extras";
    }

    public static String role(String zone) {
        for (String[] z : ZONES) if (z[0].equals(zone)) return z[2];
        return "prop";
    }

    private static String id(String p) {
        return p + Long.toString((long) (new Random().nextDouble() * 60466176L), 36);
    }

    /**
     * A new character from the cut drawings. {@code group}: {@link #PARTS} (each its own drawing, told what it is), {@link #SWAP} (one
     * part with several looks, a button for each look after the first) or {@link #LOOP} (one part that plays through them; frames must
     * already be in register, see {@code SheetCutter.align}).
     */
    public static JSONObject build(String name, List<LooseCharacter.Pic> pics, String zone, String group) throws JSONException {
        JSONObject doc = LooseCharacter.build(name, pics);
        JSONArray kids = doc.getJSONObject("root").getJSONArray("kids").getJSONObject(0).getJSONArray("kids");
        String role = role(zone), zl = label(zone);
        if (PARTS.equals(group) || pics.size() < 2) {
            for (int i = 0; i < kids.length(); i++) {
                JSONObject k = kids.getJSONObject(i);
                k.put("role", role).put("name", zl + (kids.length() > 1 ? " " + (i + 1) : ""));
            }
            return doc;
        }
        JSONObject first = kids.getJSONObject(0);
        JSONArray looks = new JSONArray();
        JSONArray triggers = doc.getJSONArray("triggers");
        boolean swap = SWAP.equals(group);
        for (int i = 0; i < kids.length(); i++) {
            JSONObject k = kids.getJSONObject(i);
            k.put("x", first.getDouble("x")).put("y", first.getDouble("y"));          // each look sits where the first does
            k.put("name", zl + " " + (i + 1));
            if (swap && i > 0) {                                                       // a button for each look after the first
                JSONObject t = new JSONObject().put("id", id("t")).put("name", zl + " " + (i + 1));
                triggers.put(t);
                k.put("trig", t.getString("id"));
            }
            looks.put(k);
        }
        JSONObject pick = swap ? new JSONObject().put("by", "default").put("rest", first.getString("id"))
                : new JSONObject().put("by", "cycle").put("fps", 12).put("rest", first.getString("id"));
        JSONObject set = new JSONObject().put("id", id("n")).put("name", zl).put("type", "set").put("role", role).put("pick", pick).put("kids", looks);
        doc.getJSONObject("root").getJSONArray("kids").getJSONObject(0).put("kids", new JSONArray().put(set));
        return doc;
    }
}
