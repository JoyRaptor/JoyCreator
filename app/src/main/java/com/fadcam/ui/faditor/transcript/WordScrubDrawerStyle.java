package com.fadcam.ui.faditor.transcript;

import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.R;
import com.fadcam.ui.faditor.Studio;
import com.fadcam.ui.faditor.layers.ObjectPalette;
import com.fadcam.ui.faditor.tools.ObjectDrawer;

/**
 * The transcript WORD drawer (long-press a word), in the Studio's drawer look.
 *
 * <p>JoyRaptor, 2026-09-25: "Functionally it works great ... visually it needs a refresh to tie
 * in with the rest ... keep things around the same size." So nothing here changes what a
 * control does or how big it is: the panel takes the see-through drawer fill with rounded bottom
 * corners, its chips take {@link ObjectDrawer.Kit}'s on/off face in the caption colour, and the
 * icons take the drawer ink ramp. B / U / I are hidden: all three only ever said "isn't built
 * yet", and a control that cannot do anything is worse than no control.
 */
public final class WordScrubDrawerStyle {

    private WordScrubDrawerStyle() {}

    /** The object colour this drawer edits: captions. */
    private static final int ACCENT = ObjectPalette.CAPTION;

    /** One-time restyle of the inflated drawer. */
    public static void apply(@NonNull View drawer) {
        float d = drawer.getResources().getDisplayMetrics().density;
        GradientDrawable bg = new GradientDrawable();
        float r = 18f * d;
        bg.setCornerRadii(new float[]{0f, 0f, 0f, 0f, r, r, r, r});
        drawer.setBackground(bg);
        ObjectDrawer.Kit.followDrawerFill(drawer);

        EditText word = drawer.findViewById(R.id.word_scrub_word_text);
        if (word != null) word.setHintTextColor(Studio.DRAWER_DIM);

        TextView ts = drawer.findViewById(R.id.word_scrub_timestamp);
        if (ts != null) {
            ObjectDrawer.Kit.setChipOn(ts, false, ACCENT);
            ts.setTextColor(Studio.DRAWER_INK);
            ts.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
            ObjectDrawer.Kit.describe(ts, drawer.getContext().getString(R.string.word_drawer_time));
        }
        icon(drawer, R.id.word_scrub_prev, Studio.DRAWER_DIM, R.string.word_drawer_earlier);
        icon(drawer, R.id.word_scrub_next, Studio.DRAWER_DIM, R.string.word_drawer_later);
        icon(drawer, R.id.word_scrub_center, Studio.DRAWER_DIM, R.string.word_drawer_center);
        icon(drawer, R.id.word_scrub_close, Studio.DRAWER_INK, R.string.word_drawer_close);

        chip(drawer, R.id.word_sync_tt, R.string.word_drawer_case_upper);
        chip(drawer, R.id.word_sync_tT, R.string.word_drawer_case_title);
        chip(drawer, R.id.word_sync_tt_low, R.string.word_drawer_case_lower);
        chip(drawer, R.id.word_sync_block, 0);
        chip(drawer, R.id.word_sync_ripple, R.string.word_drawer_ripple);
        chip(drawer, R.id.word_sync_snap, R.string.word_drawer_snap);

        for (int id : new int[]{R.id.word_sync_b, R.id.word_sync_u, R.id.word_sync_i}) {
            View v = drawer.findViewById(id);
            if (v != null) v.setVisibility(View.GONE);
        }
    }

    /** The word, the block toggle, the ripple and snap chips, for the current state. */
    public static void state(@NonNull View drawer, boolean blockMode, @Nullable String rippleName,
                             boolean snapOn) {
        EditText word = drawer.findViewById(R.id.word_scrub_word_text);
        if (word != null) {
            // One block reads as a unit: monospace, in the caption colour. Words: plain ink.
            word.setTypeface(blockMode ? android.graphics.Typeface.MONOSPACE
                    : android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
            word.setTextColor(blockMode ? ACCENT : Studio.DRAWER_INK);
        }
        TextView block = drawer.findViewById(R.id.word_sync_block);
        if (block != null) {
            ObjectDrawer.Kit.setChipOn(block, blockMode, ACCENT);
            block.setTypeface(androidx.core.content.res.ResourcesCompat.getFont(
                    drawer.getContext(), R.font.materialicons));
        }
        TextView ripple = drawer.findViewById(R.id.word_sync_ripple);
        if (ripple != null) {
            if (rippleName != null) ripple.setText(rippleName);
            ObjectDrawer.Kit.setChipOn(ripple, true, ACCENT);
        }
        TextView snap = drawer.findViewById(R.id.word_sync_snap);
        if (snap != null) {
            snap.setText(R.string.word_drawer_snap);
            ObjectDrawer.Kit.setChipOn(snap, snapOn, ACCENT);
        }
    }

    private static void icon(@NonNull View drawer, int id, int tint, int label) {
        View v = drawer.findViewById(id);
        if (!(v instanceof TextView)) return;
        ((TextView) v).setTextColor(tint);
        ObjectDrawer.Kit.describe(v, drawer.getContext().getString(label));
        ObjectDrawer.Kit.pressable(v);
    }

    private static void chip(@NonNull View drawer, int id, int label) {
        View v = drawer.findViewById(id);
        if (!(v instanceof TextView)) return;
        ObjectDrawer.Kit.setChipOn((TextView) v, false, ACCENT);
        if (label != 0) ObjectDrawer.Kit.describe(v, drawer.getContext().getString(label));
        ObjectDrawer.Kit.pressable(v);
    }
}
