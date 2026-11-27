package cc.joycreator.avatar.intake;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * "Update the art" (spec A4): bring in the repainted layered file. Every layer that has the same name (and, when the
 * name is not enough, the same path through its groups) as a drawing of the character replaces that drawing's picture;
 * every pin and setting stays. New layers land in Extras. A bigger file of the same shape comes in as finer art: the
 * drawing keeps its place and its size on the character and carries more pixels ({@code fine}, AVA_FORMAT.md B2).
 */
public final class PsdUpdate {

    /** What happened, for one plain summary sentence. */
    public static final class Result {
        public int replaced, finer, added, left;
    }

    private PsdUpdate() {}

    public static Result apply(JSONObject doc, PsdReader psd, PsdDoor.PngEncoder enc) throws IOException, JSONException {
        JSONObject pictures = doc.getJSONObject("pictures");
        JSONObject fine = doc.optJSONObject("fine");
        if (fine == null) {
            fine = new JSONObject();
        }
        // the character's drawings by path, and by name
        List<JSONObject> arts = new ArrayList<>();
        Map<String, JSONObject> byPath = new HashMap<>();
        Map<String, List<JSONObject>> byName = new HashMap<>();
        collect(doc.getJSONObject("root"), "", true, arts, byPath, byName);
        List<PsdReader.Leaf> leaves = new ArrayList<>();
        for (PsdReader.Node n : psd.tree) PsdReader.leaves(n, leaves, true, 1);
        Result res = new Result();
        List<JSONObject> used = new ArrayList<>();
        JSONArray extras = null;
        for (PsdReader.Leaf lf : leaves) {
            PsdReader.Node L = lf.layer;
            JSONObject art = byPath.get(key(L.path));
            if (art == null || used.contains(art)) {
                List<JSONObject> same = byName.get(key(L.name));
                art = null;
                if (same != null) for (JSONObject a : same) if (!used.contains(a)) {
                    art = a;
                    break;
                }
            }
            PsdReader.Image im = psd.decode(L);
            String url = enc.encode(im.w, im.h, im.rgba);
            if (art == null) {                                               // a new layer: into Extras
                if (extras == null) extras = extrasKids(doc);
                String img = "i" + Integer.toString(new Random().nextInt(Integer.MAX_VALUE), 36) + res.added;
                pictures.put(img, url);
                extras.put(new JSONObject().put("type", "art").put("id", "n" + Integer.toString(new Random().nextInt(Integer.MAX_VALUE), 36))
                        .put("name", RoleGuess.clean(L.name)).put("x", L.left).put("y", L.top).put("img", img));
                res.added++;
                continue;
            }
            used.add(art);
            String oldImg = art.getString("img");
            int[] old = size(pictures.optString(oldImg, ""));
            int[] now = size(url);
            // the repainted picture gets a new id and the old one stays in the file, so Undo (which restores the tree) brings the old art back
            String img = "i" + Integer.toString(new Random().nextInt(Integer.MAX_VALUE), 36) + "u" + res.replaced + res.finer;
            pictures.put(img, url);
            art.put("img", img);
            boolean sameShape = old != null && now != null && Math.abs(old[0] * (double) now[1] - old[1] * (double) now[0]) <= 0.01 * old[0] * now[1];
            if (old != null && now != null && (old[0] != now[0] || old[1] != now[1]) && sameShape) {
                int[] shown = fine.has(oldImg) ? new int[]{fine.getJSONArray(oldImg).getInt(0), fine.getJSONArray(oldImg).getInt(1)} : old;
                fine.put(img, new JSONArray().put(shown[0]).put(shown[1]));            // finer art: the same size on the character
                res.finer++;
            } else {
                art.put("x", L.left).put("y", L.top);
                res.replaced++;
            }
        }
        res.left = arts.size() - used.size();
        if (fine.length() > 0) doc.put("fine", fine);
        return res;
    }

    private static JSONArray extrasKids(JSONObject doc) throws JSONException {
        JSONObject root = doc.getJSONObject("root");
        JSONArray kids = root.getJSONArray("kids");
        for (int i = 0; i < kids.length(); i++) {
            JSONObject k = kids.getJSONObject(i);
            if ("Extras".equals(k.optString("name")) && "group".equals(k.optString("type"))) return k.getJSONArray("kids");
        }
        JSONObject g = new JSONObject().put("type", "group").put("id", "extras" + Integer.toString(new Random().nextInt(Integer.MAX_VALUE), 36)).put("name", "Extras").put("kids", new JSONArray());
        kids.put(g);
        return g.getJSONArray("kids");
    }

    private static void collect(JSONObject n, String path, boolean isRoot, List<JSONObject> arts, Map<String, JSONObject> byPath, Map<String, List<JSONObject>> byName) {
        String name = n.optString("name", "");
        String here = isRoot ? "" : (path.isEmpty() ? name : path + "/" + name);
        if ("art".equals(n.optString("type")) && n.has("img")) {
            arts.add(n);
            if (!byPath.containsKey(key(here))) byPath.put(key(here), n);
            List<JSONObject> l = byName.get(key(name));
            if (l == null) {
                l = new ArrayList<>();
                byName.put(key(name), l);
            }
            l.add(n);
        }
        JSONArray kids = n.optJSONArray("kids");
        for (int i = 0; kids != null && i < kids.length(); i++) {
            JSONObject k = kids.optJSONObject(i);
            if (k != null) collect(k, here, false, arts, byPath, byName);
        }
    }

    private static String key(String s) {
        return RoleGuess.clean(s).toLowerCase(Locale.ROOT);
    }

    /** The width and height of a {@code data:image/png;base64,...} picture, read from its first bytes; null when it is not one. */
    public static int[] size(String dataUrl) {
        try {
            int comma = dataUrl.indexOf(',');
            if (comma < 0) return null;
            String b64 = dataUrl.substring(comma + 1, Math.min(dataUrl.length(), comma + 1 + 48));
            byte[] b = decode64(b64.substring(0, b64.length() - b64.length() % 4));
            if (b.length < 24 || b[1] != 'P' || b[2] != 'N' || b[3] != 'G') return null;
            int w = ((b[16] & 255) << 24) | ((b[17] & 255) << 16) | ((b[18] & 255) << 8) | (b[19] & 255);
            int h = ((b[20] & 255) << 24) | ((b[21] & 255) << 16) | ((b[22] & 255) << 8) | (b[23] & 255);
            return new int[]{w, h};
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** A few bytes of base64, read by hand (java.util.Base64 needs a newer Android than this app supports). */
    private static byte[] decode64(String s) {
        String abc = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        int acc = 0, bits = 0;
        for (int i = 0; i < s.length(); i++) {
            int v = abc.indexOf(s.charAt(i));
            if (v < 0) break;
            acc = (acc << 6) | v;
            bits += 6;
            if (bits >= 8) {
                bits -= 8;
                out.write((acc >> bits) & 255);
            }
        }
        return out.toByteArray();
    }
}
