package com.fadcam.ui.faditor.export;

import android.content.Context;
import android.opengl.GLES20;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.util.GlProgram;
import androidx.media3.common.util.GlUtil;
import androidx.media3.common.util.Size;
import androidx.media3.effect.BaseGlShaderProgram;
import androidx.media3.effect.GlEffect;

import com.fadcam.ui.faditor.model.Clip;
import com.fadcam.ui.faditor.model.CompositingSpec;

/**
 * M-EXPORT-2 — the export compositor for EVERY PiP clip (one instance per clip,
 * NORMAL included since the z-unification fix): inserted into the master item's
 * effect list in track z-order (bottom→top), BEFORE the text/caption
 * {@code OverlayEffect}, so ALL PiPs share one z authority and keep the preview
 * rule (video < PiPs-in-z-order < sprites/text/captions). NORMAL renders as
 * plain SRC_OVER (shader mode 0); MULTIPLY/SCREEN/OVERLAY/ADD blend against the
 * ACCUMULATED frame — the capability a {@code BitmapOverlay} cannot express and
 * the reason PiPs left {@code CompositeExportOverlay} (drawing NORMAL there and
 * blends here z-inverted any project that interleaved them).
 *
 * <p>Follows {@code GlTransitionExportEffect}/{@code GlTransitionShaderProgram}
 * exactly: a {@link BaseGlShaderProgram} whose fragment shader samples the frame
 * plus a {@link PipFrameOverlay} texture (the PiP frame already positioned on the
 * proven Canvas path, so the shader blends with plain full-frame UVs — the export
 * transition wrapper samples its bitmap overlay unflipped, device-proven).
 * Overlay pixels arrive PREMULTIPLIED (GLUtils upload); the shader unpremultiplies
 * before applying the blend equation, then mixes by alpha (which already carries
 * the clip's keyframed opacity via the Canvas paint).</p>
 *
 * <p>COMPOSITING FAMILY (FEEDBACK_20260702 §C) — all three stages modulate the
 * src alpha BEFORE the blend mix, in fixed order:</p>
 * <ol>
 *   <li>MASKS run on the Canvas side ({@code PipFrameOverlay} clips through
 *       {@code MaskPathBuilder}) — the shader never sees masked pixels.</li>
 *   <li>CHROMA KEY: straight-RGB distance to the key color →
 *       {@code smoothstep(tolerance, tolerance+fuzziness)} soft matte, plus a
 *       post-key alpha offset (choke/spread).</li>
 *   <li>TRACK MATTE: a SECOND {@link PipFrameOverlay} renders the matte peer
 *       clip positioned by ITS OWN transform; the shader multiplies src alpha
 *       by matte LUMA × matte alpha. Gated per-frame on the CPU
 *       ({@code PipFrameOverlay#activeAt}) so a recipient renders UNMATTED
 *       outside the matte's time window (B3's overlap rule) — the shader
 *       cannot tell an inactive matte from a black one.</li>
 * </ol>
 *
 * <p>Preview shows NORMAL/unkeyed/unmatted compositing until a preview
 * approximation lands — documented per Part 10 probe #4: export is ground
 * truth. (Preview MASKS do land with this slice via the preview clip path.)</p>
 */
public final class BlendModeGlEffect implements GlEffect {

    private final Context context;
    private final Clip clip;
    private final long editorTimeOffsetMs;
    @Nullable private final Clip matteClip;

    // NOTE: the old 2-arg constructor was DELETED rather than kept for convenience. It
    // hard-coded editorTimeOffsetMs = 0, so any future caller would have silently reintroduced
    // the §2d drift with no compile error and no test failure — a trap left lying in the road.
    // Callers must state the offset.

    /** @param matteClip the resolved matte peer (from {@code compositing.mattePeerId}),
     *                   or null. Resolution + peer-hiding is ExportManager's job. */
    public BlendModeGlEffect(@NonNull Context context, @NonNull Clip clip,
                             @Nullable Clip matteClip, long editorTimeOffsetMs) {
        this.context = context.getApplicationContext();
        this.clip = clip;
        this.matteClip = matteClip;
        this.editorTimeOffsetMs = editorTimeOffsetMs;
    }

