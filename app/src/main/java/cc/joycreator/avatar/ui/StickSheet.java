package cc.joycreator.avatar.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.widget.TooltipCompat;

import cc.joycreator.avatar.perform.Stick;
import com.fadcam.R;

import com.fadcam.ui.faditor.Studio;

import java.util.Map;

/**
 * The joystick's gestures (al-stick-moves.js, the sheet): six slots, each offering only what this character can do. Choosing
 * one is one undo step, made by the room; the sheet redraws to show it.
 */
public final class StickSheet {
    public interface Host {
        /** Fill a slot: one named undo step. */
        void setSlot(String slot, String key, String stepName);

        /** "Up and down walk into the picture": one named undo step. */
        void setDepth(boolean on, String stepName);

        /** The camera's angle, 0 (overhead) to 75: one named undo step. */
        void setAngle(int deg, String stepName);
    }

    private StickSheet() {
    }

    public static void show(Context c, Stick stick, String characterName, Host host) {
        final float dp = c.getResources().getDisplayMetrics().density;
        ScrollView sv = new ScrollView(c);
        LinearLayout body = new LinearLayout(c);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding((int) (16 * dp), (int) (8 * dp), (int) (16 * dp), (int) (8 * dp));
        sv.addView(body);
        final AlertDialog d = new AlertDialog.Builder(c, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(R.string.avatar_stick_sheet_title).setView(sv).setPositiveButton(R.string.avatar_done, null).create();
        fill(c, body, stick, characterName, host, dp);
        d.show();
    }

    /** The tick box and the camera angle, at the top: up and down walk into the picture. */
    private static void depth(Context c, LinearLayout body, Stick stick, String characterName, Host host, float dp) {
        final boolean on = stick.depthOn();
        LinearLayout row = new LinearLayout(c);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView box = new TextView(c);
        box.setGravity(Gravity.CENTER);
        box.setTextSize(15);
        box.setText(on ? "\u2713" : "");
        box.setTextColor(Studio.GROUND);                                    // ON = solid cyan with the tick punched out
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(5 * dp);
        g.setColor(on ? Studio.ARMED : 0xFF27272A);
        if (!on) g.setStroke((int) Math.max(1, dp), 0xFF52525B);
        box.setBackground(g);
        row.addView(box, new LinearLayout.LayoutParams((int) (24 * dp), (int) (24 * dp)));
        TextView label = new TextView(c);
        label.setText(R.string.avatar_stick_depth);
        label.setTextColor(0xFFE4E4E7);
        label.setTextSize(13);
        label.setPadding((int) (10 * dp), 0, 0, 0);
        row.addView(label);
        row.setOnClickListener(v -> {
            host.setDepth(!on, c.getString(R.string.avatar_stick_depth_step, c.getString(on ? R.string.avatar_stick_depth_off : R.string.avatar_stick_depth_on)));
            fill(c, body, stick, characterName, host, dp);
        });
        TooltipCompat.setTooltipText(row, c.getString(R.string.avatar_hover_stick_depth));
        row.setContentDescription(c.getString(R.string.avatar_hover_stick_depth));
        body.addView(row);
        if (!on) return;
        final TextView angle = new TextView(c);
        angle.setTextColor(0xFF71717A);
        angle.setTextSize(11);
        angle.setPadding(0, (int) (10 * dp), 0, 0);
        paintAngle(c, angle, (int) Math.round(stick.angle()));
        body.addView(angle);
        android.widget.SeekBar sb = new android.widget.SeekBar(c);
        sb.setMax(75);
        sb.setProgress((int) Math.round(stick.angle()));
        sb.getProgressDrawable().setTint(Studio.ARMED);
        sb.getThumb().setTint(Studio.ARMED);
        sb.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(android.widget.SeekBar s, int p, boolean user) {
                paintAngle(c, angle, p);
            }

            public void onStartTrackingTouch(android.widget.SeekBar s) {
            }

            public void onStopTrackingTouch(android.widget.SeekBar s) {
                host.setAngle(s.getProgress(), c.getString(R.string.avatar_stick_angle_step));
            }
        });
        TooltipCompat.setTooltipText(sb, c.getString(R.string.avatar_hover_stick_angle));
        sb.setContentDescription(c.getString(R.string.avatar_hover_stick_angle));
        body.addView(sb);
    }

    private static void paintAngle(Context c, TextView t, int deg) {
        t.setText(c.getString(R.string.avatar_stick_angle, deg < 3 ? c.getString(R.string.avatar_stick_overhead) : deg + "\u00B0"));
    }

    private static void fill(Context c, LinearLayout body, Stick stick, String characterName, Host host, float dp) {
        body.removeAllViews();
        if (stick.canDepth()) depth(c, body, stick, characterName, host, dp);
        Map<String, String> m = stick.map();
        java.util.List<Stick.Act> acts = stick.acts();
        for (String[] s : Stick.SLOTS) {
            String cur = "Nothing";
            for (Stick.Act a : acts) if (a.key.equals(m.get(s[0]))) cur = a.name;
            LinearLayout head = new LinearLayout(c);
            head.setGravity(Gravity.CENTER_VERTICAL);
            TextView name = new TextView(c);
            name.setText(s[1]);
            name.setTextColor(0xFFE4E4E7);
            name.setTextSize(13);
            name.setTypeface(null, android.graphics.Typeface.BOLD);
            head.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView now = new TextView(c);
            now.setText(cur);
            now.setTextColor(0xFF71717A);
            now.setTextSize(11);
            head.addView(now);
            LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            hp.topMargin = (int) (12 * dp);
            hp.bottomMargin = (int) (6 * dp);
            body.addView(head, hp);
            HorizontalScrollView hs = new HorizontalScrollView(c);
            hs.setHorizontalScrollBarEnabled(false);
            LinearLayout row = new LinearLayout(c);
            for (Stick.Act a : acts) {
                final String slot = s[0], slotName = s[1];
                boolean on = a.key.equals(m.get(slot));
                TextView chip = new TextView(c);
                chip.setText(a.name);
                chip.setTextSize(12);
                chip.setGravity(Gravity.CENTER);
                chip.setPadding((int) (12 * dp), 0, (int) (12 * dp), 0);
                GradientDrawable g = new GradientDrawable();
                g.setCornerRadius(16 * dp);
                g.setColor(on ? Studio.ARMED : 0xFF27272A);          // ON = solid cyan, the label punched out
                chip.setBackground(g);
                chip.setTextColor(on ? Studio.GROUND : 0xFFD4D4D8);
                TooltipCompat.setTooltipText(chip, slotName + ": " + a.title);
                chip.setContentDescription(slotName + ": " + a.title);
                chip.setOnClickListener(v -> {
                    host.setSlot(slot, a.key, c.getString(R.string.avatar_stick_step, slotName, a.name));
                    fill(c, body, stick, characterName, host, dp);
                });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, (int) (32 * dp));
                lp.rightMargin = (int) (6 * dp);
                row.addView(chip, lp);
            }
            hs.addView(row);
            body.addView(hs);
        }
        TextView note = new TextView(c);
        note.setText(c.getString(R.string.avatar_stick_note, characterName));
        note.setTextColor(0xFF71717A);
        note.setTextSize(11);
        note.setPadding(0, (int) (14 * dp), 0, 0);
        body.addView(note);
    }
}
