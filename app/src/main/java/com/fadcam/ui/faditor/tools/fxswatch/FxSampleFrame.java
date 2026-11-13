package com.fadcam.ui.faditor.tools.fxswatch;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;

import androidx.annotation.NonNull;

/**
 * The one picture colour, blur and distort effects are shown over in the effect picker and on
 * the cards (STU-6): a small scene with sky, a sun, clouds, hills, a building with hard window
 * edges, a striped awning, a fence and two faces of different skin tones, so a blur, a grade
 * or a warp all read at a glance.
 *
 * <p>Drawn here, in code, at whatever size is asked for: no photograph is bundled (and none of
 * the owner's are ever used). {@link #VERSION} is part of every cached still's key, so a change
 * to this drawing remakes them.</p>
 */
public final class FxSampleFrame {

    /** Bump when the drawing changes: every cached still is keyed on it. */
    public static final int VERSION = 1;

    private FxSampleFrame() {}

    /** A neutral mid grey of {@code w} x {@code h}: what a generator is shown alone over. */
    @NonNull
    public static Bitmap grey(int w, int h) {
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        b.eraseColor(0xFF7A7A7E);
        return b;
    }

    /** The scene, drawn at {@code w} x {@code h} (made for 16:9). */
    @NonNull
    public static Bitmap scene(int w, int h) {
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.scale(w / 160f, h / 90f);   // drawn on a 160 x 90 board
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        // sky: deep blue overhead to a warm haze at the horizon
        p.setShader(new LinearGradient(0, 0, 0, 58, 0xFF1F5FAE, 0xFFF4C58A, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, 160, 58, p);
        p.setShader(null);

        // sun with a soft glow
        p.setShader(new RadialGradient(124, 24, 18, 0x99FFE7A8, 0x00FFE7A8, Shader.TileMode.CLAMP));
        c.drawCircle(124, 24, 18, p);
        p.setShader(null);
        p.setColor(0xFFFFF1C9);
        c.drawCircle(124, 24, 6.5f, p);

        // clouds
        p.setColor(0xE6FFFFFF);
        cloud(c, p, 30, 14, 1f);
        cloud(c, p, 88, 10, 0.75f);
        p.setColor(0xB3FFFFFF);
        cloud(c, p, 140, 40, 0.6f);

        // far hills, then near ones
        Path hills = new Path();
        hills.moveTo(0, 50);
        hills.cubicTo(25, 38, 45, 44, 70, 47);
        hills.cubicTo(95, 50, 115, 36, 160, 45);
        hills.lineTo(160, 60);
        hills.lineTo(0, 60);
        hills.close();
        p.setColor(0xFF5F7FA0);
        c.drawPath(hills, p);
        Path near = new Path();
        near.moveTo(0, 57);
        near.cubicTo(40, 50, 80, 56, 160, 52);
        near.lineTo(160, 62);
        near.lineTo(0, 62);
        near.close();
        p.setColor(0xFF3E7D4A);
        c.drawPath(near, p);

        // grass
        p.setShader(new LinearGradient(0, 58, 0, 90, 0xFF5DA644, 0xFF2F6B2A, Shader.TileMode.CLAMP));
        c.drawRect(0, 58, 160, 90, p);
        p.setShader(null);

        // the building: brick red, hard white windows, a striped awning
        p.setColor(0xFFA4412F);
        c.drawRect(8, 26, 50, 72, p);
        p.setColor(0xFF6E2A1F);
        c.drawRect(6, 23, 52, 27, p);
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                float x = 13 + col * 12.5f, y = 31 + row * 10;
                p.setColor(0xFFF2F4F8);
                c.drawRect(x, y, x + 7, y + 6, p);
                p.setColor(0xFF2A3446);
                c.drawRect(x + 3.2f, y, x + 3.8f, y + 6, p);
            }
        }
        for (int i = 0; i < 8; i++) {
            p.setColor((i & 1) == 0 ? 0xFFFFFFFF : 0xFF1E8C8C);
            c.drawRect(8 + i * 5.25f, 61, 8 + (i + 1) * 5.25f, 65, p);
        }
        p.setColor(0xFF2B2420);
        c.drawRect(24, 65, 34, 72, p);

        // a white picket fence along the front: fine, high-contrast edges
        p.setColor(0xFFF5F5F0);
        c.drawRect(0, 76, 160, 77.4f, p);
        for (int i = 0; i < 27; i++) {
            float x = 1 + i * 6;
            c.drawRect(x, 72, x + 2.2f, 84, p);
        }

        // two faces
        face(c, p, 82, 52, 0xFFE8B98F, 0xFF3A2618, 0xFFF2C14E, 1f);
        face(c, p, 104, 55, 0xFF8A5631, 0xFF141012, 0xFF3D6FD8, 0.92f);
        return b;
    }

    private static void cloud(@NonNull Canvas c, @NonNull Paint p, float x, float y, float k) {
        c.drawOval(new RectF(x - 12 * k, y - 3 * k, x + 12 * k, y + 4 * k), p);
        c.drawCircle(x - 4 * k, y - 3 * k, 5 * k, p);
        c.drawCircle(x + 4 * k, y - 4 * k, 6 * k, p);
    }

    /** A simple face with shoulders: skin, hair, eyes, mouth, shirt. Centre (x, y) is the face. */
    private static void face(@NonNull Canvas c, @NonNull Paint p, float x, float y,
                             int skin, int hair, int shirt, float k) {
        // shoulders
        p.setColor(shirt);
        c.drawRoundRect(new RectF(x - 15 * k, y + 11 * k, x + 15 * k, y + 40 * k),
                9 * k, 9 * k, p);
        // neck
        p.setColor(darker(skin));
        c.drawRect(x - 3.5f * k, y + 6 * k, x + 3.5f * k, y + 13 * k, p);
        // hair behind the head
        p.setColor(hair);
        c.drawOval(new RectF(x - 10.5f * k, y - 13 * k, x + 10.5f * k, y + 6 * k), p);
        // head
        p.setColor(skin);
        c.drawOval(new RectF(x - 8.5f * k, y - 10 * k, x + 8.5f * k, y + 10 * k), p);
        // fringe
        p.setColor(hair);
        c.drawArc(new RectF(x - 9 * k, y - 12 * k, x + 9 * k, y + 2 * k), 180, 180, true, p);
        // eyes
        p.setColor(Color.WHITE);
        c.drawOval(new RectF(x - 6 * k, y - 1.5f * k, x - 2 * k, y + 1.5f * k), p);
        c.drawOval(new RectF(x + 2 * k, y - 1.5f * k, x + 6 * k, y + 1.5f * k), p);
        p.setColor(0xFF1B1B22);
        c.drawCircle(x - 4 * k, y, 1.1f * k, p);
        c.drawCircle(x + 4 * k, y, 1.1f * k, p);
        // cheeks
        p.setColor(0x40E0526A);
        c.drawCircle(x - 5.5f * k, y + 4 * k, 1.8f * k, p);
        c.drawCircle(x + 5.5f * k, y + 4 * k, 1.8f * k, p);
        // mouth
        p.setColor(0xFFB3263E);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1.1f * k);
        p.setStrokeCap(Paint.Cap.ROUND);
        c.drawArc(new RectF(x - 3.5f * k, y + 2 * k, x + 3.5f * k, y + 7 * k), 20, 140, false, p);
        p.setStyle(Paint.Style.FILL);
    }

    private static int darker(int c) {
        return Color.rgb(Color.red(c) * 4 / 5, Color.green(c) * 4 / 5, Color.blue(c) * 4 / 5);
    }
}
