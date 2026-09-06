package com.fadcam.ui.faditor.tools;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.StringRes;

import com.fadcam.R;
import com.fadcam.ui.faditor.model.BlendModes;

/**
 * THE blend-mode picker: one chip showing the current mode, tapped to open a grouped popover of
 * every mode.
 *
 * <p><b>Why it stopped being a chip strip.</b> With seven modes a horizontal strip of chips was
 * fine. With twenty-six it is a scroll bar disguised as a control — the user drags sideways past
 * modes they are not looking for to reach the one they are. JoyRaptor's ruling (2026-09-04): "It
 * shouldn't be a series of chips, but ONE CHIP that when you tap it brings up a list… categorized
 * maybe in SEVERAL COLUMNS, otherwise it'll be too much slow scrolling."</p>
 *
 * <p><b>Columns, not a list.</b> Twenty-six 44dp rows in one column is 1140dp of scrolling. In
 * three columns it is one screenful, so the whole vocabulary is visible at once and picking is a
 * glance plus a tap rather than a hunt. The groups are the standard ones every other compositor
 * uses (Normal · Darken · Lighten · Contrast · Comparative · Color), each under a small grey
 * heading, so a user who knows Photoshop finds a mode where they expect it.</p>
 *
 * <p><b>One place, three call sites.</b> {@link PipDrawerTabs#blendTab}, {@code FxPanel}'s per-card
 * fold row and the editor's PiP menu item all open THIS popover, so the grouping, the labels and
 * the current-mode marking cannot drift apart between them. The mode list itself is not restated
 * here either — {@link BlendModes#GROUPED} is asserted against {@link BlendModes#ALL} by the
 * harness, so a mode added to the authority and forgotten here is a test failure, not a mode the
 * user simply cannot reach.</p>
 */
public final class BlendPickerPopover {

    private BlendPickerPopover() {}

    public interface OnPick { void onPick(@NonNull String mode); }

    private static final int SHEET_BG = 0xFF1C1C1E;
    private static final int TXT = 0xFFE8E8E8;
    private static final int TXT_HEADING = 0xFF8A8A8E;   // the "small grey text" category label
    private static final int ROW_ON = 0x338C3DFA;
    private static final int ACCENT = 0xFFB07CFF;
    private static final int CHIP_BG = 0x22FFFFFF;

    /**
     * Which column each of {@link BlendModes#GROUPED}'s groups lands in. Hand-assigned rather than
     * computed so the three columns come out roughly level: {Normal, Darken} = 6 rows,
     * {Lighten, Comparative} = 9, {Contrast, Color} = 11.
     *
     * <p>The GROUPS THEMSELVES are not restated here. They live in {@link BlendModes} beside the
     * mode list they partition, where the harness can prove the partition is exact — a grouping
     * kept in the UI would let a mode ship in the shader and be unreachable in the picker.</p>
     */
    private static final int COLUMNS = 3;
    private static final int[] GROUP_COLUMN = {0, 0, 1, 2, 1, 2};

    /** Category key ({@link BlendModes#GROUP_KEYS}) → its small grey heading. */
    @StringRes
    private static int groupLabelRes(@NonNull String key) {
        switch (key) {
            case "DARKEN":      return R.string.faditor_blend_group_darken;
            case "LIGHTEN":     return R.string.faditor_blend_group_lighten;
            case "CONTRAST":    return R.string.faditor_blend_group_contrast;
            case "COMPARATIVE": return R.string.faditor_blend_group_comparative;
            case "COLOR":       return R.string.faditor_blend_group_color;
            default:            return R.string.faditor_blend_group_normal;
        }
    }

