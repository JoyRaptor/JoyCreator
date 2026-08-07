package com.fadcam.ui.faditor.fx;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * A typed descriptor for ONE authored parameter of an {@link FxEffectDef}.
 *
 * <p><b>One descriptor, three consumers.</b> The uniform declaration, the parameter row in the FX
 * tab, and the keyframe track name are all derived from this object rather than written three
 * times. That is the generalisation of {@code GlTransitionShaderLoader.uniformsFor}, and it is why
 * {@link FxRegistry}'s self-check can reject a declared-but-unreferenced parameter: a slider that
 * drives a uniform no shader reads is silently-broken UI, and there is no other way to notice.</p>
 *
 * <p><b>Everything packs to float.</b> BOOL and ENUM become {@code float} uniforms rather than
 * {@code int} because ES2 integer uniforms are patchy on old drivers — the same finding already
 * recorded on {@code BlendModeGlEffect}'s {@code uBlendMode}. COLOR is a {@code vec3} (alpha is
 * carried by the card's own opacity, never by a colour swatch) and POINT is a {@code vec2}.</p>
 *
 * <p>Android-free so the JVM harness can reach it.</p>
 */
public final class FxParam {

    /**
     * Per-card opacity — RESERVED. The compiler emits this uniform itself for the blend/opacity
     * fold between cards, so an effect that also declared it would collide on both the uniform
     * name and the keyframe track. {@link FxRegistry#validate} rejects that.
     */
    public static final String RESERVED_OPACITY = "opacity";

    /** Per-card blend mode — RESERVED, for the same reason as {@link #RESERVED_OPACITY}. */
    public static final String RESERVED_BLEND = "blend";

    /** What the value MEANS — which decides the uniform type, the UI row, and the key count. */
    public enum Kind {
        /** One scalar in [min, max]. Uniform: {@code float}. */
        FLOAT(1),
        /** Packed 0xRRGGBB. Uniform: {@code vec3} in 0..1. */
        COLOR(3),
        /** 0 or 1. Uniform: {@code float}, see the class note. */
        BOOL(1),
        /** An index into {@link #enumLabels}. Uniform: {@code float}, see the class note. */
        ENUM(1),
        /** A normalised 0..1 point. Uniform: {@code vec2}. */
        POINT(2);

        /** How many floats this kind occupies in a uniform and in {@link FxInstance}'s values. */
        public final int components;

        Kind(int components) { this.components = components; }
    }

    /** The name used in {@code FX_P(name)}, in the uniform, and in the keyframe track. */
    @NonNull public final String name;
    /** What the FX tab shows next to the slider. */
    @NonNull public final String label;
    @NonNull public final Kind kind;
    /** Slider rails. Meaningless for COLOR; for ENUM, {@code max} is the last valid index. */
    public final float min;
    public final float max;
    /** Component defaults, length == {@code kind.components}. */
    @NonNull private final float[] defaults;
    /** Whether the FX tab offers a keyframe diamond for it. */
    public final boolean keyable;
    /** ENUM only; {@code null} otherwise. */
    @Nullable private final String[] enumLabels;

    private FxParam(@NonNull String name, @NonNull String label, @NonNull Kind kind,
                    float min, float max, @NonNull float[] defaults, boolean keyable,
                    @Nullable String[] enumLabels) {
        this.name = name;
        this.label = label;
        this.kind = kind;
        this.min = min;
        this.max = max;
        this.defaults = defaults;
        this.keyable = keyable;
        this.enumLabels = enumLabels;
    }

    // ── Factories ───────────────────────────────────────────────────────────────────────────

    @NonNull
    public static FxParam flt(@NonNull String name, @NonNull String label,
                              float min, float max, float def) {
        return new FxParam(name, label, Kind.FLOAT, min, max, new float[]{def}, true, null);
    }

    /** A scalar the UI may show but the timeline must never key — trip counts, mostly. */
    @NonNull
    public static FxParam fltStatic(@NonNull String name, @NonNull String label,
                                    float min, float max, float def) {
        return new FxParam(name, label, Kind.FLOAT, min, max, new float[]{def}, false, null);
    }

    /** @param rgb packed 0xRRGGBB; alpha bits are ignored by design (see the class note). */
    @NonNull
    public static FxParam color(@NonNull String name, @NonNull String label, int rgb) {
        return new FxParam(name, label, Kind.COLOR, 0f, 1f, new float[]{
                ((rgb >> 16) & 0xFF) / 255f,
                ((rgb >> 8) & 0xFF) / 255f,
                (rgb & 0xFF) / 255f,
        }, true, null);
    }

    @NonNull
    public static FxParam bool(@NonNull String name, @NonNull String label, boolean def) {
        return new FxParam(name, label, Kind.BOOL, 0f, 1f, new float[]{def ? 1f : 0f}, false, null);
    }

    @NonNull
    public static FxParam enumOf(@NonNull String name, @NonNull String label, int def,
                                 @NonNull String... labels) {
        if (labels.length == 0) throw new IllegalArgumentException("ENUM param " + name + " has no labels");
        return new FxParam(name, label, Kind.ENUM, 0f, labels.length - 1f,
                new float[]{def}, false, labels.clone());
    }

    @NonNull
    public static FxParam point(@NonNull String name, @NonNull String label, float defX, float defY) {
        return new FxParam(name, label, Kind.POINT, 0f, 1f, new float[]{defX, defY}, true, null);
    }

    // ── Reads ───────────────────────────────────────────────────────────────────────────────

    /** A fresh copy — callers store this straight into an {@link FxInstance}, so it must not alias. */
    @NonNull
    public float[] defaultValue() { return defaults.clone(); }

    /** Convenience for the scalar kinds. */
    public float defaultScalar() { return defaults[0]; }

    @NonNull
    public String[] enumLabels() { return enumLabels == null ? new String[0] : enumLabels.clone(); }

    /**
     * The GLSL type this parameter's uniform is declared with. The AGSL emit runs the compiler's
     * word-boundary rewrite over this same string, so there is no second type table.
     */
    @NonNull
    public String glslType() {
        switch (kind.components) {
            case 2: return "vec2";
            case 3: return "vec3";
            default: return "float";
        }
    }

    /**
     * Clamp a candidate value into the descriptor's rails. COLOR and POINT clamp to 0..1
     * regardless of {@link #min}/{@link #max}; ENUM snaps to a whole index.
     */
    @NonNull
    public float[] clamp(@NonNull float[] v) {
        float[] out = new float[kind.components];
        for (int i = 0; i < out.length; i++) {
            float x = i < v.length ? v[i] : defaults[i];
            if (Float.isNaN(x)) x = defaults[i];
            float lo = (kind == Kind.COLOR || kind == Kind.POINT) ? 0f : min;
            float hi = (kind == Kind.COLOR || kind == Kind.POINT) ? 1f : max;
            out[i] = Math.max(lo, Math.min(hi, x));
            if (kind == Kind.ENUM) out[i] = Math.round(out[i]);
            if (kind == Kind.BOOL) out[i] = out[i] >= 0.5f ? 1f : 0f;
        }
        return out;
    }

    /**
     * The suffix a multi-component keyframe track carries. Scalars get {@code ""}, so the common
     * case is exactly the {@code "fx" + slot + "." + name} the spec names and nothing more.
     */
    @NonNull
    public String componentSuffix(int component) {
        if (kind.components == 1) return "";
        if (kind == Kind.COLOR) return component == 0 ? ".r" : component == 1 ? ".g" : ".b";
        return component == 0 ? ".x" : ".y";
    }
}
