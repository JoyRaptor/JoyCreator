package com.fadcam.ui.faditor.export;

import android.content.Context;
import android.opengl.GLES20;

import androidx.annotation.NonNull;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.util.GlProgram;
import androidx.media3.common.util.GlUtil;
import androidx.media3.common.util.Size;
import androidx.media3.effect.BaseGlShaderProgram;
import androidx.media3.effect.GlEffect;

import com.fadcam.FLog;
import com.fadcam.ui.faditor.fx.FxCompiler;
import com.fadcam.ui.faditor.fx.FxEffectDef;
import com.fadcam.ui.faditor.fx.FxInstance;
import com.fadcam.ui.faditor.fx.FxStack;
import com.fadcam.ui.faditor.fx.FxUniforms;
import com.fadcam.ui.faditor.model.AdjustmentLayer;
import com.fadcam.ui.faditor.model.CompositingSpec;
import com.fadcam.ui.faditor.model.MaskSdf;

import java.util.List;

/**
 * Renders ONE adjustment layer into the export chain — the layer that transforms everything
 * beneath it in z rather than compositing over it (SPEC_ADJUSTMENT_LAYERS_FX M4).
 *
 * <p><b>Why this is so nearly free.</b> {@code ExportManager.assembleClipVideoEffects} already
 * builds one ordered {@code List<Effect>} in which chain position literally IS z-order. At the
 * point this effect runs, the frame it is handed already holds the master video plus every PiP
 * below it. So "affect everything beneath me" needs no compositor work at all — it is just an
 * entry in that list that transforms rather than draws.</p>
 *
 * <p><b>The one line that IS the semantic:</b></p>
 * <pre>out = mix(base, blendPix(base, graded), coverage * opacity)</pre>
 * <p>Masks and chroma key modulate that MIX FACTOR, not an alpha. On a PiP a mask decides where
 * the image is drawn; here it decides where the effect applies. Same machinery, different
 * question, and conflating the two would put a hole in the picture instead of limiting a grade.</p>
 *
 * <p><b>MULTI-PASS.</b> One program is compiled per pass the planner emits, and the frame
 * ping-pongs through this class's own FBOs. A SAMPLER card — either blur, RGB shift — gets the
 * finished image it needs to read neighbours from; a separable kernel declares two renders and
 * runs the same program twice with {@code uDir} flipped, horizontal then vertical.</p>
 *
 * <p><b>Only the LAST render composites.</b> The mix with the original, the mask and the layer
 * opacity all belong at the end of the chain: folding the layer over the picture mid-chain and
 * then continuing to blur the result would blur the composite rather than the source. That is
 * also why the composite reads the original through its own {@code uBaseSampler} — by then
 * {@code uTexSampler} holds the previous pass's output.</p>
 *
 * <p><b>Never throws on a driver failure.</b> {@code BlendModeGlEffect} does, and for a PiP that
 * is right — a missing overlay is a broken film. Here a lost grade is far better than a lost
 * export, so a compile failure degrades to passthrough.</p>
 */
public final class AdjustmentLayerGlEffect implements GlEffect {

    @NonNull private final Context context;
    @NonNull private final AdjustmentLayer layer;
    private final long editorTimeOffsetMs;

    /**
     * @param editorTimeOffsetMs composition→editor time correction, stated by the caller. The
     *                           two-arg convenience constructor {@code BlendModeGlEffect} used
     *                           to have was deleted because it hard-coded this to zero and left
     *                           a silent drift trap; this class never had one.
     */
    public AdjustmentLayerGlEffect(@NonNull Context context, @NonNull AdjustmentLayer layer,
                                   long editorTimeOffsetMs) {
        this.context = context.getApplicationContext();
        this.layer = layer;
        this.editorTimeOffsetMs = editorTimeOffsetMs;
    }

