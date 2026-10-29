package com.fadcam.ui.settingshub;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.fadcam.R;
import com.fadcam.ui.faditor.SheetKit;
import com.fadcam.ui.faditor.Studio;
import com.fadcam.ui.type.Type;

/**
 * Everything the thumb reaches for, at the bottom of the screen, as in the Library's dock: the
 * section chips, and under them the search field. While words are in the field the chips step
 * aside (there are no sections in a list of results) and come back when it is cleared.
 */
final class SettingsSearchDock {

    interface Listener {
        void onQuery(@NonNull String query);
        void onSection(int section);
    }

    final SettingsSectionChips chips;
    private final EditText search;
    private final TextView clear;

    SettingsSearchDock(@NonNull LinearLayout dock, @NonNull Listener l) {
        Context c = dock.getContext();
        View fade = new View(c);
        fade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Studio.alpha(Studio.GROUND, 0x00), Studio.GROUND}));
        dock.addView(fade, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, SettingsViews.dp(c, 20)));

        LinearLayout body = new LinearLayout(c);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setBackgroundColor(Studio.GROUND);
        body.setPadding(0, 0, 0, SettingsViews.dp(c, 10));
        body.setClickable(true);   // a tap between controls must not fall through to a row
        dock.addView(body);

        chips = new SettingsSectionChips(c, l::onSection);
        body.addView(chips, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout pill = new LinearLayout(c);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setBackground(SettingsViews.pill(c, Studio.RAISED));
        pill.setPadding(SettingsViews.dp(c, 12), 0, SettingsViews.dp(c, 2), 0);
        TextView lens = SettingsViews.glyph(c, 18f, Studio.INK_FAINT);
        lens.setText("search");
        lens.setImportantForAccessibility(android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO);   // decorative: the field beside it speaks
        pill.addView(lens);

        search = new EditText(c);
        search.setBackground(null);
        search.setHint(R.string.settingshub_search_hint);
        search.setHintTextColor(Studio.INK_FAINT);
        search.setTextColor(Studio.INK);
        search.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        Type.body(search, Type.REGULAR);
        search.setSingleLine(true);
        search.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        search.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        search.setPadding(SettingsViews.dp(c, 8), 0, SettingsViews.dp(c, 4), 0);
        SheetKit.label(search, c.getString(R.string.settingshub_search_hint));
        pill.addView(search, new LinearLayout.LayoutParams(0, SettingsViews.dp(c, 44), 1f));

        clear = SheetKit.iconButton(c, "close", Studio.INK_DIM, c.getString(R.string.settingshub_search_clear));
        clear.setVisibility(View.GONE);
        clear.setOnClickListener(v -> search.setText(""));
        pill.addView(clear, new LinearLayout.LayoutParams(SettingsViews.dp(c, 40), SettingsViews.dp(c, 40)));

        LinearLayout.LayoutParams pLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                SettingsViews.dp(c, 44));
        pLp.setMargins(SettingsViews.dp(c, 12), SettingsViews.dp(c, 4), SettingsViews.dp(c, 12), 0);
        body.addView(pill, pLp);
        pill.setOnClickListener(v -> focusSearch());   // the lens and the pill's ends type too
        SheetKit.label(pill, c.getString(R.string.settingshub_search_hint));

        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int d) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int d) { }
            @Override public void afterTextChanged(Editable s) {
                boolean typed = s.toString().trim().length() > 0;
                clear.setVisibility(s.length() > 0 ? View.VISIBLE : View.GONE);
                chips.setVisibility(typed ? View.GONE : View.VISIBLE);
                l.onQuery(s.toString());
            }
        });
        search.setOnEditorActionListener((v, action, ev) -> {
            if (action == EditorInfo.IME_ACTION_SEARCH) { hideKeyboard(); return true; }
            return false;
        });
    }

    /** Posted: on a view that is not attached yet, focus and the keyboard are both refused. */
    void focusSearch() {
        search.post(() -> {
            search.requestFocus();
            InputMethodManager imm = (InputMethodManager) search.getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(search, InputMethodManager.SHOW_IMPLICIT);
        });
    }

    /** Put words back in the field (a rebuilt view); the watcher passes them on as usual. */
    void setQuery(@NonNull String q) {
        if (!q.contentEquals(search.getText())) search.setText(q);
    }

    /** Back with words in the field clears them first; true when it did. */
    boolean clearSearch() {
        if (search.getText().length() == 0) return false;
        search.setText("");
        return true;
    }

    void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) search.getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(search.getWindowToken(), 0);
        search.clearFocus();
    }
}
