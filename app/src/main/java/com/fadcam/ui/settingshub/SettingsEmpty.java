package com.fadcam.ui.settingshub;

import android.content.Context;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.fadcam.R;
import com.fadcam.ui.faditor.SheetKit;
import com.fadcam.ui.faditor.Studio;
import com.fadcam.ui.type.Type;

/**
 * What the hub says when a search finds nothing: what was looked for, a nudge toward words that
 * work, and one quiet button that shows every setting again.
 */
final class SettingsEmpty {

    private SettingsEmpty() {}

    static void paint(@NonNull LinearLayout box, @NonNull String query, @NonNull Runnable showAll) {
        Context c = box.getContext();
        box.removeAllViews();
        TextView glyph = SettingsViews.glyph(c, 34f, Studio.INK_OFF);
        glyph.setText("search_off");
        box.addView(glyph);

        TextView words = text(c, c.getString(R.string.settingshub_empty, query.trim()), 13f, Studio.INK_DIM);
        words.setPadding(0, SettingsViews.dp(c, 10), 0, SettingsViews.dp(c, 4));
        box.addView(words);
        TextView hint = text(c, c.getString(R.string.settingshub_empty_hint), 12f, Studio.INK_FAINT);
        hint.setPadding(0, 0, 0, SettingsViews.dp(c, 14));
        box.addView(hint);

        box.addView(SheetKit.pillButton(c, c.getString(R.string.settingshub_empty_show_all), false,
                v -> showAll.run()));
    }

    @NonNull
    private static TextView text(@NonNull Context c, @NonNull String s, float sp, int colour) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextColor(colour);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        Type.body(t, Type.REGULAR);
        t.setGravity(Gravity.CENTER);
        t.setLineSpacing(0, 1.25f);
        return t;
    }
}
