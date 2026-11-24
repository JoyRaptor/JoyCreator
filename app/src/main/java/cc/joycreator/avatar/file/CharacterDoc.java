package cc.joycreator.avatar.file;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A character file read into a tree (format {@code joycreator.character/1} or {@code /2}, tasks/avatar/AVA_FORMAT.md).
 *
 * <p>Only what the room needs to find and draw things is lifted into fields; every node keeps its whole JSON object
 * ({@link Node#json}) so behaviour records this class does not know are never lost (format rule 2: a reader keeps
 * what it does not know). The checks of the format (74 of them) are {@code FormatCheck}'s, not this class's.</p>
 */
public final class CharacterDoc {

    /** One node of the tree: group, set, art or mirror. */
    public static final class Node {
        public final JSONObject json;
        public final String type, id, name, role, img, pickBy;
        public final float x, y;
        public final boolean hidden;
        public final List<Node> kids = new ArrayList<>();

        Node(JSONObject j) {
            json = j;
            type = j.optString("type", "group");
            id = j.optString("id", "");
            name = j.optString("name", "");
            role = j.optString("role", "");
            img = j.optString("img", "");
            x = (float) j.optDouble("x", 0);
            y = (float) j.optDouble("y", 0);
            hidden = j.optBoolean("hidden", false);
            JSONObject pick = j.optJSONObject("pick");
            pickBy = pick == null ? "" : pick.optString("by", "");
        }
    }

    public final JSONObject json;
    public final String format, name;
    public final int w, h;
    public final Node root;
    /** Picture id to its text ("data:image/png;base64,..." or a path beside the file). */
    public final Map<String, String> pictures = new LinkedHashMap<>();

    private CharacterDoc(JSONObject j) throws JSONException {
        json = j;
        format = j.optString("format", "");
        name = j.optString("name", "");
        w = j.optInt("w", 0);
        h = j.optInt("h", 0);
        JSONObject r = j.optJSONObject("root");
        if (r == null) throw new JSONException("no root");
        root = readNode(r, 0);
        JSONObject pics = j.optJSONObject("pictures");
        if (pics != null) {
            for (Iterator<String> it = pics.keys(); it.hasNext(); ) {
                String k = it.next();
                pictures.put(k, pics.optString(k, ""));
            }
        }
    }

    private static Node readNode(JSONObject j, int depth) throws JSONException {
        if (depth > 40) throw new JSONException("tangled tree");
        Node n = new Node(j);
        JSONArray kids = j.optJSONArray("kids");
        if (kids != null) {
            for (int i = 0; i < kids.length(); i++) {
                JSONObject k = kids.optJSONObject(i);
                if (k != null) n.kids.add(readNode(k, depth + 1));
            }
        }
        return n;
    }

    /** Reads a character; throws {@link JSONException} for anything that is not one (the caller says one plain sentence). */
    public static CharacterDoc parse(String text) throws JSONException {
        JSONObject j = new JSONObject(text);
        String f = j.optString("format", "");
        if (!f.startsWith("joycreator.character/")) throw new JSONException("not a character");
        return new CharacterDoc(CharacterFile.upgrade(j).doc);   // an older file is read as the newest, in memory
    }
}
