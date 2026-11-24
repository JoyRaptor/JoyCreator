package com.fadcam.ui.faditor.effects;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * PREVIEW-1: the small picture beside a colour grade's name, made by grading one fixed scene (sky, sun, hills, a dark figure,
 * a white bar: the same scene the effect swatches use) through the grade on the CPU ({@link LutMath}). Worker thread only.
 */
public final class LutSwatches {
    private static final int SIDE = 96;

    private LutSwatches() {}

    /** The graded scene for grade {@code id}, or null when the grade cannot be read or is not in the packed N x N*N layout. */
    @Nullable
    public static Bitmap of(@NonNull Context c, @NonNull String id) {
        Bitmap lut = LutManager.loadBitmap(c, id);
        if (lut == null) return null;
        int n = lut.getWidth();
        if (n < 2 || lut.getHeight() != n * n) return null;      // an unknown layout is left without a picture, never shown ungraded
        int[] packed = new int[n * n * n];
        lut.getPixels(packed, 0, n, 0, 0, n, n * n);
        Bitmap scene = scene();
        int[] px = new int[SIDE * SIDE];
        scene.getPixels(px, 0, SIDE, 0, 0, SIDE, SIDE);
        Bitmap out = Bitmap.createBitmap(LutMath.apply(packed, n, px), SIDE, SIDE, Bitmap.Config.ARGB_8888);
        return out;
    }

    private static Bitmap scene() {
        int w = SIDE, h = SIDE;
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new LinearGradient(0, 0, 0, h * 0.7f, 0xFF5BA3E8, 0xFFF6C58B, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        p.setShader(null);
        p.setColor(0xFFFFF2B0);
        c.drawCircle(w * 0.74f, h * 0.30f, h * 0.12f, p);
        p.setColor(0xFF3E7D4F);
        Path hills = new Path();
        hills.moveTo(0, h * 0.72f);
        hills.quadTo(w * 0.28f, h * 0.52f, w * 0.55f, h * 0.70f);
        hills.quadTo(w * 0.80f, h * 0.60f, w, h * 0.72f);
        hills.lineTo(w, h);
        hills.lineTo(0, h);
        hills.close();
        c.drawPath(hills, p);
        p.setColor(0xFFB5523B);
        c.drawRect(w * 0.20f, h * 0.55f, w * 0.30f, h * 0.88f, p);
        p.setColor(0xFFF4F4F5);
        c.drawRect(w * 0.60f, h * 0.76f, w * 0.90f, h * 0.80f, p);
        return b;
    }
}
