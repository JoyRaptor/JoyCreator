package cc.joycreator.avatar.ui;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.appcompat.widget.TooltipCompat;

import cc.joycreator.avatar.file.CharacterDoc;

import com.fadcam.ui.faditor.Studio;

import org.json.JSONObject;

import java.util.List;
import java.util.Locale;

/**
 * Motion: how it moves and behaves (spec A7): what the part in hand does by itself when the body moves (swinging: bendy
 * or stiff, springiness, settle, follow-through, joints or rattle room, hangs, breeze, flutter, idle wag), how a turn
 * around looks, and how it breathes and blinks. The words and ranges are the pilot's. Every slider is one named undo step
 * at the end of its drag. The numbers are ordinary data in the file; the solver reads them.
 */
public final class MotionSections {

    public interface Host {
        /** Sets one value as a named undo step: {@code nodeId} null for the character itself; {@code path} like "swing.damp". */
        void setValue(String nodeId, String path, Object value, String stepName);

        /** Removes one key (e.g. "swing") as a named undo step. */
        void remove(String nodeId, String path, String stepName);
    }

    private MotionSections() {}

    static void add(Context c, LinearLayout col, CharacterDoc doc, CharacterDoc.Node inHand, PoseRenderer r, Host host) {
        float dp = c.getResources().getDisplayMetrics().density;
        // the part in hand: anything that swings among the drawings showing now
        CharacterDoc.Node swinger = null;
        if (inHand != null && r != null) for (CharacterDoc.Node k : r.shown(inHand)) if (k.json.optJSONObject("swing") != null) {
            swinger = k;
            break;
        }
        if (swinger != null) swing(c, col, swinger, host, dp);
        else col.addView(note(c, inHand == null ? "Take a part in hand to see how it moves by itself." : "This part does not swing. Hair, feathers, a tail or a chain can.", dp));

        JSONObject st = doc.json.optJSONObject("settings");
        if (st == null) st = new JSONObject();
        String facing = st.optString("facing", "front");
        col.addView(label(c, "Breathing and blinking", dp));
        col.addView(slider(c, "Breaths a minute", 6, 40, 1, Math.round(st.optDouble("breathRate", 0.28) * 60), v -> String.valueOf((int) v), "How fast the chest rises and falls",
                v -> host.setValue(null, "settings.breathRate", v / 60.0, "Breathing speed"), dp));
        col.addView(slider(c, "Blinks about every", 1.5, 9, 0.1, st.optDouble("blinkEvery", 3.6), v -> String.format(Locale.US, "%.1f s", v), "How often it blinks by itself",
                v -> host.setValue(null, "settings.blinkEvery", Math.round(v * 10) / 10.0, "Blink rate"), dp));
        if (!"front".equals(facing)) {
            col.addView(label(c, "Turning around", dp));
            final String style = st.optString("flipStyle", "smooth");
            LinearLayout seg = new LinearLayout(c);
            seg.addView(chip(c, "Smooth", "smooth".equals(style), "The body tucks in, swaps side while tucked and opens out again", dp, v -> host.setValue(null, "settings.flipStyle", "smooth", "Turn style: smooth")));
            seg.addView(chip(c, "Instant", "snap".equals(style), "Straight to the mirror image, no in-between", dp, v -> host.setValue(null, "settings.flipStyle", "snap", "Turn style: instant")));
            seg.addView(chip(c, "A card", "turn".equals(style), "It swings round like a cardboard cut-out", dp, v -> host.setValue(null, "settings.flipStyle", "turn", "Turn style: a card")));
            col.addView(scroll(c, seg));
            if (st.optBoolean("autoFlip", false))
                col.addView(slider(c, "Flips past", 0.1, 0.9, 0.05, st.optDouble("flipAt", 0.4), v -> Math.round(v * 100) + "% turned", "How far the head has turned when the whole character flips",
                        v -> host.setValue(null, "settings.flipAt", v, "Flip point"), dp));
            if ("smooth".equals(style)) {
                col.addView(slider(c, "It takes", 0.04, 0.6, 0.02, st.optDouble("flipTime", 0.14), v -> Math.round(v * 24) + " frames", "How long the turn lasts, in frames of film (24 a second)",
                        v -> host.setValue(null, "settings.flipTime", v, "Turn time"), dp));
                col.addView(slider(c, "It tucks in", 0, 0.9, 0.02, st.optDouble("flipTuck", 0.45), v -> Math.round(v * 100) + "%", "How far the body, tail and arms pull in toward the middle before they swap sides",
                        v -> host.setValue(null, "settings.flipTuck", v, "Turn tuck"), dp));
            }
        }
    }

    // ── swinging ──────────────────────────────────────────────────────────────────────────────────

