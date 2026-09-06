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
import com.fadcam.ui.faditor.fx.FxGlSource;
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

        private static final String VERTEX_SHADER = FxGlSource.VERTEX_SHADER;

        @NonNull private final AdjustmentLayer layer;
        private final long editorTimeOffsetMs;

        /**
         * Kernel half-width. A LITERAL in the emitted source, so it is part of the source key —
         * and shared with the preview, because the same radius must mean the same blur in both.
         */
        private static final int KERNEL_HALF = FxGlSource.KERNEL_HALF;
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
                CompositingSpec cs = layer.getCompositing();
                float[] geo = MaskSdf.packShapes(cs, width, height);
                // EVERY packed shape, not shape 0. The arrays and the fold loop are sized to
                // this in the emitted source, so it is part of the program cache key — a layer
                // that gains a shape recompiles rather than silently dropping it.
                int shapes = Math.max(1, geo.length / MaskSdf.FLOATS_PER_SHAPE);
                FxStack resolved = layer.getFx().resolveAt(editorMs);
                if (!ensurePrograms(resolved, shapes)) {
                    passthrough(inputTexId);
                    return;
                }
                // Whatever count actually COMPILED — see the single-shape retry in
                // ensurePrograms. Uploading more than the program declared would be an out-of-
                // range write the driver is free to ignore or to fault on.
                shapes = Math.min(shapes, compiledShapes);
                float[] maskGeo4 = new float[shapes * 4];
                float[] maskRot2 = new float[shapes * 2];
                float[] maskCorner = new float[shapes];
                float[] maskFeather = new float[shapes];
                float[] maskOps = new float[shapes];
                int[] ops = MaskSdf.packOps(cs);
                for (int i = 0; i < shapes; i++) {
                    int o = i * MaskSdf.FLOATS_PER_SHAPE;
                    maskGeo4[i * 4] = geo[o];
                    maskGeo4[i * 4 + 1] = geo[o + 1];
                    maskGeo4[i * 4 + 2] = geo[o + 2];
                    maskGeo4[i * 4 + 3] = geo[o + 3];
                    maskRot2[i * 2] = geo[o + 4];
                    maskRot2[i * 2 + 1] = geo[o + 5];
                    maskCorner[i] = geo[o + 6];
                    maskFeather[i] = geo[o + 7];
                    maskOps[i] = i < ops.length ? ops[i] : 0f;
                }
                float opacity = layer.opacityAt(editorMs);
                // Packed by the shared authority every frame, exactly like the mask geometry
                // above — cheap, and it is what keeps this in step with a tab that can change
                // the key colour or tolerance while the preview (and, mid-export, this) is
                // running.
                float[] keyColor = com.fadcam.ui.faditor.model.ChromaKey.packColor(cs);
                float[] keyParams = com.fadcam.ui.faditor.model.ChromaKey.packParams(cs);
                float blendMode = com.fadcam.ui.faditor.model.BlendModes.modeCode(
                        layer.getBlendMode());

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
                    setF(p, "uMaskInvert", cs != null && cs.invertMasks ? 1f : 0f);
                    setFn(p, "uKeyColor", keyColor);
                    setFn(p, "uKeyParams", keyParams);
                    setF(p, "uBlendMode", blendMode);
                    p.bindAttributesAndUniforms();
                    uploadMaskArrays(p, shapes, maskGeo4, maskRot2, maskCorner, maskFeather,
                            maskOps);
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
         * Upload the per-shape mask arrays — RAW, and deliberately AFTER
         * {@code bindAttributesAndUniforms}.
         *
         * <p><b>media3's {@code GlProgram} cannot express an array uniform, and would silently
         * lose the mask if asked to.</b> Its {@code Uniform} holds one {@code float[16]} and
         * always binds with {@code count = 1}, and it is keyed by the name
         * {@code glGetActiveUniform} reports — which for an array is {@code uMaskGeo[0]}, not
         * {@code uMaskGeo}. {@code setFloatsUniform("uMaskGeo", ...)} would therefore throw,
         * {@link #setFn} would swallow it as "not in program", and the export would render an
         * UNMASKED grade with nothing but a debug line to say so. So the locations are asked for
         * by name and uploaded with {@code glUniform*fv} directly.</p>
         *
         * <p><b>After the bind, not before.</b> {@code bindAttributesAndUniforms} walks every
         * ACTIVE uniform, {@code uMaskGeo[0]} included, and writes whatever its own buffer holds
         * — zeros, since nothing ever sets it. Uploading first would have that overwrite shape
         * 0 with a degenerate rect. Uploading after leaves the driver's copy of element 0 as the
         * value written here.</p>
         *
         * <p>A location of -1 (the driver stripped the block: no mask, or an intermediate pass)
         * makes every call below a defined no-op, so no branch is needed for the unmasked
         * case.</p>
         */
        private static void uploadMaskArrays(@NonNull GlProgram p, int shapes,
                                             @NonNull float[] geo4, @NonNull float[] rot2,
                                             @NonNull float[] corner, @NonNull float[] feather,
                                             @NonNull float[] ops) {
            int n = Math.max(1, shapes);
            GLES20.glUniform4fv(p.getUniformLocation("uMaskGeo"), n, geo4, 0);
            GLES20.glUniform2fv(p.getUniformLocation("uMaskRot"), n, rot2, 0);
            GLES20.glUniform1fv(p.getUniformLocation("uMaskCorner"), n, corner, 0);
            GLES20.glUniform1fv(p.getUniformLocation("uMaskFeather"), n, feather, 0);
            GLES20.glUniform1fv(p.getUniformLocation("uMaskOp"), n, ops, 0);
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
            try { p.setFloatUniform(name, v); }
            catch (RuntimeException e) { noteSkipped(name); }
        }

        private static void setF2(@NonNull GlProgram p, @NonNull String name, float a, float b) {
            setFn(p, name, new float[]{a, b});
        }

        private static void setFn(@NonNull GlProgram p, @NonNull String name,
                                  @NonNull float[] v) {
            try { p.setFloatsUniform(name, v); }
            catch (RuntimeException e) { noteSkipped(name); }
        }

        /**
         * Names whose set was skipped, reported ONCE each.
         *
         * <p>Skipping an absent uniform is correct — the driver strips what no code reads. But
         * a uniform the shader DOES read going missing is a silent wrong picture, which is
         * exactly how a blur can run with no warning and produce no blur. Naming them turns
         * that from invisible into obvious.</p>
         */
        private static final java.util.Set<String> SKIPPED =
                java.util.Collections.synchronizedSet(new java.util.HashSet<>());

        private static void noteSkipped(@NonNull String name) {
            // DEBUG, not a warning: a skip is usually correct — the composite pass does not
            // read uDir, a blur pass does not read the mask set, and the driver strips both.
            // It earns a line only because a uniform the shader DOES read going missing is a
            // silently wrong picture, and this is the cheapest thing that would show it.
            if (SKIPPED.add(name)) FLog.d("AdjustmentLayer", "uniform not in program: " + name);
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
        private boolean ensurePrograms(@NonNull FxStack stack, int maskShapes)
                throws VideoFrameProcessingException {
            FxCompiler.Plan plan = FxCompiler.plan(stack);
            if (plan.passes.isEmpty()) return false;

            StringBuilder key = new StringBuilder();
            for (FxCompiler.Pass pa : plan.passes) {
                key.append(FxUniforms.sourceKey(pa, KERNEL_HALF)).append('/');
            }
            // The shape count SIZES THE SOURCE, so it belongs in the key: without it, adding a
            // second mask shape mid-export would keep the one-slot program and the extra shape
            // would never be uploaded.
            key.append('m').append(maskShapes);
            if (!steps.isEmpty() && key.toString().equals(sourceKey)) return true;

            if (!buildSteps(plan, maskShapes)) {
                // A many-shape mask is the one thing here that can outgrow a GPU's fragment
                // uniform budget, and losing the grade entirely would be worse than the
                // shape-0-only render this replaced. Retry at one shape, and remember what
                // compiled so drawFrame uploads exactly that many.
                if (maskShapes <= 1 || !buildSteps(plan, 1)) {
                    degraded = true;
                    return false;
                }
                compiledShapes = 1;
            } else {
                compiledShapes = maskShapes;
            }
            sourceKey = key.toString();
            return true;
        }

        /** How many shapes the compiled programs' arrays hold. @see #ensurePrograms */
        private int compiledShapes = 1;

        /** Compile every render of {@code plan} at {@code maskShapes}; false if any failed. */
        private boolean buildSteps(@NonNull FxCompiler.Plan plan, int maskShapes) {
            releaseSteps();
            try {
                for (int i = 0; i < plan.passes.size(); i++) {
                    FxCompiler.Pass pa = plan.passes.get(i);
                    boolean lastPass = i == plan.passes.size() - 1;
                    int renders = Math.max(1, pa.repeats);
                    for (int r = 0; r < renders; r++) {
                        boolean lastRender = lastPass && r == renders - 1;
                        GlProgram prog = compile(pa, lastRender, maskShapes);
                        // Horizontal first, then vertical — the order the kernel weights assume.
                        float dx = (renders > 1 && r == 1) ? 0f : 1f;
                        float dy = (renders > 1 && r == 1) ? 1f : 0f;
                        steps.add(new Step(prog, pa, dx, dy));
                    }
                }
            } catch (Exception e) {
                FLog.w("AdjustmentLayer", "shader compile failed at " + maskShapes
                        + " mask shape(s): " + e);
                releaseSteps();
                return false;
            }
            return true;
        }

        /**
         * Build one pass's program.
         *
         * <p>The source itself comes from {@link FxGlSource}, shared with the live GL preview so
         * the two renderers cannot drift. The splice order that assembly gets right — mask
         * functions and composite uniforms AHEAD of main(), because GLSL ES 1.00 requires
         * declaration before use — is what the first export A/B caught.</p>
         */
        @NonNull
        private GlProgram compile(@NonNull FxCompiler.Pass pa, boolean composite,
                                  int maskShapes) throws GlUtil.GlException {
            GlProgram prog = new GlProgram(VERTEX_SHADER,
                    FxGlSource.fragment(pa, KERNEL_HALF, composite, maskShapes));
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

        private static final String PASSTHROUGH_FRAGMENT = FxGlSource.PASSTHROUGH_FRAGMENT;

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
