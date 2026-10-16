package com.fadcam.ui.library;

import android.content.Context;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;

import com.fadcam.R;
import com.fadcam.ui.faditor.SheetKit;
import com.google.android.material.bottomsheet.BottomSheetDialog;

/**
 * The options button's sheet: only the ways out (sort and view live in the header as cycles): Trash, the classic Records
 * screen and the classic Studio list (owner ruling f: they stay whole and reachable until this one
 * is better in every way).
 */
final class LibraryOptionsSheet {

    interface Listener {
        void onTrash();
        void onClassic();
        void onClassicStudio();
    }

    private LibraryOptionsSheet() {}

    static void show(@NonNull Context c, @NonNull Listener l) {
        BottomSheetDialog d = new BottomSheetDialog(c, R.style.CustomBottomSheetDialogTheme);
        SheetKit.install(d, null);
        LinearLayout root = new LinearLayout(c);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(0, 0, 0, SheetKit.dp(c, 16));

        SheetKit.Header header = SheetKit.header(c, c.getString(R.string.library_options_title), null);
        header.addTrailing(SheetKit.closeButton(c, d::dismiss));
        root.addView(header.view);

        root.addView(SheetKit.sectionLabel(c, c.getString(R.string.library_more), 0));
        root.addView(SheetKit.row(c, "delete_outline", c.getString(R.string.library_open_trash),
                false, SheetKit.Trail.CHEVRON, v -> { d.dismiss(); l.onTrash(); }).view);
        root.addView(SheetKit.row(c, "history", c.getString(R.string.library_classic),
                false, SheetKit.Trail.CHEVRON, v -> { d.dismiss(); l.onClassic(); }).view);
        root.addView(SheetKit.row(c, "movie_edit", c.getString(R.string.projects_classic_list),
                false, SheetKit.Trail.CHEVRON, v -> { d.dismiss(); l.onClassicStudio(); }).view);

        d.setContentView(SheetKit.fitNavBar(root));
        d.show();
    }
}
