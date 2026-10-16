package com.fadcam.ui.library;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * The lobby's corner cut: a solid diagonal in a kind's gradient, bottom-right of a picture. No badge
 * and no word: the corner is dead space, and colour is the fastest thing the eye resolves, so a
 * mixed grid stays readable at a glance. Same shape as the lobby's recents (LobbyFragment.CornerCut,
 * private there), so a thing wears the same corner on the front door and in the Library.
 */
final class CornerCut extends Drawable {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private int a, b;

    CornerCut(int a, int b) {
        this.a = a;
        this.b = b;
    }

    void setColours(int a, int b) {
        this.a = a;
        this.b = b;
        onBoundsChange(getBounds());
        invalidateSelf();
    }

    @Override
    protected void onBoundsChange(@NonNull Rect r) {
        path.reset();
        path.moveTo(r.right, r.top);
        path.lineTo(r.right, r.bottom);
        path.lineTo(r.left, r.bottom);
        path.close();
        paint.setShader(r.isEmpty() ? null
                : new LinearGradient(r.left, r.top, r.right, r.bottom, a, b, Shader.TileMode.CLAMP));
    }

    @Override public void draw(@NonNull Canvas canvas) { canvas.drawPath(path, paint); }
    @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
    @Override public void setColorFilter(@Nullable ColorFilter cf) { paint.setColorFilter(cf); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
