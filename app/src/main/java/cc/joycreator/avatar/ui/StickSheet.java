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

    private static void fill(Context c, LinearLayout body, Stick stick, String characterName, Host host, float dp) {
        body.removeAllViews();
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
