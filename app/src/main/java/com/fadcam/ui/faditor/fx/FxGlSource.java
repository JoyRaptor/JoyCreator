package com.fadcam.ui.faditor.fx;

import androidx.annotation.NonNull;

import com.fadcam.ui.faditor.model.MaskSdf;

/**
 * The GLSL an adjustment layer's effect stack compiles to — for EXPORT and for the LIVE GL
 * PREVIEW, from one place.
 *
 * <p><b>Why this class exists at all.</b> The strings here used to be private to
 * {@code AdjustmentLayerGlEffect}, which was right while export was the only renderer. It no
 * longer is: {@code FxPreviewTextureView} runs the same stack on the phone's GPU so the editor
 * shows what the export will produce. Two renderers assembling their own source is precisely how
 * a preview starts lying — the tolerance you tuned against a preview that composites DIFFERENTLY
 * is worse than no preview, because it looks trustworthy. Same reason {@link FxCompiler} exists
 * rather than each renderer owning its own effect bodies, one level up.</p>
 *
 * <p><b>ORDER MATTERS and it is not obvious.</b> GLSL ES 1.00 requires declaration before use, so
 * the mask functions and the composite's uniforms are spliced in AHEAD of {@code main()}.
 * Appending them — the natural thing to write — leaves {@code main()} calling {@code fxShapeSd}
 * and reading {@code uMaskGeo} before either exists, and the driver rejects the program. That
 * cost a whole export A/B to find; it is a single method now so it can only be got wrong once.</p>
 *
 * <p>Deliberately free of media3 and of {@code android.opengl}: this is text. That keeps it on
 * the gson-only harness classpath with the rest of the {@code fx} package, and it is why the
 * preview renderer can share it without dragging the export stack into the editor.</p>
 */
public final class FxGlSource {

    private FxGlSource() {}

    /**
     * Kernel half-width baked into the emitted source as a LITERAL, so it is part of the source
     * key and a change forces a recompile. Shared so preview and export cannot pick different
     * kernel widths and produce visibly different blurs at the same radius.
     */
    public static final int KERNEL_HALF = 8;

    /**
     * The full-frame quad's vertex shader. {@code vFxUv} is the varying every emitted fragment
     * body reads, so both renderers must feed it the same way.
     */
    public static final String VERTEX_SHADER =
            "#version 100\n"
            + "attribute vec4 aFramePosition;\n"
            + "varying vec2 vFxUv;\n"
            + "void main() {\n"
            + "  gl_Position = aFramePosition;\n"
            + "  vFxUv = aFramePosition.xy * 0.5 + 0.5;\n"
            + "}\n";

    /** Writes the input straight out. Used for the degraded latch and for OES→2D staging. */
    public static final String PASSTHROUGH_FRAGMENT =
            "#version 100\n"
            + "precision mediump float;\n"
            + "varying vec2 vFxUv;\n"
            + "uniform sampler2D uTexSampler;\n"
            + "void main() { gl_FragColor = texture2D(uTexSampler, vFxUv); }\n";

    /**
     * The composite's OWN uniforms.
     *
     * <p>{@link FxCompiler} emits uniforms for the effect cards from their descriptors and knows
     * nothing about layers — correctly, since it also serves the AGSL preview. These belong to
     * the adjustment-layer semantic, so they are declared next to the code that reads them.</p>
     */
    public static final String COMPOSITE_UNIFORMS =
            // The ORIGINAL frame, on its own sampler. In a multi-pass stack uTexSampler holds the
            // PREVIOUS PASS's output by the time the composite runs, so reading "base" from it
            // would mix the effect with itself instead of with the picture underneath — a blur
            // would compose over its own blurred copy.
            "uniform sampler2D uBaseSampler;\n"
            + "uniform float uLayerOpacity;\n"
            + "uniform float uMaskCount;\n"
            + "uniform vec4 uMaskGeo;\n"
            + "uniform vec2 uMaskRot;\n"
            + "uniform float uMaskCorner;\n"
            + "uniform float uMaskFeather;\n"
            + "uniform float uMaskInvert;\n"
            // Chroma key GATES the mix factor exactly like a mask does — an adjustment layer has
            // no footage of its own to key, so "key" here means "key OUT a colour from what's
            // beneath, before grading", not the PiP sense of "key my own source". Packed by the
            // same ChromaKey authority a PiP uses, so the tolerance/softness/spill sliders mean
            // the identical distance whichever object they are tuned on.
            + "uniform vec3 uKeyColor;\n"
            + "uniform vec4 uKeyParams;\n"   // x=enabled, y=tolerance, z=fuzziness, w=offset
            // How the graded colour combines with the original, past the plain opacity mix.
            // BlendModes' own float codes (0=NORMAL … see BlendModes.modeCode).
            + "uniform float uBlendMode;\n";

