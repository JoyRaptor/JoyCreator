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

import com.fadcam.ui.faditor.Studio;

import java.util.List;

/**
 * The pieces Joybot adds to the chat: idea buttons and result cards.
 *
 * <p>Studio look: flat pills, no border, no elevation; zinc greys; the studio green only on the
 * one button Joybot thinks is best. Every button carries a hover label (owner rule: stylus and
 * mouse users learn the app by pointing at it).</p>
 */
public final class JoybotChatViews {

    private JoybotChatViews() {}

    /** Tag on every result card's root, so hands-free can find the newest card on screen. */
    public static final String CARD_TAG = "joybot_card";

    public interface OnChip {
        void onTap(@NonNull Idea idea);
        /** Long-press: "don't suggest this here again". */
        void onDismiss(@NonNull Idea idea);
    }

    /** Fill {@code row} with one pill per idea; the first real idea is green. */
    public static void fillChips(@NonNull LinearLayout row, @NonNull List<Idea> ideas,
                                 @NonNull OnChip listener) {
        row.removeAllViews();
        boolean first = true;
        for (Idea i : ideas) {
            boolean primary = first && i.kind != Idea.Kind.TYPE;
            first = false;
            TextView chip = pill(row.getContext(), i.label, primary);
            String hint = i.hint + (i.kind == Idea.Kind.TYPE ? "" : "  ·  Hold to stop suggesting this");
            TooltipCompat.setTooltipText(chip, hint);
            chip.setContentDescription(i.label + ". " + i.hint);
            chip.setOnClickListener(v -> listener.onTap(i));
            if (i.kind != Idea.Kind.TYPE) {
                chip.setOnLongClickListener(v -> { listener.onDismiss(i); return true; });
            }
            row.addView(chip);
        }
    }

    /** Fill {@code row} with plain answer buttons (the slide questions). */
    public static void fillChoices(@NonNull LinearLayout row, @NonNull List<SlideFlow.Choice> choices,
                                   @Nullable String cancelLabel, @NonNull ChoiceListener listener) {
        row.removeAllViews();
        for (SlideFlow.Choice c : choices) {
            TextView chip = pill(row.getContext(), c.label, false);
            TooltipCompat.setTooltipText(chip, c.hint);
            chip.setContentDescription(c.label + ". " + c.hint);
            chip.setOnClickListener(v -> listener.onChoice(c));
            row.addView(chip);
        }
        if (cancelLabel != null) {
            TextView never = pill(row.getContext(), cancelLabel, false);
            never.setTextColor(Studio.INK_FAINT);
            TooltipCompat.setTooltipText(never, "Stop making this slide");
            never.setOnClickListener(v -> listener.onChoice(null));
            row.addView(never);
        }
    }

    public interface ChoiceListener {
        /** {@code null} = cancelled. */
        void onChoice(@Nullable SlideFlow.Choice choice);
    }

