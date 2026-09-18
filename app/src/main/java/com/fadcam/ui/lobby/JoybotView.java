package com.fadcam.ui.lobby;

import android.animation.ValueAnimator;
import android.content.Context;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;

import androidx.annotation.Nullable;

import com.fadcam.R;
import com.fadcam.ui.motion.Motion;

/**
 * JOYBOT.
 *
 * <p>Two frames of white line art on transparency — {@code joybot_neutral} and
 * {@code joybot_smile} — registered on his HANDS, so that swapping them squashes him
 * rather than moving or resizing him. The art carries no colour of its own: tint it, or
 * put it on a disc with {@link #setOrb}.
 *
 * <h3>What he does, and why each one earns its frames</h3>
 * <ul>
 *   <li><b>Idle hover.</b> A slow float, about 1.6% of his height, on a ~4 second cycle.
 *       He is the only thing on the lobby that is a CHARACTER rather than a control, and a
 *       character that is perfectly still is a picture of a character. The amplitude is
 *       deliberately below the threshold where you would describe it as "moving" — you
 *       notice it only if you look at him directly, which is exactly when you want to.</li>
 *   <li><b>Smile on push.</b> JoyRaptor's instruction. The smile comes with a downward dip of
 *       {@link #DIP_FRACTION}, which is his note that "his body moves down about 5% when
 *       smiling in the art" — applied here as motion rather than baked into the frames, so
 *       it eases instead of cutting.</li>
 *   <li><b>Smile on scroll.</b> Also his: "on scroll gesture in screens it looks like he's
 *       watching and reacting". He reacts to the content moving, then settles.</li>
 * </ul>
 *
 * <h3>The animation is not allowed to cost anything</h3>
 * Everything here moves {@code translationY} and {@code alpha}, which are compositor
 * properties — no layout pass, no redraw of the two bitmaps. The idle animator is torn down
 * whenever he is off-screen or detached, because an infinite animator that survives the
 * screen it belongs to is the classic way a "subtle" flourish turns into a battery
 * complaint nobody can trace. {@link Motion#reduced(Context)} switches all of it off.
 */
public final class JoybotView extends FrameLayout {

    /** How far he dips when he smiles, as a fraction of his own height. */
    private static final float DIP_FRACTION = 0.05f;
    /** Idle float amplitude, as a fraction of his height. */
    private static final float HOVER_FRACTION = 0.016f;
    private static final long HOVER_PERIOD_MS = 4000L;
    /** How long a smile lasts before he settles back. */
    private static final long SMILE_HOLD_MS = 1100L;
    /** Cross-fade between the two faces. Fast: an expression is a change, not a dissolve. */
    private static final long FACE_SWAP_MS = 130L;

    private final ImageView neutral;
    private final ImageView smile;

    @Nullable private ValueAnimator hover;
    private float hoverOffset = 0f;
    private float dipOffset = 0f;
    private boolean smiling = false;

    private final Runnable settle = this::stopSmiling;

    public JoybotView(Context c) { this(c, null); }
    public JoybotView(Context c, @Nullable AttributeSet a) { this(c, a, 0); }

    public JoybotView(Context c, @Nullable AttributeSet a, int def) {
        super(c, a, def);
        neutral = face(R.drawable.joybot_neutral, 1f);
        smile   = face(R.drawable.joybot_smile, 0f);
        addView(neutral);
        addView(smile);
        setClipChildren(false);
        setClipToPadding(false);
    }

    // ── the shared canvas ───────────────────────────────────────────────────
    // Both frames live on one 360x564 canvas, registered on the BOTTOM OF HIS HANDS.
    //
    // That anchor is JoyRaptor's, and it is the reason he squashes instead of resizing. The
    // two frames are scaled so their hand-to-hand spans match, which leaves the smiling
    // head 246x295 against the neutral's 231x319 -- wider and shorter at nearly the same
    // area. Pinning the hands then makes that compression happen DOWNWARD from the crown
    // rather than upward from the base: "blinking pulling his tummy up is weirder than
    // blinking pulling his crown down."
    //
    // The box below is the NEUTRAL body. Everything above it is headroom only the smile's
    // spark strokes ever enter. Fractions, not pixels, so re-exporting the art at another
    // resolution cannot silently move him.
    private static final float BODY_TOP = 0.2074f;
    private static final float BODY_BOT = 0.9663f;
    private static final float BODY_L   = 0.0417f;
    private static final float BODY_R   = 0.9611f;

