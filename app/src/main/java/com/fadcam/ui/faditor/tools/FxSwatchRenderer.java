package com.fadcam.ui.faditor.tools;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Shader;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.util.LruCache;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.ui.faditor.fx.FxCompiler;
import com.fadcam.ui.faditor.fx.FxDeviceGuard;
import com.fadcam.ui.faditor.fx.FxEffectDef;
import com.fadcam.ui.faditor.fx.FxGlSource;
import com.fadcam.ui.faditor.fx.FxInstance;
import com.fadcam.ui.faditor.fx.FxStack;
import com.fadcam.ui.faditor.fx.FxUniforms;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.List;

/**
 * STU-6: draws a small still of an effect (or of a single stack card) with the REAL compiler,
 * on its own offscreen GL context, so the picker and each card can show what the effect looks
 * like. Same source the live preview and the export compile ({@link FxGlSource#fragment}),
 * same uniform packer ({@link FxUniforms}), so a swatch cannot disagree with the result.
 *
 * <p>One effect at a time, one card alone, composite at full opacity with no mask and no key.
 * Echo Trails (it needs last frame) and anything that fails to compile return {@code null};
 * the caller shows a plain tile, never an error.</p>
 *
 * <p>Call off the main thread. Rendering is serialised on {@link #LOCK}: only one offscreen
 * context exists at a time. The cache is in memory, keyed by effect id + source + the values
 * that were drawn, so an edited effect or a moved slider re-renders by itself.</p>
 */
public final class FxSwatchRenderer {

    private FxSwatchRenderer() {}

    private static final Object LOCK = new Object();
    private static final LruCache<String, Bitmap> CACHE = new LruCache<>(48);

    /** Where in its life the picture is taken. Animated effects show this moment. */
    private static final float SWATCH_TIME_SEC = 0.6f;

    private static final FloatBuffer QUAD = quad();

    private static FloatBuffer quad() {
        float[] v = {-1f, -1f, 0f, 1f, 1f, -1f, 0f, 1f, -1f, 1f, 0f, 1f, 1f, 1f, 0f, 1f};
        FloatBuffer b = ByteBuffer.allocateDirect(v.length * 4).order(ByteOrder.nativeOrder())
                .asFloatBuffer();
        b.put(v).position(0);
        return b;
    }

    /** A cached picture, or null. Cheap; safe on the main thread. */
    @Nullable
    public static Bitmap cached(@NonNull FxEffectDef def, int sizePx) {
        return CACHE.get(keyOf(new FxInstance(def.id, 0), sizePx));
    }

    /** Render the effect at its defaults. Blocking GL work: not on the main thread. */
    @Nullable
    public static Bitmap renderDefault(@NonNull FxEffectDef def, int sizePx) {
        FxStack s = new FxStack();
        return renderCard(s, s.add(def.id), def, sizePx);
    }

    /** Render one card ALONE at its current values. Blocking GL work. */
    @Nullable
    public static Bitmap renderCard(@NonNull FxInstance card, int sizePx) {
        FxEffectDef def = card.def();
        if (def == null) return null;
        FxStack one = new FxStack();
        FxInstance copy = card.copy();
        copy.enabled = true;
        one.cards().add(copy);
        return renderCard(one, copy, def, sizePx);
    }

    @Nullable
    private static Bitmap renderCard(@NonNull FxStack one, @NonNull FxInstance card,
                                     @NonNull FxEffectDef def, int sizePx) {
        String key = keyOf(card, sizePx);
        Bitmap hit = CACHE.get(key);
        if (hit != null) return hit;
        Bitmap out;
        synchronized (LOCK) {
            out = drawOnce(one, def.family == FxEffectDef.Family.GENERATE, sizePx);
        }
        if (out != null) CACHE.put(key, out);
        return out;
    }

    private static String keyOf(@NonNull FxInstance card, int sizePx) {
        StringBuilder sb = new StringBuilder(card.effectId).append('|').append(sizePx);
        FxEffectDef def = card.def();
        if (def != null) {
            sb.append('|').append(def.hashCode());
            for (com.fadcam.ui.faditor.fx.FxParam p : def.params) {
                sb.append('|').append(java.util.Arrays.hashCode(card.get(p)));
            }
        }
        return sb.toString();
    }

    // ── the sample frame ────────────────────────────────────────────────────

