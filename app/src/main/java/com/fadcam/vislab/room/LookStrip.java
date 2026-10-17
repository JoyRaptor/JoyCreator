package com.fadcam.vislab.room;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.ui.faditor.Studio;
import com.fadcam.ui.motion.Motion;
import com.fadcam.vislab.model.VisScene;

import java.util.ArrayList;
import java.util.List;

/**
 * The strip of looks along the bottom of the room: name, family and the look's five colours.
 * The chosen card wears the house ON state: filled cyan, its words punched out in black.
 */
final class LookStrip extends HorizontalScrollView {

    interface OnPick {
        void onPick(int index);
    }

    private final LinearLayout row;
    private final List<View> cards = new ArrayList<>();
    @Nullable private OnPick onPick;
    private int selected = -1;

    LookStrip(@NonNull Context c) {
        super(c);
        setHorizontalScrollBarEnabled(false);
        setClipToPadding(false);
        row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        addView(row);
    }

    void setOnPick(@Nullable OnPick l) {
        onPick = l;
    }

    void setLooks(@NonNull List<VisScene> looks) {
        row.removeAllViews();
        cards.clear();
        Context c = getContext();
        for (int i = 0; i < looks.size(); i++) {
            final int index = i;
            VisScene s = looks.get(i);
            LinearLayout card = new LinearLayout(c);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setGravity(Gravity.START);
            int pad = Ui.dp(c, 9);
            card.setPadding(pad, Ui.dp(c, 8), pad, Ui.dp(c, 8));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(c, 122), LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMarginEnd(Ui.dp(c, 7));
            card.setLayoutParams(lp);
            TextView name = Ui.text(c, s.name(), 12.5f, Studio.INK, true, 650);
            name.setSingleLine(true);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            card.addView(name);
            TextView fam = Ui.text(c, s.family(), 10.5f, Studio.INK_FAINT, false, 400);
            fam.setPadding(0, Ui.dp(c, 3), 0, Ui.dp(c, 6));
            card.addView(fam);
            card.addView(swatch(c, s.palette()));
            Ui.label(card, s.name() + ". " + s.description());
            card.setOnClickListener(v -> {
                Motion.press(v);
                setSelected(index);
                if (onPick != null) onPick.onPick(index);
            });
            cards.add(card);
            row.addView(card);
        }
        paintAll();
    }

    @NonNull
    private static View swatch(@NonNull Context c, @Nullable String[] stops) {
        LinearLayout sw = new LinearLayout(c);
        sw.setOrientation(LinearLayout.HORIZONTAL);
        sw.setClipToOutline(true);
        sw.setBackground(Ui.round(Studio.LINE, Ui.dp(c, 3)));
        String[] s = stops != null ? stops : new String[]{"#333", "#555", "#777", "#999", "#bbb"};
        for (String hex : s) {
            View v = new View(c);
            v.setBackgroundColor(Ui.parse(hex, Studio.LINE));
            sw.addView(v, new LinearLayout.LayoutParams(0, Ui.dp(c, 6), 1f));
        }
        return sw;
    }

    int childCount() {
        return cards.size();
    }

    void setSelected(int index) {
        selected = index;
        paintAll();
        if (index >= 0 && index < cards.size()) {
            View card = cards.get(index);
            post(() -> smoothScrollTo(Math.max(0, card.getLeft() - (getWidth() - card.getWidth()) / 2), 0));
        }
    }

    private void paintAll() {
        Context c = getContext();
        for (int i = 0; i < cards.size(); i++) {
            boolean on = i == selected;
            LinearLayout card = (LinearLayout) cards.get(i);
            card.setBackground(Ui.round(on ? Studio.ARMED : Ui.DRAWER, Ui.dp(c, 12)));
            ((TextView) card.getChildAt(0)).setTextColor(on ? Studio.GROUND : Studio.INK);
            ((TextView) card.getChildAt(1)).setTextColor(on ? Studio.GROUND : Studio.INK_FAINT);
            card.setSelected(on);
        }
    }
}
