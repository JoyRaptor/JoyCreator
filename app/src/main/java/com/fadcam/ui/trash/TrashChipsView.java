package com.fadcam.ui.trash;

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
import com.fadcam.ui.library.LibraryKindStyle;
import com.fadcam.ui.lobby.SlantDrawable;
import com.fadcam.ui.library.LibraryViews;
import com.fadcam.ui.type.Type;

import java.util.EnumMap;
import java.util.Map;

/**
 * All, Projects, Drawings, Recordings, Photos, Other: the Library's chips, same sizes and the same
 * rules. Every chip keeps its place (an empty one fades to the off grey), each carries a live count
 * and its kind's colour dot, and the chosen one is solid cyan with its words cut out.
 */
@SuppressLint("ViewConstructor")
final class TrashChipsView extends HorizontalScrollView {

    interface Listener {
        void onKind(@Nullable TrashKind kind);
    }

    private final Map<TrashKind, Chip> chips = new EnumMap<>(TrashKind.class);
    private final Chip all;
    private TrashKind selected;

    TrashChipsView(@NonNull Context c, @NonNull Listener listener) {
        super(c);
        setHorizontalScrollBarEnabled(false);
        setOverScrollMode(OVER_SCROLL_NEVER);
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(LibraryViews.dp(c, 12), 0, LibraryViews.dp(c, 12), 0);
        addView(row);
        all = new Chip(c, null, listener);
        row.addView(all.view);
        for (TrashKind k : TrashKind.values()) {
            Chip chip = new Chip(c, k, listener);
            chips.put(k, chip);
            row.addView(chip.view);
        }
        paint();
    }

    void setCounts(@NonNull Map<TrashKind, Integer> counts) {
        int total = 0;
        for (Map.Entry<TrashKind, Chip> e : chips.entrySet()) {
            Integer n = counts.get(e.getKey());
            e.getValue().setCount(n == null ? 0 : n);
            total += n == null ? 0 : n;
        }
        all.setCount(total);
        paint();
    }

    void setSelected(@Nullable TrashKind kind) {
        selected = kind;
        paint();
        View v = kind == null ? all.view : chips.get(kind).view;
        post(() -> smoothScrollTo(Math.max(0, v.getLeft() - getWidth() / 3), 0));
    }

    private void paint() {
        all.paint(selected == null);
        for (Map.Entry<TrashKind, Chip> e : chips.entrySet()) e.getValue().paint(e.getKey() == selected);
    }

    /** The lobby New chips' shear, shared with the Library. */
    private static final int SLANT_DP = 10;

    /** One chip: the kind's icon, label, count, on the lobby's sheared shape. */
    private static final class Chip {
        final TrashKind kind;
        final LinearLayout view;
        final TextView icon, label, count;
        int n;

        Chip(@NonNull Context c, @Nullable TrashKind kind, @NonNull Listener listener) {
            this.kind = kind;
            view = new LinearLayout(c);
            view.setOrientation(LinearLayout.HORIZONTAL);
            view.setGravity(Gravity.CENTER_VERTICAL);
            int slant = LibraryViews.dp(c, SLANT_DP);
            // Gap between chips shaved 15% (owner, 2026-10-17); two thirds of what was shaved goes back as padding.
            view.setPadding(slant / 2 + LibraryViews.dp(c, 8), 0, slant / 2 + LibraryViews.dp(c, 8) + Math.max(1, Math.round(3.5f * 0.15f * (2f / 3f) * c.getResources().getDisplayMetrics().density)), 0);
            view.setClickable(true);
            view.setFocusable(true);
            SheetKit.press(view);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                    LibraryViews.dp(c, 34));
            lp.setMarginEnd(Math.round(3.5f * 0.85f * c.getResources().getDisplayMetrics().density));
            view.setLayoutParams(lp);

            icon = LibraryViews.glyph(c, 15f, Studio.INK_DIM);
            icon.setText(LibraryKindStyle.glyph(kind == null ? null : kind.look));
            icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            LinearLayout.LayoutParams iLp = new LinearLayout.LayoutParams(LibraryViews.dp(c, 18), LibraryViews.dp(c, 18));
            iLp.setMarginEnd(LibraryViews.dp(c, 5));
            view.addView(icon, iLp);

            label = LibraryViews.line(c, 12f, Studio.INK_DIM, false, Type.MEDIUM);
            label.setText(LibraryKindStyle.label(kind == null ? null : kind.look));
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
                    c.getString(LibraryKindStyle.label(kind == null ? null : kind.look)), n));
        }

        void paint(boolean on) {
            Context c = view.getContext();
            int[] pair = kind == null ? new int[]{Studio.ARMED_LIGHT, Studio.ARMED} : LibraryKindStyle.colours(c, kind.look);
            int slant = LibraryViews.dp(c, SLANT_DP);
            boolean first = kind == null;
            view.setBackground(on
                    ? new SlantDrawable(pair[0], pair[1], slant, LibraryViews.dp(c, 3), first, false)
                    : new SlantDrawable(Studio.RAISED, Studio.RAISED, slant, LibraryViews.dp(c, 3), first, false));
            boolean empty = n == 0 && kind != null;
            icon.setTextColor(on ? Studio.GROUND : empty ? Studio.INK_OFF : kind == null ? Studio.INK_DIM : pair[0]);
            label.setTextColor(on ? Studio.GROUND : empty ? Studio.INK_OFF : Studio.INK_DIM);
            Type.body(label, on ? Type.SEMIBOLD : Type.MEDIUM);
            count.setTextColor(on ? Studio.alpha(Studio.GROUND, 0xB3) : empty ? Studio.INK_OFF : Studio.INK_FAINT);
            view.setSelected(on);
        }
    }
}
