package com.fadcam.ui.faditor.overlay;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * SPEC K — the pasteboard: everything outside the video canvas draws dimmed, so an
 * object (or its clipped part) hanging off-frame reads as "outside" instead of
 * vanishing into black letterbox without a trace.
 *
 * <p>Four 75%-black bands around the canvas rect (see {@link #onDraw}) — the
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
    private final RectF canvas = new RectF();
    private boolean haveCanvas;
    /** One line the first time we actually paint, so the phone can prove the bands are right. */
    private boolean drawLogged;

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
            com.fadcam.ui.faditor.transform.TransformDiag.log("pasteboard view="
                    + getWidth() + "x" + getHeight() + " canvas=" + r);
        }
    }

    /**
     * SPEC M §2 — FOUR BANDS, NOT A PUNCHED HOLE.
     *
     * <p>This used to fill itself and punch an EVEN_ODD hole at the canvas rect. On JoyRaptor's
     * Note 9 the hole did not land: a screenshot A/B at the identical playhead (00:00.000,
     * nothing under it, so no grade of any kind in play) measured the white canvas at 255
     * before the first {@link #setCanvasRect} push and 64 after it, with the out-of-canvas
     * hatch going 41 → 10 in the same step — one uniform 75% black over the WHOLE view, the
     * hole nowhere in it, while the rect the view had just logged
     * ({@code view=1080x1033 canvas=RectF(249,0,830,1033)}) was pixel-exact for the canvas.
     * That is JoyRaptor's "dark film over my entire preview".</p>
     *
     * <p>Rather than keep debugging why one clever call did not do what it says, the dim is
     * now four ordinary rectangles around the canvas — above, below, left, right. There is no
     * fill rule to get wrong and no way for it to cover the picture: the canvas band is never
     * drawn because it is never one of the four. Slower by three draw calls a frame, which on
     * a view that repaints only when the rect changes is free.</p>
     *
     * <p><b>Fails safe, not dark.</b> A dim that hides the whole picture is always wrong, so a
     * rect that does not intersect us, or one whose visible part is a speck, draws NOTHING —
     * which is what a layout transient (screen wake, drawer resize, surface churn) looks like
     * on the way through.</p>
     */
    @Override
    protected void onDraw(@NonNull Canvas c) {
        if (!haveCanvas) return;
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        // The hole, clamped to our own bounds.
        float l = Math.max(0f, canvas.left);
        float t = Math.max(0f, canvas.top);
        float r = Math.min((float) w, canvas.right);
        float b = Math.min((float) h, canvas.bottom);
        // Off-view, degenerate, or a speck: draw nothing at all. Dimming everything is never
        // the right answer, and mid-layout is the one moment this rect can be nonsense.
        if (r - l <= 0f || b - t <= 0f) return;
        if ((r - l) * (b - t) < 0.02f * (float) w * (float) h) return;
        // Canvas fills us: nothing outside to dim.
        if (l <= 0.5f && t <= 0.5f && r >= w - 0.5f && b >= h - 0.5f) return;
        if (t > 0f) c.drawRect(0f, 0f, w, t, fill);
        if (b < h) c.drawRect(0f, b, w, h, fill);
        if (l > 0f) c.drawRect(0f, t, l, b, fill);
        if (r < w) c.drawRect(r, t, w, b, fill);
        if (!drawLogged) {
            drawLogged = true;
            com.fadcam.ui.faditor.transform.TransformDiag.log("pasteboard draw view="
                    + w + "x" + h + " hole=" + l + "," + t + "," + r + "," + b);
        }
    }
}