    @NonNull
    @Override
    public BaseGlShaderProgram toGlShaderProgram(@NonNull Context ignored, boolean useHdr)
            throws VideoFrameProcessingException {
        if (useHdr) {
            throw new VideoFrameProcessingException("HDR adjustment layers are not supported");
        }
        return new Program(layer, editorTimeOffsetMs);
    }

    private static final class Program extends BaseGlShaderProgram {

        private static final String VERTEX_SHADER =
                "#version 100\n"
                + "attribute vec4 aFramePosition;\n"
                + "varying vec2 vFxUv;\n"
                + "void main() {\n"
                + "  gl_Position = aFramePosition;\n"
                + "  vFxUv = aFramePosition.xy * 0.5 + 0.5;\n"
                + "}\n";

        @NonNull private final AdjustmentLayer layer;
        private final long editorTimeOffsetMs;

        /** Kernel half-width. A LITERAL in the emitted source, so it is part of the source key. */
        private static final int KERNEL_HALF = 8;
        /** Null once a compile has failed — the passthrough latch. */
        private boolean degraded;
        private int width = 1, height = 1;
        /** The source this program was compiled from; a change means recompile. */
        @androidx.annotation.Nullable private String sourceKey;

        Program(@NonNull AdjustmentLayer layer, long editorTimeOffsetMs) {
            super(/* useHighPrecisionColorComponents= */ false, /* texturePoolCapacity= */ 1);
            this.layer = layer;
            this.editorTimeOffsetMs = editorTimeOffsetMs;
        }

        @Override
        public Size configure(int inputWidth, int inputHeight) {
            width = Math.max(1, inputWidth);
            height = Math.max(1, inputHeight);
            return new Size(width, height);
        }

        @Override
        public void drawFrame(int inputTexId, long presentationTimeUs)
                throws VideoFrameProcessingException {
            long editorMs = (presentationTimeUs / 1000L) + editorTimeOffsetMs;
            try {
                // TIME GATE first: an adjustment layer is emitted into every clip's chain with
                // the same offset and self-gates here, exactly as PipFrameOverlay.activeAt does
                // for a PiP. That is why no per-clip slicing is needed.
                if (degraded || !layer.activeAt(editorMs) || !layer.rendersAnything()) {
                    passthrough(inputTexId);
                    return;
                }
                FxStack resolved = layer.getFx().resolveAt(editorMs);
                if (!ensurePrograms(resolved)) {
                    passthrough(inputTexId);
                    return;
                }
                CompositingSpec cs = layer.getCompositing();
                float[] geo = MaskSdf.packShapes(cs, width, height);
                float opacity = layer.opacityAt(editorMs);

                // Remember the framebuffer media3 bound for our OUTPUT before we focus any of
                // our own — the final pass has to give it back.
                int[] outFbo = new int[1];
                GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, outFbo, 0);

                int src = inputTexId;
                int stepCount = steps.size();
                for (int i = 0; i < stepCount; i++) {
                    Step step = steps.get(i);
                    boolean last = i == stepCount - 1;
                    if (last) {
                        GlUtil.focusFramebufferUsingCurrentContext(outFbo[0], width, height);
                    } else {
                        GlUtil.focusFramebufferUsingCurrentContext(fboFor(i), width, height);
                    }
                    GlProgram p = step.program;
                    p.use();
                    p.setSamplerTexIdUniform("uTexSampler", src, 0);
                    // The ORIGINAL, on unit 1 so it cannot collide with the pass input.
                    // GUARDED for the same reason the float setters are: only the COMPOSITE
                    // pass reads uBaseSampler, so on every intermediate pass the driver strips
                    // it and media3 throws looking the name up in the linked program.
                    setSampler(p, "uBaseSampler", inputTexId, 1);
                    setF2(p, "uTexel", 1f / width, 1f / height);
                    setF(p, "uAspect", (float) width / (float) height);
                    setF(p, "uTime", editorMs / 1000f);
                    // A separable kernel runs the SAME program twice with the axis flipped;
                    // that is the whole reason a pass can declare two renders.
                    setF2(p, "uDir", step.dirX, step.dirY);
                    for (FxUniforms.Value v : FxUniforms.forPass(step.pass)) {
                        if (v.components() == 1) setF(p, v.name, v.data[0]);
                        else setFn(p, v.name, v.data);
                    }
                    setF(p, "uLayerOpacity", opacity);
                    setF(p, "uMaskCount", cs == null || cs.masks.isEmpty() ? 0f : 1f);
                    setFn(p, "uMaskGeo", new float[]{geo[0], geo[1], geo[2], geo[3]});
                    setF2(p, "uMaskRot", geo[4], geo[5]);
                    setF(p, "uMaskCorner", geo[6]);
                    setF(p, "uMaskFeather", geo[7]);
                    setF(p, "uMaskInvert", cs != null && cs.invertMasks ? 1f : 0f);
                    p.bindAttributesAndUniforms();
                    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
                    GlUtil.checkGlError();
                    if (!last) src = texFor(i);
                }
            } catch (Exception e) {
                // A lost grade beats a lost export. Latch so a per-frame failure does not spam.
                if (!degraded) {
                    // WITH the throwable: a bare toString on an NPE names no line, and this
                    // path is only ever reached on a device where a debugger is not attached.
                    FLog.w("AdjustmentLayer", "degrading to passthrough", e);
                    degraded = true;
                }
                try {
                    passthrough(inputTexId);
                } catch (Exception ignored) {
                    throw new VideoFrameProcessingException(e);
                }
            }
        }

