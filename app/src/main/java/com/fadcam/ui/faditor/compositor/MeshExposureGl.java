package com.fadcam.ui.faditor.compositor;

import android.graphics.Bitmap;
import android.opengl.GLES20;

import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.util.GlProgram;
import androidx.media3.common.util.GlUtil;

import com.fadcam.ui.faditor.compositor.lane.ExactLaneExposure;
import com.fadcam.ui.faditor.motion.MotionShutter;
import com.fadcam.ui.faditor.transform.mesh.MeshWarpSpec;

/** Exact isolated SDR mesh exposure. Call only on the GL thread that owns the output. */
public final class MeshExposureGl {
    private static final String COPY_FRAGMENT_SHADER = """
            #version 100
            precision mediump float;
            varying vec2 vTexSamplingCoord;
            uniform sampler2D uStamp;
            void main() {
              gl_FragColor = texture2D(uStamp, vTexSamplingCoord);
            }
            """;

    /** Placement in the stamp's normalized, top-left coordinate system. */
    public record Placement(float cx, float cy, float width, float height,
                            float pivotX, float pivotY, boolean applyPivot, float rotation,
                            float opacity, float[] cornerPin8, float mirrorX, float mirrorY) {}

    @FunctionalInterface
    public interface PlacementSampler {
        /** Resolve only the authored placement on the project clock. */
        Placement sample(long projectTimeUs);
    }

    private final MeshStampGl stamp = new MeshStampGl();
    private final ExactLaneExposure accumulator = new ExactLaneExposure();
    private GlProgram copy;

    /** Draws one planned authored sprite using its cached, unplaced source cell. */
    public boolean drawSprite(int outputFramebuffer, int width, int height,
                              int sourceCell, Bitmap atlas, AuthoredSpriteMeshExposure.Taps taps)
            throws GlUtil.GlException, VideoFrameProcessingException {
        if (taps == null || sourceCell != taps.sourceCell() || atlas == null || atlas.isRecycled()) {
            return false;
        }
        float widthPerHeight = (atlas.getWidth() / (float) atlas.getHeight()) / (width / (float) height);
        return draw(outputFramebuffer, width, height, atlas, taps.mesh(), taps.exposure(), taps.schedule(),
                projectUs -> {
                    var pose = taps.placementAt(projectUs);
                    return new Placement(pose.cx(), pose.cy(), pose.height() * widthPerHeight, pose.height(),
                            0f, 0f, false, pose.rotation(), pose.alpha(), null, pose.mirrorX(), pose.mirrorY());
                });
    }

    /**
     * Replaces the whole target with a premultiplied exposure of one mesh over transparency.
     * The caller composites it at the mesh's ordinary lane position. The same atlas and mesh
     * topology must apply for every tap, so use a schedule prepared by {@link MeshExposure}.
     *
     * <p>False makes no GL calls and leaves the target for the ordinary sharp draw. It means
     * one or fewer effective taps, an inactive item, or a frame above the working budget. The
     * budget covers the renderer's four targets independently of the caller's output and the
     * cached source atlas. The caller decides how to report an over-budget Exact export.
     *
     * <p>A failed sample stops the whole exposure. The caller can propagate the error or redraw
     * the entire target. No prefix of the shutter samples is resolved as a successful result.
     */
    public boolean draw(int outputFramebuffer, int width, int height,
                        Bitmap atlas, MeshWarpSpec spec, MeshExposure exposure,
                        MotionShutter.Schedule schedule, PlacementSampler placement)
            throws GlUtil.GlException, VideoFrameProcessingException {
        if (schedule == null) throw new IllegalArgumentException("Missing mesh exposure schedule");
        if (!MeshExposureGlPolicy.admits(width, height, schedule.timesUs(), schedule.weights())) {
            return false;
        }
        if (atlas == null || atlas.isRecycled() || spec == null || exposure == null
                || placement == null) {
            throw new IllegalArgumentException("Missing mesh exposure input");
        }

        boolean scissor = GLES20.glIsEnabled(GLES20.GL_SCISSOR_TEST);
        boolean depth = GLES20.glIsEnabled(GLES20.GL_DEPTH_TEST);
        boolean cull = GLES20.glIsEnabled(GLES20.GL_CULL_FACE);
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST);
        try {
            stamp.configure(width, height);
            accumulator.configure(width, height);
            accumulator.draw(outputFramebuffer, schedule, (target, projectUs) ->
                    drawTap(target, width, height, atlas, spec, exposure.localTimeUs(projectUs),
                            placement.sample(projectUs)));
            return true;
        } finally {
            setEnabled(GLES20.GL_SCISSOR_TEST, scissor);
            setEnabled(GLES20.GL_DEPTH_TEST, depth);
            setEnabled(GLES20.GL_CULL_FACE, cull);
        }
    }

    private void drawTap(int targetFramebuffer, int width, int height,
                         Bitmap atlas, MeshWarpSpec spec, long localUs, Placement p)
            throws GlUtil.GlException, VideoFrameProcessingException {
        if (p == null || !Float.isFinite(p.opacity())) {
            throw new VideoFrameProcessingException("Mesh exposure has no valid placement");
        }
        if (p.opacity() <= 0.001f) { // Same invisible cutoff as MeshStampGl.
            focus(targetFramebuffer, width, height);
            GLES20.glClearColor(0f, 0f, 0f, 0f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            GlUtil.checkGlError();
            return;
        }
        int texture = stamp.renderExposureTap(atlas, spec, localUs, p.cx(), p.cy(), p.width(),
                p.height(), p.pivotX(), p.pivotY(), p.applyPivot(), p.rotation(), p.opacity(),
                p.cornerPin8(), p.mirrorX(), p.mirrorY());
        if (texture == 0) throw new VideoFrameProcessingException("Mesh exposure tap could not be drawn");

        focus(targetFramebuffer, width, height);
        if (copy == null) copy = createCopyProgram();
        copy.use();
        copy.setSamplerTexIdUniform("uStamp", texture, 0);
        copy.bindAttributesAndUniforms();
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GlUtil.checkGlError();
    }

    private static void focus(int framebuffer, int width, int height) throws GlUtil.GlException {
        GLES20.glDisable(GLES20.GL_BLEND);
        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        GLES20.glDisable(GLES20.GL_CULL_FACE);
        GlUtil.focusFramebufferUsingCurrentContext(framebuffer, width, height);
    }

    private static GlProgram createCopyProgram() throws GlUtil.GlException {
        GlProgram program = new GlProgram(RigidTextureShutterPass.VERTEX_SHADER, COPY_FRAGMENT_SHADER);
        try {
            program.setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), 4);
            float[] identity = GlUtil.create4x4IdentityMatrix();
            program.setFloatsUniform("uTransformationMatrix", identity);
            program.setFloatsUniform("uTexTransformationMatrix", identity);
            return program;
        } catch (RuntimeException | Error failure) {
            try {
                program.delete();
            } catch (GlUtil.GlException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    private static void setEnabled(int cap, boolean enabled) {
        if (enabled) GLES20.glEnable(cap);
        else GLES20.glDisable(cap);
    }

    /** Releases the cached atlas, stamp, accumulator, and copy program on the owning GL thread. */
    public void release() throws GlUtil.GlException {
        GlProgram oldCopy = copy;
        copy = null;
        try {
            stamp.release();
        } finally {
            try {
                accumulator.release();
            } finally {
                if (oldCopy != null) oldCopy.delete();
            }
        }
    }
}
