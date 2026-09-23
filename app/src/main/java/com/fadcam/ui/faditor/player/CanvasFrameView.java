package com.fadcam.ui.faditor.player;

import com.fadcam.ui.faditor.Studio;

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
 *   <li>the Joy Creator film-graffiti art over the whole preview area (it replaced a
 *       diagonal hatch) — i.e. everything OUTSIDE the canvas, so the user can see where
 *       the project edges are and what will be cropped;</li>
 *   <li>a solid black rectangle for the canvas itself, so unused canvas area reads
 *       as black (matching the exported frame).</li>
 * </ul>
 *
 * <p>The content views are sized to the same canvas rect on top of this, so the
 * net effect is: content in the canvas, black for unfilled canvas, the art around.</p>
 */
public class CanvasFrameView extends View {

    private static final int CANVAS_BLACK = Studio.GROUND;

    private final Paint canvasPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    /**
     * THE WORKSPACE BACKDROP: JoyRaptor's Joy Creator film-graffiti art (2026-09-23), replacing
     * the diagonal hatch. Scaled to the preview's WIDTH and centred; on a slot taller than the
     * scaled art it scales up instead, so no edge is ever bare. Not tileable by design: it is
     * drawn once, at the size of a phone.
     */
    @Nullable private android.graphics.Bitmap backdrop;
    private final Paint backdropPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final android.graphics.Matrix backdropMatrix = new android.graphics.Matrix();

    /** Canvas rect size (centered). When <= 0 the whole view is treated as canvas. */
    private int canvasW = -1;
    private int canvasH = -1;

    public CanvasFrameView(@NonNull Context context) {
        super(context);
        init();
    }

    public CanvasFrameView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        // RGB_565: the art is an opaque JPEG, so half the memory and no visible difference.
        android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
        o.inPreferredConfig = android.graphics.Bitmap.Config.RGB_565;
        backdrop = android.graphics.BitmapFactory.decodeResource(getResources(),
                com.fadcam.R.drawable.studio_backdrop_graffiti, o);
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

        // The backdrop everywhere; the canvas rect goes on top of it.
        canvas.drawColor(CANVAS_BLACK);
        android.graphics.Bitmap art = backdrop;
        if (art != null && art.getWidth() > 0 && art.getHeight() > 0) {
            float scale = Math.max(w / (float) art.getWidth(), h / (float) art.getHeight());
            backdropMatrix.setScale(scale, scale);
            backdropMatrix.postTranslate((w - art.getWidth() * scale) / 2f,
                    (h - art.getHeight() * scale) / 2f);
            canvas.drawBitmap(art, backdropMatrix, backdropPaint);
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
