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
            + "uniform float uMaskInvert;\n";

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
                + body.substring(mainAt);
        return composite ? withComposite(fragment) : fragment;
    }

    /**
     * Rewrite the compiler's {@code main} so the graded colour is mixed back over the original by
     * mask coverage and layer opacity.
     *
     * <p>The compiler's entry writes {@code gl_FragColor = c}. Here the ORIGINAL is still in hand,
     * so the last statement becomes the one line that IS the adjustment-layer semantic:</p>
     * <pre>out = mix(base, graded, coverage * opacity)</pre>
     * <p>Masks modulate that MIX FACTOR, not an alpha. On a PiP a mask decides where the image is
     * drawn; here it decides where the effect applies — same machinery, different question, and
     * conflating the two puts a hole in the picture instead of limiting a grade.</p>
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
                + "  float amt = clamp(cover * uLayerOpacity, 0.0, 1.0);\n"
                + "  gl_FragColor = vec4(mix(base.rgb, c.rgb, amt), base.a);\n");
    }
}
