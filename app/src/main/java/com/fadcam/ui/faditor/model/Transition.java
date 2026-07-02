package com.fadcam.ui.faditor.model;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * A transition effect between two clips or at the start/end of the timeline.
 *
 * <p>Stored as data (not rendered in preview yet — but the schema is in place
 * for the AI to author and for the renderer to pick up later).</p>
 */
public class Transition {

    public enum Type {
        FADE_IN_FROM_BLACK,
        FADE_OUT_TO_BLACK,
        FADE_IN_FROM_WHITE,
        FADE_OUT_TO_WHITE,
        CROSS_DISSOLVE,
        /** Wipe from left to right. */
        WIPE_LEFT,
        /** Wipe from right to left. */
        WIPE_RIGHT,
        /** Wipe from top to bottom. */
        WIPE_DOWN,
        /** Wipe from bottom to top. */
        WIPE_UP,
        /** Push: new clip pushes old clip out to the left. */
        PUSH_LEFT,
        /** Push: new clip pushes old clip out to the right. */
        PUSH_RIGHT,
        /** Push: new clip pushes old clip out upward. */
        PUSH_UP,
        /** Push: new clip pushes old clip out downward. */
        PUSH_DOWN,
        /** Radial reveal/collapse transition. */
        RADIAL,
        /** Mirror wipe from a center line outward/inward. */
        LINEAR_MIRROR_WIPE,
        /** Fast RGB split, channel warp, and neon flicker glitch. */
        GLITCH,
        /** Old-TV channel change: white flash, static, and vertical re-sync. */
        TV_CHANNEL,
        /** A GL Transitions.org shader transition. */
        GL_SHADER,
    }

    public Type type;
    public long durationMs;
    public int clipIndex;
    /** Edge fuzziness 0..1 (0 = hard cut, 1 = very blurry). */
    public float fuzziness = 0.0f;

    @Nullable public String glTransitionId;

    @Nullable public java.util.Map<String, Float> paramOverrides;

    public Transition(@NonNull Type type, long durationMs, int clipIndex) {
        this.type = type;
        this.durationMs = Math.max(100, Math.min(2000, durationMs));
        this.clipIndex = clipIndex;
    }

    public Transition(@NonNull Type type, long durationMs, int clipIndex, float fuzziness) {
        this.type = type;
        this.durationMs = Math.max(100, Math.min(2000, durationMs));
        this.clipIndex = clipIndex;
        this.fuzziness = Math.max(0f, Math.min(1f, fuzziness));
    }

    /** True if this is a directional wipe/push transition. */
    public boolean isDirectional() {
        return type.name().startsWith("WIPE_") || type.name().startsWith("PUSH_");
    }

    /** Direction: "left", "right", "up", or "down" for directional types. */
    @NonNull
    public String getDirection() {
        String name = type.name();
        if (name.endsWith("_LEFT")) return "left";
        if (name.endsWith("_RIGHT")) return "right";
        if (name.endsWith("_UP")) return "up";
        if (name.endsWith("_DOWN")) return "down";
        return "none";
    }

    /** True if this is a push transition. */
    public boolean isPush() {
        return type.name().startsWith("PUSH_");
    }

    /** True if this is a wipe transition. */
    public boolean isWipe() {
        return type.name().startsWith("WIPE_");
    }

    /** True if this is a fade-to-color transition. */
    public boolean isFade() {
        return type.name().startsWith("FADE_");
    }

    /** True if this is the neon RGB glitch transition. */
    public boolean isGlitch() {
        return type == Type.GLITCH;
    }

    /** True if this is the old-TV channel-change transition. */
    public boolean isTvChannel() {
        return type == Transition.Type.TV_CHANNEL;
    }

    /** True if this transition uses a GL Transitions.org shader. */
    public boolean isGlShader() {
        return type == Transition.Type.GL_SHADER;
    }

    /**
     * Resolve the GL transition shader id for this transition. For
     * {@link Type#GL_SHADER} returns the user-set {@link #glTransitionId} (or
     * a sensible default if unset). For all other types returns a shader that
     * best approximates the named effect (e.g. PUSH_LEFT → "PushLeft",
     * GLITCH → "FadcamGlitch"). Returns null if the type is unknown — the
     * caller is then expected to fall back to a generic transition.
     */
    @Nullable
    public String resolveGlTransitionId() {
        if (glTransitionId != null) return glTransitionId;
        switch (type) {
            case PUSH_LEFT:  return "PushLeft";
            case PUSH_RIGHT: return "PushRight";
            case PUSH_UP:    return "PushUp";
            case PUSH_DOWN:  return "PushDown";
            case WIPE_LEFT:  return "WipeLeft";
            case WIPE_RIGHT: return "WipeRight";
            case WIPE_UP:    return "WipeUp";
            case WIPE_DOWN:  return "WipeDown";
            case GLITCH:     return "FadcamGlitch";
            case RADIAL:     return "Radial";
            case CROSS_DISSOLVE: return "Dreamy";
            case FADE_IN_FROM_BLACK:
            case FADE_OUT_TO_BLACK: return "HSVfade";
            case FADE_IN_FROM_WHITE:
            case FADE_OUT_TO_WHITE: return "Overexposure";
            case LINEAR_MIRROR_WIPE: return "StereoViewer";
            case TV_CHANNEL: return "FilmBurn";
            case GL_SHADER: return null;  // caller must set glTransitionId
            default:        return "CrossZoom";
        }
    }
}
