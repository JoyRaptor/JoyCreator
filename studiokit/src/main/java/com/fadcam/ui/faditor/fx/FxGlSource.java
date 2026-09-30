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
            // ONE SLOT PER PACKED SHAPE. __MASKN__ is substituted with this layer's shape
            // count by fragment(), exactly as FxPreviewTextureView.withMaskShapes sizes a
            // PiP's. A single-shape layer compiles an array of one and a loop of one
            // iteration — the identical arithmetic the scalar uniforms performed — while an
            // 8-shape mask finally grades through all eight instead of through shape ZERO.
            // That was wrong in the preview AND in the export, and wrong TOGETHER; this is
            // the one place that fixes both, which is why the source lives here rather than
            // in either renderer.
            + "uniform vec4 uMaskGeo[__MASKN__];\n"
            + "uniform vec2 uMaskRot[__MASKN__];\n"
            + "uniform float uMaskCorner[__MASKN__];\n"
            + "uniform float uMaskFeather[__MASKN__];\n"
            // MaskFold ordinals as floats (0 union, 1 difference, 2 intersect) — the shader
            // has no integer uniforms here. Slot 0's op is never read: it seeds the fold.
            + "uniform float uMaskOp[__MASKN__];\n"
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
        return fragment(pass, kernelHalf, composite, 1);
    }

    /**
     * The same, sized to a layer's ACTUAL mask shape count.
     *
     * @param maskShapes how many shapes {@code MaskSdf.packShapes} packed for this layer. The
     *                   arrays and the fold loop are declared this long, so the source is part of
     *                   the program cache key and a layer that gains a shape recompiles. Clamped
     *                   into 1..{@link MaskSdf#MAX_SHAPES}: the packer drops shapes past the
     *                   ceiling (declaring more slots than it can fill would upload garbage), and
     *                   a GLSL array of length 0 does not exist — every pass declares the mask
     *                   block whether the layer has a mask or not, the {@code uMaskCount} branch
     *                   being what turns it off.
     */
    @NonNull
    public static String fragment(@NonNull FxCompiler.Pass pass, int kernelHalf,
                                  boolean composite, int maskShapes) {
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
        return withMaskShapes(composite ? withComposite(fragment) : fragment, maskShapes);
    }

    /**
     * Size the mask uniform arrays and the fold loop to the layer's shape count.
     *
     * <p>Applied to EVERY pass, not just the composite: the arrays are declared in
     * {@link #COMPOSITE_UNIFORMS}, which is spliced into all of them. An intermediate pass reads
     * none of them and the driver strips the lot, exactly as it stripped the scalar uniforms.</p>
     */
    @NonNull
    private static String withMaskShapes(@NonNull String fragment, int maskShapes) {
        int n = Math.max(1, Math.min(MaskSdf.MAX_SHAPES, maskShapes));
        return fragment.replace("__MASKN__", Integer.toString(n));
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
                // The fold is MaskSdf.coverage's, shape for shape: seed with the first,
                // then union/difference/intersect as MaskFold ordered them. Constant loop
                // bound (no break, no dynamic index) so GLSL ES 1.00 accepts it on every
                // driver. The coverage local is cvg, NOT c: c is the GRADED COLOUR the
                // compiler's main() left in scope, and shadowing it would mix the frame
                // with a float.
                + "    float inside = 0.0;\n"
                + "    for (int i = 0; i < __MASKN__; i++) {\n"
                + "      float sd = fxShapeSd(vFxUv, frame, uMaskGeo[i], uMaskRot[i],\n"
                + "                           uMaskCorner[i]);\n"
                + "      float cvg = fxCoverageOf(sd, uMaskFeather[i]);\n"
                + "      if (i == 0) inside = cvg;\n"
                + "      else if (uMaskOp[i] > 1.5) inside = min(inside, cvg);\n"
                + "      else if (uMaskOp[i] > 0.5) inside = min(inside, 1.0 - cvg);\n"
                + "      else inside = max(inside, cvg);\n"
                + "    }\n"
                // HOLE BY DEFAULT — see fxMaskCover in MaskSdf.GLSL_MASK_FN, the single
                // statement of this policy shared by all three GL consumers.
                + "    cover = fxMaskCover(inside, uMaskInvert);\n"
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
