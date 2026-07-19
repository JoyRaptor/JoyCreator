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
    @Nullable private final int[] canvasDims;
    private int width = 1;
    private int height = 1;
    /**
     * Fit-centered rect of the INPUT (outgoing leg) on the output frame, normalized
     * {offX, offY, w, h}. Identity when the segment already runs at output dims.
     */
    private float[] fromFit = {0f, 0f, 1f, 1f};

    public GlTransitionShaderProgram(@NonNull android.content.Context context,
                                      @NonNull String fragmentShader,
                                      @NonNull Transition transition,
                                      long durationMs,
                                      long timelineStartMs,
                                      @NonNull TextureOverlay overlay)
            throws VideoFrameProcessingException {
        this(context, fragmentShader, transition, durationMs, timelineStartMs, overlay, null);
    }

    public GlTransitionShaderProgram(@NonNull android.content.Context context,
                                      @NonNull String fragmentShader,
                                      @NonNull Transition transition,
                                      long durationMs,
                                      long timelineStartMs,
                                      @NonNull TextureOverlay overlay,
                                      @Nullable int[] canvasDims)
            throws VideoFrameProcessingException {
        super(false, 2);
        this.transition = transition;
        this.durationMs = Math.max(1L, durationMs);
        this.timelineStartMs = timelineStartMs;
        this.overlay = overlay;
        this.canvasDims = canvasDims == null ? null : new int[]{canvasDims[0], canvasDims[1]};

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
        // CANVAS-ASPECT PARITY (2026-07-18, export sibling of preview F13 item 4): with a FIXED
        // canvas preset, the transition segment used to run at the OUTGOING clip's frame dims and
        // get fit onto the canvas downstream — so the incoming leg was fit into the outgoing
        // rect FIRST and could land at a very different size than its own post-cut compose
        // (measured: incoming B at 547px mid-blend vs 1215px after the cut, 16:9 canvas +
        // narrow-cropped outgoing A). Run the blend ON THE CANVAS instead: output at canvasDims,
        // fit-center the input in-shader (uFromFit, sampled black outside), and let the overlay
        // compose the incoming leg directly at canvas size. The downstream canvas fit then
        // no-ops (aspects equal) and both legs' mid-blend geometry == their own segments'.
        int outW = width, outH = height;
        fromFit = new float[]{0f, 0f, 1f, 1f};
        if (canvasDims != null && canvasDims[0] > 0 && canvasDims[1] > 0
                && (canvasDims[0] != width || canvasDims[1] != height)) {
            outW = canvasDims[0];
            outH = canvasDims[1];
            float scale = Math.min(outW / (float) width, outH / (float) height);
            float fw = width * scale / outW;
            float fh = height * scale / outH;
            fromFit = new float[]{(1f - fw) / 2f, (1f - fh) / 2f,
                    Math.max(0.0001f, fw), Math.max(0.0001f, fh)};
        }
        overlay.configure(new Size(outW, outH));
        return new Size(outW, outH);
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
            setFloatsUniform("uFromFit", fromFit);
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
