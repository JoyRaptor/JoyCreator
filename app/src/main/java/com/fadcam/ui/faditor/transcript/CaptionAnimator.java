package com.fadcam.ui.faditor.transcript;

import androidx.annotation.NonNull;

/**
 * THE authority for caption/text animation: a pure {@code (style, mediaTime) -> transform}
 * function that BOTH the live preview ({@code CaptionOverlayView}) and the export renderer
 * ({@code CaptionExportRenderer}) call. No other code may derive an animation value.
 *
 * <h3>Why this class exists</h3>
 * The two renderers used to compute the same three animations independently, and the comment
 * in the export path said so out loud — {@code "Approximate the preview interpolators"}. The
 * easing arithmetic happened to match, but the two were driven by DIFFERENT CLOCKS:
 * <ul>
 *   <li>preview ran a 300ms {@code ValueAnimator} started on the word-change EVENT, so it
 *       advanced on WALL-CLOCK time;</li>
 *   <li>export computed {@code (sourceMs - wordStart) / 300}, i.e. MEDIA time.</li>
 * </ul>
 * They therefore disagreed whenever those two clocks disagree, which is most of the time that
 * matters: on a speed-adjusted clip a 300ms wall-clock animation is 150ms of output at 2x; a
 * paused preview kept animating while media time stood still; and a scrub re-triggered the
 * animator rather than showing the frame the export would produce. The user never sees this in
 * the editor — only in the finished file, which is the worst possible place to find it.
 *
 * <p><b>Media time wins.</b> The exported frame is ground truth, so preview is the one that
 * changes: it now asks this class what the animation looks like AT THE CURRENT PLAYHEAD, which
 * makes a paused preview correct by construction (frozen media time = frozen animation) and
 * makes scrubbing show the real exported frame.</p>
 *
 * <h3>Scope</h3>
 * Today this covers the three entrance animations that ship ({@link CaptionStyle.Anim}). It is
 * deliberately shaped for what {@code SPEC_TEXT_ANIMATION.md} needs next — per-unit animation at
 * LETTER / WORD / SENTENCE / BLOCK granularity, with in/out zones driven by the object's tape
 * handles — so those land on ONE evaluator instead of adding a third copy.
 * See {@link #unitProgress} for the in/out zone model.
 */
public final class CaptionAnimator {

    /** How long an entrance animation runs, in MEDIA milliseconds. */
    public static final long EMPHASIS_MS = 300L;

    private CaptionAnimator() {}

    /**
     * The per-unit transform. Fields are deliberately additive/multiplicative over whatever the
     * caller already has, so a preset composes with keyframed opacity/scale rather than
     * replacing it (SPEC_TEXT_ANIMATION "compose, don't replace").
     */
    public static final class Transform {
        /** Uniform scale about the unit's centre. 1 = unchanged. */
        public float scale = 1f;
        /** Vertical offset in px, applied before the scale. */
        public float dy = 0f;
        /** Alpha multiplier, 0..1. */
        public float alpha = 1f;
    }

    /**
     * Eased 0..1 progress of an entrance that began at {@code unitStartMs}, evaluated at
     * {@code mediaMs}. MAY EXCEED 1 for overshoot animations — that is the overshoot, and
     * clamping it here is what would flatten a POP into a fade.
     */
    public static float emphasis(@NonNull CaptionStyle style, long mediaMs, long unitStartMs) {
        float t = (mediaMs - unitStartMs) / (float) EMPHASIS_MS;
        t = Math.max(0f, Math.min(1f, t));
        return ease(style.anim, t);
    }

    /**
     * The easing curves, as pure math. These are the exact formulations of Android's
     * {@code DecelerateInterpolator()} and {@code OvershootInterpolator(2.2f)} — kept as
     * arithmetic rather than as interpolator instances so this class stays free of
     * {@code android.view} and can be exercised in the JVM harness alongside the model tests.
     */
    public static float ease(@NonNull CaptionStyle.Anim anim, float linear) {
        float t = Math.max(0f, Math.min(1f, linear));
        if (anim == CaptionStyle.Anim.ZOOM) {
            return 1f - (1f - t) * (1f - t); // DecelerateInterpolator(1.0)
        }
        float s = 2.2f;                       // OvershootInterpolator(2.2)
        float u = t - 1f;
        return u * u * ((s + 1f) * u + s) + 1f;
    }