    /**
     * The one fixed frame colour, blur and distort effects are shown over: sky, sun, hills,
     * a dark figure and hard edges, so a blur, a grade and a warp each have something to bite.
     * Generators are shown over flat neutral grey instead.
     */
    @NonNull
    static Bitmap sampleFrame(int w, int h, boolean neutralGrey) {
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        if (neutralGrey) {
            c.drawColor(0xFF6B6B73);
            return b;
        }
        p.setShader(new LinearGradient(0, 0, 0, h * 0.7f, 0xFF5BA3E8, 0xFFF6C58B,
                Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        p.setShader(null);
        p.setColor(0xFFFFF2B0);
        c.drawCircle(w * 0.74f, h * 0.30f, h * 0.12f, p);
        p.setColor(0xFF3E7D4F);
        android.graphics.Path hills = new android.graphics.Path();
        hills.moveTo(0, h * 0.72f);
        hills.quadTo(w * 0.28f, h * 0.52f, w * 0.55f, h * 0.70f);
        hills.quadTo(w * 0.80f, h * 0.60f, w, h * 0.72f);
        hills.lineTo(w, h);
        hills.lineTo(0, h);
        hills.close();
        c.drawPath(hills, p);
        p.setColor(0xFF1B1B22);
        c.drawRect(w * 0.20f, h * 0.55f, w * 0.30f, h * 0.88f, p);
        c.drawCircle(w * 0.25f, h * 0.48f, h * 0.075f, p);
        p.setColor(0xFFF4F4F5);
        c.drawRect(w * 0.60f, h * 0.76f, w * 0.90f, h * 0.80f, p);
        return b;
    }

    // ── GL ──────────────────────────────────────────────────────────────────

    @Nullable
    private static Bitmap drawOnce(@NonNull FxStack stack, boolean generator, int size) {
        EGLDisplay dpy = EGL14.EGL_NO_DISPLAY;
        EGLContext ctx = EGL14.EGL_NO_CONTEXT;
        EGLSurface surf = EGL14.EGL_NO_SURFACE;
        int[] tex = new int[3];
        int[] fbo = new int[3];
        int[] programs = new int[16];
        int nProg = 0;
        boolean glUp = false;
        try {
            dpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            int[] ver = new int[2];
            if (!EGL14.eglInitialize(dpy, ver, 0, ver, 1)) return null;
            int[] cfgAttr = {
                    EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                    EGL14.EGL_ALPHA_SIZE, 8,
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT, EGL14.EGL_NONE};
            EGLConfig[] cfg = new EGLConfig[1];
            int[] n = new int[1];
            if (!EGL14.eglChooseConfig(dpy, cfgAttr, 0, cfg, 0, 1, n, 0) || n[0] < 1) return null;
            ctx = EGL14.eglCreateContext(dpy, cfg[0], EGL14.EGL_NO_CONTEXT,
                    new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE}, 0);
            surf = EGL14.eglCreatePbufferSurface(dpy, cfg[0],
                    new int[]{EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE}, 0);
            if (ctx == EGL14.EGL_NO_CONTEXT || surf == EGL14.EGL_NO_SURFACE) return null;
            if (!EGL14.eglMakeCurrent(dpy, surf, surf, ctx)) return null;
            glUp = true;

            int w = size, h = size;
            GLES20.glGenTextures(3, tex, 0);
            GLES20.glGenFramebuffers(3, fbo, 0);
            // tex[0] holds the sample frame (flipped so the final readback is upright);
            // tex[1], tex[2] are the ping-pong targets.
            Bitmap frame = sampleFrame(w, h, generator);
            Matrix flip = new Matrix();
            flip.setScale(1f, -1f, 0f, h / 2f);
            Bitmap flipped = Bitmap.createBitmap(frame, 0, 0, w, h, flip, false);
            for (int i = 0; i < 3; i++) {
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[i]);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
                if (i == 0) {
                    GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, flipped, 0);
                } else {
                    GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0,
                            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
                }
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[i]);
                GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                        GLES20.GL_TEXTURE_2D, tex[i], 0);
            }
            if (flipped != frame) flipped.recycle();
            frame.recycle();

            FxCompiler.Plan plan = FxCompiler.plan(stack);
            int src = 0;            // slot holding the pass input
            int result = 0;
            for (int pi = 0; pi < plan.passes.size(); pi++) {
                FxCompiler.Pass pa = plan.passes.get(pi);
                if (pa.feedback) return null;          // needs last frame: no still
                boolean lastPass = pi == plan.passes.size() - 1;
                int renders = Math.max(1, pa.repeats);
                for (int r = 0; r < renders; r++) {
                    boolean lastRender = lastPass && r == renders - 1;
                    int prog;
                    try {
                        prog = build(FxGlSource.VERTEX_SHADER,
                                FxGlSource.fragment(pa, FxGlSource.KERNEL_HALF, lastRender, 1));
                    } catch (RuntimeException refused) {
                        // The driver will not take this effect: remember it, so the picker
                        // stops offering it and nothing ever adds a card that cannot run here.
                        for (FxInstance c : pa.cards) FxDeviceGuard.markFailed(c.effectId);
                        return null;
                    }
                    if (nProg < programs.length) programs[nProg++] = prog;
                    int dst = src == 1 ? 2 : 1;
                    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[dst]);
                    GLES20.glViewport(0, 0, w, h);
                    GLES20.glUseProgram(prog);
                    int loc = GLES20.glGetAttribLocation(prog, "aFramePosition");
                    GLES20.glEnableVertexAttribArray(loc);
                    GLES20.glVertexAttribPointer(loc, 4, GLES20.GL_FLOAT, false, 16, QUAD);
                    sampler(prog, "uTexSampler", tex[src], 0);
                    sampler(prog, "uBaseSampler", tex[0], 1);
                    f2(prog, "uTexel", 1f / w, 1f / h);
                    f1(prog, "uAspect", 1f);
                    f1(prog, "uTime", SWATCH_TIME_SEC);
                    boolean second = renders > 1 && r == 1;
                    f2(prog, "uDir", second ? 0f : 1f, second ? 1f : 0f);
                    List<FxUniforms.Value> vals = FxUniforms.forPass(pa);
                    for (FxUniforms.Value v : vals) fn(prog, v.name, v.data);
                    f1(prog, "uLayerOpacity", 1f);
                    f1(prog, "uMaskCount", 0f);
                    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
                    src = dst;
                    result = dst;
                }
            }
            if (plan.passes.isEmpty()) return null;

            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[result]);
            ByteBuffer px = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder());
            GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, px);
            if (GLES20.glGetError() != GLES20.GL_NO_ERROR) return null;
            Bitmap raw = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            raw.copyPixelsFromBuffer(px);
            // GL rows run bottom-up; flip once so the swatch is upright.
            Matrix up = new Matrix();
            up.setScale(1f, -1f, 0f, h / 2f);
            Bitmap out = Bitmap.createBitmap(raw, 0, 0, w, h, up, false);
            if (out != raw) raw.recycle();
            return out;
        } catch (RuntimeException e) {
            return null;     // the picker shows a plain tile; never an error to the user
        } finally {
            if (glUp) {
                try {
                    for (int i = 0; i < nProg; i++) GLES20.glDeleteProgram(programs[i]);
                    GLES20.glDeleteFramebuffers(3, fbo, 0);
                    GLES20.glDeleteTextures(3, tex, 0);
                } catch (RuntimeException ignored) { }
            }
            if (dpy != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE,
                        EGL14.EGL_NO_CONTEXT);
                if (surf != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(dpy, surf);
                if (ctx != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(dpy, ctx);
                EGL14.eglTerminate(dpy);
            }
        }
    }

    private static int build(@NonNull String vs, @NonNull String fs) {
        int v = shader(GLES20.GL_VERTEX_SHADER, vs);
        int f = shader(GLES20.GL_FRAGMENT_SHADER, fs);
        int p = GLES20.glCreateProgram();
        GLES20.glAttachShader(p, v);
        GLES20.glAttachShader(p, f);
        GLES20.glLinkProgram(p);
        int[] ok = new int[1];
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0);
        GLES20.glDeleteShader(v);
        GLES20.glDeleteShader(f);
        if (ok[0] == 0) {
            GLES20.glDeleteProgram(p);
            throw new IllegalStateException("swatch link failed");
        }
        return p;
    }

    private static int shader(int type, @NonNull String src) {
        int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, src);
        GLES20.glCompileShader(s);
        int[] ok = new int[1];
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) {
            GLES20.glDeleteShader(s);
            throw new IllegalStateException("swatch compile failed");
        }
        return s;
    }

    private static void sampler(int p, String name, int texId, int unit) {
        int l = GLES20.glGetUniformLocation(p, name);
        if (l < 0) return;
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + unit);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId);
        GLES20.glUniform1i(l, unit);
    }

    private static void f1(int p, String name, float v) {
        int l = GLES20.glGetUniformLocation(p, name);
        if (l >= 0) GLES20.glUniform1f(l, v);
    }

    private static void f2(int p, String name, float a, float b) {
        int l = GLES20.glGetUniformLocation(p, name);
        if (l >= 0) GLES20.glUniform2f(l, a, b);
    }

    private static void fn(int p, String name, float[] d) {
        int l = GLES20.glGetUniformLocation(p, name);
        if (l < 0) return;
        switch (d.length) {
            case 1: GLES20.glUniform1f(l, d[0]); break;
            case 2: GLES20.glUniform2f(l, d[0], d[1]); break;
            case 3: GLES20.glUniform3f(l, d[0], d[1], d[2]); break;
            case 4: GLES20.glUniform4f(l, d[0], d[1], d[2], d[3]); break;
            default: GLES20.glUniform1fv(l, d.length, d, 0);
        }
    }
}
