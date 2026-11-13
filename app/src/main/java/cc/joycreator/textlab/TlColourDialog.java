package cc.joycreator.textlab;

import android.app.Activity;
import android.app.Dialog;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;

import java.util.Locale;
import java.util.function.Consumer;

/**
 * A colour for one of a look's colour settings: hue, how strong, how bright, or a hex code. A colour written
 * "#rrggbb*gain" (light brighter than white) keeps its gain. {@code live} is called as it changes (the picture
 * follows), {@code done} once with the final value, or with the starting value on Cancel.
 */
final class TlColourDialog {

    private static final int PANEL = 0xFF141417, CONTROL = 0xFF1F1F26, INK = 0xFFD4D4D8, DIM = 0xFF8A8A96,
            CYAN = 0xFF22D3EE, GROUND = 0xFF0B0B0C;

    private TlColourDialog() {}

    static void show(@NonNull Activity a, @NonNull String title, @NonNull String value,
                     @NonNull Consumer<String> live, @NonNull Consumer<String> done) {
        String[] parts = value.split("\\*", 2);
        final String gain = parts.length > 1 ? "*" + parts[1] : "";
        int start;
        try {
            start = Color.parseColor(parts[0].trim());
        } catch (IllegalArgumentException e) {
            start = Color.WHITE;
        }
        float[] hsv = new float[3];
        Color.colorToHSV(start, hsv);

        LinearLayout body = new LinearLayout(a);
        body.setOrientation(LinearLayout.VERTICAL);
        int p = dp(a, 18);
        body.setPadding(p, p, p, dp(a, 12));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(PANEL);
        bg.setCornerRadius(dp(a, 16));
        body.setBackground(bg);

        TextView t = new TextView(a);
        t.setText(title);
        t.setTextColor(INK);
        t.setTextSize(16);
        body.addView(t);

        TextView swatch = new TextView(a);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(a, 44));
        sl.topMargin = dp(a, 12);
        body.addView(swatch, sl);

        final boolean[] quiet = {false};
        final String[] now = {value};
        EditText hex = new EditText(a);
        SeekBar[] bars = new SeekBar[3];
        Runnable paint = () -> {
            int col = Color.HSVToColor(hsv);
            GradientDrawable g = new GradientDrawable();
            g.setColor(col);
            g.setCornerRadius(dp(a, 10));
            swatch.setBackground(g);
            now[0] = String.format(Locale.ROOT, "#%06x", col & 0xFFFFFF) + gain;
            if (!quiet[0]) {
                quiet[0] = true;
                hex.setText(String.format(Locale.ROOT, "%06x", col & 0xFFFFFF));
                quiet[0] = false;
            }
            live.accept(now[0]);
        };
        String[] names = {"Hue", "Strength", "Brightness"};
        float[] max = {360, 1, 1};
        for (int i = 0; i < 3; i++) {
            final int at = i;
            TextView l = new TextView(a);
            l.setText(names[i]);
            l.setTextColor(DIM);
            l.setTextSize(12);
            l.setPadding(0, dp(a, 10), 0, 0);
            body.addView(l);
            SeekBar s = new SeekBar(a);
            s.setMax(1000);
            s.setProgress(Math.round(hsv[i] / max[i] * 1000));
            s.setProgressTintList(ColorStateList.valueOf(CYAN));
            s.setThumbTintList(ColorStateList.valueOf(CYAN));
            s.setContentDescription(names[i]);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) s.setTooltipText(names[i]);
            s.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar sb, int v, boolean user) {
                    if (!user) return;
                    hsv[at] = v / 1000f * max[at];
                    paint.run();
                }

                @Override public void onStartTrackingTouch(SeekBar sb) {}

                @Override public void onStopTrackingTouch(SeekBar sb) {}
            });
            bars[i] = s;
            body.addView(s);
        }

        LinearLayout hexRow = new LinearLayout(a);
        hexRow.setGravity(Gravity.CENTER_VERTICAL);
        hexRow.setPadding(0, dp(a, 10), 0, 0);
        TextView hash = new TextView(a);
        hash.setText("#");
        hash.setTextColor(DIM);
        hexRow.addView(hash);
        hex.setTextColor(INK);
        hex.setSingleLine(true);
        hex.setTextSize(14);
        hex.setContentDescription("Colour code");
        hexRow.addView(hex, new LinearLayout.LayoutParams(dp(a, 120), ViewGroup.LayoutParams.WRAP_CONTENT));
        body.addView(hexRow);
        hex.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int af) {}

            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {}

            @Override
            public void afterTextChanged(Editable s) {
                if (quiet[0] || s.length() != 6) return;
                try {
                    Color.colorToHSV(Color.parseColor("#" + s), hsv);
                } catch (IllegalArgumentException e) {
                    return;
                }
                for (int i = 0; i < 3; i++) bars[i].setProgress(Math.round(hsv[i] / max[i] * 1000));
                quiet[0] = true;
                paint.run();
                quiet[0] = false;
            }
        });

        Dialog d = new Dialog(a);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout buttons = new LinearLayout(a);
        buttons.setGravity(Gravity.END);
        buttons.setPadding(0, dp(a, 12), 0, 0);
        final boolean[] kept = {false};
        buttons.addView(button(a, "Cancel", CONTROL, INK, v -> d.dismiss()));
        Button ok = button(a, "Done", CYAN, GROUND, v -> {
            kept[0] = true;
            d.dismiss();
        });
        LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(a, 40));
        olp.setMarginStart(dp(a, 8));
        buttons.addView(ok, olp);
        body.addView(buttons);
        d.setContentView(body);
        if (d.getWindow() != null) {
            d.getWindow().setBackgroundDrawable(new ColorDrawable(0));
            d.getWindow().setLayout(dp(a, 320), ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        d.setOnDismissListener(x -> {
            if (kept[0]) done.accept(now[0]);
            else {
                live.accept(value);
                done.accept(value);
            }
        });
        quiet[0] = false;
        paint.run();
        d.show();
    }

    private static Button button(Activity a, String s, int fill, int ink, android.view.View.OnClickListener click) {
        Button b = new Button(a);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextColor(ink);
        b.setTextSize(13);
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(999);
        b.setBackground(g);
        b.setMinWidth(0);
        b.setMinimumWidth(dp(a, 72));
        b.setMinHeight(0);
        b.setMinimumHeight(dp(a, 40));
        b.setPadding(dp(a, 14), 0, dp(a, 14), 0);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) b.setTooltipText(s);
        b.setOnClickListener(click);
        return b;
    }

    private static int dp(Activity a, float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, a.getResources().getDisplayMetrics());
    }
}