    @NonNull
    @Override
    public BaseGlShaderProgram toGlShaderProgram(@NonNull Context ignored, boolean useHdr)
            throws VideoFrameProcessingException {
        if (useHdr) {
            throw new VideoFrameProcessingException("HDR blend modes are not supported");
        }
        return new Program(context, clip, matteClip, editorTimeOffsetMs);
    }

    private static final class Program extends BaseGlShaderProgram {

        private static final String VERTEX_SHADER =
                "#version 100\n"
                + "attribute vec4 aFramePosition;\n"
                + "varying vec2 vTexSamplingCoord;\n"
                + "void main() {\n"
                + "  gl_Position = aFramePosition;\n"
                + "  vTexSamplingCoord = aFramePosition.xy * 0.5 + 0.5;\n"
                + "}\n";

        /**
         * Per-object FX (SPEC_ADJUSTMENT_LAYERS_FX M7), spliced into THIS clip's shader.
         *
         * <p><b>The no-FX case returns the string that shipped, untouched.</b> A PiP that
         * carries no effects compiles exactly the source it always did — the same gate
         * discipline the mask fold and the adjustment chain already use, so M7 is provably
         * inert for every existing project.</p>
         *
         * <p><b>Only the FUSED pass.</b> A SAMPLER card would need the object rendered to its
         * own FBO first, which is the adjustment layer's machinery and not this class's job.
         * Such a card is skipped; {@code FxPreviewTier} is what tells the UI so.</p>
         */
        @NonNull
        private static String fragmentFor(@NonNull Clip clip) {
            com.fadcam.ui.faditor.fx.FxStack stack = clip.getFx();
            if (stack == null || stack.active().isEmpty()) return FRAGMENT_SHADER;
            com.fadcam.ui.faditor.fx.FxCompiler.Plan plan =
                    com.fadcam.ui.faditor.fx.FxCompiler.plan(stack);
            com.fadcam.ui.faditor.fx.FxCompiler.Pass fused = null;
            for (com.fadcam.ui.faditor.fx.FxCompiler.Pass p : plan.passes) {
                if (!p.sampler) { fused = p; break; }
            }
            if (fused == null) return FRAGMENT_SHADER;

            String emitted = com.fadcam.ui.faditor.fx.FxCompiler.emitGlsl(fused, 8);
            int mainAt = emitted.indexOf("void main()");
            if (mainAt < 0) return FRAGMENT_SHADER;
            // Everything the compiler declared BEFORE its entry point — uniforms, the prelude,
            // the blend authority, the per-card functions. Its own main() is discarded: this
            // shader already has one, and here the subject is one object's colour rather than
            // a whole frame.
            String decls = emitted.substring(0, mainAt)
                    // Already declared by this shader; declaring either twice fails to compile.
                    .replace("uniform sampler2D uTexSampler;\n", "")
                    .replace("varying vec2 vFxUv;\n", "")
                    .replace("precision mediump float;\n", "")
                    .replace("precision highp float;\n", "");

            StringBuilder fold = new StringBuilder();
            for (com.fadcam.ui.faditor.fx.FxInstance card : fused.cards) {
                fold.append("  fxc = fxBlendOver(fxc, fx").append(card.slot)
                        .append("(ovc, fxc), ")
                        .append(com.fadcam.ui.faditor.fx.FxCompiler.foldOpacityName(card))
                        .append(", ")
                        .append(com.fadcam.ui.faditor.fx.FxCompiler.foldBlendName(card))
                        .append(");\n");
            }
            // AFTER the key, deliberately. Keying measures distance from a colour in the SOURCE
            // image, so inverting or grading the object first would stop a green screen being
            // green and the key would silently miss.
            String apply = "  vec4 fxc = vec4(sc, 1.0);\n" + fold + "  sc = fxc.rgb;\n";
            return FRAGMENT_SHADER
                    .replace("varying vec2 vTexSamplingCoord;\n",
                            "varying vec2 vTexSamplingCoord;\n" + decls)
                    .replace("  if (uMatteOn > 0.5) {", apply + "  if (uMatteOn > 0.5) {");
        }