    /** A flat pill. Primary = studio green with black ink; otherwise raised zinc. */
    @NonNull
    public static TextView pill(@NonNull Context c, @NonNull String text, boolean primary) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        t.setTypeface(Typeface.DEFAULT, primary ? Typeface.BOLD : Typeface.NORMAL);
        t.setTextColor(primary ? Studio.ON_GO : Studio.INK);
        t.setGravity(Gravity.CENTER);
        t.setSingleLine(true);
        int h = dp(c, 36);
        t.setMinHeight(h);
        t.setPadding(dp(c, 14), 0, dp(c, 14), 0);
        GradientDrawable bg = primary
                ? new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{Studio.GO, Studio.GO_END})
                : new GradientDrawable();
        if (!primary) bg.setColor(Studio.RAISED);
        bg.setCornerRadius(h / 2f);
        t.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, h);
        lp.setMarginEnd(dp(c, 6));
        t.setLayoutParams(lp);
        t.setClickable(true);
        t.setFocusable(true);
        com.fadcam.ui.motion.Motion.press(t);
        return t;
    }

    /** The parts of a result card the chat needs to reach after it is shown. */
    public static final class Card {
        @NonNull public final LinearLayout root;
        @NonNull public final TextView title;
        @NonNull public final LinearLayout buttons;

        Card(@NonNull LinearLayout root, @NonNull TextView title, @NonNull LinearLayout buttons) {
            this.root = root;
            this.title = title;
            this.buttons = buttons;
        }

        /** Grey out and stop every button (after Undo, or when a newer step exists). */
        public void retire(@NonNull String why) {
            title.setText(why);
            for (int i = 0; i < buttons.getChildCount(); i++) {
                View b = buttons.getChildAt(i);
                b.setEnabled(false);
                b.setAlpha(0.35f);
            }
        }
    }

    /**
     * What Joybot did: the change in plain words, optional details (a chapter list, say), and a row
     * of buttons the caller fills.
     */
    @NonNull
    public static Card resultCard(@NonNull Context c, @NonNull String title, @Nullable String details) {
        LinearLayout card = new LinearLayout(c);
        card.setTag(CARD_TAG);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Studio.PANEL);
        bg.setCornerRadius(dp(c, 16));
        card.setBackground(bg);
        card.setPadding(dp(c, 14), dp(c, 12), dp(c, 14), dp(c, 10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(c, 8), dp(c, 4), dp(c, 48), dp(c, 4));
        card.setLayoutParams(lp);

        TextView t = new TextView(c);
        t.setText(title);
        t.setTextColor(Studio.INK);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        t.setTextIsSelectable(true);
        card.addView(t);

        if (details != null && !details.trim().isEmpty()) {
            TextView d = new TextView(c);
            d.setText(details.trim());
            d.setTextColor(Studio.INK_DIM);
            d.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            d.setTextIsSelectable(true);
            d.setPadding(0, dp(c, 6), 0, 0);
            card.addView(d);
        }

        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(c, 10), 0, 0);
        card.addView(row);
        return new Card(card, t, row);
    }

    /**
     * Buttons two to a row, for a card with more than fit across a phone (call after adding them).
     * Not for cards that {@link Card#retire} later: it greys the rows, not the buttons in them.
     */
    public static void wrapButtons(@NonNull Card card) {
        LinearLayout b = card.buttons;
        int n = b.getChildCount();
        if (n <= 2) return;
        java.util.List<View> kids = new java.util.ArrayList<>();
        for (int i = 0; i < n; i++) kids.add(b.getChildAt(i));
        b.removeAllViews();
        b.setOrientation(LinearLayout.VERTICAL);
        LinearLayout row = null;
        for (int i = 0; i < n; i++) {
            if (i % 2 == 0) {
                row = new LinearLayout(b.getContext());
                row.setOrientation(LinearLayout.HORIZONTAL);
                if (i > 0) row.setPadding(0, dp(b.getContext(), 6), 0, 0);
                b.addView(row);
            }
            row.addView(kids.get(i));
        }
    }

    /** A small button for a result card. */
    @NonNull
    public static TextView cardButton(@NonNull Context c, @NonNull String text, @NonNull String hint,
                                      @NonNull View.OnClickListener onClick) {
        TextView b = pill(c, text, false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        b.setMinHeight(dp(c, 30));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(c, 30));
        lp.setMarginEnd(dp(c, 6));
        b.setLayoutParams(lp);
        ((GradientDrawable) b.getBackground()).setCornerRadius(dp(c, 15));
        ((GradientDrawable) b.getBackground()).setColor(Studio.PRESSED);
        TooltipCompat.setTooltipText(b, hint);
        b.setContentDescription(text + ". " + hint);
        b.setOnClickListener(onClick);
        return b;
    }

    private static int dp(Context c, int v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    /**
     * A model's reply as the bubble shows it. Models write Markdown, and a TextView showed it raw:
     * "*   **Cell 0:** A happy star" (Note 9, 2026-10-15, the first sprite-sheet look). Bold
     * becomes bold, list markers become bullets, heading hashes and code ticks go. The copy
     * button still copies the words as written.
     */
    @NonNull
    public static CharSequence styled(@NonNull String text) {
        String t = text.replaceAll("(?m)^\\s{0,3}#{1,6}\\s+", "")
                .replaceAll("(?m)^(\\s*)[*\\-+]\\s+", "$1• ")
                .replaceAll("`([^`\\n]+)`", "$1");
        android.text.SpannableStringBuilder out = new android.text.SpannableStringBuilder();
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\\*\\*(.+?)\\*\\*|__(.+?)__").matcher(t);
        int at = 0;
        while (m.find()) {
            out.append(t, at, m.start());
            String bold = m.group(1) != null ? m.group(1) : m.group(2);
            int s = out.length();
            out.append(bold);
            out.setSpan(new android.text.style.StyleSpan(Typeface.BOLD), s, out.length(),
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            at = m.end();
        }
        out.append(t, at, t.length());
        return out;
    }
}
