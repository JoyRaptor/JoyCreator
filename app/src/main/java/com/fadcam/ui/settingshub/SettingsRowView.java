package com.fadcam.ui.settingshub;

import android.annotation.SuppressLint;
import android.content.Context;
import android.text.TextUtils;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.R;
import com.fadcam.ui.faditor.SheetKit;
import com.fadcam.ui.faditor.Studio;
import com.fadcam.ui.type.Type;

/**
 * One row: its glyph, its title, and one line under it, which is the row's live value when it has
 * one (where the folder is, the theme in use) and its description when it does not. A switch-like
 * row ends in a small state mark: ON is solid cyan with the word cut out of it, the owner's ON rule;
 * OFF is the word alone, in the faint grey. Then a chevron, or an arrow out when it leaves the app.
 *
 * <p>In search results the row also names its section above the title, so a hit says where it lives.</p>
 */
@SuppressLint("ViewConstructor")
final class SettingsRowView extends LinearLayout {

    private final TextView glyph, eyebrow, title, line, state, trail;

    SettingsRowView(@NonNull Context c) {
        super(c);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setMinimumHeight(SettingsViews.dp(c, 56));
        setPadding(SettingsViews.dp(c, 14), SettingsViews.dp(c, 10), SettingsViews.dp(c, 10), SettingsViews.dp(c, 10));
        setClickable(true);
        setFocusable(true);
        SheetKit.press(this);

        glyph = SettingsViews.glyph(c, 20f, Studio.INK_DIM);
        LayoutParams gLp = new LayoutParams(SettingsViews.dp(c, 24), SettingsViews.dp(c, 24));
        gLp.setMarginEnd(SettingsViews.dp(c, 14));
        addView(glyph, gLp);

        LinearLayout col = new LinearLayout(c);
        col.setOrientation(VERTICAL);
        eyebrow = SettingsViews.line(c, 8.5f, Studio.INK_FAINT, true, Type.MEDIUM);
        eyebrow.setLetterSpacing(0.12f);
        eyebrow.setAllCaps(true);   // after setSingleLine (in line()): both are transformations, the last wins
        eyebrow.setVisibility(GONE);
        LayoutParams eLp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        eLp.bottomMargin = SettingsViews.dp(c, 4);
        col.addView(eyebrow, eLp);
        title = SettingsViews.line(c, 14f, Studio.INK, false, Type.MEDIUM);
        col.addView(title, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        line = SettingsViews.line(c, 12f, Studio.INK_FAINT, false, Type.REGULAR);
        line.setSingleLine(false);
        line.setMaxLines(2);
        line.setEllipsize(TextUtils.TruncateAt.END);
        line.setLineSpacing(0, 1.15f);
        LayoutParams lLp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        lLp.topMargin = SettingsViews.dp(c, 4);
        col.addView(line, lLp);
        addView(col, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));

        state = SettingsViews.line(c, 9.5f, Studio.INK_FAINT, true, Type.SEMIBOLD);
        state.setAllCaps(true);
        state.setLetterSpacing(0.08f);
        state.setGravity(Gravity.CENTER);
        state.setPadding(SettingsViews.dp(c, 8), SettingsViews.dp(c, 4), SettingsViews.dp(c, 8), SettingsViews.dp(c, 4));
        state.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);   // the row says it
        state.setVisibility(GONE);
        LayoutParams sLp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        sLp.setMarginStart(SettingsViews.dp(c, 8));
        addView(state, sLp);

        trail = SettingsViews.glyph(c, 18f, Studio.INK_OFF);
        LayoutParams tLp = new LayoutParams(SettingsViews.dp(c, 24), SettingsViews.dp(c, 24));
        tLp.setMarginStart(SettingsViews.dp(c, 4));
        addView(trail, tLp);
    }

    /**
     * @param section the section's name to show above the title (search results), or null
     * @param value   the row's live value, or null when it has none (or it has not been read yet)
     */
    void bind(@NonNull SettingsCatalog.Row row, @Nullable String section, @NonNull SettingsLines.Shape shape,
              @Nullable SettingsStatus.Value value) {
        Context c = getContext();
        setBackground(SettingsViews.rowBackground(c, shape));
        glyph.setText(row.glyph);
        String name = c.getString(row.title);
        String desc = c.getString(row.summary);
        title.setText(name);
        eyebrow.setVisibility(section == null ? GONE : VISIBLE);
        if (section != null) eyebrow.setText(section);
        trail.setText(row.trail == SettingsCatalog.Trail.LEAVE ? "open_in_new" : "chevron_right");

        boolean replacesLine = value != null
                && (value.kind == SettingsStatus.Kind.LINE || value.kind == SettingsStatus.Kind.ATTENTION);
        line.setText(replacesLine ? value.text : desc);
        line.setTextColor(!replacesLine ? Studio.INK_FAINT
                : value.kind == SettingsStatus.Kind.ATTENTION ? Studio.CAREFUL : Studio.INK_DIM);
        paintState(value);

        // Read aloud, and shown on hover: the description too, even where the value took its line.
        String said;
        if (row.trail == SettingsCatalog.Trail.LEAVE) {
            said = c.getString(R.string.settingshub_row_a11y_leaves, name, desc);
        } else if (value != null) {
            said = c.getString(R.string.settingshub_row_a11y_state, name, value.text, desc);
        } else {
            said = c.getString(R.string.settingshub_row_a11y, name, desc);
        }
        SheetKit.label(this, said);
    }

    private void paintState(@Nullable SettingsStatus.Value value) {
        boolean shown = value != null
                && (value.kind == SettingsStatus.Kind.ON || value.kind == SettingsStatus.Kind.OFF);
        state.setVisibility(shown ? VISIBLE : GONE);
        if (!shown) return;
        boolean on = value.kind == SettingsStatus.Kind.ON;
        state.setText(value.text);
        state.setTextColor(on ? Studio.GROUND : Studio.INK_FAINT);
        state.setBackground(on ? SettingsViews.pill(getContext(), Studio.ARMED) : null);
    }
}