    /**
     * Assemble one pass's fragment source.
     *
     * @param composite true for the LAST render of the stack — the only one that mixes the graded
     *                  colour back over the original by mask coverage and layer opacity. Folding
     *                  the layer over the picture mid-chain and then continuing to blur would
     *                  blur the composite rather than the source.
     * @throws IllegalStateException if the compiler emitted no entry point. Loud on purpose:
     *                               handing the driver a program with nothing to run produces a
     *                               black frame and no diagnostic.
     */
    @NonNull
    public static String fragment(@NonNull FxCompiler.Pass pass, int kernelHalf,
                                  boolean composite) {
        String body = FxCompiler.emitGlsl(pass, kernelHalf);
        int mainAt = body.indexOf("void main()");
        if (mainAt < 0) {
            throw new IllegalStateException("FxCompiler emitted no entry point");
        }
        String fragment = "#version 100\n"
                + body.substring(0, mainAt)
                + COMPOSITE_UNIFORMS
                + MaskSdf.GLSL_MASK_FN
                // Same shared-source discipline as the mask function: the key and blend
                // equations live in ChromaKey/BlendModes so export and preview cannot compile
                // two different ideas of what they mean.
                + com.fadcam.ui.faditor.model.ChromaKey.GLSL_KEY_FN
                + com.fadcam.ui.faditor.model.BlendModes.GLSL_BLEND_FN
                + body.substring(mainAt);
        return composite ? withComposite(fragment) : fragment;
    }

    /**
     * Rewrite the compiler's {@code main} so the graded colour is mixed back over the original by
     * mask coverage and layer opacity.
     *
     * <p>The compiler's entry writes {@code gl_FragColor = c}. Here the ORIGINAL is still in hand,
     * so the last statement becomes the one line that IS the adjustment-layer semantic:</p>
     * <pre>out = mix(base, blendPix(base, graded), coverage * opacity)</pre>
     * <p>Masks and the chroma key both modulate that MIX FACTOR, not an alpha. On a PiP a mask (or
     * a key) decides where the image is drawn; here they decide where the effect applies — same
     * machinery, different question, and conflating the two puts a hole in the picture instead of
     * limiting a grade. The key reads {@code base}, the frame BEFORE this layer's grade, because an
     * adjustment layer has no footage of its own to key — "key out this colour, then grade what's
     * left" is the only reading of chroma key that means anything here.</p>
     *
     * <p>Textual because the compiler deliberately knows nothing about layers.</p>
     */
    @NonNull
    private static String withComposite(@NonNull String fragment) {
        return fragment.replace(
                "  gl_FragColor = c;\n",
                "  vec4 base = texture2D(uBaseSampler, fxClamp(vFxUv));\n"
                + "  float cover = 1.0;\n"
                + "  if (uMaskCount > 0.5) {\n"
                + "    vec2 frame = vec2(1.0) / uTexel;\n"
                + "    float sd = fxShapeSd(vFxUv, frame, uMaskGeo, uMaskRot, uMaskCorner);\n"
                + "    float inside = fxCoverageOf(sd, uMaskFeather);\n"
                // invert flips WHICH SIDE the effect lands on. Default: a mask cuts a hole, so
                // the effect applies outside it.
                + "    cover = uMaskInvert > 0.5 ? inside : 1.0 - inside;\n"
                + "  }\n"
                // Key gates the SAME mix factor a mask does, on the colour already sitting there
                // (BASE, not the graded result) — "key out this colour before grading", which is
                // the only reading of chroma key that makes sense with no footage of its own.
                // fadKeyAlpha self-gates on uKeyParams.x, so this is a no-op unless the tab
                // switched keying on — no second "is it enabled" branch to keep in step.
                + "  cover *= fadKeyAlpha(base.rgb / max(base.a, 0.001), 1.0, uKeyColor, "
                + "uKeyParams);\n"
                + "  float amt = clamp(cover * uLayerOpacity, 0.0, 1.0);\n"
                + "  vec3 blended = clamp(blendPix(base.rgb, c.rgb), 0.0, 1.0);\n"
                + "  gl_FragColor = vec4(mix(base.rgb, blended, amt), base.a);\n");
    }
}
