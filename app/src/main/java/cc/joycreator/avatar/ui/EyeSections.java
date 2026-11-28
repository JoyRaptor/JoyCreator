package cc.joycreator.avatar.ui;

import android.content.Context;
import android.widget.LinearLayout;

import cc.joycreator.avatar.file.CharacterDoc;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Set up, the eyes (the pilot's Eyes card): how far the pupils travel when the eyes look aside, and the blinking: by itself or not,
 * how often, whether the eyes get there before the head, and a Blink now to see it. Every control is one named undo step.
 */
final class EyeSections {
    private EyeSections() {
    }

    /** Is this part one of the eyes (so this card applies)? */
    static boolean isEye(CharacterDoc.Node n) {
        String r = n.role;
        return "eyeL".equals(r) || "eyeR".equals(r) || "pupil".equals(r) || "eyeball".equals(r) || "blink".equals(r);
    }

    private static void pupils(CharacterDoc.Node n, List<CharacterDoc.Node> out) {
        if ("pupil".equals(n.role) && n.json.optJSONObject("look") != null) out.add(n);
        for (CharacterDoc.Node k : n.kids) pupils(k, out);
    }

    static void add(Context c, LinearLayout col, CharacterDoc doc, MotionSections.Host host, float dp) {
        JSONObject st = doc.json.optJSONObject("settings");
        if (st == null) return;
        List<CharacterDoc.Node> pup = new ArrayList<>();
        pupils(doc.root, pup);
        col.addView(MotionSections.label(c, "Looking", dp));
        if (!pup.isEmpty()) {
            final List<Object[]> all = new ArrayList<>();
            col.addView(MotionSections.slider(c, "Pupils move", 0, 14, 0.5, pup.get(0).json.optJSONObject("look").optDouble("rx", 0), v -> String.format(Locale.US, "%.1f px", v),
                    "How far a pupil slides when the eyes look to the side", v -> {
                        List<Object[]> items = new ArrayList<>();
                        for (CharacterDoc.Node p : pup) {
                            items.add(new Object[]{p.id, "look.rx", v});
                            items.add(new Object[]{p.id, "look.ry", Math.round(v * 0.65 * 10) / 10.0});
                        }
                        host.setMany("Pupils move", items);
                    }, dp));
        }
        col.addView(MotionSections.label(c, "Blinking", dp));
        col.addView(WalkSections.tick(c, "Blink by itself", st.optBoolean("blinkAuto", true), "Uneven, like a living thing: sometimes twice", dp, v -> host.setValue(null, "settings.blinkAuto", v, "Blink by itself")));
        col.addView(MotionSections.slider(c, "About every", 1.5, 9, 0.1, st.optDouble("blinkEvery", 3.6), v -> String.format(Locale.US, "%.1f s", v), "How often it blinks by itself",
                v -> host.setValue(null, "settings.blinkEvery", Math.round(v * 10) / 10.0, "Blink rate"), dp));
        col.addView(WalkSections.tick(c, "Eyes lead the head", st.optBoolean("eyesLead", true), "The eyes get there first, then the head follows", dp, v -> host.setValue(null, "settings.eyesLead", v, "Eyes lead the head")));
        LinearLayout row = new LinearLayout(c);
        row.addView(MotionSections.chip(c, "Blink now", false, "See it blink", dp, v -> host.blinkNow()));
        col.addView(MotionSections.scroll(c, row));
    }
}
