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

import com.fadcam.ui.faditor.model.BlendModes;
import com.fadcam.ui.faditor.model.TextOverlayItem;

/**
 * Blend modes for an IMAGE overlay: the picture is composited against the accumulated frame with
 * MULTIPLY / SCREEN / OVERLAY / ADD, which is the one thing a {@code BitmapOverlay} cannot express
 * — a Canvas has no video underneath it to blend with.
 *
 * <p>Emitted only for an image overlay whose mode is NOT normal. Everything else stays on
 * {@link CompositeExportOverlay}'s canvas path exactly as it always did, so this is provably inert
 * for every project that has not chosen a blend mode.</p>
 *
 * <p><b>Now also carries the chroma key and per-object FX</b> — this note used to say it
 * deliberately did not, with "when those features become real for images, they arrive here". They
 * did, and they arrived here. Both are DEVICE-VERIFIED by measuring exported frames against a dump
 * of the overlay bitmap rather than by eye: with an invert card, 174 of 190 sampled points inside
 * the mask are closer to the INVERSE of the un-effected image than to it; with a max-tolerance key
 * the image leaves the frame entirely. Reading those frames by eye is what produced a wrong
 * conclusion and a needless revert first time round — the fixture's overlay is a photo of the same
 * scene as the video, so "the image, inverted" and "the video, inverted" look alike.
 *
 * <p>Track MATTES remain PiP-only: an image has no peer clip to matte against.</p>
 *
 * <p><b>Z-ORDER CAVEAT, stated plainly.</b> Chain position is paint order, so a blended image
 * composites where this effect sits in the chain — above the video and every PiP, below the
 * canvas overlay pass. An image overlay that a user ordered ABOVE a text overlay will therefore
 * appear BENEATH it once a blend mode is chosen. This is the same compromise
 * {@code TextFxGlEffect} already makes for text that carries effects, and it is why blend stays
 * an opt-in rather than something every image goes through.</p>
 *
 * <p><b>The preview now runs all three.</b> This note used to say the preview showed the image
 * NORMAL, and that the fix would need image overlays fed into the GL composite as 2D textures.
 * That is exactly what happened: {@code TextOverlayLayer.fxPipFor} builds a
 * {@code FxPreviewTextureView.Pip} per image whose {@code wantsGlExport()} is true, backed by the
 * same decoded bitmap the {@code ImageView} shows, and the composite compiles the same FX splice,
 * the same {@code BlendModes} equations and the same {@code ChromaKey} function this effect does.
 * Measured on a device rather than read by eye — see this class's note above for why that
 * distinction is not pedantry here.</p>
 */
final class ImageBlendGlEffect implements GlEffect {

    private final Context context;
    private final TextOverlayItem item;
    private final long projectDurationMs;
    private final long editorTimeOffsetMs;

    ImageBlendGlEffect(@NonNull Context context, @NonNull TextOverlayItem item,
                       long projectDurationMs, long editorTimeOffsetMs) {
        this.context = context.getApplicationContext();
        this.item = item;
        this.projectDurationMs = projectDurationMs;
        this.editorTimeOffsetMs = editorTimeOffsetMs;
    }

