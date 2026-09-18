package com.fadcam.ui.faditor;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

/**
 * Thin progress stripe shown at the very top of the editor while an export is
 * running, so the user can tell an export is in flight even while editing.
 *
 * <p>Fills {@code progress} fraction (0f–1f) of the view's width with a flat,
 * two-tone diagonal stripe pattern reminiscent of a barbershop pole rotated
 * horizontal. The stripes continuously scroll to signal "in progress" the
 * same way an indeterminate spinner would, while still communicating real
 * percentage via the filled width. Purely decorative: never intercepts touch.</p>
 */
public class ExportProgressStripeView extends View {

    /** Width (in dp) of a single diagonal stripe band before repeating. */
    private static final float STRIPE_WIDTH_DP = 14f;
    /** Diagonal slant of each stripe, in dp of horizontal shift per pixel of height. */
    private static final float STRIPE_SLANT_DP = 10f;
    /** One full scroll cycle (one stripe period) takes this long. */
    private static final long CYCLE_DURATION_MS = 900L;

    private final Paint basePaint = new Paint();
    private final Paint stripePaint = new Paint();
    private final Path bandPath = new Path();

    private float progress = 0f; // 0f..1f
    private float stripeWidthPx;
    private float slantPx;
    private float phasePx = 0f;

    @androidx.annotation.Nullable
    private ValueAnimator phaseAnimator;

    public ExportProgressStripeView(Context context) {
        super(context);
        init();
    }

    public ExportProgressStripeView(Context context, @androidx.annotation.Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public ExportProgressStripeView(Context context, @androidx.annotation.Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        setClickable(false);
        setFocusable(false);
        // Never steal touches from the editor beneath it.
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);

        float density = getResources().getDisplayMetrics().density;
        stripeWidthPx = STRIPE_WIDTH_DP * density;
        slantPx = STRIPE_SLANT_DP * density;

        basePaint.setAntiAlias(false);
        basePaint.setStyle(Paint.Style.FILL);
        basePaint.setColor(0xFF35F6BF); // lighter flat green

        stripePaint.setAntiAlias(false);
        stripePaint.setStyle(Paint.Style.FILL);
        stripePaint.setColor(0xFF35F6BF); // darker flat green
    }

    /** Sets export progress as a 0f–1f fraction and redraws. */
    public void setProgress(float fraction) {
        progress = Math.max(0f, Math.min(1f, fraction));
        invalidate();
    }

    /** Starts the horizontal stripe-scroll animation. No-op if already running. */
    public void startAnimating() {
        if (phaseAnimator != null && phaseAnimator.isRunning()) return;
        float period = stripeWidthPx * 2f; // one light+dark band pair
        phaseAnimator = ValueAnimator.ofFloat(0f, period);
        phaseAnimator.setDuration(CYCLE_DURATION_MS);
        phaseAnimator.setRepeatCount(ValueAnimator.INFINITE);
        phaseAnimator.setInterpolator(new LinearInterpolator());
        phaseAnimator.addUpdateListener(a -> {
            phasePx = (float) a.getAnimatedValue();
            postInvalidateOnAnimation();
        });
        phaseAnimator.start();
    }

    /** Stops the animation loop so it doesn't keep drawing/spend battery while hidden. */
    public void stopAnimating() {
        if (phaseAnimator != null) {
            phaseAnimator.cancel();
            phaseAnimator = null;
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        stopAnimating();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int width = getWidth();
        int height = getHeight();
        if (width <= 0 || height <= 0 || progress <= 0f) return;

        float filledWidth = width * progress;

        int saveCount = canvas.save();
        canvas.clipRect(0f, 0f, filledWidth, height);

        // Flat base fill, then darker diagonal bands drawn on top — no gradients/shadows.
        canvas.drawRect(0f, 0f, filledWidth, height, basePaint);

        float period = stripeWidthPx * 2f;
        // Draw enough bands to cover the filled width plus the diagonal overhang on both sides,
        // shifting by the animated phase for the horizontal "rotating pole" motion.
        float leftBound = -slantPx - period;
        float rightBound = filledWidth + period;
        for (float x = leftBound - phasePx; x < rightBound; x += period) {
            // Parallelogram band: top edge offset by slant relative to bottom edge.
            bandPath.reset();
            bandPath.moveTo(x, height);
            bandPath.lineTo(x + slantPx, 0f);
            bandPath.lineTo(x + slantPx + stripeWidthPx, 0f);
            bandPath.lineTo(x + stripeWidthPx, height);
            bandPath.close();
            canvas.drawPath(bandPath, stripePaint);
        }

        canvas.restoreToCount(saveCount);
    }
}
