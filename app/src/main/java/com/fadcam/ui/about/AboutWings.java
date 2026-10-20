package com.fadcam.ui.about;

import android.content.Context;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.StringRes;

import com.fadcam.R;
import com.fadcam.ui.faditor.Studio;
import com.fadcam.ui.type.Type;

/**
 * THE WINGS: what Joy Creator is made of, one row each, each wearing its room's gradient on the
 * lobby's slanted chip with its glyph and name punched out in black, and one plain line saying
 * what you do there.
 *
 * <p>The chips share one width and are squared off on the left, so the column reads as one banded
 * object, the way the lobby's New row does across. They are a colour key, not doors: the rooms are
 * entered from the lobby, and a row of buttons here would be a second front door. Viz Lab is still
 * being built and says so.</p>
 *
 * <p>The names are the lobby's own strings, so a room renamed there is renamed here.</p>
 */
final class AboutWings {

    private static final float CHIP_W_DP = 118f, CHIP_H_DP = 30f;

    private AboutWings() {}

    @NonNull
    static LinearLayout build(@NonNull Context c) {
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(AboutKit.label(c, R.string.aboutjoy_wings_label));

        cc.joycreator.joybrush.android.Palette jb = cc.joycreator.joybrush.android.JbColors.palette(c);
        wing(col, "movie_edit", R.string.lobby_room_studio, R.string.aboutjoy_wing_studio,
                Studio.ROOM_STUDIO, Studio.ROOM_STUDIO_END, false);
        wing(col, "brush", R.string.lobby_room_joybrush, R.string.aboutjoy_wing_joybrush,
                jb.getRoomStart(), jb.getRoomEnd(), false);
        wing(col, "directions_run", R.string.lobby_room_sprites, R.string.aboutjoy_wing_sprites,
                Studio.ROOM_SPRITE, Studio.ROOM_SPRITE_END, false);
        wing(col, "videocam", R.string.lobby_room_capture, R.string.aboutjoy_wing_capture,
                Studio.ROOM_CAPTURE, Studio.ROOM_SPRITE, false);
        wing(col, "auto_awesome", R.string.aboutjoy_wing_joybot, R.string.aboutjoy_wing_joybot_desc,
                Studio.ROOM_AVATAR, Studio.ROOM_AVATAR_DEEP, false);
        wing(col, "graphic_eq", R.string.lobby_room_viz, R.string.aboutjoy_wing_viz,
                Studio.ROOM_VIZ, Studio.ROOM_VIZ_DEEP, true);
        return col;
    }

    private static void wing(@NonNull LinearLayout col, @NonNull String ligature, @StringRes int name,
                             @StringRes int what, int gradA, int gradB, boolean soon) {
        Context c = col.getContext();
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout chip = AboutKit.slantChip(c, ligature, c.getString(name), gradA, gradB,
                CHIP_H_DP, 10.5f);
        chip.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        row.addView(chip, new LinearLayout.LayoutParams(AboutKit.dp(c, CHIP_W_DP), AboutKit.dp(c, CHIP_H_DP)));

        TextView desc = AboutKit.text(c, 12.5f, Studio.INK_DIM, AboutKit.BODY, Type.REGULAR);
        desc.setText(what);
        desc.setMaxLines(2);
        desc.setEllipsize(TextUtils.TruncateAt.END);
        desc.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams dLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        dLp.setMarginStart(AboutKit.dp(c, 12));
        row.addView(desc, dLp);

        if (soon) {
            TextView tag = AboutKit.label(c, R.string.aboutjoy_soon);
            tag.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tLp.setMarginStart(AboutKit.dp(c, 8));
            row.addView(tag, tLp);
        }

        // One row, one sentence for a screen reader: "Studio: Cut video and sound on a timeline".
        String spoken = c.getString(R.string.aboutjoy_wing_spoken, c.getString(name), c.getString(what));
        if (soon) spoken += ". " + c.getString(R.string.aboutjoy_soon);
        row.setContentDescription(spoken);
        row.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        row.setFocusable(true);

        LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rLp.topMargin = AboutKit.dp(c, col.getChildCount() == 1 ? 12 : 7);
        col.addView(row, rLp);
    }
}
