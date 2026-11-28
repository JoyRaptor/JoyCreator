package cc.joycreator.avatar.ui;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.widget.TooltipCompat;

import cc.joycreator.avatar.file.CharacterDoc;

import com.fadcam.ui.faditor.Studio;

import org.json.JSONObject;

import java.util.Locale;

/**
 * Motion: "Ground and walking" (the pilot's al-ground.js and cards.walkControls): whether dragging the body walks it, where the ground
 * is, how it moves (steps or hops), stride, step height, rise and fall, heel and toe, lean, arm swing, what the legs do in the air,
 * when it jumps and drops to all fours. A character with no legs can float where you drag it instead. Every control writes the
 * character's settings ({@code settings.walk}, {@code settings.hover}) as one named undo step when the finger lifts; the walker reads
 * them live.
 */
final class WalkSections {
    private WalkSections() {
    }

    static void add(Context c, LinearLayout col, CharacterDoc doc, MotionSections.Host host, float dp) {
        JSONObject st = doc.json.optJSONObject("settings");
        if (st == null) return;
        JSONObject wk = st.optJSONObject("walk");
        col.addView(MotionSections.label(c, "Ground and walking", dp));
        if (wk == null) {                                                                   // no legs: it can float where you drag it
            JSONObject hv = st.optJSONObject("hover");
            boolean on = hv != null && hv.optBoolean("on", true);
            col.addView(tick(c, "Floats where you drag the body", on, "For a character with no legs: a hovering robot, a ghost, a fish. It drifts after your finger and leans into the move.", dp,
                    v -> {
                        if (hv == null) {
                            host.setValue(null, "settings.hover.lean", 1.0, "Floats where you drag");
                            host.setValue(null, "settings.hover.quick", 9, "Floats where you drag");
                        }
                        host.setValue(null, "settings.hover.on", v, "Floats where you drag");
                    }));
            if (on && hv != null) {
                col.addView(MotionSections.slider(c, "Lean", 0, 2.5, 0.05, hv.optDouble("lean", 1), v -> String.format(Locale.US, "%.2f×", v), "How far it tips into the move",
                        v -> host.setValue(null, "settings.hover.lean", v, "Float lean"), dp));
                col.addView(MotionSections.slider(c, "Keeps up", 2, 24, 0.5, hv.optDouble("quick", 9), v -> v < 5 ? "drifts" : v > 15 ? "snappy" : "easy", "How quickly it catches up with your finger",
                        v -> host.setValue(null, "settings.hover.quick", v, "Float keeps up"), dp));
            } else if (!on)
                col.addView(MotionSections.note(c, "Or give it two legs that bend and it walks: the feet plant and step by themselves.", dp));
            return;
        }
        boolean walks = wk.optBoolean("on", true);
        col.addView(tick(c, "Walks when you drag the body", walks, "Each foot stays planted until it is left behind, then steps. Nothing slides.", dp, v -> host.setValue(null, "settings.walk.on", v, "Walks when dragged")));
        if (!walks) return;
        final boolean plane = "plane".equals(wk.optString("ground", "line"));
        col.addView(MotionSections.label(c, "It starts", dp));
        col.addView(seg(c, dp, new String[][]{{"line", "On a ground line", "Pulling the body up takes the feet off the ground: a jump"}, {"plane", "Walking anywhere", "Up and down the stage is walking too, and further up can be further away"}},
                plane ? "plane" : "line", v -> host.setValue(null, "settings.walk.ground", v, "Walk: where it starts")));
        col.addView(MotionSections.label(c, "It moves by", dp));
        col.addView(seg(c, dp, new String[][]{{"walk", "Steps", "One foot at a time"}, {"hop", "Hops", "Both feet together, the body arcs"}},
                wk.optString("gait", "walk"), v -> host.setValue(null, "settings.walk.gait", v, "Walk: gait")));
        col.addView(MotionSections.slider(c, "Stride", 8, 220, 1, wk.optDouble("stride", 60), v -> Math.round(v) + " px", "How far apart the footfalls are", v -> host.setValue(null, "settings.walk.stride", Math.round(v), "Walk: stride"), dp));
        col.addView(MotionSections.slider(c, "Step height", 0, 90, 1, wk.optDouble("stepH", 20), v -> Math.round(v) + " px", "How high a foot lifts", v -> host.setValue(null, "settings.walk.stepH", Math.round(v), "Walk: step height"), dp));
        col.addView(MotionSections.slider(c, "Rise and fall", 0, 4, 0.05, wk.optDouble("bob", 0.8), v -> v < 0.05 ? "flat" : v < 1.2 ? "natural" : v < 2.5 ? "bouncy" : "cartoony",
                "The wave the body and head ride with every step: low as the weight lands on a foot, high as the other leg passes", v -> host.setValue(null, "settings.walk.bob", v, "Walk: rise and fall"), dp));
        col.addView(MotionSections.slider(c, "Step", -1, 1, 0.05, wk.optDouble("style", -0.4), v -> v < -0.12 ? "heel first" : v > 0.12 ? "tiptoe" : "flat",
                "Where the weight lands: reaching out with the heel, flat-footed, or up on the toes", v -> host.setValue(null, "settings.walk.style", v, "Walk: step"), dp));
        col.addView(MotionSections.slider(c, "Heel to toe", 0, 1.6, 0.05, wk.optDouble("roll", 1), v -> v < 0.03 ? "none" : String.format(Locale.US, "%.2f×", v),
                "How much the foot rolls. None suits a ball or a peg for a foot.", v -> host.setValue(null, "settings.walk.roll", v, "Walk: heel to toe"), dp));
        col.addView(MotionSections.slider(c, "Lean", 0, 1.5, 0.05, wk.optDouble("lean", 0.5), v -> String.format(Locale.US, "%.2f×", v), "How far it leans into the walk",
                v -> host.setValue(null, "settings.walk.lean", v, "Walk: lean"), dp));
        col.addView(MotionSections.slider(c, "Arm swing", 0, 50, 1, wk.optDouble("armSwing", 18), v -> Math.round(v) + "°", "A hand nobody is holding swings against the legs",
                v -> host.setValue(null, "settings.walk.armSwing", Math.round(v), "Walk: arm swing"), dp));
        col.addView(MotionSections.label(c, "In the air, it", dp));
        col.addView(seg(c, dp, new String[][]{{"tuck", "Jumps", "The legs fold on the way up and swing forward on the way down, ready to land"}, {"hang", "Hangs", "The legs dangle"}},
                "hang".equals(wk.optString("air")) ? "hang" : "tuck", v -> host.setValue(null, "settings.walk.air", v, "Walk: in the air")));
        col.addView(tick(c, "Falls back when let go", wk.optBoolean("fall", true), "Off: it stays where you leave it, in the air", dp, v -> host.setValue(null, "settings.walk.fall", v, "Walk: falls back")));
        if (plane)
            col.addView(MotionSections.slider(c, "Smaller far away", 0, 1, 0.05, wk.optDouble("depth", 0.35), v -> Math.round(v * 100) + "%", "How much it shrinks as it walks up the stage",
                    v -> host.setValue(null, "settings.walk.depth", v, "Walk: smaller far away"), dp));
        if (!"front".equals(st.optString("facing", "front")))
            col.addView(tick(c, "Turns to face the way it walks", wk.optBoolean("face", true), "", dp, v -> host.setValue(null, "settings.walk.face", v, "Walk: turns to face")));
        if (!plane) {
            col.addView(MotionSections.label(c, "Upright, all fours, or a jump", dp));
            if (wk.optString("fours", "").length() > 0)
                col.addView(MotionSections.slider(c, "Drops to all fours", 0.3, 1, 0.02, wk.optDouble("fourAt", 0.82), v -> v < 0.5 ? "at a light push" : v > 0.85 ? "only pushed hard" : "pushed " + Math.round(v * 100) + "%",
                        "How far down the body has to be pushed before it goes onto all fours", v -> host.setValue(null, "settings.walk.fourAt", v, "Walk: drops to all fours"), dp));
            col.addView(MotionSections.slider(c, "Jumps", 0.2, 1.6, 0.05, wk.optDouble("jumpAt", 0.7), v -> v < 0.45 ? "at a light pull" : v > 1.1 ? "only pulled hard" : "pulled " + Math.round(v * 100) + "%",
                    "How far up the body has to be pulled before it jumps", v -> host.setValue(null, "settings.walk.jumpAt", v, "Walk: jumps"), dp));
        }
    }

