package com.fadcam.ui.faditor.compositor.lane;

import android.opengl.GLES20;
import android.opengl.GLES30;

import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.util.GlProgram;
import androidx.media3.common.util.GlUtil;

import com.fadcam.ui.faditor.compositor.RigidTextureShutterPass;
import com.fadcam.ui.faditor.motion.MotionShutter;

/** Replays an ordered scope at each shutter tap into a linear-light premultiplied accumulation. */
public final class ExactLaneExposure {
    @FunctionalInterface
    public interface Scope {
        /** Draw one complete SDR, premultiplied scope, without advancing any persistent FX state. */
        void drawAt(int targetFramebuffer, long presentationTimeUs)
                throws VideoFrameProcessingException, GlUtil.GlException;
    }

    private static final int HALF_FLOAT_OES = 0x8d61;
    private static final int SAMPLE = 0;
    private static final int FIRST_ACCUMULATION = 1;
    private static final int SECOND_ACCUMULATION = 2;

    private static final String ACCUMULATE = """
            #version 100
            precision highp float;
            varying vec2 vTexSamplingCoord;
            uniform sampler2D uSample;
            uniform sampler2D uPrevious;
            uniform float uWeight;

            vec3 toLinear(vec3 rgb) {
              return mix(rgb / 4.5, pow((rgb + 0.099) / 1.099, vec3(1.0 / 0.45)),
                         step(vec3(0.081), rgb));
            }
            void main() {
              vec4 cur = texture2D(uSample, vTexSamplingCoord);
              vec3 straight = cur.a > 0.0 ? clamp(cur.rgb / cur.a, 0.0, 1.0) : vec3(0.0);
              vec4 sampleValue = vec4(toLinear(straight) * cur.a, cur.a);
              gl_FragColor = uWeight == 1.0 ? sampleValue
                  : mix(texture2D(uPrevious, vTexSamplingCoord), sampleValue, uWeight);
            }
            """;

    private static final String RESOLVE = """
            #version 100
            precision highp float;
            varying vec2 vTexSamplingCoord;
            uniform sampler2D uLinear;
            vec3 toSdr(vec3 rgb) {
              return mix(4.5 * rgb, 1.099 * pow(rgb, vec3(0.45)) - 0.099,
                         step(vec3(0.018), rgb));
            }
            void main() {
              vec4 color = texture2D(uLinear, vTexSamplingCoord);
              vec3 straight = color.a > 0.0 ? clamp(color.rgb / color.a, 0.0, 1.0) : vec3(0.0);
              gl_FragColor = vec4(toSdr(straight) * color.a, color.a);
            }
            """;

    private final float[] weightUniform = new float[1];
    private final int[] textures = new int[3];
    private final int[] framebuffers = new int[3];
    private GlProgram accumulate;
    private GlProgram resolve;
    private int width;
    private int height;

    public void configure(int width, int height) throws GlUtil.GlException {
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("Invalid exposure target size");
        if (this.width == width && this.height == height) return;
        releaseTargets();
        this.width = width;
        this.height = height;
    }

