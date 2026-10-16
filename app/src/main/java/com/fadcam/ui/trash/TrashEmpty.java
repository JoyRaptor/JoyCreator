package com.fadcam.ui.trash;

import android.content.Context;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.R;
import com.fadcam.ui.faditor.SheetKit;
import com.fadcam.ui.faditor.Studio;
import com.fadcam.ui.library.LibraryKindStyle;
import com.fadcam.ui.library.LibraryViews;
import com.fadcam.ui.type.Type;

/**
 * What an empty Trash says: a friendly line, the kind's glyph in its own colour, and, when a chip
 * emptied the view, one quiet button that shows everything again.
 */
final class TrashEmpty {

    private TrashEmpty() {}

    static void paint(@NonNull LinearLayout box, @Nullable TrashKind kind, boolean anything,
                      @NonNull Runnable showAll) {
        Context c = box.getContext();
        box.removeAllViews();
        int[] pair = LibraryKindStyle.colours(c, kind == null ? null : kind.look);
        TextView glyph = LibraryViews.glyph(c, 34f, Studio.alpha(pair[0], 0x99));
        glyph.setText(kind == null ? "delete_outline" : LibraryKindStyle.glyph(kind.look));
        box.addView(glyph);

        TextView words = new TextView(c);
        words.setText(line(kind));
        words.setTextColor(Studio.INK_DIM);
        words.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        Type.body(words, Type.REGULAR);
        words.setGravity(Gravity.CENTER);
        words.setLineSpacing(0, 1.25f);
        words.setPadding(0, LibraryViews.dp(c, 10), 0, LibraryViews.dp(c, 14));
        box.addView(words);

        if (kind != null && anything) {
            View all = SheetKit.pillButton(c, c.getString(R.string.trash_empty_show_all), false, v -> showAll.run());
            box.addView(all);
        }
    }

    private static int line(@Nullable TrashKind kind) {
        if (kind == null) return R.string.trash_empty_all;
        switch (kind) {
            case PROJECTS:   return R.string.trash_empty_projects;
            case DRAWINGS:   return R.string.trash_empty_drawings;
            case RECORDINGS: return R.string.trash_empty_recordings;
            case PHOTOS:     return R.string.trash_empty_photos;
            default:         return R.string.trash_empty_other;
        }
    }
}
