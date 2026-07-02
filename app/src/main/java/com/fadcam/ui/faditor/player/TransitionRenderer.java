package com.fadcam.ui.faditor.player;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RectF;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.ui.faditor.model.Transition;

/**
 * Composites a FULL two-frame transition (outgoing {@code a} → incoming {@code b}) at a given progress
 * onto a canvas. One renderer shared by the animated drawer preview cards and the live scrubbable
 * preview, so what you see in the little card is what you get in playback/export (ballpark).
 *
 * <p>Stateless static methods; the caller owns the bitmaps and canvas.</p>
 */
public final class TransitionRenderer {

    private TransitionRenderer() {}

    /** Compose {@code a}→{@code b} for {@code transition} at {@code progress} (0..1) filling [0,0,w,h]. */
    public static void compose(@NonNull Canvas canvas, @Nullable Bitmap a, @Nullable Bitmap b,
                               @NonNull Transition transition, float progress, int w, int h) {
        progress = clamp(progress);
        RectF rect = new RectF(0, 0, w, h);
        Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);

        switch (transition.type) {
            case FADE_OUT_TO_BLACK:
            case FADE_IN_FROM_BLACK:
                dip(canvas, a, b, rect, paint, progress, Color.BLACK);
                return;
            case FADE_OUT_TO_WHITE:
            case FADE_IN_FROM_WHITE:
                dip(canvas, a, b, rect, paint, progress, Color.WHITE);
                return;
            case CROSS_DISSOLVE:
                drawFull(canvas, a, rect, paint, 255);
                drawFull(canvas, b, rect, paint, (int) (255f * progress));
                return;
            case GL_SHADER:
                // Tiny-card approximation of a GL shader: a zoom-dissolve. Real GL renders in the
                // live preview/export.
                zoomDissolve(canvas, a, b, rect, paint, progress);
                return;
            default:
                break;
        }

