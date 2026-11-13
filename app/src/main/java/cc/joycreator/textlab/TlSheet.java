package cc.joycreator.textlab;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The room's small dialogs in the house style: a list of choices (a menu), a question with two answers, and a
 * name to type. Old black, zinc greys, the main answer solid cyan; every row has a hover label.
 */
final class TlSheet {

    private static final int PANEL = 0xFF141417, CONTROL = 0xFF1F1F26, INK = 0xFFD4D4D8, DIM = 0xFF8A8A96,
            CYAN = 0xFF22D3EE, GROUND = 0xFF0B0B0C;

    /** One row of a menu. */
    static final class Row {
        final String text, tip;
        final boolean on;
        final Runnable run;

        Row(String text, String tip, boolean on, Runnable run) {
            this.text = text;
            this.tip = tip;
            this.on = on;
            this.run = run;
        }
    }

    private TlSheet() {}

    static void menu(@NonNull Activity a, @NonNull String title, @NonNull List<Row> rows) {
        LinearLayout body = panel(a, title, null);
        Dialog d = dialog(a);
        LinearLayout list = new LinearLayout(a);
        list.setOrientation(LinearLayout.VERTICAL);
        for (Row r : rows) {
            TextView t = new TextView(a);
            t.setText(r.text);
            t.setTextSize(14);
            t.setTextColor(r.on ? GROUND : INK);
            t.setGravity(Gravity.CENTER_VERTICAL);
            t.setPadding(dp(a, 14), 0, dp(a, 14), 0);
            GradientDrawable g = new GradientDrawable();
            g.setColor(r.on ? CYAN : CONTROL);
            g.setCornerRadius(dp(a, 10));
            t.setBackground(g);
            t.setContentDescription(r.tip);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) t.setTooltipText(r.tip);
            t.setOnClickListener(v -> {
                d.dismiss();
                r.run.run();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(a, 44));
            lp.topMargin = dp(a, 6);
            list.addView(t, lp);
        }
        ScrollView sc = new ScrollView(a);
        sc.addView(list);
        body.addView(sc, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        show(a, d, body);
    }

    /** A question: {@code yes} on the main answer; Cancel does nothing. */
    static void ask(@NonNull Activity a, @NonNull String title, @NonNull String text, @NonNull String yesWord,
                    @NonNull Runnable yes, @Nullable String otherWord, @Nullable Runnable other) {
        LinearLayout body = panel(a, title, text);
        Dialog d = dialog(a);
        LinearLayout buttons = buttons(a);
        buttons.addView(button(a, a.getString(com.fadcam.R.string.textlab_cancel), CONTROL, INK, d::dismiss));
        if (otherWord != null && other != null) {
            buttons.addView(button(a, otherWord, CONTROL, INK, () -> {
                d.dismiss();
                other.run();
            }));
        }
        buttons.addView(button(a, yesWord, CYAN, GROUND, () -> {
            d.dismiss();
            yes.run();
        }));
        body.addView(buttons);
        show(a, d, body);
    }

    /** A name to type, starting as {@code start}. */
    static void name(@NonNull Activity a, @NonNull String title, @NonNull String start, @NonNull String yesWord,
                     @NonNull Consumer<String> done) {
        LinearLayout body = panel(a, title, null);
        EditText e = new EditText(a);
        e.setSingleLine(true);
        e.setText(start);
        e.setSelectAllOnFocus(true);
        e.setTextColor(INK);
        e.setHintTextColor(DIM);
        body.addView(e);
        Dialog d = dialog(a);
        LinearLayout buttons = buttons(a);
        buttons.addView(button(a, a.getString(com.fadcam.R.string.textlab_cancel), CONTROL, INK, d::dismiss));
        buttons.addView(button(a, yesWord, CYAN, GROUND, () -> {
            String n = e.getText().toString().trim();
            if (n.isEmpty()) return;
            d.dismiss();
            done.accept(n);
        }));
        body.addView(buttons);
        show(a, d, body);
        e.requestFocus();
    }

    // ---- pieces -------------------------------------------------------------------------------------

    private static LinearLayout panel(Activity a, String title, @Nullable String text) {
        LinearLayout body = new LinearLayout(a);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(a, 18), dp(a, 16), dp(a, 18), dp(a, 14));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(PANEL);
        bg.setCornerRadius(dp(a, 16));
        body.setBackground(bg);
        TextView t = new TextView(a);
        t.setText(title);
        t.setTextColor(INK);
        t.setTextSize(17);
        body.addView(t);
        if (text != null) {
            TextView s = new TextView(a);
            s.setText(text);
            s.setTextColor(DIM);
            s.setTextSize(13);
            s.setPadding(0, dp(a, 8), 0, 0);
            body.addView(s);
        }
        return body;
    }

    private static Dialog dialog(Activity a) {
        Dialog d = new Dialog(a);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        return d;
    }

    private static void show(Activity a, Dialog d, LinearLayout body) {
        d.setContentView(body);
        if (d.getWindow() != null) {
            d.getWindow().setBackgroundDrawable(new ColorDrawable(0));
            d.getWindow().setLayout(dp(a, 320), ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        d.show();
    }

    private static LinearLayout buttons(Activity a) {
        LinearLayout b = new LinearLayout(a);
        b.setGravity(Gravity.END);
        b.setPadding(0, dp(a, 14), 0, 0);
        return b;
    }

    private static TextView button(Activity a, String s, int fill, int ink, Runnable click) {
        TextView b = new TextView(a);
        b.setText(s);
        b.setTextColor(ink);
        b.setTextSize(13);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(a, 14), 0, dp(a, 14), 0);
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(999);
        b.setBackground(g);
        b.setMinWidth(dp(a, 72));
        b.setContentDescription(s);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) b.setTooltipText(s);
        b.setOnClickListener(v -> click.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(a, 40));
        lp.setMarginStart(dp(a, 8));
        b.setLayoutParams(lp);
        return b;
    }

    static int dp(Activity a, float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, a.getResources().getDisplayMetrics());
    }

    @NonNull
    static List<Row> rows() {
        return new ArrayList<>();
    }
}
