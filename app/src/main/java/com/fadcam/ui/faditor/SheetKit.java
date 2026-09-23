package com.fadcam.ui.faditor;

import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.StateListAnimator;
import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.TooltipCompat;
import androidx.core.content.res.ResourcesCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.fadcam.R;
import com.fadcam.ui.type.Type;

/**
 * THE PARTS EVERY EDITOR SHEET IS BUILT FROM.
 *
 * <p>JoyRaptor asked for "things calling helpers instead of hard coded so there are fewer areas
 * to break." Fourteen sheets each hand-built their own title, row, chip and button, and no two
 * agreed on a padding, a size or which grey meant what. Every one of those now comes from here,
 * so a sheet says WHAT it contains and this file decides what that looks like.</p>
 *
 * <h3>Where the numbers come from</h3>
 * <ul>
 *   <li>Header: record 06 {@code .sheet .sh} — Archivo 14.5 w800, mono 8.5 count, padding
 *       11 / 13 / 8.</li>
 *   <li>Section label: record 06 {@code .tg} — mono 8sp, letter-spacing .14em, uppercase, 5dp
 *       dot.</li>
 *   <li>Grab pill: record 06 {@code .dgrab} — 38 x 3.5 in a 13dp strip.</li>
 *   <li>Sheet: record 06 — 20dp top corners, PANEL. Record 01 §05 (the pill language) — flat
 *       controls, no border, RAISED fill, fully round chips and buttons.</li>
 *   <li>Rows sit 12dp in from the sheet edge with an 8dp radius, so the corners are concentric
 *       with the sheet's 20dp (outer = inner + gap).</li>
 *   <li>Press: 140ms to scale .97. Focus: a 2dp ARMED ring, instant (record 06 MINOR 10).</li>
 * </ul>
 *
 * <p>Values are taken as drawn, not scaled up for a wider screen — "things are a bit smaller,
 * so more fits on the screen."</p>
 *
 * <p>These sheets are OPAQUE (panel, not scrim), so everything here uses the SCREEN ink ramp.
 * The drawer ramp is only for text over the translucent scrim.</p>
 */
public final class SheetKit {

    private SheetKit() { }

    // ── measurements ────────────────────────────────────────────────────────
    public static final float TITLE_SP   = 14.5f;   // .sh b
    public static final float COUNT_SP   = 8.5f;    // .sh s
    public static final float SECTION_SP = 8f;      // .tg
    public static final float ROW_SP     = 14f;
    public static final float ROW_ICON_SP = 18f;
    public static final float DESC_SP    = 12f;
    public static final float CHIP_SP    = 12f;

    public static final int SHEET_RADIUS_DP = 20;
    public static final int ROW_INSET_DP    = 12;
    public static final int ROW_RADIUS_DP   = SHEET_RADIUS_DP - ROW_INSET_DP;   // concentric
    public static final int ROW_MIN_H_DP    = 46;                               // record 06 .dr
    public static final int PRESS_MS        = 140;
    private static final float PRESS_SCALE  = 0.97f;

    // ── ink, by role ────────────────────────────────────────────────────────
    /** The words on a row. */
    public static final int ROW_LABEL = Studio.INK;
    /** A row's leading glyph at rest — it recedes so the words lead. */
    public static final int ROW_ICON  = Studio.INK_DIM;
    /** Secondary copy: descriptions, subtitles, meta lines. */
    public static final int ROW_DESC  = Studio.INK_FAINT;
    /** A trailing chevron: present, not asking to be read. */
    public static final int ROW_CHEVRON = Studio.INK_OFF;

    /** Trailing mark on a row. */
    public enum Trail { NONE, CHEVRON, CHECK }

    /** Run once the sheet's frame exists — for the few sheets that also size or pin it. */
    public interface Ready {
        void onReady(@NonNull View sheet);
    }

    // ── the sheet itself ────────────────────────────────────────────────────

