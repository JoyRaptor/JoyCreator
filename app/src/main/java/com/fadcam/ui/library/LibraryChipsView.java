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
import java.util.Set;

/**
 * The kind chips, one row that scrolls sideways: All, then every kind, each with a live count, and
 * Project media last behind a hairline (owner ruling c: hidden unless asked for).
 *
 * <p>Chips keep their places as counts change, so the thumb learns where Drawings is. A chip with
 * nothing in it fades to the off grey rather than leaving.</p>
 *
 * <p>The shape is the lobby's own: the sheared chip of the New row (SlantDrawable), the first one
 * square on the left and every one after it leaning. Off = zinc with the kind's colour on its icon;
 * on = the kind's gradient with icon, words and count punched out in black (owner, 2026-10-17:
 * "everyone has pills, this will make us stand out").</p>
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

    /** Horizontal run of the shear, the lobby New chips' value. */
    private static final int SLANT_DP = 10;

    /** One chip: the kind's icon, label, count, on the lobby's sheared shape. */
    private final class Chip {
        final LibraryKind kind;
        final LinearLayout view;
        final TextView icon, label, count;
        int n;

        Chip(@NonNull Context c, @Nullable LibraryKind kind) {
            this.kind = kind;
            view = new LinearLayout(c);
            view.setOrientation(LinearLayout.HORIZONTAL);
            view.setGravity(Gravity.CENTER_VERTICAL);
            view.setMinimumHeight(LibraryViews.dp(c, 34));
            int slant = LibraryViews.dp(c, SLANT_DP);
            // Content centred in the shape; the lean eats the ends, so pad by about half of it.
            view.setPadding(slant / 2 + LibraryViews.dp(c, 8), 0, slant / 2 + LibraryViews.dp(c, 8), 0);
            view.setClickable(true);
            view.setFocusable(true);
            SheetKit.press(view);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                    LibraryViews.dp(c, 34));
            // Banded, like the New row: the gap reads as a division, not a space.
            lp.setMarginEnd(Math.round(3.5f * c.getResources().getDisplayMetrics().density));
            view.setLayoutParams(lp);

            icon = LibraryViews.glyph(c, 15f, Studio.INK_DIM);
            icon.setText(LibraryKindStyle.glyph(kind));
            icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);   // the chip speaks once, as itself
            LinearLayout.LayoutParams iLp = new LinearLayout.LayoutParams(LibraryViews.dp(c, 18), LibraryViews.dp(c, 18));
            iLp.setMarginEnd(LibraryViews.dp(c, 5));
            view.addView(icon, iLp);

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
            int[] pair = kind == null ? new int[]{Studio.ARMED_LIGHT, Studio.ARMED} : LibraryKindStyle.colours(c, kind);
            boolean first = kind == null;   // the row starts square on the left, like the lobby's New row
            int slant = LibraryViews.dp(c, SLANT_DP);
            view.setBackground(on
                    ? new SlantDrawable(pair[0], pair[1], slant, LibraryViews.dp(c, 3), first, false)
                    : new SlantDrawable(Studio.RAISED, Studio.RAISED, slant, LibraryViews.dp(c, 3), first, false));
            boolean empty = n == 0 && kind != null;
            // On: everything punched out in black. Off: the kind's own colour on the icon, light grey
            // words, medium grey number; an empty kind drops to the off grey.
            icon.setTextColor(on ? Studio.GROUND : empty ? Studio.INK_OFF : kind == null ? Studio.INK_DIM : pair[0]);
            label.setTextColor(on ? Studio.GROUND : empty ? Studio.INK_OFF : Studio.INK_DIM);
            Type.body(label, on ? Type.SEMIBOLD : Type.MEDIUM);
            count.setTextColor(on ? Studio.alpha(Studio.GROUND, 0xB3) : empty ? Studio.INK_OFF : Studio.INK_FAINT);
            view.setSelected(on);
        }
    }
}
