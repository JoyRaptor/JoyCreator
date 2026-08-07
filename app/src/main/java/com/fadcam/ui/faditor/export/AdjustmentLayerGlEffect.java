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
 * <p><b>SINGLE PASS in this version, and it says so rather than pretending.</b> Only cards the
 * compiler can FUSE — pointwise and generator — are rendered here; that is 9 of the 12 shipped
 * effects. A SAMPLER card (the two blurs, RGB shift) needs a finished image to read neighbours
 * from, which means real ping-pong FBOs; until that lands such a card is SKIPPED with an
 * {@link FLog} warning rather than rendered wrongly. A wrong blur that looks plausible is worse
 * than an absent one, because nobody goes looking for it.</p>
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

        @androidx.annotation.Nullable private GlProgram program;
        @androidx.annotation.Nullable private FxCompiler.Pass pass;
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
                if (!ensureProgram(resolved)) {
                    passthrough(inputTexId);
                    return;
                }
                GlProgram p = program;
                if (p == null || pass == null) { passthrough(inputTexId); return; }
                p.use();
                p.setSamplerTexIdUniform("uTexSampler", inputTexId, 0);
                p.setFloatsUniform("uTexel", new float[]{1f / width, 1f / height});
                p.setFloatUniform("uAspect", (float) width / (float) height);
                p.setFloatUniform("uTime", editorMs / 1000f);
                p.setFloatsUniform("uDir", new float[]{1f, 0f});
                for (FxUniforms.Value v : FxUniforms.forPass(pass)) {
                    if (v.components() == 1) p.setFloatUniform(v.name, v.data[0]);
                    else p.setFloatsUniform(v.name, v.data);
                }
                // The mask/opacity mix factor — see the class note. Packed flat so the shader
                // needs no loop over a shape array it may not have.
                CompositingSpec cs = layer.getCompositing();
                float[] geo = MaskSdf.packShapes(cs, width, height);
                p.setFloatUniform("uLayerOpacity", layer.opacityAt(editorMs));
                p.setFloatUniform("uMaskCount",
                        cs == null || cs.masks.isEmpty() ? 0f : 1f);
                p.setFloatsUniform("uMaskGeo", new float[]{geo[0], geo[1], geo[2], geo[3]});
                p.setFloatsUniform("uMaskRot", new float[]{geo[4], geo[5]});
                p.setFloatUniform("uMaskCorner", geo[6]);
                p.setFloatUniform("uMaskFeather", geo[7]);
                p.setFloatUniform("uMaskInvert",
                        cs != null && cs.invertMasks ? 1f : 0f);
                p.bindAttributesAndUniforms();
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
                GlUtil.checkGlError();
            } catch (Exception e) {
                // A lost grade beats a lost export. Latch so a per-frame failure does not spam.
                if (!degraded) {
                    FLog.w("AdjustmentLayer", "degrading to passthrough: " + e);
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
         * Compile the fused pass for {@code stack}, or report that there is nothing to draw.
         *
         * @return false when the stack has no fusable card — the caller then passes the frame
         *         through untouched rather than binding a program that would do nothing.
         */
        private boolean ensureProgram(@NonNull FxStack stack)
                throws VideoFrameProcessingException {
            FxCompiler.Plan plan = FxCompiler.plan(stack);
            FxCompiler.Pass fused = null;
            int skipped = 0;
            for (FxCompiler.Pass candidate : plan.passes) {
                if (candidate.sampler) { skipped++; continue; }
                if (fused == null) fused = candidate;
                else skipped++;
            }
            if (skipped > 0 && sourceKey == null) {
                // Said ONCE, at compile time, not per frame. Naming the count is what makes the
                // difference between "the blur did nothing" and "this build cannot do blurs yet".
                FLog.w("AdjustmentLayer",
                        skipped + " pass(es) skipped: multi-pass FX are not wired into export "
                                + "yet (SPEC_ADJUSTMENT_LAYERS_FX M4 remainder)");
            }
            if (fused == null) return false;
            String key = FxUniforms.sourceKey(fused, 8);
            if (program != null && key.equals(sourceKey)) { pass = fused; return true; }
            String fragment = "#version 100\n"
                    + FxCompiler.emitGlsl(fused, 8)
                    + MaskSdf.GLSL_MASK_FN;
            // The composite is appended rather than emitted by FxCompiler: the compiler builds
            // an effect chain, and "fold my result back over what was already there, where the
            // mask allows" is the ADJUSTMENT LAYER's semantic, not the stack's.
            try {
                program = new GlProgram(withComposite(fragment), VERTEX_SHADER);
            } catch (Exception e) {
                FLog.w("AdjustmentLayer", "shader compile failed, passing through: " + e);
                degraded = true;
                return false;
            }
            sourceKey = key;
            pass = fused;
            return true;
        }

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
                    "  vec4 base = texture2D(uTexSampler, fxClamp(vFxUv));\n"
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
                    passProgram = new GlProgram(PASSTHROUGH_FRAGMENT, VERTEX_SHADER);
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
                if (program != null) program.delete();
                if (passProgram != null) passProgram.delete();
            } catch (Exception e) {
                throw new VideoFrameProcessingException(e);
            }
        }
    }
}
