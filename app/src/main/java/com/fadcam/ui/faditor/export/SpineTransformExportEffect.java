package com.fadcam.ui.faditor.export;

import android.opengl.GLES20;

import androidx.annotation.NonNull;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.util.GlProgram;
import androidx.media3.common.util.GlUtil;
import androidx.media3.common.util.Size;
import androidx.media3.effect.BaseGlShaderProgram;
import androidx.media3.effect.GlEffect;
import androidx.media3.effect.GlShaderProgram;

import com.fadcam.ui.faditor.model.Clip;
import com.fadcam.ui.faditor.model.SpineTransform;

/**
 * THE EXPORT HALF OF THE SPINE CANVAS TRANSFORM — and it is only a half in the sense of "which
 * surface runs it", not "which maths it uses".
 *
 * <p>Every number this shader consumes comes out of {@link SpineTransform#uniforms} and every
 * line of GLSL out of {@link SpineTransform#fragmentShader}, both of which the live preview also
 * calls. There is no matrix built here, no aspect compensation written here and no out-of-frame
 * rule decided here, so there is nothing for this file to get subtly different from
 * {@code FxPreviewTextureView.drawSpineTransform}. Preview/export divergence in this repo has
 * always come from two transcriptions of one idea; this feature has one.</p>
 *
 * <h3>Where it sits in the chain</h3>
 * <p>{@code ExportManager.assembleClipVideoEffects} appends it AFTER the canvas
 * {@code Presentation} and the clip's own FxStack, and BEFORE {@code OpacityExportEffect} and
 * everything that composites on top. So the frame it is handed is the finished, canvas-shaped
 * clip picture — geometry, crop, grade and effects all applied — and what it does is PLACE that
 * picture. Overlays, PiPs, adjustment layers and captions then draw over the canvas at their own
 * coordinates, unmoved, which is what "the clip moves, the layers above it do not" means.</p>
 *
 * <h3>Time</h3>
 * <p>{@code presentationTimeUs} is timeline-absolute for effects in a multi-clip export, so the
 * item's timeline offset is subtracted to recover the clip-local time
 * {@link Clip#spinePoseAt} wants — the identical convention, and the identical helper
 * ({@code ExportManager.clipMsFor}), that {@code OpacityExportShaderProgram} uses. Getting this
 * sign wrong is a bug this project has already paid for once.</p>
 */
public final class SpineTransformExportEffect implements GlEffect {

    @NonNull private final Clip clip;
    private final long clipTimelineOffsetMs;

    public SpineTransformExportEffect(@NonNull Clip clip, long clipTimelineOffsetMs) {
        this.clip = clip;
        this.clipTimelineOffsetMs = clipTimelineOffsetMs;
    }

    @NonNull
    @Override
    public GlShaderProgram toGlShaderProgram(@NonNull android.content.Context context, boolean hdr)
            throws VideoFrameProcessingException {
        return new Program(clip, clipTimelineOffsetMs);
    }

    @Override
    public boolean isNoOp(int width, int height) {
        // The real gate is at BUILD time in ExportManager (clip.hasSpineTransform()), so a clip
        // with no transform never reaches this class at all and its effect list is
        // element-for-element what it was before this existed. Answering "true" here as well
        // would be a second, weaker copy of that decision.
        return false;
    }

    /** The one pass. Full-frame, same size in as out; a bad pose passes the frame through. */
    private static final class Program extends BaseGlShaderProgram {

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

        @NonNull private final Clip clip;
        private final long clipTimelineOffsetMs;
        @NonNull private final GlProgram glProgram;

        private final float[] pose = new float[SpineTransform.POSE];
        private final float[] u = new float[SpineTransform.UNIFORMS];
        private final float[] invA = new float[4];
        private final float[] invB = new float[2];
        private final float[] edge = new float[2];

        private int width = 1, height = 1;
        private int drawCounter = 0;

        Program(@NonNull Clip clip, long clipTimelineOffsetMs)
                throws VideoFrameProcessingException {
            super(false, 2);
            this.clip = clip;
            this.clipTimelineOffsetMs = clipTimelineOffsetMs;
            try {
                this.glProgram = new GlProgram(
                        VERTEX_SHADER, SpineTransform.fragmentShader("vTexSamplingCoord"));
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
            // SAME SIZE IN AS OUT. The picture is moved WITHIN the canvas, never given a bigger
            // one: growing the frame here would change what every later effect in the chain --
            // the caption/text OverlayEffect above all, which sizes its bitmap to the frame it
            // is handed -- believes the canvas to be.
            width = Math.max(1, inputWidth);
            height = Math.max(1, inputHeight);
            return new Size(width, height);
        }

        @Override
        public void drawFrame(int inputTexId, long presentationTimeUs)
                throws VideoFrameProcessingException {
            try {
                long clipMs = ExportManager.clipMsFor(presentationTimeUs, clipTimelineOffsetMs);
                clip.spinePoseAt(clipMs, pose);
                boolean identity = SpineTransform.isIdentity(pose);
                if (identity || !SpineTransform.uniforms(pose, width, height, u)) {
                    // A pose that is identity at THIS instant (an animated transform passing
                    // through its rest pose), or one that cannot be drawn at all, must leave the
                    // frame exactly as it arrived -- not blank it. Feeding the shader the
                    // identity uniforms does precisely that, and keeps a single code path.
                    invA[0] = 1f; invA[1] = 0f; invA[2] = 0f; invA[3] = 1f;
                    invB[0] = 0f; invB[1] = 0f;
                    // Zero feather: with an identity map the picture fills the frame, and a
                    // feather would darken its outermost pixel row for nothing.
                    edge[0] = 0f; edge[1] = 0f;
                } else {
                    invA[0] = u[0]; invA[1] = u[1]; invA[2] = u[2]; invA[3] = u[3];
                    invB[0] = u[4]; invB[1] = u[5];
                    edge[0] = u[6]; edge[1] = u[7];
                }
                glProgram.use();
                glProgram.setSamplerTexIdUniform("uTexSampler", inputTexId, 0);
                glProgram.setFloatsUniform("uSpineInvA", invA);
                glProgram.setFloatsUniform("uSpineInvB", invB);
                glProgram.setFloatsUniform("uSpineEdge", edge);
                glProgram.bindAttributesAndUniforms();
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
                GlUtil.checkGlError();
                if (drawCounter < 4) {
                    com.fadcam.FLog.d("SpineTransformExport",
                            "frame=" + drawCounter + " clipMs=" + clipMs
                                    + " frame=" + width + "x" + height
                                    + " cx=" + pose[SpineTransform.CX]
                                    + " cy=" + pose[SpineTransform.CY]
                                    + " sc=" + pose[SpineTransform.SC]
                                    + " sx=" + pose[SpineTransform.SX]
                                    + " sy=" + pose[SpineTransform.SY]
                                    + " rot=" + pose[SpineTransform.ROT]
                                    + " identity=" + identity);
                }
                drawCounter++;
            } catch (Exception e) {
                throw new VideoFrameProcessingException(e);
            }
        }

        @Override
        public void release() throws VideoFrameProcessingException {
            super.release();
            try {
                glProgram.delete();
            } catch (Exception e) {
                throw new VideoFrameProcessingException(e);
            }
        }
    }
}