        private static final String FRAGMENT_SHADER =
                "#version 100\n"
                + "precision mediump float;\n"
                + "varying vec2 vTexSamplingCoord;\n"
                + "uniform sampler2D uVideoTexSampler0;\n"
                + "uniform sampler2D uOverlayTexSampler0;\n"
                + "uniform sampler2D uMatteTexSampler0;\n"
                + "uniform float uBlendMode;\n" // float: ES2 int uniforms are patchy on old drivers
                + "uniform vec3 uKeyColor;\n"
                // x=enabled(0/1), y=tolerance, z=fuzziness, w=offset — packed by ChromaKey
                + "uniform vec4 uKeyParams;\n"
                + "uniform float uMatteOn;\n"
                // The key itself is NOT written here. It is compiled from the one shared source
                // so the live preview tier and this export effect cannot drift apart — see
                // ChromaKey's class note for why that mattered enough to centralise.
                + com.fadcam.ui.faditor.model.ChromaKey.GLSL_KEY_FN
                // Nor is the blend written here, for the same reason and by the same pattern: the
                // FX compiler folds effect cards with these exact equations, so OVERLAY's
                // per-channel branch exists in ONE place. The text below used to be inline and was
                // moved without a character changed — BlendModesTest pins that byte-for-byte.
                // NORMAL relies on the mix-by-alpha further down being SRC_OVER already.
                + com.fadcam.ui.faditor.model.BlendModes.GLSL_BLEND_FN
                + "void main() {\n"
                + "  vec4 base = texture2D(uVideoTexSampler0, vTexSamplingCoord);\n"
                // Bitmap textures upload Y-DOWN (row 0 = top) while the frame
                // UVs are Y-UP — sample the overlay V-flipped or every PiP
                // composites upside-down/mirrored-in-place. CONFIRMED on
                // device: pre-fix, a PiP authored at y=.378 exported at
                // y=.622 (=1-.378) while the preview showed .378 — the
                // z-unification A/B luma proof was blind to it (both sides
                // flipped identically). Flipping V here un-flips position,
                // rotation and masks as one finished image.
                + "  vec2 ovc = vec2(vTexSamplingCoord.x, 1.0 - vTexSamplingCoord.y);\n"
                + "  vec4 src = texture2D(uOverlayTexSampler0, ovc);\n"
                + "  vec3 sc = src.rgb / max(src.a, 0.001);\n" // unpremultiply
                + "  float a = src.a;\n"
                // Chroma key on the STRAIGHT color: distance → soft matte. The enabled test
                // lives inside the shared function, so there is no second place to forget it.
                + "  a = fadKeyAlpha(sc, a, uKeyColor, uKeyParams);\n"
                + "  if (uMatteOn > 0.5) {\n"
                // Luma matte: the matte frame is premultiplied; luma of its
                // straight color × its alpha (empty regions matte to 0).
                + "    vec4 mt = texture2D(uMatteTexSampler0, ovc);\n"
                + "    vec3 mc = mt.rgb / max(mt.a, 0.001);\n"
                + "    a *= dot(mc, vec3(0.299, 0.587, 0.114)) * mt.a;\n"
                + "  }\n"
                + "  vec3 outc = mix(base.rgb, clamp(blendPix(base.rgb, sc), 0.0, 1.0), a);\n"
                + "  gl_FragColor = vec4(outc, base.a);\n"
                + "}\n";

        private final GlProgram glProgram;
        private final PipFrameOverlay overlay;
        private final GlPipFrameOverlay glOverlay;
        @Nullable private final PipFrameOverlay matteOverlay;
        @Nullable private final GlPipFrameOverlay glMatteOverlay;
        private final float mode;
        private final float[] keyColor;
        private final float[] keyParams;
        /** Kept for its FX uniform values; the geometry all lives in the overlay. */
        @NonNull private final Clip fxClip;
        @NonNull private final Clip clip;
        @Nullable private final Clip matteClip;