    public static int dp(@NonNull Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    /**
     * Paint the Material sheet frame when the dialog shows, then hand it to {@code extra}.
     * Replaces the per-sheet {@code setOnShowListener} that each file carried a copy of.
     */
    public static void install(@NonNull Dialog dialog, @Nullable Ready extra) {
        dialog.setOnShowListener(d -> {
            View sheet = dialog.findViewById(com.google.android.material.R.id.design_bottom_sheet);
            if (sheet != null) {
                sheet.setBackground(sheetBackground(sheet.getContext()));
                if (extra != null) extra.onReady(sheet);
            }
        });
    }

    /** PANEL, 20dp top corners, flat. */
    @NonNull
    public static Drawable sheetBackground(@NonNull Context c) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Studio.PANEL);
        float r = dp(c, SHEET_RADIUS_DP);
        bg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        return bg;
    }

    /**
     * Keep a sheet's last row clear of the navigation / gesture bar. Idempotent: the base
     * padding is captured once and the inset is added to it on every dispatch, so a repeat
     * dispatch never stacks. Where the window already stops above the bar the inset is 0 and
     * nothing changes.
     */
    @NonNull
    public static <T extends View> T fitNavBar(@NonNull T content) {
        final int baseBottom = content.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(content, (v, insets) -> {
            int bottom = Math.max(
                    insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom,
                    insets.getInsets(WindowInsetsCompat.Type.mandatorySystemGestures()).bottom);
            v.setPadding(v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(),
                    baseBottom + bottom);
            return insets;
        });
        return content;
    }

    // ── header ──────────────────────────────────────────────────────────────

    /** Record 06 {@code .dgrab}: a 38 x 3.5 pill centred in a 13dp strip. */
    @NonNull
    public static View grab(@NonNull Context c) {
        FrameLayout strip = new FrameLayout(c);
        strip.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(c, 13)));
        View pill = new View(c);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Studio.alpha(Studio.INK, 0x4D));   // rgba(255,255,255,.3) on the screen ramp
        bg.setCornerRadius(dp(c, 2));
        pill.setBackground(bg);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                dp(c, 38), Math.max(1, Math.round(3.5f * c.getResources().getDisplayMetrics().density)));
        lp.gravity = Gravity.CENTER;
        strip.addView(pill, lp);
        return strip;
    }

    /**
     * A sheet header: grab pill, then {@code title} in Archivo 14.5 w800 and an optional mono
     * count, with a flexible gap after them so {@link #addTrailing} lands on the right.
     */
    @NonNull
    public static Header header(@NonNull Context c, @NonNull CharSequence title,
                                @Nullable CharSequence count) {
        LinearLayout column = new LinearLayout(c);
        column.setOrientation(LinearLayout.VERTICAL);
        column.addView(grab(c));

        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        // .sh is 11 / 13 / 8; the grab strip above already supplies most of the 11.
        row.setPadding(dp(c, 13), dp(c, 2), dp(c, 13), dp(c, 8));
        row.setMinimumHeight(dp(c, 40));

        TextView t = new TextView(c);
        t.setText(title);
        t.setTextColor(Studio.INK);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, TITLE_SP);
        Type.display(t, Type.EXTRA);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(t, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView n = new TextView(c);
        n.setTextColor(Studio.INK_DIM);
        n.setTextSize(TypedValue.COMPLEX_UNIT_SP, COUNT_SP);
        Type.mono(n, Type.REGULAR);
        n.setSingleLine(true);
        LinearLayout.LayoutParams nLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nLp.setMarginStart(dp(c, 8));
        row.addView(n, nLp);
        if (count == null) n.setVisibility(View.GONE); else n.setText(count);

        View gap = new View(c);
        row.addView(gap, new LinearLayout.LayoutParams(0, 1, 1f));

        column.addView(row);
        return new Header(column, row, t, n);
    }

    /** What {@link #header} built, so a sheet can update the count or add a trailing control. */
    public static final class Header {
        @NonNull public final LinearLayout view;
        @NonNull public final LinearLayout row;
        @NonNull public final TextView title;
        @NonNull public final TextView count;

        Header(@NonNull LinearLayout view, @NonNull LinearLayout row,
               @NonNull TextView title, @NonNull TextView count) {
            this.view = view;
            this.row = row;
            this.title = title;
            this.count = count;
        }

        public void setCount(@Nullable CharSequence text) {
            count.setText(text);
            count.setVisibility(text == null ? View.GONE : View.VISIBLE);
        }

        /** Append a control at the right-hand end of the title line. */
        @NonNull
        public Header addTrailing(@NonNull View v) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMarginStart(SheetKit.dp(v.getContext(), 6));
            lp.gravity = Gravity.CENTER_VERTICAL;
            row.addView(v, lp);
            return this;
        }
    }

    /** A 40dp close glyph. Tooltip and content description say what it does. */
    @NonNull
    public static TextView closeButton(@NonNull Context c, @NonNull Runnable onClose) {
        TextView x = iconButton(c, "close", Studio.INK_DIM, c.getString(R.string.close));
        x.setOnClickListener(v -> onClose.run());
        return x;
    }

    /** A square borderless glyph button with a 40dp target. */
    @NonNull
    public static TextView iconButton(@NonNull Context c, @NonNull String ligature, int colour,
                                      @NonNull CharSequence what) {
        TextView b = new TextView(c);
        b.setTypeface(icons(c));
        b.setText(ligature);
        b.setTextColor(colour);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, ROW_ICON_SP);
        b.setGravity(Gravity.CENTER);
        b.setMinWidth(dp(c, 40));
        b.setMinHeight(dp(c, 40));
        b.setBackgroundResource(borderless(c));
        b.setClickable(true);
        b.setFocusable(true);
        label(b, what);
        press(b);
        return b;
    }

    /** Secondary copy under a header: 12sp, faint. */
    @NonNull
    public static TextView subtitle(@NonNull Context c, @NonNull CharSequence text) {
        TextView s = new TextView(c);
        s.setText(text);
        s.setTextColor(ROW_DESC);
        s.setTextSize(TypedValue.COMPLEX_UNIT_SP, DESC_SP);
        Type.body(s, Type.REGULAR);
        s.setLineSpacing(0, 1.25f);
        s.setPadding(dp(c, 13), 0, dp(c, 13), dp(c, 10));
        return s;
    }

    // ── sections ────────────────────────────────────────────────────────────

    /** Record 06 {@code .tg}: mono 8sp .14em uppercase, with a 5dp dot when {@code dot != 0}. */
    @NonNull
    public static LinearLayout sectionLabel(@NonNull Context c, @NonNull CharSequence text, int dot) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(c, 13), dp(c, 10), dp(c, 13), dp(c, 5));
        if (dot != 0) {
            View d = new View(c);
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(dot);
            d.setBackground(g);
            LinearLayout.LayoutParams dLp = new LinearLayout.LayoutParams(dp(c, 5), dp(c, 5));
            dLp.setMarginEnd(dp(c, 5));
            row.addView(d, dLp);
        }
        TextView t = new TextView(c);
        t.setText(text);
        t.setLetterSpacing(0.14f);
        t.setTextColor(Studio.INK_DIM);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, SECTION_SP);
        Type.mono(t, Type.MEDIUM);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        // AFTER setSingleLine, and the order is load-bearing: both are TransformationMethods and
        // a TextView holds exactly one, so single-line set second silently threw the capitals
        // away. Every sheet's section labels were rendering in sentence case against the
        // record's uppercase. (The lobby marquee learned the same lesson; see LobbyFragment.)
        t.setAllCaps(true);
        row.addView(t);
        return row;
    }

    /**
     * For a row placed inside a body that already carries the sheet's side padding: drop the
     * row's own side margins so it is not inset twice, and set its top gap.
     */
    public static void flush(@NonNull View row, float topDp) {
        ViewGroup.LayoutParams p = row.getLayoutParams();
        if (p instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams m = (ViewGroup.MarginLayoutParams) p;
            m.setMarginStart(0);
            m.setMarginEnd(0);
            m.leftMargin = 0;
            m.rightMargin = 0;
            m.topMargin = dp(row.getContext(), topDp);
            row.setLayoutParams(m);
        }
    }

    /** A 1dp hairline, inset like the rows. */
    @NonNull
    public static View divider(@NonNull Context c) {
        View d = new View(c);
        d.setBackgroundColor(Studio.LINE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(c, 1)));
        lp.setMargins(dp(c, ROW_INSET_DP), dp(c, 8), dp(c, ROW_INSET_DP), dp(c, 4));
        d.setLayoutParams(lp);
        return d;
    }

    // ── rows ────────────────────────────────────────────────────────────────

    /** What {@link #row} built, so a sheet can recolour or relabel it live. */
    public static final class Row {
        @NonNull public final LinearLayout view;
        @Nullable public final TextView icon;
        @NonNull public final TextView label;
        @Nullable public final TextView trail;

        Row(@NonNull LinearLayout view, @Nullable TextView icon, @NonNull TextView label,
            @Nullable TextView trail) {
            this.view = view;
            this.icon = icon;
            this.label = label;
            this.trail = trail;
        }
    }

    /**
     * A tappable option row: glyph, one-line label, trailing mark.
     *
     * @param ligature  materialicons ligature, or null for a text-only row
     * @param selected  paints the row ARMED (selected #22D3EE) and shows a check when
     *                  {@code trail} is {@link Trail#CHECK}
     * @param onClick   what the row does; null leaves it for the caller to wire
     */
    @NonNull
    public static Row row(@NonNull Context c, @Nullable String ligature, @NonNull CharSequence label,
                          boolean selected, @NonNull Trail trail, @Nullable View.OnClickListener onClick) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(c, ROW_MIN_H_DP));
        row.setPadding(dp(c, 14), dp(c, 8), dp(c, 12), dp(c, 8));
        row.setBackground(rowBackground(c));
        row.setClickable(true);
        row.setFocusable(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(c, ROW_INSET_DP), dp(c, 2), dp(c, ROW_INSET_DP), dp(c, 2));
        row.setLayoutParams(lp);
        press(row);

        TextView icon = null;
        if (ligature != null) {
            icon = new TextView(c);
            icon.setTypeface(icons(c));
            icon.setText(ligature);
            icon.setTextSize(TypedValue.COMPLEX_UNIT_SP, ROW_ICON_SP);
            icon.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams iLp = new LinearLayout.LayoutParams(dp(c, 24), dp(c, 24));
            iLp.setMarginEnd(dp(c, 14));
            row.addView(icon, iLp);
        }

        TextView text = new TextView(c);
        text.setText(label);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, ROW_SP);
        text.setSingleLine(true);
        text.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView end = null;
        if (trail != Trail.NONE) {
            end = new TextView(c);
            end.setTypeface(icons(c));
            end.setTextSize(TypedValue.COMPLEX_UNIT_SP, ROW_ICON_SP);
            end.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams eLp = new LinearLayout.LayoutParams(dp(c, 24), dp(c, 24));
            eLp.setMarginStart(dp(c, 8));
            row.addView(end, eLp);
        }

        Row r = new Row(row, icon, text, end);
        if (trail == Trail.CHEVRON && end != null) {
            end.setText("chevron_right");
            end.setTextColor(ROW_CHEVRON);
        }
        paintRow(r, selected, trail == Trail.CHECK);
        label(row, label);
        if (onClick != null) row.setOnClickListener(onClick);
        return r;
    }

    /** Selected or not: ARMED glyph, label and check; or the resting greys. */
    public static void paintRow(@NonNull Row r, boolean selected, boolean showCheck) {
        if (r.icon != null) r.icon.setTextColor(selected ? Studio.ARMED : ROW_ICON);
        r.label.setTextColor(selected ? Studio.ARMED : ROW_LABEL);
        Type.body(r.label, selected ? Type.SEMIBOLD : Type.REGULAR);
        if (showCheck && r.trail != null) {
            r.trail.setText("check");
            r.trail.setTextColor(Studio.ARMED);
            r.trail.setVisibility(selected ? View.VISIBLE : View.INVISIBLE);
        }
    }

    /** One colour for the whole row — for a state that is not "selected" (DANGER, CAREFUL). */
    public static void tintRow(@NonNull Row r, int colour, boolean strong) {
        if (r.icon != null) r.icon.setTextColor(colour);
        r.label.setTextColor(colour);
        Type.body(r.label, strong ? Type.SEMIBOLD : Type.REGULAR);
    }

    /**
     * A row with a title and a wrapped description beneath it — for settings and for choices
     * that need a sentence of explanation. Returns the row; {@code trailing} (a switch, a
     * chevron) is appended on the right when given.
     */
    @NonNull
    public static LinearLayout detailRow(@NonNull Context c, @Nullable String ligature, int iconColour,
                                         @NonNull CharSequence title, @Nullable CharSequence desc,
                                         @Nullable View trailing, boolean tappable) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(c, ROW_MIN_H_DP));
        row.setPadding(dp(c, 14), dp(c, 11), dp(c, 12), dp(c, 11));
        row.setBackground(tappable ? rowBackground(c) : cardBackground(c));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(c, ROW_INSET_DP), dp(c, 3), dp(c, ROW_INSET_DP), dp(c, 3));
        row.setLayoutParams(lp);
        if (tappable) {
            row.setClickable(true);
            row.setFocusable(true);
            press(row);
            label(row, title);
        }

        if (ligature != null) {
            TextView icon = new TextView(c);
            icon.setTypeface(icons(c));
            icon.setText(ligature);
            icon.setTextColor(iconColour);
            icon.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f);
            icon.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams iLp = new LinearLayout.LayoutParams(dp(c, 32), dp(c, 32));
            iLp.setMarginEnd(dp(c, 12));
            row.addView(icon, iLp);
        }

        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(c);
        t.setText(title);
        t.setTextColor(ROW_LABEL);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, ROW_SP);
        Type.body(t, Type.SEMIBOLD);
        col.addView(t);
        if (desc != null) {
            TextView d = new TextView(c);
            d.setText(desc);
            d.setTextColor(ROW_DESC);
            d.setTextSize(TypedValue.COMPLEX_UNIT_SP, DESC_SP);
            Type.body(d, Type.REGULAR);
            d.setLineSpacing(0, 1.25f);
            LinearLayout.LayoutParams dLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            dLp.topMargin = dp(c, 3);
            col.addView(d, dLp);
        }
        row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        if (trailing != null) {
            LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tLp.setMarginStart(dp(c, 12));
            row.addView(trailing, tLp);
        }
        return row;
    }

    /** A trailing chevron glyph for {@link #detailRow}. */
    @NonNull
    public static TextView chevron(@NonNull Context c) {
        TextView ch = new TextView(c);
        ch.setTypeface(icons(c));
        ch.setText("chevron_right");
        ch.setTextColor(ROW_CHEVRON);
        ch.setTextSize(TypedValue.COMPLEX_UNIT_SP, ROW_ICON_SP);
        return ch;
    }

    // ── chips and buttons (record 01 §05, the pill language) ────────────────

    /** A flat round chip on RAISED. */
    @NonNull
    public static TextView chip(@NonNull Context c, @NonNull CharSequence text) {
        TextView chip = new TextView(c);
        chip.setText(text);
        chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, CHIP_SP);
        chip.setGravity(Gravity.CENTER);
        chip.setSingleLine(true);
        chip.setMinHeight(dp(c, 32));
        chip.setPadding(dp(c, 13), dp(c, 6), dp(c, 13), dp(c, 6));
        chip.setClickable(true);
        chip.setFocusable(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(dp(c, 6));
        chip.setLayoutParams(lp);
        setChipSelected(chip, false);
        label(chip, text);
        press(chip);
        return chip;
    }

    /** Selected: ARMED ink on a faint ARMED wash. At rest: dim ink on RAISED. */
    public static void setChipSelected(@NonNull TextView chip, boolean selected) {
        Context c = chip.getContext();
        chip.setBackground(pillBackground(c,
                selected ? Studio.alpha(Studio.ARMED, 0x29) : Studio.RAISED));
        chip.setTextColor(selected ? Studio.ARMED : Studio.INK_DIM);
        Type.body(chip, selected ? Type.SEMIBOLD : Type.MEDIUM);
    }

    /**
     * A pill button. {@code primary} wears the Studio gradient with ink-on-go — one per view,
     * that is the rule. Everything else is a flat RAISED pill.
     */
    @NonNull
    public static TextView pillButton(@NonNull Context c, @NonNull CharSequence text, boolean primary,
                                      @NonNull View.OnClickListener onClick) {
        TextView b = new TextView(c);
        b.setText(text);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        b.setGravity(Gravity.CENTER);
        b.setSingleLine(true);
        b.setMinHeight(dp(c, 36));
        b.setPadding(dp(c, 16), dp(c, 8), dp(c, 16), dp(c, 8));
        if (primary) {
            b.setBackground(ripple(ResourcesCompat.getDrawable(c.getResources(),
                    R.drawable.studio_action_pill, c.getTheme()), c, true));
            b.setTextColor(Studio.ON_GO);
            Type.body(b, Type.BOLD);
        } else {
            b.setBackground(pillBackground(c, Studio.RAISED));
            b.setTextColor(Studio.INK);
            Type.body(b, Type.SEMIBOLD);
        }
        b.setClickable(true);
        b.setFocusable(true);
        b.setOnClickListener(onClick);
        label(b, text);
        press(b);
        return b;
    }

    // ── backgrounds ─────────────────────────────────────────────────────────

    /** RAISED, 8dp corners, PRESSED ripple, ARMED focus ring. */
    @NonNull
    public static Drawable rowBackground(@NonNull Context c) {
        return ripple(focusable(c, Studio.RAISED, dp(c, ROW_RADIUS_DP)), c, false);
    }

    /** The same shape without press feedback, for a card that is not itself a button. */
    @NonNull
    public static Drawable cardBackground(@NonNull Context c) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(Studio.RAISED);
        g.setCornerRadius(dp(c, ROW_RADIUS_DP));
        return g;
    }

    @NonNull
    public static Drawable pillBackground(@NonNull Context c, int fill) {
        return ripple(focusable(c, fill, dp(c, 999)), c, true);
    }

    @NonNull
    private static Drawable focusable(@NonNull Context c, int fill, float radius) {
        GradientDrawable rest = new GradientDrawable();
        rest.setColor(fill);
        rest.setCornerRadius(radius);
        GradientDrawable focused = new GradientDrawable();
        focused.setColor(fill);
        focused.setCornerRadius(radius);
        focused.setStroke(dp(c, 2), Studio.ARMED);
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_focused}, focused);
        s.addState(new int[]{}, rest);
        s.setEnterFadeDuration(0);   // a focus ring that animates reads as lag
        s.setExitFadeDuration(0);
        return s;
    }

    @NonNull
    private static Drawable ripple(@Nullable Drawable content, @NonNull Context c, boolean pill) {
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(Studio.INK);
        mask.setCornerRadius(pill ? dp(c, 999) : dp(c, ROW_RADIUS_DP));
        return new RippleDrawable(ColorStateList.valueOf(Studio.PRESSED), content, mask);
    }

    private static int borderless(@NonNull Context c) {
        TypedValue tv = new TypedValue();
        c.getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true);
        return tv.resourceId;
    }

    // ── behaviour shared by every control ───────────────────────────────────

    /** Content description AND hover tooltip — the stylus and the mouse both read it. */
    public static void label(@NonNull View v, @Nullable CharSequence what) {
        if (what == null) return;
        v.setContentDescription(what);
        TooltipCompat.setTooltipText(v, what);
    }

    /**
     * 140ms to scale .97 while pressed, back on release. A StateListAnimator rather than a touch
     * listener, so it never competes with the view's own click, long-press or drag handling.
     */
    public static void press(@NonNull View v) {
        StateListAnimator sla = new StateListAnimator();
        sla.addState(new int[]{android.R.attr.state_pressed}, scaleTo(v, PRESS_SCALE));
        sla.addState(new int[]{}, scaleTo(v, 1f));
        v.setStateListAnimator(sla);
    }

    @NonNull
    private static AnimatorSet scaleTo(@NonNull View v, float s) {
        AnimatorSet set = new AnimatorSet();
        set.playTogether(
                ObjectAnimator.ofFloat(v, View.SCALE_X, s),
                ObjectAnimator.ofFloat(v, View.SCALE_Y, s));
        set.setDuration(PRESS_MS);
        set.setInterpolator(new android.view.animation.PathInterpolator(0.23f, 1f, 0.32f, 1f));
        return set;
    }

    @Nullable
    public static android.graphics.Typeface icons(@NonNull Context c) {
        return ResourcesCompat.getFont(c, R.font.materialicons);
    }
}
