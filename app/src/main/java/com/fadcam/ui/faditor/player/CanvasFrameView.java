package com.fadcam.ui.faditor.player;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Decorative preview backdrop that makes the project canvas unambiguous.
 *
 * <p>Sits behind all preview content (player / image / slide) and draws:</p>
 * <ul>
 *   <li>a diagonal hatch (alternating dark-gray / darker-gray thick stripes) over
 *       the whole preview area — i.e. everything OUTSIDE the canvas, so the user
 *       can see where the project edges are and what will be cropped;</li>
 *   <li>a solid black rectangle for the canvas itself, so unused canvas area reads
 *       as black (matching the exported frame).</li>
 * </ul>
 *
 * <p>The content views are sized to the same canvas rect on top of this, so the
 * net effect is: content in the canvas, black for unfilled canvas, hatch around.</p>
 */
public class CanvasFrameView extends View {

    private static final int HATCH_DARK = 0x8C33333C;    // stripe (~55% opacity)
    private static final int HATCH_DARKER = 0xFF16161B;  // background
    private static final int CANVAS_BLACK = 0xFF000000;

    private final Paint stripePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint canvasPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    /** Canvas rect size (centered). When <= 0 the whole view is treated as canvas. */
    private int canvasW = -1;
    private int canvasH = -1;

    private float stripePeriodPx;

    public CanvasFrameView(@NonNull Context context) {
        super(context);
        init();
    }

    public CanvasFrameView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        float density = getResources().getDisplayMetrics().density;
        stripePeriodPx = 22f * density; // thick stripes
        stripePaint.setStyle(Paint.Style.STROKE);
        stripePaint.setColor(HATCH_DARK);
        stripePaint.setStrokeWidth(stripePeriodPx / 2f);
        canvasPaint.setStyle(Paint.Style.FILL);
        canvasPaint.setColor(CANVAS_BLACK);
    }

    /**
     * Set the canvas rect (centered) in pixels. Pass dimensions >= the view size
     * (or call {@link #setFill()}) when the canvas fills the whole preview.
     */
    public void setCanvasRect(int w, int h) {
        if (w != canvasW || h != canvasH) {
            canvasW = w;
            canvasH = h;
            invalidate();
        }
    }

    /** Canvas fills the whole preview (no hatch). */
    public void setFill() {
        setCanvasRect(-1, -1);
    }

    private float canvasDx = 0f, canvasDy = 0f;

    /**
     * Offset the black canvas rect without moving this view.
     *
     * <p>The drawer reflows slide the PICTURE aside so more of it clears an open drawer. This
     * view must not slide with it: it is the workspace backdrop, and moving it drags the hatch
     * off one edge and leaves bare black there — the exact thing the hatch exists to prevent.
     * But the black rect underneath the picture DOES have to follow, or the picture sits on
     * hatching while a black rectangle stays behind where it used to be.
     *
     * <p>So the view holds still and hatches the whole slot, and only the black rect moves.</p>
     */
    public void setCanvasOffset(float dx, float dy) {
        if (dx != canvasDx || dy != canvasDy) {
            canvasDx = dx;
            canvasDy = dy;
            invalidate();
        }
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        // Hatch background everywhere.
        canvas.drawColor(HATCH_DARKER);
        float period = stripePeriodPx;
        // 45° stripes: lines from the top/left running down-right, sweeping across.
        for (float x = -h; x < w + h; x += period) {
            canvas.drawLine(x, 0, x + h, h, stripePaint);
        }

        // Black canvas rect on top (so the canvas area is clean black).
        int cw = canvasW;
        int ch = canvasH;
        if (cw <= 0 || ch <= 0 || (cw >= w && ch >= h)) {
            // Fill mode: whole preview is canvas.
            canvas.drawColor(CANVAS_BLACK);
            return;
        }
        float left = (w - cw) / 2f + canvasDx;
        float top = (h - ch) / 2f + canvasDy;
        canvas.drawRect(left, top, left + cw, top + ch, canvasPaint);
    }
}