        Program(@NonNull Context context, @NonNull Clip clip, @Nullable Clip matteClip,
                 long editorTimeOffsetMs)
                 throws VideoFrameProcessingException {
            super(/* useHighPrecisionColorComponents= */ false, /* texturePoolCapacity= */ 1);
            this.clip = clip;
            this.matteClip = matteClip;
            this.overlay = new PipFrameOverlay(context, clip, editorTimeOffsetMs);
            // Option 2 (§4): masked PiPs stay on the CPU Canvas path where MaskPathBuilder clips.
            // Only unmasked PiPs are routed through the Surface → GL texture path.
            // Measurement (PipFrameStats, 2026-08-28): 91% of JoyRaptor's overlay clips are unmasked,
            // so option 2 removes ~91% of the remaining convertMs while keeping the fallback trivial.
            // Full mask GL (option 1, second texture for feather) remains the follow-up for the 9%.
            this.glOverlay = new GlPipFrameOverlay(context, clip, editorTimeOffsetMs);
            this.matteOverlay = matteClip != null
                    ? new PipFrameOverlay(context, matteClip, editorTimeOffsetMs) : null;
            this.glMatteOverlay = matteClip != null
                    ? new GlPipFrameOverlay(context, matteClip, editorTimeOffsetMs) : null;
            // The wire-value → shader-code mapping moved out with the equations it selects; a
            // mode string that means 3 here and 3 in the FX fold is the whole point of one table.
            this.fxClip = clip;
            this.mode = com.fadcam.ui.faditor.model.BlendModes.modeCode(clip.getOverlayBlendMode());
            // Packed by the shared authority, not unpacked by hand here — the preview tier
            // packs the identical uniforms from the identical spec, so a clamp added on one
            // side can never be missing on the other.
            CompositingSpec spec = clip.getCompositing();
            this.keyColor = com.fadcam.ui.faditor.model.ChromaKey.packColor(spec);
            this.keyParams = com.fadcam.ui.faditor.model.ChromaKey.packParams(spec);
            try {
                // Per-clip source: identical to FRAGMENT_SHADER unless this object carries FX.
                this.glProgram = new GlProgram(VERTEX_SHADER, fragmentFor(clip));
                this.glProgram.setBufferAttribute("aFramePosition",
                        GlUtil.getNormalizedCoordinateBounds(), 4);
            } catch (Exception e) {
                throw new VideoFrameProcessingException(e);
            }
        }

        @NonNull
        @Override
        public Size configure(int inputWidth, int inputHeight) {
            Size size = new Size(Math.max(1, inputWidth), Math.max(1, inputHeight));
            overlay.configure(size);
            glOverlay.configure(size);
            if (matteOverlay != null) matteOverlay.configure(size);
            if (glMatteOverlay != null) glMatteOverlay.configure(size);
            return size;
        }

