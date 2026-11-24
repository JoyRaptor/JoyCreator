package cc.joycreator.avatar.file;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;
import java.util.Random;

/**
 * The "separate pictures" door (spec A4): a new character whose layers are the pictures the person picked, each one
 * landing in Extras on a tidy grid, ready to be told what it is. Pure JSON, so the same file opens in the pilot.
 */
public final class LooseCharacter {
    /** One picture: its name, its size, and the picture itself as a {@code data:image/png;base64,...} text. */
    public static final class Pic {
        public final String name, dataUrl;
        public final int w, h;

        public Pic(String name, int w, int h, String dataUrl) {
            this.name = name;
            this.w = w;
            this.h = h;
            this.dataUrl = dataUrl;
        }
    }

    private static final int CELL = 256, COLUMNS = 4;

    private LooseCharacter() {}

    public static JSONObject build(String name, List<Pic> pics) throws JSONException {
        Random r = new Random();
        JSONObject doc = new JSONObject();
        JSONArray extras = new JSONArray();
        JSONObject pictures = new JSONObject();
        int rows = Math.max(1, (pics.size() + COLUMNS - 1) / COLUMNS);
        for (int i = 0; i < pics.size(); i++) {
            Pic p = pics.get(i);
            String imgId = "i" + Integer.toString(r.nextInt(Integer.MAX_VALUE), 36) + i;
            pictures.put(imgId, p.dataUrl);
            int col = i % COLUMNS, row = i / COLUMNS;
            // centred in its cell; a picture larger than the cell keeps its top left on the cell
            double x = col * CELL + Math.max(0, (CELL - p.w) / 2.0), y = row * CELL + Math.max(0, (CELL - p.h) / 2.0);
            extras.put(new JSONObject().put("type", "art").put("id", "a" + i + Integer.toString(r.nextInt(46656), 36))
                    .put("name", p.name).put("x", x).put("y", y).put("img", imgId));
        }
        JSONObject extrasGroup = new JSONObject().put("type", "group").put("id", "extras").put("name", "Extras").put("kids", extras);
        JSONObject root = new JSONObject().put("type", "group").put("id", "root").put("name", name)
                .put("kids", new JSONArray().put(extrasGroup));
        doc.put("format", CharacterFile.CHARACTER).put("name", name).put("w", COLUMNS * CELL).put("h", rows * CELL)
                .put("root", root).put("triggers", new JSONArray()).put("settings", new JSONObject().put("facing", "front"))
                .put("poses", new JSONArray()).put("palettes", new JSONArray()).put("pictures", pictures);
        return doc;
    }
}
