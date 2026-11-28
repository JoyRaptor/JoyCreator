package cc.joycreator.avatar.ui;

import android.content.Context;
import android.widget.LinearLayout;

import cc.joycreator.avatar.file.CharacterDoc;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Set up, the small face features (the pilot's Brows, Jaw, Nose, Hair cards): how far the brows lift, how far the jaw drops with the
 * mouth, and how far a nose, hair or ear slides as the head turns (depth). Each control writes every part with that role as one named undo step.
 */
final class FeatureSections {
    private FeatureSections() {
    }

    private static boolean has(String role, String... roles) {
        for (String r : roles) if (r.equals(role)) return true;
        return false;
    }

    static boolean applies(CharacterDoc.Node n) {
        return has(n.role, "browL", "browR", "jaw", "nose", "hair");
    }

    private static void byRole(CharacterDoc.Node n, List<String> roles, List<CharacterDoc.Node> out) {
        if (roles.contains(n.role)) out.add(n);
        for (CharacterDoc.Node k : n.kids) byRole(k, roles, out);
    }

    private static double linkAmt(CharacterDoc.Node n, double def) {
        JSONArray l = n.json.optJSONArray("links");
        JSONObject f = l == null ? null : l.optJSONObject(0);
        return f == null ? def : f.optDouble("amt", def);
    }

    static void add(Context c, LinearLayout col, CharacterDoc doc, CharacterDoc.Node node, MotionSections.Host host, float dp) {
        if (has(node.role, "browL", "browR")) {
            List<CharacterDoc.Node> br = new ArrayList<>();
            byRole(doc.root, java.util.Arrays.asList("browL", "browR"), br);
            col.addView(MotionSections.label(c, "Brows", dp));
            col.addView(MotionSections.slider(c, "Lift", 0, 16, 0.5, br.isEmpty() ? 4 : -linkAmt(br.get(0), -4), v -> String.format(Locale.US, "%.1f px", v), "How far the brows lift with surprise and when the mouth opens wide",
                    v -> host.setMany("Brows lift", links(br, -v)), dp));
        } else if ("jaw".equals(node.role)) {
            List<CharacterDoc.Node> jw = new ArrayList<>();
            byRole(doc.root, java.util.Arrays.asList("jaw"), jw);
            col.addView(MotionSections.label(c, "Jaw", dp));
            col.addView(MotionSections.slider(c, "Drops", 0, 14, 0.5, jw.isEmpty() ? 3 : linkAmt(jw.get(0), 3), v -> String.format(Locale.US, "%.1f px", v), "How far the jaw drops as the mouth opens, so the chin joins in",
                    v -> host.setMany("Jaw drops", links(jw, v)), dp));
        } else {
            List<CharacterDoc.Node> list = new ArrayList<>();
            byRole(doc.root, java.util.Arrays.asList(node.role), list);
            col.addView(MotionSections.label(c, "How far it slides", dp));
            col.addView(MotionSections.slider(c, "Depth", -1.5, 1.5, 0.1, list.isEmpty() ? 0 : list.get(0).json.optDouble("depth", 0),
                    v -> v > 0.05 ? "front " + String.format(Locale.US, "%.1f", v) : v < -0.05 ? "behind " + String.format(Locale.US, "%.1f", -v) : "flat", "Front slides with the turn, behind slides against it",
                    v -> {
                        List<Object[]> items = new ArrayList<>();
                        for (CharacterDoc.Node n : list) items.add(new Object[]{n.id, "depth", Math.round(v * 10) / 10.0});
                        host.setMany("Depth", items);
                    }, dp));
        }
    }

    private static List<Object[]> links(List<CharacterDoc.Node> nodes, double amt) {
        List<Object[]> items = new ArrayList<>();
        for (CharacterDoc.Node n : nodes) {
            JSONArray l = n.json.optJSONArray("links");
            if (l != null && l.optJSONObject(0) != null) items.add(new Object[]{n.id, "links.0.amt", amt});
        }
        return items;
    }
}
