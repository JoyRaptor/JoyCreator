package com.fadcam.ui.faditor.export;

import android.opengl.GLES20;

import androidx.annotation.NonNull;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.util.GlProgram;
import androidx.media3.common.util.GlUtil;
import androidx.media3.common.util.Size;
import androidx.media3.effect.BaseGlShaderProgram;

import com.fadcam.ui.faditor.model.Clip;

public class OpacityExportShaderProgram extends BaseGlShaderProgram {

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

    // Both RGB and alpha are scaled by uOpacity. This is correct for H.264 export
    // (encoder drops alpha — RGB=0 yields black) AND for premultiplied-alpha
    // downstream consumers (OverlayEffect + Presentation chain), so the
    // composited frame fades cleanly to black, including any text/waveform/
    // caption overlays drawn earlier in the pipeline.
    private static final String FRAGMENT_SHADER =
            "#version 100\n"
            + "precision mediump float;\n"
            + "uniform sampler2D uTexSampler;\n"
            + "uniform float uOpacity;\n"
            + "varying vec2 vTexSamplingCoord;\n"
            + "void main() {\n"
            + "  vec4 c = texture2D(uTexSampler, vTexSamplingCoord);\n"
            + "  gl_FragColor = vec4(c.rgb * uOpacity, c.a * uOpacity);\n"
            + "}\n";

    @NonNull
    private final Clip clip;

    /**
     * Absolute timeline position (ms) of the start of the EditedMediaItem this
     * program is processing. The {@code drawFrame} method subtracts this from
     * the (timeline-absolute) per-frame presentation time to recover the
     * clip-local time expected by {@link Clip#opacityAtClipMs(long)}.
     */
    private final long clipTimelineOffsetMs;

    @NonNull
    private final GlProgram glProgram;

    public OpacityExportShaderProgram(@NonNull Clip clip, long clipTimelineOffsetMs)
            throws VideoFrameProcessingException {
        super(false, 2);
        this.clip = clip;
        this.clipTimelineOffsetMs = clipTimelineOffsetMs;
        try {
            this.glProgram = new GlProgram(VERTEX_SHADER, FRAGMENT_SHADER);
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
        return new Size(Math.max(1, inputWidth), Math.max(1, inputHeight));
    }

    @Override
    public void drawFrame(int inputTexId, long presentationTimeUs)
            throws VideoFrameProcessingException {
        try {
            // Keyframes are stored as CLIP-LOCAL time (per Clip.opacityAtClipMs docstring).
            // presentationTimeUs is timeline-absolute (Media3 does NOT reset to 0 per item
            // for effects in a multi-clip export), so we SUBTRACT the item's timeline
            // offset to recover clip-local time. Adding it (the previous wrong sign) made
            // the lookup point past the last keyframe → constant opacity = no visible fade.
            long clipMs = ExportManager.clipMsFor(presentationTimeUs, clipTimelineOffsetMs);
            // FADE_KNOBS §2.5: knob fade multiplies the keyframe envelope — the spine's
            // fade-to-black rides the SAME alpha the Opacity button drives (RGB+alpha both
            // scaled → encoder drops alpha → fades cleanly to black).
            float opacity = clip.opacityAtClipMs(clipMs) * clip.masterFadeFactorAt(clipMs);
            glProgram.use();
            glProgram.setSamplerTexIdUniform("uTexSampler", inputTexId, 0);
            glProgram.setFloatUniform("uOpacity", opacity);
            glProgram.bindAttributesAndUniforms();
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            GlUtil.checkGlError();
            if (opacityDrawCounter < 8
                    || (opacityDrawCounter < 80 && opacityDrawCounter % 10 == 0)) {
                com.fadcam.FLog.d("OpacityExportShaderProgram",
                        "frame=" + opacityDrawCounter
                                + " pts=" + presentationTimeUs
                                + " offsetMs=" + clipTimelineOffsetMs
                                + " clipMs=" + clipMs
                                + " opacity=" + opacity
                                + " keyframes=" + clip.getOpacityKeyframes().size());
            }
            opacityDrawCounter++;
        } catch (Exception e) {
            throw new VideoFrameProcessingException(e);
        }
    }

    private int opacityDrawCounter = 0;

    @Override
    public void release() throws VideoFrameProcessingException {
        try {
            glProgram.delete();
            super.release();
        } catch (Exception e) {
            throw new VideoFrameProcessingException(e);
        }
    }
}
