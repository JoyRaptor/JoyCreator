package com.fadcam.ui.faditor.keyframe;

import androidx.annotation.NonNull;

/**
 * Interpolation curve used between one keyframe and the next. Kept as a small
 * enum (rather than arbitrary bezier handles) so the project file stays simple
 * and an AI or script can author keyframes without understanding curves.
 *
 * <p>D2a curve set (JoyRaptor 2026-07-17, FEEDBACK_20260717_ui_dialogs_and_keyframes):
 * practical presets for real-work animation — expo pairs for dramatic launches /
 * snap arrivals, anticipation + overshoot (the classic animation principles),
 * three spring damping levels, a true bounce (rebounds, never crosses the
 * target — distinct from a spring), and a jump stair-step. Preset thumbnails in
 * the picker MUST be rendered from {@link #apply} itself so they can't lie.</p>
 */
public enum Easing {
    /** Constant speed. */
    LINEAR,
    /** Slow start (quadratic). */
    EASE_IN,
    /** Slow end (quadratic). */
    EASE_OUT,
    /** Slow start and end (the natural-looking default). */
    EASE_IN_OUT,
    /** No interpolation — hold the value until the next keyframe (step). */
    HOLD,
    /** Sharp exponential launch — much harder acceleration than EASE_IN. */
    EASE_IN_EXPO,
    /** Snap arrival — fast out of the gate, exponential settle. */
    EASE_OUT_EXPO,
    /** Anticipation: dips BACKWARD first, then commits (back-in). */
    ANTICIPATE,
    /** One swing PAST the target, then settle (back-out) — the UI-pop workhorse. */
    OVERSHOOT,
    /** Gentle spring: one soft overshoot (ζ≈0.75 feel). */
    SPRING_SOFT,
    /** Medium spring: a couple of visible oscillations (ζ≈0.5 feel). */
    SPRING,
    /** Lively spring: pronounced wobble (ζ≈0.3 feel). */
    SPRING_BOUNCY,
    /** Ball-drop bounce: rebounds off the target from below, never crosses it. */
    BOUNCE,
    /** Jump stair-step: four equal holds, then the final jump lands the value. */
    STAIRS_4;

    /** Back-curve tension (Penner's classic ~10% overshoot). */
    private static final float BACK_S = 1.70158f;

    /**
     * Remap a normalised progress {@code t} through this curve. Guaranteed to
     * return exactly 0 at {@code t<=0} and exactly 1 at {@code t>=1} for every
     * curve. The springy curves (ANTICIPATE/OVERSHOOT/SPRING_*) intentionally
     * leave [0,1] mid-flight — consumers that need clamped output (e.g. alpha)
     * clamp at their own edge, position/scale ride the overshoot.
     *
     * @param t linear progress between the two keyframes
     */
    public float apply(float t) {
        if (t <= 0f) return 0f;
        if (t >= 1f) return 1f;
        switch (this) {
            case EASE_IN:
                return t * t;
            case EASE_OUT:
                return 1f - (1f - t) * (1f - t);
            case EASE_IN_OUT:
                return t < 0.5f
                        ? 2f * t * t
                        : 1f - (float) Math.pow(-2f * t + 2f, 2) / 2f;
            case HOLD:
                return 0f; // value stays at the left keyframe until the next one
            case EASE_IN_EXPO:
                return (float) Math.pow(2, 10 * t - 10);
            case EASE_OUT_EXPO:
                return 1f - (float) Math.pow(2, -10 * t);
            case ANTICIPATE:
                return t * t * ((BACK_S + 1f) * t - BACK_S);
            case OVERSHOOT: {
                float u = t - 1f;
                return u * u * ((BACK_S + 1f) * u + BACK_S) + 1f;
            }
            // Springs: 1 − e^(−λt)·cos(ωt) with ω an odd multiple of π/2, so the
            // cosine is exactly 0 at t=1 and the curve lands on 1 with no
            // residual-ramp correction. λ (decay) + ω (cycles) set the feel.
            case SPRING_SOFT:
                return spring(t, 6f, 1.5f * (float) Math.PI);
            case SPRING:
                return spring(t, 5f, 2.5f * (float) Math.PI);
            case SPRING_BOUNCY:
                return spring(t, 4f, 3.5f * (float) Math.PI);
            case BOUNCE:
                return bounceOut(t);
            case STAIRS_4:
                return (float) Math.floor(t * 4f) / 4f; // t>=1 guard supplies the last jump
            case LINEAR:
            default:
                return t;
        }
    }

    private static float spring(float t, float decay, float omega) {
        return 1f - (float) (Math.exp(-decay * t) * Math.cos(omega * t));
    }

    /** Penner bounce-out: rebounds approaching 1 from below. */
    private static float bounceOut(float t) {
        final float n1 = 7.5625f, d1 = 2.75f;
        if (t < 1f / d1) {
            return n1 * t * t;
        } else if (t < 2f / d1) {
            float u = t - 1.5f / d1;
            return n1 * u * u + 0.75f;
        } else if (t < 2.5f / d1) {
            float u = t - 2.25f / d1;
            return n1 * u * u + 0.9375f;
        } else {
            float u = t - 2.625f / d1;
            return n1 * u * u + 0.984375f;
        }
    }

    @NonNull
    public static Easing fromName(@NonNull String name) {
        try {
            return Easing.valueOf(name);
        } catch (IllegalArgumentException e) {
            return EASE_IN_OUT;
        }
    }
}