    /**
     * The transform for one animating unit. {@code fontPx} scales the distance-based
     * animations so a bounce is proportional to the type size rather than a fixed pixel count.
     */
    @NonNull
    public static Transform transform(@NonNull CaptionStyle style, float eased, float fontPx) {
        Transform out = new Transform();
        switch (style.anim) {
            case ZOOM:
                out.scale = lerp(1.6f, 1.15f, eased);
                break;
            case BOUNCE:
                out.dy = -(1f - eased) * fontPx * 0.5f;
                out.scale = 1.15f;
                break;
            case POP:
            default:
                out.scale = 1.15f + (1f - eased) * 0.35f;
                break;
        }
        return out;
    }

    /** Convenience: the two steps above in one call, for a unit that began at {@code unitStartMs}. */
    @NonNull
    public static Transform transformAt(@NonNull CaptionStyle style, long mediaMs,
                                        long unitStartMs, float fontPx) {
        return transform(style, emphasis(style, mediaMs, unitStartMs), fontPx);
    }

    // ── Groundwork for SPEC_TEXT_ANIMATION (not yet wired to any UI) ──────────────────────

    /**
     * How the text is cut into independently-animating units. The user's framing (2026-07-28):
     * a whole paragraph flying in reads like a slide deck, single letters flying in read like a
     * cinematic title — same preset, different granularity, so granularity is a setting rather
     * than a property of the preset.
     */
    public enum Granularity {
        /** Every glyph animates on its own. The cost centre — per-glyph layout in both paths. */
        LETTER,
        /** Every word animates on its own. What ships today. */
        WORD,
        /** Each sentence animates as one body. */
        SENTENCE,
        /** The entire text animates as a single body. */
        BLOCK
    }

    /**
     * Progress of one unit, driven ENTIRELY by the object's own tape and its in/out handles —
     * the user's model, 2026-07-28:
     *
     * <blockquote>"the in/out carets show how long it will take to play in and how long, if at
     * all, to play out. If the carets are at the end, there will be no animation. If they are
     * brought all the way into the centre, everything animates in, and as soon as it's in it
     * starts animating out."</blockquote>
     *
     * So the handles are not a preset parameter — they ARE the timing, for every granularity.
     * Zero-length zones mean no animation, which is why the handles resting at the ends is the
     * natural "off" and needs no separate switch.
     *
     * @param mediaMs    current media time
     * @param itemStartMs / {@code itemEndMs} the object's own tape span
     * @param inZoneMs   length of the entrance zone (0 = no entrance)
     * @param outZoneMs  length of the exit zone (0 = no exit)
     * @param unitIndex  this unit's index within the object
     * @param unitCount  total units at the chosen {@link Granularity}
     * @return a signed progress: {@code [0..1]} climbing through the entrance, exactly 1 while
     *         fully on screen, and {@code [1..0]} falling back through the exit. One number the
     *         presets can drive everything from.
     */
    public static float unitProgress(long mediaMs, long itemStartMs, long itemEndMs,
                                     long inZoneMs, long outZoneMs,
                                     int unitIndex, int unitCount) {
        long span = Math.max(1L, itemEndMs - itemStartMs);
        long local = Math.max(0L, Math.min(mediaMs - itemStartMs, span));
        int count = Math.max(1, unitCount);
        int idx = Math.max(0, Math.min(unitIndex, count - 1));

        // Units are STAGGERED across their zone so the effect reads as a sweep rather than
        // every letter moving in lockstep. Each unit gets an equal slice of the zone and they
        // overlap by half a slice, which keeps the last unit finishing exactly at the zone end.
        if (inZoneMs > 0 && local < inZoneMs) {
            float slice = inZoneMs / (float) (count + 1);
            float unitStart = slice * idx;
            float p = (local - unitStart) / Math.max(1f, slice * 2f);
            return Math.max(0f, Math.min(1f, p));
        }
        if (outZoneMs > 0 && local > span - outZoneMs) {
            float into = local - (span - outZoneMs);
            float slice = outZoneMs / (float) (count + 1);
            float unitStart = slice * idx;
            float p = (into - unitStart) / Math.max(1f, slice * 2f);
            return 1f - Math.max(0f, Math.min(1f, p));
        }
        // Between the zones — or no zones at all, which is the handles-at-the-ends "off" state.
        return 1f;
    }

    private static float lerp(float from, float to, float t) {
        return from + (to - from) * Math.max(0f, Math.min(1f, t));
    }
}
