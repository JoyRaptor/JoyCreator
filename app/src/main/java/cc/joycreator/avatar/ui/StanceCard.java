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
import cc.joycreator.avatar.file.Stances;

import com.fadcam.ui.faditor.Studio;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

/**
 * Arrange, the pose row and the stance in hand (spec A6): <b>Standing</b> is how it was drawn; each stance has its own chip
 * (what you move is moved for that stance only); <b>+</b> makes a new one from nothing; all fours is worked out from the art
 * for a character that can. With a stance in hand: see it in part or in full, how far it tips and how far the hips come down
 * (all fours), put every part back, how long it takes, settles with a bounce, goes down by itself, rename, copy, remove.
 * The words are the pilot's.
 */
public final class StanceCard {

    public interface Host {
        /** The stance being worked on (its id), or null for Standing. */
        String poseEditing();

        void editStance(String idOrNull);

        void addStance();

        /** "copy", "rename", "remove", "fours" (work out all fours), "again" (forget hand moves). */
        void stanceAction(String action);

        void setStanceValue(String path, Object value, String stepName);

        double poseAmount();

        void setPoseAmount(double v);

        boolean canFours();
    }

    private StanceCard() {}

    static void add(Context c, LinearLayout col, CharacterDoc doc, Host host, float dp) {
        JSONArray poses = doc.json.optJSONArray("poses");
        String editing = host.poseEditing();
        JSONObject ed = editing == null ? null : Stances.find(doc.json, editing);
        col.addView(label(c, "Stance", dp));
        LinearLayout row = new LinearLayout(c);
        row.addView(chip(c, "Standing", ed == null, "Standing: what you move is moved when it stands", dp, v -> host.editStance(null)));
        for (int i = 0; poses != null && i < poses.length(); i++) {
            JSONObject p = poses.optJSONObject(i);
            if (p == null) continue;
            final String id = p.optString("id"), name = p.optString("name", id);
            row.addView(chip(c, name, ed == p, name + ": what you move is moved in this stance only", dp, v -> host.editStance(id)));
        }
        row.addView(chip(c, "+", false, "Make a new stance: a whole-body pose with its own button", dp, v -> host.addStance()));
        if (Stances.fours(doc.json) == null && host.canFours())
            row.addView(chip(c, "All fours", false, "Work out an all-fours stance: the body tips forward about the hips until the hands reach the ground", dp, v -> host.stanceAction("fours")));
        col.addView(scroll(c, row));
        if (ed == null) {
            col.addView(note(c, poses == null || poses.length() == 0
                    ? "A stance is a whole-body pose it melts into at the press of a button: all fours, sitting, a crouch. Tap + for a new one."
                    : "Standing is how it was drawn. Choose a stance to place the parts for it.", dp));
            return;
        }
        col.addView(note(c, "The character is in " + ed.optString("name") + ". Tap a part and drag it: it is moved for this stance only. Standing is not touched.", dp));
        col.addView(slider(c, "See it", 0, 100, 1, Math.round(host.poseAmount() * 100), v -> v >= 100 ? "fully in" : v <= 0 ? "standing" : (int) v + "%",
                "Slide to watch the character go into this stance and back out", v -> host.setPoseAmount(v / 100.0), true, dp));
        JSONObject fours = ed.optJSONObject("fours");
        if (fours != null) {
            col.addView(slider(c, "How far it tips", 30, 125, 1, fours.optDouble("angle", 90), v -> (int) v + "°", "How far the body leans forward about the hips",
                    v -> host.setStanceValue("fours.angle", (int) v, "Tip all fours"), false, dp));
            col.addView(slider(c, "Hips come down", 0, 80, 1, fours.optDouble("drop", 0), v -> v == 0 ? "not at all" : (int) v + " px", "How far the hips sink while it is on all fours. The feet stay planted, so the back legs fold",
                    v -> host.setStanceValue("fours.drop", (int) v, "Lower the hips"), false, dp));
        }
        int moves = Stances.handMoves(ed);
        LinearLayout acts = new LinearLayout(c);
        if (moves > 0)
            acts.addView(chip(c, fours != null ? "Work it out again" : "Put every part back", false,
                    "Forget every part you placed by hand in this stance (" + moves + "): " + (fours != null ? "back to All fours as it was worked out" : "back to standing") + ". Undo brings them back", dp, v -> host.stanceAction("again")));
        col.addView(scroll(c, acts));
        col.addView(slider(c, "It takes", 0.15, 2, 0.05, ed.optDouble("time", 0.6), v -> String.format(Locale.US, "%.2f s", v), "How long it takes to get into and out of this stance",
                v -> host.setStanceValue("time", Math.round(v * 100) / 100.0, "Stance time"), false, dp));
        LinearLayout more = new LinearLayout(c);
        boolean bounce = ed.optBoolean("bounce", false);
        more.addView(chip(c, "Settles with a bounce", bounce, "It goes a little too far and comes back, like something heavy arriving", dp, v -> host.setStanceValue("bounce", !bounce, "Bouncy stance")));
        JSONObject walk = doc.json.optJSONObject("settings") == null ? null : doc.json.optJSONObject("settings").optJSONObject("walk");
        if (fours != null && walk != null) {
            boolean auto = walk.optBoolean("autoFours", true);
            more.addView(chip(c, "Goes down by itself", auto, "On: pushed low it drops onto all fours instead of squatting deeper, and pulled up it stands again. Off: only its button does it", dp, v -> host.setStanceValue("walk.autoFours", !auto, "All fours by itself")));
        }
        col.addView(scroll(c, more));
        LinearLayout end = new LinearLayout(c);
        end.addView(chip(c, "Rename", false, "Give this stance a new name", dp, v -> host.stanceAction("rename")));
        end.addView(chip(c, "Copy", false, "Make a copy of this stance with a button of its own", dp, v -> host.stanceAction("copy")));
        end.addView(chip(c, "Remove", false, "Take this stance away. Undo brings it back", dp, v -> host.stanceAction("remove")));
        col.addView(scroll(c, end));
    }

