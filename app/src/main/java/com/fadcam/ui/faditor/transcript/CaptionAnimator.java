package com.fadcam.ui.faditor.transcript;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

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
     *
     * <p>The scale is split into X and Y because BEAM — "letters start 200% tall / 5% wide and
     * normalise" — is not expressible with a uniform one, and a preset that cannot be expressed
     * is a preset that grows its own private arithmetic somewhere else.</p>
     */
    public static final class Transform {
        /** Horizontal scale about the unit's centre. 1 = unchanged. */
        public float scaleX = 1f;
        /** Vertical scale about the unit's centre. 1 = unchanged. */
        public float scaleY = 1f;
        /** Horizontal offset in px, applied before the scale. */
        public float dx = 0f;
        /** Vertical offset in px, applied before the scale. */
        public float dy = 0f;
        /** Alpha multiplier, 0..1. */
        public float alpha = 1f;
        /**
         * Gaussian blur radius in px, 0 = sharp. GHOST is the only preset that sets it.
         *
         * <p><b>CONSUMED BY TEXT BOXES since 2026-07-30</b> ({@code TextBoxRenderer#drawUnit},
         * which both the preview and the export call), so GHOST really softens on a text box.
         * The trap it was held back for is real and was solved rather than dodged:
         * {@code BlurMaskFilter} is ignored on a hardware-accelerated canvas, so blurring the
         * preview needs {@code LAYER_TYPE_SOFTWARE} — {@code TextBoxView} switches to it only
         * for a preset that actually blurs, which is why the price is one box while it animates
         * rather than "every frame of playback" as first recorded. Measured at ~0.4ms per draw,
         * about 2.4% of a 16.7ms frame; see {@link #presetBlurs}.
         *
         * <p><b>Captions still ignore it</b> — {@code CaptionOverlayView} and
         * {@code CaptionExportRenderer} do not apply it, and the picker thumbnail still omits it.
         * The caption preview is ONE shared view for all words rather than a view per object, so
         * its cost profile is different and its decision is genuinely separate. Do not assume the
         * text-box answer settles it.</p>
         *
         * <p>Renderers that cannot blur must ignore this rather than approximate it.</p>
         */
        public float blurPx = 0f;

        /**
         * A glow radius in px that the PRESET supplies, drawn in the unit's own fill colour.
         * 0 = none, which is what every preset but {@link Preset#NEON_FLICKER} returns.
         *
         * <p><b>Why the preset supplies its own glow instead of modulating the object's.</b>
         * Stroke and glow are OPTIONAL per-object properties: {@code TextBoxRenderer.paintRun}
         * draws its glow pass only when the user set a radius and colour, and captions have no
         * per-object glow at all — {@code CaptionOverlayView} sets a fixed shadow from
         * {@code style.shadow} and there is nothing of the user's to scale. So a preset that
         * merely multiplied an existing glow would render as NOTHING on a default text box and
         * nothing on any caption, which is precisely the failure {@link Preset#implemented}
         * exists to prevent. A preset owns its feel and must not depend on unrelated user
         * styling to be visible.
         *
         * <p><b>It is a RADIUS, not a colour+radius pair, deliberately.</b> The colour is the
         * unit's own fill at the draw site, so the glow tracks a recoloured caption or text box
         * for free and cannot be left pointing at a stale colour. A neon tube glows the colour
         * it burns.
         *
         * <p><b>This does NOT hit the {@link #blurPx} problem.</b> That one needs a software
         * layer because {@code BlurMaskFilter} is ignored on a hardware canvas. This uses
         * {@code Paint.setShadowLayer}, which IS honoured for text on a hardware canvas —
         * confirmed on a Note 9 on 2026-07-30 by giving a text box a 30px magenta glow and
         * seeing the halo in the live preview. So one code path serves both surfaces with no
         * divergence and no {@code LAYER_TYPE_SOFTWARE}.
         *
         * <p>Renderers that cannot draw a glow must ignore this rather than approximate it.</p>
         */
        public float glowPx = 0f;

        /**
         * How much of the unit's own slot is REVEALED, 0..1, wiped in from the leading (left)
         * edge. 1 = the whole unit, i.e. no clip at all, which is what every preset but
         * {@link Preset#MASK_WIPE} returns — so this channel is inert for all of them.
         *
         * <p><b>Why this is a field on {@code Transform} rather than a fourth function.</b> It is
         * the THIRD output channel, after geometry/alpha and {@link #substituteUnit}'s string, and
         * like them it is per-unit and per-frame. Every renderer already has a {@code Transform}
         * in hand at the draw site, so folding it in here costs no new call and — more to the
         * point — makes it impossible for a surface to consume the transform and silently miss the
         * reveal. A separate function would have been a fourth thing four call sites must remember
         * to call.</p>
         *
         * <p><b>It is a FRACTION, not pixels, and that is load-bearing.</b> The four surfaces that
         * consume it measure their slot in four different units — preview caption pixels, export
         * frame pixels, a {@code TextView}'s measured width, and a 60dp thumbnail. A pixel radius
         * would mean four different wipes from one project; a fraction of whatever slot the caller
         * already measured is correct on all four by construction. Same reasoning as
         * {@code CompositingSpec.featherRadiusPx} being derived rather than stored.</p>
         *
         * <p>Callers turn it into a clip with {@link #revealClip}, which is shared precisely so
         * that "which edge, and how tall" cannot be answered differently by the preview and the
         * export. <b>A renderer that cannot clip must ignore this and NOT approximate it with
         * alpha</b> — a wipe faked as a fade is the preview/export divergence this class exists to
         * prevent, and it would also make MASK_WIPE indistinguishable from FADE.</p>
         */
        public float revealFrac = 1f;

        /** Set both axes at once — the common case. */
        void scale(float s) {
            scaleX = s;
            scaleY = s;
        }
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
        return anim == CaptionStyle.Anim.ZOOM ? decelerate(t) : overshoot(t);
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
                out.scale(lerp(1.6f, 1.15f, eased));
                break;
            case BOUNCE:
                out.dy = -(1f - eased) * fontPx * 0.5f;
                out.scale(1.15f);
                break;
            case POP:
            default:
                out.scale(1.15f + (1f - eased) * 0.35f);
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

    // ── Presets ──────────────────────────────────────────────────────────────────────────────

    /**
     * The animation presets from SPEC_TEXT_ANIMATION. Orthogonal to {@link Granularity}: one
     * preset at four granularities is four quite different effects, which is where most of the
     * expressive range comes from for very little code.
     *
     * <p><b>Not the same thing as {@link CaptionStyle.Anim}.</b> Those three (POP / ZOOM /
     * BOUNCE) are an ACTIVE-WORD EMPHASIS — they sit at 1.15x scale at rest, permanently
     * enlarging the spoken word relative to its neighbours. A preset here is an ENTRANCE/EXIT:
     * it resolves to identity at rest. Folding the two together would silently change how every
     * existing captioned project renders, so they stay separate vocabularies that compose.</p>
     *
     * <p>{@link #implemented} is what the picker filters on. The five unimplemented entries are
     * declared rather than omitted because each needs something a {@link Transform} cannot
     * express, and naming that requirement here is what stops the next person quietly inventing
     * a second evaluator for it. See {@link #unsupportedReason}.</p>
     */
    public enum Preset {
        /** Handles at the ends. The natural "off" — no zones, so nothing to evaluate. */
        NONE(true),
        /** The unit simply appears, fully formed, when its slot arrives. No easing. */
        TYPEWRITER(true),
        /** Opacity only. The safe default. */
        FADE(true),
        /** Slides up past the baseline and settles. The lower-third standard. */
        RISE(true),
        /** Fades in while sliding horizontally, shrinking and un-blurring — materialising out of smoke. */
        GHOST(true),
        /** Starts 200% tall / 5% wide and normalises, as if written in by a beam. */
        BEAM(true),
        /**
         * Ticks through nonsense characters and settles left-to-right. The one preset whose motion
         * is NOT a {@link Transform}: it animates WHICH CHARACTER is drawn, via
         * {@link #substituteUnit}, and deliberately leaves geometry and alpha at identity so the
         * churn is what the eye follows. Implemented 2026-07-30; it was the first of the five
         * declared-but-blocked presets, and its blocker was the missing substitution channel.
         */
        MATRIX(true),

        /**
         * Letters start displaced in their OWN direction and settle into place. Second of the five
         * declared-but-blocked presets, implemented 2026-07-29.
         *
         * <p>Unlike {@link #MATRIX} this needed no new output channel. Its blocker was recorded as
         * "per-glyph positional scatter", which reads like a missing per-glyph {@code Transform}
         * ARRAY — but at {@link Granularity#LETTER} every glyph is ALREADY its own unit with its own
         * {@code Transform} and its own progress, in both renderers. The one thing actually missing
         * was that {@link #presetTransform} could not see WHICH unit it was transforming, so every
         * glyph would have scattered along the same vector — a diagonal wipe, not a scatter. The fix
         * was therefore a parameter, not a channel.</p>
         *
         * <p><b>On a single-unit body it degrades to one directional slide</b> (see
         * {@link #presetTransform}), which is the honest result of scattering one thing rather than
         * an approximation.</p>
         */
        UNSCRAMBLE(true),

        /**
         * The unit is UNCOVERED left-to-right by a moving mask rather than faded in: full-strength
         * ink, partially present. Third of the five declared-but-blocked presets, implemented
         * 2026-07-30.
         *
         * <p>Its blocker note — "needs a per-unit clip rect" — was CORRECT, unlike
         * {@link #UNSCRAMBLE}'s. A clip is not geometry and not alpha, so no {@code Transform}
         * field then existing could express it. What the note did not say, and what re-deriving it
         * against the drawing loops showed, is that the clip is CHEAP at all four surfaces: the two
         * caption renderers already have {@code x}/{@code baseY}/{@code w} at the draw site inside
         * an existing {@code save()}/{@code restore()} bracket, and the two text-box surfaces
         * animate one whole body, which is one rect.</p>
         *
         * <p><b>It does NOT hit the text-box {@code TextView} wall that blocks
         * {@link #ODOMETER}</b>, and the difference is worth understanding rather than assuming.
         * ODOMETER needs TWO clipped glyph rows inside one slot, which one {@code TextView} holding
         * one string genuinely cannot draw. MASK_WIPE needs ONE clip over the whole body — and a
         * view can be clipped without being re-rendered, via {@code View.setClipBounds}. So the
         * wall is about drawing two things, not about clipping.</p>
         *
         * <p>Deliberately leaves geometry and alpha at identity, for the same reason
         * {@link #MATRIX} does: the reveal is the motion, and adding a fade on top would make it
         * read as FADE with extra steps.</p>
         */
        MASK_WIPE(true),

        // Declared, NOT implemented. Each needs more than a Transform:
        /** Needs GLYPH SUBSTITUTION plus a vertical roll clip per slot. */
        ODOMETER(false),

        /**
         * A tube striking: the unit stutters between lit and nearly-dark on an irregular but
         * DETERMINISTIC schedule, with a glow that swells as it strikes and settles away as the
         * tube steadies. Fourth of the five declared-but-blocked presets, implemented
         * 2026-07-30.
         *
         * <p>Its old blocker read "needs the renderer to modulate STROKE/GLOW, which is not a
         * geometric transform". True, and not the problem — see {@link Transform#glowPx}: the
         * real obstacle was that there is usually no stroke or glow to modulate, so the fix was
         * for the preset to SUPPLY one rather than for the renderers to scale one.
         *
         * <p>Both channels return to identity at {@code p = 1} — alpha to 1, glow to 0 — so the
         * unit lands exactly on its un-animated appearance and the zone boundary is continuous.
         * A residual glow would pop off the instant the in-zone ended.</p>
         */
        NEON_FLICKER(true);

        /** True when {@link #presetTransform} fully expresses this preset. */
        public final boolean implemented;

        Preset(boolean implemented) {
            this.implemented = implemented;
        }
    }

    /** Why an unimplemented preset cannot ship yet — one line, for the picker and for the log. */
    @NonNull
    public static String unsupportedReason(@NonNull Preset p) {
        switch (p) {
            // MATRIX is no longer here: substituteUnit is the channel it was waiting for.
            // UNSCRAMBLE is no longer here either: the unitIndex parameter on presetTransform was
            // the whole of what it needed. Its old reason read "needs per-glyph positional
            // scatter", which overstated the work — see the enum constant.
            // MASK_WIPE is no longer here either. Its reason ("needs a per-unit clip rect") was
            // ACCURATE about what was missing and misleading about the size of it: the clip rect
            // was genuinely a new channel, but Transform#revealFrac plus one shared revealClip
            // helper covered all four surfaces. A blocker note names a requirement, not a cost.
            // NEON_FLICKER is no longer here. Its reason ("needs stroke/glow modulation") named
            // the wrong obstacle: modulation was never the hard part, HAVING something to
            // modulate was — stroke and glow are optional per-object properties that a default
            // text box lacks and a caption has no per-object form of at all. The preset supplies
            // its own glow instead (Transform#glowPx), which is one new channel and four
            // consuming surfaces, the same shape MASK_WIPE's revealFrac took.
            case ODOMETER:    return "needs glyph substitution and a per-slot roll clip";
            default:          return "";
        }
    }

    /**
     * The transform for one unit of a preset at {@code progress}.
     *
     * <p>{@code progress} is the LINEAR number from {@link #unitProgress}: 0 = fully absent,
     * 1 = fully arrived, and on the way back down through the exit zone. Easing is applied HERE,
     * per preset, so a preset owns its own feel while the timing stays entirely the tape's.</p>
     *
     * <p><b>The exit is the entrance reversed</b>, which is not a shortcut but the user's stated
     * model — "everything animates in, and as soon as it's in it starts animating out". One
     * signed progress therefore drives both directions and there is no separate exit curve to
     * keep in agreement with the entrance one.</p>
     *
     * <p>An unimplemented preset returns identity rather than an approximation. A preset that
     * silently degrades to "something roughly like it" is how the preview and the export come
     * apart again.</p>
     *
     * @param fontPx type size, so distance-based motion is proportional to the text rather than
     *               a fixed pixel count that looks right at one size only
     */
    @NonNull
    public static Transform presetTransform(@NonNull Preset preset, float progress, float fontPx) {
        return presetTransform(preset, progress, fontPx, 0);
    }

    /**
     * The transform for one unit, told WHICH unit it is.
     *
     * <p>{@link Preset#UNSCRAMBLE} is the reason this overload exists: a scatter needs every glyph
     * to travel along its OWN vector, and a function that cannot tell one unit from another can
     * only produce a single shared direction — which is a diagonal wipe, not a scatter. Every other
     * preset ignores {@code unitIndex} entirely, so the three-argument form above stays exactly
     * correct for them and no existing caller had to change.</p>
     *
     * <p>The direction is a pure function of {@code unitIndex}, for the same reason
     * {@link #substituteUnit} is: the preview and the export must displace a given glyph the same
     * way, and {@code Math.random()} per frame would guarantee they do not. It is built from
     * {@link #mix} and {@code Math.sqrt} only — {@code sqrt} is the one transcendental-looking
     * operation IEEE 754 requires to be correctly rounded, so unlike {@code sin}/{@code cos} it is
     * bit-identical on every implementation. That is why the direction is drawn as a random vector
     * and normalised rather than as an angle put through trigonometry.</p>
     *
     * @param unitIndex the unit's index within its object. On a single-unit body (a text box, or
     *                  {@link Granularity#BLOCK}) this is always 0, so UNSCRAMBLE resolves to ONE
     *                  fixed direction and reads as a directional slide. That is the honest result
     *                  of scattering a single object, not a degraded approximation of the effect —
     *                  but it does mean UNSCRAMBLE only says what it means at LETTER granularity.
     */
    @NonNull
    public static Transform presetTransform(@NonNull Preset preset, float progress, float fontPx,
                                            int unitIndex) {
        float p = Math.max(0f, Math.min(1f, progress));
        Transform out = new Transform();
        if (!preset.implemented || preset == Preset.NONE) return out;
        switch (preset) {
            case TYPEWRITER:
                // Deliberately a step, not a ramp: a typewriter that fades is a fade.
                out.alpha = p > 0f ? 1f : 0f;
                break;
            case FADE:
                out.alpha = decelerate(p);
                break;
            case RISE: {
                float e = overshoot(p);
                out.dy = (1f - e) * fontPx * 0.9f;
                out.alpha = Math.min(1f, p * 2f); // opaque well before it settles
                break;
            }
            case NEON_FLICKER: {
                // A tube striking. Closed-form in p and unitIdx — no clock, no RNG state — so
                // preview, export and thumbnail all compute the identical frame, which is the
                // rule the whole class exists to keep ("PREDICT, THEN LOOK").
                //
                // settle rises quadratically so the stutter is concentrated EARLY and the last
                // third is essentially steady; a uniformly random flicker reads as a fault
                // rather than as a tube warming up.
                float settle = p * p;
                int slot = (int) (p * NEON_SLOTS);
                // 0..1 from the shared integer mix, keyed by unit so adjacent glyphs strike out
                // of step with each other rather than blinking in unison.
                float h = Math.floorMod(mix(unitIndex, slot, NEON_SALT), 1000) / 1000f;
                boolean lit = h < 0.30f + 0.70f * settle;
                // Dropouts get shallower as it settles, reaching exactly 1 at p = 1.
                out.alpha = lit ? 1f : 0.18f + 0.82f * settle;
                // The glow swells and dies within the zone: 0 at both ends, peak mid-strike.
                // Ending at 0 is what makes the zone boundary continuous.
                float envelope = 4f * p * (1f - p);
                out.glowPx = (lit ? 1f : 0.35f) * fontPx * 0.45f * envelope;
                break;
            }
            case GHOST: {
                float e = decelerate(p);
                out.dx = (1f - e) * fontPx * 0.35f;
                out.scale(lerp(1.18f, 1f, e));
                out.blurPx = (1f - e) * fontPx * 0.18f;
                out.alpha = e;
                break;
            }
            case BEAM: {
                float e = decelerate(p);
                out.scaleX = lerp(0.05f, 1f, e);
                out.scaleY = lerp(2.0f, 1f, e);
                out.alpha = Math.min(1f, p * 3f);
                break;
            }
            case UNSCRAMBLE: {
                // Every unit travels the SAME distance and differs only in DIRECTION. That is what
                // separates a scatter from per-glyph noise: the text reads as one body coming
                // together, rather than as letters arriving from arbitrary depths.
                float e = decelerate(p);
                float r = (1f - e) * fontPx * SCATTER_RADIUS;
                float ux = scatterAxis(unitIndex, SCATTER_SALT_X);
                float uy = scatterAxis(unitIndex, SCATTER_SALT_Y);
                float len = (float) Math.sqrt(ux * ux + uy * uy);
                if (len < 1e-4f) {   // the vanishingly rare near-origin draw, which has no direction
                    ux = 1f;
                    uy = 0f;
                    len = 1f;
                }
                out.dx = ux / len * r;
                out.dy = uy / len * r;
                // Opaque well before it lands, like RISE: a glyph that is still fading while it is
                // still moving reads as GHOST, and the two would stop being distinguishable.
                out.alpha = Math.min(1f, p * 2.5f);
                break;
            }
            case MASK_WIPE:
                // Geometry and alpha stay at IDENTITY on purpose, exactly as MATRIX does. The ink
                // is at full strength from the first frame and simply is not there yet to the
                // right of the mask edge, which is what separates a wipe from a fade. Easing is
                // `decelerate` for the same reason FADE/GHOST/BEAM use it — the mask edge arrives
                // and settles rather than stopping dead.
                out.revealFrac = decelerate(p);
                break;
            default:
                break;
        }
        return out;
    }

    /** Convenience: {@link #unitProgress} straight into {@link #presetTransform}. */
    @NonNull
    public static Transform presetTransformAt(@NonNull Preset preset, long mediaMs,
                                              long itemStartMs, long itemEndMs,
                                              long inZoneMs, long outZoneMs,
                                              int unitIndex, int unitCount, float fontPx) {
        float p = unitProgress(mediaMs, itemStartMs, itemEndMs, inZoneMs, outZoneMs,
                unitIndex, unitCount);
        return presetTransform(preset, p, fontPx, unitIndex);
    }

    // ── Third output channel: HOW MUCH of the slot is uncovered ──────────────────────────────

    /**
     * How far above and below the baseline a caption's reveal mask must reach, as a multiple of
     * the type size.
     *
     * <p>The mask clips HORIZONTALLY only — vertically it must contain every pixel the unit could
     * paint, or a wipe would silently crop tall glyphs and descenders and read as a defect rather
     * than an effect. These are deliberately generous: the tallest thing a unit draws is its
     * ascent (~0.8em) multiplied by the active-word emphasis (1.15x) and by BEAM's 2.0x vertical
     * scale if the two ever compose, plus an outline stroke (0.08em) and a shadow (0.10em offset
     * 0.04em). 2em up and 1em down clears all of that with room to spare, and costs nothing —
     * an over-tall mask clips nothing, while an under-tall one clips ink.
     */
    private static final float REVEAL_ABOVE_EM = 2.0f;
    private static final float REVEAL_BELOW_EM = 1.0f;

    /**
     * The clip rectangle for a unit that is {@code revealFrac} uncovered — the geometry half of
     * {@link Transform#revealFrac}, in ONE place so the four surfaces that consume the channel
     * cannot disagree about which edge the mask sweeps from or how tall it is.
     *
     * <p>Written into a caller-supplied array rather than returned, because at LETTER granularity
     * this runs once per glyph per frame and a returned {@code float[]} would be ~1800
     * allocations a second on a captioned line. The array is {@code {left, top, right, bottom}} —
     * a {@code RectF} would drag {@code android.graphics} into a class that is deliberately
     * android-free so the JVM harness can exercise it.</p>
     *
     * <p>The mask sweeps from the LEFT edge, i.e. reading order. That is a v1 decision, not a
     * limitation of the channel: a direction control would be a second setting on a picker whose
     * whole design is one tap, and text that uncovers against its reading direction reads as an
     * exit rather than an entrance. The EXIT needs no separate rule — progress falls back through
     * the same number, so the mask retreats the way it came, which is the "exit is the entrance
     * reversed" model every other preset here follows.</p>
     *
     * @param x       the slot's left edge, in whatever units the caller measured it in
     * @param baseY   the text baseline
     * @param w       the slot's advance width, measured from the REAL text — the same width
     *                {@link #substituteUnit} relies on being untouched
     * @param fontPx  type size, which sets the mask's vertical reach
     * @param out     a length-4 array to fill with {@code {left, top, right, bottom}}
     */
    public static void revealClip(float x, float baseY, float w, float fontPx,
                                  float revealFrac, @NonNull float[] out) {
        float f = Math.max(0f, Math.min(1f, revealFrac));
        out[0] = x;
        out[1] = baseY - fontPx * REVEAL_ABOVE_EM;
        out[2] = x + w * f;
        out[3] = baseY + fontPx * REVEAL_BELOW_EM;
    }

    /**
     * Whether a unit at {@code revealFrac} is worth drawing at all.
     *
     * <p>MASK_WIPE keeps alpha at 1, so the {@code alpha <= 0.004f} skip both caption renderers
     * already have never fires for it — without this a fully-masked unit would be laid out,
     * transformed and drawn into an empty clip on every frame of its entrance. Kept next to
     * {@link #revealClip} so the two halves of the channel are read together.</p>
     */
    public static boolean revealDrawsAnything(float revealFrac) {
        return revealFrac > 0.0005f;
    }

    /**
     * Whether this preset ever produces a non-zero {@link Transform#blurPx}.
     *
     * <p>A surface that wants to honour the blur must render through a SOFTWARE canvas —
     * {@code BlurMaskFilter} is ignored on a hardware-accelerated one — and that has a price, so
     * a surface needs to know in advance whether to pay it. Answering here rather than at the
     * call sites keeps it with the switch that assigns {@code blurPx}: add a blurring preset and
     * this is in the same file, three screens away, instead of in a view that never mentions it.
     *
     * <p><b>Measured, 2026-07-30, so the price is not guesswork.</b> On a Note 9, forcing
     * {@code LAYER_TYPE_SOFTWARE} on a text box took its {@code onDraw} from a median 193.5us to
     * 591.0us on a 1041x564 box (+205%), and 201.0us to 566.0us on a 1080x1031 one (+182%);
     * n = 54-62 windows of 30 draws per arm, same build, layer type chosen from a flag file so
     * build variance could not contaminate the delta. Large in RELATIVE terms and about
     * <b>0.4ms in absolute terms — roughly 2.4% of a 16.7ms frame, per animating box</b>, which
     * is why the blur is drawn in the preview rather than being an export-only divergence.
     * The figure is a LOWER BOUND: it times the inside of {@code onDraw} and so excludes the
     * layer's own bitmap allocation and upload, which happen outside it.
     */
    public static boolean presetBlurs(@Nullable Preset preset) {
        return preset == Preset.GHOST;
    }

    // ── Second output channel: WHICH CHARACTERS to draw ──────────────────────────────────────

    /**
     * The alphabet MATRIX churns through: <b>HALFWIDTH katakana plus digits</b> — the film's actual
     * look, and the reason the film used halfwidth too.
     *
     * <p><b>This replaced a plain-ASCII set, whose stated reason turned out to be wrong.</b> The old
     * comment said katakana "would render as tofu in most of" the caption fonts. It would not: all
     * six families {@code CaptionStyle.typeface()} can return are SYSTEM families
     * ({@code SANS_SERIF}, {@code SERIF}, {@code MONOSPACE}, {@code sans-serif-condensed/medium/
     * light}), so they all resolve through Android's font-FALLBACK chain rather than through one
     * file. Verified on the Note 9: {@code /system/fonts} carries {@code SECCJK-Regular.ttc} and
     * {@code NotoSerifCJK-Regular.ttc}, and {@code fonts.xml} lists the latter with
     * {@code fallbackFor="serif"} — so both the sans and the serif branches are covered. A custom
     * asset font would have been the tofu risk; there is not one.
     *
     * <p><b>HALFWIDTH (U+FF66…U+FF9D) rather than fullwidth (U+30A2…) is load-bearing, not taste.</b>
     * The layout-safety property in {@link #substituteUnit} is that the slot width was measured from
     * the REAL text and is reused untouched. Fullwidth katakana are about twice the advance of a
     * Latin letter, so they would paint well outside the slot they were given and collide with the
     * next unit — length would be preserved while the ink stopped fitting. Halfwidth forms sit in
     * the same column as Latin, which keeps that property honest.
     *
     * <p>The range deliberately stops at U+FF9D and excludes U+FF9E/U+FF9F, the halfwidth voiced and
     * semi-voiced sound marks. Those are combining marks: they would attach to the preceding glyph
     * instead of filling their own slot, which is precisely the reflow the same-length rule exists
     * to prevent.
     *
     * <p>Built from a range of unicode ESCAPES rather than written out as literal katakana. That is
     * not fussiness: the JVM harness compiles with the platform default encoding (windows-1252
     * here) while the file is stored as UTF-8, so a literal non-ASCII character inside a CHAR OR
     * STRING LITERAL decodes as several wrong characters and the build breaks. {@code
     * SENTENCE_TRAILERS} already carries that scar. Escapes are resolved by the lexer before
     * encoding matters, so they are immune.
     *
     * <p>Note the limit of that rule, since it is easy to over-apply — and this javadoc first got
     * it wrong: the surrounding COMMENTS in this file are full of em dashes and have always
     * compiled fine. Mojibake in a comment changes nothing. It is only LITERALS that must stay
     * ASCII.
     *
     * <p><b>Known limit, stated rather than hidden:</b> a device with no CJK font in its fallback
     * chain at all (a stripped or Go-edition ROM) would draw tofu. The digits are kept in the set
     * partly so such a device still shows something recognisable. If that is ever reported, the fix
     * is a {@code Paint.hasGlyph} probe in ONE shared helper called by both renderers — never two
     * probes, or the preview and the export could pick different alphabets from the same project.
     */
    private static final char[] MATRIX_GLYPHS = buildMatrixGlyphs();

    /**
     * The churn alphabet, deliberately WEIGHTED towards ASCII.
     *
     * <p>User direction, 2026-07-30: <i>"it should have a higher ratio of english letters and
     * numbers in the nonsense pool so it is clear we're not trying to spell anything in any
     * specific language"</i>, plus an explicit request for the symbol run below. The original pool
     * was 56 katakana against 10 digits \u2014 85% katakana \u2014 which read as scrambled JAPANESE rather
     * than as machine noise. Weighting is done by listing the ASCII block twice rather than by
     * thinning the katakana, so the katakana range stays intact (the harness pins that it is fully
     * reached, that no fullwidth leaked in, and that the combining marks are excluded) while its
     * share of the pool drops to roughly a quarter.</p>
     *
     * <p>Every added character is ASCII in the SOURCE, which matters here: a literal non-ASCII
     * character in a Java string breaks the windows-1252 harness build, so the katakana stays as
     * {@code \\uXXXX} escapes \u2014 javac resolves those in the lexer regardless of file encoding.</p>
     *
     * <p>Layout safety is unaffected: every glyph is one char, the returned string keeps the input
     * length, and both renderers measure the slot from the REAL text.</p>
     */
    private static char[] buildMatrixGlyphs() {
        StringBuilder sb = new StringBuilder();
        // U+FF66 HALFWIDTH KATAKANA LETTER WO .. U+FF9D HALFWIDTH KATAKANA LETTER N.
        for (char c = '\uFF66'; c <= '\uFF9D'; c++) sb.append(c);
        // Listed TWICE \u2014 this is the weighting, and it is the whole point of the block above.
        for (int rep = 0; rep < MATRIX_ASCII_WEIGHT; rep++) sb.append(MATRIX_ASCII_GLYPHS);
        return sb.toString().toCharArray();
    }

    /**
     * The ASCII half of the churn alphabet. Letters and digits carry the "this is not a language"
     * reading; the symbol run is the user's own list and is what makes it read as code rather than
     * as a failed font.
     */
    private static final String MATRIX_ASCII_GLYPHS =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789*&^%$#@!{}?<>";

    /** How many times {@link #MATRIX_ASCII_GLYPHS} is repeated in the pool \u2014 see the builder. */
    private static final int MATRIX_ASCII_WEIGHT = 2;

    /**
     * How many discrete churn steps a unit passes through across its zone. Quantising is not a
     * cost saving — see {@link #substituteUnit} for why it is what keeps the preview and the
     * export showing the same characters.
     */
    private static final int MATRIX_TICKS = 12;

    /**
     * The shortest and longest a single character churns before settling, as a fraction of its
     * unit's progress. The SPREAD between them is the point: with one fixed duration every
     * character resolves a fixed distance behind the one before it, which reads as a mechanical
     * wipe rather than as code resolving.
     *
     * <p>~0.18–0.42 puts roughly two to four characters mid-churn at any instant on a ten-character
     * title, which is the band the effect is named for.</p>
     */
    private static final float MATRIX_CHURN_MIN = 0.18f;
    private static final float MATRIX_CHURN_MAX = 0.42f;

    /** Keeps the per-character churn DURATION independent of the per-tick glyph CHOICE. */
    private static final int MATRIX_JITTER_SALT = 0x3A7C19;

    /**
     * Which characters a unit should DRAW at {@code progress} — the second thing a preset can
     * animate, alongside {@link #presetTransform}.
     *
     * <p><b>Why this cannot be a {@code Transform}.</b> A {@code Transform} is geometry and alpha.
     * MATRIX is glyph substitution: the ink itself is a different character early on. There is no
     * scale, offset or opacity that expresses "show a Q where the P will be", so a preset like this
     * needs its own channel or it cannot exist. This is exactly what
     * {@code unsupportedReason(MATRIX)} used to name as the blocker.</p>
     *
     * <p><b>Layout is NOT affected, by construction.</b> Both renderers measure a unit's advance
     * from the REAL text and then draw into that slot, so replacing the ink leaves x-positions
     * untouched. Substituting a narrow character for a wide one therefore cannot reflow or jitter
     * the line — the returned string is always the SAME LENGTH as the input, and callers keep using
     * the width they already measured.</p>
     *
     * <p><b>Why it is deterministic, and why that is the whole design.</b> The obvious
     * implementation reaches for {@code Math.random()} per frame. That would make the preview and
     * the export draw different characters from the same project — the precise divergence this
     * class exists to prevent, and one no frame-diff of the preview alone would ever catch. So the
     * churn is a pure function of (unit index, character position, tick): same inputs, same
     * characters, on any surface, in any process, on any run.</p>
     *
     * <p>Quantising progress into {@link #MATRIX_TICKS} steps also makes this MORE robust than the
     * alpha channel, not less. The preview samples at whatever time a frame lands while the export
     * samples exact frame times; for alpha those two produce slightly different numbers, but for
     * substitution they land on the same tick and produce identical characters unless they straddle
     * a tick boundary.</p>
     *
     * <p><b>The message READS ACROSS; it does not start as a full body of nonsense.</b> Each
     * character has its own three-stage life: BLANK before it arrives, then churning glyphs, then
     * its real letter. Reveal times are strictly increasing left-to-right
     * ({@link #matrixRevealAt}) while churn durations vary per character
     * ({@link #matrixChurnFor}), so at any instant there is settled text on the left, a band of two
     * to four characters churning, and nothing yet on the right — the gradient the film's effect is
     * actually made of. On the way out progress falls, so it un-resolves right-to-left: the exit is
     * the entrance reversed, the same rule {@link #presetTransform} follows.</p>
     *
     * <p><b>This replaced a version that drew every character as a glyph from progress 0</b>, so
     * the whole body appeared as nonsense at once and then resolved left-to-right in place. That
     * is a legible effect but it is not this one, and the user rejected it on sight: the leading
     * edge is what makes it read as a message arriving rather than as a block of noise clearing.
     * The blank is a SPACE, so the returned string is still the same length and the layout-safety
     * property below is untouched.</p>
     *
     * <p>Whitespace is never substituted. A space carries no ink, and scrambling it would put a
     * glyph where the layout promised a gap.</p>
     *
     * @param unitIndex the unit's index within its object, so two identical letters in different
     *                  slots churn differently instead of in lockstep
     * @return a string of the same length as {@code text}; {@code text} itself for every preset
     *         that does not substitute, so no existing preset can change behaviour
     */
    @NonNull
    public static String substituteUnit(@NonNull Preset preset, @NonNull String text,
                                        float progress, int unitIndex) {
        if (preset != Preset.MATRIX || text.isEmpty()) return text;
        float p = Math.max(0f, Math.min(1f, progress));
        if (p >= 1f) return text; // settled: the real text, exactly
        int len = text.length();
        int tick = (int) (p * MATRIX_TICKS);
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) {
                sb.append(c);
                continue;
            }
            float reveal = matrixRevealAt(i, len);
            if (p < reveal) {
                sb.append(' ');                 // has not arrived yet — the leading edge
            } else if (p >= reveal + matrixChurnFor(unitIndex, i)) {
                sb.append(c);                   // settled on its real character
            } else {
                sb.append(MATRIX_GLYPHS[Math.floorMod(mix(unitIndex, i, tick),
                        MATRIX_GLYPHS.length)]);
            }
        }
        return sb.toString();
    }

    /**
     * When character {@code i} of a unit of length {@code len} first appears, as a fraction of the
     * unit's progress.
     *
     * <p><b>Strictly increasing in {@code i}, and that is a requirement rather than a
     * side-effect.</b> An earlier draft jittered the reveal time as well as the churn duration,
     * which made the reveal non-monotonic and opened HOLES in the middle of the message — a
     * character further right appearing while one to its left was still blank. It reads as dropped
     * text, not as a wave. So the jitter lives entirely in {@link #matrixChurnFor}: every character
     * arrives in reading order, and only how long it churns varies.</p>
     *
     * <p>The spread is scaled by {@code 1 - MATRIX_CHURN_MAX} so that the LAST character's churn
     * still fits inside the zone: its reveal is at {@code 1 - MATRIX_CHURN_MAX} and its resolve is
     * therefore at most exactly 1. Without that scaling the tail characters all clamp to 1 and snap
     * to their real letters together on the final frame, which is the one moment the effect must
     * not draw attention to itself.</p>
     */
    private static float matrixRevealAt(int i, int len) {
        return len <= 1 ? 0f : (i / (float) (len - 1)) * (1f - MATRIX_CHURN_MAX);
    }

    /**
     * How long character {@code i} spends churning before it settles, as a fraction of the unit's
     * progress — the "different letters take different times to resolve" half of the effect.
     *
     * <p>Deterministic for the same reason the glyph choice is: {@code Math.random()} here would
     * make the preview and the export resolve the same letter at different moments from one
     * project. Keyed on the unit index too, so the same letter in two slots does not share a
     * schedule.</p>
     */
    private static float matrixChurnFor(int unitIndex, int i) {
        float jitter = Math.floorMod(mix(unitIndex, i, MATRIX_JITTER_SALT), 1000) / 1000f;
        return MATRIX_CHURN_MIN + (MATRIX_CHURN_MAX - MATRIX_CHURN_MIN) * jitter;
    }

    /**
     * A small integer avalanche. Hand-rolled rather than {@code java.util.Random} so the result is
     * fixed by the arithmetic alone — no seeding, no instance state, nothing that could differ
     * between the preview process and an export pass.
     */
    /**
     * How far a scattered glyph starts from its home, as a multiple of the type size. Font-relative
     * for the same reason every other distance here is: a fixed pixel count that looks right on a
     * caption is invisible on a title card.
     */
    private static final float SCATTER_RADIUS = 1.6f;

    /**
     * Two arbitrary constants that make the X and Y draws independent. They must simply DIFFER;
     * with one salt both axes would return the same number and every glyph would sit on the
     * 45-degree diagonal. Non-zero so that unit 0 — the only unit a single-unit body has — does not
     * fall out of {@link #mix}(0,0,0) as a degenerate zero vector.
     */
    private static final int SCATTER_SALT_X = 0x5CA77E7;
    private static final int SCATTER_SALT_Y = 0x1D1EC70;

    /** How many flicker slots NEON_FLICKER cuts its entrance into. */
    private static final int NEON_SLOTS = 14;
    /** Keeps NEON_FLICKER's hash from colliding with UNSCRAMBLE's scatter for the same unit. */
    private static final int NEON_SALT = 0x4E30F1;

    /** One axis of a unit's scatter direction, in {@code [-1, 1]}. */
    private static float scatterAxis(int unitIndex, int salt) {
        return (Math.floorMod(mix(unitIndex, salt, 0), 2001) - 1000) / 1000f;
    }

    private static int mix(int a, int b, int c) {
        int h = a * 0x27D4EB2D ^ b * 0x165667B1 ^ c * 0x9E3779B1;
        h ^= (h >>> 15);
        h *= 0x85EBCA6B;
        h ^= (h >>> 13);
        return h;
    }

    /**
     * The whole-body transform for a TEXT BOX at {@code mediaMs} — one call, from the four
     * values the box stores to the transform both renderers apply.
     *
     * <p>This exists so the preview (which transforms a {@code TextView}) and the export (which
     * transforms a rasterised {@code Bitmap}) cannot drift: they are two very different drawing
     * surfaces, and the ONLY thing keeping them agreeing is that neither computes anything. Both
     * call this and apply the result. The original §3g defect was exactly two implementations of
     * the same animation, so this is the rule that stops it recurring on the text-box side.</p>
     *
     * <p>BLOCK by construction — {@code unitCount = 1}, so there is no stagger and no per-unit
     * index. See {@code TextOverlayItem.textAnimGranularitySupported} for why a text box gets
     * only that granularity today.</p>
     *
     * @param spanMs the box's own visible span, already resolved against the timeline for an
     *               open-ended overlay ({@code TextOverlayItem.animSpanMs}). 0 ⇒ identity.
     */
    @NonNull
    public static Transform textBoxTransformAt(@NonNull Preset preset, long mediaMs,
                                               long startMs, long spanMs,
                                               float inPct, float outPct, float fontPx) {
        if (spanMs <= 0L) return new Transform();
        long inZone = zoneForSpan(inPct, spanMs);
        long outZone = zoneForSpan(outPct, spanMs);
        if (inZone <= 0L && outZone <= 0L) return new Transform();
        return presetTransformAt(preset, mediaMs, startMs, startMs + spanMs,
                inZone, outZone, 0, 1, fontPx);
    }

    /**
     * A text box's own progress at {@code mediaMs} — the same number {@link #textBoxTransformAt}
     * evaluates internally, exposed because the SUBSTITUTION channel needs it too and neither
     * surface may compute it for itself.
     *
     * <p>Returns 1 (fully arrived, nothing to animate) for the off states — no span, or both zones
     * at zero — so a caller can hand the result straight to {@link #substituteUnit} and get the
     * real text back.</p>
     */
    public static float textBoxProgressAt(long mediaMs, long startMs, long spanMs,
                                          float inPct, float outPct) {
        if (spanMs <= 0L) return 1f;
        long inZone = zoneForSpan(inPct, spanMs);
        long outZone = zoneForSpan(outPct, spanMs);
        if (inZone <= 0L && outZone <= 0L) return 1f;
        return unitProgress(mediaMs, startMs, startMs + spanMs, inZone, outZone, 0, 1);
    }

    /**
     * What a TEXT BOX should draw at {@code mediaMs} — one call, so the preview's {@code TextView}
     * and the export's rasterised {@code Bitmap} cannot substitute differently.
     *
     * <p>A text box is BLOCK by construction (one unit), so the whole string settles
     * left-to-right as its entrance runs. Returns {@code text} unchanged for every preset that
     * does not substitute, which is every preset but MATRIX.</p>
     */
    @NonNull
    public static String textBoxTextAt(@NonNull Preset preset, @NonNull String text, long mediaMs,
                                       long startMs, long spanMs, float inPct, float outPct) {
        if (preset != Preset.MATRIX) return text;
        return substituteUnit(preset, text,
                textBoxProgressAt(mediaMs, startMs, spanMs, inPct, outPct), 0);
    }

    // ── Unit splitting ───────────────────────────────────────────────────────────────────────

    /**
     * Cut {@code text} into the independently-animating units of a {@link Granularity}, as
     * {@code [startOffset, endOffset)} character ranges into the ORIGINAL string — offsets rather
     * than substrings so a renderer can lay the text out once and animate slices of that layout,
     * which is what makes LETTER affordable.
     *
     * <p>Whitespace between units is never itself a unit; it belongs to no one and is drawn
     * unanimated. LTR only for v1, per the spec — no grapheme clustering, so a combining mark
     * animates as its own letter.</p>
     */
    @NonNull
    public static int[][] splitUnits(@NonNull String text, @NonNull Granularity g) {
        int n = text.length();
        if (n == 0) return new int[0][];
        if (g == Granularity.BLOCK) return new int[][]{{0, n}};

        java.util.List<int[]> out = new java.util.ArrayList<>();
        if (g == Granularity.LETTER) {
            for (int i = 0; i < n; i++) {
                if (!Character.isWhitespace(text.charAt(i))) out.add(new int[]{i, i + 1});
            }
        } else if (g == Granularity.WORD) {
            int start = -1;
            for (int i = 0; i < n; i++) {
                boolean ws = Character.isWhitespace(text.charAt(i));
                if (!ws && start < 0) start = i;
                if (ws && start >= 0) {
                    out.add(new int[]{start, i});
                    start = -1;
                }
            }
            if (start >= 0) out.add(new int[]{start, n});
        } else { // SENTENCE
            int start = -1;
            for (int i = 0; i < n; i++) {
                char c = text.charAt(i);
                if (start < 0 && !Character.isWhitespace(c)) start = i;
                if (start >= 0 && (c == '.' || c == '!' || c == '?' || c == '\n')) {
                    // Absorb a run of trailing terminators so "?!" is one sentence, not two.
                    int end = i + 1;
                    while (end < n && (text.charAt(end) == '.' || text.charAt(end) == '!'
                            || text.charAt(end) == '?')) {
                        end++;
                    }
                    out.add(new int[]{start, end});
                    start = -1;
                    i = end - 1;
                }
            }
            if (start >= 0) out.add(new int[]{start, n});
        }
        return out.toArray(new int[0][]);
    }

    // ── Units within a caption phrase ────────────────────────────────────────────────────────

    /**
     * How many units a phrase's visible words make up at {@code g}.
     *
     * <p>Captions arrive as a list of already-separated words rather than as one string, so this
     * is the word-list twin of {@link #splitUnits}. Both must agree about what a unit is, which
     * is why they live next to each other.</p>
     */
    public static int unitCount(@NonNull java.util.List<String> words, @NonNull Granularity g) {
        if (words.isEmpty()) return 1;
        switch (g) {
            case BLOCK:    return 1;
            case WORD:     return words.size();
            case SENTENCE: return sentenceIndexOf(words, words.size() - 1) + 1;
            case LETTER: {
                int n = 0;
                for (String w : words) n += w.length();
                return Math.max(1, n);
            }
            default:       return words.size();
        }
    }

    /**
     * Which unit a given character of a given word belongs to.
     *
     * @param charIdxInWord ignored for every granularity except LETTER, so a renderer that
     *                      draws whole words can pass 0 and get the right answer
     */
    public static int unitIndexOf(@NonNull java.util.List<String> words, @NonNull Granularity g,
                                  int wordIdx, int charIdxInWord) {
        if (words.isEmpty()) return 0;
        int wi = Math.max(0, Math.min(wordIdx, words.size() - 1));
        switch (g) {
            case BLOCK:    return 0;
            case WORD:     return wi;
            case SENTENCE: return sentenceIndexOf(words, wi);
            case LETTER: {
                int n = 0;
                for (int i = 0; i < wi; i++) n += words.get(i).length();
                return n + Math.max(0, Math.min(charIdxInWord,
                        Math.max(0, words.get(wi).length() - 1)));
            }
            default:       return wi;
        }
    }

    /** Sentence ordinal of {@code wordIdx}, counting terminators on the END of a word. */
    private static int sentenceIndexOf(@NonNull java.util.List<String> words, int wordIdx) {
        int s = 0;
        for (int i = 0; i < wordIdx; i++) {
            if (endsSentence(words.get(i))) s++;
        }
        return s;
    }

    /**
     * Characters that may sit AFTER a sentence terminator without cancelling it, as a string
     * rather than a chain of char literals: the JVM harness compiles with the platform default
     * encoding (windows-1252 here), where a literal curly quote fails to build. The two curly
     * quotes are therefore written as escapes.
     */
    private static final String SENTENCE_TRAILERS = "\"')]" + (char) 0x201D + (char) 0x2019;

    private static boolean endsSentence(@NonNull String w) {
        for (int i = w.length() - 1; i >= 0; i--) {
            char c = w.charAt(i);
            if (c == '.' || c == '!' || c == '?') return true;
            // Trailing quotes and brackets are skipped so a quoted sentence still
            // terminates. The curly quotes are compared BY CODE POINT rather than written as
            // literals: the JVM harness compiles with the platform default encoding
            // (windows-1252 here) and a literal curly quote fails that build.
            if (SENTENCE_TRAILERS.indexOf(c) >= 0) continue;
            return false;
        }
        return false;
    }

    /**
     * The largest fraction of a line either zone may occupy. At {@code 0.5} the entrance ends
     * exactly where the exit begins — the user's own description of full travel — so this is the
     * MODEL, not a safety rail: there is no defined behaviour past it to protect.
     */
    public static final float MAX_ZONE_PCT = 0.5f;

    /** Clamp a stored zone fraction into {@code [0, MAX_ZONE_PCT]}. NaN collapses to 0. */
    public static float clampZonePct(float pct) {
        if (Float.isNaN(pct) || pct <= 0f) return 0f;
        return Math.min(MAX_ZONE_PCT, pct);
    }

    /**
     * The in/out zone in ms actually usable on an animating object of {@code spanMs}.
     *
     * <p>The zones are stored as a FRACTION OF EACH LINE, not as a duration. That is the whole
     * point of the fraction: the ms form had to be held in SOURCE ms by hand so a speed-adjusted
     * clip would not animate over the wrong span — the exact mistake LEDGER §3g originally was.
     * <b>A fraction has no units, so it is correct in both bases by construction</b> and there is
     * nothing left to get wrong. It also solves the authoring problem it was changed for: one
     * value set once applies to every caption line as a share of that line's own duration, rather
     * than a duration that means "most of the line" on a short phrase and "a flicker" on a long
     * one.</p>
     *
     * <p>Capping at {@link #MAX_ZONE_PCT} keeps the user's stated property true at every phrase
     * length: at the cap the entrance ends exactly where the exit begins, which is "everything
     * animates in, and as soon as it's in it starts animating out".</p>
     */
    public static long zoneForSpan(float storedZonePct, long spanMs) {
        if (spanMs <= 0) return 0L;
        float pct = clampZonePct(storedZonePct);
        if (pct <= 0f) return 0L;
        // The floor cap is not redundant with clampZonePct. Rounding half an ODD span up gives
        // (span+1)/2, so two such zones would sum to one millisecond MORE than the line they sit
        // on — a one-frame overlap that unitProgress has no defined answer for. Flooring keeps
        // "in + out never exceeds the span" exact at every length, which is the invariant the cap
        // exists to hold.
        return Math.min(Math.round(spanMs * (double) pct), spanMs / 2);
    }

    // ── The carets (text boxes) and the range control (captions) ─────────────────────────────

    /**
     * Where a caret sits, as a fraction of its inward travel: 0 = resting at the end of the tape
     * (no animation), 1 = at the tape's centre (the unit finishes arriving exactly as it starts
     * leaving).
     *
     * <p>This pair and its inverse live HERE rather than in the timeline view for the same reason
     * the easing does: it is the mapping between what the user drags and what gets stored, and a
     * mapping that exists in one place cannot round-trip differently from itself. The view owns
     * pixels; this owns the model.</p>
     *
     * <p>Since the change to fractions there is no scale factor left in this mapping — full
     * travel is {@link #MAX_ZONE_PCT} at every line length, so the caret no longer has to be
     * scaled against the longest visible phrase to keep its travel meaningful.</p>
     */
    public static float caretFractionForZone(float zonePct) {
        return clampZonePct(zonePct) / MAX_ZONE_PCT;
    }

    /** The inverse: a caret's (or slider's) travel fraction back to a stored zone fraction. */
    public static float zoneFromCaretFraction(float fraction) {
        if (Float.isNaN(fraction)) return 0f;
        float f = Math.max(0f, Math.min(1f, fraction));
        return f * MAX_ZONE_PCT;
    }

    // ── Resolving stored values ──────────────────────────────────────────────────────────────

    /**
     * Resolve a stored preset NAME. Lives here rather than on either renderer because both need
     * it and a helper duplicated across the preview/export boundary is how this whole area went
     * wrong the first time.
     *
     * <p>An unknown name - a project written by a newer build, or a preset later removed -
     * degrades to {@link Preset#NONE} rather than throwing. Silently not animating is recoverable;
     * crashing the export thread is not.</p>
     */
    @NonNull
    public static Preset parsePreset(String name) {
        if (name == null) return Preset.NONE;
        try {
            return Preset.valueOf(name);
        } catch (IllegalArgumentException e) {
            return Preset.NONE;
        }
    }

    /**
     * The human name of a preset — one authority, because the ad-hoc ones kept going stale.
     *
     * <p>This exists as a fix for a bug that has now happened twice. Three separate switches
     * spelled these out (the picker tile, the caption drawer's Motion row and the text box's
     * Motion row), each with a {@code default:} falling back to {@code Preset.name()}. MATRIX
     * shipped and the caption row read a bare <b>{@code MATRIX}</b> for a session; the text-box
     * row's {@code name().charAt(0) + name().substring(1).toLowerCase()} would have rendered this
     * preset as <b>{@code Mask_wipe}</b>. Both are the same defect: a fallback that produces
     * something plausible-looking instead of failing. Centralising it does not by itself prevent
     * the next omission, so the harness pins that no preset's label leaks an enum spelling —
     * see {@code CaptionAnimatorTest.presetLabels}. TODO(strings) — extraction is frozen behind
     * the rebrand (road_map.md:49), same as every other literal on this path.</p>
     */
    @NonNull
    public static String presetLabel(@NonNull Preset p) {
        switch (p) {
            case NONE:         return "None";
            case TYPEWRITER:   return "Type";
            case FADE:         return "Fade";
            case RISE:         return "Rise";
            case GHOST:        return "Ghost";
            case BEAM:         return "Beam";
            case MATRIX:       return "Matrix";
            case UNSCRAMBLE:   return "Unscramble";
            case MASK_WIPE:    return "Mask wipe";
            case ODOMETER:     return "Odometer";
            case NEON_FLICKER: return "Neon flicker";
            // No default that invents a name. A new constant must be added above; the harness
            // fails on any label that still looks like an enum constant, which is what makes that
            // a rule rather than a hope.
        }
        return p.name();
    }

    /** Resolve a stored granularity NAME, defaulting to {@link Granularity#WORD}. */
    @NonNull
    public static Granularity parseGranularity(String name) {
        if (name == null) return Granularity.WORD;
        try {
            return Granularity.valueOf(name);
        } catch (IllegalArgumentException e) {
            return Granularity.WORD;
        }
    }

    /** Multiply a colour's alpha channel - how {@link Transform#alpha} reaches a canvas. */
    public static int applyAlpha(int color, float a) {
        int alpha = Math.round(((color >>> 24) & 0xFF) * Math.max(0f, Math.min(1f, a)));
        return (alpha << 24) | (color & 0x00FFFFFF);
    }

    // ── Easing, as pure math ─────────────────────────────────────────────────────────────────

    /** Android's {@code DecelerateInterpolator(1.0)}. */
    private static float decelerate(float t) {
        return 1f - (1f - t) * (1f - t);
    }

    /** Android's {@code OvershootInterpolator(2.2f)}. May exceed 1 — that IS the overshoot. */
    private static float overshoot(float t) {
        float s = 2.2f;
        float u = t - 1f;
        return u * u * ((s + 1f) * u + s) + 1f;
    }

    private static float lerp(float from, float to, float t) {
        return from + (to - from) * Math.max(0f, Math.min(1f, t));
    }
}
