package com.fadcam.ui.faditor.ai.joybot;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.TooltipCompat;
import androidx.core.content.res.ResourcesCompat;

import com.fadcam.ui.faditor.Studio;

import java.util.List;

/**
 * The create tree's buttons (BOT-7): each kind is a bubble with its icon, in the gradient of the
 * room it lands in, so the colour says where it will happen. Answers are plain grey pills; "Type
 * your own" and "Never mind" are quieter. Every button has a hover label (owner rule).
 */
public final class CreateBubbles {
    private CreateBubbles() { }

    public interface OnTap { void tap(@NonNull String id); }

    /** One bubble per kind, then "Never mind". */
    public static void kinds(@NonNull LinearLayout row, @NonNull List<CreateTree.Kind> kinds, @NonNull OnTap tap) {
        row.removeAllViews();
        Context c = row.getContext();
        for (CreateTree.Kind k : kinds) {
            View b = bubble(c, k.icon, k.label, RoomTint.forRoom(c, k.room));
            TooltipCompat.setTooltipText(b, k.hint);
            b.setContentDescription(k.label + ". " + k.hint);
            b.setOnClickListener(v -> tap.tap(k.id));
            row.addView(b);
        }
        row.addView(quiet(c, "Never mind", "Stop; nothing is made", () -> tap.tap("")));
    }

    /** A question's answers, in its room's colours; "Type your own" when typing makes sense; "Never mind". */
    public static void answers(@NonNull LinearLayout row, @NonNull CreateTree.Question q, @NonNull String room,
                               @NonNull OnTap tap) {
        row.removeAllViews();
        Context c = row.getContext();
        RoomColours.Pair p = RoomTint.forRoom(c, room);
        boolean first = true;
        for (CreateTree.Choice ch : q.choices) {
            TextView b = pill(c, ch.label);
            if (first) RoomTint.pill(b, p);   // the common pick, in the room's colours
            first = false;
            TooltipCompat.setTooltipText(b, ch.hint);
            b.setContentDescription(ch.label + ". " + ch.hint);
            b.setOnClickListener(v -> tap.tap(ch.id));
            row.addView(b);
        }
        if (q.typeHint != null) {
            row.addView(quiet(c, "Type your own", q.typeHint, () -> tap.tap(CreateTree.TYPE_OWN)));
        }
        row.addView(quiet(c, "Never mind", "Stop; nothing is made", () -> tap.tap("")));
    }

    /** Plain buttons (the tour's, Help's questions): label → id, the first in {@code room}'s colours if given. */
    public static void buttons(@NonNull LinearLayout row, @NonNull String[][] labelIdHint, @Nullable String room,
                               @NonNull OnTap tap) {
        row.removeAllViews();
        Context c = row.getContext();
        for (int i = 0; i < labelIdHint.length; i++) {
            String[] b = labelIdHint[i];
            TextView t = pill(c, b[0]);
            if (i == 0 && room != null) RoomTint.pill(t, RoomTint.forRoom(c, room));
            TooltipCompat.setTooltipText(t, b[2]);
            t.setContentDescription(b[0] + ". " + b[2]);
            t.setOnClickListener(v -> tap.tap(b[1]));
            row.addView(t);
        }
    }

    @NonNull
    private static View bubble(@NonNull Context c, @NonNull String icon, @NonNull String label, @NonNull RoomColours.Pair p) {
        LinearLayout b = new LinearLayout(c);
        b.setOrientation(LinearLayout.HORIZONTAL);
        b.setGravity(Gravity.CENTER_VERTICAL);
        int h = dp(c, 40);
        b.setMinimumHeight(h);
        b.setPadding(dp(c, 12), 0, dp(c, 14), 0);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{p.start, p.end});
        bg.setCornerRadius(h / 2f);
        b.setBackground(bg);
        TextView ic = new TextView(c);
        // ICON-1's pictures replace these placeholders (Material Icons ligatures, the lobby's own font).
        Typeface font = null;
        try { font = ResourcesCompat.getFont(c, com.fadcam.R.font.materialicons); } catch (Throwable ignored) { }
        if (font != null) {
            ic.setTypeface(font);
            ic.setText(icon);
            ic.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
            ic.setTextColor(p.ink);
            ic.setPadding(0, 0, dp(c, 6), 0);
            ic.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            b.addView(ic);
        }
        TextView t = new TextView(c);
        t.setText(label);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        t.setTextColor(p.ink);
        t.setSingleLine(true);
        b.addView(t);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, h);
        lp.setMarginEnd(dp(c, 6));
        b.setLayoutParams(lp);
        b.setClickable(true);
        b.setFocusable(true);
        com.fadcam.ui.motion.Motion.press(b);
        return b;
    }

    @NonNull
    private static TextView pill(@NonNull Context c, @NonNull String text) {
        return JoybotChatViews.pill(c, text, false);
    }

    @NonNull
    private static TextView quiet(@NonNull Context c, @NonNull String text, @NonNull String hint, @NonNull Runnable r) {
        TextView t = pill(c, text);
        t.setTextColor(Studio.INK_FAINT);
        TooltipCompat.setTooltipText(t, hint);
        t.setOnClickListener(v -> r.run());
        return t;
    }

    private static int dp(@NonNull Context c, int v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }
}
