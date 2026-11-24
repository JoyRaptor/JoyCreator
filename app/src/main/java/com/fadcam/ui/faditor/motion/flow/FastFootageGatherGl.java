package com.fadcam.ui.faditor.motion.flow;

import android.content.res.AssetManager;
import android.opengl.GLES20;

import androidx.media3.common.util.GlProgram;
import androidx.media3.common.util.GlUtil;

import com.fadcam.ui.faditor.motion.MotionShutter;

import java.io.IOException;
import java.util.Arrays;

/**
 * One-pass GLSL ES 1.00 Fast footage exposure. Owned and released by one GL thread.
 *
 * <p>Input and output use the same canvas dimensions and premultiplied SDR sRGB coding. The
 * input, packed field and guide must be distinct from the destination attachment. The caller
 * sets a fullscreen draw state: no array buffer, blend, depth, stencil, culling or scissor, with
 * a full colour mask. The pass restores the framebuffer, viewport, active unit and dithering.
 */
public final class FastFootageGatherGl {
    public static final int MAX_TAPS = 8;

    private final AssetManager assets;
    private final float[] region = new float[4];
    private final float[] stageSize = new float[2];
    private final float[] sourcePerFlowPixel = new float[2];
    private final float[] flowSize = new float[2];
    private final float[] travelScale = new float[1];
    private final float[] offsetsA = new float[4], offsetsB = new float[4];
    private final float[] weightsA = new float[4], weightsB = new float[4];
    private final int[] savedFramebuffer = new int[1];
    private final int[] savedViewport = new int[4];
    private final int[] savedActiveUnit = new int[1];
    private GlProgram program;
    private boolean released;

    public FastFootageGatherGl(AssetManager assets) {
        this.assets = assets;
    }

    /** Prewarm the shader on the owning GL thread once the preview gates are open. */
    public void prepare() throws IOException, GlUtil.GlException {
        requireOpen();
        if (program != null) return;
        GlProgram candidate = new GlProgram(GpuPyramidFlow.read(assets, "fullscreen.vert"),
                GpuPyramidFlow.read(assets, "fast-gather.frag") + "\n"
                        + GpuPyramidFlow.read(assets, "velocity.glsl"));
        try {
            candidate.setBufferAttribute("aPosition", new float[]{-1, -1, 1, -1, -1, 1, 1, 1}, 2);
            program = candidate;
        } catch (RuntimeException failure) {
            try {
                candidate.delete();
            } catch (GlUtil.GlException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    /**
     * Writes the shutter exposure into the caller's destination. With a missing flow frame or
     * centre-only schedule this does no GL work and returns false, so the source remains sharp.
     */
    public boolean draw(int sourceTexture, int destinationFbo, int width, int height,
                        FastFootagePreviewOwner.Frame frame, long centreProjectUs,
                        MotionShutter.Schedule schedule) throws IOException, GlUtil.GlException {
        requireOpen();
        if (frame == null || !loadTaps(frame, centreProjectUs, schedule)) return false;
        if (sourceTexture <= 0 || destinationFbo < 0 || width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Invalid Fast footage render target");
        }
        var source = frame.estimate().source();
        var field = frame.estimate().field();
        region[0] = source.left();
        region[1] = source.bottom();
        region[2] = source.right() - source.left();
        region[3] = source.top() - source.bottom();
        stageSize[0] = width;
        stageSize[1] = height;
        flowSize[0] = field.width();
        flowSize[1] = field.height();
        sourcePerFlowPixel[0] = source.visibleWidth() / flowSize[0];
        sourcePerFlowPixel[1] = source.visibleHeight() / flowSize[1];

        GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, savedFramebuffer, 0);
        GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, savedViewport, 0);
        GLES20.glGetIntegerv(GLES20.GL_ACTIVE_TEXTURE, savedActiveUnit, 0);
        boolean dither = GLES20.glIsEnabled(GLES20.GL_DITHER);
        GLES20.glDisable(GLES20.GL_DITHER);
        try {
            prepare();
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, destinationFbo);
            GLES20.glViewport(0, 0, width, height);
            program.use();
            program.setSamplerTexIdUniform("uSource", sourceTexture, 0);
            program.setSamplerTexIdUniform("uFlow", field.textureId(), 1);
            program.setSamplerTexIdUniform("uGuide", field.guideTextureId(), 2);
            program.setFloatsUniform("uRegion", region);
            program.setFloatsUniform("uStageSize", stageSize);
            program.setFloatsUniform("uSourcePerFlowPixel", sourcePerFlowPixel);
            program.setFloatsUniform("uFlowSize", flowSize);
            program.setFloatsUniform("uTravelScale", travelScale);
            program.setFloatsUniform("uOffsetsA", offsetsA);
            program.setFloatsUniform("uOffsetsB", offsetsB);
            program.setFloatsUniform("uWeightsA", weightsA);
            program.setFloatsUniform("uWeightsB", weightsB);
            program.bindAttributesAndUniforms();
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            GlUtil.checkGlError();
            return true;
        } finally {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, savedFramebuffer[0]);
            GLES20.glViewport(savedViewport[0], savedViewport[1], savedViewport[2], savedViewport[3]);
            GLES20.glActiveTexture(savedActiveUnit[0]);
            if (dither) GLES20.glEnable(GLES20.GL_DITHER);
        }
    }

    private boolean loadTaps(FastFootagePreviewOwner.Frame frame, long centreUs,
                             MotionShutter.Schedule schedule) {
        long[] times = schedule.timesUs();
        double[] weights = schedule.weights();
        if (times.length != weights.length || times.length == 0 || times.length > MAX_TAPS) {
            throw new IllegalArgumentException("Invalid Fast footage shutter tap count");
        }
        double total = 0.0;
        for (double weight : weights) {
            if (!Double.isFinite(weight) || weight < 0.0) {
                throw new IllegalArgumentException("Invalid Fast footage shutter weight");
            }
            total += weight;
        }
        if (!Double.isFinite(total) || total == 0.0) {
            throw new IllegalArgumentException("Empty Fast footage shutter weight");
        }
        Arrays.fill(weightsA, 0f);
        Arrays.fill(weightsB, 0f);
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < times.length; i++) {
            if (weights[i] == 0.0) continue;
            double offset = frame.scaleForOffset((double) times[i] - centreUs);
            float packedOffset = (float) offset;
            if (!Float.isFinite(packedOffset)) {
                throw new IllegalArgumentException("Invalid Fast footage shutter offset");
            }
            min = Math.min(min, offset);
            max = Math.max(max, offset);
            float weight = (float) (weights[i] / total);
            if (i < 4) {
                offsetsA[i] = packedOffset;
                weightsA[i] = weight;
            } else {
                offsetsB[i - 4] = packedOffset;
                weightsB[i - 4] = weight;
            }
        }
        travelScale[0] = (float) Math.max(max - min, Math.max(Math.abs(min), Math.abs(max)));
        if (!Float.isFinite(travelScale[0])) {
            throw new IllegalArgumentException("Invalid Fast footage shutter span");
        }
        return travelScale[0] > 0f;
    }

    /** Release the shader on its owning GL thread. Safe to call twice. */
    public void release() throws GlUtil.GlException {
        if (released) return;
        released = true;
        GlProgram old = program;
        program = null;
        if (old != null) old.delete();
    }

    private void requireOpen() {
        if (released) throw new IllegalStateException("Fast footage gather has been released");
    }
}