    /** The scope needs a distinct source from every target assigned to it in this call. */
    public void draw(int outputFramebuffer, MotionShutter.Schedule shutter, Scope scope)
            throws VideoFrameProcessingException, GlUtil.GlException {
        if (width <= 0 || height <= 0) throw new IllegalStateException("Exposure target is not configured");
        long[] times = shutter.timesUs();
        double[] weights = shutter.weights();
        if (times.length == 0 || times.length != weights.length) {
            throw new IllegalArgumentException("Invalid scope exposure schedule");
        }
        int firstTap = -1;
        boolean distinctTimes = false;
        double weightSum = 0;
        for (int i = 0; i < weights.length; i++) {
            if (!Double.isFinite(weights[i]) || weights[i] < 0) {
                throw new IllegalArgumentException("Invalid scope exposure weight");
            }
            if (weights[i] > 0) {
                if (firstTap == -1) firstTap = i;
                distinctTimes |= times[i] != times[firstTap];
                weightSum += weights[i];
            }
        }
        if (firstTap == -1 || !Double.isFinite(weightSum)) {
            throw new IllegalArgumentException("Invalid total scope exposure weight");
        }

        boolean wasBlending = GLES20.glIsEnabled(GLES20.GL_BLEND);
        boolean wasScissoring = GLES20.glIsEnabled(GLES20.GL_SCISSOR_TEST);
        try {
            if (!distinctTimes) {
                scope.drawAt(outputFramebuffer, times[firstTap]);
                return;
            }
            ensureTargets();
            if (accumulate == null) accumulate = program(ACCUMULATE);
            if (resolve == null) resolve = program(RESOLVE);

            int previous = SAMPLE;
            int next = FIRST_ACCUMULATION;
            double totalWeight = 0;
            for (int i = 0; i < times.length; i++) {
                if (weights[i] == 0) continue;
                scope.drawAt(framebuffers[SAMPLE], times[i]);
                totalWeight += weights[i];
                focus(framebuffers[next]);
                accumulate.use();
                accumulate.setSamplerTexIdUniform("uSample", textures[SAMPLE], 0);
                accumulate.setSamplerTexIdUniform("uPrevious", textures[previous], 1);
                weightUniform[0] = (float) (weights[i] / totalWeight);
                accumulate.setFloatsUniform("uWeight", weightUniform);
                drawQuad(accumulate);
                previous = next;
                next = next == FIRST_ACCUMULATION ? SECOND_ACCUMULATION : FIRST_ACCUMULATION;
            }

            focus(outputFramebuffer);
            resolve.use();
            resolve.setSamplerTexIdUniform("uLinear", textures[previous], 0);
            drawQuad(resolve);
        } finally {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, outputFramebuffer);
            GLES20.glViewport(0, 0, width, height);
            restore(GLES20.GL_BLEND, wasBlending);
            restore(GLES20.GL_SCISSOR_TEST, wasScissoring);
        }
    }

    private static void restore(int state, boolean enabled) {
        if (enabled) GLES20.glEnable(state);
        else GLES20.glDisable(state);
    }

    private void focus(int framebuffer) throws GlUtil.GlException {
        GLES20.glDisable(GLES20.GL_BLEND);
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST);
        GlUtil.focusFramebufferUsingCurrentContext(framebuffer, width, height);
    }

    private static GlProgram program(String shader) throws GlUtil.GlException {
        GlProgram program = new GlProgram(RigidTextureShutterPass.VERTEX_SHADER, shader);
        program.setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), 4);
        float[] identity = GlUtil.create4x4IdentityMatrix();
        program.setFloatsUniform("uTransformationMatrix", identity);
        program.setFloatsUniform("uTexTransformationMatrix", identity);
        return program;
    }

    private static void drawQuad(GlProgram program) throws GlUtil.GlException {
        program.bindAttributesAndUniforms();
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GlUtil.checkGlError();
    }

    private void ensureTargets() throws GlUtil.GlException, VideoFrameProcessingException {
        if (framebuffers[SECOND_ACCUMULATION] != 0) return;
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        String version = GLES20.glGetString(GLES20.GL_VERSION);
        boolean es3 = version != null && version.startsWith("OpenGL ES 3.");
        try {
            textures[SAMPLE] = GlUtil.createTexture(width, height, false);
            framebuffers[SAMPLE] = GlUtil.createFboForTexture(textures[SAMPLE]);
            for (int i = FIRST_ACCUMULATION; i <= SECOND_ACCUMULATION; i++) {
                GLES20.glGenTextures(1, textures, i);
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[i]);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
                GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0,
                        es3 ? GLES30.GL_RGBA16F : GLES20.GL_RGBA, width, height, 0, GLES20.GL_RGBA,
                        es3 ? GLES30.GL_HALF_FLOAT : HALF_FLOAT_OES, null);
                GlUtil.checkGlError();
                framebuffers[i] = GlUtil.createFboForTexture(textures[i]);
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffers[i]);
                if (GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
                        != GLES20.GL_FRAMEBUFFER_COMPLETE) {
                    throw new VideoFrameProcessingException("Exact exposure requires a renderable half-float target");
                }
            }
        } catch (GlUtil.GlException | VideoFrameProcessingException error) {
            try {
                releaseTargets();
            } catch (GlUtil.GlException cleanup) {
                error.addSuppressed(cleanup);
            }
            throw error;
        }
    }

    private void releaseTargets() throws GlUtil.GlException {
        GLES20.glDeleteFramebuffers(framebuffers.length, framebuffers, 0);
        GLES20.glDeleteTextures(textures.length, textures, 0);
        java.util.Arrays.fill(framebuffers, 0);
        java.util.Arrays.fill(textures, 0);
        GlUtil.checkGlError();
    }

    public void release() throws GlUtil.GlException {
        GlProgram oldAccumulator = accumulate;
        GlProgram oldResolver = resolve;
        accumulate = null;
        resolve = null;
        width = 0;
        height = 0;
        try {
            releaseTargets();
        } finally {
            try {
                if (oldAccumulator != null) oldAccumulator.delete();
            } finally {
                if (oldResolver != null) oldResolver.delete();
            }
        }
    }
}
