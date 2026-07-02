package com.fadcam.ui.faditor.text;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;

import androidx.annotation.NonNull;

public final class TextStyleRenderer {

    private TextStyleRenderer() {}

    @NonNull
    public static Bitmap render(@NonNull TextStyle style, @NonNull String text, int outW, int outH) {
        TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(style.getColorInt());
        paint.setTextSize(Math.max(8f, outH * 0.08f));
        paint.setTypeface(typeface(style));
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setShadowLayer(style.getShadowRadiusPx(), 0f, paint.getTextSize() * 0.04f,
                style.getShadowColorInt());
        String[] lines = text.split("\n", -1);
        float lineH = paint.getFontMetrics().descent - paint.getFontMetrics().ascent;
        float maxW = 1f;
        for (String line : lines) maxW = Math.max(maxW, paint.measureText(line));
        int pad = Math.round(paint.getTextSize() * 0.45f);
        int w = Math.max(1, Math.min(outW, Math.round(maxW + pad * 2f)));
        int h = Math.max(1, Math.round(lineH * lines.length + pad * 2f));
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        if (style.getBackgroundColorInt() != Color.TRANSPARENT) {
            Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
            bg.setColor(style.getBackgroundColorInt());
            canvas.drawRoundRect(new RectF(0, 0, w, h), pad * 0.5f, pad * 0.5f, bg);
        }
        float x = w / 2f;
        float y = pad - paint.getFontMetrics().ascent;
        for (String line : lines) {
            if (style.getGlowRadiusPx() > 0f && style.getGlowColorInt() != Color.TRANSPARENT) {
                paint.setShadowLayer(style.getGlowRadiusPx(), 0f, 0f, style.getGlowColorInt());
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(style.getColorInt());
                canvas.drawText(line, x, y, paint);
            }
            if (style.getStrokeWidthPx() > 0f && style.getStrokeColorInt() != Color.TRANSPARENT) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(style.getStrokeWidthPx());
                paint.setColor(style.getStrokeColorInt());
                canvas.drawText(line, x, y, paint);
            }
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(style.getColorInt());
            paint.setShadowLayer(style.getShadowRadiusPx(), 0f, paint.getTextSize() * 0.04f,
                    style.getShadowColorInt());
            canvas.drawText(line, x, y, paint);
            y += lineH;
        }
        return bmp;
    }

    @NonNull
    public static StaticLayout staticLayout(@NonNull TextStyle style, @NonNull String text, int width) {
        TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(style.getColorInt());
        paint.setTextSize(Math.max(8f, width * 0.08f));
        paint.setTypeface(typeface(style));
        return StaticLayout.Builder.obtain(text, 0, text.length(), paint, width)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setIncludePad(false)
                .build();
    }

    @NonNull
    private static Typeface typeface(@NonNull TextStyle style) {
        switch (style.getFontFamily()) {
            case "serif": return Typeface.SERIF;
            case "mono": return Typeface.MONOSPACE;
            case "light": return Typeface.create("sans-serif-light", Typeface.NORMAL);
            case "bold": return Typeface.DEFAULT_BOLD;
            default: return Typeface.DEFAULT;
        }
    }
}
