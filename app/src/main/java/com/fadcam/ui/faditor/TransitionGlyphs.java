package com.fadcam.ui.faditor;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.ui.faditor.model.Transition;

/**
 * PREVIEW-1: a tiny picture of what a built-in transition does, drawn half way through between a "from" and a "to" tile.
 * A schematic, not the real render (the 26 GL transitions need a GPU render and are still name-only).
 */
public final class TransitionGlyphs {
    private static final int FROM = 0xFF5BA3E8, TO = 0xFFF6C58B, SIDE = 96;

    private TransitionGlyphs() {}

    /** The picture for {@code type}, or null for a type that has no schematic (the GL effects). */
    @Nullable
    public static Bitmap of(@NonNull Transition.Type type) {
        Bitmap b = Bitmap.createBitmap(SIDE, SIDE, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        float s = SIDE, h = s / 2f;
        switch (type) {
            case CROSS_DISSOLVE:
                p.setColor(FROM);
                c.drawRect(0, 0, s, s, p);
                p.setColor(TO);
                p.setAlpha(128);
                c.drawRect(0, 0, s, s, p);
                break;
            case FADE_OUT_TO_BLACK:
            case FADE_OUT_TO_WHITE:
                p.setColor(FROM);
                c.drawRect(0, 0, s, s, p);
                p.setColor(type == Transition.Type.FADE_OUT_TO_BLACK ? 0xFF000000 : 0xFFFFFFFF);
                p.setAlpha(150);
                c.drawRect(0, 0, s, s, p);
                break;
            case WIPE_LEFT:
                p.setColor(FROM);
                c.drawRect(0, 0, s, s, p);
                p.setColor(TO);
                c.drawRect(0, 0, h, s, p);
                break;
            case PUSH_LEFT:
                p.setColor(FROM);
                c.drawRect(0, 0, h, s, p);
                p.setColor(TO);
                c.drawRect(h, 0, s, s, p);
                p.setColor(0xFFFFFFFF);
                p.setAlpha(200);
                c.drawRect(h - 3, 0, h + 3, s, p);
                break;
            case RADIAL:
                p.setColor(FROM);
                c.drawRect(0, 0, s, s, p);
                p.setColor(TO);
                c.drawArc(new RectF(-s * 0.2f, -s * 0.2f, s * 1.2f, s * 1.2f), -90, 180, true, p);
                break;
            case LINEAR_MIRROR_WIPE:
                p.setColor(FROM);
                c.drawRect(0, 0, s, s, p);
                p.setColor(TO);
                c.drawRect(s * 0.3f, 0, s * 0.7f, s, p);
                break;
            case GLITCH:
                p.setColor(FROM);
                c.drawRect(0, 0, s, s, p);
                p.setColor(TO);
                c.drawRect(0, s * 0.1f, s * 0.7f, s * 0.3f, p);
                c.drawRect(s * 0.3f, s * 0.45f, s, s * 0.6f, p);
                c.drawRect(0, s * 0.75f, s * 0.55f, s * 0.9f, p);
                break;
            case TV_CHANNEL:
                p.setColor(FROM);
                c.drawRect(0, 0, s, s, p);
                p.setColor(0xFF8A8A92);
                for (int i = 0; i < 12; i++) c.drawRect(0, i * s / 12f, s, i * s / 12f + s / 24f, p);
                break;
            default:
                return null;
        }
        return b;
    }
}
