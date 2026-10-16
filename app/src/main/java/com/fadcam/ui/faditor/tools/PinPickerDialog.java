package com.fadcam.ui.faditor.tools;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.fadcam.R;
import com.fadcam.ui.faditor.SheetKit;
import com.fadcam.ui.faditor.Studio;
import com.fadcam.ui.type.Type;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.function.IntConsumer;

/**
 * "FOLLOW WHAT?" for a picture or sprite with pins: a small map of the pins on top, a short
 * scrolling list under it. The old dialog was a plain list that grew past the screen and gave no
 * way to tell "Pin 4" from "Pin 5".
 *
 * <p>The map ({@link PinDiagramView}) and the list share one highlight: touch, hover or focus a
 * row and its dot lights up; touch or hover a dot and its row lights up. Choosing either calls
 * {@code onPick} with -1 for "the picture itself" or the 0-based pin. In landscape the map and
 * the list sit side by side so the dialog still fits the short screen.
 */
public final class PinPickerDialog {

    private PinPickerDialog() {}

    /**
     * @param restX  each pin's rest x in the picture's unit square (top-left origin)
     * @param restY  each pin's rest y
     * @param aspect the cell's width / height
     * @param onPick -1 = the picture itself, otherwise the 0-based pin
     */
    public static void show(@NonNull Context ctx, @NonNull float[] restX, @NonNull float[] restY,
                            float aspect, @NonNull IntConsumer onPick) {
        final int n = Math.min(restX.length, restY.length);
        final DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        final boolean wide = dm.heightPixels < dm.widthPixels;

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Studio.PANEL);
        bg.setCornerRadius(SheetKit.dp(ctx, SheetKit.SHEET_RADIUS_DP));
        final androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(ctx)
                .setBackground(bg)
                .create();

        // ── the map ──
        final PinDiagramView map = new PinDiagramView(ctx);
        float[] xs = new float[n], ys = new float[n];
        System.arraycopy(restX, 0, xs, 0, n);
        System.arraycopy(restY, 0, ys, 0, n);
        map.setPins(xs, ys, aspect);
        map.setContentDescription(ctx.getString(R.string.studio_pinpick_diagram));
        int mapPx = Math.min(SheetKit.dp(ctx, 200), Math.round(dm.heightPixels * (wide ? 0.42f : 0.5f)));

        // ── the list ──
        final SheetKit.Row[] rows = new SheetKit.Row[n + 1];
        final LinearLayout list = new LinearLayout(ctx);
        list.setOrientation(LinearLayout.VERTICAL);
        final int[] lit = {PinDiagramView.HIGHLIGHT_NONE};
        final Runnable[] paint = new Runnable[1];
        for (int i = -1; i < n; i++) {
            final int which = i;
            CharSequence name = i < 0 ? ctx.getString(R.string.studio_pinpick_whole)
                    : ctx.getString(R.string.studio_pinpick_pin_n, i + 1);
            SheetKit.Row r = SheetKit.row(ctx, null, name, false, SheetKit.Trail.NONE, v -> {
                dialog.dismiss();
                onPick.accept(which);
            });
            rows[i + 1] = r;
            r.view.setOnTouchListener((v, e) -> {
                int a = e.getActionMasked();
                if (a == MotionEvent.ACTION_DOWN) light(lit, paint, which);
                else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                    light(lit, paint, PinDiagramView.HIGHLIGHT_NONE);
                }
                return false;   // the click still fires
            });
            r.view.setOnHoverListener((v, e) -> {
                int a = e.getActionMasked();
                if (a == MotionEvent.ACTION_HOVER_ENTER || a == MotionEvent.ACTION_HOVER_MOVE) {
                    light(lit, paint, which);
                } else if (a == MotionEvent.ACTION_HOVER_EXIT) {
                    light(lit, paint, PinDiagramView.HIGHLIGHT_NONE);
                }
                return false;
            });
            r.view.setOnFocusChangeListener((v, has) ->
                    light(lit, paint, has ? which : PinDiagramView.HIGHLIGHT_NONE));
            list.addView(r.view);
        }
        paint[0] = () -> {
            int h = lit[0];
            map.setHighlight(h);
            map.setContentDescription(h >= 0
                    ? ctx.getString(R.string.studio_pinpick_dot_n, h + 1)
                    : ctx.getString(R.string.studio_pinpick_diagram));
            for (int k = 0; k < rows.length; k++) {
                boolean on = h == k - 1;
                SheetKit.paintRow(rows[k], on, false);
                rows[k].view.setBackground(on ? litRow(ctx) : SheetKit.rowBackground(ctx));
            }
        };
        map.setListener(new PinDiagramView.Listener() {
            @Override public void onPick(int pin) {
                dialog.dismiss();
                onPick.accept(pin);
            }

            @Override public void onHighlight(int h) { light(lit, paint, h); }
        });

        MaxHeightScrollView scroll = new MaxHeightScrollView(ctx);
        scroll.setMaxHeightFraction(wide ? 0.45f : 0.40f);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.addView(list);

        // ── the frame ──
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(SheetKit.dp(ctx, 8), SheetKit.dp(ctx, 16), SheetKit.dp(ctx, 8), SheetKit.dp(ctx, 12));

        TextView title = new TextView(ctx);
        title.setText(R.string.studio_pinpick_title);
        title.setTextColor(Studio.INK);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, SheetKit.TITLE_SP);
        Type.display(title, Type.EXTRA);
        title.setPadding(SheetKit.dp(ctx, 13), 0, SheetKit.dp(ctx, 13), SheetKit.dp(ctx, 2));
        root.addView(title);
        root.addView(SheetKit.subtitle(ctx, ctx.getString(R.string.studio_pinpick_hint)));

        if (wide) {
            LinearLayout both = new LinearLayout(ctx);
            both.setOrientation(LinearLayout.HORIZONTAL);
            both.setGravity(Gravity.CENTER_VERTICAL);
            both.addView(map, new LinearLayout.LayoutParams(mapPx, mapPx));
            both.addView(scroll, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            root.addView(both);
        } else {
            LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(mapPx, mapPx);
            mlp.gravity = Gravity.CENTER_HORIZONTAL;
            mlp.bottomMargin = SheetKit.dp(ctx, 6);
            root.addView(map, mlp);
            root.addView(scroll, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        }

        LinearLayout buttons = new LinearLayout(ctx);
        buttons.setGravity(Gravity.END);
        buttons.setPadding(SheetKit.dp(ctx, 8), SheetKit.dp(ctx, 10), SheetKit.dp(ctx, 8), 0);
        buttons.addView(SheetKit.pillButton(ctx, ctx.getString(R.string.studio_pinpick_cancel), false,
                v -> dialog.dismiss()));
        root.addView(buttons);

        paint[0].run();
        dialog.setView(root);
        dialog.show();
    }

    private static void light(int[] lit, Runnable[] paint, int h) {
        if (lit[0] == h) return;
        lit[0] = h;
        if (paint[0] != null) paint[0].run();
    }

    /** A row that is lit: a pressed-grey fill with an ARMED ring. */
    private static GradientDrawable litRow(Context ctx) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(Studio.PRESSED);
        g.setCornerRadius(SheetKit.dp(ctx, SheetKit.ROW_RADIUS_DP));
        g.setStroke(Math.max(1, SheetKit.dp(ctx, 1.5f)), Studio.ARMED);
        return g;
    }
}
