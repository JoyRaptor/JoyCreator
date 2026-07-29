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
         * <p><b>NO RENDERER CONSUMES THIS TODAY (2026-07-28).</b> Neither {@code
         * CaptionOverlayView} nor {@code CaptionExportRenderer} applies it, so GHOST currently
         * ships as slide + shrink + fade with no softening, and the preset picker's thumbnail
         * deliberately omits the blur to match. This is recorded rather than quietly fixed
         * because the obvious fix is a trap: {@code BlurMaskFilter} is ignored on a
         * hardware-accelerated canvas, so adding it to the preview alone would do nothing on
         * screen while the export — which draws into a {@code Bitmap}, i.e. software — really
         * would blur. That is the preview/export divergence this class exists to prevent, in a
         * form no frame-diff of the preview would catch. Blurring the preview needs
         * {@code LAYER_TYPE_SOFTWARE} on the overlay, which costs every frame of playback, so it
         * is a decision with a price rather than an oversight to patch.</p>
         *
         * <p>Renderers that cannot blur must ignore this rather than approximate it.</p>
         */
        public float blurPx = 0f;

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

        // Declared, NOT implemented. Each needs more than a Transform:
        /** Needs GLYPH SUBSTITUTION — ticking through nonsense characters before settling. */
        MATRIX(false),
        /** Needs PER-GLYPH POSITIONAL SCATTER — letters displaced then settling into place. */
        UNSCRAMBLE(false),
        /** Needs GLYPH SUBSTITUTION plus a vertical roll clip per slot. */
        ODOMETER(false),
        /** Needs a CLIP RECT per unit — revealed by masking, not by opacity. */
        MASK_WIPE(false),
        /** Needs the renderer to modulate STROKE/GLOW, which is not a geometric transform. */
        NEON_FLICKER(false);

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
            case MATRIX:      return "needs glyph substitution";
            case UNSCRAMBLE:  return "needs per-glyph positional scatter";
            case ODOMETER:    return "needs glyph substitution and a per-slot roll clip";
            case MASK_WIPE:   return "needs a per-unit clip rect";
            case NEON_FLICKER:return "needs stroke/glow modulation";
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
        return presetTransform(preset, p, fontPx);
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
