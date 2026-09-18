package com.fadcam.ui.motion;

import android.animation.ValueAnimator;
import android.content.Context;
import android.provider.Settings;
import android.view.View;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * JOY CREATOR'S MOTION SYSTEM — four curves, a handful of durations, and six rules.
 *
 * <p>Design record 04 §04. The numbers come from Emil Kowalski's animation standards,
 * reconciled against what this app actually does. They are here rather than scattered as
 * literals so that an agent implementing a new surface has <b>nothing left to invent</b>.</p>
 *
 * <h3>The rules that matter more than the numbers</h3>
 * <ol>
 *   <li><b>Never {@code ease-in} on UI.</b> It delays motion at exactly the moment the eye is
 *       watching. No curve in this class starts slow.</li>
 *   <li><b>Only transform and alpha.</b> Anything else recalculates layout, and on a phone
 *       that is also encoding video that is dropped frames in the preview.</li>
 *   <li><b>Under 300ms for anything you do more than once a session.</b> {@link #SHEET} is the
 *       single exception, because a drawer travels a long way.</li>
 *   <li><b>Interruptible.</b> Anything you can tap twice quickly must retarget mid-flight, not
 *       restart from zero.</li>
 *   <li><b>Things done a hundred times a day get NO animation at all.</b> Undo, play, split,
 *       keying a sprite cell at the playhead, the playhead itself. Motion on a
 *       two-hundred-times-an-afternoon control does not read as polish — it reads as a slow
 *       app. There is deliberately no helper in this class for those.</li>
 *   <li><b>Reduced motion means fewer and gentler, not zero.</b> Alpha and colour survive;
 *       travel and scale are dropped. See {@link #reduced(Context)}.</li>
 * </ol>
 */
public final class Motion {

    private Motion() {}

    // ── the four curves ─────────────────────────────────────────────────────

    /** Entering, leaving, and almost everything else. Starts fast, lands soft. */
    public static final Interpolator EASE_OUT = new PathInterpolator(0.23f, 1f, 0.32f, 1f);

    /** Something MOVING on screen — it existed before and it exists after. */
    public static final Interpolator EASE_IN_OUT = new PathInterpolator(0.77f, 0f, 0.175f, 1f);

    /** Drawers and sheets. A long tail, so a panel settles rather than stops. */
    public static final Interpolator DRAWER = new PathInterpolator(0.32f, 0.72f, 0f, 1f);

    /** Constant motion only — a progress bar, a marquee. Never for an entrance. */
    public static final Interpolator LINEAR = input -> input;

    // ── durations, in milliseconds ──────────────────────────────────────────

    /** Any press. Every tappable thing in the app, no exceptions. */
    public static final long PRESS = 140L;
    /** Tooltips, badges, the smallest reveals. */
    public static final long TIP   = 170L;
    /** A tool row swapping its contents. Cross-fade only — never movement. */
    public static final long SWAP  = 170L;
    /** Popovers and pickers. From scale .95, never from scale 0. */
    public static final long MENU  = 220L;
    /** A hero or a title changing what it is showing. */
    public static final long HERO  = 260L;
    /** Drawers and sheets. The one thing allowed past 300ms. */
    public static final long SHEET = 320L;
    /** Seen once, with nobody waiting on it. Delight is allowed here and nowhere else. */
    public static final long ONCE  = 560L;

    /** Between staggered items. Decorative only — never block a tap waiting for it. */
    public static final long STAGGER = 40L;

    /** How far a press sinks. 0.95–0.98 is the usable range; this is the middle of it. */
    public static final float PRESS_SCALE = 0.97f;

    // ── helpers ─────────────────────────────────────────────────────────────

    /**
     * Whether the user has asked the system for less movement.
     *
     * <p>Checked live rather than cached: accessibility settings change while an app is
     * running, and a cached answer means a user who turns this on has to relaunch.</p>
     */
    public static boolean reduced(@Nullable Context ctx) {
        if (ctx == null) return false;
        try {
            float scale = Settings.Global.getFloat(ctx.getContentResolver(),
                    Settings.Global.ANIMATOR_DURATION_SCALE, 1f);
            return scale == 0f;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Give a view the standard press feel: it sinks 3% and springs back.
     *
     * <p>Applied on top of whatever click listener the view already has, so it is additive
     * and cannot swallow a tap. Under reduced motion the view still responds — it just
     * responds with alpha instead of scale, because removing ALL feedback from a press is
     * worse for everyone than a small movement is for someone sensitive to it.</p>
     */
    public static void press(@NonNull View v) {
        final boolean noScale = reduced(v.getContext());
        v.setOnTouchListener((view, ev) -> {
            switch (ev.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    if (noScale) {
                        view.animate().alpha(0.72f).setDuration(PRESS)
                                .setInterpolator(EASE_OUT).start();
                    } else {
                        view.animate().scaleX(PRESS_SCALE).scaleY(PRESS_SCALE)
                                .setDuration(PRESS).setInterpolator(EASE_OUT).start();
                    }
                    break;
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL:
                    view.animate().scaleX(1f).scaleY(1f).alpha(1f)
                            .setDuration(PRESS).setInterpolator(EASE_OUT).start();
                    break;
                default:
                    break;
            }
            return false; // never consume — the click listener still fires
        });
    }

    /**
     * Cross-fade a view to new content without moving it.
     *
     * <p>Movement would imply the old content went somewhere you could go back to. A
     * cross-fade says it simply became something else, which is what a hero or a tool row
     * actually does.</p>
     */
    public static void swap(@NonNull View v, long durationMs, @NonNull Runnable applyNewContent) {
        if (reduced(v.getContext())) { applyNewContent.run(); return; }
        v.animate().cancel();
        v.animate().alpha(0f).setDuration(durationMs / 2).setInterpolator(EASE_OUT)
                .withEndAction(() -> {
                    applyNewContent.run();
                    v.setAlpha(0f);
                    v.animate().alpha(1f).setDuration(durationMs / 2)
                            .setInterpolator(EASE_OUT).start();
                }).start();
    }

    /**
     * Swap a PICTURE, softly.
     *
     * <p>The lobby mockup specifies this exactly: <i>"the hero cross-fades on the real
     * curve, with a 2px blur through the swap so two states never read as two states."</i>
     *
     * <p>The blur is doing something a plain cross-fade cannot. Halfway through a straight
     * dissolve both images are at 50% and BOTH are still legible, so for a few frames the
     * screen is showing two different photographs at once and the eye tries to read them as
     * one. Taking the detail out through the middle removes anything to read, so the swap
     * lands as one picture becoming another rather than as two pictures overlapping.
     *
     * <h3>Two implementations, because the blur is not available where it is needed most</h3>
     * {@link android.graphics.RenderEffect} is API 31. This app's minSdk is 24 and the
     * device this was designed on runs API 29, so on the machine the design was judged
     * against the real blur does not exist at all.
     *
     * <p>The fallback is a scale, not a cheaper blur. Blurring a bitmap by hand — a
     * downscale-and-upscale, or the old RenderScript path — costs a full-screen allocation
     * twice per swap on the main thread, for an effect that is on screen for 130ms. A 3%
     * scale-up through the midpoint achieves the same PURPOSE by a different means: it
     * makes the outgoing frame stop matching the incoming one geometrically, so the two
     * cannot be read as a single image even while both are partly visible.
     */
    public static void swapPicture(@NonNull View v, long durationMs,
                                   @NonNull Runnable applyNewContent) {
        if (reduced(v.getContext())) { applyNewContent.run(); return; }
        v.animate().cancel();
        final long half = Math.max(1L, durationMs / 2);

        if (android.os.Build.VERSION.SDK_INT >= 31) {
            blur(v, 2f * v.getResources().getDisplayMetrics().density);
        } else {
            v.setScaleX(1f);
            v.setScaleY(1f);
        }

        v.animate().alpha(0f)
                .scaleX(1.03f).scaleY(1.03f)
                .setDuration(half).setInterpolator(EASE_IN_OUT)
                .withEndAction(() -> {
                    applyNewContent.run();
                    v.setAlpha(0f);
                    v.animate().alpha(1f)
                            .scaleX(1f).scaleY(1f)
                            .setDuration(half).setInterpolator(EASE_IN_OUT)
                            // Clear the effect only when the motion is over. Clearing it at
                            // the midpoint would snap the incoming frame into focus the
                            // instant it appeared, which is the hard cut this exists to
                            // avoid, just moved half a beat later.
                            .withEndAction(() -> blur(v, 0f))
                            .start();
                }).start();
    }

    private static void blur(@NonNull View v, float radiusPx) {
        if (android.os.Build.VERSION.SDK_INT < 31) return;
        try {
            v.setRenderEffect(radiusPx <= 0f ? null
                    : android.graphics.RenderEffect.createBlurEffect(
                            radiusPx, radiusPx, android.graphics.Shader.TileMode.CLAMP));
        } catch (Throwable ignored) {
            // A view without a hardware layer cannot take a RenderEffect. The scale still
            // carries the swap, so there is nothing to fall back to and nothing to report.
        }
    }

    /**
     * Fade-and-rise a view in, optionally after a stagger.
     *
     * <p>Rise is 6dp, not 20: a big travel on a list of cards reads as the page assembling
     * itself, which is a thing to watch rather than a thing to use.</p>
     */
    public static void enter(@NonNull View v, int index) {
        if (reduced(v.getContext())) { v.setAlpha(1f); v.setTranslationY(0f); return; }
        float rise = 6f * v.getResources().getDisplayMetrics().density;
        v.setAlpha(0f);
        v.setTranslationY(rise);
        v.animate().alpha(1f).translationY(0f)
                .setStartDelay(index * STAGGER)
                .setDuration(HERO)
                .setInterpolator(EASE_OUT)
                .start();
    }

    /** An interruptible colour animation — retargets mid-flight rather than restarting. */
    @NonNull
    public static ValueAnimator colour(int from, int to, long durationMs) {
        ValueAnimator a = ValueAnimator.ofArgb(from, to);
        a.setDuration(durationMs);
        a.setInterpolator(EASE_OUT);
        return a;
    }
}
