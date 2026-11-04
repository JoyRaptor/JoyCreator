package com.fadcam.ui.library;

import android.annotation.SuppressLint;
import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.R;
import com.fadcam.ui.faditor.SheetKit;
import com.fadcam.ui.faditor.Studio;
import com.fadcam.ui.lobby.SlantDrawable;
import com.fadcam.ui.type.Type;

import java.util.EnumMap;
import java.util.Map;

/**
 * The Recordings chip's second row: All, Camera, Screen, Stream, Dual, each with its count. Shown
 * only while Recordings is the chosen kind. Same language as {@link LibraryChipsView}: sheared
 * chips, on = the Recordings gradient with icon, words and count punched out in black, off = zinc
 * with the Recordings colour on the icon, a source with nothing in it fades to the off grey. A bit
 * shorter than the kind chips, so the two rows read as parent and child.
 */
@SuppressLint("ViewConstructor")
final class LibrarySourceChips extends HorizontalScrollView {

    interface Listener {
        void onSource(@Nullable LibraryCapture.Source source);
    }

    private static final int SLANT_DP = 8;
    private static final int HEIGHT_DP = 30;

    private final Listener listener;
    private final Chip all;
    private final Map<LibraryCapture.Source, Chip> chips = new EnumMap<>(LibraryCapture.Source.class);
    private LibraryCapture.Source selected;

    LibrarySourceChips(@NonNull Context c, @NonNull Listener listener) {
        super(c);
        this.listener = listener;
        setHorizontalScrollBarEnabled(false);
        setOverScrollMode(OVER_SCROLL_NEVER);
        setClipToPadding(false);
        setVisibility(GONE);
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(LibraryViews.dp(c, 12), LibraryViews.dp(c, 6), LibraryViews.dp(c, 12), 0);
        addView(row);
        all = new Chip(c, null);
        row.addView(all.view);
        for (LibraryCapture.Source s : LibraryCapture.Source.values()) {
            Chip chip = new Chip(c, s);
            chips.put(s, chip);
            row.addView(chip.view);
        }
        paint();
    }

    /** Height the row adds to the dock while it shows, in pixels. */
    int height() {
        return LibraryViews.dp(getContext(), HEIGHT_DP + 6);
    }

    void show(boolean show) {
        setVisibility(show ? VISIBLE : GONE);
    }

    void setSelected(@Nullable LibraryCapture.Source s) {
        selected = s;
        paint();
    }

    void setCounts(int total, @NonNull Map<LibraryCapture.Source, Integer> counts) {
        all.setCount(total);
        for (Map.Entry<LibraryCapture.Source, Chip> e : chips.entrySet()) {
            Integer n = counts.get(e.getKey());
            e.getValue().setCount(n == null ? 0 : n);
        }
        paint();
    }

    private void paint() {
        all.paint(selected == null);
        for (Map.Entry<LibraryCapture.Source, Chip> e : chips.entrySet()) e.getValue().paint(e.getKey() == selected);
    }

    private final class Chip {
        final LibraryCapture.Source source;
        final LinearLayout view;
        final TextView icon, label, count;
        int n;

        Chip(@NonNull Context c, @Nullable LibraryCapture.Source source) {
            this.source = source;
            view = new LinearLayout(c);
            view.setOrientation(LinearLayout.HORIZONTAL);
            view.setGravity(Gravity.CENTER_VERTICAL);
            int slant = LibraryViews.dp(c, SLANT_DP);
            view.setPadding(slant / 2 + LibraryViews.dp(c, 8), 0, slant / 2 + LibraryViews.dp(c, 9), 0);
            view.setClickable(true);
            view.setFocusable(true);
            SheetKit.press(view);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                    LibraryViews.dp(c, HEIGHT_DP));
            lp.setMarginEnd(Math.round(3.5f * 0.85f * c.getResources().getDisplayMetrics().density));
            view.setLayoutParams(lp);

            icon = LibraryViews.glyph(c, 14f, Studio.INK_DIM);
            icon.setText(source == null ? "videocam" : source.glyph);
            icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            LinearLayout.LayoutParams iLp = new LinearLayout.LayoutParams(LibraryViews.dp(c, 16), LibraryViews.dp(c, 16));
            iLp.setMarginEnd(LibraryViews.dp(c, 5));
            view.addView(icon, iLp);

            label = LibraryViews.line(c, 12f, Studio.INK_DIM, false, Type.MEDIUM);
            label.setText(source == null ? R.string.library_source_all : source.label);
            view.addView(label);
            count = LibraryViews.line(c, 10f, Studio.INK_FAINT, true, Type.MEDIUM);
            LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            cLp.setMarginStart(LibraryViews.dp(c, 6));
            view.addView(count, cLp);
            view.setOnClickListener(v -> listener.onSource(source));
        }

        void setCount(int n) {
            this.n = n;
            count.setText(String.valueOf(n));
            Context c = view.getContext();
            SheetKit.label(view, c.getString(R.string.library_source_a11y,
                    c.getString(source == null ? R.string.library_source_all : source.label), n));
        }

        void paint(boolean on) {
            Context c = view.getContext();
            int[] pair = LibraryKindStyle.colours(c, LibraryKind.RECORDINGS);
            boolean first = source == null;   // square on the left, like the row above
            int slant = LibraryViews.dp(c, SLANT_DP);
            view.setBackground(on
                    ? new SlantDrawable(pair[0], pair[1], slant, LibraryViews.dp(c, 3), first, false)
                    : new SlantDrawable(Studio.RAISED, Studio.RAISED, slant, LibraryViews.dp(c, 3), first, false));
            boolean empty = n == 0 && source != null;
            icon.setTextColor(on ? Studio.GROUND : empty ? Studio.INK_OFF : source == null ? Studio.INK_DIM : pair[0]);
            label.setTextColor(on ? Studio.GROUND : empty ? Studio.INK_OFF : Studio.INK_DIM);
            Type.body(label, on ? Type.SEMIBOLD : Type.MEDIUM);
            count.setTextColor(on ? Studio.alpha(Studio.GROUND, 0xB3) : empty ? Studio.INK_OFF : Studio.INK_FAINT);
            view.setSelected(on);
        }
    }
}
