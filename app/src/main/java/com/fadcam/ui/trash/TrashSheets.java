package com.fadcam.ui.trash;

import android.content.Context;
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
import com.fadcam.utils.TrashManager;
import com.google.android.material.bottomsheet.BottomSheetDialog;

import java.util.List;

/**
 * The two sheets that stand between a tap and a change: the confirm that names what goes forever,
 * and the choice of where restored recordings go. Built from SheetKit like every sheet in the app.
 */
final class TrashSheets {

    interface Destination {
        void onChosen(@NonNull TrashManager.RestoreDestination d);
    }

    private TrashSheets() {}

    /** The danger pill: red words on a faint red wash. Destroys, so it never wears the action gradient. */
    @NonNull
    static TextView dangerPill(@NonNull Context c, @NonNull CharSequence text, @NonNull View.OnClickListener onClick) {
        TextView b = SheetKit.pillButton(c, text, false, onClick);
        b.setBackground(SheetKit.pillBackground(c, Studio.alpha(Studio.DANGER, 0x29)));
        b.setTextColor(Studio.DANGER);
        Type.body(b, Type.SEMIBOLD);
        return b;
    }

    @NonNull
    private static BottomSheetDialog sheet(@NonNull Context c) {
        BottomSheetDialog d = new BottomSheetDialog(c, R.style.CustomBottomSheetDialogTheme);
        SheetKit.install(d, null);
        return d;
    }

    @NonNull
    private static LinearLayout column(@NonNull Context c) {
        LinearLayout root = new LinearLayout(c);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(0, 0, 0, SheetKit.dp(c, 16));
        return root;
    }

    /**
     * "Delete 3 items forever?" / "This cannot be undone." with Keep and the red button. The red
     * button sits at the far end, so the thumb resting where it tapped lands on Keep, not on it.
     */
    static void confirm(@NonNull Context c, @NonNull String question, @NonNull String detail,
                        @NonNull String action, @NonNull Runnable onConfirm) {
        BottomSheetDialog d = sheet(c);
        LinearLayout root = column(c);
        SheetKit.Header header = SheetKit.header(c, question, null);
        header.title.setSingleLine(false);
        header.title.setMaxLines(3);
        header.addTrailing(SheetKit.closeButton(c, d::dismiss));
        root.addView(header.view);
        root.addView(SheetKit.subtitle(c, detail));

        LinearLayout buttons = new LinearLayout(c);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        buttons.setPadding(SheetKit.dp(c, SheetKit.ROW_INSET_DP), SheetKit.dp(c, 4),
                SheetKit.dp(c, SheetKit.ROW_INSET_DP), 0);
        buttons.addView(SheetKit.pillButton(c, c.getString(R.string.trash_confirm_keep), false, v -> d.dismiss()));
        TextView go = dangerPill(c, action, v -> {
            d.dismiss();
            onConfirm.run();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginStart(SheetKit.dp(c, 8));
        buttons.addView(go, lp);
        root.addView(buttons);
        d.setContentView(SheetKit.fitNavBar(root));
        d.show();
    }

    /**
     * Where recordings go, offered exactly as the classic Trash offers it
     * ({@code TrashManager.getAvailableRestoreDestinations}). Choosing one restores: restoring
     * loses nothing, so there is no second question.
     */
    static void destination(@NonNull Context c, int count, boolean mixed,
                            @NonNull List<TrashManager.RestoreDestination> offered, @NonNull Destination l) {
        BottomSheetDialog d = sheet(c);
        LinearLayout root = column(c);
        SheetKit.Header header = SheetKit.header(c,
                c.getResources().getQuantityString(R.plurals.trash_restore_title, count, count), null);
        header.addTrailing(SheetKit.closeButton(c, d::dismiss));
        root.addView(header.view);
        root.addView(SheetKit.subtitle(c, c.getString(mixed ? R.string.trash_restore_where_mixed
                : R.string.trash_restore_where)));
        for (TrashManager.RestoreDestination dest : offered) {
            int title, desc;
            String glyph;
            switch (dest) {
                case DOWNLOADS_FOLDER:
                    title = R.string.restore_destination_downloads;
                    desc = R.string.restore_destination_downloads_desc;
                    glyph = "download";
                    break;
                case INTERNAL_STORAGE:
                    title = R.string.restore_destination_internal;
                    desc = R.string.restore_destination_internal_desc;
                    glyph = "smartphone";
                    break;
                case SAF_STORAGE:
                    title = R.string.restore_destination_original;
                    desc = R.string.restore_destination_original_desc;
                    glyph = "undo";
                    break;
                default:
                    continue;
            }
            LinearLayout row = SheetKit.detailRow(c, glyph, Studio.INK_DIM, c.getString(title), c.getString(desc),
                    SheetKit.chevron(c), true);
            row.setOnClickListener(v -> {
                d.dismiss();
                l.onChosen(dest);
            });
            root.addView(row);
        }
        d.setContentView(SheetKit.fitNavBar(root));
        d.show();
    }
}
