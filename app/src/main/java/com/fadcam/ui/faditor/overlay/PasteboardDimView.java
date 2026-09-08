package com.fadcam.ui.faditor.overlay;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * SPEC K — the pasteboard: everything outside the video canvas draws dimmed, so an
 * object (or its clipped part) hanging off-frame reads as "outside" instead of
 * vanishing into black letterbox without a trace.
 *
 * <p>One full-container rect with a canvas-shaped hole (even-odd), 75% black — the
 * content outside therefore shows at roughly 25%, whatever draws it (Canvas views,
 * GL planes, captions). Preview-only chrome: the export never reads this view, and
 * it sits below the handles/transform surfaces (plain child order, no elevation),
 * so chrome stays bright. Not clickable, not focusable — touches fall straight
 * through to whatever owns them.
 *
 * <p>The canvas rect is PUSHED in (the activity already computes it for every layout
 * and tick); compare-and-set means a steady state costs one rect comparison and no
 * redraw.
 */
public class PasteboardDimView extends android.view.View {

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF canvas = new RectF();
    private boolean haveCanvas;

    public PasteboardDimView(@NonNull Context context) {
        super(context);
        setClickable(false);
        setFocusable(false);
        setFocusableInTouchMode(false);
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(0xBF000000);
    }

    /**
     * Point the hole at the video canvas, in this view's own pixels. Null (or an
     * empty rect) hides the dim entirely.
     */
    public void setCanvasRect(@Nullable RectF r) {
        if (r == null || r.width() <= 0f || r.height() <= 0f) {
            if (haveCanvas) {
                haveCanvas = false;
                invalidate();
            }
            return;
        }
        if (!haveCanvas || canvas.left != r.left || canvas.top != r.top
                || canvas.right != r.right || canvas.bottom != r.bottom) {
            canvas.set(r);
            haveCanvas = true;
            invalidate();
        }
    }

    @Override
    protected void onDraw(@NonNull Canvas c) {
        if (!haveCanvas) return;
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        // Canvas fills us: nothing outside to dim.
        if (canvas.left <= 0f && canvas.top <= 0f && canvas.right >= w && canvas.bottom >= h) return;
        path.reset();
        path.addRect(0f, 0f, w, h, Path.Direction.CW);
        path.addRect(canvas.left, canvas.top, canvas.right, canvas.bottom, Path.Direction.CW);
        path.setFillType(Path.FillType.EVEN_ODD);
        c.drawPath(path, fill);
    }
}