    /** A tick box: on or off (ON = solid cyan with the tick punched out). */
    private static View tick(Context c, String label, boolean on, String hover, float dp, java.util.function.Consumer<Boolean> set) {
        LinearLayout row = new LinearLayout(c);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, Math.round(6 * dp), 0, Math.round(6 * dp));
        TextView box = MotionSections.text(c, on ? "✓" : "", 15, Studio.GROUND);
        box.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(5 * dp);
        g.setColor(on ? Studio.ARMED : 0xFF27272A);
        if (!on) g.setStroke(Math.max(1, Math.round(dp)), 0xFF52525B);
        box.setBackground(g);
        row.addView(box, new LinearLayout.LayoutParams(Math.round(24 * dp), Math.round(24 * dp)));
        TextView t = MotionSections.text(c, label, 13, 0xFFE4E4E7);
        t.setPadding(Math.round(10 * dp), 0, 0, 0);
        row.addView(t);
        row.setOnClickListener(v -> set.accept(!on));
        if (!hover.isEmpty()) {
            TooltipCompat.setTooltipText(row, hover);
            row.setContentDescription(label + ": " + hover);
        } else row.setContentDescription(label);
        return row;
    }

    /** Two or three equals (a switch): the chosen one solid cyan. Each entry is {key, label, hover}. */
    private static View seg(Context c, float dp, String[][] options, String current, java.util.function.Consumer<String> set) {
        LinearLayout row = new LinearLayout(c);
        for (String[] o : options) row.addView(MotionSections.chip(c, o[1], o[0].equals(current), o[2], dp, v -> set.accept(o[0])));
        return MotionSections.scroll(c, row);
    }
}
