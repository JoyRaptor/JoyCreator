package com.fadcam.ui.library;

import android.content.Context;
import android.text.InputType;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;

import com.fadcam.R;
import com.fadcam.joyfolder.FileKind;
import com.fadcam.joyfolder.Placement;
import com.fadcam.ui.faditor.SheetKit;
import com.fadcam.ui.faditor.Studio;
import com.fadcam.ui.type.Type;
import com.google.android.material.bottomsheet.BottomSheetDialog;

/**
 * Long-press on a card: what it is, where it lives, and what can be done to it ({@link LibraryActions}
 * decides which). Also shown on a tap when nothing in the app opens the thing (a font).
 *
 * <p>Move to Trash takes two taps on the same row, the second one where the thumb already is; no
 * dialog in the way. Rename turns the sheet into a name field in place.</p>
 */
final class LibraryItemSheet {

    interface Listener {
        void onOpen(@NonNull LibraryItem it);
        void onDone(@NonNull String message, boolean changed);
    }

    private LibraryItemSheet() {}

    static void show(@NonNull Context c, @NonNull LibraryItem it, @NonNull Listener l) {
        BottomSheetDialog d = new BottomSheetDialog(c, R.style.CustomBottomSheetDialogTheme);
        SheetKit.install(d, null);
        LinearLayout root = new LinearLayout(c);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(0, 0, 0, SheetKit.dp(c, 16));

        SheetKit.Header header = SheetKit.header(c, it.name, c.getString(LibraryKindStyle.tag(it)));
        header.addTrailing(SheetKit.closeButton(c, d::dismiss));
        root.addView(header.view);
        root.addView(SheetKit.subtitle(c, details(c, it)));

        LinearLayout actions = new LinearLayout(c);
        actions.setOrientation(LinearLayout.VERTICAL);
        root.addView(actions);

        boolean opens = it.fileKind != FileKind.FONT && it.fileKind != FileKind.PLUGIN;
        if (opens) {
            actions.addView(SheetKit.row(c, "open_in_new", c.getString(R.string.library_action_open), false,
                    SheetKit.Trail.NONE, v -> { d.dismiss(); l.onOpen(it); }).view);
        } else {
            actions.addView(SheetKit.subtitle(c, c.getString(R.string.library_nowhere_to_open)));
        }
        if (LibraryActions.canRename(it)) {
            actions.addView(SheetKit.row(c, "edit", c.getString(R.string.library_action_rename), false,
                    SheetKit.Trail.NONE, v -> showRename(c, d, actions, it, l)).view);
        }
        if (LibraryActions.canShare(it)) {
            actions.addView(SheetKit.row(c, "share", c.getString(R.string.library_action_share), false,
                    SheetKit.Trail.NONE, v -> { d.dismiss(); LibraryActions.share(c, it); }).view);
        }
        if (LibraryActions.canTrash(it)) {
            final boolean[] armed = {false};
            final SheetKit.Row[] holder = new SheetKit.Row[1];
            holder[0] = SheetKit.row(c, "delete_outline", c.getString(R.string.library_action_trash), false,
                    SheetKit.Trail.NONE, v -> {
                        if (!armed[0]) {
                            armed[0] = true;
                            holder[0].label.setText(R.string.library_action_trash_confirm);
                            SheetKit.label(holder[0].view, c.getString(R.string.library_action_trash_confirm));
                            SheetKit.tintRow(holder[0], Studio.DANGER, true);
                            return;
                        }
                        d.dismiss();
                        LibraryActions.trash(c, it, l::onDone);
                    });
            SheetKit.tintRow(holder[0], Studio.CAREFUL, false);
            actions.addView(holder[0].view);
        }
        d.setContentView(SheetKit.fitNavBar(root));
        d.show();
    }

    /** "In your Joy Creator folder · Recordings", then size and date, one line each. */
    @NonNull
    private static String details(@NonNull Context c, @NonNull LibraryItem it) {
        String where;
        if (it.folderPath != null) {
            String dir = Placement.parentOf(it.folderPath);
            where = dir.isEmpty() ? c.getString(R.string.library_where_folder_top)
                    : c.getString(R.string.library_where_folder, dir);
        } else if (it.parentName != null) {
            where = c.getString(R.string.library_where_project, it.parentName);
        } else if (it.uri != null && it.uri.startsWith("content:")) {
            where = c.getString(R.string.library_where_elsewhere);   // a recording location the user picked
        } else {
            where = c.getString(R.string.library_where_app);
        }
        String size = LibraryFormat.size(c, it.size);
        String when = LibraryFormat.date(c, it.modified);
        return where + "\n" + (size.isEmpty() ? when : c.getString(R.string.library_meta, size, when));
    }

    /** The action rows give way to a name field and one Rename button. */
    private static void showRename(@NonNull Context c, @NonNull BottomSheetDialog d,
                                   @NonNull LinearLayout actions, @NonNull LibraryItem it,
                                   @NonNull Listener l) {
        actions.removeAllViews();
        EditText field = new EditText(c);
        field.setText(it.name);
        field.setSelectAllOnFocus(true);
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        field.setImeOptions(EditorInfo.IME_ACTION_DONE);
        field.setHint(R.string.library_rename_hint);
        field.setHintTextColor(Studio.INK_FAINT);
        field.setTextColor(Studio.INK);
        field.setTextSize(TypedValue.COMPLEX_UNIT_SP, SheetKit.ROW_SP);
        Type.body(field, Type.REGULAR);
        field.setBackground(LibraryViews.fill(Studio.RAISED, SheetKit.dp(c, SheetKit.ROW_RADIUS_DP)));
        field.setPadding(SheetKit.dp(c, 14), SheetKit.dp(c, 12), SheetKit.dp(c, 14), SheetKit.dp(c, 12));
        SheetKit.label(field, c.getString(R.string.library_rename_hint));
        LinearLayout.LayoutParams fLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        fLp.setMargins(SheetKit.dp(c, SheetKit.ROW_INSET_DP), SheetKit.dp(c, 4), SheetKit.dp(c, SheetKit.ROW_INSET_DP), 0);
        actions.addView(field, fLp);

        Runnable save = () -> {
            String name = field.getText().toString().trim();
            if (name.isEmpty() || name.equals(it.name)) { d.dismiss(); return; }
            d.dismiss();
            LibraryActions.rename(c, it, name, l::onDone);
        };
        View button = SheetKit.pillButton(c, c.getString(R.string.library_action_rename), true, v -> save.run());
        LinearLayout.LayoutParams bLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        bLp.gravity = android.view.Gravity.END;
        bLp.setMargins(0, SheetKit.dp(c, 10), SheetKit.dp(c, SheetKit.ROW_INSET_DP), 0);
        actions.addView(button, bLp);
        field.setOnEditorActionListener((v, action, ev) -> {
            if (action == EditorInfo.IME_ACTION_DONE) { save.run(); return true; }
            return false;
        });
        if (d.getWindow() != null) {
            d.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        field.requestFocus();
        field.post(() -> {
            android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager)
                    c.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(field, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
        });
    }
}
