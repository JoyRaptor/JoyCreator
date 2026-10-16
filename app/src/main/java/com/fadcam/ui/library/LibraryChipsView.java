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
import com.fadcam.ui.type.Type;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

/**
 * The kind chips, one row that scrolls sideways: All, then every kind, each with a live count, and
 * Project media last behind a hairline (owner ruling c: hidden unless asked for).
 *
 * <p>Chips keep their places as counts change, so the thumb learns where Drawings is. A chip with
 * nothing in it fades to the off grey rather than leaving. The chosen chip is solid cyan with its
 * words cut out of it (the owner's ON rule: never an outline). A small dot in the kind's colour ties
 * each chip to the corner cut on its cards.</p>
 */
@SuppressLint("ViewConstructor")
final class LibraryChipsView extends HorizontalScrollView {

    interface Listener {
        void onKind(@Nullable LibraryKind kind);
    }

    private final Listener listener;
    private final LinearLayout row;
    private final Map<LibraryKind, Chip> chips = new EnumMap<>(LibraryKind.class);
    private final Chip all;
    private LibraryKind selected;

    LibraryChipsView(@NonNull Context c, @NonNull Listener listener) {
        super(c);
        this.listener = listener;
        setHorizontalScrollBarEnabled(false);
        setOverScrollMode(OVER_SCROLL_NEVER);
        setClipToPadding(false);
        row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(LibraryViews.dp(c, 12), 0, LibraryViews.dp(c, 12), 0);
        addView(row);
        all = new Chip(c, null);
        row.addView(all.view);
        for (LibraryKind k : LibraryKind.values()) {
            if (k == LibraryKind.PROJECT_MEDIA) row.addView(divider(c));
            Chip chip = new Chip(c, k);
            chips.put(k, chip);
            row.addView(chip.view);
        }
        paintSelection();
    }

    /**
     * @param counts  per kind, under the current search and shared-in settings
     * @param present kinds that hold anything at all, whatever the filters (decides OTHER's chip)
     */
    void setCounts(@NonNull Map<LibraryKind, Integer> counts, @NonNull Set<LibraryKind> present) {
        all.setCount(LibraryFilter.allCount(counts));
        for (Map.Entry<LibraryKind, Chip> e : chips.entrySet()) {
            LibraryKind k = e.getKey();
            boolean listed = k.alwaysListed() || present.contains(k) || k == selected;
            e.getValue().view.setVisibility(listed ? VISIBLE : GONE);
            Integer n = counts.get(k);
            e.getValue().setCount(n == null ? 0 : n);
        }
        paintSelection();
    }

    void setSelected(@Nullable LibraryKind kind) {
        selected = kind;
        paintSelection();
        View v = kind == null ? all.view : chips.get(kind).view;
        post(() -> smoothScrollTo(Math.max(0, v.getLeft() - getWidth() / 3), 0));
    }

    private void paintSelection() {
        all.paint(selected == null);
        for (Map.Entry<LibraryKind, Chip> e : chips.entrySet()) e.getValue().paint(e.getKey() == selected);
    }

    @NonNull
    private static View divider(@NonNull Context c) {
        View d = new View(c);
        d.setBackgroundColor(Studio.LINE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Math.max(1, LibraryViews.dp(c, 1)),
                LibraryViews.dp(c, 18));
        lp.setMargins(LibraryViews.dp(c, 4), 0, LibraryViews.dp(c, 10), 0);
        d.setLayoutParams(lp);
        return d;
    }

    /** One chip: dot, label, count. */
    private final class Chip {
        final LibraryKind kind;
        final LinearLayout view;
        final View dot;
        final TextView label, count;
        int n;

        Chip(@NonNull Context c, @Nullable LibraryKind kind) {
            this.kind = kind;
            view = new LinearLayout(c);
            view.setOrientation(LinearLayout.HORIZONTAL);
            view.setGravity(Gravity.CENTER_VERTICAL);
            view.setMinimumHeight(LibraryViews.dp(c, 34));
            view.setPadding(LibraryViews.dp(c, kind == null ? 13 : 11), 0, LibraryViews.dp(c, 12), 0);
            view.setClickable(true);
            view.setFocusable(true);
            SheetKit.press(view);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                    LibraryViews.dp(c, 34));
            lp.setMarginEnd(LibraryViews.dp(c, 6));
            view.setLayoutParams(lp);

            dot = new View(c);
            dot.setBackground(LibraryViews.fill(LibraryKindStyle.colours(c, kind)[0], LibraryViews.dp(c, 3)));
            LinearLayout.LayoutParams dLp = new LinearLayout.LayoutParams(LibraryViews.dp(c, 6), LibraryViews.dp(c, 6));
            dLp.setMarginEnd(LibraryViews.dp(c, 6));
            view.addView(dot, dLp);
            dot.setVisibility(kind == null ? GONE : VISIBLE);

            label = LibraryViews.line(c, 12f, Studio.INK_DIM, false, Type.MEDIUM);
            label.setText(LibraryKindStyle.label(kind));
            view.addView(label);
            count = LibraryViews.line(c, 10f, Studio.INK_FAINT, true, Type.MEDIUM);
            LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            cLp.setMarginStart(LibraryViews.dp(c, 6));
            view.addView(count, cLp);
            view.setOnClickListener(v -> listener.onKind(kind));
        }

        void setCount(int n) {
            this.n = n;
            count.setText(String.valueOf(n));
            Context c = view.getContext();
            SheetKit.label(view, c.getResources().getQuantityString(R.plurals.library_a11y_kind, n,
                    c.getString(LibraryKindStyle.label(kind)), n));
        }

        void paint(boolean on) {
            Context c = view.getContext();
            view.setBackground(LibraryViews.pressable(on ? Studio.ARMED : Studio.RAISED, LibraryViews.dp(c, 99)));
            boolean empty = n == 0 && kind != null;
            label.setTextColor(on ? Studio.GROUND : empty ? Studio.INK_OFF : Studio.INK_DIM);
            Type.body(label, on ? Type.SEMIBOLD : Type.MEDIUM);
            count.setTextColor(on ? Studio.alpha(Studio.GROUND, 0xB3) : empty ? Studio.INK_OFF : Studio.INK_FAINT);
            dot.setAlpha(on ? 0f : empty ? 0.35f : 1f);
            view.setSelected(on);
        }
    }
}
