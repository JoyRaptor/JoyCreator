package com.fadcam.ui.faditor.keyframe;

import android.graphics.Path;
import androidx.annotation.NonNull;

/**
 * Single shape factory for every keyframe glyph in the app (SPEC 20260829 KEYFRAME SHAPES).
 * <p>
 * The glyph is DRAWN FROM {@link Easing#apply(float)} so it cannot lie — the silhouette
 * gives the family at a glance, the curve on the right half gives the exact motion.
 * No {@code Canvas} or {@code Paint} here: colour / fill / hollow / selected are the
 * caller's business, because they depend on playhead / selection / preset-owned state (§6).
 * Only {@link Path} is used.
 * </p>
 */
public final class KeyframeGlyph {

    /** Five families — the whole contract with {@link Easing}. One switch, one place. */
    public enum Family { LINEAR, HOLD, RAMP, SMOOTH, EXOTIC }

    /** Below this size the glyph is a few pixels wide — draw silhouette only, no squiggle. 14dp. */
    public static final float DETAIL_MIN_DP = 14f;
    /**
     * System density estimate via {@code Resources.getSystem()} when available.
     * Used so {@link #pathFor} can decide detail threshold in px while its signature stays
     * {@code radiusPx} per spec. Fallback 2.75 (xxhdpi) if Resources unavailable (unit tests).
     */
    private static float detailMinPx() {
        try {
            float d = android.content.res.Resources.getSystem().getDisplayMetrics().density;
            if (d > 0f) return DETAIL_MIN_DP * d;
        } catch (Throwable ignore) {}
        return DETAIL_MIN_DP * 2.75f;
    }

    private KeyframeGlyph() {}

    /** The ONE mapping. Every caller uses this; nobody writes a second switch. */
    @NonNull
    public static Family familyOf(@NonNull Easing e) {
        switch (e) {
            case LINEAR: return Family.LINEAR;
            case HOLD:
            case STAIRS_4: return Family.HOLD;
            case EASE_IN:
            case EASE_IN_EXPO:
            case ANTICIPATE:
            case EASE_OUT:
            case EASE_OUT_EXPO:
            case OVERSHOOT: return Family.RAMP;
            case EASE_IN_OUT: return Family.SMOOTH;
            case SPRING_SOFT:
            case SPRING:
            case SPRING_BOUNCY:
            case BOUNCE: return Family.EXOTIC;
            default: return Family.LINEAR;
        }
    }

    /**
     * Short human label for drawer / legend. Keeps the glossary the picker also uses
     * but the legend sheet (§4.4) replaces in/out jargon with plain sentences.
     */
    @NonNull
    public static String labelOf(@NonNull Easing e) {
        switch (e) {
            case LINEAR: return "Even";
            case HOLD: return "Hold";
            case STAIRS_4: return "Hold (steps)";
            case EASE_IN: return "Ease in";
            case EASE_OUT: return "Ease out";
            case EASE_IN_OUT: return "Smooth";
            case EASE_IN_EXPO: return "Ease in (expo)";
            case EASE_OUT_EXPO: return "Ease out (expo)";
            case ANTICIPATE: return "Anticipate";
            case OVERSHOOT: return "Overshoot";
            case SPRING_SOFT: return "Spring soft";
            case SPRING: return "Spring";
            case SPRING_BOUNCY: return "Spring bouncy";
            case BOUNCE: return "Bounce";
            default: return e.name();
        }
    }

    /**
     * Append the glyph for {@code e} into {@code out}, centred on (cx,cy) at the given radius,
     * reading rightward per §2 (easing governs segment LEAVING to the RIGHT, curve on right half).
     * <p>
     * Silhouette is always drawn. Curve detail is included only when {@code 2*radiusPx >= DETAIL_MIN_PX}
     * (≈14dp). The curve is sampled from {@link Easing#apply(float)} at ~12 points across the
     * right half (x = cx → cx+radius, t = 0→1). It is NOT clipped to the silhouette so
     * overshoot / spring poke outside is visible — that IS the motion.
     * </p>
     * <p>Caller controls fill/stroke/colour. This method only builds geometry.</p>
     */
    public static void pathFor(@NonNull Easing e, float cx, float cy, float radiusPx, @NonNull Path out) {
        out.rewind();
        appendSilhouette(e, cx, cy, radiusPx, out);
        float diameter = radiusPx * 2f;
        if (diameter < detailMinPx()) return;
        appendCurve(e, cx, cy, radiusPx, out);
    }

    /** Silhouette only — caller can fill vs stroke separately (e.g. solid glyph with contrasting curve). */
    public static void silhouetteFor(@NonNull Easing e, float cx, float cy, float radiusPx, @NonNull Path out) {
        out.rewind();
        appendSilhouette(e, cx, cy, radiusPx, out);
    }

