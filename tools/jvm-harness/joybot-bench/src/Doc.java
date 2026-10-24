import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * A document (project or VisLab state) flattened to leaf paths, so "what changed" is a list of
 * paths a person can read: {@code timeline.textOverlays[t-title].colorInt}. Array items with an
 * "id" are named by it (so a deletion is one item gone, not every later index shifting); others by
 * position. Save stamps are left out: a re-save is not an edit.
 */
final class Doc {

    private static final java.util.Set<String> IGNORED = java.util.Set.of("lastModified", "modifiedAt", "savedAt");

    final Map<String, JsonElement> leaves = new LinkedHashMap<>();

    Doc(JsonElement root) { flatten("", root); }

    private void flatten(String path, JsonElement e) {
        if (e == null || e.isJsonNull()) { leaves.put(path, e); return; }
        if (e.isJsonObject()) {
            JsonObject o = e.getAsJsonObject();
            if (o.size() == 0) leaves.put(path, o);
            for (Map.Entry<String, JsonElement> m : o.entrySet()) {
                if (IGNORED.contains(m.getKey())) continue;
                flatten(path.isEmpty() ? m.getKey() : path + "." + m.getKey(), m.getValue());
            }
        } else if (e.isJsonArray()) {
            JsonArray a = e.getAsJsonArray();
            if (a.size() == 0) leaves.put(path, a);
            for (int i = 0; i < a.size(); i++) {
                JsonElement it = a.get(i);
                String key = String.valueOf(i);
                if (it.isJsonObject() && it.getAsJsonObject().has("id") && it.getAsJsonObject().get("id").isJsonPrimitive()) {
                    key = it.getAsJsonObject().get("id").getAsString();
                }
                flatten(path + "[" + key + "]", it);
            }
        } else {
            leaves.put(path, e);
        }
    }

    /** Leaf paths that differ (added, removed or changed). */
    static List<String> diff(Doc before, Doc after) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, JsonElement> b : before.leaves.entrySet()) {
            JsonElement a = after.leaves.get(b.getKey());
            if (a == null && !after.leaves.containsKey(b.getKey())) out.add(b.getKey());
            else if (!same(b.getValue(), a)) out.add(b.getKey());
        }
        for (String k : after.leaves.keySet()) if (!before.leaves.containsKey(k)) out.add(k);
        return out;
    }

    static boolean same(JsonElement a, JsonElement b) {
        if (a == null || a.isJsonNull()) return b == null || b.isJsonNull();
        if (b == null) return false;
        if (a.isJsonPrimitive() && b.isJsonPrimitive()) {
            JsonPrimitive x = a.getAsJsonPrimitive(), y = b.getAsJsonPrimitive();
            if (x.isNumber() && y.isNumber()) return Math.abs(x.getAsDouble() - y.getAsDouble()) < 1e-6;
        }
        return a.equals(b);
    }

    /** "timeline.textOverlays[*].text" → regex. {@code *} = within one part; {@code **} = anything. */
    static Pattern glob(String g) {
        StringBuilder r = new StringBuilder();
        for (int i = 0; i < g.length(); i++) {
            char c = g.charAt(i);
            if (c == '*' && i + 1 < g.length() && g.charAt(i + 1) == '*') { r.append(".*"); i++; }
            else if (c == '*') r.append("[^.\\[\\]]*");
            else if ("\\.[]{}()+?^$|".indexOf(c) >= 0) r.append('\\').append(c);
            else r.append(c);
        }
        return Pattern.compile(r.toString());
    }

    /** Leaves whose path matches the glob, or starts with a match (a whole item). */
    Map<String, JsonElement> find(String g) {
        Pattern p = glob(g);
        Pattern under = Pattern.compile(p.pattern() + "(\\..*|\\[.*)");
        Map<String, JsonElement> out = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> e : leaves.entrySet()) {
            if (p.matcher(e.getKey()).matches() || under.matcher(e.getKey()).matches()) out.put(e.getKey(), e.getValue());
        }
        return out;
    }

    static boolean covered(String path, List<String> globs) {
        for (String g : globs) {
            Pattern p = glob(g);
            if (p.matcher(path).matches()) return true;
            if (Pattern.compile(p.pattern() + "(\\..*|\\[.*)").matcher(path).matches()) return true;
        }
        return false;
    }
}
