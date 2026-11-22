package com.fadcam.ui.faditor.motion;

import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.util.Log;

import com.fadcam.vislab.gl.GpuTimer;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Arrays;
import java.util.Locale;
import java.util.Random;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/** A full-frame GLES calibration workload, isolated from the Studio preview compositor. */
final class MotionGatherCalibration implements GLSurfaceView.Renderer {
    private static final int W = 1280, H = 720;
    private static final int WARMUP = 30, COUNT = 180;
    private static final String VERTEX = """
            attribute vec2 a;
            varying vec2 uv;
            void main(){ uv = a*0.5+0.5; gl_Position = vec4(a,0.0,1.0); }
            """;
    private static final String GATHER = """
            precision highp float;
            varying vec2 uv;
            uniform sampler2D image;
            void main(){
                vec4 sum = vec4(0.0);
                for(int i=0; i<12; ++i){
                    float offset = float(i)-5.5;
                    sum += texture2D(image, uv+vec2(offset*1.7/1280.0,offset*0.7/720.0));
                }
                gl_FragColor = sum/12.0;
            }
            """;
    private static final String PRESENT = """
            precision mediump float;
            varying vec2 uv;
            uniform sampler2D image;
            void main(){ gl_FragColor = texture2D(image, uv); }
            """;
    private final Runnable finished;
    private final FloatBuffer quad = ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer();
    private final double[] ms = new double[COUNT];
    private int frames, count, surfaceWidth, surfaceHeight, input, output, fbo, gather, present;
    private GpuTimer timer;
    private boolean done;

    MotionGatherCalibration(Runnable finished) {
        this.finished = finished;
        quad.put(new float[]{-1, -1, 1, -1, -1, 1, 1, 1}).position(0);
    }

    @Override public void onSurfaceCreated(GL10 ignored, EGLConfig configuration) {
        try {
            gather = program(GATHER);
            present = program(PRESENT);
            ByteBuffer pattern = ByteBuffer.allocateDirect(W * H * 4);
            byte[] row = new byte[W * 4];
            var random = new Random(170);
            for (int y = 0; y < H; y++) {
                random.nextBytes(row);
                pattern.put(row);
            }
            pattern.flip();
            input = texture(pattern);
            output = texture(null);
            int[] id = new int[1];
            GLES20.glGenFramebuffers(1, id, 0);
            fbo = id[0];
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo);
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                    GLES20.GL_TEXTURE_2D, output, 0);
            if (GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) != GLES20.GL_FRAMEBUFFER_COMPLETE) {
                throw new IllegalStateException("incomplete 720p calibration framebuffer");
            }
            timer = new GpuTimer("12-tap");
            Log.i(MotionBenchActivity.TAG, "Calibration: renderer=" + GLES20.glGetString(GLES20.GL_RENDERER)
                    + " driver=" + GLES20.glGetString(GLES20.GL_VERSION)
                    + " timerQuery=" + timer.available());
        } catch (RuntimeException e) {
            Log.e(MotionBenchActivity.TAG, "Calibration setup failed", e);
            complete();
        }
    }

    @Override public void onSurfaceChanged(GL10 ignored, int width, int height) {
        surfaceWidth = width;
        surfaceHeight = height;
    }

    @Override public void onDrawFrame(GL10 ignored) {
        if (done) return;
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo);
        GLES20.glViewport(0, 0, W, H);
        if (timer.available() && frames >= WARMUP && frames < WARMUP + COUNT) timer.begin(0);
        draw(gather, input);
        timer.end();

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight);
        draw(present, output);
        timer.frameDone((stage, nanos) -> {
            if (count < ms.length) ms[count++] = nanos / 1e6;
        });
        if (++frames >= WARMUP + COUNT + 8) {
            if (count > 0) {
                Arrays.sort(ms, 0, count);
                Log.i(MotionBenchActivity.TAG, String.format(Locale.ROOT,
                        "12-tap 1280x720 GPU: samples=%d p50=%.3fms p95=%.3fms p99=%.3fms",
                        count, percentile(.50), percentile(.95), percentile(.99)));
            } else {
                Log.w(MotionBenchActivity.TAG, "12-tap GPU timing unavailable on this context");
            }
            complete();
        }
    }

    private double percentile(double p) { return ms[Math.min(count - 1, (int) Math.ceil(p * count) - 1)]; }

    private void draw(int program, int texture) {
        GLES20.glUseProgram(program);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "image"), 0);
        int position = GLES20.glGetAttribLocation(program, "a");
        quad.position(0);
        GLES20.glEnableVertexAttribArray(position);
        GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 0, quad);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glDisableVertexAttribArray(position);
    }

    private void complete() {
        done = true;
        if (timer != null) timer.release();
        GLES20.glDeleteTextures(2, new int[]{input, output}, 0);
        GLES20.glDeleteFramebuffers(1, new int[]{fbo}, 0);
        GLES20.glDeleteProgram(gather);
        GLES20.glDeleteProgram(present);
        finished.run();
    }

    private static int texture(ByteBuffer pixels) {
        int[] id = new int[1];
        GLES20.glGenTextures(1, id, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id[0]);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, W, H, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        return id[0];
    }

    private static int program(String fragment) {
        int vertex = shader(GLES20.GL_VERTEX_SHADER, VERTEX);
        int pixel = shader(GLES20.GL_FRAGMENT_SHADER, fragment);
        int program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, vertex);
        GLES20.glAttachShader(program, pixel);
        GLES20.glLinkProgram(program);
        GLES20.glDeleteShader(vertex);
        GLES20.glDeleteShader(pixel);
        int[] ok = new int[1];
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, ok, 0);
        if (ok[0] == 0) throw new IllegalStateException(GLES20.glGetProgramInfoLog(program));
        return program;
    }

    private static int shader(int type, String source) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);
        int[] ok = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) throw new IllegalStateException(GLES20.glGetShaderInfoLog(shader));
        return shader;
    }
}
