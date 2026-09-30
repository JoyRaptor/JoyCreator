package com.fadcam.ui.faditor.tools;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;

/**
 * A tiny drawn picture of a visualizer shape, so the Shape row can show what each option LOOKS
 * like instead of naming it (owner, 2026-09-30: "a preview icon in the chip ... little icons
 * before the words"). Drawn, not bitmap: crisp at any size, one colour that follows the chip's
 * ink, no assets to keep in step with the emitter list.
 *
 * <p>Kinds are the {@code VizLayer.EMITTER_*} ids ("bars", "line", ...). An unknown id draws bars.
 */
public final class VizGlyph extends Drawable {

    private final String kind;
    private final int size;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);

    // Heights of the sample bars / points, 0..1: irregular on purpose, so it reads as a signal.
    private static final float[] H = {0.45f, 0.85f, 0.6f, 1f, 0.5f, 0.75f};

    public VizGlyph(@NonNull String kind, int sizePx, int colour) {
        this.kind = kind;
        this.size = sizePx;
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(colour);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setColor(colour);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        stroke.setStrokeWidth(Math.max(1.5f, sizePx / 11f));
    }

    @Override public int getIntrinsicWidth() { return size; }
    @Override public int getIntrinsicHeight() { return size; }

    @Override
    public void draw(@NonNull Canvas c) {
        float w = size, h = size, pad = size * 0.08f;
        float l = pad, r = w - pad, t = pad, b = h - pad, mid = h / 2f;
        int n = H.length;
        float step = (r - l) / n;
        switch (kind) {
            case "line": {
                Path p = new Path();
                for (int i = 0; i < n; i++) {
                    float x = l + step * (i + 0.5f), y = b - (b - t) * H[i];
                    if (i == 0) p.moveTo(x, y); else p.lineTo(x, y);
                }
                c.drawPath(p, stroke);
                break;
            }
            case "filled": {
                Path p = new Path();
                p.moveTo(l, b);
                for (int i = 0; i < n; i++) p.lineTo(l + step * (i + 0.5f), b - (b - t) * H[i]);
                p.lineTo(r, b);
                p.close();
                c.drawPath(p, fill);
                break;
            }
            case "dots": {
                float rad = step * 0.28f;
                for (int i = 0; i < n; i++) {
                    c.drawCircle(l + step * (i + 0.5f), b - (b - t) * H[i], rad, fill);
                }
                break;
            }
            case "squares": {
                float s = step * 0.62f;
                for (int i = 0; i < n; i += 1) {
                    float x = l + step * (i + 0.5f);
                    int stack = Math.max(1, Math.round(H[i] * 3f));
                    for (int k = 0; k < stack; k++) {
                        float y = b - (s + 1f) * (k + 1);
                        c.drawRect(x - s / 2, y, x + s / 2, y + s, fill);
                    }
                }
                break;
            }
            case "peaks": {
                float bw = step * 0.3f;
                for (int i = 0; i < n; i++) {
                    float x = l + step * (i + 0.5f), y = b - (b - t) * H[i];
                    c.drawRoundRect(new RectF(x - bw / 2, y + bw * 1.6f, x + bw / 2, b), bw / 2, bw / 2, fill);
                    c.drawCircle(x, y + bw * 0.5f, bw * 0.75f, fill);
                }
                break;
            }
            case "ring": {
                float rad = Math.min(r - l, b - t) * 0.32f;
                c.drawCircle(w / 2f, mid, rad, stroke);
                for (int i = 0; i < 12; i++) {
                    double a = Math.PI * 2 * i / 12;
                    float len = rad * (0.35f + 0.45f * H[i % n]);
                    float x0 = (float) (w / 2f + Math.cos(a) * (rad + 1f));
                    float y0 = (float) (mid + Math.sin(a) * (rad + 1f));
                    float x1 = (float) (w / 2f + Math.cos(a) * (rad + len));
                    float y1 = (float) (mid + Math.sin(a) * (rad + len));
                    c.drawLine(x0, y0, x1, y1, stroke);
                }
                break;
            }
            case "particles": {
                float[][] pts = {{0.22f, 0.7f, 0.11f}, {0.5f, 0.3f, 0.16f}, {0.78f, 0.62f, 0.09f},
                        {0.36f, 0.2f, 0.07f}, {0.68f, 0.85f, 0.12f}, {0.86f, 0.22f, 0.06f}};
                for (float[] p : pts) c.drawCircle(w * p[0], h * p[1], w * p[2], fill);
                break;
            }
            default: {   // bars
                float bw = step * 0.62f;
                for (int i = 0; i < n; i++) {
                    float x = l + step * (i + 0.5f), top = b - (b - t) * H[i];
                    c.drawRoundRect(new RectF(x - bw / 2, top, x + bw / 2, b), bw / 3, bw / 3, fill);
                }
            }
        }
    }

    @Override public void setAlpha(int alpha) { fill.setAlpha(alpha); stroke.setAlpha(alpha); }
    @Override public void setColorFilter(ColorFilter cf) { fill.setColorFilter(cf); stroke.setColorFilter(cf); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