        /**
         * Set a uniform that the driver may have OPTIMISED AWAY.
         *
         * <p>GLSL compilers delete uniforms no code path reads, and media3's {@code GlProgram}
         * looks names up in a map built from the LINKED program — so setting one that was
         * removed throws NPE. An invert-only stack reads none of {@code uTexel}, {@code uTime},
         * {@code uAspect} or {@code uDir}, all of which this class declares for the effects that
         * do, so the very simplest possible stack was the one that crashed. Found by running an
         * actual export.</p>
         */
        private static void setSampler(@NonNull GlProgram p, @NonNull String name,
                                       int texId, int unit) {
            try { p.setSamplerTexIdUniform(name, texId, unit); }
            catch (RuntimeException ignored) { }
        }

        private static void setF(@NonNull GlProgram p, @NonNull String name, float v) {
            try { p.setFloatUniform(name, v); } catch (RuntimeException ignored) { }
        }

        private static void setF2(@NonNull GlProgram p, @NonNull String name, float a, float b) {
            setFn(p, name, new float[]{a, b});
        }

        private static void setFn(@NonNull GlProgram p, @NonNull String name,
                                  @NonNull float[] v) {
            try { p.setFloatsUniform(name, v); } catch (RuntimeException ignored) { }
        }

        /** One RENDER: a compiled program, the pass it came from, and its kernel axis. */
        private static final class Step {
            @NonNull final GlProgram program;
            @NonNull final FxCompiler.Pass pass;
            final float dirX, dirY;

            Step(@NonNull GlProgram program, @NonNull FxCompiler.Pass pass,
                 float dirX, float dirY) {
                this.program = program;
                this.pass = pass;
                this.dirX = dirX;
                this.dirY = dirY;
            }
        }

        @NonNull private final List<Step> steps = new java.util.ArrayList<>();
        /** Ping-pong targets, one per intermediate step. Lazily made, reused every frame. */
        @NonNull private final List<int[]> pingPong = new java.util.ArrayList<>();

        private int texFor(int i) throws GlUtil.GlException { return pingPongAt(i)[0]; }
        private int fboFor(int i) throws GlUtil.GlException { return pingPongAt(i)[1]; }

