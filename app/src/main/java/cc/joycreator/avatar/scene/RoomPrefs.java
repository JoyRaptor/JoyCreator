package cc.joycreator.avatar.scene;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * What the room remembers between visits about the frame and the strip (the pilot's {@code al.room} and {@code al.empty}):
 * whether the frame is shown, which parts of the strip are up (Scene, Character, Shots), Cut or Glide, the room round the frame; and
 * THE EMPTY SCENE's own frame (its shape, margins, and the shots kept for each character). The empty scene is no scene file at all:
 * the plain dark stage with the character you are on (AVA_FORMAT.md B3). Pure data; {@code ui/RoomPrefsStore} keeps it on the phone.
 */
public final class RoomPrefs {
    public boolean frame, sc, ch, sh, glide, air;
    public String emptyShape = "16:9", emptySafe = "off";
    /** Shots kept on the empty scene, per character key: [{id, name, cx, cy, k}] in character pixels. */
    public final JSONObject emptyShots = new JSONObject();

    /** The parts of the strip showing, in order. Shots need the frame. */
    public List<String> parts() {
        List<String> l = new ArrayList<>();
        if (sc) l.add("sc");
        if (ch) l.add("ch");
        if (sh && frame) l.add("sh");
        return l;
    }

    /** Turn the frame on or off. Off: there are no shots, so the Shots part folds away. */
    public void setFrame(boolean on) {
        frame = on;
        if (!on) sh = false;
    }

    /** Turn a part on or off ({@code sc}, {@code ch}, {@code sh}); Shots turn the frame on. */
    public void setPart(String key, boolean on) {
        if ("sc".equals(key)) sc = on;
        else if ("ch".equals(key)) ch = on;
        else if ("sh".equals(key)) {
            sh = on;
            if (on) frame = true;
        }
    }

    public JSONArray shotsOf(String characterKey) throws JSONException {
        JSONArray a = emptyShots.optJSONArray(characterKey);
        if (a == null) {
            a = new JSONArray();
            emptyShots.put(characterKey, a);
        }
        return a;
    }

    public JSONObject toJson() {
        try {
            return new JSONObject().put("frame", frame).put("sc", sc).put("ch", ch).put("sh", sh).put("glide", glide).put("air", air)
                    .put("emptyShape", emptyShape).put("emptySafe", emptySafe).put("emptyShots", emptyShots);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    public static RoomPrefs fromJson(String text) {
        RoomPrefs p = new RoomPrefs();
        try {
            JSONObject j = new JSONObject(text);
            p.frame = j.optBoolean("frame");
            p.sc = j.optBoolean("sc");
            p.ch = j.optBoolean("ch");
            p.sh = j.optBoolean("sh");
            p.glide = j.optBoolean("glide");
            p.air = j.optBoolean("air");
            p.emptyShape = j.optString("emptyShape", "16:9");
            p.emptySafe = j.optString("emptySafe", "off");
            JSONObject s = j.optJSONObject("emptyShots");
            if (s != null) for (java.util.Iterator<String> it = s.keys(); it.hasNext(); ) {
                String k = it.next();
                p.emptyShots.put(k, s.get(k));
            }
            if (!p.frame) p.sh = false;
        } catch (JSONException e) {
            return new RoomPrefs();                        // a damaged note is a fresh start, never an error to show
        }
        return p;
    }
}
