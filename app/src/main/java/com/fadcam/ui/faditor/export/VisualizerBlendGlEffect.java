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

/**
 * Blend modes for a VISUALIZER: the visualizer is composited against the accumulated frame with
 * the same equations a picture and a PiP use, which is the one thing the final
 * {@link CompositeExportOverlay} pass cannot express (a {@code BitmapOverlay} only alpha-overs).
 *
 * <p>Emitted only for a visualizer whose blend mode is not Normal
 * ({@code WaveformOverlayInstance#wantsExportBlend}); ExportManager hands the Canvas pass exactly
 * the others. A Normal visualizer never builds this, so its chain and its output are untouched.</p>
 *
 * <p><b>Why a bitmap overlay and not a new renderer.</b> The overlay given here is a
 * {@link CompositeExportOverlay} holding just this one visualizer, so the picture (size, rotation,
 * keyframed pose, fades, link alpha, the VisLab or classic renderer) is drawn by the very code the
 * Normal path runs. Only the mixing differs: this program samples the frame and that bitmap and
 * mixes by the bitmap's own alpha, which already carries every fade.</p>
 *
 * <p>The shader is {@code ImageBlendGlEffect}'s flat path without the key and the effect cards (a
 * visualizer has neither): the blend equations are NOT written here but taken from
 * {@link BlendModes#GLSL_BLEND_FN}, so there is one set of equations for every object that blends.</p>
 */
final class VisualizerBlendGlEffect implements GlEffect {

    private final CompositeExportOverlay overlay;
    private final float mode;

    VisualizerBlendGlEffect(@NonNull CompositeExportOverlay overlay, @NonNull String blendMode) {
        this.overlay = overlay;
        // Wire value -> shader code, from the table every blending object shares. An unreadable
        // value is code 0 (Normal): it costs the project its blend, never its export.
        this.mode = BlendModes.modeCode(blendMode);
    }

    @NonNull
    @Override
    public BaseGlShaderProgram toGlShaderProgram(@NonNull Context ignored, boolean useHdr)
            throws VideoFrameProcessingException {
        if (useHdr) {
            throw new VideoFrameProcessingException("HDR blend modes are not supported");
        }
        return new Program(overlay, mode);
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
                + "uniform float uBlendMode;\n"   // float: ES2 int uniforms are patchy on old drivers
                + BlendModes.GLSL_BLEND_FN
                + "void main() {\n"
                + "  vec4 base = texture2D(uVideoTexSampler0, vTexSamplingCoord);\n"
                // Bitmap textures upload Y-DOWN (row 0 = top) while the frame UVs are Y-UP; sample
                // the overlay V-flipped or the visualizer composites upside-down (the same flip
                // ImageBlendGlEffect and the PiP path make, device-proven there).
                + "  vec2 ovc = vec2(vTexSamplingCoord.x, 1.0 - vTexSamplingCoord.y);\n"
                + "  vec4 src = texture2D(uOverlayTexSampler0, ovc);\n"
                + "  vec3 sc = src.rgb / max(src.a, 0.001);\n"   // unpremultiply
                // Mix by the visualizer's own alpha: it already carries the visualizer's fades,
                // the host clip's opacity envelope and a parent link's opacity.
                + "  vec3 outc = mix(base.rgb, clamp(blendPix(base.rgb, sc), 0.0, 1.0), src.a);\n"
                + "  gl_FragColor = vec4(outc, base.a);\n"
                + "}\n";

        private final GlProgram glProgram;
        private final CompositeExportOverlay overlay;
        private final float mode;

        Program(@NonNull CompositeExportOverlay overlay, float mode)
                throws VideoFrameProcessingException {
            super(/* useHighPrecisionColorComponents= */ false, /* texturePoolCapacity= */ 1);
            this.overlay = overlay;
            this.mode = mode;
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
            GlErrors.drain("pending when VisualizerBlendGlEffect began (left by an earlier step)");
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
