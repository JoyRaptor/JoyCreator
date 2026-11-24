package cc.joycreator.avatar.intake;

import cc.joycreator.avatar.file.CharacterFile;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.Locale;
import java.util.Random;

/**
 * The "layered file" door (spec A4), first half: a Photoshop file's layer tree becomes a character's tree, with every
 * layer's picture inside the file, its place, whether it is shown, its opacity, and a role guessed from its name.
 * Rigging (angle folders into a turn set, arm fans, pins from the pixels) follows with the solver; until then the
 * character opens with its layers as they were, which is exactly what the Layers list and Parts row show.
 */
public final class PsdDoor {

    /** Turns one layer's straight RGBA into a {@code data:image/png;base64,...} text (the phone's bitmap code is behind it). */
    public interface PngEncoder {
        String encode(int w, int h, byte[] rgba);
    }

    /** What the file held, for the plain summary after it opens. */
    public static final class Summary {
        public int layers, groups, clipped, blended;
    }

    private final Random rnd = new Random();
    private PsdDoor() {}

    public static JSONObject build(PsdReader psd, String name, PngEncoder enc, Summary into) throws IOException, JSONException {
        return new PsdDoor().make(psd, name, enc, into == null ? new Summary() : into);
    }

    private JSONObject make(PsdReader psd, String name, PngEncoder enc, Summary sum) throws IOException, JSONException {
        JSONObject pictures = new JSONObject();
        JSONArray kids = new JSONArray();
        for (PsdReader.Node n : psd.tree) {
            JSONObject k = convert(psd, n, enc, pictures, sum);
            if (k != null) kids.put(k);
        }
        if (sum.layers == 0)
            throw new IOException("That file has no layers in it (it was saved flattened). Save it from the art program with its layers.");
        String title = name == null || name.isEmpty() ? "New character" : name;
        JSONObject root = new JSONObject().put("type", "group").put("id", id("n")).put("name", title).put("kids", kids);
        tag(root, null, null, true);
        return new JSONObject().put("format", CharacterFile.CHARACTER).put("name", title).put("w", psd.width).put("h", psd.height)
                .put("root", root).put("triggers", new JSONArray()).put("settings", new JSONObject().put("facing", "front"))
                .put("poses", new JSONArray()).put("palettes", new JSONArray()).put("pictures", pictures);
    }

    private JSONObject convert(PsdReader psd, PsdReader.Node L, PngEncoder enc, JSONObject pictures, Summary sum) throws IOException, JSONException {
        JSONObject n = new JSONObject().put("id", id("n")).put("name", RoleGuess.clean(L.name));
        if (!L.visible) n.put("hidden", true);
        if (L.opacity < 0.999) n.put("opacity", Math.round(L.opacity * 1000) / 1000.0);
        if (L.name != null && L.name.matches("^\\s*\\+.*")) n.put("free", true);
        if (L.group) {
            sum.groups++;
            JSONArray kids = new JSONArray();
            for (PsdReader.Node c : L.children) {
                JSONObject k = convert(psd, c, enc, pictures, sum);
                if (k != null) kids.put(k);
            }
            return n.put("type", "group").put("kids", kids);
        }
        if (L.w <= 0 || L.h <= 0) return null;
        if (L.clip) sum.clipped++;
        if (!L.blend.isEmpty() && !"norm".equals(L.blend) && !"pass".equals(L.blend)) sum.blended++;
        PsdReader.Image im = psd.decode(L);
        String img = id("i");
        pictures.put(img, enc.encode(im.w, im.h, im.rgba));
        sum.layers++;
        return n.put("type", "art").put("img", img).put("x", L.left).put("y", L.top);
    }

    /** Roles from names, passing the parent role and side down exactly as the pilot's tag pass does. */
    private static void tag(JSONObject n, String parentRole, String side, boolean isRoot) throws JSONException {
        if (!isRoot) {
            String r = RoleGuess.roleOf(n.optString("name"), parentRole, side);
            if (r != null) n.put("role", r);
        }
        String role = n.optString("role", "");
        String s2 = RoleGuess.sideOf(n.optString("name"));
        if (s2 == null) s2 = side;
        JSONArray kids = n.optJSONArray("kids");
        if (kids == null) return;
        String childParent = "mouth".equals(role) ? "mouth" : (role.startsWith("eye") ? role : (role.isEmpty() ? parentRole : role));
        String childSide = role.matches("^(eye|brow|arm|leg).*") ? (role.endsWith("R") ? "R" : "L") : s2;
        for (int i = 0; i < kids.length(); i++) tag(kids.getJSONObject(i), childParent, childSide, false);
    }

    private String id(String prefix) {
        return prefix + Integer.toString(rnd.nextInt(Integer.MAX_VALUE), 36).toLowerCase(Locale.ROOT);
    }
}
