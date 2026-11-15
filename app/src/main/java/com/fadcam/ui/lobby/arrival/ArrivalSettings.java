package com.fadcam.ui.lobby.arrival;

import android.app.Activity;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.CheckBox;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import com.fadcam.R;
import com.fadcam.ui.lobby.JoybotArrival;
import com.fadcam.ui.size.WindowSize;

/**
 * The "How Joybot arrives" part of Joybot's settings. Owner, 15 Nov: "the title transition and the
 * drawer opening should be in settings ... letters land after him should also be something that the
 * user can choose", a quick hop as a second choice for later opens, and a toggle for a card on a
 * phone. Choices apply at once (next time the chat opens) and are kept separately for phones and
 * tablets, since each has its own best default.
 */
public final class ArrivalSettings {

    private ArrivalSettings() {}

    /** Add the section to {@code root} (the settings dialog's column). */
    public static void addTo(@NonNull Activity a, @NonNull LinearLayout root) {
        float d = a.getResources().getDisplayMetrics().density;
        int pad = Math.round(16 * d);
        boolean wide = WindowSize.isExpanded(a);

        TextView head = new TextView(a);
        head.setText(wide ? "How Joybot arrives (on this tablet)" : "How Joybot arrives (on this phone)");
        head.setTextColor(ContextCompat.getColor(a, R.color.s_ink));
        head.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        head.setPadding(0, pad, 0, Math.round(4 * d));
        root.addView(head);

        String[] reveals = {"Out of the button", "Drops like a curtain", "Opens in his wake"};
        JoybotArrival.Reveal[] rv = {JoybotArrival.Reveal.BUTTON, JoybotArrival.Reveal.CURTAIN, JoybotArrival.Reveal.WAKE};
        choice(a, root, "Drawer opening", reveals, indexOf(rv, JoybotArrival.reveal(a)),
                i -> JoybotArrival.setReveal(a, rv[i]));

        String[] titles = {"Out of the button", "With the drawer", "Letters land after him"};
        JoybotArrival.Title[] tv = {JoybotArrival.Title.BUTTON, JoybotArrival.Title.DRAWER, JoybotArrival.Title.LETTERS};
        choice(a, root, "Title", titles, indexOf(tv, JoybotArrival.title(a)),
                i -> JoybotArrival.setTitle(a, tv[i]));

        String[] laters = {"The whole show", "The show, shorter", "A quick hop"};
        JoybotArrival.Later[] lv = {JoybotArrival.Later.SHOW, JoybotArrival.Later.SHORT, JoybotArrival.Later.HOP};
        choice(a, root, "Later opens (the first of the day is always the whole show)", laters,
                indexOf(lv, JoybotArrival.later(a)), i -> JoybotArrival.setLater(a, lv[i]));

        if (!wide) {
            CheckBox cardBox = check(a, "A card I can move and size, instead of the full screen",
                    JoybotArrival.phoneCard(a));
            cardBox.setOnCheckedChangeListener((b, on) -> JoybotArrival.setPhoneCard(a, on));
            androidx.appcompat.widget.TooltipCompat.setTooltipText(cardBox,
                    "Drag its title to move it and its corner to size it; you can still use the screen beside it");
            root.addView(cardBox);
        }
        CheckBox frost = check(a, "Frost: blur what is behind the chat", JoybotArrival.frost(a));
        frost.setOnCheckedChangeListener((b, on) -> JoybotArrival.setFrost(a, on));
        root.addView(frost);
    }

    private interface Pick { void on(int i); }

    private static <E> int indexOf(@NonNull E[] all, @NonNull E e) {
        for (int i = 0; i < all.length; i++) if (all[i] == e) return i;
        return 0;
    }

    /** A label and a row of pills, one chosen: the chosen one solid cyan (the app's ON). */
    private static void choice(@NonNull Activity a, @NonNull LinearLayout root, @NonNull String label,
                               @NonNull String[] names, int chosen, @NonNull Pick pick) {
        float d = a.getResources().getDisplayMetrics().density;
        TextView l = new TextView(a);
        l.setText(label);
        l.setTextColor(ContextCompat.getColor(a, R.color.s_ink_dim));
        l.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        l.setPadding(0, Math.round(10 * d), 0, Math.round(4 * d));
        root.addView(l);
        HorizontalScrollView scroll = new HorizontalScrollView(a);
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        scroll.addView(row);
        final TextView[] pills = new TextView[names.length];
        final int[] on = {chosen};
        Runnable paint = () -> {
            for (int i = 0; i < pills.length; i++) {
                boolean sel = i == on[0];
                GradientDrawable g = new GradientDrawable();
                g.setCornerRadius(999f);
                g.setColor(sel ? ContextCompat.getColor(a, R.color.s_armed) : ContextCompat.getColor(a, R.color.s_raised));
                pills[i].setBackground(g);
                pills[i].setTextColor(sel ? 0xFF050507 : ContextCompat.getColor(a, R.color.s_ink));
                pills[i].setSelected(sel);
            }
        };
        for (int i = 0; i < names.length; i++) {
            TextView p = new TextView(a);
            p.setText(names[i]);
            p.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            p.setGravity(Gravity.CENTER);
            p.setPadding(Math.round(14 * d), Math.round(8 * d), Math.round(14 * d), Math.round(8 * d));
            final int k = i;
            p.setOnClickListener(v -> { on[0] = k; pick.on(k); paint.run(); });
            androidx.appcompat.widget.TooltipCompat.setTooltipText(p, label + ": " + names[i]);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMarginEnd(Math.round(6 * d));
            row.addView(p, lp);
            pills[i] = p;
        }
        paint.run();
        root.addView(scroll);
    }

    @NonNull
    private static CheckBox check(@NonNull Activity a, @NonNull String text, boolean on) {
        CheckBox c = new CheckBox(a);
        c.setText(text);
        c.setTextColor(ContextCompat.getColor(a, R.color.s_ink_dim));
        c.setChecked(on);
        c.setPadding(0, Math.round(6 * a.getResources().getDisplayMetrics().density), 0, 0);
        c.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        return c;
    }
}