    @NonNull
    @Override
    public BaseGlShaderProgram toGlShaderProgram(@NonNull Context ignored, boolean useHdr)
            throws VideoFrameProcessingException {
        if (useHdr) {
            throw new VideoFrameProcessingException("HDR blend modes are not supported");
        }
        return new Program(context, item, projectDurationMs, editorTimeOffsetMs);
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

        private static final String FRAGMENT_SHADER =
                "#version 100\n"
                + "precision mediump float;\n"
                + "varying vec2 vTexSamplingCoord;\n"
                + "uniform sampler2D uVideoTexSampler0;\n"
                + "uniform sampler2D uOverlayTexSampler0;\n"
                + "uniform float uBlendMode;\n" // float: ES2 int uniforms are patchy on old drivers
                + "uniform vec3 uKeyColor;\n"
                // x=enabled(0/1), y=tolerance, z=fuzziness, w=offset — packed by ChromaKey
                + "uniform vec4 uKeyParams;\n"
                // The key itself is NOT written here, for the same reason BlendModeGlEffect does
                // not write it: one shared source means the preview tier and both export paths
                // cannot drift apart on what "green enough" means.
                + com.fadcam.ui.faditor.model.ChromaKey.GLSL_KEY_FN
                // The blend equations are NOT written here — same reason BlendModeGlEffect does
                // not write them. One authority means OVERLAY's per-channel branch exists once,
                // and BlendModesTest pins the text byte-for-byte.
                + BlendModes.GLSL_BLEND_FN
                + "void main() {\n"
                + "  vec4 base = texture2D(uVideoTexSampler0, vTexSamplingCoord);\n"
                // Bitmap textures upload Y-DOWN (row 0 = top) while the frame UVs are Y-UP —
                // sample the overlay V-flipped or the image composites upside-down. Device-proven
                // on the PiP path, where the pre-fix symptom was an overlay authored at y=.378
                // exporting at y=.622.
                + "  vec2 ovc = vec2(vTexSamplingCoord.x, 1.0 - vTexSamplingCoord.y);\n"
                + "  vec4 src = texture2D(uOverlayTexSampler0, ovc);\n"
                + "  vec3 sc = src.rgb / max(src.a, 0.001);\n" // unpremultiply
                // Chroma key on the STRAIGHT colour, BEFORE any effect grades it — keying measures
                // distance from a colour in the SOURCE image, so inverting or grading first would
                // stop a green screen being green and the key would silently miss. The enabled
                // test lives inside the shared function, so there is no second place to forget it.
                + "  float a = fadKeyAlpha(sc, src.a, uKeyColor, uKeyParams);\n"
                // Mix by the overlay's own alpha, which already carries the item's keyframed
                // opacity and its entrance animation via the Canvas paint, and now the key.
                + "  vec3 outc = mix(base.rgb, clamp(blendPix(base.rgb, sc), 0.0, 1.0), a);\n"
                + "  gl_FragColor = vec4(outc, base.a);\n"
                + "}\n";

        /**
         * Per-object FX for an IMAGE overlay, spliced into this shader — the same construction
         * {@code BlendModeGlEffect.fragmentFor} performs for a PiP, against the same
         * {@code FxStack} type, so the two renderers cannot drift into different fold orders.
         *
         * <p><b>The no-FX case returns the string that shipped, untouched.</b> An image that
         * carries no effects compiles exactly the source it always did, so this is provably inert
         * for every existing project — the same gate discipline the blend path itself uses.</p>
         *
         * <p><b>Only the FUSED pass.</b> A SAMPLER card (blur and friends) needs the object
         * rendered to its own FBO first, which is the adjustment layer's machinery; such a card is
         * skipped here exactly as it is for a PiP, and {@code FxPreviewTier} is what tells the UI
         * so. That is a smaller gap than the whole tab being inert, and an honest one.</p>
         */
        @NonNull
        private static String fragmentFor(@NonNull TextOverlayItem item) {
            com.fadcam.ui.faditor.fx.FxStack stack = item.getFx();
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
            // Everything the compiler declared BEFORE its entry point; its own main() is dropped
            // because this shader has one and the subject here is one object's colour.
            String decls = emitted.substring(0, mainAt)
                    // Already declared below; declaring either twice fails to compile.
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
            // Applied to the UNPREMULTIPLIED colour and BEFORE the blend, so an effect grades the
            // picture and the blend mode then composites the graded picture — the order the drawer
            // shows the two tabs in, and the order the preview will have to match.
            String apply = "  vec4 fxc = vec4(sc, 1.0);\n" + fold + "  sc = fxc.rgb;\n";
            return FRAGMENT_SHADER
                    .replace("varying vec2 vTexSamplingCoord;\n",
                            "varying vec2 vTexSamplingCoord;\n" + decls)
                    .replace("  vec3 outc = mix(", apply + "  vec3 outc = mix(");
        }

        private final GlProgram glProgram;
        private final ImageOverlayFrameOverlay overlay;
        private final float mode;
        private final float[] keyColor;
        private final float[] keyParams;
        /** Kept for its FX uniform values; the geometry all lives in the overlay. */
        @NonNull private final TextOverlayItem fxItem;

        Program(@NonNull Context context, @NonNull TextOverlayItem item,
                long projectDurationMs, long editorTimeOffsetMs)
                throws VideoFrameProcessingException {
            super(/* useHighPrecisionColorComponents= */ false, /* texturePoolCapacity= */ 1);
            this.overlay = new ImageOverlayFrameOverlay(
                    context, item, projectDurationMs, editorTimeOffsetMs);
            this.mode = BlendModes.modeCode(item.getOverlayBlendMode());
            // Packed by the shared authority, not unpacked by hand here — the preview tier and the
            // PiP path pack the identical uniforms from the identical spec, so a clamp added on
            // one side can never be missing on another.
            com.fadcam.ui.faditor.model.CompositingSpec spec = item.getCompositing();
            this.keyColor = com.fadcam.ui.faditor.model.ChromaKey.packColor(spec);
            this.keyParams = com.fadcam.ui.faditor.model.ChromaKey.packParams(spec);
            this.fxItem = item;
            try {
                this.glProgram = new GlProgram(VERTEX_SHADER, fragmentFor(item));
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
            return size;
        }

        @Override
        public void drawFrame(int inputTexId, long presentationTimeUs)
                throws VideoFrameProcessingException {
            try {
                glProgram.use();
                glProgram.setSamplerTexIdUniform("uVideoTexSampler0", inputTexId, 0);
                glProgram.setSamplerTexIdUniform("uOverlayTexSampler0",
                        overlay.getTextureId(presentationTimeUs), 1);
                glProgram.setFloatUniform("uBlendMode", mode);
                glProgram.setFloatsUniform("uKeyColor", keyColor);
                glProgram.setFloatsUniform("uKeyParams", keyParams);
                // Per-object FX, resolved at the playhead so a keyed parameter animates — the same
                // stack and the same resolver an adjustment layer and a PiP use.
                setFxUniforms(presentationTimeUs);
                glProgram.bindAttributesAndUniforms();
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
                GlUtil.checkGlError();
            } catch (Exception e) {
                throw new VideoFrameProcessingException(e);
            }
        }

        /**
         * Upload this image's FX parameters.
         *
         * <p>Each set is GUARDED: an unused uniform is stripped by the driver and media3 looks
         * names up in the LINKED program, so setting one it removed throws. That cost a whole
         * export cycle to find on the adjustment layer and again on the PiP; it does not get to
         * cost a third.</p>
         *
         * <p>Silent no-op with no FX, because then the shader is the original string and none of
         * these names exist at all.</p>
         */
        private void setFxUniforms(long presentationTimeUs) {
            com.fadcam.ui.faditor.fx.FxStack stack = fxItem.getFx();
            if (stack == null || stack.active().isEmpty()) return;
            com.fadcam.ui.faditor.fx.FxStack resolved = stack.resolveAt(presentationTimeUs / 1000L);
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
                glProgram.delete();
                super.release();
            } catch (Exception e) {
                throw new VideoFrameProcessingException(e);
            }
        }
    }
}
