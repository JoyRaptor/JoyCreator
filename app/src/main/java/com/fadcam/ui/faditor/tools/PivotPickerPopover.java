package com.fadcam.ui.faditor.tools;

import com.fadcam.ui.faditor.Studio;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.fadcam.R;

/**
 * SPEC B — the pivot picker: a small popover of nine dots (centre, the four edge midpoints,
 * the four corners); tapping one sets the object's rotation pivot and closes. Opened from the
 * Pivot button in the image/video drawer's transform tab.
 *
 * <p>Styled after {@link BlendPickerPopover} — same sheet colour, corner radius, elevation and
 * above-the-anchor placement — so the drawer's popovers read as one family. The dots render
 * through {@link PivotNineView}'s colours and snapping, but each of the nine is its own 44dp
 * clickable view: one tap is one pick reported by its OWN listener, with no touch-coordinate
 * inference (the failure mode the single-view draft had — see PivotNineView's note).</p>
 */
public final class PivotPickerPopover {

    private PivotPickerPopover() {}

    // The sheet itself — colour, radius, elevation, title — and the above-the-anchor placement
    // come from BlendPickerPopover, so the two popovers cannot drift apart. Screen ramp: this is
    // an opaque sheet, not the drawer's scrim.

    /** The nine anchors, row-major (top-left … bottom-right): their spoken names. */
    private static final int[] DOT_NAMES = {
            R.string.lane_a_pivot_top_left, R.string.lane_a_pivot_top,
            R.string.lane_a_pivot_top_right, R.string.lane_a_pivot_left,
            R.string.lane_a_pivot_centre, R.string.lane_a_pivot_right,
            R.string.lane_a_pivot_bottom_left, R.string.lane_a_pivot_bottom,
            R.string.lane_a_pivot_bottom_right};

    /** One pick. Normalized fractions in 0..1, the model's own unit. */
    public interface OnPivotPick {
        void onPivotPick(float normX, float normY);
    }

    /**
     * Open the picker hanging off {@code anchor} (the Pivot button). {@code currentX/Y} are the
     * model's normalized pivot fractions; {@code onPick} receives the picked anchor as
     * normalized fractions — one pick, then the popover is gone.
     */
    public static void show(@NonNull View anchor, float currentX, float currentY,
                            @NonNull OnPivotPick onPick) {
        Context ctx = anchor.getContext();
        DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        float d = dm.density;

        LinearLayout sheet = BlendPickerPopover.sheet(ctx, ctx.getText(R.string.lane_a_pivot_title));

        final PopupWindow pop = new PopupWindow(ctx);
        pop.setFocusable(true);
        pop.setOutsideTouchable(true);
        pop.setElevation(16 * d);

        int selCol = PivotNineView.snap(currentX);
        int selRow = PivotNineView.snap(currentY);
        int cell = Math.round(44 * d); // 44dp: the minimum tap target, ×3 = the popover
        LinearLayout grid = new LinearLayout(ctx);
        grid.setOrientation(LinearLayout.VERTICAL);
        for (int row = 0; row < 3; row++) {
            LinearLayout line = new LinearLayout(ctx);
            line.setOrientation(LinearLayout.HORIZONTAL);
            for (int col = 0; col < 3; col++) {
                final int fCol = col, fRow = row;
                DotView dot = new DotView(ctx, fCol, fRow,
                        fRow == selRow && fCol == selCol);
                dot.setOnClickListener(v -> {
                    pop.dismiss();
                    onPick.onPivotPick(fCol / 2f, fRow / 2f);
                });
                line.addView(dot, new LinearLayout.LayoutParams(cell, cell));
            }
            grid.addView(line);
        }
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        gp.gravity = Gravity.CENTER_HORIZONTAL;
        sheet.addView(grid, gp);

        // IMMERSIVE: a FOCUSABLE PopupWindow gets its OWN window, and a window without the
        // host's immersive flags makes Android reveal the status and navigation bars. In this
        // editor that RESIZED the content area, so the preview surface and the handles overlay
        // were briefly measured against different rects -- JoyRaptor 2026-09-05: "just me clicking
        // the rotation pivot menu causes it to desync a little bit... the image is offset a
        // couple of pixels" BEFORE any dot was picked, with the pivot still at centre and every
        // line of pivot arithmetic skipped. Copying the host's flags onto this window keeps the
        // bars hidden, so nothing moves. (The activity also now carries the LAYOUT_* stability
        // flags, which make the layout immune even if a bar does appear -- belt and braces,
        // because either one alone leaves a path open.)
        sheet.setSystemUiVisibility(anchor.getRootView().getSystemUiVisibility());
        pop.setContentView(sheet);
        pop.setWidth(LinearLayout.LayoutParams.WRAP_CONTENT);
        pop.setHeight(LinearLayout.LayoutParams.WRAP_CONTENT);
        pop.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0x00000000));

        // Above the anchor, same reasoning as BlendPickerPopover: these buttons sit in a drawer
        // near the bottom of the screen, so dropping down would open off the edge.
        sheet.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        BlendPickerPopover.showAbove(pop, anchor, sheet.getMeasuredHeight(), Gravity.CENTER);
    }

    /** One 44dp dot cell. Colours come from {@link PivotNineView} so the picker and the
     * button icon cannot drift apart on what "selected" looks like. */
    private static final class DotView extends View {
        private final int col;
        private final int row;
        private final boolean selected;

        DotView(@NonNull Context ctx, int col, int row, boolean selected) {
            super(ctx);
            this.col = col;
            this.row = row;
            this.selected = selected;
            // "Pivot: top left", not "Pivot 0,0" — and a hover tooltip for the stylus.
            com.fadcam.ui.faditor.tools.ObjectDrawer.Kit.describe(this,
                    ctx.getString(DOT_NAMES[row * 3 + col]));
        }

        @Override
        protected void onDraw(@NonNull Canvas c) {
            float w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;
            // Same proportions as the icon grid: dot radius = 1/10 of the grid side.
            float r = Math.min(w, h) / 4.4f;
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setColor(selected ? PivotNineView.DOT_SELECTED : PivotNineView.DOT);
            c.drawCircle(w / 2f, h / 2f, r, p);
        }
    }
}
