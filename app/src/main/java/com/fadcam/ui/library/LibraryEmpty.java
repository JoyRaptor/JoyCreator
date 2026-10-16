package com.fadcam.ui.library;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.fadcam.R;
import com.fadcam.ui.faditor.SheetKit;
import com.fadcam.ui.faditor.Studio;
import com.fadcam.ui.type.Type;

/**
 * What an empty view says. One friendly line per kind ("No drawings yet. Joy Brush keeps every one
 * here."), or, when a search or the Shared in chip emptied it, what narrowed it, with one quiet
 * button that shows everything again.
 */
final class LibraryEmpty {

    private LibraryEmpty() {}

    static void paint(@NonNull LinearLayout box, @NonNull LibraryFilter f, @NonNull Runnable showAll) {
        Context c = box.getContext();
        box.removeAllViews();
        int[] pair = LibraryKindStyle.colours(c, f.kind());
        TextView glyph = LibraryViews.glyph(c, 34f, Studio.alpha(pair[0], 0x99));
        glyph.setText(LibraryKindStyle.glyph(f.kind()));
        box.addView(glyph);

        String line;
        if (!f.query().isEmpty()) line = c.getString(R.string.library_empty_search, f.query());
        else if (f.shared() == LibraryFilter.Shared.ONLY) line = c.getString(R.string.library_empty_shared_only);
        else line = c.getString(LibraryKindStyle.empty(f.kind()));
        TextView words = new TextView(c);
        words.setText(line);
        words.setTextColor(Studio.INK_DIM);
        words.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f);
        Type.body(words, Type.REGULAR);
        words.setGravity(Gravity.CENTER);
        words.setLineSpacing(0, 1.25f);
        words.setPadding(0, LibraryViews.dp(c, 10), 0, LibraryViews.dp(c, 14));
        box.addView(words);

        if (f.narrowed() || f.kind() != null) {
            View all = SheetKit.pillButton(c, c.getString(R.string.library_empty_show_all), false, v -> showAll.run());
            box.addView(all);
        }
    }
}
