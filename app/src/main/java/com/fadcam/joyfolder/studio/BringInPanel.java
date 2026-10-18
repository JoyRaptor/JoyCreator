package com.fadcam.joyfolder.studio;

import android.app.Activity;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.text.TextUtils;
import android.text.format.Formatter;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.fadcam.joyfolder.studio.BringInDialog.Choice;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The "files outside your folder" choices — Copy all / Move all / Leave all, then Copy · Move ·
 * Leave per file, the guess pre-chosen — as a plain view. {@link BringInDialog} shows it in a
 * dialog (the Studio project list); Joybot shows it inside his chat message (the editor, owner
 * 2026-10-16: "put that whole thing in the Joybot comment… same formatting, just conversational").
 */
public final class BringInPanel {

    private static final int CYAN = 0xFF22D3EE;
    private static final int GROUND = 0xFF050507;
    private static final int PANEL_2 = 0xFF1C1C22;
    private static final int TEXT = 0xFFE9E9EE;
    private static final int MUTED = 0xFFA1A1AC;

    /** The view, and the person's current choice per file (kept up to date as they tap). */
    @NonNull public final View view;
    @NonNull public final Map<String, Choice> choices;

    private BringInPanel(@NonNull View view, @NonNull Map<String, Choice> choices) {
        this.view = view;
        this.choices = choices;
    }

    /**
     * @param withWhy include the "why it matters" paragraph (the dialog does; Joybot says it in
     *                his own words instead).
     */
    @NonNull
    public static BringInPanel build(@NonNull Activity a, @NonNull List<String> refs, @NonNull List<String> names,
                                     @NonNull List<Long> sizes, boolean withWhy) {
        final Map<String, Choice> choices = new LinkedHashMap<>();
        final List<TextView[]> rows = new ArrayList<>();
        long total = 0;
        for (int i = 0; i < refs.size(); i++) {
            choices.put(refs.get(i), OutsideFiles.madeHere(names.get(i)) ? Choice.MOVE : Choice.COPY);
            total += Math.max(0, sizes.get(i));
        }

        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);

        if (withWhy) {
            col.addView(text(a, (refs.size() == 1 ? "It's" : "They're") + " linked where "
                    + (refs.size() == 1 ? "it is" : "they are") + " (" + Formatter.formatShortFileSize(a, total)
                    + "), not in your Joy Creator folder. If one is moved or deleted there, the project loses it, and a new "
                    + "phone won't have it.", 14, MUTED, false));
        }
        TextView key = text(a, "Copy keeps the original where it is. Move takes it out of there (good for something you "
                + "recorded for this project). Leave keeps the link.", 12, MUTED, false);
        key.setPadding(0, withWhy ? dp(a, 8) : 0, 0, dp(a, 4));
        col.addView(key);

        // Copy all / Move all / Leave all
        if (refs.size() > 1) {
            LinearLayout all = new LinearLayout(a);
            all.setOrientation(LinearLayout.HORIZONTAL);
            all.setPadding(0, dp(a, 8), 0, dp(a, 4));
            for (Choice c : Choice.values()) {
                TextView b = pill(a, label(c) + " all", hint(c, true));
                b.setOnClickListener(v -> {
                    for (String r : refs) choices.put(r, c);
                    for (int i = 0; i < rows.size(); i++) paint(rows.get(i), choices.get(refs.get(i)));
                });
                all.addView(b);
                gap(a, all);
            }
            col.addView(all);
        }

        for (int i = 0; i < refs.size(); i++) {
            final String ref = refs.get(i);
            LinearLayout row = new LinearLayout(a);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(0, dp(a, 10), 0, 0);
            long size = sizes.get(i);
            TextView name = text(a, names.get(i) + (size >= 0 ? "  ·  " + Formatter.formatShortFileSize(a, size) : ""),
                    14, TEXT, true);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            row.addView(name);
            LinearLayout seg = new LinearLayout(a);
            seg.setOrientation(LinearLayout.HORIZONTAL);
            seg.setPadding(0, dp(a, 6), 0, 0);
            TextView[] three = new TextView[3];
            for (Choice c : Choice.values()) {
                TextView b = pill(a, label(c), hint(c, false));
                b.setOnClickListener(v -> {
                    choices.put(ref, c);
                    paint(three, c);
                });
                three[c.ordinal()] = b;
                seg.addView(b);
                gap(a, seg);
            }
            paint(three, choices.get(ref));
            rows.add(three);
            row.addView(seg);
            col.addView(row);
        }
        return new BringInPanel(col, choices);
    }

    @NonNull
    private static String label(@NonNull Choice c) {
        return c == Choice.COPY ? "Copy" : c == Choice.MOVE ? "Move" : "Leave";
    }

    @NonNull
    private static String hint(@NonNull Choice c, boolean all) {
        String what = all ? "every file" : "this file";
        if (c == Choice.COPY) return "Copy " + what + " into your Joy Creator folder; the original stays where it is";
        if (c == Choice.MOVE) return "Move " + what + " into your Joy Creator folder; nothing is left behind";
        return "Keep using " + what + " where it is";
    }

    /** ON is solid cyan with dark text; the others are zinc pills. */
    private static void paint(@NonNull TextView[] three, @NonNull Choice on) {
        for (Choice c : Choice.values()) {
            TextView b = three[c.ordinal()];
            boolean sel = c == on;
            ((GradientDrawable) b.getBackground()).setColor(sel ? CYAN : PANEL_2);
            b.setTextColor(sel ? GROUND : TEXT);
            b.setSelected(sel);
        }
    }

    @NonNull
    private static TextView pill(@NonNull Activity a, @NonNull String label, @NonNull String hint) {
        TextView b = text(a, label, 13, TEXT, true);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(a, 14), dp(a, 8), dp(a, 14), dp(a, 8));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(PANEL_2);
        bg.setCornerRadius(dp(a, 999));
        b.setBackground(bg);
        b.setContentDescription(label + ". " + hint);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) b.setTooltipText(hint);
        b.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return b;
    }

    private static void gap(@NonNull Activity a, @NonNull LinearLayout row) {
        View g = new View(a);
        g.setLayoutParams(new LinearLayout.LayoutParams(dp(a, 8), 1));
        row.addView(g);
    }

    @NonNull
    private static TextView text(@NonNull Activity a, @NonNull String s, int sp, int color, boolean bold) {
        TextView t = new TextView(a);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private static int dp(@NonNull Activity a, int v) {
        return Math.round(v * a.getResources().getDisplayMetrics().density);
    }
}
