package com.fadcam.ui.faditor.tools;

import com.fadcam.ui.faditor.Studio;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * The "this selection holds MORE THAN ONE value" swatch (W5-2 §3.8 rich text).
 *
 * <p>When a drawer control reads a selection whose characters disagree on a property, the swatch
 * can show neither value — showing one would claim the wrong thing is about to happen. So it
 * shows the MIXED mark instead: a dark circle split in half along the main diagonal, upper-left
 * in the app's purple accent (the same "formatting applies to a RANGE" colour the toggle chips
 * use), lower-right left bare. The border matches the plain swatches' hairline so the two sit
 * side by side without looking like different controls.</p>
 */
public final class MixedSwatchDrawable extends Drawable {

    private static final int BG = Studio.LINE;
    private static final int ACCENT = Studio.alpha(Studio.GUIDE, 0x99);

    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint splitPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path splitPath = new Path();
    private final RectF oval = new RectF();

    public MixedSwatchDrawable() {
        bgPaint.setColor(BG);
        splitPaint.setColor(ACCENT);
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(1.5f);
        borderPaint.setColor(Studio.INK_FAINT);
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        Rect b = getBounds();
        oval.set(b);
        canvas.drawOval(oval, bgPaint);
        // Upper-left triangle of the main diagonal — a clean "one thing and another" read
        // at swatch size, cheap and rotation-independent.
        splitPath.reset();
        splitPath.moveTo(b.left, b.top);
        splitPath.lineTo(b.right, b.top);
        splitPath.lineTo(b.left, b.bottom);
        splitPath.close();
        canvas.save();
        canvas.clipPath(splitPath);
        canvas.drawOval(oval, splitPaint);
        canvas.restore();
        canvas.drawOval(oval, borderPaint);
    }

    @Override public void setAlpha(int alpha) { }
    @Override public void setColorFilter(@Nullable ColorFilter colorFilter) { }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}