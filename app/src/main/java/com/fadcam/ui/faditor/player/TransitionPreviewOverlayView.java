package com.fadcam.ui.faditor.player;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.ui.faditor.model.Transition;

public class TransitionPreviewOverlayView extends View {

    private static final int DEFAULT_W = 1080;
    private static final int DEFAULT_H = 1920;
    private final float density;

    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint colorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private Bitmap bitmap;
    private Transition transition;
    private float progress;
    private boolean useColor;
    private int color = Color.BLACK;

    public TransitionPreviewOverlayView(Context context) {
        super(context);
        this.density = getResources().getDisplayMetrics().density;
        init();
    }

    public TransitionPreviewOverlayView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        this.density = getResources().getDisplayMetrics().density;
        init();
    }

    public TransitionPreviewOverlayView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        this.density = getResources().getDisplayMetrics().density;
        init();
    }

    private void init() {
        setVisibility(View.GONE);
        setLayerType(View.LAYER_TYPE_HARDWARE, null);
    }

    public void renderColor(int color, float progress) {
        this.useColor = true;
        this.bitmap = null;
        this.transition = null;
        this.color = color;
        this.progress = clamp(progress);
        colorPaint.setAlpha((int) (255f * this.progress));
        // The view defaults to GONE; without flipping it VISIBLE here, onDraw()
        // early-returns and the veil never paints. Hide again at ~0 alpha so a
        // transparent reset doesn't leave an invisible-but-VISIBLE layer around.
        setVisibility(this.progress > 0.001f ? View.VISIBLE : View.GONE);
        invalidate();
    }

    public void renderBitmap(@NonNull Bitmap source, @NonNull Transition transition, float progress) {
        this.useColor = false;
        this.bitmap = source;
        this.transition = transition;
        this.progress = clamp(progress);
        setVisibility(View.VISIBLE);
        invalidate();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        recycleBitmap();
    }

    private void recycleBitmap() {
        if (bitmap != null) {
            bitmap.recycle();
            bitmap = null;
        }
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);
        if (getVisibility() != View.VISIBLE) return;
        if (useColor) {
            canvas.drawColor(withAlpha(color, progress));
            return;
        }
        Bitmap source = bitmap;
        if (source == null || source.isRecycled() || transition == null) return;
        float alpha = transitionAlpha();
        bitmapPaint.setAlpha((int) (255f * alpha));
        RectF rect = fitRect(source.getWidth(), source.getHeight(), getWidth(), getHeight());
        canvas.save();
        if (transition.isWipe()) {
            drawWipe(canvas, source, rect);
        } else if (transition.isPush()) {
            drawPush(canvas, source, rect);
        } else if (transition.type == Transition.Type.RADIAL) {
            drawRadial(canvas, source, rect);
        } else if (transition.type == Transition.Type.LINEAR_MIRROR_WIPE) {
            drawMirrorWipe(canvas, source, rect);
        } else if (transition.isGlitch()) {
            drawGlitch(canvas, source, rect);
        } else if (transition.isTvChannel()) {
            drawTvChannel(canvas, source, rect);
        } else {
            canvas.drawBitmap(source, null, rect, bitmapPaint);
        }
        canvas.restore();
    }

    private float transitionAlpha() {
        if (transition.type == Transition.Type.CROSS_DISSOLVE) return progress;
        return 1f;
    }

    private void drawWipe(@NonNull Canvas canvas, @NonNull Bitmap source, @NonNull RectF rect) {
        RectF reveal = wipeRevealRect(rect, source.getWidth(), source.getHeight());
        if (reveal.isEmpty()) return;
        canvas.save();
        canvas.clipRect(reveal);
        canvas.drawBitmap(source, null, rect, bitmapPaint);
        canvas.restore();
    }

    private RectF wipeRevealRect(@NonNull RectF rect, int srcW, int srcH) {
        float w = rect.width();
        float h = rect.height();
        float p = progress;
        switch (transition.getDirection()) {
            case "left":
                return new RectF(rect.left, rect.top, rect.left + w * p, rect.bottom);
            case "right":
                return new RectF(rect.right - w * p, rect.top, rect.right, rect.bottom);
            case "up":
                return new RectF(rect.left, rect.top, rect.right, rect.top + h * p);
            case "down":
            default:
                return new RectF(rect.left, rect.bottom - h * p, rect.right, rect.bottom);
        }
    }

    private void drawPush(@NonNull Canvas canvas, @NonNull Bitmap source, @NonNull RectF rect) {
        float w = rect.width();
        float h = rect.height();
        float p = progress;
        float dx = 0f;
        float dy = 0f;
        switch (transition.getDirection()) {
            case "left":
                dx = w * (1f - p);
                break;
            case "right":
                dx = -w * (1f - p);
                break;
            case "up":
                dy = h * (1f - p);
                break;
            case "down":
            default:
                dy = -h * (1f - p);
                break;
        }
        canvas.save();
        canvas.clipRect(rect);
        RectF shifted = new RectF(rect.left + dx, rect.top + dy, rect.right + dx, rect.bottom + dy);
        canvas.drawBitmap(source, null, shifted, bitmapPaint);
        canvas.restore();
    }

    private void drawRadial(@NonNull Canvas canvas, @NonNull Bitmap source, @NonNull RectF rect) {
        float radius = (float) Math.hypot(rect.width(), rect.height()) * progress;
        if (radius <= 0f) return;
        Path path = new Path();
        path.addCircle(rect.centerX(), rect.centerY(), radius, Path.Direction.CW);
        canvas.save();
        canvas.clipPath(path);
        canvas.drawBitmap(source, null, rect, bitmapPaint);
        canvas.restore();
    }

    private void drawMirrorWipe(@NonNull Canvas canvas, @NonNull Bitmap source, @NonNull RectF rect) {
        float halfW = rect.width() * progress / 2f;
        float halfH = rect.height() * progress / 2f;
        canvas.save();
        canvas.clipRect(rect.left, rect.centerY() - halfH, rect.right, rect.centerY() + halfH);
        canvas.clipRect(rect.centerX() - halfW, rect.top, rect.centerX() + halfW, rect.bottom);
        canvas.drawBitmap(source, null, rect, bitmapPaint);
        canvas.restore();
    }

    private void drawGlitch(@NonNull Canvas canvas, @NonNull Bitmap source, @NonNull RectF rect) {
        float flicker = (float) ((Math.sin(progress * Math.PI * 18d) + 1d) / 2d);
        float split = rect.width() * (0.015f + flicker * 0.055f);
        int oldAlpha = bitmapPaint.getAlpha();

        bitmapPaint.setAlpha(90);
        canvas.drawBitmap(source, null, rect, bitmapPaint);

        bitmapPaint.setColorFilter(new PorterDuffColorFilter(Color.RED, PorterDuff.Mode.SRC_IN));
        bitmapPaint.setAlpha((int) (130f + flicker * 90f));
        canvas.drawBitmap(source, null,
                new RectF(rect.left + split, rect.top, rect.right + split, rect.bottom), bitmapPaint);

        bitmapPaint.setColorFilter(new PorterDuffColorFilter(Color.BLUE, PorterDuff.Mode.SRC_IN));
        bitmapPaint.setAlpha((int) (120f + flicker * 100f));
        canvas.drawBitmap(source, null,
                new RectF(rect.left - split, rect.top, rect.right - split, rect.bottom), bitmapPaint);

        bitmapPaint.setColorFilter(null);
        bitmapPaint.setAlpha(oldAlpha);

        int slices = 7;
        float sliceH = rect.height() / slices;
        for (int i = 0; i < slices; i++) {
            float y = rect.top + i * sliceH;
            float offset = (i % 2 == 0 ? 1f : -1f) * split * (0.35f + i * 0.18f);
            canvas.save();
            canvas.clipRect(rect.left + offset, y, rect.right + offset, y + sliceH);
            bitmapPaint.setAlpha(190);
            canvas.drawBitmap(source, null, rect, bitmapPaint);
            canvas.restore();
        }
        bitmapPaint.setAlpha(oldAlpha);

        colorPaint.setColor(Color.BLACK);
        colorPaint.setAlpha((int) (45f + flicker * 70f));
        for (float y = rect.top + 3f * density; y < rect.bottom; y += 5f * density) {
            canvas.drawRect(rect.left, y, rect.right, y + density, colorPaint);
        }
        colorPaint.setAlpha(255);
    }

    private void drawTvChannel(@NonNull Canvas canvas, @NonNull Bitmap source, @NonNull RectF rect) {
        if (progress < 0.35f) {
            colorPaint.setColor(Color.WHITE);
            colorPaint.setAlpha((int) (255f * (1f - progress / 0.35f)));
            canvas.drawRect(rect, colorPaint);
            colorPaint.setAlpha(255);
        }

        int oldAlpha = bitmapPaint.getAlpha();
        bitmapPaint.setAlpha((int) (70f + 100f * Math.min(1f, progress * 2f)));
        canvas.drawBitmap(source, null,
                new RectF(rect.left, rect.top - rect.height() * progress * 0.45f,
                        rect.right, rect.bottom - rect.height() * progress * 0.45f), bitmapPaint);
        bitmapPaint.setAlpha(oldAlpha);

        int lines = 34;
        for (int i = 0; i < lines; i++) {
            float y = rect.top + ((i * 17f + progress * rect.height() * 1.7f) % rect.height());
            int shade = (i % 3 == 0) ? 245 : ((i % 2 == 0) ? 35 : 160);
            colorPaint.setARGB(70 + (i % 4) * 28, shade, shade, shade);
            canvas.drawRect(rect.left, y, rect.right, y + density * (1f + (i % 3)), colorPaint);
        }

        float syncY = rect.bottom - rect.height() * progress;
        colorPaint.setColor(Color.WHITE);
        colorPaint.setAlpha(170);
        canvas.drawRect(rect.left, syncY, rect.right, syncY + 2f * density, colorPaint);
        colorPaint.setAlpha(255);
    }

    private static RectF fitRect(int srcW, int srcH, int viewW, int viewH) {
        float w = Math.max(1, viewW);
        float h = Math.max(1, viewH);
        float scale = Math.min(w / Math.max(1, srcW), h / Math.max(1, srcH));
        float drawnW = srcW * scale;
        float drawnH = srcH * scale;
        return new RectF((w - drawnW) / 2f, (h - drawnH) / 2f,
                (w - drawnW) / 2f + drawnW, (h - drawnH) / 2f + drawnH);
    }

    private static int withAlpha(int color, float alpha) {
        return Color.argb((int) (255f * clamp(alpha)), Color.red(color), Color.green(color), Color.blue(color));
    }

    private static float clamp(float value) {
        return Math.max(0f, Math.min(1f, value));
    }
}
