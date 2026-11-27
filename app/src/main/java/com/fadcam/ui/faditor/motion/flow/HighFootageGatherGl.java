package com.fadcam.ui.faditor.motion.flow;

import android.content.res.AssetManager;
import android.opengl.GLES20;

import androidx.media3.common.util.GlProgram;
import androidx.media3.common.util.GlUtil;

import com.fadcam.ui.faditor.motion.MotionShutter;

import java.io.IOException;
import java.util.Arrays;

/** Bidirectional neighbouring-frame reconstruction for High footage exposure (GLES2). */
public final class HighFootageGatherGl {
    public static final int MAX_TAPS = 12;
    private static final int FIRST_FLOW_UNIT = 3;

    /** Packed, confidence-bearing fields for one increasing decoded PTS pair. */
    public record Pair(int forwardTexture, int backwardTexture, long fromPtsUs, long toPtsUs) {
        public Pair {
            if (forwardTexture <= 0 || backwardTexture <= 0 || fromPtsUs >= toPtsUs) {
                throw new IllegalArgumentException("Invalid High footage flow pair");
            }
        }
    }

    /** Three caller-owned staged source frames and their two adjacent flow pairs. */
    public record Frame(int previousTexture, int currentTexture, int nextTexture,
                        Pair previousToCurrent, Pair currentToNext, double sourceSpeed,
                        float left, float bottom, float right, float top) {
        public Frame {
            if (currentTexture <= 0 || !Double.isFinite(sourceSpeed)
                    || left < 0f || bottom < 0f || right > 1f || top > 1f
                    || left >= right || bottom >= top) {
                throw new IllegalArgumentException("Invalid High footage frame");
            }
            if (previousToCurrent != null && previousTexture <= 0) {
                throw new IllegalArgumentException("Invalid previous High footage frame");
            }
            if (currentToNext != null && nextTexture <= 0) {
                throw new IllegalArgumentException("Invalid next High footage frame");
            }
            if (previousToCurrent != null && currentToNext != null
                    && previousToCurrent.toPtsUs() != currentToNext.fromPtsUs()) {
                throw new IllegalArgumentException("High footage pairs disagree on centre PTS");
            }
        }

        public long currentPtsUs() {
            if (previousToCurrent != null) return previousToCurrent.toPtsUs();
            if (currentToNext != null) return currentToNext.fromPtsUs();
            return Long.MIN_VALUE;
        }
    }

    private final AssetManager assets;
    private final float[] fractionsA = new float[4], fractionsB = new float[4], fractionsC = new float[4];
    private final float[] weightsA = new float[4], weightsB = new float[4], weightsC = new float[4];
    private final float[] region = new float[4], stageSize = new float[2], flowSize = new float[2];
    private final float[] sourcePerFlowPixel = new float[2];
    private final float[] extents = new float[2];
    private final int[] savedFramebuffer = new int[1], savedViewport = new int[4], savedActiveUnit = new int[1];
    private GlProgram program;
    private boolean released;

    public HighFootageGatherGl(AssetManager assets) { this.assets = assets; }

