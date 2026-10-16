package com.fadcam.ui.library;

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
import androidx.annotation.Nullable;

import com.fadcam.R;
import com.fadcam.ui.faditor.SheetKit;
import com.fadcam.ui.faditor.Studio;
import com.fadcam.ui.type.Type;

/**
 * Everything the thumb reaches for, at the bottom of the screen: the kind chips, and under them the
 * search field, the Shared in chip and the options button.
 *
 * <p>Shared in is ONE button that cycles any, only, hide (the Studio's one-button cycle). Any is a
 * resting grey pill; only and hide are both real filters, so both are ON: solid cyan, words cut
 * out, and a glyph that says which.</p>
 */
final class LibraryDock {

    interface Listener {
        void onKind(@Nullable LibraryKind kind);
        void onQuery(@NonNull String query);
        void onSharedCycle();
        void onOptions();
    }

    final LibraryChipsView chips;
    private final EditText search;
    private final TextView clear, sharedGlyph, sharedLabel;
    private final LinearLayout shared;

    LibraryDock(@NonNull LinearLayout dock, @NonNull Listener l) {
        Context c = dock.getContext();
        View fade = new View(c);
        fade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Studio.alpha(Studio.GROUND, 0x00), Studio.GROUND}));
        dock.addView(fade, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, LibraryViews.dp(c, 24)));

        LinearLayout body = new LinearLayout(c);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setBackgroundColor(Studio.GROUND);
        body.setPadding(0, 0, 0, LibraryViews.dp(c, 10));
        body.setClickable(true);   // taps between controls must not fall through to a card
        dock.addView(body);

        chips = new LibraryChipsView(c, l::onKind);
        body.addView(chips, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout controls = new LinearLayout(c);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER_VERTICAL);
        controls.setPadding(LibraryViews.dp(c, 12), LibraryViews.dp(c, 8), LibraryViews.dp(c, 12), 0);
        body.addView(controls);

        // ── search ──
        LinearLayout pill = new LinearLayout(c);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setBackground(LibraryViews.fill(Studio.RAISED, LibraryViews.dp(c, 99)));
        pill.setPadding(LibraryViews.dp(c, 12), 0, LibraryViews.dp(c, 4), 0);
        TextView lens = LibraryViews.glyph(c, 18f, Studio.INK_FAINT);
        lens.setText("search");
        pill.addView(lens);
        search = new EditText(c);
        search.setBackground(null);
        search.setHint(R.string.library_search_hint);
        search.setHintTextColor(Studio.INK_FAINT);
        search.setTextColor(Studio.INK);
        search.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        Type.body(search, Type.REGULAR);
        search.setSingleLine(true);
        search.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        search.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        search.setPadding(LibraryViews.dp(c, 8), 0, LibraryViews.dp(c, 4), 0);
        SheetKit.label(search, c.getString(R.string.library_search_hint));
        pill.addView(search, new LinearLayout.LayoutParams(0, LibraryViews.dp(c, 40), 1f));
        clear = SheetKit.iconButton(c, "close", Studio.INK_DIM, c.getString(R.string.library_search_clear));
        clear.setVisibility(View.GONE);
        clear.setOnClickListener(v -> search.setText(""));
        pill.addView(clear, new LinearLayout.LayoutParams(LibraryViews.dp(c, 36), LibraryViews.dp(c, 36)));
        controls.addView(pill, new LinearLayout.LayoutParams(0, LibraryViews.dp(c, 42), 1f));

        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int d) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int d) { }
            @Override public void afterTextChanged(Editable s) {
                clear.setVisibility(s.length() > 0 ? View.VISIBLE : View.GONE);
                l.onQuery(s.toString());
            }
        });
        search.setOnEditorActionListener((v, action, ev) -> {
            if (action == EditorInfo.IME_ACTION_SEARCH) { hideKeyboard(); return true; }
            return false;
        });

        // ── shared in ──
        shared = new LinearLayout(c);
        shared.setOrientation(LinearLayout.HORIZONTAL);
        shared.setGravity(Gravity.CENTER_VERTICAL);
        shared.setPadding(LibraryViews.dp(c, 11), 0, LibraryViews.dp(c, 13), 0);
        shared.setClickable(true);
        shared.setFocusable(true);
        SheetKit.press(shared);
        sharedGlyph = LibraryViews.glyph(c, 16f, Studio.INK_DIM);
        shared.addView(sharedGlyph);
        sharedLabel = LibraryViews.line(c, 12f, Studio.INK_DIM, false, Type.MEDIUM);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.setMarginStart(LibraryViews.dp(c, 6));
        shared.addView(sharedLabel, slp);
        shared.setOnClickListener(v -> l.onSharedCycle());
        LinearLayout.LayoutParams shLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                LibraryViews.dp(c, 42));
        shLp.setMarginStart(LibraryViews.dp(c, 8));
        controls.addView(shared, shLp);

        // ── options ──
        TextView options = SheetKit.iconButton(c, "tune", Studio.INK_DIM, c.getString(R.string.library_options));
        options.setBackground(LibraryViews.pressable(Studio.RAISED, LibraryViews.dp(c, 99)));
        options.setOnClickListener(v -> l.onOptions());
        LinearLayout.LayoutParams oLp = new LinearLayout.LayoutParams(LibraryViews.dp(c, 42), LibraryViews.dp(c, 42));
        oLp.setMarginStart(LibraryViews.dp(c, 8));
        controls.addView(options, oLp);
    }

    void paintShared(@NonNull LibraryFilter.Shared s) {
        Context c = shared.getContext();
        boolean on = s != LibraryFilter.Shared.ANY;
        shared.setBackground(LibraryViews.pressable(on ? Studio.ARMED : Studio.RAISED, LibraryViews.dp(c, 99)));
        int ink = on ? Studio.GROUND : Studio.INK_DIM;
        sharedGlyph.setTextColor(ink);
        sharedLabel.setTextColor(ink);
        Type.body(sharedLabel, on ? Type.SEMIBOLD : Type.MEDIUM);
        sharedGlyph.setText(s == LibraryFilter.Shared.HIDE ? "visibility_off" : "move_to_inbox");
        int label = s == LibraryFilter.Shared.ONLY ? R.string.library_shared_only
                : s == LibraryFilter.Shared.HIDE ? R.string.library_shared_hide : R.string.library_shared_any;
        int tip = s == LibraryFilter.Shared.ONLY ? R.string.library_shared_only_tip
                : s == LibraryFilter.Shared.HIDE ? R.string.library_shared_hide_tip : R.string.library_shared_any_tip;
        sharedLabel.setText(label);
        SheetKit.label(shared, c.getString(tip));
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
