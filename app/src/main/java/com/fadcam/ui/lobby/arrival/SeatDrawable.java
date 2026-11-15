package com.fadcam.ui.lobby.arrival;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Joybot's seat at the start of the chat's bottom row: a little alcove lit from below in his purple,
 * with a lit sill he hovers over (design page, "A seat in the bottom row"; owner's pick, 15 Nov).
 * It is also his calm glow once he is in place.
 */
final class SeatDrawable extends Drawable {

    private static final int PURPLE = 0xCC27FF;
    private final float density;
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path shape = new Path();
    private final Matrix m = new Matrix();
    private final RadialGradient grad = new RadialGradient(0f, 0f, 1f,
            new int[]{0x47000000 | PURPLE, 0x00000000 | PURPLE}, new float[]{0f, .7f}, Shader.TileMode.CLAMP);

    SeatDrawable(float density) {
        this.density = density;
        glow.setShader(grad);
        sill.setColor(0x8C000000 | PURPLE);
    }

    @Override
    protected void onBoundsChange(@NonNull Rect b) {
        float r = 14f * density;
        shape.reset();
        shape.addRoundRect(new RectF(b), new float[]{r, r, r, r, 0f, 0f, 0f, 0f}, Path.Direction.CW);
        m.reset();
        m.setScale(b.width() * .7f, b.height() * .9f);
        m.postTranslate(b.exactCenterX(), b.bottom);
        grad.setLocalMatrix(m);
    }

    @Override
    public void draw(@NonNull Canvas c) {
        Rect b = getBounds();
        int save = c.save();
        c.clipPath(shape);
        c.drawRect(b, glow);
        c.drawRect(b.left, b.bottom - 2f * density, b.right, b.bottom, sill);
        c.restoreToCount(save);
    }

    @Override public void setAlpha(int alpha) { glow.setAlpha(alpha); sill.setAlpha(alpha); invalidateSelf(); }
    @Override public void setColorFilter(@Nullable ColorFilter cf) { glow.setColorFilter(cf); sill.setColorFilter(cf); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
