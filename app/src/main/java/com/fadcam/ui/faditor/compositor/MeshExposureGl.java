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
    private static final String COMPOSITE_FRAGMENT_SHADER = """
            #version 100
            precision mediump float;
            varying vec2 vTexSamplingCoord;
            uniform sampler2D uBase;
            uniform sampler2D uStamp;
            void main() {
              vec4 base = texture2D(uBase, vTexSamplingCoord);
              vec4 stamp = texture2D(uStamp, vTexSamplingCoord);
              gl_FragColor = vec4(base.rgb * (1.0 - stamp.a) + stamp.rgb, base.a);
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

    private final MeshStampGl stamp;
    private final boolean ownsStamp;
    private final ExactLaneExposure accumulator = new ExactLaneExposure();
    private final int[] blendFactors = new int[4];
    private GlProgram copy;
    private GlProgram composite;

    public MeshExposureGl() { this(new MeshStampGl(), true); }

    /** Borrow the caller's existing sharp mesh stamp on the same GL thread. */
    public MeshExposureGl(MeshStampGl stamp) { this(stamp, false); }

    private MeshExposureGl(MeshStampGl stamp, boolean ownsStamp) {
        this.stamp = stamp;
        this.ownsStamp = ownsStamp;
    }

    /** Draws the premultiplied exposure over the existing picture at the sprite's lane position. */
    public boolean compositeSprite(int outputFramebuffer, int baseTexture, int width, int height,
                                   int sourceCell, float sourceAspect, Bitmap atlas,
                                   AuthoredSpriteMeshExposure.Taps taps)
            throws GlUtil.GlException, VideoFrameProcessingException {
        int stampTexture = drawSprite(width, height, sourceCell, sourceAspect, atlas, taps);
        if (stampTexture == 0) return false;
        boolean blend = GLES20.glIsEnabled(GLES20.GL_BLEND);
        boolean scissor = GLES20.glIsEnabled(GLES20.GL_SCISSOR_TEST);
        boolean depth = GLES20.glIsEnabled(GLES20.GL_DEPTH_TEST);
        boolean cull = GLES20.glIsEnabled(GLES20.GL_CULL_FACE);
        try {
            GLES20.glDisable(GLES20.GL_SCISSOR_TEST);
            focus(outputFramebuffer, width, height);
            if (composite == null) composite = createProgram(COMPOSITE_FRAGMENT_SHADER);
            composite.use();
            composite.setSamplerTexIdUniform("uBase", baseTexture, 0);
            composite.setSamplerTexIdUniform("uStamp", stampTexture, 1);
            drawQuad(composite);
            return true;
        } finally {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            setEnabled(GLES20.GL_BLEND, blend);
            setEnabled(GLES20.GL_SCISSOR_TEST, scissor);
            setEnabled(GLES20.GL_DEPTH_TEST, depth);
            setEnabled(GLES20.GL_CULL_FACE, cull);
        }
    }

    private int drawSprite(int width, int height, int sourceCell, float sourceAspect,
                           Bitmap atlas, AuthoredSpriteMeshExposure.Taps taps)
            throws GlUtil.GlException, VideoFrameProcessingException {
        if (taps == null || sourceCell != taps.sourceCell() || atlas == null || atlas.isRecycled()) {
            return 0;
        }
        if (!(sourceAspect > 0f) || !Float.isFinite(sourceAspect)) return 0;
        float widthPerHeight = sourceAspect * height / width;
        return render(0, true, width, height, atlas, taps.mesh(), taps.exposure(), taps.schedule(),
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
        return render(outputFramebuffer, false, width, height, atlas, spec, exposure, schedule, placement) != 0;
    }

    private int render(int outputFramebuffer, boolean resolveToTexture, int width, int height,
                       Bitmap atlas, MeshWarpSpec spec, MeshExposure exposure,
                       MotionShutter.Schedule schedule, PlacementSampler placement)
            throws GlUtil.GlException, VideoFrameProcessingException {
        if (schedule == null) throw new IllegalArgumentException("Missing mesh exposure schedule");
        if (!MeshExposureGlPolicy.admits(width, height, schedule.timesUs(), schedule.weights())) {
            return 0;
        }
        if (atlas == null || atlas.isRecycled() || spec == null || exposure == null
                || placement == null) {
            throw new IllegalArgumentException("Missing mesh exposure input");
        }

        boolean scissor = GLES20.glIsEnabled(GLES20.GL_SCISSOR_TEST);
        boolean depth = GLES20.glIsEnabled(GLES20.GL_DEPTH_TEST);
        boolean cull = GLES20.glIsEnabled(GLES20.GL_CULL_FACE);
        GLES20.glGetIntegerv(GLES20.GL_BLEND_SRC_RGB, blendFactors, 0);
        GLES20.glGetIntegerv(GLES20.GL_BLEND_DST_RGB, blendFactors, 1);
        GLES20.glGetIntegerv(GLES20.GL_BLEND_SRC_ALPHA, blendFactors, 2);
        GLES20.glGetIntegerv(GLES20.GL_BLEND_DST_ALPHA, blendFactors, 3);
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST);
        try {
            stamp.configure(width, height);
            accumulator.configure(width, height);
            ExactLaneExposure.Scope scope = (target, projectUs) ->
                    drawTap(target, width, height, atlas, spec, exposure.localTimeUs(projectUs),
                            placement.sample(projectUs));
            if (resolveToTexture) return accumulator.drawToTexture(schedule, scope);
            accumulator.draw(outputFramebuffer, schedule, scope);
            return 1;
        } finally {
            GLES20.glBlendFuncSeparate(blendFactors[0], blendFactors[1], blendFactors[2], blendFactors[3]);
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
        if (copy == null) copy = createProgram(COPY_FRAGMENT_SHADER);
        copy.use();
        copy.setSamplerTexIdUniform("uStamp", texture, 0);
        drawQuad(copy);
    }

    private static void drawQuad(GlProgram program) throws GlUtil.GlException {
        program.bindAttributesAndUniforms();
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GlUtil.checkGlError();
    }

    private static void focus(int framebuffer, int width, int height) throws GlUtil.GlException {
        GLES20.glDisable(GLES20.GL_BLEND);
        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        GLES20.glDisable(GLES20.GL_CULL_FACE);
        GlUtil.focusFramebufferUsingCurrentContext(framebuffer, width, height);
    }

    private static GlProgram createProgram(String fragment) throws GlUtil.GlException {
        GlProgram program = new GlProgram(RigidTextureShutterPass.VERTEX_SHADER, fragment);
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

    /** Releases owned GL resources. The caller releases its borrowed stamp separately. */
    public void release() throws GlUtil.GlException {
        GlProgram oldCopy = copy;
        GlProgram oldComposite = composite;
        copy = null;
        composite = null;
        try {
            if (ownsStamp) stamp.release();
        } finally {
            try {
                accumulator.release();
            } finally {
                try {
                    if (oldCopy != null) oldCopy.delete();
                } finally {
                    if (oldComposite != null) oldComposite.delete();
                }
            }
        }
    }
}
