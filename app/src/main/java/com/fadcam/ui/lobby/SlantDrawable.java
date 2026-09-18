package com.fadcam.ui.lobby;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.CornerPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/**
 * A SHEARED CHIP — a parallelogram with softened corners, filled with a horizontal gradient.
 *
 * <p>JoyRaptor's sketch for the lobby's New row was literally this:
 *
 * <pre>[🎥Recording /  /🎬projects/  /🧍‍♂️characters/  /📥 import/</pre>
 *
 * <p>which is four chips sharing one shear angle: the first squared off on the left so it sits
 * flush in the gutter, every edge after it leaning the same way.
 *
 * <h3>Why the shear is doing real work</h3>
 * Four upright rounded rectangles in a row read as four unrelated buttons, and the eye has to
 * check each one. A constant shear does something an upright row cannot: it makes the whole row
 * read as ONE object that happens to be divided, so it is scanned in a single sweep. It is also
 * the only shape language on the screen borrowed from the subject matter — this is the angle of
 * a film splice and of the slanted cut on the recents cards, so the lobby says "video" in its
 * geometry rather than only in its icons.
 *
 * <h3>Why CornerPathEffect rather than a rounded-rect path</h3>
 * A sheared rectangle's corners are not 90°; two are acute and two obtuse. Rounding those
 * correctly means solving a different tangent arc at every vertex. {@link CornerPathEffect} does
 * exactly that as a path filter, so the acute corners get a tighter visual radius than the
 * obtuse ones — which is what the eye expects and what hand-rolled quadratics get wrong.
 *
 * <p>The radius here is deliberately small. His instruction was "make the round over much less":
 * a heavily rounded parallelogram loses the angle that is the entire point and turns into a
 * lozenge.
 */
public final class SlantDrawable extends Drawable {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    private final int colorStart, colorEnd;
    private final float slant;
    private final boolean flatLeft, flatRight;

    /**
     * @param colorStart left-hand gradient stop
     * @param colorEnd   right-hand gradient stop
     * @param slantPx    horizontal run of the shear, in pixels; the lean angle is
     *                   atan(slantPx / height), so a taller chip at the same slant leans less
     * @param cornerPx   corner softening, small on purpose
     * @param flatLeft   true for the first chip in a row, so its left edge sits flush
     * @param flatRight  true for a chip that must end square
     */
    public SlantDrawable(int colorStart, int colorEnd, float slantPx, float cornerPx,
                         boolean flatLeft, boolean flatRight) {
        this.colorStart = colorStart;
        this.colorEnd   = colorEnd;
        this.slant      = slantPx;
        this.flatLeft   = flatLeft;
        this.flatRight  = flatRight;
        paint.setStyle(Paint.Style.FILL);
        paint.setPathEffect(new CornerPathEffect(cornerPx));
    }

    @Override
    protected void onBoundsChange(Rect b) {
        super.onBoundsChange(b);
        if (b.width() <= 0 || b.height() <= 0) return;

        float l = b.left, t = b.top, r = b.right, btm = b.bottom;

        // Both edges lean the SAME way — top edge displaced right of the bottom edge — which is
        // what keeps neighbouring chips parallel and the gap between them a constant width.
        float leftTop     = flatLeft  ? l : l + slant;
        float leftBottom  = l;
        float rightTop    = r;
        float rightBottom = flatRight ? r : r - slant;

        path.reset();
        path.moveTo(leftTop,     t);
        path.lineTo(rightTop,    t);
        path.lineTo(rightBottom, btm);
        path.lineTo(leftBottom,  btm);
        path.close();

        // Gradient runs along the chip rather than across it, so a row of chips reads as a
        // continuous sweep of colour instead of four separate vertical washes.
        paint.setShader(new LinearGradient(l, t, r, t, colorStart, colorEnd, Shader.TileMode.CLAMP));
    }

    @Override public void draw(Canvas c) {
        if (getBounds().width() <= 0) return;
        c.drawPath(path, paint);
    }

    @Override public void setAlpha(int a) { paint.setAlpha(a); invalidateSelf(); }
    @Override public void setColorFilter(ColorFilter f) { paint.setColorFilter(f); invalidateSelf(); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