        @Override
        public void drawFrame(int inputTexId, long presentationTimeUs)
                throws VideoFrameProcessingException {
            GlErrors.drain("pending when BlendModeGlEffect began (left by an earlier step)");
            try {
                glProgram.use();
                glProgram.setSamplerTexIdUniform("uVideoTexSampler0", inputTexId, 0);
                // §4 option 2: unmasked PiPs use the Surface → GL path; masked stay on Canvas.
                // The GL texture is already full-frame positioned (FBO blit), so the shader
                // keeps its plain full-frame UVs and v-flip handling unchanged.
                boolean useGl = !PipFrameStats.isMasked(clip) && !glOverlay.isDegraded();
                int overlayTex = useGl ? glOverlay.getTextureId(presentationTimeUs) : 0;
                if (overlayTex == 0) overlayTex = overlay.getTextureId(presentationTimeUs);
                glProgram.setSamplerTexIdUniform("uOverlayTexSampler0", overlayTex, 1);
                boolean matteOn = false;
                int matteTex = overlayTex; // placeholder
                if (matteClip != null) {
                    boolean matteMasked = PipFrameStats.isMasked(matteClip);
                    boolean useGlMatte = !matteMasked && glMatteOverlay != null && !glMatteOverlay.isDegraded();
                    if (matteOverlay != null) matteOn = matteOverlay.activeAt(presentationTimeUs);
                    else if (glMatteOverlay != null) matteOn = glMatteOverlay.activeAt(presentationTimeUs);
                    if (matteOn) {
                        int glMatteTex = useGlMatte ? glMatteOverlay.getTextureId(presentationTimeUs) : 0;
                        if (glMatteTex != 0) matteTex = glMatteTex;
                        else if (matteOverlay != null) matteTex = matteOverlay.getTextureId(presentationTimeUs);
                    }
                } else if (matteOverlay != null) {
                    matteOn = matteOverlay.activeAt(presentationTimeUs);
                    if (matteOn) matteTex = matteOverlay.getTextureId(presentationTimeUs);
                }
                // The matte texture unit must always be bound on ES2 — reuse the
                // overlay texture as a harmless placeholder while inactive.
                glProgram.setSamplerTexIdUniform("uMatteTexSampler0", matteTex, 2);
                glProgram.setFloatUniform("uBlendMode", mode);
                glProgram.setFloatsUniform("uKeyColor", keyColor);
                glProgram.setFloatsUniform("uKeyParams", keyParams);
                glProgram.setFloatUniform("uMatteOn", matteOn ? 1f : 0f);
                // Per-object FX (M7). Resolved at the playhead so a keyed parameter animates,
                // exactly as an adjustment layer's does — same stack, same resolver.
                setFxUniforms(presentationTimeUs);
                glProgram.bindAttributesAndUniforms();
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
                GlUtil.checkGlError();
            } catch (Exception e) {
                throw new VideoFrameProcessingException(e);
            }
        }

        /**
         * Upload this object's FX parameters.
         *
         * <p>Each set is GUARDED: an unused uniform is stripped by the driver, and media3 looks
         * names up in the LINKED program, so setting one it removed throws. That cost a whole
         * export cycle to find on the adjustment layer; it is not going to cost another here.</p>
         *
         * <p>Silent no-op when the clip has no FX, because then the shader is the original
         * string and none of these names exist at all.</p>
         */
        private void setFxUniforms(long presentationTimeUs) {
            com.fadcam.ui.faditor.fx.FxStack stack = fxClip.getFx();
            if (stack == null || stack.active().isEmpty()) return;
            long editorMs = presentationTimeUs / 1000L;
            com.fadcam.ui.faditor.fx.FxStack resolved = stack.resolveAt(editorMs);
            com.fadcam.ui.faditor.fx.FxCompiler.Plan plan =
                    com.fadcam.ui.faditor.fx.FxCompiler.plan(resolved);
            for (com.fadcam.ui.faditor.fx.FxCompiler.Pass p : plan.passes) {
                if (p.sampler) continue;   // not compiled into this shader — see fragmentFor
                for (com.fadcam.ui.faditor.fx.FxUniforms.Value v
                        : com.fadcam.ui.faditor.fx.FxUniforms.forPass(p)) {
                    try {
                        if (v.components() == 1) glProgram.setFloatUniform(v.name, v.data[0]);
                        else glProgram.setFloatsUniform(v.name, v.data);
                    } catch (RuntimeException ignored) {
                        // Stripped by the driver; harmless.
                    }
                }
                break;   // only the first fused pass is in this shader
            }
        }

        @Override
        public void release() throws VideoFrameProcessingException {
            try {
                overlay.release();
                glOverlay.release();
                if (matteOverlay != null) matteOverlay.release();
                if (glMatteOverlay != null) glMatteOverlay.release();
                glProgram.delete();
                super.release();
            } catch (Exception e) {
                throw new VideoFrameProcessingException(e);
            }
        }
    }
}