    // ── small pieces ──────────────────────────────────────────────────────────────────────────────

    private interface Fmt {
        String of(double v);
    }

    private interface Commit {
        void to(double v);
    }

    private static View slider(Context c, String label, double min, double max, double step, double value, Fmt fmt, String hover, Commit commit, boolean live, float dp) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        LinearLayout head = new LinearLayout(c);
        TextView l = text(c, label, 13, Studio.LABEL), val = text(c, fmt.of(value), 12, Studio.INK_DIM);
        head.addView(l, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(val);
        box.addView(head);
        SeekBar sb = new SeekBar(c);
        sb.setMax((int) Math.round((max - min) / step));
        sb.setProgress((int) Math.round((Math.max(min, Math.min(max, value)) - min) / step));
        sb.getProgressDrawable().setTint(Studio.ARMED);
        sb.getThumb().setTint(Studio.ARMED);
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean user) {
                val.setText(fmt.of(min + p * step));
                if (live && user) commit.to(min + p * step);          // "See it" follows the finger and is not a step
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
                if (!live) commit.to(min + s.getProgress() * step);   // everything else: one undo step when the finger lifts
            }
        });
        box.addView(sb);
        box.setPadding(0, Math.round(4 * dp), 0, Math.round(2 * dp));
        TooltipCompat.setTooltipText(box, hover);
        box.setContentDescription(label + ": " + hover);
        return box;
    }

    private static View chip(Context c, String text, boolean on, String hover, float dp, View.OnClickListener tap) {
        TextView t = text(c, text, 12, on ? Studio.GROUND : Studio.LABEL);
        t.setGravity(Gravity.CENTER);
        t.setPadding(Math.round(12 * dp), 0, Math.round(12 * dp), 0);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(16 * dp);
        g.setColor(on ? Studio.ARMED : Studio.RAISED);
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
        TextView t = text(c, s, 12, Studio.INK_FAINT);
        t.setPadding(0, Math.round(8 * dp), 0, Math.round(6 * dp));
        return t;
    }

    private static TextView note(Context c, String s, float dp) {
        TextView t = text(c, s, 13, Studio.INK_DIM);
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
