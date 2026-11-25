package com.fadcam.ui.faditor.motion;

import android.content.res.AssetManager;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.util.Log;

import androidx.media3.common.util.GlUtil;

import com.fadcam.ui.faditor.motion.flow.FlowConfidence;
import com.fadcam.ui.faditor.motion.flow.GpuPyramidFlow;
import com.fadcam.ui.faditor.motion.flow.LumaImage;
import com.fadcam.ui.faditor.motion.flow.PyramidFlowEstimator;
import com.fadcam.vislab.gl.GpuTimer;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Locale;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

final class MotionFlowBenchRenderer implements GLSurfaceView.Renderer {
    private static final String TAG = MotionFlowBenchActivity.TAG;
    private static final int WARMUP = 30, SAMPLES = 180, DRAIN = 8;
    private static final int MAX_LEVELS = PyramidFlowEstimator.Settings.DEFAULT.maxLevels();
    private static final GpuPyramidFlow.Pass[] PASSES = GpuPyramidFlow.Pass.values();
    private static final int STAGE_COUNT = 1 + PASSES.length * MAX_LEVELS;

    private final AssetManager assets;
    private final boolean timing;
    private final Runnable finished;
    private final int[] inputs = new int[2];
    private final long[][] times = new long[STAGE_COUNT][SAMPLES];
    private final int[] counts = new int[STAGE_COUNT];
    private GpuPyramidFlow flow;
    private GpuTimer timer;
    private int frame;
    private boolean done;

    private final GpuPyramidFlow.PassObserver observer = new GpuPyramidFlow.PassObserver() {
        @Override public void begin(GpuPyramidFlow.Pass pass, int level) {
            timer.begin(1 + pass.ordinal() * MAX_LEVELS + level);
        }

        @Override public void end() {
            timer.end();
        }
    };

    MotionFlowBenchRenderer(AssetManager assets, boolean timing, Runnable finished) {
        this.assets = assets;
        this.timing = timing;
        this.finished = finished;
    }

    @Override public void onSurfaceCreated(GL10 ignored, EGLConfig config) {
        if (done) return;
        Log.i(TAG, "renderer=" + GLES20.glGetString(GLES20.GL_RENDERER)
                + " driver=" + GLES20.glGetString(GLES20.GL_VERSION));
        try {
            if (!timing) return;
            String[] names = new String[STAGE_COUNT];
            for (int stage = 0; stage < names.length; stage++) names[stage] = stageName(stage);
            timer = new GpuTimer(names);
            if (!timer.available()) {
                Log.w(TAG, "TIMING_UNAVAILABLE: no disjoint timer queries");
                complete();
                return;
            }
            prepare(240, 135, false);
            upload(inputs[0], 240, 135, scene(240, 135, 1, 3.4, -1.7, 0));
            upload(inputs[1], 240, 135, scene(240, 135, 1, 0, 0, 0));
        } catch (Exception problem) {
            fail(problem);
        }
    }

    @Override public void onSurfaceChanged(GL10 ignored, int width, int height) {}

    @Override public void onDrawFrame(GL10 ignored) {
        if (done) return;
        try {
            if (!timing) {
                runParity();
                Log.i(TAG, "PARITY_PASS: identity, subpixel, long shift and flash");
                complete();
                return;
            }
            if (frame < WARMUP) {
                flow.estimate(inputs[0], inputs[1]);
            } else if (frame < WARMUP + SAMPLES) {
                timer.begin(0);
                try {
                    flow.estimate(inputs[0], inputs[1]);
                } finally {
                    timer.end();
                }
            } else if (frame < WARMUP + 2 * SAMPLES) {
                flow.estimate(inputs[0], inputs[1], observer);
            }
            timer.frameDone((stage, ns) -> {
                if (counts[stage] < SAMPLES && ns > 0) times[stage][counts[stage]++] = ns;
            });
            if (++frame == WARMUP + 2 * SAMPLES + DRAIN) {
                reportTimings();
                complete();
            }
        } catch (Exception problem) {
            fail(problem);
        }
    }

    private void prepare(int w, int h, boolean reverse) throws Exception {
        flow = new GpuPyramidFlow(assets, w, h, reverse);
        GLES20.glGenTextures(inputs.length, inputs, 0);
        GlUtil.checkGlError();
    }

    private void runParity() throws Exception {
        int w = 161, h = 121;
        prepare(w, h, true);
        verify(w, h, "identity", 1, 0, 0, 0);
        verify(w, h, "subpixel", 1, 3.4, -1.7, 0);
        verify(w, h, "long-shift", 1, 17, 9, 0);
        verify(w, h, "flash", 2, 2.5, 1.25, 0.12);
    }