        @NonNull
        private int[] pingPongAt(int i) throws GlUtil.GlException {
            while (pingPong.size() <= i) {
                int tex = GlUtil.createTexture(width, height,
                        /* useHighPrecisionColorComponents= */ false);
                pingPong.add(new int[]{tex, GlUtil.createFboForTexture(tex)});
            }
            return pingPong.get(i);
        }

        /**
         * Compile ONE PROGRAM PER RENDER for {@code stack}.
         *
         * <p>Every pass the planner emits is compiled, including SAMPLER passes — the
         * single-fused-pass limitation is gone. A separable kernel declares two renders and gets
         * two {@link Step}s sharing one program with the axis flipped, which is exactly what
         * {@code uDir} was emitted for.</p>
         *
         * <p>ONLY THE LAST STEP composites. The mix with the original, the mask and the layer
         * opacity all belong at the end of the chain: applying them to an intermediate would
         * fold the layer over the picture and then keep blurring the result.</p>
         *
         * @return false when the stack plans nothing to draw.
         */
        private boolean ensurePrograms(@NonNull FxStack stack)
                throws VideoFrameProcessingException {
            FxCompiler.Plan plan = FxCompiler.plan(stack);
            if (plan.passes.isEmpty()) return false;

            StringBuilder key = new StringBuilder();
            for (FxCompiler.Pass pa : plan.passes) {
                key.append(FxUniforms.sourceKey(pa, KERNEL_HALF)).append('/');
            }
            if (!steps.isEmpty() && key.toString().equals(sourceKey)) return true;

            releaseSteps();
            try {
                for (int i = 0; i < plan.passes.size(); i++) {
                    FxCompiler.Pass pa = plan.passes.get(i);
                    boolean lastPass = i == plan.passes.size() - 1;
                    int renders = Math.max(1, pa.repeats);
                    for (int r = 0; r < renders; r++) {
                        boolean lastRender = lastPass && r == renders - 1;
                        GlProgram prog = compile(pa, lastRender);
                        // Horizontal first, then vertical — the order the kernel weights assume.
                        float dx = (renders > 1 && r == 1) ? 0f : 1f;
                        float dy = (renders > 1 && r == 1) ? 1f : 0f;
                        steps.add(new Step(prog, pa, dx, dy));
                    }
                }
            } catch (Exception e) {
                FLog.w("AdjustmentLayer", "shader compile failed, passing through: " + e);
                releaseSteps();
                degraded = true;
                return false;
            }
            sourceKey = key.toString();
            return true;
        }

        /**
         * Build one pass's program.
         *
         * <p>ORDER MATTERS, and getting it wrong is what the first export A/B caught. GLSL ES
         * 1.00 requires declaration before use, so the mask functions and the composite's own
         * uniforms are spliced in AHEAD of main(); appending them left main() calling fxShapeSd
         * and reading uMaskGeo before either existed, and the driver rejected the program.</p>
         */
        @NonNull
        private GlProgram compile(@NonNull FxCompiler.Pass pa, boolean composite)
                throws GlUtil.GlException {
            String body = FxCompiler.emitGlsl(pa, KERNEL_HALF);
            int mainAt = body.indexOf("void main()");
            if (mainAt < 0) {
                // The compiler always emits an entry point; if it ever stops, fail loudly here
                // rather than handing the driver a program with nothing to run.
                throw new GlUtil.GlException("FxCompiler emitted no entry point");
            }
            String fragment = "#version 100\n"
                    + body.substring(0, mainAt)
                    + COMPOSITE_UNIFORMS
                    + MaskSdf.GLSL_MASK_FN
                    + body.substring(mainAt);
            GlProgram prog = new GlProgram(VERTEX_SHADER,
                    composite ? withComposite(fragment) : fragment);
            // The quad. Without it GlProgram throws "call setBuffer before bind" on first draw.
            prog.setBufferAttribute("aFramePosition",
                    GlUtil.getNormalizedCoordinateBounds(), 4);
            return prog;
        }

