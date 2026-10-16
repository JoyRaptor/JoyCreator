package com.fadcam.ui.trash;

import android.content.Context;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;

import com.fadcam.R;
import com.fadcam.ui.faditor.SheetKit;
import com.fadcam.ui.faditor.Studio;
import com.google.android.material.bottomsheet.BottomSheetDialog;

/**
 * The options button's sheet: Select all, the Trash rule, Empty trash (careful amber; it asks
 * first), and, quiet at the bottom, the classic Trash screen, kept whole and one tap away until this
 * one is better in every way. Also the rule's own picker.
 */
final class TrashOptionsSheet {

    interface Listener {
        void onSelectAll();
        void onRule();
        void onEmpty();
        void onClassic();
    }

    interface RuleListener {
        void onRule(int minutes);
    }

    private TrashOptionsSheet() {}

    static void show(@NonNull Context c, boolean anything, int ruleMinutes, @NonNull Listener l) {
        BottomSheetDialog d = new BottomSheetDialog(c, R.style.CustomBottomSheetDialogTheme);
        SheetKit.install(d, null);
        LinearLayout root = new LinearLayout(c);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(0, 0, 0, SheetKit.dp(c, 16));

        SheetKit.Header header = SheetKit.header(c, c.getString(R.string.trash_options_title), null);
        header.addTrailing(SheetKit.closeButton(c, d::dismiss));
        root.addView(header.view);

        if (anything) {
            root.addView(SheetKit.row(c, "select_all", c.getString(R.string.trash_opt_select_all), false,
                    SheetKit.Trail.NONE, v -> { d.dismiss(); l.onSelectAll(); }).view);
        }
        root.addView(SheetKit.row(c, "schedule",
                c.getString(R.string.trash_opt_rule, TrashPolicy.label(c, ruleMinutes)), false,
                SheetKit.Trail.CHEVRON, v -> { d.dismiss(); l.onRule(); }).view);
        if (anything) {
            SheetKit.Row empty = SheetKit.row(c, "delete_forever", c.getString(R.string.trash_opt_empty), false,
                    SheetKit.Trail.NONE, v -> { d.dismiss(); l.onEmpty(); });
            SheetKit.tintRow(empty, Studio.CAREFUL, false);
            root.addView(empty.view);
        }
        root.addView(SheetKit.divider(c));
        SheetKit.Row classic = SheetKit.row(c, "history", c.getString(R.string.trash_opt_classic), false,
                SheetKit.Trail.CHEVRON, v -> { d.dismiss(); l.onClassic(); });
        SheetKit.tintRow(classic, Studio.INK_DIM, false);
        root.addView(classic.view);

        d.setContentView(SheetKit.fitNavBar(root));
        d.show();
    }

    /** The classic Trash's ten choices, the current one ticked. */
    static void rule(@NonNull Context c, int current, @NonNull RuleListener l) {
        BottomSheetDialog d = new BottomSheetDialog(c, R.style.CustomBottomSheetDialogTheme);
        SheetKit.install(d, null);
        LinearLayout root = new LinearLayout(c);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(0, 0, 0, SheetKit.dp(c, 16));
        SheetKit.Header header = SheetKit.header(c, c.getString(R.string.trash_rule_title), null);
        header.addTrailing(SheetKit.closeButton(c, d::dismiss));
        root.addView(header.view);
        for (int i = 0; i < TrashPolicy.MINUTES.length; i++) {
            final int minutes = TrashPolicy.MINUTES[i];
            root.addView(SheetKit.row(c, null, c.getString(TrashPolicy.LABELS[i]), minutes == current,
                    SheetKit.Trail.CHECK, v -> {
                        d.dismiss();
                        if (minutes != current) l.onRule(minutes);
                    }).view);
        }
        android.widget.ScrollView scroll = new android.widget.ScrollView(c);
        scroll.addView(root);
        d.setContentView(SheetKit.fitNavBar(scroll));
        d.show();
    }
}
