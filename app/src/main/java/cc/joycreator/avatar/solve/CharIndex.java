package cc.joycreator.avatar.solve;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The character's tree, indexed: id to node and id to parent ({@code AL.ix}), each node's children as a list, and the rest
 * bounds of everything under a node ({@code AL.boundsOf}). Built once per character; build it again after the tree changes.
 */
final class CharIndex {
    final JSONObject root;
    final Map<String, JSONObject> node = new HashMap<>();
    final Map<String, JSONObject> parent = new HashMap<>();
    private final Map<JSONObject, List<JSONObject>> kids = new HashMap<>();
    private final Pics pics;

    CharIndex(JSONObject root, Pics pics) {
        this.root = root;
        this.pics = pics;
        index(root, null, 0);
    }

    private void index(JSONObject n, JSONObject par, int depth) {
        if (depth > 40) return;
        String id = n.optString("id", "");
        node.put(id, n);
        parent.put(id, par);
        List<JSONObject> list = new ArrayList<>();
        JSONArray a = n.optJSONArray("kids");
        if (a != null) for (int i = 0; i < a.length(); i++) {
            JSONObject k = a.optJSONObject(i);
            if (k == null) continue;
            list.add(k);
            index(k, n, depth + 1);
        }
        kids.put(n, list);
    }

    List<JSONObject> kids(JSONObject n) {
        List<JSONObject> k = kids.get(n);
        return k == null ? new ArrayList<>() : k;
    }

    JSONObject n(String id) { return id == null ? null : node.get(id); }

    JSONObject p(String id) { return id == null ? null : parent.get(id); }

    static String id(JSONObject n) { return n.optString("id", ""); }

    /** Rest bounds [x0, y0, x1, y1] of every drawing under a node, or null. */
    double[] boundsOf(JSONObject n) {
        double[][] b = {null};
        walk(n, k -> {
            if (!"art".equals(k.optString("type")) ) return;
            String img = J.s(k, "img");
            if (img == null || !pics.has(img)) return;
            double x0 = k.optDouble("x", 0), y0 = k.optDouble("y", 0), x1 = x0 + pics.w(img), y1 = y0 + pics.h(img);
            if (b[0] == null) b[0] = new double[] {x0, y0, x1, y1};
            else b[0] = new double[] {Math.min(b[0][0], x0), Math.min(b[0][1], y0), Math.max(b[0][2], x1), Math.max(b[0][3], y1)};
        });
        return b[0];
    }

    interface Visit { void at(JSONObject n); }

    void walk(JSONObject n, Visit v) {
        v.at(n);
        for (JSONObject k : kids(n)) walk(k, v);
    }
}
