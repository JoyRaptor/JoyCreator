package cc.joycreator.avatar.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.widget.LinearLayout;

import cc.joycreator.avatar.file.CharacterDoc;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Motion, "Follows" (the pilot's follows card): any part can ride any signal. Lift with the mouth, shake with the voice, squash while a
 * button is held, spin all the time. Each link says what it moves, with what, how much; Bouncy overshoots and settles; Back and forth rocks
 * in time. Every change is one named undo step.
 */
final class FollowSections {
    private FollowSections() {
    }

    private static final String[][] SIGS = {
            {"mouth", "the mouth opening"}, {"turnX", "the head turning left and right"}, {"turnY", "the head turning up and down"}, {"roll", "the head tilting"},
            {"lookX", "the eyes looking sideways"}, {"lookY", "the eyes looking up and down"}, {"blinkL", "a blink"}, {"browL", "the brows"}, {"breath", "breathing"},
            {"sway", "the body leaning"}, {"bounce", "the body bouncing"}, {"shrug", "the shoulders shrugging"},
            {"face.cheekPuff", "your cheeks puffed out"}, {"face.smile", "your smile"}, {"face.jaw", "your jaw dropping"}, {"face.mouthWide", "your mouth stretched wide"},
            {"face.pucker", "your lips puckered"}, {"face.wide", "your eyes wide"}, {"face.squint", "your eyes squinting"}, {"face.browInner", "your brows raised"},
            {"face.frown", "your frown"}, {"face.sneer", "your sneer"}, {"always", "all the time"}};
    private static final String[][] PROPS = {
            {"y", "slides up and down"}, {"x", "slides sideways"}, {"rot", "turns"}, {"s", "grows and shrinks"}, {"sy", "stretches tall"}, {"sx", "stretches wide"},
            {"shake", "shakes"}, {"spin", "spins"}};

    private static boolean isScale(String p) {
        return "s".equals(p) || "sx".equals(p) || "sy".equals(p);
    }

    private static String nameOf(String[][] list, String key) {
        for (String[] s : list) if (s[0].equals(key)) return s[1];
        return key;
    }

    /** The signals on offer: the signals themselves, and this character's own buttons. */
    private static List<String[]> signals(CharacterDoc doc) {
        List<String[]> out = new ArrayList<>();
        for (String[] s : SIGS) out.add(s);
        JSONArray ts = doc.json.optJSONArray("triggers");
        for (int i = 0; ts != null && i < ts.length(); i++) {
            JSONObject t = ts.optJSONObject(i);
            if (t != null) out.add(new String[]{"trig:" + t.optString("id"), "the “" + t.optString("name") + "” button"});
        }
        return out;
    }

    static void add(Context c, LinearLayout col, CharacterDoc doc, CharacterDoc.Node node, MotionSections.Host host, float dp) {
        final JSONArray links = node.json.optJSONArray("links");
        col.addView(MotionSections.label(c, "Follows", dp));
        if (links == null || links.length() == 0)
            col.addView(MotionSections.note(c, "Make this part move with something else: lift with the mouth, shake with the voice, squash while a button is held, spin all the time.", dp));
        final List<String[]> sigs = signals(doc);
        for (int i = 0; links != null && i < links.length(); i++) {
            final JSONObject l = links.optJSONObject(i);
            if (l == null) continue;
            final int at = i;
            final String sig = l.optString("sig"), prop = l.optString("prop");
            String sname = sig;
            for (String[] s : sigs) if (s[0].equals(sig)) sname = s[1];
            col.addView(MotionSections.note(c, "It " + nameOf(PROPS, prop) + ("always".equals(sig) ? " " : " with ") + ("always".equals(sig) ? "all the time" : sname), dp));
            LinearLayout row = new LinearLayout(c);
            row.addView(MotionSections.chip(c, "Bouncy", l.optBoolean("bounce", false), "It overshoots and settles, like something soft, instead of following exactly", dp,
                    v -> host.setValue(node.id, "links." + at + ".bounce", !l.optBoolean("bounce", false), "Bouncy")));
            row.addView(MotionSections.chip(c, "Back and forth", l.has("osc"), "It rocks to and fro in time instead of holding still: a hover, a bob, a wag", dp, v -> {
                if (l.has("osc")) host.remove(node.id, "links." + at + ".osc", "Back and forth");
                else host.setValue(node.id, "links." + at + ".osc", 1.0, "Back and forth");
            }));
            row.addView(MotionSections.chip(c, "Remove", false, "Remove this", dp, v -> host.removeLink(node.id, at)));
            col.addView(MotionSections.scroll(c, row));
            final boolean sc = isScale(prop);
            col.addView(MotionSections.slider(c, "How much", sc ? -1 : -40, sc ? 1 : 40, sc ? 0.02 : 0.5, l.optDouble("amt", 4),
                    v -> "spin".equals(prop) ? Math.round(v * 9) + "° a second" : String.format(Locale.US, "%.1f", v) + ("rot".equals(prop) ? "°" : sc ? "×" : " px"), "How much it moves",
                    v -> host.setValue(node.id, "links." + at + ".amt", v, "Follow amount"), dp));
            if (l.has("osc"))
                col.addView(MotionSections.slider(c, "To and fro", 0.1, 6, 0.05, l.optDouble("osc", 1), v -> String.format(Locale.US, "%.1f a second", v), "How fast it rocks",
                        v -> host.setValue(node.id, "links." + at + ".osc", v, "To and fro"), dp));
        }
        LinearLayout add = new LinearLayout(c);
        add.addView(MotionSections.chip(c, "Add a follow", false, "Pick how it moves, then what it follows", dp, v -> pick(c, node, sigs, host)));
        col.addView(MotionSections.scroll(c, add));
    }

    private static void pick(Context c, CharacterDoc.Node node, List<String[]> sigs, MotionSections.Host host) {
        String[] props = new String[PROPS.length];
        for (int i = 0; i < props.length; i++) props[i] = "It " + PROPS[i][1];
        new AlertDialog.Builder(c, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle("How does it move?").setItems(props, (d, pi) -> {
            String[] names = new String[sigs.size()];
            for (int i = 0; i < names.length; i++) names[i] = ("always".equals(sigs.get(i)[0]) ? "" : "with ") + sigs.get(i)[1];
            new AlertDialog.Builder(c, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle("What does it follow?").setItems(names, (d2, si) -> {
                String pr = PROPS[pi][0];
                double amt = "rot".equals(pr) ? 8 : "spin".equals(pr) ? 10 : "shake".equals(pr) ? 2 : isScale(pr) ? 0.15 : 4;
                try {
                    host.addLink(node.id, new JSONObject().put("sig", sigs.get(si)[0]).put("prop", pr).put("amt", amt));
                } catch (org.json.JSONException e) {
                    // a link of plain values cannot fail to build
                }
            }).show();
        }).show();
    }
}
