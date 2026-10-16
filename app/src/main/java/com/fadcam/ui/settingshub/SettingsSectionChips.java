package com.fadcam.ui.settingshub;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.drawable.InsetDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.fadcam.R;
import com.fadcam.ui.faditor.SheetKit;
import com.fadcam.ui.faditor.Studio;
import com.fadcam.ui.lobby.SlantDrawable;
import com.fadcam.ui.type.Type;

import java.util.ArrayList;
import java.util.List;

/**
 * One chip per section, in a row that scrolls sideways, in the thumb's reach: tap one and the list
 * jumps to it, so the bottom of a long screen is never a stretch away. The chip of the section you
 * are looking at is ON (solid cyan, its glyph and words cut out); the rest rest in grey.
 *
 * <p>Chips never move or reorder, so the thumb learns where Recording is. Each is drawn 34dp tall
 * inside a 44dp touch target.</p>
 */
@SuppressLint("ViewConstructor")
final class SettingsSectionChips extends HorizontalScrollView {

    interface Listener {
        void onSection(int section);
    }

    private static final float CHIP_DP = 34f, TARGET_DP = 44f;
    /** The lobby New chips' shear, shared with the Library. */
    private static final int SLANT_DP = 10;

    private final List<Chip> chips = new ArrayList<>();
    private int current = -1;

    SettingsSectionChips(@NonNull Context c, @NonNull Listener listener) {
        super(c);
        setHorizontalScrollBarEnabled(false);
        setOverScrollMode(OVER_SCROLL_NEVER);
        setClipToPadding(false);
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(SettingsViews.dp(c, 12), 0, SettingsViews.dp(c, 12), 0);
        addView(row);
        for (int s = 0; s < SettingsCatalog.SECTIONS.size(); s++) {
            Chip chip = new Chip(c, s, listener);
            chips.add(chip);
            row.addView(chip.view);
        }
        paint();
    }

    /** Hide the chip of a section with nothing shown on this device. */
    void setShown(@NonNull List<List<SettingsKey>> shown) {
        for (int s = 0; s < chips.size(); s++) {
            chips.get(s).view.setVisibility(s < shown.size() && !shown.get(s).isEmpty() ? VISIBLE : GONE);
        }
    }

    /** Mark the section in view, and bring its chip into sight. */
    void setCurrent(int section) {
        if (section == current) return;
        current = section;
        paint();
        if (section < 0 || section >= chips.size()) return;
        View v = chips.get(section).view;
        post(() -> smoothScrollTo(Math.max(0, v.getLeft() - getWidth() / 3), 0));
    }

    private void paint() {
        for (int s = 0; s < chips.size(); s++) chips.get(s).paint(s == current);
    }

    /** One chip: glyph and word. */
    private static final class Chip {
        final LinearLayout view;
        final TextView glyph, label;
        boolean first;

        Chip(@NonNull Context c, int section, @NonNull Listener listener) {
            SettingsCatalog.Section s = SettingsCatalog.SECTIONS.get(section);
            view = new LinearLayout(c);
            view.setOrientation(LinearLayout.HORIZONTAL);
            view.setGravity(Gravity.CENTER_VERTICAL);
            int slant = SettingsViews.dp(c, SLANT_DP);
            // Same gap and padding as the Library chips (owner, 2026-10-17: gap 15% smaller, two thirds of it back as padding).
            int give = Math.max(1, Math.round(3.5f * 0.15f * (2f / 3f) * c.getResources().getDisplayMetrics().density));
            view.setPadding(slant / 2 + SettingsViews.dp(c, 8), 0, slant + SettingsViews.dp(c, 6) + give, 0);
            view.setClickable(true);
            view.setFocusable(true);
            SheetKit.press(view);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                    SettingsViews.dp(c, TARGET_DP));
            lp.setMarginEnd(Math.round(3.5f * 0.85f * c.getResources().getDisplayMetrics().density));
            view.setLayoutParams(lp);
            this.first = section == 0;

            glyph = SettingsViews.glyph(c, 14f, Studio.INK_FAINT);
            glyph.setText(s.glyph);
            LinearLayout.LayoutParams gLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            gLp.setMarginEnd(SettingsViews.dp(c, 6));
            view.addView(glyph, gLp);
            label = SettingsViews.line(c, 12f, Studio.INK_DIM, false, Type.MEDIUM);
            label.setText(s.chip);
            view.addView(label);

            String name = c.getString(s.title);
            SheetKit.label(view, c.getString(R.string.settingshub_jump, name));
            view.setOnClickListener(v -> listener.onSection(section));
        }

        void paint(boolean on) {
            Context c = view.getContext();
            int inset = Math.round((SettingsViews.dp(c, TARGET_DP) - SettingsViews.dp(c, CHIP_DP)) / 2f);
            int slant = SettingsViews.dp(c, SLANT_DP);
            SlantDrawable face = on
                    ? new SlantDrawable(Studio.ARMED_LIGHT, Studio.ARMED, slant, SettingsViews.dp(c, 3), first, false)
                    : new SlantDrawable(Studio.RAISED, Studio.RAISED, slant, SettingsViews.dp(c, 3), first, false);
            view.setBackground(new InsetDrawable(face, 0, inset, 0, inset));
            // setBackground replaces the view's padding with the drawable's own (none): put ours back,
            // or the label runs to the chip's edge and, when chosen, black text spills onto black.
            int give = Math.max(1, Math.round(3.5f * 0.15f * (2f / 3f) * c.getResources().getDisplayMetrics().density));
            view.setPadding(slant / 2 + SettingsViews.dp(c, 8), 0, slant + SettingsViews.dp(c, 6) + give, 0);
            glyph.setTextColor(on ? Studio.GROUND : Studio.INK_FAINT);
            label.setTextColor(on ? Studio.GROUND : Studio.INK_DIM);
            // Weight stays put: a bolder label would be wider than the width the chip was measured at.
            view.setSelected(on);
        }
    }
}
