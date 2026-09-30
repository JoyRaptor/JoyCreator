package com.fadcam.ui.faditor.fx;

import androidx.annotation.NonNull;

import com.fadcam.ui.faditor.model.BlendModes;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ONE packer, both renderers — the role {@code ChromaKey.packParams} plays for the key.
 *
 * <p>The export path and the preview path set uniforms through completely different APIs
 * ({@code GlProgram} vs {@code RuntimeShader}), but they must set the SAME numbers under the
 * SAME names. That agreement is the whole reason this class exists: each renderer asks here for
 * the values and then does nothing but hand them to its own API.</p>
 *
 * <p>Android-free, so the harness can assert the packing directly.</p>
 */
public final class FxUniforms {

    private FxUniforms() {}

    /** A named uniform value. 1 float = scalar, 2 = vec2, 3 = vec3. */
    public static final class Value {
        @NonNull public final String name;
        @NonNull public final float[] data;

        Value(@NonNull String name, @NonNull float[] data) {
            this.name = name;
            this.data = data;
        }

        public int components() { return data.length; }

        @NonNull
        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder(name).append('=');
            for (int i = 0; i < data.length; i++) {
                if (i > 0) sb.append(',');
                sb.append(data[i]);
            }
            return sb.toString();
        }
    }

    /**
     * Every uniform one pass needs, in a stable order.
     *
     * <p>BOOL and ENUM arrive as floats, not ints, because ES2 integer uniforms are patchy on
     * old drivers — the finding already recorded on {@code BlendModeGlEffect}'s
     * {@code uBlendMode}, and the reason {@link FxParam.Kind} packs everything to float.</p>
     */
    @NonNull
    public static List<Value> forPass(@NonNull FxCompiler.Pass pass) {
        List<Value> out = new ArrayList<>();
        List<FxInstance> all = new ArrayList<>(pass.remaps);
        all.addAll(pass.cards);
        for (FxInstance card : all) {
            FxEffectDef def = card.def();
            if (def == null) continue;
            for (FxParam p : def.params) {
                if (p.kind == FxParam.Kind.GRADIENT) {
                    // No single uniform to pack this under — see FxCompiler's gradient section.
                    out.addAll(FxCompiler.gradientUniformValues(card, p));
                    continue;
                }
                if (p.kind == FxParam.Kind.CURVE) {
                    // Likewise no single uniform — and the values are RESAMPLED here rather than
                    // sliced, which is precisely why both renderers must come through this class.
                    out.addAll(FxCompiler.curveUniformValues(card, p));
                    continue;
                }
                out.add(new Value(FxCompiler.uniformName(card, p), card.get(p)));
            }
            if (def.foldsColor()) {
                out.add(new Value(FxCompiler.foldOpacityName(card),
                        new float[]{Math.max(0f, Math.min(1f, card.opacity))}));
                // The blend mode travels as a float for the same driver reason as BOOL/ENUM,
                // and through BlendModes.modeCode so the FX fold and a PiP's blend cannot
                // disagree about what "SCREEN" means.
                out.add(new Value(FxCompiler.foldBlendName(card),
                        new float[]{BlendModes.modeCode(card.blendMode)}));
            }
        }
        return out;
    }

    /** The same, as a map — convenient for a renderer that sets by name. */
    @NonNull
    public static Map<String, float[]> mapForPass(@NonNull FxCompiler.Pass pass) {
        Map<String, float[]> m = new LinkedHashMap<>();
        for (Value v : forPass(pass)) m.put(v.name, v.data);
        return m;
    }

    /**
     * A stable key for "this pass's compiled program". Cheap to compare per frame, and it must
     * change whenever the SOURCE would — never merely when a value does, or an animated slider
     * would recompile the shader on every single frame.
     */
    @NonNull
    public static String sourceKey(@NonNull FxCompiler.Pass pass, int kernelHalf) {
        StringBuilder sb = new StringBuilder(64);
        for (FxInstance c : pass.remaps) sb.append('r').append(c.slot).append(':').append(c.effectId);
        for (FxInstance c : pass.cards) sb.append('c').append(c.slot).append(':').append(c.effectId);
        // Kernel size IS source: it is emitted as a literal trip count.
        if (pass.sampler) sb.append("|k").append(kernelHalf);
        return sb.toString();
    }
}