    /** Wire value → its display string. The one mapping; every call site reads it. */
    @StringRes
    public static int labelRes(@NonNull String mode) {
        switch (mode) {
            case BlendModes.MULTIPLY:      return R.string.faditor_blend_multiply;
            case BlendModes.SCREEN:        return R.string.faditor_blend_screen;
            case BlendModes.OVERLAY:       return R.string.faditor_blend_overlay;
            case BlendModes.ADD:           return R.string.faditor_blend_add;
            case BlendModes.DIFFERENCE:    return R.string.faditor_blend_difference;
            case BlendModes.COLOR:         return R.string.faditor_blend_color;
            case BlendModes.DARKEN:        return R.string.faditor_blend_darken;
            case BlendModes.LIGHTEN:       return R.string.faditor_blend_lighten;
            case BlendModes.COLOR_DODGE:   return R.string.faditor_blend_color_dodge;
            case BlendModes.COLOR_BURN:    return R.string.faditor_blend_color_burn;
            case BlendModes.LINEAR_BURN:   return R.string.faditor_blend_linear_burn;
            case BlendModes.HARD_LIGHT:    return R.string.faditor_blend_hard_light;
            case BlendModes.SOFT_LIGHT:    return R.string.faditor_blend_soft_light;
            case BlendModes.VIVID_LIGHT:   return R.string.faditor_blend_vivid_light;
            case BlendModes.LINEAR_LIGHT:  return R.string.faditor_blend_linear_light;
            case BlendModes.PIN_LIGHT:     return R.string.faditor_blend_pin_light;
            case BlendModes.HARD_MIX:      return R.string.faditor_blend_hard_mix;
            case BlendModes.EXCLUSION:     return R.string.faditor_blend_exclusion;
            case BlendModes.SUBTRACT:      return R.string.faditor_blend_subtract;
            case BlendModes.DIVIDE:        return R.string.faditor_blend_divide;
            case BlendModes.DARKER_COLOR:  return R.string.faditor_blend_darker_color;
            case BlendModes.LIGHTER_COLOR: return R.string.faditor_blend_lighter_color;
            case BlendModes.HUE:           return R.string.faditor_blend_hue;
            case BlendModes.SATURATION:    return R.string.faditor_blend_saturation;
            case BlendModes.LUMINOSITY:    return R.string.faditor_blend_luminosity;
            default:                       return R.string.faditor_blend_normal;
        }
    }

    /**
     * The ONE chip. Shows the current mode's name; tapping it opens the popover; picking updates
     * the chip in place, so the control always reads as the answer to "what is this layer doing".
     */
    @NonNull
    public static TextView chip(@NonNull Context ctx,
                                @NonNull java.util.function.Supplier<String> getMode,
                                @NonNull java.util.function.Consumer<String> setMode,
                                @NonNull Runnable apply) {
        float d = ctx.getResources().getDisplayMetrics().density;
        final TextView chip = new TextView(ctx);
        chip.setTextColor(TXT);
        chip.setTextSize(12f);
        chip.setShadowLayer(3f * d, 0f, 1f, 0xCC000000);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        int px = Math.round(14 * d), py = Math.round(9 * d);
        chip.setPadding(px, py, px, py);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(14f * d);
        bg.setColor(CHIP_BG);
        bg.setStroke(Math.round(1 * d), 0x33FFFFFF);
        chip.setBackground(bg);
        chip.setMinHeight(Math.round(44 * d));
        chip.setText(chipText(ctx, getMode.get()));
        chip.setOnClickListener(v -> show(v, getMode.get(), picked -> {
            setMode.accept(picked);
            chip.setText(chipText(ctx, picked));
            apply.run();
        }));
        return chip;
    }

    @NonNull
    private static String chipText(@NonNull Context ctx, @NonNull String mode) {
        // The ▾ is what says "this opens something" — without it a single chip reads as a toggle.
        return ctx.getString(labelRes(mode)) + "  ▾";
    }

    /** Open the picker hanging off {@code anchor} (a chip). */
    public static void show(@NonNull View anchor, @NonNull String current,
                            @NonNull OnPick onPick) {
        showInternal(anchor, current, onPick, false);
    }

    /**
     * Open the picker centred on the window — for a call site with no chip to hang off, such as
     * the editor's PiP overflow menu, which used to raise a {@code setSingleChoiceItems} dialog.
     */
    public static void showCentered(@NonNull View root, @NonNull String current,
                                    @NonNull OnPick onPick) {
        showInternal(root, current, onPick, true);
    }

