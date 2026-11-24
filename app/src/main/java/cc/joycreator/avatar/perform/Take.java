package cc.joycreator.avatar.perform;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A take: signals per frame (AVA_FORMAT.md B4), never pixels, so a replay is the same character solved again.
 * {@code ch} holds one lane per signal: a number, a mouth shape's name, a 0/1 step, or null (not driven); a lane shorter
 * than {@code n} holds its last value. The voice kept with a take is {@code TakeVoice}'s, not this class's.
 */
public final class Take {
    public static final int FPS = 30;

    public int fps = FPS;
    public int n;
    public final Map<String, List<Object>> ch = new LinkedHashMap<>();

    public Take copy() {
        Take t = new Take();
        t.fps = fps;
        t.n = n;
        for (Map.Entry<String, List<Object>> e : ch.entrySet()) t.ch.put(e.getKey(), new ArrayList<>(e.getValue()));
        return t;
    }

    public double seconds() {
        return n / (double) fps;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject lanes = new JSONObject();
        for (Map.Entry<String, List<Object>> e : ch.entrySet()) {
            JSONArray a = new JSONArray();
            for (Object v : e.getValue()) a.put(v == null ? JSONObject.NULL : v);
            lanes.put(e.getKey(), a);
        }
        return new JSONObject().put("fps", fps).put("n", n).put("ch", lanes);
    }

    public static Take fromJson(JSONObject j) {
        Take t = new Take();
        t.fps = j.optInt("fps", FPS);
        t.n = j.optInt("n", 0);
        JSONObject lanes = j.optJSONObject("ch");
        if (lanes != null) {
            for (java.util.Iterator<String> it = lanes.keys(); it.hasNext(); ) {
                String k = it.next();
                JSONArray a = lanes.optJSONArray(k);
                if (a == null) continue;
                List<Object> l = new ArrayList<>();
                for (int i = 0; i < a.length(); i++) {
                    Object v = a.opt(i);
                    l.add(v == JSONObject.NULL ? null : v instanceof Number ? (Object) ((Number) v).doubleValue() : v);   // numbers read back as numbers, always the same kind
                }
                t.ch.put(k, l);
            }
        }
        return t;
    }

    String toJsonSafe() {
        try {
            return toJson().toString();
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    static Take fromJsonSafe(String text) {
        try {
            return fromJson(new JSONObject(text));
        } catch (JSONException e) {
            return null;
        }
    }

    /** True when both takes hold the same lanes, sample for sample. */
    public boolean sameAs(Take o) {
        if (o == null || n != o.n || fps != o.fps || !ch.keySet().equals(o.ch.keySet())) return false;
        for (String k : ch.keySet()) {
            List<Object> a = ch.get(k), b = o.ch.get(k);
            if (a.size() != b.size()) return false;
            for (int i = 0; i < a.size(); i++) {
                Object x = a.get(i), y = b.get(i);
                if (x == null ? y != null : !x.equals(y)) return false;
            }
        }
        return true;
    }
}
