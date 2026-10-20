package com.fadcam.ui.about;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.ui.faditor.Studio;
import com.fadcam.ui.motion.Motion;

/**
 * The soft light behind the wordmark: an ellipse of the Sprite Lab magenta falling to Joybot's
 * indigo, then to nothing, so it fades out inside its own bounds and never shows an edge.
 *
 * <p>It BREATHES: its opacity drifts between 55% and full over almost four seconds and back. Slow
 * enough to read as a light rather than an animation. With reduced motion it simply rests at 80%.
 * The breath runs only while the page is in front ({@link #breathe}); a hidden glow costs nothing.</p>
 */
final class AboutGlow extends View {

    /** The centre, as a share of the width: over the middle of the lettering, not the page. */
    private static final float CENTRE_X = 0.40f;
    private static final long BREATH_MS = 3800L;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    @Nullable private ObjectAnimator breath;

    AboutGlow(@NonNull Context c) {
        super(c);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        setAlpha(0.8f);
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        if (w <= 0 || h <= 0) return;
        // A circle of radius w/2, squashed to the view's height in onDraw: an ellipse that ends
        // exactly at the view's top and bottom, so nothing is ever clipped to a hard line.
        paint.setShader(new RadialGradient(0, 0, w / 2f,
                new int[]{ Studio.alpha(Studio.ROOM_SPRITE, 0x5C), Studio.alpha(Studio.ORB_DEEP, 0x2A), 0 },
                new float[]{ 0f, 0.45f, 1f }, Shader.TileMode.CLAMP));
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        canvas.save();
        canvas.translate(w * CENTRE_X, h / 2f);
        canvas.scale(1f, h / (float) w);
        canvas.drawCircle(0, 0, w / 2f, paint);
        canvas.restore();
    }

    /** Opacity is applied straight to the paint: no offscreen layer for a single shape. */
    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }

    /** Start or stop the breath. Safe to call repeatedly. */
    void breathe(boolean on) {
        if (!on || Motion.reduced(getContext())) {
            if (breath != null) breath.cancel();
            breath = null;
            setAlpha(0.8f);
            return;
        }
        if (breath != null) return;
        breath = ObjectAnimator.ofFloat(this, View.ALPHA, 0.55f, 1f);
        breath.setDuration(BREATH_MS);
        breath.setRepeatMode(ValueAnimator.REVERSE);
        breath.setRepeatCount(ValueAnimator.INFINITE);
        breath.setInterpolator(Motion.EASE_IN_OUT);
        breath.start();
    }
}