        // Effects where B is revealed/slid over a held A.
        drawFull(canvas, a, rect, paint, 255);
        if (b == null || b.isRecycled()) return;
        if (transition.isWipe()) {
            clipReveal(canvas, b, rect, paint, wipeRect(rect, transition.getDirection(), progress));
        } else if (transition.isPush()) {
            push(canvas, b, rect, paint, transition.getDirection(), progress);
        } else if (transition.type == Transition.Type.RADIAL) {
            radial(canvas, b, rect, paint, progress);
        } else if (transition.type == Transition.Type.LINEAR_MIRROR_WIPE) {
            mirrorWipe(canvas, b, rect, paint, progress);
        } else if (transition.isGlitch()) {
            if (progress > 0.5f) drawFull(canvas, b, rect, paint, 255);
            glitch(canvas, progress > 0.5f ? b : a, rect, paint, progress);
        } else if (transition.isTvChannel()) {
            if (progress > 0.45f) drawFull(canvas, b, rect, paint, 255);
            tvChannel(canvas, progress > 0.45f ? b : a, rect, paint, progress);
        } else {
            // Unknown → dissolve.
            drawFull(canvas, b, rect, paint, (int) (255f * progress));
        }
    }

    // ── building blocks ──────────────────────────────────────────────

    private static void drawFull(@NonNull Canvas canvas, @Nullable Bitmap bmp, @NonNull RectF rect,
                                 @NonNull Paint paint, int alpha) {
        if (bmp == null || bmp.isRecycled()) return;
        paint.setColorFilter(null);
        paint.setAlpha(alpha);
        canvas.drawBitmap(bmp, null, rect, paint);
    }

    /** Dip-to-color crossfade: A→color in the first half, color→B in the second. */
    private static void dip(@NonNull Canvas canvas, @Nullable Bitmap a, @Nullable Bitmap b,
                            @NonNull RectF rect, @NonNull Paint paint, float p, int color) {
        if (p < 0.5f) {
            drawFull(canvas, a, rect, paint, 255);
            veil(canvas, rect, color, p / 0.5f);
        } else {
            drawFull(canvas, b, rect, paint, 255);
            veil(canvas, rect, color, (1f - p) / 0.5f);
        }
    }

    private static void veil(@NonNull Canvas canvas, @NonNull RectF rect, int color, float alpha) {
        Paint p = new Paint();
        p.setColor(color);
        p.setAlpha((int) (255f * clamp(alpha)));
        canvas.drawRect(rect, p);
    }

    private static void zoomDissolve(@NonNull Canvas canvas, @Nullable Bitmap a, @Nullable Bitmap b,
                                     @NonNull RectF rect, @NonNull Paint paint, float p) {
        drawFull(canvas, a, rect, paint, 255);
        if (b == null || b.isRecycled()) return;
        float scale = 1f + 0.25f * (1f - p);
        float dw = rect.width() * scale, dh = rect.height() * scale;
        RectF zr = new RectF(rect.centerX() - dw / 2f, rect.centerY() - dh / 2f,
                rect.centerX() + dw / 2f, rect.centerY() + dh / 2f);
        paint.setColorFilter(null);
        paint.setAlpha((int) (255f * p));
        canvas.drawBitmap(b, null, zr, paint);
    }

    private static void clipReveal(@NonNull Canvas canvas, @NonNull Bitmap b, @NonNull RectF rect,
                                   @NonNull Paint paint, @NonNull RectF reveal) {
        if (reveal.isEmpty()) return;
        canvas.save();
        canvas.clipRect(reveal);
        drawFull(canvas, b, rect, paint, 255);
        canvas.restore();
    }

    private static RectF wipeRect(@NonNull RectF rect, @NonNull String dir, float p) {
        float w = rect.width(), h = rect.height();
        switch (dir) {
            case "left":  return new RectF(rect.left, rect.top, rect.left + w * p, rect.bottom);
            case "right": return new RectF(rect.right - w * p, rect.top, rect.right, rect.bottom);
            case "up":    return new RectF(rect.left, rect.top, rect.right, rect.top + h * p);
            case "down":
            default:      return new RectF(rect.left, rect.bottom - h * p, rect.right, rect.bottom);
        }
    }

    private static void push(@NonNull Canvas canvas, @NonNull Bitmap b, @NonNull RectF rect,
                             @NonNull Paint paint, @NonNull String dir, float p) {
        float w = rect.width(), h = rect.height();
        float dx = 0f, dy = 0f;
        switch (dir) {
            case "left":  dx = w * (1f - p); break;
            case "right": dx = -w * (1f - p); break;
            case "up":    dy = h * (1f - p); break;
            case "down":
            default:      dy = -h * (1f - p); break;
        }
        canvas.save();
        canvas.clipRect(rect);
        RectF shifted = new RectF(rect.left + dx, rect.top + dy, rect.right + dx, rect.bottom + dy);
        drawFull(canvas, b, shifted, paint, 255);
        canvas.restore();
    }

    private static void radial(@NonNull Canvas canvas, @NonNull Bitmap b, @NonNull RectF rect,
                               @NonNull Paint paint, float p) {
        float radius = (float) Math.hypot(rect.width(), rect.height()) * p;
        if (radius <= 0f) return;
        Path path = new Path();
        path.addCircle(rect.centerX(), rect.centerY(), radius, Path.Direction.CW);
        canvas.save();
        canvas.clipPath(path);
        drawFull(canvas, b, rect, paint, 255);
        canvas.restore();
    }

    private static void mirrorWipe(@NonNull Canvas canvas, @NonNull Bitmap b, @NonNull RectF rect,
                                   @NonNull Paint paint, float p) {
        // Full-width horizontal BAR that opens from the centre line outward (top + bottom symmetric),
        // not a centred square — the incoming clip appears as a growing horizontal band.
        float halfH = rect.height() * p / 2f;
        canvas.save();
        canvas.clipRect(rect.left, rect.centerY() - halfH, rect.right, rect.centerY() + halfH);
        drawFull(canvas, b, rect, paint, 255);
        canvas.restore();
    }

    private static void glitch(@NonNull Canvas canvas, @Nullable Bitmap src, @NonNull RectF rect,
                               @NonNull Paint paint, float p) {
        if (src == null || src.isRecycled()) return;
        float flicker = (float) ((Math.sin(p * Math.PI * 18d) + 1d) / 2d);
        float split = rect.width() * (0.015f + flicker * 0.06f);
        paint.setColorFilter(new PorterDuffColorFilter(Color.RED, PorterDuff.Mode.SRC_IN));
        paint.setAlpha((int) (110f + flicker * 90f));
        canvas.drawBitmap(src, null,
                new RectF(rect.left + split, rect.top, rect.right + split, rect.bottom), paint);
        paint.setColorFilter(new PorterDuffColorFilter(Color.CYAN, PorterDuff.Mode.SRC_IN));
        canvas.drawBitmap(src, null,
                new RectF(rect.left - split, rect.top, rect.right - split, rect.bottom), paint);
        paint.setColorFilter(null);
    }

    private static void tvChannel(@NonNull Canvas canvas, @Nullable Bitmap src, @NonNull RectF rect,
                                  @NonNull Paint paint, float p) {
        if (src == null || src.isRecycled()) return;
        if (p < 0.35f) veil(canvas, rect, Color.WHITE, 1f - p / 0.35f);
        Paint lp = new Paint();
        int lines = 26;
        for (int i = 0; i < lines; i++) {
            float y = rect.top + ((i * 13f + p * rect.height() * 1.7f) % rect.height());
            int shade = (i % 3 == 0) ? 235 : ((i % 2 == 0) ? 35 : 150);
            lp.setARGB(60 + (i % 4) * 26, shade, shade, shade);
            canvas.drawRect(rect.left, y, rect.right, y + 2f, lp);
        }
    }

    private static float clamp(float v) {
        return Math.max(0f, Math.min(1f, v));
    }
}