    private static void swing(Context c, LinearLayout col, CharacterDoc.Node n, Host host, float dp) {
        JSONObject d = n.json.optJSONObject("swing");
        final String id = n.id, name = n.name.isEmpty() ? n.id : n.name;
        boolean stiff = "stiff".equals(d.optString("kind", "bendy"));
        col.addView(label(c, "How " + name + " swings", dp));
        LinearLayout kind = new LinearLayout(c);
        kind.addView(chip(c, "Bendy", !stiff, "Bends along its length: hair, braids, feathers, tails", dp, v -> host.setValue(id, "swing.kind", "bendy", name + ": bendy")));
        kind.addView(chip(c, "Stiff", stiff, "Never bends; swings and rattles: chain links, earrings, charms", dp, v -> host.setValue(id, "swing.kind", "stiff", name + ": stiff")));
        boolean on = d.optBoolean("on", true);
        kind.addView(chip(c, on ? "On" : "Off", on, "Switch the swinging on or off", dp, v -> host.setValue(id, "swing.on", !on, name + (on ? ": swinging off" : ": swinging on"))));
        col.addView(scroll(c, kind));
        col.addView(slider(c, "Springiness", 0, 1, 0.02, d.optDouble("stiff", 0.35), v -> v < 0.2 ? "floppy" : v > 0.7 ? "snappy" : String.valueOf(Math.round(v * 100)), "Low is floppy, high snaps back",
                v -> host.setValue(id, "swing.stiff", v, name + ": springiness"), dp));
        col.addView(slider(c, "Settle", 0, 1, 0.02, d.optDouble("damp", 0.3), v -> v < 0.15 ? "keeps going" : v > 0.7 ? "at once" : String.valueOf(Math.round(v * 100)), "How quickly it comes to rest. Low keeps swinging",
                v -> host.setValue(id, "swing.damp", v, name + ": settle"), dp));
        col.addView(slider(c, "Follow-through", 0, 2, 0.05, d.optDouble("follow", 1), v -> String.format(Locale.US, "%.2f", v) + "×", "0 rides rigidly, 1 is left behind and catches up, more is flung",
                v -> host.setValue(id, "swing.follow", v, name + ": follow-through"), dp));
        if (!stiff) col.addView(slider(c, "Joints", 1, 8, 1, d.optDouble("segs", 4), v -> String.valueOf((int) v), "More joints make a longer, lazier wave down its length",
                v -> host.setValue(id, "swing.segs", (int) v, name + ": joints"), dp));
        else col.addView(slider(c, "Rattle room", 5, 180, 1, d.optDouble("limit", 80), v -> "±" + (int) v + "°", "How far it may swing before it knocks and bounces back",
                v -> host.setValue(id, "swing.limit", (int) v, name + ": rattle room"), dp));
        col.addView(slider(c, "Breeze", 0, 2.5, 0.05, d.optDouble("wind", 1), v -> v < 0.03 ? "ignores it" : String.format(Locale.US, "%.2f", v) + "×", "How much the wind moves it",
                v -> host.setValue(id, "swing.wind", v, name + ": breeze"), dp));
        if (!stiff) col.addView(slider(c, "Flutter", 0, 1, 0.02, d.optDouble("flutter", 0), v -> String.valueOf(Math.round(v * 100)), "A fast shiver in the wind: feathers, leaves, ribbons",
                v -> host.setValue(id, "swing.flutter", v, name + ": flutter"), dp));
        col.addView(slider(c, "Idle wag", 0, 0.3, 0.01, d.optDouble("wag", 0), v -> String.valueOf(Math.round(v * 100)), "A small movement of its own when nothing else is happening",
                v -> host.setValue(id, "swing.wag", v, name + ": idle wag"), dp));
        LinearLayout stop = new LinearLayout(c);
        stop.addView(chip(c, "Stop it swinging", false, "Take the swinging off this part", dp, v -> host.remove(id, "swing", name + ": stop swinging")));
        col.addView(scroll(c, stop));
    }

    // ── small pieces ──────────────────────────────────────────────────────────────────────────────

    private interface Fmt {
        String of(double v);
    }

    private interface Commit {
        void to(double v);
    }

    private static View slider(Context c, String label, double min, double max, double step, double value, Fmt fmt, String hover, Commit commit, float dp) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        LinearLayout head = new LinearLayout(c);
        TextView l = text(c, label, 13, 0xFFD4D4D8);
        TextView val = text(c, fmt.of(value), 12, 0xFFA1A1AA);
        head.addView(l, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(val);
        box.addView(head);
        final int steps = (int) Math.round((max - min) / step);
        SeekBar sb = new SeekBar(c);
        sb.setMax(steps);
        sb.setProgress((int) Math.round((Math.max(min, Math.min(max, value)) - min) / step));
        sb.getProgressDrawable().setTint(Studio.ARMED);
        sb.getThumb().setTint(Studio.ARMED);
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean user) {
                val.setText(fmt.of(min + p * step));
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
                commit.to(min + s.getProgress() * step);          // one undo step, when the finger lifts
            }
        });
        box.addView(sb);
        box.setPadding(0, Math.round(4 * dp), 0, Math.round(2 * dp));
        TooltipCompat.setTooltipText(box, hover);
        box.setContentDescription(label + ": " + hover);
        return box;
    }

    private static View chip(Context c, String text, boolean on, String hover, float dp, View.OnClickListener tap) {
        TextView t = text(c, text, 12, on ? Studio.GROUND : 0xFFD4D4D8);
        t.setGravity(Gravity.CENTER);
        t.setPadding(Math.round(12 * dp), 0, Math.round(12 * dp), 0);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(16 * dp);
        g.setColor(on ? Studio.ARMED : 0xFF27272A);
        t.setBackground(g);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Math.round(32 * dp));
        lp.rightMargin = Math.round(6 * dp);
        t.setLayoutParams(lp);
        t.setOnClickListener(tap);
        TooltipCompat.setTooltipText(t, hover);
        t.setContentDescription(hover);
        return t;
    }

    private static View scroll(Context c, LinearLayout row) {
        HorizontalScrollView hs = new HorizontalScrollView(c);
        hs.setHorizontalScrollBarEnabled(false);
        hs.addView(row);
        return hs;
    }

    private static TextView label(Context c, String s, float dp) {
        TextView t = text(c, s, 12, 0xFF71717A);
        t.setPadding(0, Math.round(10 * dp), 0, Math.round(6 * dp));
        return t;
    }

    private static TextView note(Context c, String s, float dp) {
        TextView t = text(c, s, 13, 0xFFA1A1AA);
        t.setPadding(0, Math.round(6 * dp), 0, Math.round(6 * dp));
        return t;
    }

    private static TextView text(Context c, String s, int sp, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }
}
