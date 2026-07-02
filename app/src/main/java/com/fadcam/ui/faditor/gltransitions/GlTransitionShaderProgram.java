package com.fadcam.ui.faditor.gltransitions;

import android.opengl.GLES20;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.util.GlProgram;
import androidx.media3.common.util.GlUtil;
import androidx.media3.common.util.Size;
import androidx.media3.effect.BaseGlShaderProgram;
import androidx.media3.effect.TextureOverlay;

import com.fadcam.FLog;
import com.fadcam.ui.faditor.model.Transition;

import java.util.Map;

public class GlTransitionShaderProgram extends BaseGlShaderProgram {

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

    private static final String TAG = "GlTransitionShaderProgram";

    private final GlProgram glProgram;
    private final Transition transition;
    private final long durationMs;
    /** Absolute composition time (ms) at which this transition item starts. */
    private final long timelineStartMs;
    private final TextureOverlay overlay;
    private int width = 1;
    private int height = 1;

    public GlTransitionShaderProgram(@NonNull android.content.Context context,
                                      @NonNull String fragmentShader,
                                      @NonNull Transition transition,
                                      long durationMs,
                                      long timelineStartMs,
                                      @NonNull TextureOverlay overlay)
            throws VideoFrameProcessingException {
        super(false, 2);
        this.transition = transition;
        this.durationMs = Math.max(1L, durationMs);
        this.timelineStartMs = timelineStartMs;
        this.overlay = overlay;

        try {
            this.glProgram = new GlProgram(VERTEX_SHADER, fragmentShader);
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
        overlay.configure(new Size(width, height));
        return new Size(width, height);
    }

    @Override
    public void drawFrame(int inputTexId, long presentationTimeUs)
            throws VideoFrameProcessingException {
        try {
            // presentationTimeUs is timeline-absolute; subtract this item's
            // composition start to recover the local transition progress 0..1.
            float localMs = (presentationTimeUs / 1000f) - timelineStartMs;
            float progress = clamp(localMs / durationMs, 0f, 1f);
            glProgram.use();
            glProgram.setSamplerTexIdUniform("uVideoTexSampler0", inputTexId, 0);
            int overlayTexId = overlay.getTextureId(presentationTimeUs);
            glProgram.setSamplerTexIdUniform("uOverlayTexSampler0", overlayTexId, 1);
            setFloatUniform("uOverlayAlphaScale0",
                    overlay.getOverlaySettings(presentationTimeUs).getAlphaScale());
            setFloatUniform("progress", progress);
            if ("GridFlip".equals(transition.glTransitionId)) {
                setFloatsUniform("bgcolor", new float[]{0f, 0f, 0f, 1f});
            }
            setParamUniforms();
            glProgram.bindAttributesAndUniforms();
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            GlUtil.checkGlError();
            if (drawCounter < 8 || (drawCounter < 80 && drawCounter % 10 == 0)) {
                com.fadcam.FLog.d(TAG, "drawFrame counter=" + drawCounter
                        + " pts=" + presentationTimeUs
                        + " durationMs=" + durationMs
                        + " progress=" + progress
                        + " transitionId=" + transition.glTransitionId
                        + " type=" + transition.type);
            }
            drawCounter++;
        } catch (Exception e) {
            throw new VideoFrameProcessingException(e);
        }
    }

    private int drawCounter = 0;

    private void setFloatUniform(@NonNull String name, float value) {
        try {
            glProgram.setFloatUniform(name, value);
        } catch (NullPointerException e) {
            FLog.w(TAG, "Uniform '" + name + "' not found in transition shader");
        }
    }

    private void setFloatsUniform(@NonNull String name, @NonNull float[] values) {
        try {
            glProgram.setFloatsUniform(name, values);
        } catch (NullPointerException e) {
            FLog.w(TAG, "Uniform '" + name + "' not found in transition shader");
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

    private void setParamUniforms() throws VideoFrameProcessingException {
        GLTransitionCatalog.Entry entry = GLTransitionCatalog.find(transition.glTransitionId);
        if (entry == null) {
            // External (user-supplied) shader: not in the catalog, so apply the default param values
            // parsed from its own `// = N` comments (overridable per-transition). Without this its
            // params would default to 0 and the effect would barely render.
            if (transition.glTransitionId != null) {
                Map<String, Float> ext =
                        GlTransitionShaderLoader.getExternalParams(transition.glTransitionId);
                Map<String, Float> overrides = transition.paramOverrides;
                for (Map.Entry<String, Float> e : ext.entrySet()) {
                    float value = e.getValue();
                    if (overrides != null && overrides.containsKey(e.getKey())) {
                        value = overrides.get(e.getKey());
                    }
                    setFloatUniform(e.getKey(), value);
                }
            }
            return;
        }
        for (GLTransitionCatalog.Param param : entry.params) {
            float value = param.defaultValue;
            Map<String, Float> overrides = transition.paramOverrides;
            if (overrides != null && overrides.containsKey(param.name)) {
                value = overrides.get(param.name);
            }
            setFloatUniform(param.name, clamp(value, param.minValue, param.maxValue));
        }
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
