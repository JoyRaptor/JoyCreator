package cc.joycreator.textlab;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;

import java.util.Set;
import java.util.function.BiConsumer;

/**
 * What a look is built from (VisLab's lab model: a look is parts you can add, take away and mix): every piece TextLab
 * has, each with its icon; the ones this look has are solid cyan. A tap adds or takes away a piece (one undo step
 * each); its sliders appear as a tab. Some pieces are made for each other (Front with Burn, Sand or Vine); any
 * mix draws, though not every mix makes sense.
 */
final class TlPiecesDialog {

    private static final int PANEL = 0xFF141417, CONTROL = 0xFF1F1F26, INK = 0xFFD4D4D8, DIM = 0xFF8A8A96,
            CYAN = 0xFF22D3EE, GROUND = 0xFF0B0B0C;

    private TlPiecesDialog() {}

    /** {@code toggle(feature, on)} is called for each tap. */
    static void show(@NonNull Activity a, @NonNull TlParts parts, @NonNull Set<String> has,
                     @NonNull BiConsumer<String, Boolean> toggle) {
        LinearLayout body = new LinearLayout(a);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(a, 18), dp(a, 16), dp(a, 18), dp(a, 14));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(PANEL);
        bg.setCornerRadius(dp(a, 16));
        body.setBackground(bg);
        TextView t = new TextView(a);
        t.setText(a.getString(com.fadcam.R.string.textlab_pieces_title));
        t.setTextColor(INK);
        t.setTextSize(18);
        body.addView(t);
        TextView sub = new TextView(a);
        sub.setText(a.getString(com.fadcam.R.string.textlab_pieces_sub));
        sub.setTextColor(DIM);
        sub.setTextSize(12);
        sub.setPadding(0, dp(a, 4), 0, dp(a, 10));
        body.addView(sub);

        GridLayout grid = new GridLayout(a);
        grid.setColumnCount(3);
        for (TlParts.Piece p : parts.pieces) {
            TextView cell = new TextView(a);
            boolean[] on = {has.contains(p.feature)};
            Runnable paint = () -> {
                GradientDrawable g = new GradientDrawable();
                g.setColor(on[0] ? CYAN : CONTROL);
                g.setCornerRadius(dp(a, 12));
                cell.setBackground(g);
                cell.setTextColor(on[0] ? GROUND : INK);
                Drawable d = a.getDrawable(TlIcons.forTab(p.slot));
                if (d != null) {
                    d = d.mutate();
                    d.setTint(on[0] ? GROUND : INK);
                    d.setBounds(0, 0, dp(a, 22), dp(a, 22));
                    cell.setCompoundDrawablesRelative(null, d, null, null);
                }
            };
            cell.setText(p.slot);
            cell.setTextSize(12);
            cell.setGravity(Gravity.CENTER);
            cell.setCompoundDrawablePadding(dp(a, 4));
            cell.setPadding(dp(a, 4), dp(a, 10), dp(a, 4), dp(a, 8));
            String tip = a.getString(on[0] ? com.fadcam.R.string.textlab_piece_tip_remove
                    : com.fadcam.R.string.textlab_piece_tip_add, p.slot);
            cell.setContentDescription(tip);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) cell.setTooltipText(tip);
            paint.run();
            cell.setOnClickListener(v -> {
                on[0] = !on[0];
                paint.run();
                toggle.accept(p.feature, on[0]);
            });
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = dp(a, 88);
            lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            lp.setMargins(dp(a, 3), dp(a, 3), dp(a, 3), dp(a, 3));
            grid.addView(cell, lp);
        }
        ScrollView sc = new ScrollView(a);
        sc.addView(grid);
        body.addView(sc, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(a, 380)));

        Dialog d = new Dialog(a);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        TextView close = new TextView(a);
        close.setText(a.getString(com.fadcam.R.string.textlab_done));
        close.setTextColor(GROUND);
        close.setGravity(Gravity.CENTER);
        GradientDrawable cg = new GradientDrawable();
        cg.setColor(CYAN);
        cg.setCornerRadius(999);
        close.setBackground(cg);
        close.setContentDescription(a.getString(com.fadcam.R.string.textlab_pieces_close));
        close.setOnClickListener(v -> d.dismiss());
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(dp(a, 96), dp(a, 40));
        clp.gravity = Gravity.END;
        clp.topMargin = dp(a, 10);
        body.addView(close, clp);
        d.setContentView(body);
        if (d.getWindow() != null) {
            d.getWindow().setBackgroundDrawable(new ColorDrawable(0));
            d.getWindow().setLayout(dp(a, 320), ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        d.show();
    }

    private static int dp(Activity a, float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, a.getResources().getDisplayMetrics());
    }
}
