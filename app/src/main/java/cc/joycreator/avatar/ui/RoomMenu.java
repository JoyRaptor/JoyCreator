package cc.joycreator.avatar.ui;

import android.app.Dialog;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.widget.TooltipCompat;

import cc.joycreator.avatar.file.CharacterStore;

import com.fadcam.R;
import com.fadcam.ui.faditor.Studio;

import java.util.List;

/**
 * The Menu (spec A3): the character's name, a row of characters (Topps first, then the ones in the folder, one always
 * cut off at the edge), Open, Save, Close. About 55% see-through, never solid. Only what works is here: Send, scenes,
 * the older room and "Create new avatar" join as they are built.
 */
public final class RoomMenu {

    /** What the menu asks of the room. */
    public interface Host {
        String characterName();

        /** One of the characters that come with the app ({@code CharacterAssets.TOPPS} or {@code PIP}). */
        void openBuiltIn(String asset);

        void openFromFolder(CharacterStore.Entry entry);

        void openFromPicker();

        void saveNow();

        /** The "separate pictures" door: pick pictures, get a new character. */
        void newFromPictures();

        /** The "layered file" door: pick a .psd or .psb, get a new character. */
        void newFromLayeredFile();

        /** "Cut parts from a sheet": one picture with the parts laid out; tap each one. */
        default void newFromSheet() {
        }

        /** "Pin one whole drawing": pick a finished drawing in one piece, tap its head, hands and feet. */
        default void newFromWholeDrawing() {
        }

        /** "Update the art": bring in the repainted layered file; every pin and setting stays. */
        void updateTheArt();
    }

    private RoomMenu() {}

    public static void show(Context c, Host host, List<CharacterStore.Entry> mine) {
        float dp = c.getResources().getDisplayMetrics().density;
        Dialog d = new Dialog(c, android.R.style.Theme_Translucent_NoTitleBar);
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(px(dp, 16), px(dp, 14), px(dp, 16), px(dp, 18));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x8C09090B);               // about 55% see-through; Frost would add blur, never a solid fill
        float r = 18 * dp;
        bg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        box.setBackground(bg);

        TextView title = text(c, host.characterName(), 16, 0xFFFAFAFA);
        box.addView(title);

        HorizontalScrollView scroll = new HorizontalScrollView(c);
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(c);
        row.setPadding(0, px(dp, 12), 0, px(dp, 12));
        row.addView(card(c, dp, c.getString(R.string.avatar_topps), c.getString(R.string.avatar_hover_open_one, c.getString(R.string.avatar_topps)),
                v -> { d.dismiss(); host.openBuiltIn(cc.joycreator.avatar.file.CharacterAssets.TOPPS); }));
        row.addView(card(c, dp, c.getString(R.string.avatar_pip), c.getString(R.string.avatar_hover_open_one, c.getString(R.string.avatar_pip)),
                v -> { d.dismiss(); host.openBuiltIn(cc.joycreator.avatar.file.CharacterAssets.PIP); }));
        for (CharacterStore.Entry e : mine)
            row.addView(card(c, dp, e.name, c.getString(R.string.avatar_hover_open_one, e.name), v -> { d.dismiss(); host.openFromFolder(e); }));
        scroll.addView(row);
        box.addView(scroll);

        box.addView(action(c, dp, c.getString(R.string.avatar_update_art), c.getString(R.string.avatar_hover_update_art), v -> { d.dismiss(); host.updateTheArt(); }));
        box.addView(door(c, dp, c.getString(R.string.avatar_new_from_psd), c.getString(R.string.avatar_door_psd), c.getString(R.string.avatar_hover_new_from_psd), v -> { d.dismiss(); host.newFromLayeredFile(); }));
        box.addView(door(c, dp, c.getString(R.string.avatar_new_sheet), c.getString(R.string.avatar_door_sheet), c.getString(R.string.avatar_hover_new_sheet), v -> { d.dismiss(); host.newFromSheet(); }));
        box.addView(door(c, dp, c.getString(R.string.avatar_new_whole), c.getString(R.string.avatar_door_whole), c.getString(R.string.avatar_hover_new_whole), v -> { d.dismiss(); host.newFromWholeDrawing(); }));
        box.addView(door(c, dp, c.getString(R.string.avatar_new_from_pictures), c.getString(R.string.avatar_door_pictures), c.getString(R.string.avatar_hover_new_from_pictures), v -> { d.dismiss(); host.newFromPictures(); }));
        box.addView(action(c, dp, c.getString(R.string.avatar_about_title), c.getString(R.string.avatar_hover_about), v -> about(c)));
        box.addView(action(c, dp, c.getString(R.string.avatar_open), c.getString(R.string.avatar_hover_open), v -> { d.dismiss(); host.openFromPicker(); }));
        box.addView(action(c, dp, c.getString(R.string.avatar_save), c.getString(R.string.avatar_hover_save), v -> { d.dismiss(); host.saveNow(); }));
        box.addView(action(c, dp, c.getString(R.string.avatar_close), c.getString(R.string.avatar_hover_close), v -> d.dismiss()));

        d.setContentView(box);
        Window w = d.getWindow();
        if (w != null) {
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setGravity(Gravity.BOTTOM);
            w.setDimAmount(0.4f);
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        }
        d.show();
    }

    private static View card(Context c, float dp, String name, String hover, View.OnClickListener tap) {
        TextView t = text(c, name, 12, 0xFFD4D4D8);
        t.setGravity(Gravity.CENTER);
        t.setMaxLines(2);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(10 * dp);
        g.setColor(0xFF27272A);
        t.setBackground(g);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(dp, 58), px(dp, 52));
        lp.rightMargin = px(dp, 8);
        t.setLayoutParams(lp);
        t.setOnClickListener(tap);
        TooltipCompat.setTooltipText(t, hover);
        t.setContentDescription(hover);
        return t;
    }

    private static View action(Context c, float dp, String label, String hover, View.OnClickListener tap) {
        TextView t = text(c, label, 14, 0xFFFAFAFA);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setMinHeight(px(dp, 44));
        t.setOnClickListener(tap);
        TooltipCompat.setTooltipText(t, hover);
        t.setContentDescription(hover);
        return t;
    }

    /** A door: its name, and under it one sentence that says what it takes (the pilot's door words). */
    private static View door(Context c, float dp, String label, String sub, String hover, View.OnClickListener tap) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setMinimumHeight(px(dp, 52));
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.addView(text(c, label, 14, 0xFFFAFAFA));
        box.addView(text(c, sub, 11, 0xFF71717A));
        box.setPadding(0, px(dp, 4), 0, px(dp, 4));
        box.setOnClickListener(tap);
        TooltipCompat.setTooltipText(box, hover);
        box.setContentDescription(label + ". " + sub);
        return box;
    }

    /** "What can this do?": what the room does, in plain words. */
    private static void about(Context c) {
        TextView t = new TextView(c);
        t.setText(c.getString(R.string.avatar_about_body));
        t.setTextColor(0xFFD4D4D8);
        t.setTextSize(14);
        int p = Math.round(20 * c.getResources().getDisplayMetrics().density);
        t.setPadding(p, p / 2, p, 0);
        android.widget.ScrollView sv = new android.widget.ScrollView(c);
        sv.addView(t);
        new android.app.AlertDialog.Builder(c, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle(R.string.avatar_about_title).setView(sv)
                .setPositiveButton(R.string.avatar_done, null).show();
    }

    private static TextView text(Context c, String s, int sp, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    private static int px(float dp, float v) {
        return Math.round(v * dp);
    }
}
