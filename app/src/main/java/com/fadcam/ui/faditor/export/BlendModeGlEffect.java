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

import com.fadcam.ui.faditor.model.Clip;

/**
 * M-EXPORT-2 blend modes (PLAN_LAYERS_V2 §5.2) — the one PiP capability a
 * {@code BitmapOverlay} cannot express: blending the overlay against the
 * ACCUMULATED frame. One instance per blend-mode PiP clip, inserted into the
 * master item's effect list BEFORE the text/caption {@code OverlayEffect}, so
 * blend PiPs keep the preview z-rule (video < PiP < sprites/text/captions).
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
 * <p>Preview shows NORMAL compositing for blend clips until a preview
 * approximation lands — documented per Part 10 probe #4: export is ground truth.</p>
 */
public final class BlendModeGlEffect implements GlEffect {

    /** Wire values match {@code Clip#getOverlayBlendMode()} strings. */
    static int modeCode(@NonNull String blendMode) {
        switch (blendMode) {
            case "MULTIPLY": return 1;
            case "SCREEN":   return 2;
            case "OVERLAY":  return 3;
            case "ADD":      return 4;
            default:          return 0; // NORMAL — callers shouldn't create us for it
        }
    }

    private final Context context;
    private final Clip clip;

    public BlendModeGlEffect(@NonNull Context context, @NonNull Clip clip) {
        this.context = context.getApplicationContext();
        this.clip = clip;
    }

    @NonNull
    @Override
    public BaseGlShaderProgram toGlShaderProgram(@NonNull Context ignored, boolean useHdr)
            throws VideoFrameProcessingException {
        if (useHdr) {
            throw new VideoFrameProcessingException("HDR blend modes are not supported");
        }
        return new Program(context, clip);
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
                + "vec3 blendPix(vec3 b, vec3 s) {\n"
                + "  if (uBlendMode < 1.5) return b * s;\n"
                + "  if (uBlendMode < 2.5) return 1.0 - (1.0 - b) * (1.0 - s);\n"
                + "  if (uBlendMode < 3.5) {\n"
                + "    vec3 lo = 2.0 * b * s;\n"
                + "    vec3 hi = 1.0 - 2.0 * (1.0 - b) * (1.0 - s);\n"
                + "    return vec3(b.r < 0.5 ? lo.r : hi.r,\n"
                + "                b.g < 0.5 ? lo.g : hi.g,\n"
                + "                b.b < 0.5 ? lo.b : hi.b);\n"
                + "  }\n"
                + "  return min(b + s, vec3(1.0));\n"
                + "}\n"
                + "void main() {\n"
                + "  vec4 base = texture2D(uVideoTexSampler0, vTexSamplingCoord);\n"
                + "  vec4 src = texture2D(uOverlayTexSampler0, vTexSamplingCoord);\n"
                + "  vec3 sc = src.rgb / max(src.a, 0.001);\n" // unpremultiply
                + "  vec3 outc = mix(base.rgb, clamp(blendPix(base.rgb, sc), 0.0, 1.0), src.a);\n"
                + "  gl_FragColor = vec4(outc, base.a);\n"
                + "}\n";

        private final GlProgram glProgram;
        private final PipFrameOverlay overlay;
        private final float mode;

        Program(@NonNull Context context, @NonNull Clip clip)
                throws VideoFrameProcessingException {
            super(/* useHighPrecisionColorComponents= */ false, /* texturePoolCapacity= */ 1);
            this.overlay = new PipFrameOverlay(context, clip);
            this.mode = modeCode(clip.getOverlayBlendMode());
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
