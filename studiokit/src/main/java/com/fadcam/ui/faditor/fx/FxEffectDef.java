package com.fadcam.ui.faditor.fx;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The immutable definition of ONE effect: what it is called, what it can be given, what it costs,
 * and — the part that matters — the single authored body that both backends are compiled from.
 *
 * <p><b>The body is a STATEMENT BLOCK, not a function.</b> The signature is emitted by
 * {@link FxCompiler} from {@link #capability}, which is what lets the same text be renamed
 * {@code fx3_main} in one pass and {@code fx7_main} in another without any string surgery on the
 * author's part. It is also why {@link FxRegistry}'s self-check can reject a body containing
 * {@code main(} outright: a body that declares its own entry point has taken over a job the
 * compiler owns, and the two will disagree the first time the emit changes.</p>
 *
 * <p><b>Bodies are Java constants, not assets.</b> {@code GlTransitionShaderLoader} reads its
 * bodies from {@code assets/}, which costs an {@code IOException} path, an asset-open per preview
 * rebuild, and — the real cost — invisibility to the type checker and to the JVM harness. Effect
 * bodies live in {@link FxRegistry} as string constants so a typo is caught by a test that runs in
 * seconds rather than by a driver on someone's phone.</p>
 *
 * <p>Android-free so the JVM harness can reach it.</p>
 */
public final class FxEffectDef {

    /** The picker's top-level grouping. */
    public enum Family { BLUR, COLOR, GENERATE, DISTORT }

    /**
     * What the body is allowed to do — which is exactly what decides pass planning.
     *
     * <p>The distinction that carries the whole fusion scheme: a POINTWISE body may read only the
     * colour it is handed, so any number of them collapse into one pass. The moment a body wants a
     * NEIGHBOUR it needs the previous pass's finished image, and that is a pass boundary.</p>
     */
    public enum Capability {
        /** {@code vec4 fx_main(vec2 uv, vec4 src)} reading only {@code src}. Fuses. */
        POINTWISE,
        /** Same signature, but synthesises from {@code uv} and ignores {@code src}. Fuses. */
        GENERATOR,
        /** Same signature, uses {@code FX_SAMPLE}. Closes the current pass and opens its own. */
        SAMPLER,
        /** {@code vec2 fx_uv(vec2 uv)}. Opens NO pass; folds into the next sampler's coordinate. */
        UV_REMAP
    }

    @NonNull public final String id;
    @NonNull public final String displayName;
    @NonNull public final Family family;
    @NonNull public final Capability capability;
    @NonNull public final List<FxParam> params;
    /**
     * How many GL/RenderEffect passes one instance renders. Always 1 except for a separable kernel,
     * which declares 2 and is emitted once with a {@code FX_DIR} uniform the renderer flips between
     * horizontal and vertical. Note this is NOT the same number as the plan's pass count — see
     * {@link FxCompiler.Plan}.
     */
    public final int passes;
    /** Rough ALU/bandwidth weight, in units of "one cheap full-screen pointwise pass". */
    public final float costWeight;
    /** The authored GLSL ES 1.00 statement block. See the class note. */
    @NonNull public final String glslBody;

    public FxEffectDef(@NonNull String id, @NonNull String displayName, @NonNull Family family,
                       @NonNull Capability capability, int passes, float costWeight,
                       @NonNull String glslBody, @NonNull FxParam... params) {
        this.id = id;
        this.displayName = displayName;
        this.family = family;
        this.capability = capability;
        this.passes = Math.max(1, passes);
        this.costWeight = costWeight;
        this.glslBody = glslBody;
        this.params = Collections.unmodifiableList(new ArrayList<>(Arrays.asList(params)));
    }

    @Nullable
    public FxParam param(@NonNull String name) {
        for (FxParam p : params) {
            if (p.name.equals(name)) return p;
        }
        return null;
    }

    /** True when this effect takes part in the per-card blend/opacity fold. UV_REMAP does not —
     *  a coordinate has no colour to blend, and offering an opacity slider for one would lie. */
    public boolean foldsColor() { return capability != Capability.UV_REMAP; }

    @NonNull
    @Override
    public String toString() { return id + "(" + capability + ")"; }
}
