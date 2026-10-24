package com.fadcam.vislab.room;

import android.content.Context;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
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
import com.google.android.material.bottomsheet.BottomSheetDialog;

import java.util.function.Consumer;

/**
 * The two sheets the look footer needs, built from SheetKit like every sheet in the app: a name
 * (Rename, Duplicate) and a confirm that says what a change will replace (Revert, Delete).
 */
final class LookSheets {

    private LookSheets() {}

    @NonNull
    private static BottomSheetDialog sheet(@NonNull Context c) {
        BottomSheetDialog d = new BottomSheetDialog(c, R.style.CustomBottomSheetDialogTheme);
        SheetKit.install(d, null);
        return d;
    }

    @NonNull
    private static LinearLayout column(@NonNull Context c, @NonNull BottomSheetDialog d, @NonNull String title,
                                       @Nullable android.view.View action) {
        LinearLayout root = new LinearLayout(c);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(0, 0, 0, SheetKit.dp(c, 16));
        SheetKit.Header header = SheetKit.header(c, title, null);
        header.title.setSingleLine(false);
        header.title.setMaxLines(3);
        if (action != null) header.addTrailing(action);
        header.addTrailing(SheetKit.closeButton(c, d::dismiss));
        root.addView(header.view);
        return root;
    }

    /**
     * A name field, with its button in the header row beside the title: the keyboard rises over
     * the bottom of an edge-to-edge sheet, so a button under the field was hidden (Note 9, 10-17).
     * The keyboard's Done does the same. An empty name does nothing.
     */
    static void askName(@NonNull Context c, @NonNull String title, @NonNull String current,
                        @NonNull String action, @NonNull Consumer<String> onName) {
        BottomSheetDialog d = sheet(c);
        EditText field = new EditText(c);
        Runnable go = () -> {
            String name = field.getText().toString().trim();
            d.dismiss();
            if (!name.isEmpty()) onName.accept(name);
        };
        TextView button = SheetKit.pillButton(c, action, true, v -> go.run());
        LinearLayout root = column(c, d, title, button);
        field.setText(current);
        field.setSelectAllOnFocus(true);
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        field.setImeOptions(EditorInfo.IME_ACTION_DONE);
        field.setHint("Look name");
        field.setHintTextColor(Studio.INK_FAINT);
        field.setTextColor(Studio.INK);
        field.setTextSize(TypedValue.COMPLEX_UNIT_SP, SheetKit.ROW_SP);
        Type.body(field, Type.REGULAR);
        field.setBackground(SheetKit.pillBackground(c, Studio.RAISED));
        field.setPadding(SheetKit.dp(c, 16), SheetKit.dp(c, 12), SheetKit.dp(c, 16), SheetKit.dp(c, 12));
        SheetKit.label(field, "Look name");
        int inset = SheetKit.dp(c, SheetKit.ROW_INSET_DP);
        LinearLayout.LayoutParams fLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        fLp.setMargins(inset, SheetKit.dp(c, 4), inset, 0);
        root.addView(field, fLp);
        field.setOnEditorActionListener((v, a, ev) -> {
            if (a == EditorInfo.IME_ACTION_DONE) {
                go.run();
                return true;
            }
            return false;
        });
        d.setContentView(SheetKit.fitNavBar(root));
        d.show();
        field.requestFocus();
        field.post(() -> {
            InputMethodManager imm = (InputMethodManager) c.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT);
        });
    }

    /**
     * Which sheet a Sprite layer plays: the built-in Joy record first, then every Sprite Lab sheet
     * on the phone with where it lives. {@code onPick} gets null for the built-in one.
     */
    static void chooseSheet(@NonNull Context c, @NonNull java.util.List<SheetShelf.Entry> sheets,
                            @Nullable String currentId, @NonNull Consumer<SheetShelf.Entry> onPick) {
        BottomSheetDialog d = sheet(c);
        LinearLayout root = column(c, d, "Sprite sheet", null);
        LinearLayout list = new LinearLayout(c);
        list.setOrientation(LinearLayout.VERTICAL);
        list.addView(SheetKit.row(c, "album", "Joy record (built in)", currentId == null, SheetKit.Trail.CHECK, v -> {
            d.dismiss();
            onPick.accept(null);
        }).view);
        for (SheetShelf.Entry e : sheets) {
            com.google.gson.JsonElement id = e.sheet.get("id");
            boolean on = currentId != null && id != null && currentId.equals(id.getAsString());
            SheetKit.Row r = SheetKit.row(c, "grid_view", e.name + "  ·  " + e.where, on, SheetKit.Trail.CHECK, v -> {
                d.dismiss();
                onPick.accept(e);
            });
            SheetKit.label(r.view, "Play " + e.name + " (from " + e.where + ")");
            list.addView(r.view);
        }
        if (sheets.isEmpty()) {
            root.addView(SheetKit.subtitle(c, "No Sprite Lab sheets yet. Make one in Sprite Lab and it shows up here."));
        }
        android.widget.ScrollView scroll = new android.widget.ScrollView(c);
        scroll.addView(list);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                Math.min(SheetKit.dp(c, 64) * (sheets.size() + 1), SheetKit.dp(c, 420))));
        d.setContentView(SheetKit.fitNavBar(root));
        d.show();
    }

    /**
     * "Revert Sound City to default?" / what it replaces, with Keep and the red button at the far
     * end, so a thumb resting where it tapped lands on Keep.
     */
    static void confirm(@NonNull Context c, @NonNull String question, @NonNull String detail,
                        @NonNull String action, @NonNull Runnable onConfirm) {
        BottomSheetDialog d = sheet(c);
        LinearLayout root = column(c, d, question, null);
        root.addView(SheetKit.subtitle(c, detail));
        LinearLayout buttons = new LinearLayout(c);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        int inset = SheetKit.dp(c, SheetKit.ROW_INSET_DP);
        buttons.setPadding(inset, SheetKit.dp(c, 4), inset, 0);
        buttons.addView(SheetKit.pillButton(c, "Keep mine", false, v -> d.dismiss()));
        TextView red = SheetKit.pillButton(c, action, false, v -> {
            d.dismiss();
            onConfirm.run();
        });
        red.setBackground(SheetKit.pillBackground(c, Studio.alpha(Studio.DANGER, 0x29)));
        red.setTextColor(Studio.DANGER);
        Type.body(red, Type.SEMIBOLD);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginStart(SheetKit.dp(c, 8));
        buttons.addView(red, lp);
        root.addView(buttons);
        d.setContentView(SheetKit.fitNavBar(root));
        d.show();
    }
}
