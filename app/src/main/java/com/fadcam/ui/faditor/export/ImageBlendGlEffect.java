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
 * <p><b>Deliberately narrower than {@link BlendModeGlEffect}.</b> That class carries chroma key,
 * track mattes and per-object FX because a PiP has all three. An image overlay has none of them in
 * either renderer today (its Mask, Chroma key and Effects tabs are labelled as inert), so
 * inheriting that machinery would mean inheriting three uniform sets nothing writes and a shader
 * three times the size. When those features become real for images, they arrive here.</p>
 *
 * <p><b>Z-ORDER CAVEAT, stated plainly.</b> Chain position is paint order, so a blended image
 * composites where this effect sits in the chain — above the video and every PiP, below the
 * canvas overlay pass. An image overlay that a user ordered ABOVE a text overlay will therefore
 * appear BENEATH it once a blend mode is chosen. This is the same compromise
 * {@code TextFxGlEffect} already makes for text that carries effects, and it is why blend stays
 * an opt-in rather than something every image goes through.</p>
 *
 * <p><b>Preview shows the image NORMAL.</b> Same standing caveat as a PiP's blend, and the reason
 * the drawer says so: making this live in the preview needs image overlays fed into the GL
 * composite as 2D textures, which is the multi-PiP z-order work, not this.</p>
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
                // Mix by the overlay's own alpha, which already carries the item's keyframed
                // opacity and its entrance animation via the Canvas paint.
                + "  vec3 outc = mix(base.rgb, clamp(blendPix(base.rgb, sc), 0.0, 1.0), src.a);\n"
                + "  gl_FragColor = vec4(outc, base.a);\n"
                + "}\n";

        private final GlProgram glProgram;
        private final ImageOverlayFrameOverlay overlay;
        private final float mode;

        Program(@NonNull Context context, @NonNull TextOverlayItem item,
                long projectDurationMs, long editorTimeOffsetMs)
                throws VideoFrameProcessingException {
            super(/* useHighPrecisionColorComponents= */ false, /* texturePoolCapacity= */ 1);
            this.overlay = new ImageOverlayFrameOverlay(
                    context, item, projectDurationMs, editorTimeOffsetMs);
            this.mode = BlendModes.modeCode(item.getOverlayBlendMode());
            try {
                this.glProgram = new GlProgram(VERTEX_SHADER, FRAGMENT_SHADER);
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
                glProgram.bindAttributesAndUniforms();
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
                GlUtil.checkGlError();
            } catch (Exception e) {
                throw new VideoFrameProcessingException(e);
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
