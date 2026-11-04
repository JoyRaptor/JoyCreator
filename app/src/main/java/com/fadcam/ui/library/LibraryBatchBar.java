package com.fadcam.ui.library;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.fadcam.R;
import com.fadcam.ui.faditor.SheetKit;
import com.fadcam.ui.faditor.Studio;
import com.fadcam.ui.type.Type;

/**
 * What the thumb reaches for while choosing, in the place the dock sits: stop choosing, how many are
 * chosen, then Save to gallery, Open in Studio (one video only) and Move to Trash.
 *
 * <p>Move to Trash takes two taps on the same button, as the item sheet's does: the first turns it
 * into "Tap again", and any change of choice puts it back. Like the Trash screen's bar, it sits on the
 * ground colour (a plain bar, never an opaque drawer).</p>
 */
final class LibraryBatchBar {

    interface Listener {
        void onStop();
        void onSave();
        void onOpenInStudio();
        void onTrash();
    }

    private final LinearLayout bar;
    private final TextView count, save, studio, trash;
    private boolean armed;

    LibraryBatchBar(@NonNull LinearLayout host, @NonNull Listener l) {
        Context c = host.getContext();
        bar = host;
        View fade = new View(c);
        fade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Studio.alpha(Studio.GROUND, 0x00), Studio.GROUND}));
        host.addView(fade, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, LibraryViews.dp(c, 24)));

        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(LibraryViews.dp(c, 50));
        row.setBackgroundColor(Studio.GROUND);
        row.setPadding(LibraryViews.dp(c, 12), LibraryViews.dp(c, 8), LibraryViews.dp(c, 12), LibraryViews.dp(c, 10));
        row.setClickable(true);   // taps between controls must not fall through to a card
        host.addView(row);

        TextView stop = SheetKit.iconButton(c, "close", Studio.INK_DIM, c.getString(R.string.library_select_stop));
        stop.setBackground(LibraryViews.pressable(Studio.RAISED, LibraryViews.dp(c, 99)));
        stop.setOnClickListener(v -> l.onStop());
        row.addView(stop, new LinearLayout.LayoutParams(LibraryViews.dp(c, 42), LibraryViews.dp(c, 42)));

        count = LibraryViews.line(c, 12f, Studio.INK_DIM, true, Type.MEDIUM);
        count.setPadding(LibraryViews.dp(c, 10), 0, LibraryViews.dp(c, 6), 0);
        row.addView(count, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        save = SheetKit.iconButton(c, "file_download", Studio.INK, c.getString(R.string.library_action_save_gallery));
        save.setBackground(LibraryViews.pressable(Studio.RAISED, LibraryViews.dp(c, 99)));
        save.setOnClickListener(v -> l.onSave());
        row.addView(save, button(c));

        studio = SheetKit.iconButton(c, "movie_edit", Studio.INK, c.getString(R.string.library_action_open_studio));
        studio.setBackground(LibraryViews.pressable(Studio.RAISED, LibraryViews.dp(c, 99)));
        studio.setOnClickListener(v -> l.onOpenInStudio());
        row.addView(studio, button(c));

        // The danger pill: red words on a faint red wash. Destroys, so it never wears the action gradient.
        trash = SheetKit.pillButton(c, c.getString(R.string.library_batch_trash), false, v -> {
            if (!armed) { arm(true); return; }
            l.onTrash();
        });
        trash.setBackground(SheetKit.pillBackground(c, Studio.alpha(Studio.DANGER, 0x29)));
        trash.setTextColor(Studio.DANGER);
        Type.body(trash, Type.SEMIBOLD);
        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                LibraryViews.dp(c, 42));
        tLp.setMarginStart(LibraryViews.dp(c, 8));
        row.addView(trash, tLp);

        host.setVisibility(View.GONE);
    }

    @NonNull
    private static LinearLayout.LayoutParams button(@NonNull Context c) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LibraryViews.dp(c, 42), LibraryViews.dp(c, 42));
        lp.setMarginStart(LibraryViews.dp(c, 8));
        return lp;
    }

    /** Show or hide the whole bar. */
    void show(boolean on) {
        bar.setVisibility(on ? View.VISIBLE : View.GONE);
        if (!on) arm(false);
    }

    /**
     * Repaint for the current choice: how many are chosen, and which actions apply to them.
     *
     * @param chosen how many cards are chosen
     * @param videos how many of them are videos (what Save and Open in Studio can act on)
     * @param trashable how many of them can go to Trash
     */
    void setChoice(int chosen, int videos, int trashable) {
        Context c = bar.getContext();
        arm(false);
        count.setText(c.getResources().getQuantityString(R.plurals.library_batch_count, chosen, chosen));
        enable(save, videos > 0, c.getResources().getQuantityString(R.plurals.library_batch_save_tip, videos, videos));
        // Open in Studio takes one video; it is hidden, not greyed, when it cannot apply.
        studio.setVisibility(chosen == 1 && videos == 1 ? View.VISIBLE : View.GONE);
        enable(trash, trashable > 0, c.getResources().getQuantityString(R.plurals.library_batch_trash_tip, trashable, trashable));
    }

    private static void enable(@NonNull TextView v, boolean on, @NonNull CharSequence tip) {
        v.setEnabled(on);
        v.setAlpha(on ? 1f : 0.35f);
        SheetKit.label(v, tip);
    }

    private void arm(boolean on) {
        armed = on;
        Context c = bar.getContext();
        trash.setText(c.getString(on ? R.string.library_batch_trash_again : R.string.library_batch_trash));
        if (on) SheetKit.label(trash, c.getString(R.string.library_batch_trash_again));
    }
}
