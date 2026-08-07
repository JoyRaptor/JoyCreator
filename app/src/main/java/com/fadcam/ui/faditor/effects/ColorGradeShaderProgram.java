package com.fadcam.ui.faditor.effects;

import android.opengl.GLES20;

import androidx.annotation.NonNull;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.util.GlProgram;
import androidx.media3.common.util.GlUtil;
import androidx.media3.common.util.Size;
import androidx.media3.effect.BaseGlShaderProgram;
import androidx.media3.effect.GlEffect;
import androidx.media3.effect.GlShaderProgram;

public class ColorGradeShaderProgram extends BaseGlShaderProgram implements GlEffect {

    private static final String VERTEX_SHADER =
            "#version 100\n"
            + "attribute vec4 aFramePosition;\n"
            + "uniform mat4 uTransformationMatrix;\n"
            + "uniform mat4 uTexTransformationMatrix;\n"
            + "varying vec2 vTexSamplingCoord;\n"
            + "void main() {\n"
            + "  gl_Position = uTransformationMatrix * aFramePosition;\n"
            + "  vec4 texturePosition = vec4(aFramePosition.x * 0.5 + 0.5,\n"
            + "                              aFramePosition.y * 0.5 + 0.5, 0.0, 1.0);\n"
            + "  vTexSamplingCoord = (uTexTransformationMatrix * texturePosition).xy;\n"
            + "}\n";

    private final EffectStack params;
    private final GlProgram glProgram;
    private int width = 1;
    private int height = 1;

    public ColorGradeShaderProgram(@NonNull android.content.Context context,
                                   @NonNull EffectStack params)
            throws VideoFrameProcessingException {
        super(false, 2);
        this.params = new EffectStack(params);
        try {
            this.glProgram = new GlProgram(VERTEX_SHADER, fragmentShader(params));
            this.glProgram.setBufferAttribute("aFramePosition",
                    GlUtil.getNormalizedCoordinateBounds(), 4);
            float[] identity = GlUtil.create4x4IdentityMatrix();
            this.glProgram.setFloatsUniform("uTransformationMatrix", identity);
            this.glProgram.setFloatsUniform("uTexTransformationMatrix", identity);
        } catch (Exception e) {
            throw new VideoFrameProcessingException(e);
        }
    }

    @NonNull
    @Override
    public Size configure(int inputWidth, int inputHeight) {
        width = Math.max(1, inputWidth);
        height = Math.max(1, inputHeight);
        return new Size(width, height);
    }

    @Override
    public void drawFrame(int inputTexId, long presentationTimeUs)
            throws VideoFrameProcessingException {
        try {
            glProgram.use();
            glProgram.setSamplerTexIdUniform("uTexSampler", inputTexId, 0);
            // uExposure / uTemperature / uTint are GONE, not merely unset — see the shader's
            // own note. media3's Brightness and RgbAdjustment sit EARLIER in the same chain and
            // already applied them, so setting them here applied each one twice.
            glProgram.setFloatUniform("uHighlights", params.getHighlights());
            glProgram.setFloatUniform("uShadows", params.getShadows());
            glProgram.setFloatUniform("uFade", params.getFade());
            glProgram.setFloatUniform("uVignette", params.getVignette());
            glProgram.setFloatUniform("uGrain", params.getGrain());
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
            glProgram.delete();
            super.release();
        } catch (Exception e) {
            throw new VideoFrameProcessingException(e);
        }
    }

    @NonNull
    @Override
    public GlShaderProgram toGlShaderProgram(@NonNull android.content.Context context, boolean hdr)
            throws VideoFrameProcessingException {
        if (hdr) {
            throw new VideoFrameProcessingException("HDR color grading is not supported");
        }
        return this;
    }

    @NonNull
    private static String fragmentShader(@NonNull EffectStack p) {
        return "#version 100\n"
                + "precision mediump float;\n"
                + "uniform sampler2D uTexSampler;\n"
                // NO uExposure / uTemperature / uTint. This program is the LAST link in a chain
                // whose earlier links (media3 Brightness, then RgbAdjustment) have already
                // applied exposure and temperature/tint — see EffectStack.toEffects. Declaring
                // them here and adding them again was a double-apply with DIFFERENT maths each
                // time (additive here, multiplicative there), and it fired on every project
                // where any of highlights / shadows / fade / vignette / grain was non-zero.
                // What arrives at vTexSamplingCoord is already exposed and already balanced;
                // this program's job is only what nothing upstream can do.
                + "uniform float uHighlights;\n"
                + "uniform float uShadows;\n"
                + "uniform float uFade;\n"
                + "uniform float uVignette;\n"
                + "uniform float uGrain;\n"
                + "varying vec2 vTexSamplingCoord;\n"
                + "float rand(vec2 co) {\n"
                + "  return fract(sin(dot(co, vec2(12.9898, 78.233))) * 43758.5453);\n"
                + "}\n"
                + "void main() {\n"
                + "  vec4 c = texture2D(uTexSampler, vTexSamplingCoord);\n"
                + "  vec3 color = c.rgb;\n"
                // The luma the masks key off is now measured on the ALREADY-EXPOSED colour,
                // which is what the masks were always meant to see: brightening a shot should
                // move which pixels count as highlights.
                + "  float luma = dot(color, vec3(0.299, 0.587, 0.114));\n"
                + "  float highlightMask = step(0.5, luma) * clamp((luma - 0.5) * 2.0, 0.0, 1.0);\n"
                + "  float shadowMask = step(luma, 0.5) * clamp((0.5 - luma) * 2.0, 0.0, 1.0);\n"
                + "  color = mix(color, vec3(luma), uHighlights * highlightMask);\n"
                + "  color = mix(color, vec3(luma), -uShadows * shadowMask);\n"
                + "  float fadeUp = max(0.0, uFade);\n"
                + "  float fadeDown = max(0.0, -uFade);\n"
                + "  color = mix(color, vec3(0.0), fadeUp);\n"
                + "  color = mix(color, vec3(1.0), fadeDown);\n"
                + "  float d = distance(vTexSamplingCoord, vec2(0.5));\n"
                + "  float vignette = smoothstep(0.72, 0.28, d) * uVignette;\n"
                + "  color *= 1.0 - vignette;\n"
                + "  float noise = rand(vTexSamplingCoord + fract(uGrain * 1000.0)) - 0.5;\n"
                + "  color += noise * uGrain * 0.08;\n"
                + "  c.rgb = clamp(color, 0.0, 1.0);\n"
                + "  gl_FragColor = c;\n"
                + "}\n";
    }
}