    /** Curve only (right-half, may be empty when below DETAIL_MIN_PX). */
    public static void curveFor(@NonNull Easing e, float cx, float cy, float radiusPx, @NonNull Path out) {
        out.rewind();
        if (radiusPx * 2f < detailMinPx()) return;
        appendCurve(e, cx, cy, radiusPx, out);
    }

    private static void appendSilhouette(@NonNull Easing e, float cx, float cy, float r, @NonNull Path out) {
        Family f = familyOf(e);
        switch (f) {
            case LINEAR:
                // Diamond
                out.moveTo(cx, cy - r);
                out.lineTo(cx + r, cy);
                out.lineTo(cx, cy + r);
                out.lineTo(cx - r, cy);
                out.close();
                break;
            case HOLD:
                // Axis-aligned square (step)
                out.moveTo(cx - r, cy - r);
                out.lineTo(cx + r, cy - r);
                out.lineTo(cx + r, cy + r);
                out.lineTo(cx - r, cy + r);
                out.close();
                break;
            case RAMP:
                // Two mirrors: IN (slow start) flat on its RIGHT side, OUT flat on LEFT — §7.3 check.
                // IN  = EASE_IN group: left apex, vertical base on RIGHT (flat right edge).
                // OUT = EASE_OUT group: right apex, vertical base on LEFT (flat left edge).
                // These are horizontal mirrors; the sloped edge IS the speed ramp and the curve
                // on the right half inside mirrors it. Direction matches Easing.apply slope.
                boolean isIn = isRampIn(e);
                if (isIn) {
                    // Left-pointing triangle: apex left, base vertical on right
                    out.moveTo(cx - r, cy);
                    out.lineTo(cx + r, cy - r);
                    out.lineTo(cx + r, cy + r);
                    out.close();
                } else {
                    // Right-pointing triangle: apex right, base vertical on left
                    out.moveTo(cx + r, cy);
                    out.lineTo(cx - r, cy + r);
                    out.lineTo(cx - r, cy - r);
                    out.close();
                }
                break;
            case SMOOTH:
                out.addCircle(cx, cy, r, Path.Direction.CW);
                break;
            case EXOTIC:
                // Regular pentagon, point up
                // 5 points, angle offset -90 degrees so one vertex at top
                for (int i = 0; i < 5; i++) {
                    double angle = Math.toRadians(-90 + i * 72);
                    float x = cx + (float) (r * Math.cos(angle));
                    float y = cy + (float) (r * Math.sin(angle));
                    if (i == 0) out.moveTo(x, y);
                    else out.lineTo(x, y);
                }
                out.close();
                break;
        }
    }

    private static boolean isRampIn(@NonNull Easing e) {
        switch (e) {
            case EASE_IN:
            case EASE_IN_EXPO:
            case ANTICIPATE:
                return true;
            case EASE_OUT:
            case EASE_OUT_EXPO:
            case OVERSHOOT:
                return false;
            default:
                return true;
        }
    }

    private static void appendCurve(@NonNull Easing e, float cx, float cy, float r, @NonNull Path out) {
        // Curve maps t=0..1 across x = cx .. cx + r (right half), value 0..1 maps to y = cy + r*0.75 .. cy - r*0.75
        // Leave small inset so curve does not sit on silhouette stroke.
        // For overshoot/spring, value <0 or >1 will poke outside the inset — intentionally.
        final int STEPS = 16;
        final float inset = r * 0.15f;
        final float usableH = (r - inset) * 2f; // total vertical span for 0..1
        final float yBase = cy + (r - inset); // value 0 at bottom
        // Draw as separate contour (not closed) starting at cx, y for t=0
        boolean first = true;
        for (int i = 0; i <= STEPS; i++) {
            float t = i / (float) STEPS;
            float v = e.apply(t);
            // HOLD / STAIRS_4 are step functions — straight sample is fine (will be horizontal then jump)
            float x = cx + t * (r - 0.5f); // small right inset so tip not on edge
            // Clamp visualization for layout: y = yBase - v * usableH
            // Allow v outside [0,1] to poke beyond silhouette (overshoot / spring)
            float y = yBase - v * usableH;
            // Nudge y slightly for very small radius so line stays visible
            if (first) {
                out.moveTo(x, y);
                first = false;
            } else {
                out.lineTo(x, y);
            }
        }
        // Do not close — open polyline is the curve. Caller strokes it with its own Paint if needed,
        // but current spec has curve as part of same Path (silhouette already closed, this is open).
        // To keep them distinguishable, we leave as open; callers that fill silhouette should
        // fill before stroking curve. Since both are in same Path, a fill would fill curve too —
        // callers should draw in two passes: first fill silhouette (using path before curve?).
        // To support that, we instead keep silhouette as closed contour and curve as separate
        // contour; a FillType will fill only the closed contour. This is sufficient because
        // WINDING fill of an open contour contributes nothing.
    }
}
