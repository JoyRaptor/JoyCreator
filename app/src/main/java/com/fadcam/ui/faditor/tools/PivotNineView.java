package com.fadcam.ui.faditor.tools;

import com.fadcam.ui.faditor.Studio;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

import androidx.annotation.NonNull;

import com.fadcam.ui.faditor.model.TextOverlayItem;

/**
 * SPEC B — the 3×3 pivot ICON: nine dots, the selected one bright. This is the Pivot button's
 * face in the drawer, and it is deliberately a PURE drawing view — no touch handling, no
 * listener. An earlier draft made this same view the picker too and inferred the picked dot
 * from touch coordinates inside {@code performClick}; that is fragile (an accessibility click
 * arrives with no preceding touch, so the "last" coordinates are stale) and it is why the
 * picker is nine real 44dp cell views with their own click listeners instead — see
 * {@link PivotPickerPopover}. Two roles, one grid geometry, defined once here.
 *
 * <p>The nine anchors are the three positions per axis the model snaps to — centre, the four
 * edge midpoints, the four corners — so this grid and {@link TextOverlayItem}
 * {@code #setRotationPivot}'s snapping cannot disagree about what a dot means.</p>
 */
public final class PivotNineView extends View {

    /** Grey dots; the selected one white. Matches the drawer's muted icon tints. */
    static final int DOT = Studio.INK_FAINT;
    static final int DOT_SELECTED = Studio.INK;

    /** Row/column of the selection, 0..2, top-left origin — (1,1) is the centre. */
    private int selRow = 1;
    private int selCol = 1;

    public PivotNineView(@NonNull Context ctx) {
        super(ctx);
        setContentDescription("Rotation pivot");                           // TODO(strings)
    }

    /**
     * Point the grid at the model's current pivot. Fractional values are snapped to the same
     * three positions per axis the model stores, so the icon always shows a real anchor.
     */
    public void setSelection(float normX, float normY) {
        setSelection(snap(normX), snap(normY));
    }

    /** Overload taking the snapped 0..2 indices directly. */
    public void setSelection(int col, int row) {
        selCol = Math.max(0, Math.min(2, col));
        selRow = Math.max(0, Math.min(2, row));
        invalidate();
    }

    /** Model fraction → 0..2 grid index. Must match the model's own snapping. */
    static int snap(float v) {
        if (Float.isNaN(v) || Float.isInfinite(v)) return 1;
        return v < 0.25f ? 0 : (v > 0.75f ? 2 : 1);
    }

    @Override
    protected void onDraw(@NonNull Canvas c) {
        float w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        // The grid keeps a square aspect inside whatever box the parent gave it, centred —
        // the button gives it a 44dp touch square with a smaller icon square inside.
        float side = Math.min(w, h);
        float left = (w - side) / 2f, top = (h - side) / 2f;
        float stepX = side / 3f, stepY = side / 3f;
        float r = side / 10f;
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                p.setColor(row == selRow && col == selCol ? DOT_SELECTED : DOT);
                c.drawCircle(left + stepX * (col + 0.5f),
                        top + stepY * (row + 0.5f), r, p);
            }
        }
    }
}