    private void verify(int w, int h, String name, int seed, double dx, double dy, double flash)
            throws GlUtil.GlException {
        float[] a = scene(w, h, seed, 0, 0, 0);
        float[] b = scene(w, h, seed, dx, dy, flash);
        var from = new LumaImage(w, h, a);
        var to = new LumaImage(w, h, b);
        var oracle = new PyramidFlowEstimator();
        var expected = FlowConfidence.attach(oracle.estimate(from, to, 0, 33_367),
                oracle.estimate(to, from, 33_367, 0), from, to, FlowConfidence.Params.DEFAULT);
        upload(inputs[0], w, h, a);
        upload(inputs[1], w, h, b);
        ByteBuffer gpu = readField(flow.estimate(inputs[0], inputs[1]));
        double[] errors = new double[w * h];
        int count = 0;
        double total = 0, confidenceTotal = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                // GL output rows follow the input upload order. Keep the same Y convention in the oracle.
                int rgba = gpu.getInt();
                double vx = VelocityPack.xPixels(rgba), vy = VelocityPack.yPixels(rgba);
                if ("identity".equals(name) && (vx != 0 || vy != 0)) {
                    throw new IllegalStateException("Still footage produced non-zero flow at " + x + "," + y);
                }
                confidenceTotal += Math.abs(VelocityPack.order(rgba) / 255.0 - expected.confidence(x, y));
                if (x < 24 || x >= w - 24 || y < 24 || y >= h - 24) continue;
                // A flash intentionally lowers photometric confidence even with the correct vector.
                if (flash == 0 && expected.confidence(x, y) < 0.5f) continue;
                double error = Math.hypot(vx - expected.u(x, y), vy - expected.v(x, y));
                errors[count++] = error;
                total += error;
            }
        }
        if (count == 0) throw new IllegalStateException("No confident interior pixels for " + name);
        Arrays.sort(errors, 0, count);
        double mean = total / count, p99 = errors[(int) Math.ceil(count * 0.99) - 1];
        double confidenceError = confidenceTotal / (w * h);
        Log.i(TAG, String.format(Locale.ROOT, "PARITY %s pixels=%d mean=%.5fpx p99=%.5fpx confidenceMAE=%.5f",
                name, count, mean, p99, confidenceError));
        if (mean > 0.1 || p99 > 0.5 || confidenceError > 0.05) {
            throw new IllegalStateException("Flow differs from the Java oracle for " + name);
        }
    }

    private void reportTimings() {
        for (int stage = 0; stage < counts.length; stage++) {
            int count = counts[stage];
            if (count == 0) continue;
            long[] samples = times[stage];
            Arrays.sort(samples, 0, count);
            double p95 = samples[(int) Math.ceil(0.95 * count) - 1] / 1e6;
            Log.i(TAG, String.format(Locale.ROOT, "GPU 240x135 %s samples=%d p95=%.3fms",
                    stageName(stage), count, p95));
        }
        if (counts[0] < SAMPLES / 2) {
            Log.w(TAG, "TIMING_INCONCLUSIVE: too few valid total queries, samples=" + counts[0]);
        } else {
            long p95 = times[0][(int) Math.ceil(0.95 * counts[0]) - 1];
            Log.i(TAG, p95 <= 3_000_000 ? "TIMING_PASS: <=3 ms P95" : "TIMING_OVER_BUDGET: >3 ms P95");
        }
    }

    private static String stageName(int stage) {
        if (stage == 0) return "total";
        return PASSES[(stage - 1) / MAX_LEVELS].name() + "-level-" + (stage - 1) % MAX_LEVELS;
    }

    private static ByteBuffer readField(GpuPyramidFlow.FieldTexture field) throws GlUtil.GlException {
        int[] fbo = new int[1];
        GLES20.glGenFramebuffers(1, fbo, 0);
        try {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[0]);
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                    GLES20.GL_TEXTURE_2D, field.textureId(), 0);
            if (GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) != GLES20.GL_FRAMEBUFFER_COMPLETE) {
                throw new IllegalStateException("Incomplete flow readback framebuffer");
            }
            var bytes = ByteBuffer.allocateDirect(field.width() * field.height() * 4);
            GLES20.glReadPixels(0, 0, field.width(), field.height(), GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, bytes);
            GlUtil.checkGlError();
            return bytes;
        } finally {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
            GLES20.glDeleteFramebuffers(1, fbo, 0);
        }
    }

    private static void upload(int texture, int w, int h, float[] luma) throws GlUtil.GlException {
        var pixels = ByteBuffer.allocateDirect(w * h * 4);
        for (float value : luma) {
            byte channel = (byte) Math.round(value * 255);
            pixels.put(channel).put(channel).put(channel).put((byte) 255);
        }
        pixels.flip();
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels);
        GlUtil.checkGlError();
    }

    private static float[] scene(int w, int h, int seed, double dx, double dy, double flash) {
        float[] values = new float[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                double sum = 0, total = 0, amp = 0.5;
                for (int octave = 0; octave < 4; octave++) {
                    double period = 24.0 / (1 << octave);
                    sum += amp * noise(seed * 31 + octave, (x - dx) / period, (y - dy) / period);
                    total += amp;
                    amp *= 0.55;
                }
                values[y * w + x] = Math.round(255 * (0.2 + 0.6 * sum / total + flash)) / 255f;
            }
        }
        return values;
    }

    private static double noise(int seed, double x, double y) {
        int x0 = (int) Math.floor(x), y0 = (int) Math.floor(y);
        double fx = x - x0, fy = y - y0;
        fx = fx * fx * (3 - 2 * fx);
        fy = fy * fy * (3 - 2 * fy);
        double a = hash(seed, x0, y0), b = hash(seed, x0 + 1, y0);
        double c = hash(seed, x0, y0 + 1), d = hash(seed, x0 + 1, y0 + 1);
        return (a + (b - a) * fx) * (1 - fy) + (c + (d - c) * fx) * fy;
    }

    private static double hash(int seed, int x, int y) {
        long h = seed * 0x9E3779B97F4A7C15L + x * 0xC2B2AE3D27D4EB4FL + y * 0x165667B19E3779F9L;
        h ^= h >>> 29;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 32;
        return (h >>> 11) / (double) (1L << 53);
    }

    private void fail(Exception problem) {
        Log.e(TAG, "FLOW_BENCH_FAILED", problem);
        complete();
    }

    private void complete() {
        done = true;
        if (timer != null) timer.release();
        if (flow != null) {
            try {
                flow.release();
            } catch (GlUtil.GlException error) {
                Log.e(TAG, "Flow diagnostic cleanup failed", error);
            }
        }
        GLES20.glDeleteTextures(inputs.length, inputs, 0);
        finished.run();
    }
}
