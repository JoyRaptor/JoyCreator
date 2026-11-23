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
import com.fadcam.ui.faditor.motion.ItemMotionSampler;
import com.fadcam.ui.faditor.motion.MotionBreaks;
import com.fadcam.ui.faditor.motion.MotionFrameCadence;
import com.fadcam.ui.faditor.motion.MotionItemAdapters;

/**
 * Places the isolated spine clip on the export canvas.
 *
 * <p>The sharp pass shares its geometry and shader with live preview. When enabled, the
 * SDR export shutter samples that same transform at subframe times.</p>
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

    private final long pieceStartUs;
    private final long pieceEndUs;

    public SpineTransformExportEffect(@NonNull Clip clip, long clipTimelineOffsetMs,
                                      long pieceStartMs, long pieceDurationMs) {
        this.clip = clip;
        this.clipTimelineOffsetMs = clipTimelineOffsetMs;
        pieceStartUs = pieceStartMs * 1000L;
        pieceEndUs = (pieceStartMs + pieceDurationMs) * 1000L;
    }

    @NonNull
    @Override
    public GlShaderProgram toGlShaderProgram(@NonNull android.content.Context context, boolean hdr)
            throws VideoFrameProcessingException {
        MotionBreaks breaks = null;
        if (!hdr && clip.isRigidMotionBlurEnabled() && clip.isSpineTransformArmed()
                && pieceEndUs > pieceStartUs) {
            var builder = new MotionBreaks.Builder().add(pieceStartUs).add(pieceEndUs);
            var transform = clip.getSpineTransform();
            for (String property : SpineTransform.tracks()) {
                var track = transform.get(property);
                if (track != null) builder.addSteps(track, clipTimelineOffsetMs * 1000L);
            }
            breaks = builder.build();
        }
        return new Program(clip, clipTimelineOffsetMs, pieceStartUs, pieceEndUs, breaks);
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

        @NonNull private final Clip clip;
        private final long clipTimelineOffsetMs;
        @NonNull private final GlProgram glProgram;
        private final long pieceStartUs;
        private final long pieceEndUs;
        private final MotionBreaks breaks;
        private ItemMotionSampler sampler;
        private RigidTextureShutterPass shutterPass;
        private final MotionFrameCadence cadence = new MotionFrameCadence();

        private final float[] pose = new float[SpineTransform.POSE];
        private final float[] u = new float[SpineTransform.UNIFORMS];
        private final float[] invA = new float[4];
        private final float[] invB = new float[2];
        private final float[] edge = new float[2];

        private int width = 1, height = 1;
        private int drawCounter = 0;

        Program(@NonNull Clip clip, long clipTimelineOffsetMs, long pieceStartUs,
                long pieceEndUs, MotionBreaks breaks) throws VideoFrameProcessingException {
            super(false, 2);
            this.clip = clip;
            this.clipTimelineOffsetMs = clipTimelineOffsetMs;
            this.pieceStartUs = pieceStartUs;
            this.pieceEndUs = pieceEndUs;
            this.breaks = breaks;
            try {
                this.glProgram = new GlProgram(
                        RigidTextureShutterPass.VERTEX_SHADER, SpineTransform.fragmentShader("vTexSamplingCoord"));
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
            if (breaks != null) {
                cadence.reset();
                sampler = MotionItemAdapters.spine(clip, clipTimelineOffsetMs * 1000L, width, height);
            }
            return new Size(width, height);
        }

        @Override
        public void flush() {
            cadence.reset();
            super.flush();
        }

        @Override
        public void drawFrame(int inputTexId, long presentationTimeUs)
                throws VideoFrameProcessingException {
            try {
                if (sampler != null && presentationTimeUs >= pieceStartUs
                        && presentationTimeUs < pieceEndUs) {
                    var plan = RigidTextureShutterPass.naturalPlan(sampler, presentationTimeUs,
                            cadence.observe(presentationTimeUs), breaks, width, height);
                    if (plan != null) {
                        if (shutterPass == null) shutterPass = new RigidTextureShutterPass(RigidTextureShutterPass.Source.SPINE);
                        shutterPass.draw(inputTexId, width, height, plan, 0, 1f);
                        return;
                    }
                }
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
                try {
                    if (shutterPass != null) shutterPass.release();
                } finally {
                    glProgram.delete();
                }
            } catch (Exception e) {
                throw new VideoFrameProcessingException(e);
            }
        }
    }
}