    /**
     * How much of the box he fills when he is sitting on an orb.
     *
     * <p>Without an orb he fills it. On one he has to clear a CIRCLE, and a circle's
     * usable width shrinks toward its top and bottom — his hands are at his widest exactly
     * where the disc is still wide, but his base ring and his crown are not, so fitting him
     * edge to edge would push both outside the disc and make it look like a badge he is
     * falling out of.
     */
    private static final float ORB_FILL = 0.76f;

    private boolean onOrb = false;

    private ImageView face(int res, float alpha) {
        ImageView v = new ImageView(getContext());
        v.setImageResource(res);
        // MATRIX, not FIT_CENTER.
        //
        // FIT_CENTER fits the whole 360x540 canvas, headroom included, so in a 40dp chrome
        // slot Joybot came out as a tiny distant figure whose head was about 16dp — the
        // headroom was eating a third of his box for something that is visible for one
        // second when he smiles.
        //
        // The matrix fits his BODY to the view and lets the sparks fall outside it. Both
        // frames get the SAME matrix, which is the whole point of registering them: they
        // stay locked to each other, and when he smiles the sparks pop above the view's own
        // bounds. clipChildren is off all the way up, so that reads as delight rather than
        // as clipping.
        v.setScaleType(ImageView.ScaleType.MATRIX);
        v.setAlpha(alpha);
        v.setLayoutParams(new LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER));
        return v;
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        fit(neutral, w, h);
        fit(smile, w, h);
    }

    private void fit(ImageView v, int w, int h) {
        android.graphics.drawable.Drawable d = v.getDrawable();
        if (d == null || w == 0 || h == 0) return;
        float iw = d.getIntrinsicWidth(), ih = d.getIntrinsicHeight();
        if (iw <= 0 || ih <= 0) return;

        float bodyW = iw * (BODY_R - BODY_L);
        float bodyH = ih * (BODY_BOT - BODY_TOP);
        float fill = onOrb ? ORB_FILL : 1f;
        float s = Math.min(w * fill / bodyW, h * fill / bodyH);

        android.graphics.Matrix m = new android.graphics.Matrix();
        m.setScale(s, s);
        // Centre the BODY box in the view, not the canvas.
        m.postTranslate((w - bodyW * s) / 2f - iw * BODY_L * s,
                        (h - bodyH * s) / 2f - ih * BODY_TOP * s);
        v.setImageMatrix(m);
    }

    /** Tint both faces. The art is white, so this is a straight multiply. */
    public void setTint(int colour) {
        neutral.setColorFilter(colour);
        smile.setColorFilter(colour);
    }

    /**
     * Put him on a gradient disc.
     *
     * <p>JoyRaptor, after seeing him tinted violet on the lobby's black: "turns out he looks
     * harder to read coloured on black." He is right, and the reason is that this art is
     * LINE work — its shape is carried by thin strokes and by the holes between them, and
     * a mid-violet stroke on black has nothing like the separation a white one does.
     *
     * <p>The disc fixes both halves at once: it gives the violet back as a field instead
     * of as line colour, and it gives the white strokes a bright ground to read against.
     * It also makes him a distinct OBJECT in the chrome rather than a drawing floating in
     * the background, which is right for the one element on the screen that is a character.
     */
    public void setOrb(int from, int to) {
        android.graphics.drawable.GradientDrawable d =
                new android.graphics.drawable.GradientDrawable(
                        android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                        new int[]{from, to});
        d.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        setBackground(d);
        onOrb = true;
        setTint(0xFFFFFFFF);
        if (getWidth() > 0) { fit(neutral, getWidth(), getHeight()); fit(smile, getWidth(), getHeight()); }
    }

    // ── expressions ─────────────────────────────────────────────────────────

    /**
     * Smile, then settle on his own.
     *
     * <p>Re-arming rather than restarting: a second call while he is already smiling just
     * pushes the settle back, so a burst of scroll events reads as one sustained reaction
     * instead of a stutter of half-finished cross-fades.
     */
    public void react() {
        removeCallbacks(settle);
        postDelayed(settle, SMILE_HOLD_MS);
        if (smiling) return;
        smiling = true;
        if (Motion.reduced(getContext())) {
            neutral.setAlpha(0f);
            smile.setAlpha(1f);
            return;
        }
        neutral.animate().alpha(0f).setDuration(FACE_SWAP_MS)
                .setInterpolator(Motion.EASE_OUT).start();
        smile.animate().alpha(1f).setDuration(FACE_SWAP_MS)
                .setInterpolator(Motion.EASE_OUT).start();
        animateDip(getHeight() * DIP_FRACTION);
    }

    private void stopSmiling() {
        if (!smiling) return;
        smiling = false;
        if (Motion.reduced(getContext())) {
            neutral.setAlpha(1f);
            smile.setAlpha(0f);
            return;
        }
        neutral.animate().alpha(1f).setDuration(FACE_SWAP_MS * 2)
                .setInterpolator(Motion.EASE_OUT).start();
        smile.animate().alpha(0f).setDuration(FACE_SWAP_MS * 2)
                .setInterpolator(Motion.EASE_OUT).start();
        animateDip(0f);
    }

    @Nullable private ValueAnimator dipAnim;

    private void animateDip(float target) {
        if (dipAnim != null) dipAnim.cancel();
        // The dip and the hover both want to own translationY, so neither sets it directly;
        // each writes its own offset and applyOffsets() sums them. Letting two animators
        // drive one property is how a float like this ends up fighting itself and snapping.
        dipAnim = ValueAnimator.ofFloat(dipOffset, target);
        dipAnim.setDuration(target > 0f ? 260L : 420L);
        dipAnim.setInterpolator(target > 0f ? Motion.EASE_OUT : Motion.EASE_IN_OUT);
        dipAnim.addUpdateListener(a -> {
            dipOffset = (float) a.getAnimatedValue();
            applyOffsets();
        });
        dipAnim.start();
    }

    private void applyOffsets() {
        neutral.setTranslationY(hoverOffset + dipOffset);
        smile.setTranslationY(hoverOffset + dipOffset);
    }

    // ── idle ────────────────────────────────────────────────────────────────

    private void startHover() {
        if (hover != null || Motion.reduced(getContext())) return;
        hover = ValueAnimator.ofFloat(0f, (float) (Math.PI * 2));
        hover.setDuration(HOVER_PERIOD_MS);
        hover.setRepeatCount(ValueAnimator.INFINITE);
        hover.setInterpolator(null);   // a sine is already eased; an interpolator on top
                                       // of it would make the float lurch at the ends
        hover.addUpdateListener(a -> {
            float amp = getHeight() * HOVER_FRACTION;
            hoverOffset = (float) Math.sin((float) a.getAnimatedValue()) * amp;
            applyOffsets();
        });
        hover.start();
    }

    private void stopHover() {
        if (hover == null) return;
        hover.cancel();
        hover = null;
        hoverOffset = 0f;
        applyOffsets();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        startHover();
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(settle);
        if (dipAnim != null) dipAnim.cancel();
        stopHover();
        super.onDetachedFromWindow();
    }

    @Override
    public void onVisibilityAggregated(boolean visible) {
        super.onVisibilityAggregated(visible);
        // Covers the cases onDetachedFromWindow does not: the fragment behind another
        // fragment, the screen off, the app in the background. All of those leave the view
        // attached, and without this he would keep animating into a surface nobody sees.
        if (visible) startHover(); else stopHover();
    }

    private long lastReactMs = 0L;

    /**
     * Wire him to a scroll container so he reacts while it moves.
     *
     * <p>Uses {@link View#setOnScrollChangeListener}, which is scoped to the ONE view it is
     * set on. The obvious alternative — {@code getViewTreeObserver().addOnScrollChangedListener}
     * — is scoped to the whole WINDOW, so he would twitch at every scroll anywhere on the
     * screen, and it is registered against an observer that outlives this view, which leaks
     * it until the window goes away.
     *
     * <p>Throttled because a scroll fires this on every frame. Without the gate a flick
     * would post and cancel the settle runnable sixty times a second to no visible effect.
     */
    public void watch(View scrollable) {
        if (scrollable == null) return;
        scrollable.setOnScrollChangeListener((v, x, y, ox, oy) -> {
            long now = android.os.SystemClock.uptimeMillis();
            if (now - lastReactMs < 300L) return;
            lastReactMs = now;
            react();
        });
    }
}
