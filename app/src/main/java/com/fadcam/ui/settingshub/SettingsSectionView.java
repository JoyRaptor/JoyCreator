package com.fadcam.ui.settingshub;

import android.annotation.SuppressLint;
import android.content.Context;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.view.ViewCompat;

import com.fadcam.ui.faditor.SheetKit;
import com.fadcam.ui.faditor.Studio;
import com.fadcam.ui.type.Type;

/**
 * A section's heading over its group of rows: the kit's section label (mono, spaced capitals,
 * record 06 {@code .tg}) led by the same small glyph its jump chip wears in the dock, so the chip
 * and the place it jumps to read as one thing. A heading for screen readers.
 */
@SuppressLint("ViewConstructor")
final class SettingsSectionView extends LinearLayout {

    private final TextView glyph, label;

    SettingsSectionView(@NonNull Context c) {
        super(c);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setPadding(SettingsViews.dp(c, 6), SettingsViews.dp(c, 22), SettingsViews.dp(c, 6), SettingsViews.dp(c, 8));
        ViewCompat.setAccessibilityHeading(this, true);

        glyph = SettingsViews.glyph(c, 12f, Studio.INK_FAINT);
        LayoutParams gLp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        gLp.setMarginEnd(SettingsViews.dp(c, 6));
        addView(glyph, gLp);

        label = SettingsViews.line(c, SheetKit.SECTION_SP, Studio.INK_DIM, true, Type.MEDIUM);
        label.setLetterSpacing(0.14f);
        label.setAllCaps(true);   // after setSingleLine (in line()): the last transformation wins
        addView(label);
    }

    void bind(@NonNull String title, @NonNull String glyphName) {
        label.setText(title);
        glyph.setText(glyphName);
        setContentDescription(title);
    }
}
