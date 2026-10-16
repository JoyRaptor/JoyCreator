package com.fadcam.ui.trash;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.R;
import com.fadcam.ui.faditor.SheetKit;
import com.fadcam.ui.faditor.Studio;
import com.fadcam.ui.library.LibraryViews;
import com.fadcam.ui.type.Type;

/**
 * Everything the thumb reaches for, at the bottom: the kind chips, and under them one of two rows.
 *
 * <ul>
 *   <li>At rest: the Trash rule as a quiet line ("Kept 30 days, then deleted for good.") and the
 *       options button.</li>
 *   <li>Choosing: stop choosing, then Delete forever (the danger red, never the action gradient)
 *       and Restore (the one gradient pill, at the far end where the thumb already is).</li>
 * </ul>
 */
final class TrashDock {

    interface Listener {
        void onKind(@Nullable TrashKind kind);
        void onOptions();
        void onStopChoosing();
        void onRestore();
        void onDeleteForever();
    }

    final TrashChipsView chips;
    private final LinearLayout rest, choose;
    private final TextView rule, restore, delete;

    TrashDock(@NonNull LinearLayout dock, @NonNull Listener l) {
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

        chips = new TrashChipsView(c, l::onKind);
        body.addView(chips, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        // ── at rest ──
        rest = row(c);
        rule = LibraryViews.line(c, 11f, Studio.INK_FAINT, false, Type.REGULAR);
        rule.setPadding(LibraryViews.dp(c, 4), 0, LibraryViews.dp(c, 8), 0);
        rest.addView(rule, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView options = SheetKit.iconButton(c, "more_horiz", Studio.INK_DIM, c.getString(R.string.trash_options));
        options.setBackground(LibraryViews.pressable(Studio.RAISED, LibraryViews.dp(c, 99)));
        options.setOnClickListener(v -> l.onOptions());
        rest.addView(options, new LinearLayout.LayoutParams(LibraryViews.dp(c, 42), LibraryViews.dp(c, 42)));
        body.addView(rest);

        // ── choosing ──
        choose = row(c);
        TextView stop = SheetKit.iconButton(c, "close", Studio.INK_DIM, c.getString(R.string.trash_stop_selecting));
        stop.setBackground(LibraryViews.pressable(Studio.RAISED, LibraryViews.dp(c, 99)));
        stop.setOnClickListener(v -> l.onStopChoosing());
        choose.addView(stop, new LinearLayout.LayoutParams(LibraryViews.dp(c, 42), LibraryViews.dp(c, 42)));
        choose.addView(new View(c), new LinearLayout.LayoutParams(0, 1, 1f));
        delete = TrashSheets.dangerPill(c, c.getString(R.string.trash_delete_forever), v -> l.onDeleteForever());
        choose.addView(delete, pill(c));
        restore = SheetKit.pillButton(c, c.getString(R.string.trash_restore), true, v -> l.onRestore());
        choose.addView(restore, pill(c));
        choose.setVisibility(View.GONE);
        body.addView(choose);
    }

    @NonNull
    private static LinearLayout row(@NonNull Context c) {
        LinearLayout r = new LinearLayout(c);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setMinimumHeight(LibraryViews.dp(c, 50));
        r.setPadding(LibraryViews.dp(c, 12), LibraryViews.dp(c, 8), LibraryViews.dp(c, 12), 0);
        return r;
    }

    @NonNull
    private static LinearLayout.LayoutParams pill(@NonNull Context c) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                LibraryViews.dp(c, 42));
        lp.setMarginStart(LibraryViews.dp(c, 8));
        return lp;
    }

    void setRule(@NonNull String words) {
        rule.setText(words);
    }

    /** {@code count} 0 puts the dock back at rest. */
    void setChoosing(int count) {
        boolean on = count > 0;
        rest.setVisibility(on ? View.GONE : View.VISIBLE);
        choose.setVisibility(on ? View.VISIBLE : View.GONE);
        if (!on) return;
        Context c = restore.getContext();
        SheetKit.label(restore, c.getResources().getQuantityString(R.plurals.trash_restore_tip, count, count));
        SheetKit.label(delete, c.getResources().getQuantityString(R.plurals.trash_delete_forever_tip, count, count));
    }
}
