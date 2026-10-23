package com.fadcam.vislab.room;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.widget.SeekBar;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.ui.faditor.Studio;

/**
 * A knob's slider that can show "where it was": a small ring on the track at the value it had
 * when the finger came down, so a scrub can be judged against it. It fades 0.4 s after release.
 * The look (orange fill, thumb) is the plain SeekBar's; only the ring is added.
 */
final class GhostSeekBar extends SeekBar {

    private static final long FADE_MS = 400;

    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** Ring radius to the stroke's middle: with the stroke it is about 8dp across. */
    private final float radius;
    /** Track position of the ring, 0..1, or -1 for none. */
    private float at = -1f;
    /** 1 while held, easing to 0 after release. */
    private float strength;
    @Nullable private ValueAnimator fade;

    GhostSeekBar(@NonNull Context c) {
        super(c);
        float d = c.getResources().getDisplayMetrics().density;
        radius = 3.25f * d;
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(1.5f * d);
        ring.setColor(Studio.INK);
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(Studio.INK);
    }

    /** A drag began: put the ring where the slider stands (0..1 of the track). */
    void showWas(float pos) {
        if (fade != null) fade.cancel();
        at = Math.max(0f, Math.min(1f, pos));
        strength = 1f;
        invalidate();
    }

    /** The finger lifted: let the ring fade out. */
    void hideWas() {
        if (at < 0f) return;
        if (fade != null) fade.cancel();
        ValueAnimator a = ValueAnimator.ofFloat(strength, 0f);
        a.setDuration(FADE_MS);
        a.addUpdateListener(v -> {
            strength = (float) v.getAnimatedValue();
            invalidate();
        });
        a.addListener(new AnimatorListenerAdapter() {
            private boolean cancelled;

            @Override
            public void onAnimationCancel(Animator animation) {
                cancelled = true;   // a new drag took over: the ring stays for it
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                if (cancelled) return;
                at = -1f;
                strength = 0f;
                invalidate();
            }
        });
        fade = a;
        a.start();
    }

    @Override
    protected void onDetachedFromWindow() {
        if (fade != null) fade.cancel();
        at = -1f;
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);
        if (at < 0f || strength <= 0f) return;
        // The thumb's centre and the ring both sit on the track between the paddings.
        float w = getWidth() - getPaddingLeft() - getPaddingRight();
        boolean rtl = getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
        float x = getPaddingLeft() + (rtl ? 1f - at : at) * w;
        float thumbAt = getMax() > 0 ? getProgress() / (float) getMax() : 0f;
        float tx = getPaddingLeft() + (rtl ? 1f - thumbAt : thumbAt) * w;
        Drawable thumb = getThumb();
        float half = thumb != null ? thumb.getIntrinsicWidth() / 2f : radius * 2f;
        if (Math.abs(x - tx) < half) return;   // under the thumb the ring would only smudge it
        float y = (getHeight() + getPaddingTop() - getPaddingBottom()) / 2f;
        fill.setAlpha(Math.round(70 * strength));
        ring.setAlpha(Math.round(190 * strength));
        canvas.drawCircle(x, y, radius, fill);
        canvas.drawCircle(x, y, radius, ring);
    }
}
