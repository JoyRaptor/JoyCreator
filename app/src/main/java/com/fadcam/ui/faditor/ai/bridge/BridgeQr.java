package com.fadcam.ui.faditor.ai.bridge;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.graphics.Shader;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.R;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.util.EnumMap;
import java.util.Map;

/**
 * The pairing QR in the owner's style (10-28): round dots in the aqua-to-lime gradient running across
 * it, the three corner squares rounded, and a punched-out centre with Joybot's face in the same
 * gradient. The highest error correction keeps it readable with the middle cut out.
 */
public final class BridgeQr {
    private BridgeQr() { }

    public static final int AQUA = com.fadcam.ui.faditor.Studio.GO;
    public static final int LIME = com.fadcam.ui.faditor.Studio.GO_END;

    /** {@code text} as a square QR of {@code size} px on {@code background}; null if it can't be made. */
    @Nullable
    public static Bitmap draw(@NonNull Context c, @NonNull String text, int size, int background) {
        BitMatrix m;
        try {
            Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
            hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.H);
            hints.put(EncodeHintType.MARGIN, 0);
            hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
            m = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints);
        } catch (Exception e) {
            return null;
        }
        int n = m.getWidth();
        int quiet = 2;                            // modules of quiet zone round it
        float cell = size / (float) (n + quiet * 2);
        Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        canvas.drawColor(background);

        // Everything is first drawn as an alpha mask, then the gradient fills it in one pass.
        Bitmap mask = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas mc = new Canvas(mask);
        Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);
        ink.setColor(0xFFFFFFFF);

        // The punched-out centre: a circle about a quarter of the code across.
        float cx = size / 2f, cy = size / 2f;
        float hole = (n * cell) * 0.13f;
        float holeR = hole + cell * 0.8f;
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                if (!m.get(x, y) || finder(x, y, n)) continue;
                float px = (x + quiet + 0.5f) * cell, py = (y + quiet + 0.5f) * cell;
                if (Math.hypot(px - cx, py - cy) < holeR) continue;
                mc.drawCircle(px, py, cell * 0.43f, ink);
            }
        }
        // The three corner squares, rounded: a ring and a dot each.
        int[][] corners = {{0, 0}, {n - 7, 0}, {0, n - 7}};
        Paint clear = new Paint(Paint.ANTI_ALIAS_FLAG);
        clear.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
        for (int[] k : corners) {
            float l = (k[0] + quiet) * cell, t = (k[1] + quiet) * cell;
            RectF outer = new RectF(l, t, l + 7 * cell, t + 7 * cell);
            mc.drawRoundRect(outer, cell * 2.2f, cell * 2.2f, ink);
            RectF inner = new RectF(l + cell, t + cell, l + 6 * cell, t + 6 * cell);
            mc.drawRoundRect(inner, cell * 1.5f, cell * 1.5f, clear);
            RectF dot = new RectF(l + 2 * cell, t + 2 * cell, l + 5 * cell, t + 5 * cell);
            mc.drawRoundRect(dot, cell * 1.0f, cell * 1.0f, ink);
        }
        // Joybot's face in the hole (his art is a white silhouette with the features cut out).
        Bitmap face = BitmapFactory.decodeResource(c.getResources(), R.drawable.joybot_neutral);
        if (face != null) {
            float fh = hole * 1.7f;
            float fw = fh * face.getWidth() / (float) face.getHeight();
            RectF where = new RectF(cx - fw / 2, cy - fh / 2, cx + fw / 2, cy + fh / 2);
            Paint fp = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            mc.drawBitmap(face, null, where, fp);
            face.recycle();
        }
        // The gradient across the whole code, through the mask.
        Paint grad = new Paint(Paint.ANTI_ALIAS_FLAG);
        grad.setShader(new LinearGradient(0, 0, size, size, AQUA, LIME, Shader.TileMode.CLAMP));
        Bitmap alpha = mask.extractAlpha();
        canvas.drawBitmap(alpha, 0, 0, grad);
        alpha.recycle();
        mask.recycle();
        return out;
    }

    /** One of the three 7x7 corner squares (drawn on their own, rounded). */
    private static boolean finder(int x, int y, int n) {
        return (x < 7 && y < 7) || (x >= n - 7 && y < 7) || (x < 7 && y >= n - 7);
    }
}
