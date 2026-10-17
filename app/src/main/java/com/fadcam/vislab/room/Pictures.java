package com.fadcam.vislab.room;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.ui.faditor.Studio;
import com.fadcam.ui.type.Type;
import com.fadcam.vislab.gl.VisRenderer;

import java.io.InputStream;

/**
 * Supplies pictures to image layers: the default emblem (the same "JOY" record the web pilot
 * draws), or a picture the person chose (a content:// or file reference stored in the layer's
 * {@code image}). Called on the GL thread, once per picture; big pictures are scaled down so
 * a phone photo never costs more than 1024 px of texture.
 */
final class Pictures implements VisRenderer.ImageSource {

    private static final String TAG = "VisPictures";
    private static final int MAX = 1024;

    private final Context app;
    @Nullable private Bitmap emblem;

    Pictures(@NonNull Context c) {
        app = c.getApplicationContext();
    }

    @Nullable
    @Override
    public synchronized Bitmap bitmap(@Nullable String ref) {
        if (ref == null || ref.isEmpty()) return emblem();
        try {
            Uri uri = Uri.parse(ref);
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            try (InputStream in = app.getContentResolver().openInputStream(uri)) {
                BitmapFactory.decodeStream(in, null, o);
            }
            int s = 1;
            while (Math.max(o.outWidth, o.outHeight) / s > MAX) s *= 2;
            BitmapFactory.Options d = new BitmapFactory.Options();
            d.inSampleSize = s;
            try (InputStream in = app.getContentResolver().openInputStream(uri)) {
                Bitmap b = BitmapFactory.decodeStream(in, null, d);
                return b != null ? b : emblem();
            }
        } catch (Exception e) {
            Log.w(TAG, "picture unavailable, showing the emblem: " + ref, e);
            return emblem();
        }
    }

    @NonNull
    private Bitmap emblem() {
        if (emblem != null) return emblem;
        int n = 512;
        Bitmap b = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new LinearGradient(0, 0, n, n, Studio.ROOM_VIZ, Studio.ROOM_VIZ_DEEP, Shader.TileMode.CLAMP));
        c.drawCircle(n / 2f, n / 2f, 240, p);
        p.setShader(null);
        p.setColor(0xFF0B0B0E);
        c.drawCircle(n / 2f, n / 2f, 200, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(3);
        p.setColor(Color.argb(90, 250, 160, 61));
        for (int r = 120; r < 200; r += 14) c.drawCircle(n / 2f, n / 2f, r, p);
        p.setStyle(Paint.Style.FILL);
        p.setColor(Studio.ROOM_VIZ);
        Typeface t = Type.display(app, Type.EXTRA);
        p.setTypeface(t != null ? t : Typeface.DEFAULT_BOLD);
        p.setTextSize(150);
        p.setTextAlign(Paint.Align.CENTER);
        Paint.FontMetrics fm = p.getFontMetrics();
        c.drawText("JOY", n / 2f, n / 2f - (fm.ascent + fm.descent) / 2f, p);
        emblem = b;
        return b;
    }
}
