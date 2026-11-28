package cc.joycreator.avatar.ui;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;

/**
 * The four sets that come with the room, painted by the phone itself (the pilot's {@code PAINT} in {@code al-scene.js}, stroke for
 * stroke): Meadow, Room, Night, Studio. A scene is never an empty black box; each is 1280 x 720 and drawn once.
 */
public final class SetPaint {
    public static final int W = 1280, H = 720;

    private SetPaint() {
    }

    /** A little deterministic random (the pilot's LCG), so a set looks the same every time. */
    private static final class Rnd {
        long s;

        Rnd(long seed) {
            s = seed;
        }

        double next() {
            s = (s * 1664525L + 1013904223L) & 0xFFFFFFFFL;
            return s / 4294967296.0;
        }
    }

    private static Paint fill(int color) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(color);
        return p;
    }

    private static Paint grad(float x0, float y0, float x1, float y1, int a, int b) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new LinearGradient(x0, y0, x1, y1, a, b, Shader.TileMode.CLAMP));
        return p;
    }

    /** The set's picture, or null for a key that is not one of the four. */
    public static Bitmap paint(String key) {
        Bitmap b = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        switch (key == null ? "" : key) {
            case "meadow": meadow(c); break;
            case "room": room(c); break;
            case "night": night(c); break;
            default: studio(c); break;
        }
        return b;
    }

    private static void meadow(Canvas g) {
        float w = W, h = H;
        Rnd r = new Rnd(7);
        g.drawRect(0, 0, w, h, grad(0, 0, 0, h * 0.62f, 0xFF6FBCF0, 0xFFDFF3FB));
        Paint sun = new Paint(Paint.ANTI_ALIAS_FLAG);
        sun.setShader(new RadialGradient(w * 0.78f, h * 0.2f, h * 0.42f, new int[]{0xF2FFFAD6, 0x8CFFF6C8, 0x00FFF6C8}, new float[]{0, 0.16f, 1}, Shader.TileMode.CLAMP));
        g.drawRect(0, 0, w, h, sun);
        int[] hills = {0xFFA9D7A0, 0xFF86C57F};
        float[][] hp = {{0.5f, 0.11f}, {0.55f, 0.08f}};
        for (int i = 0; i < 2; i++) {
            Path p = new Path();
            p.moveTo(0, h * 0.64f);
            for (float x = 0; x <= w + 1; x += w / 8) {
                float cy = h * (hp[i][0] - hp[i][1] * (float) r.next()), ey = h * (hp[i][0] + 0.05f + 0.03f * (float) r.next());
                p.quadTo(x + w / 16, cy, x + w / 8, ey);
            }
            p.lineTo(w, h);
            p.lineTo(0, h);
            p.close();
            g.drawPath(p, fill(hills[i]));
        }
        g.drawRect(0, h * 0.6f, w, h, grad(0, h * 0.6f, 0, h, 0xFF8FD06C, 0xFF3F9345));
        Paint blade = new Paint(Paint.ANTI_ALIAS_FLAG);
        blade.setColor(0x59286E32);
        blade.setStrokeWidth(2);
        for (int i = 0; i < 160; i++) {
            float x0 = (float) (r.next() * w), y0 = (float) (h * (0.62 + 0.38 * r.next())), l = 6 + (y0 / h - 0.6f) * 46;
            g.drawLine(x0, y0, x0 + (float) ((r.next() - 0.5) * 8), y0 - l, blade);
        }
    }

    private static void room(Canvas g) {
        float w = W, h = H;
        g.drawRect(0, 0, w, h * 0.66f, grad(0, 0, 0, h * 0.66f, 0xFFEFE3CF, 0xFFDCCBB0));
        g.drawRect(w * 0.62f, h * 0.12f, w * 0.84f, h * 0.42f, fill(0xFFCFE6F5));
        Paint wf = new Paint(Paint.ANTI_ALIAS_FLAG);
        wf.setStyle(Paint.Style.STROKE);
        wf.setColor(0xFFFFFFFF);
        wf.setStrokeWidth(10);
        g.drawRect(w * 0.62f, h * 0.12f, w * 0.84f, h * 0.42f, wf);
        wf.setStrokeWidth(6);
        g.drawLine(w * 0.73f, h * 0.12f, w * 0.73f, h * 0.42f, wf);
        g.drawLine(w * 0.62f, h * 0.27f, w * 0.84f, h * 0.27f, wf);
        g.drawRect(0, h * 0.66f, w, h, grad(0, h * 0.66f, 0, h, 0xFFB78F66, 0xFF8A6642));
        Paint board = new Paint(Paint.ANTI_ALIAS_FLAG);
        board.setColor(0x47462D19);
        board.setStrokeWidth(2);
        for (int i = -8; i <= 16; i++) g.drawLine(w * 0.5f + (i - 4) * w * 0.06f, h * 0.66f, w * 0.5f + (i - 4) * w * 0.2f, h, board);
        g.drawRect(0, h * 0.635f, w, h * 0.665f, fill(0xFFF6EFE2));
        g.drawRect(0, h * 0.665f, w, h * 0.677f, fill(0x1F000000));
    }

    private static void night(Canvas g) {
        float w = W, h = H;
        Rnd r = new Rnd(21);
        g.drawRect(0, 0, w, h * 0.64f, grad(0, 0, 0, h * 0.64f, 0xFF070B24, 0xFF2A3566));
        for (int i = 0; i < 150; i++) {
            Paint s = fill(((int) ((0.3 + 0.7 * r.next()) * 255) << 24) | 0xFFFFFF);
            float z = (float) (0.6 + 1.6 * r.next());
            float x = (float) (r.next() * w), y = (float) (r.next() * h * 0.6);
            g.drawRect(x, y, x + z, y + z, s);
        }
        Paint moon = new Paint(Paint.ANTI_ALIAS_FLAG);
        moon.setShader(new RadialGradient(w * 0.2f, h * 0.2f, h * 0.3f, new int[]{0xFFF0F4FF, 0xFFF0F4FF, 0x47BECDFF, 0x00BECDFF}, new float[]{0, 0.2f, 0.22f, 1}, Shader.TileMode.CLAMP));
        g.drawRect(0, 0, w, h, moon);
        Path p = new Path();
        p.moveTo(0, h * 0.62f);
        for (float x = 0; x <= w + 1; x += w / 6) p.quadTo(x + w / 12, h * (0.5f + 0.08f * (float) r.next()), x + w / 6, h * (0.6f + 0.03f * (float) r.next()));
        p.lineTo(w, h);
        p.lineTo(0, h);
        p.close();
        g.drawPath(p, fill(0xFF18233F));
        g.drawRect(0, h * 0.62f, w, h, grad(0, h * 0.62f, 0, h, 0xFF1D2F3A, 0xFF0C1519));
    }

    private static void studio(Canvas g) {
        float w = W, h = H;
        Paint k = new Paint(Paint.ANTI_ALIAS_FLAG);
        k.setShader(new LinearGradient(0, 0, 0, h, new int[]{0xFF1B1B20, 0xFF2C2C34, 0xFF34343D, 0xFF17171B}, new float[]{0, 0.6f, 0.68f, 1}, Shader.TileMode.CLAMP));
        g.drawRect(0, 0, w, h, k);
        g.save();
        g.translate(w / 2, h * 0.86f);
        g.scale(1, 0.26f);
        Paint s = new Paint(Paint.ANTI_ALIAS_FLAG);
        s.setShader(new RadialGradient(0, 0, w * 0.42f, 0x33FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        g.drawCircle(0, 0, w * 0.42f, s);
        g.restore();
    }
}