    public void prepare() throws IOException, GlUtil.GlException {
        requireOpen();
        if (program != null) return;
        GlProgram candidate = new GlProgram(GpuPyramidFlow.read(assets, "fullscreen.vert"),
                GpuPyramidFlow.read(assets, "high-gather.frag") + "\n"
                        + GpuPyramidFlow.read(assets, "velocity.glsl"));
        try {
            candidate.setBufferAttribute("aPosition", new float[]{-1, -1, 1, -1, -1, 1, 1, 1}, 2);
            program = candidate;
        } catch (RuntimeException failure) {
            try { candidate.delete(); } catch (GlUtil.GlException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    /** Draws one centred exposure, or returns false without GL work when it must stay sharp. */
    public boolean draw(Frame frame, int destinationFbo, int width, int height,
                        int flowWidth, int flowHeight, long centreProjectUs,
                        MotionShutter.Schedule schedule) throws IOException, GlUtil.GlException {
        requireOpen();
        if (frame == null || frame.sourceSpeed() == 0.0 || frame.currentPtsUs() == Long.MIN_VALUE
                || !loadTaps(frame, centreProjectUs, schedule)) return false;
        if (destinationFbo < 0 || width <= 0 || height <= 0 || flowWidth <= 0 || flowHeight <= 0) {
            throw new IllegalArgumentException("Invalid High footage render target");
        }
        Pair before = frame.previousToCurrent(), after = frame.currentToNext();
        Pair beforeTexture = before != null ? before : after;
        Pair afterTexture = after != null ? after : before;
        region[0] = frame.left(); region[1] = frame.bottom();
        region[2] = frame.right() - frame.left(); region[3] = frame.top() - frame.bottom();
        stageSize[0] = width; stageSize[1] = height;
        flowSize[0] = flowWidth; flowSize[1] = flowHeight;
        sourcePerFlowPixel[0] = width * region[2] / flowWidth;
        sourcePerFlowPixel[1] = height * region[3] / flowHeight;

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
            program.setSamplerTexIdUniform("uPrevious", before == null ? frame.currentTexture() : frame.previousTexture(), 0);
            program.setSamplerTexIdUniform("uCurrent", frame.currentTexture(), 1);
            program.setSamplerTexIdUniform("uNext", after == null ? frame.currentTexture() : frame.nextTexture(), 2);
            program.setSamplerTexIdUniform("uBeforeForward", beforeTexture.forwardTexture(), FIRST_FLOW_UNIT);
            program.setSamplerTexIdUniform("uBeforeBackward", beforeTexture.backwardTexture(), FIRST_FLOW_UNIT + 1);
            program.setSamplerTexIdUniform("uAfterForward", afterTexture.forwardTexture(), FIRST_FLOW_UNIT + 2);
            program.setSamplerTexIdUniform("uAfterBackward", afterTexture.backwardTexture(), FIRST_FLOW_UNIT + 3);
            program.setFloatsUniform("uRegion", region);
            program.setFloatsUniform("uStageSize", stageSize);
            program.setFloatsUniform("uFlowSize", flowSize);
            program.setFloatsUniform("uSourcePerFlowPixel", sourcePerFlowPixel);
            program.setFloatsUniform("uExtents", extents);
            program.setFloatsUniform("uFractionsA", fractionsA);
            program.setFloatsUniform("uFractionsB", fractionsB);
            program.setFloatsUniform("uFractionsC", fractionsC);
            program.setFloatsUniform("uWeightsA", weightsA);
            program.setFloatsUniform("uWeightsB", weightsB);
            program.setFloatsUniform("uWeightsC", weightsC);
            program.bindAttributesAndUniforms();
            // Media3 rebinds samplers with linear filtering. Packed vector bytes must be read intact.
            for (int unit = FIRST_FLOW_UNIT; unit < FIRST_FLOW_UNIT + 4; unit++) {
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + unit);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST);
            }
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

    private boolean loadTaps(Frame frame, long centreUs, MotionShutter.Schedule schedule) {
        if (schedule == null) return false;
        long[] times = schedule.timesUs(); double[] weights = schedule.weights();
        if (times.length == 0 || times.length != weights.length || times.length > MAX_TAPS) {
            throw new IllegalArgumentException("Invalid High footage shutter tap count");
        }
        double total = 0;
        for (double weight : weights) {
            if (!Double.isFinite(weight) || weight < 0) throw new IllegalArgumentException("Invalid High footage weight");
            total += weight;
        }
        if (!(total > 0) || !Double.isFinite(total)) throw new IllegalArgumentException("Empty High footage exposure");
        for (float[] a : new float[][]{fractionsA, fractionsB, fractionsC, weightsA, weightsB, weightsC}) Arrays.fill(a, 0f);
        extents[0] = extents[1] = 0f;
        long currentPts = frame.currentPtsUs();
        for (int i = 0; i < times.length; i++) {
            if (weights[i] == 0) continue;
            double offset = ((double) times[i] - centreUs) * frame.sourceSpeed();
            float encoded = 0f;
            if (offset < 0 && frame.previousToCurrent() != null) {
                double from = (double) frame.previousToCurrent().fromPtsUs() - currentPts;
                if (offset >= from) {
                    double alpha = (offset - from) / -from;
                    encoded = (float) (alpha - 1.0);
                    extents[0] = Math.max(extents[0], (float) (1.0 - alpha));
                }
            } else if (offset > 0 && frame.currentToNext() != null) {
                double to = (double) frame.currentToNext().toPtsUs() - currentPts;
                if (offset <= to) {
                    double alpha = offset / to;
                    encoded = (float) (1.0 + alpha);
                    extents[1] = Math.max(extents[1], (float) alpha);
                }
            }
            if (encoded != 0f) {
                set(i, encoded, (float) (weights[i] / total));
            }
        }
        return extents[0] > 0f || extents[1] > 0f;
    }

    private void set(int index, float fraction, float weight) {
        float[] f = index < 4 ? fractionsA : index < 8 ? fractionsB : fractionsC;
        float[] w = index < 4 ? weightsA : index < 8 ? weightsB : weightsC;
        int slot = index & 3; f[slot] = fraction; w[slot] = weight;
    }

    public void release() throws GlUtil.GlException {
        if (released) return;
        released = true;
        GlProgram old = program; program = null;
        if (old != null) old.delete();
    }

    private void requireOpen() {
        if (released) throw new IllegalStateException("High footage gather has been released");
    }
}
