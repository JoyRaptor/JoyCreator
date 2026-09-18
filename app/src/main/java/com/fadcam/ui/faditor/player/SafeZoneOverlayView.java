package com.fadcam.ui.faditor.player;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Preview-only 9:16 safe-zone guide overlay (road_map "small never-built features").
 *
 * <p>Draws a dashed rectangle marking the standard social-media safe area for
 * vertical (9:16) video — the region that stays clear of platform chrome
 * (profile/caption/like-button rails on TikTok/Reels/Shorts) — plus thin tick
 * marks at the horizontal thirds for rough center/edge alignment. This is a
 * pure visual GUIDE drawn OVER the live preview; it never touches the project
 * model and has zero effect on export (mirrors the crop rule-of-thirds grid's
 * preview-only contract).</p>
 *
 * <p>Only draws when the CURRENT canvas is 9:16 (portrait, matching within a
 * small tolerance) — for other aspect ratios the toggle is a no-op so the
 * guide never misleads on a landscape/square canvas. {@link #setEnabled} /
 * {@link #setCanvasAspect} both trigger a redraw; the view otherwise never
 * intercepts touches (see the XML's clickable="false").</p>
 */
public class SafeZoneOverlayView extends View {

    // Standard vertical-video safe margins (fraction of each edge kept clear of
    // platform chrome). Top: profile/status row. Bottom: caption/like/share rail
    // (the biggest reserved zone on TikTok/Reels/Shorts). Sides: symmetric margin
    // so text/logos don't get clipped/rounded off on any device's screen.
    private static final float MARGIN_TOP = 0.12f;
    private static final float MARGIN_BOTTOM = 0.20f;
    private static final float MARGIN_SIDE = 0.06f;

    /** Portrait aspect (w/h) tolerance — only draws near true 9:16. */
    private static final float PORTRAIT_ASPECT = 9f / 16f;
    private static final float ASPECT_TOLERANCE = 0.03f;

    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tickPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private boolean guideEnabled = false;
    /** Current canvas aspect (width/height); <= 0 means "unknown, don't draw". */
    private float canvasAspect = -1f;

    public SafeZoneOverlayView(@NonNull Context context) {
        super(context);
        init();
    }

    public SafeZoneOverlayView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        float density = getResources().getDisplayMetrics().density;
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setColor(0xFFFBBF24); // amber-yellow, reads clearly over any footage
        linePaint.setStrokeWidth(1.5f * density);
        linePaint.setPathEffect(new android.graphics.DashPathEffect(
                new float[]{8f * density, 6f * density}, 0f));

        tickPaint.setStyle(Paint.Style.STROKE);
        tickPaint.setColor(0x55FBBF24);
        tickPaint.setStrokeWidth(1f * density);
    }

    /** Enable/disable the guide (does not itself change visibility; caller sets GONE/VISIBLE). */
    public void setGuideEnabled(boolean enabled) {
        if (guideEnabled != enabled) {
            guideEnabled = enabled;
            invalidate();
        }
    }

    /** Update the current canvas aspect (width/height) so the guide only draws on ~9:16. */
    public void setCanvasAspect(float aspect) {
        if (Math.abs(canvasAspect - aspect) > 0.0001f) {
            canvasAspect = aspect;
            invalidate();
        }
    }

    private boolean isPortrait916() {
        return canvasAspect > 0 && Math.abs(canvasAspect - PORTRAIT_ASPECT) <= ASPECT_TOLERANCE;
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);
        if (!guideEnabled || !isPortrait916()) return;

        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        float left = w * MARGIN_SIDE;
        float right = w * (1f - MARGIN_SIDE);
        float top = h * MARGIN_TOP;
        float bottom = h * (1f - MARGIN_BOTTOM);

        RectF safe = new RectF(left, top, right, bottom);
        canvas.drawRect(safe, linePaint);

        // Thirds ticks inside the safe rect (short marks, not full lines — keeps the
        // guide readable without turning into a second grid).
        float tickLen = Math.min(w, h) * 0.02f;
        float thirdX1 = left + safe.width() / 3f;
        float thirdX2 = left + 2f * safe.width() / 3f;
        float thirdY1 = top + safe.height() / 3f;
        float thirdY2 = top + 2f * safe.height() / 3f;
        canvas.drawLine(thirdX1, top, thirdX1, top + tickLen, tickPaint);
        canvas.drawLine(thirdX1, bottom - tickLen, thirdX1, bottom, tickPaint);
        canvas.drawLine(thirdX2, top, thirdX2, top + tickLen, tickPaint);
        canvas.drawLine(thirdX2, bottom - tickLen, thirdX2, bottom, tickPaint);
        canvas.drawLine(left, thirdY1, left + tickLen, thirdY1, tickPaint);
        canvas.drawLine(right - tickLen, thirdY1, right, thirdY1, tickPaint);
        canvas.drawLine(left, thirdY2, left + tickLen, thirdY2, tickPaint);
        canvas.drawLine(right - tickLen, thirdY2, right, thirdY2, tickPaint);
    }
}
