package cc.joycreator.avatar.scene;

import cc.joycreator.avatar.file.CharacterFile;
import cc.joycreator.avatar.file.FormatCheck;
import cc.joycreator.avatar.perform.Take;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reading and writing a scene file, {@code <Name>.scene.json} ({@code joycreator.scene/1}, AVA_FORMAT.md B3): the scene itself, the
 * takes of its characters, the pictures it carries, and (when it travels) every character inside it. Reading upgrades the carried
 * characters to the newest version and runs every rule of the format; a damaged file is reported in plain sentences and not opened.
 * The pilot reads and writes the very same file (tools/avatarlab, {@code scene.toJson} / {@code scene.open}).
 */
public final class SceneFile {
    private SceneFile() {
    }

    /** What a scene file holds, apart from the scene itself. */
    public static final class Parsed {
        public Scene scene;
        /** A take per cast item id. */
        public final Map<String, Take> takes = new LinkedHashMap<>();
        /** The characters that travel with it, by cast item id: whole character files. */
        public final Map<String, JSONObject> characters = new LinkedHashMap<>();
        /** Picture id to its data URL or path, as in a character file. */
        public final Map<String, String> pictures = new LinkedHashMap<>();
        /** What is wrong, in plain sentences (empty = it follows every rule). */
        public final List<String> problems = new ArrayList<>();
        public final List<String> upgraded = new ArrayList<>();

        public boolean ok() {
            return scene != null && problems.isEmpty();
        }
    }

    /** Read a scene file's text. Never throws for a damaged file: {@code problems} says what is wrong and {@code scene} is null. */
    public static Parsed read(String text) {
        Parsed p = new Parsed();
        try {
            JSONObject doc = new JSONObject(text);
            if (!CharacterFile.SCENE.equals(doc.optString("format", null))) {
                for (FormatCheck.Problem q : FormatCheck.check(doc)) p.problems.add(q.say);
                return p;
            }
            CharacterFile.Upgraded up = CharacterFile.upgradeScene(doc);
            p.upgraded.addAll(up.report);
            for (FormatCheck.Problem q : FormatCheck.check(up.doc)) p.problems.add(q.say);
            if (!p.problems.isEmpty()) return p;
            JSONObject d = up.doc;
            JSONObject takes = d.optJSONObject("takes"), chars = d.optJSONObject("characters"), pics = d.optJSONObject("pictures");
            d.remove("takes");
            d.remove("characters");
            d.remove("pictures");
            for (Iterator<String> it = keys(takes); it.hasNext(); ) {
                String k = it.next();
                JSONObject t = takes.optJSONObject(k);
                if (t != null) p.takes.put(k, Take.fromJson(t));
            }
            for (Iterator<String> it = keys(chars); it.hasNext(); ) {
                String k = it.next();
                JSONObject c = chars.optJSONObject(k);
                if (c != null) p.characters.put(k, c);
            }
            for (Iterator<String> it = keys(pics); it.hasNext(); ) {
                String k = it.next();
                p.pictures.put(k, pics.optString(k));
            }
            p.scene = new Scene(d);
        } catch (JSONException e) {
            p.problems.add("This is not a scene file: " + e.getMessage());
        }
        return p;
    }

    private static Iterator<String> keys(JSONObject o) {
        return o == null ? new ArrayList<String>().iterator() : o.keys();
    }

    /**
     * The scene as one document. {@code characters} null = the shelf's characters are named, not copied; otherwise each rides inside
     * (a file that can travel). Pictures are the ones the scene's things name that are not a set that comes with the room.
     */
    public static JSONObject write(Scene s, Map<String, Take> takes, Map<String, JSONObject> characters, Map<String, String> pictures) throws JSONException {
        JSONObject o = new JSONObject(s.text());
        if (takes != null) {
            JSONObject t = new JSONObject();
            for (Map.Entry<String, Take> e : takes.entrySet()) if (e.getValue() != null && e.getValue().n > 0 && s.item(e.getKey()) != null) t.put(e.getKey(), e.getValue().toJson());
            o.put("takes", t);
        }
        if (characters != null) {
            JSONObject c = new JSONObject();
            for (Map.Entry<String, JSONObject> e : characters.entrySet()) if (s.item(e.getKey()) != null) c.put(e.getKey(), e.getValue());
            o.put("characters", c);
        }
        if (pictures != null) {
            JSONObject pi = new JSONObject();
            for (Map.Entry<String, String> e : pictures.entrySet()) pi.put(e.getKey(), e.getValue());
            o.put("pictures", pi);
        }
        return o;
    }

    /** The readable file name: {@code <Name>.scene.json}, characters the file system dislikes taken out. */
    public static String fileName(String sceneName) {
        String n = sceneName == null ? "" : sceneName.replaceAll("[^\\w\\- ]+", "").trim();
        return (n.isEmpty() ? "scene" : n) + ".scene.json";
    }
}
