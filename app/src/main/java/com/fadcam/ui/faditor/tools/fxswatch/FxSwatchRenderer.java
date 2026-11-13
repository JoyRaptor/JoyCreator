package com.fadcam.ui.faditor.tools.fxswatch;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.ui.faditor.fx.FxCompiler;
import com.fadcam.ui.faditor.fx.FxGlSource;
import com.fadcam.ui.faditor.fx.FxStack;
import com.fadcam.ui.faditor.fx.FxUniforms;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Draws a small effect stack over a picture into a Bitmap, on a GL thread of its own (the
 * swatches' {@link com.fadcam.vislab.studio.OffscreenGl}), never the preview's.
 *
 * <p>The SAME source the export and the live preview compile: {@link FxCompiler#plan},
 * {@link FxGlSource#fragment} and {@link FxUniforms#forPass}. Each pass runs as the preview runs
 * it (separable kernels twice, H then V; a FEEDBACK pass with no history yet). The layer
 * composite is left out on purpose: a swatch is the card's own result, not a layer mixed by a
 * mask.</p>
 *
 * <p>Coordinates follow the real chain: the picture is uploaded upside down so {@code uv.y = 1}
 * is its top, as it is for video, and read back the right way up.</p>
 *
 * <p>GL thread only. Every failure is logged and returns null; the caller shows a plain tile.</p>
 */
final class FxSwatchRenderer {

    private static final String TAG = "FxSwatch";
    /** Compiled programs kept, by source. Enough for every effect in the picker. */
    private static final int MAX_PROGRAMS = 96;

    private final FloatBuffer quad = ByteBuffer.allocateDirect(16 * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer();
    private final Map<String, Integer> programs =
            new LinkedHashMap<String, Integer>(32, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Integer> e) {
                    if (size() <= MAX_PROGRAMS) return false;
                    GLES20.glDeleteProgram(e.getValue());
                    return true;
                }
            };
    /** Sources that failed to compile: never retried in this process. */
    private final java.util.Set<String> failed = new java.util.HashSet<>();

    private int w, h;
    private final int[] tex = new int[2];
    private final int[] fbo = new int[2];
    private int inputTex;
    @Nullable private Bitmap inputOf;
    @Nullable private ByteBuffer pixels;

    FxSwatchRenderer() {
        quad.put(new float[]{-1, -1, 0, 1, 1, -1, 0, 1, -1, 1, 0, 1, 1, 1, 0, 1}).position(0);
    }

    /**
     * {@code stack} over {@code input} at {@code input}'s size, at {@code timeSec} on the
     * effect clock. Null when it could not be drawn (the reason is in the log).
     */
    @Nullable
    Bitmap render(@NonNull FxStack stack, @NonNull Bitmap input, float timeSec) {
        try {
            FxCompiler.Plan plan = FxCompiler.plan(stack);
            int vw = input.getWidth(), vh = input.getHeight();
            if (!targets(vw, vh)) return null;
            upload(input);
            if (plan.passes.isEmpty()) return read(inputFbo());

            int srcTex = inputTex;
            int dst = 0;
            boolean any = false;
            for (FxCompiler.Pass pa : plan.passes) {
                int renders = Math.max(1, pa.repeats);
                List<FxUniforms.Value> values = FxUniforms.forPass(pa);
                String src = FxGlSource.fragment(pa, FxGlSource.KERNEL_HALF, false, 1);
                Integer prog = program(src);
                if (prog == null) return null;
                for (int r = 0; r < renders; r++) {
                    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[dst]);
                    GLES20.glViewport(0, 0, vw, vh);
                    GLES20.glUseProgram(prog);
                    bindQuad(prog);
                    sampler(prog, "uTexSampler", srcTex, 0);
                    sampler(prog, "uBaseSampler", inputTex, 1);
                    if (pa.feedback) {
                        // No history in a still: the pass reads its input instead.
                        sampler(prog, FxCompiler.U_PREV, inputTex, 2);
                        f1(prog, FxCompiler.U_PREV_VALID, 0f);
                        f1(prog, FxCompiler.U_DT, 1f / 30f);
                    }
                    f2(prog, FxCompiler.U_TEXEL, 1f / vw, 1f / vh);
                    f1(prog, FxCompiler.U_ASPECT, (float) vw / vh);
                    f1(prog, FxCompiler.U_TIME, timeSec);
                    boolean second = renders > 1 && r == 1;
                    f2(prog, FxCompiler.U_DIR, second ? 0f : 1f, second ? 1f : 0f);
                    for (FxUniforms.Value v : values) fn(prog, v.name, v.data);
                    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
                    srcTex = tex[dst];
                    dst = 1 - dst;
                    any = true;
                }
            }
            int err = GLES20.glGetError();
            if (err != GLES20.GL_NO_ERROR) Log.w(TAG, "GL error " + err + " drawing " + plan);
            return read(any ? fbo[1 - dst] : inputFbo());
        } catch (RuntimeException e) {
            Log.w(TAG, "swatch not drawn", e);
            return null;
        }
    }

    /** The input has no framebuffer of its own: an empty plan is a copy through slot 0. */
    private int inputFbo() {
        Integer prog = program(FxGlSource.PASSTHROUGH_FRAGMENT);
        if (prog == null) return fbo[0];
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[0]);
        GLES20.glViewport(0, 0, w, h);
        GLES20.glUseProgram(prog);
        bindQuad(prog);
        sampler(prog, "uTexSampler", inputTex, 0);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        return fbo[0];
    }

    @Nullable
    private Integer program(@NonNull String fragment) {
        Integer p = programs.get(fragment);
        if (p != null) return p;
        if (failed.contains(fragment)) return null;
        int vs = shader(GLES20.GL_VERTEX_SHADER, FxGlSource.VERTEX_SHADER);
        int fs = shader(GLES20.GL_FRAGMENT_SHADER, fragment);
        if (vs == 0 || fs == 0) {
            failed.add(fragment);
            return null;
        }
        int prog = GLES20.glCreateProgram();
        GLES20.glAttachShader(prog, vs);
        GLES20.glAttachShader(prog, fs);
        GLES20.glLinkProgram(prog);
        GLES20.glDeleteShader(vs);
        GLES20.glDeleteShader(fs);
        int[] ok = new int[1];
        GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, ok, 0);
        if (ok[0] == 0) {
            Log.w(TAG, "swatch program did not link: " + GLES20.glGetProgramInfoLog(prog));
            GLES20.glDeleteProgram(prog);
            failed.add(fragment);
            return null;
        }
        programs.put(fragment, prog);
        return prog;
    }

    private static int shader(int type, @NonNull String src) {
        int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, src);
        GLES20.glCompileShader(s);
        int[] ok = new int[1];
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) {
            Log.w(TAG, "swatch shader did not compile: " + GLES20.glGetShaderInfoLog(s));
            GLES20.glDeleteShader(s);
            return 0;
        }
        return s;
    }

    /** Two ping-pong textures of the swatch size, and the input texture. */
    private boolean targets(int vw, int vh) {
        if (fbo[0] != 0 && vw == w && vh == h) return true;
        release();
        GLES20.glGenTextures(2, tex, 0);
        GLES20.glGenFramebuffers(2, fbo, 0);
        for (int i = 0; i < 2; i++) {
            texParams(tex[i]);
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, vw, vh, 0,
                    GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[i]);
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                    GLES20.GL_TEXTURE_2D, tex[i], 0);
            int status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER);
            if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
                Log.w(TAG, "swatch framebuffer incomplete: " + status);
                release();
                return false;
            }
        }
        int[] t = new int[1];
        GLES20.glGenTextures(1, t, 0);
        inputTex = t[0];
        texParams(inputTex);
        w = vw;
        h = vh;
        pixels = ByteBuffer.allocateDirect(vw * vh * 4).order(ByteOrder.nativeOrder());
        return true;
    }

    private static void texParams(int t) {
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        // CLAMP, as the preview's targets: a blur must not wrap the far edge in.
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
    }

    /** Upload {@code b} upside down (its top at uv.y = 1), only when it is a new picture. */
    private void upload(@NonNull Bitmap b) {
        if (b == inputOf) return;
        Bitmap flipped = Bitmap.createBitmap(b.getWidth(), b.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(flipped);
        Matrix m = new Matrix();
        m.setScale(1f, -1f, 0f, b.getHeight() / 2f);
        c.drawBitmap(b, m, new Paint(Paint.FILTER_BITMAP_FLAG));
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, inputTex);
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, flipped, 0);
        flipped.recycle();
        inputOf = b;
    }

    /** The finished picture, opaque, the right way up. */
    @Nullable
    private Bitmap read(int framebuffer) {
        ByteBuffer px = pixels;
        if (px == null) return null;
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer);
        px.clear();
        GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, px);
        // GL rows run bottom-up (and the picture went in upside down), so row y of the
        // framebuffer is row h-1-y of the Bitmap. A swatch is opaque: alpha forced to full.
        byte[] all = new byte[w * h * 4];
        px.position(0);
        px.get(all);
        byte[] out = new byte[all.length];
        int stride = w * 4;
        for (int y = 0; y < h; y++) {
            System.arraycopy(all, y * stride, out, (h - 1 - y) * stride, stride);
        }
        for (int i = 3; i < out.length; i += 4) out[i] = (byte) 0xFF;
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        b.copyPixelsFromBuffer(ByteBuffer.wrap(out));
        return b;
    }

    private void bindQuad(int program) {
        int loc = GLES20.glGetAttribLocation(program, "aFramePosition");
        if (loc < 0) return;
        quad.position(0);
        GLES20.glVertexAttribPointer(loc, 4, GLES20.GL_FLOAT, false, 0, quad);
        GLES20.glEnableVertexAttribArray(loc);
    }

    private static void sampler(int program, @NonNull String name, int texId, int unit) {
        int loc = GLES20.glGetUniformLocation(program, name);
        if (loc < 0) return;
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + unit);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId);
        GLES20.glUniform1i(loc, unit);
    }

    private static void f1(int program, @NonNull String name, float v) {
        int loc = GLES20.glGetUniformLocation(program, name);
        if (loc >= 0) GLES20.glUniform1f(loc, v);
    }

    private static void f2(int program, @NonNull String name, float a, float b) {
        int loc = GLES20.glGetUniformLocation(program, name);
        if (loc >= 0) GLES20.glUniform2f(loc, a, b);
    }

    private static void fn(int program, @NonNull String name, @NonNull float[] v) {
        int loc = GLES20.glGetUniformLocation(program, name);
        if (loc < 0) return;
        switch (v.length) {
            case 1: GLES20.glUniform1f(loc, v[0]); break;
            case 2: GLES20.glUniform2f(loc, v[0], v[1]); break;
            case 3: GLES20.glUniform3f(loc, v[0], v[1], v[2]); break;
            case 4: GLES20.glUniform4f(loc, v[0], v[1], v[2], v[3]); break;
            default: break;
        }
    }

    private void release() {
        if (fbo[0] != 0) {
            GLES20.glDeleteFramebuffers(2, fbo, 0);
            GLES20.glDeleteTextures(2, tex, 0);
            GLES20.glDeleteTextures(1, new int[]{inputTex}, 0);
        }
        fbo[0] = fbo[1] = tex[0] = tex[1] = inputTex = 0;
        inputOf = null;
        w = h = 0;
    }
}
