package cc.joycreator.avatar.ui;

import android.content.Context;
import android.widget.LinearLayout;

import cc.joycreator.avatar.file.CharacterDoc;

import org.json.JSONObject;

import java.util.Locale;

/**
 * Set up, the mouth (a part of the pilot's Mouth card): how it talks. On a hinge it swings shut as the voice goes quiet; squashing it
 * flattens; swap only it does not move by itself. Whether the drawing is an open mouth or a shut one, how far it swings, how far it
 * shuts. Guess again works the hinge out from the art. Every control is one named undo step.
 */
final class MouthSections {
    private MouthSections() {
    }

    static boolean isMouth(CharacterDoc.Node n) {
        return "mouth".equals(n.role);
    }

    /** The drawing that talks: the part itself, or the one a set of mouths rests on. */
    private static CharacterDoc.Node art(CharacterDoc.Node m) {
        if (!"set".equals(m.type)) return m;
        JSONObject pick = m.json.optJSONObject("pick");
        String rest = pick == null ? "" : pick.optString("rest");
        for (CharacterDoc.Node k : m.kids) if (k.id.equals(rest)) return k;
        return m.kids.isEmpty() ? null : m.kids.get(0);
    }

    static void add(Context c, LinearLayout col, CharacterDoc.Node mouth, MotionSections.Host host, PartsView.Host parts, float dp) {
        CharacterDoc.Node art = art(mouth);
        if (art == null || "viseme".equals(mouth.pickBy)) return;                       // one drawing per sound: the voice picks the shape
        final JSONObject hinge = art.json.optJSONObject("hinge"), squash = art.json.optJSONObject("squash");
        final String mode = hinge != null ? "hinge" : squash != null ? "squash" : "swap";
        col.addView(MotionSections.label(c, "How it talks", dp));
        LinearLayout seg = new LinearLayout(c);
        seg.addView(MotionSections.chip(c, "Hinge", "hinge".equals(mode), "Swings open and shut around a pin", dp, v -> {
            if (!"hinge".equals(mode)) {
                host.remove(art.id, "squash", "Mouth: hinge");
                parts.guessPins(art.id, "mouth");
            }
        }));
        seg.addView(MotionSections.chip(c, "Squash", "squash".equals(mode), "Flattens shut toward a line", dp, v -> {
            if (!"squash".equals(mode)) parts.mouthAction(art.id, "squash");
        }));
        seg.addView(MotionSections.chip(c, "Swap only", "swap".equals(mode), "Does not move by itself", dp, v -> {
            host.remove(art.id, "hinge", "Mouth: swap only");
            host.remove(art.id, "squash", "Mouth: swap only");
        }));
        col.addView(MotionSections.scroll(c, seg));
        JSONObject cfg = hinge != null ? hinge : squash;
        if (cfg == null) {
            col.addView(MotionSections.note(c, "Stays as drawn. Other mouths swap in from their buttons.", dp));
            return;
        }
        final String root = hinge != null ? "hinge" : "squash";
        LinearLayout rest = new LinearLayout(c);
        final String cur = cfg.optString("rest", "open");
        col.addView(MotionSections.label(c, "As drawn it is", dp));
        rest.addView(MotionSections.chip(c, "Open", "open".equals(cur), "Is the drawing an open mouth or a shut one?", dp, v -> host.setValue(art.id, root + ".rest", "open", "Mouth: drawn open")));
        rest.addView(MotionSections.chip(c, "Shut", "closed".equals(cur), "Is the drawing an open mouth or a shut one?", dp, v -> host.setValue(art.id, root + ".rest", "closed", "Mouth: drawn shut")));
        col.addView(MotionSections.scroll(c, rest));
        if (hinge != null)
            col.addView(MotionSections.slider(c, "Swing", -80, 80, 1, hinge.optDouble("angle", 30), v -> Math.round(v) + "°", "How far it turns to shut. Minus swings the other way.",
                    v -> host.setValue(art.id, "hinge.angle", Math.round(v), "Mouth: swing"), dp));
        else
            col.addView(MotionSections.slider(c, "Shuts to", 0, 0.6, 0.02, squash.optDouble("min", 0.14), v -> Math.round(v * 100) + "%", "How flat it squashes when shut",
                    v -> host.setValue(art.id, "squash.min", v, "Mouth: shuts to"), dp));
        col.addView(MotionSections.slider(c, "Try: talk", 0, 1, 0.05, 0, v -> v < 0.05 ? "shut" : Math.round(v * 100) + "% open", "Slide to see it talk",
                v -> {
                }, dp, v -> parts.tryMouth(v)));
        LinearLayout acts = new LinearLayout(c);
        acts.addView(MotionSections.chip(c, "Same on every angle", false, "Copy this way of talking to the mouth at every head angle (each keeps its own hinge pin)", dp, v -> parts.mouthAction(art.id, "everywhere")));
        if (hinge != null)
            acts.addView(MotionSections.chip(c, "Guess again", false, "Work the hinge out from the art again", dp, v -> parts.guessPins(art.id, "mouth")));
        col.addView(MotionSections.scroll(c, acts));
    }
}