        private void releaseSteps() {
            for (Step st : steps) {
                try { st.program.delete(); } catch (Exception ignored) { }
            }
            steps.clear();
            sourceKey = null;
        }

        /**
         * The composite's OWN uniforms.
         *
         * <p>{@link FxCompiler} emits uniforms for the effect cards from their descriptors, and
         * knows nothing about layers — correctly, since it also serves the preview. These belong
         * to the adjustment-layer semantic, so they are declared here, next to the code that
         * reads them and the code that sets them.</p>
         */
        private static final String COMPOSITE_UNIFORMS =
                // The ORIGINAL frame, on its own sampler. In a multi-pass stack uTexSampler
                // holds the PREVIOUS PASS's output by the time the composite runs, so reading
                // "base" from it would mix the effect with itself instead of with the picture
                // underneath — a blur would compose over its own blurred copy.
                "uniform sampler2D uBaseSampler;\n"
                + "uniform float uLayerOpacity;\n"
                + "uniform float uMaskCount;\n"
                + "uniform vec4 uMaskGeo;\n"
                + "uniform vec2 uMaskRot;\n"
                + "uniform float uMaskCorner;\n"
                + "uniform float uMaskFeather;\n"
                + "uniform float uMaskInvert;\n";

        /**
         * Rewrite the compiler's {@code main} so the graded colour is mixed back over the
         * original by mask coverage and layer opacity.
         *
         * <p>The compiler's entry writes {@code gl_FragColor = c}. Here the ORIGINAL is still in
         * hand, so the last statement becomes the mix that defines what an adjustment layer
         * means. Textual because the compiler deliberately knows nothing about layers.</p>
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
                    // invert flips WHICH SIDE the effect lands on. Default: a mask cuts a hole,
                    // so the effect applies outside it.
                    + "    cover = uMaskInvert > 0.5 ? inside : 1.0 - inside;\n"
                    + "  }\n"
                    + "  float amt = clamp(cover * uLayerOpacity, 0.0, 1.0);\n"
                    + "  gl_FragColor = vec4(mix(base.rgb, c.rgb, amt), base.a);\n")
                    + "\n"
                    // Declared after the replace so it cannot be clobbered by it.
                    ;
        }

        private void passthrough(int inputTexId) throws VideoFrameProcessingException {
            // Not a no-op: media3 has already bound this program's output framebuffer, so
            // something must be written or the frame would be whatever the texture last held.
            try {
                if (passProgram == null) {
                    passProgram = new GlProgram(VERTEX_SHADER, PASSTHROUGH_FRAGMENT);
                    passProgram.setBufferAttribute("aFramePosition",
                            GlUtil.getNormalizedCoordinateBounds(), 4);
                }
                passProgram.use();
                passProgram.setSamplerTexIdUniform("uTexSampler", inputTexId, 0);
                passProgram.bindAttributesAndUniforms();
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
                GlUtil.checkGlError();
            } catch (Exception e) {
                throw new VideoFrameProcessingException(e);
            }
        }

        @androidx.annotation.Nullable private GlProgram passProgram;

        private static final String PASSTHROUGH_FRAGMENT =
                "#version 100\n"
                + "precision mediump float;\n"
                + "varying vec2 vFxUv;\n"
                + "uniform sampler2D uTexSampler;\n"
                + "void main() { gl_FragColor = texture2D(uTexSampler, vFxUv); }\n";

        @Override
        public void release() throws VideoFrameProcessingException {
            super.release();
            try {
                releaseSteps();
                // The ping-pong targets are GL objects too. Leaking one per export would be a
                // slow bleed nobody notices until a long session runs out of texture memory.
                for (int[] pp : pingPong) {
                    try { GlUtil.deleteTexture(pp[0]); } catch (Exception ignored) { }
                }
                pingPong.clear();
                if (passProgram != null) passProgram.delete();
            } catch (Exception e) {
                throw new VideoFrameProcessingException(e);
            }
        }
    }
}