    private static void showInternal(@NonNull View anchor, @NonNull String current,
                                     @NonNull OnPick onPick, boolean centered) {
        Context ctx = anchor.getContext();
        DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        float d = dm.density;

        // Width: the whole screen bar a margin, capped — three columns want the room on a phone,
        // but a tablet must not stretch a 26-entry list across 900dp of nothing.
        int width = Math.min(dm.widthPixels - Math.round(20 * d), Math.round(430 * d));
        int colW = (width - Math.round(24 * d)) / COLUMNS;

        LinearLayout sheet = new LinearLayout(ctx);
        sheet.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(SHEET_BG);
        bg.setCornerRadius(16 * d);
        sheet.setBackground(bg);
        sheet.setElevation(16 * d);
        int pad = Math.round(12 * d);
        sheet.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(ctx);
        title.setText(R.string.faditor_blend_title);
        title.setTextColor(TXT);
        title.setTextSize(14f);
        title.setPadding(Math.round(4 * d), 0, 0, Math.round(8 * d));
        sheet.addView(title);

        LinearLayout cols = new LinearLayout(ctx);
        cols.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout[] col = new LinearLayout[COLUMNS];
        for (int i = 0; i < COLUMNS; i++) {
            col[i] = new LinearLayout(ctx);
            col[i].setOrientation(LinearLayout.VERTICAL);
            cols.addView(col[i], new LinearLayout.LayoutParams(colW,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
        }

        final PopupWindow pop = new PopupWindow(ctx);
        pop.setFocusable(true);
        pop.setOutsideTouchable(true);
        pop.setElevation(16 * d);

        for (int g = 0; g < BlendModes.GROUPED.length; g++) {
            LinearLayout host = col[GROUP_COLUMN[Math.min(g, GROUP_COLUMN.length - 1)]];
            TextView heading = new TextView(ctx);
            heading.setText(groupLabelRes(BlendModes.GROUP_KEYS[
                    Math.min(g, BlendModes.GROUP_KEYS.length - 1)]));
            heading.setTextColor(TXT_HEADING);
            heading.setTextSize(10f);
            heading.setAllCaps(true);
            heading.setLetterSpacing(0.08f);
            heading.setPadding(Math.round(6 * d), Math.round(8 * d), 0, Math.round(2 * d));
            host.addView(heading);
            for (String mode : BlendModes.GROUPED[g]) {
                host.addView(row(ctx, d, colW, mode, mode.equals(current), picked -> {
                    pop.dismiss();
                    onPick.onPick(picked);
                }));
            }
        }

        // Capped height: three columns fit a phone without scrolling, but a large font scale or a
        // short landscape window must still be able to reach the bottom row.
        ScrollView scroll = new ScrollView(ctx);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(cols);
        sheet.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        // Same immersive-window trap PivotPickerPopover documents: a focusable popup without
        // the host's flags reveals the system bars and shifts the content under the handles.
        sheet.setSystemUiVisibility(anchor.getRootView().getSystemUiVisibility());
        pop.setContentView(sheet);
        pop.setWidth(width);
        pop.setHeight(LinearLayout.LayoutParams.WRAP_CONTENT);
        pop.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0x00000000));
        int maxH = Math.round(dm.heightPixels * 0.82f);
        sheet.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.UNSPECIFIED);
        if (sheet.getMeasuredHeight() > maxH) pop.setHeight(maxH);

        if (centered) {
            pop.showAtLocation(anchor, Gravity.CENTER, 0, 0);
        } else {
            // Prefer ABOVE the chip: these chips sit in a drawer near the bottom of the screen,
            // so dropping down would open the picker off the edge.
            int h = Math.min(sheet.getMeasuredHeight(), maxH);
            int[] loc = new int[2];
            anchor.getLocationInWindow(loc);
            int gap = Math.round(6 * d);
            if (loc[1] - h - gap > 0) {
                pop.showAsDropDown(anchor, 0, -(h + anchor.getHeight() + gap), Gravity.START);
            } else {
                pop.showAsDropDown(anchor, 0, gap, Gravity.START);
            }
        }
    }

    @NonNull
    private static View row(@NonNull Context ctx, float d, int colW, @NonNull String mode,
                            boolean selected, @NonNull OnPick tap) {
        TextView t = new TextView(ctx);
        // The ✓ marks the current mode as well as the tint does: a colour difference alone is not
        // readable for every user, and this row is how you find out what the layer is set to.
        t.setText(selected ? "✓ " + ctx.getString(labelRes(mode)) : ctx.getString(labelRes(mode)));
        t.setTextColor(selected ? ACCENT : TXT);
        t.setTextSize(13f);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setPadding(Math.round(8 * d), 0, Math.round(6 * d), 0);
        if (selected) {
            GradientDrawable on = new GradientDrawable();
            on.setCornerRadius(9 * d);
            on.setColor(ROW_ON);
            t.setBackground(on);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                colW - Math.round(2 * d), Math.round(44 * d)); // 44dp: the minimum tap target
        lp.bottomMargin = Math.round(2 * d);
        t.setLayoutParams(lp);
        t.setOnClickListener(v -> tap.onPick(mode));
        return t;
    }
}
