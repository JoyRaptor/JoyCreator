package com.fadcam.ui.faditor.gltransitions;

import com.fadcam.ui.faditor.Studio;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
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
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.FLog;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Bakes each GL transition shader into a horizontal sprite strip so the tiny drawer cards can DEMO the
 * real effect (frame-cycled ImageView-style animation) instead of a category-proxy Canvas animation.
 *
 * <p>Rendering happens once, off the main thread, in a headless EGL pbuffer context that reuses the
 * exact preview shader wrapping ({@link GlTransitionShaderLoader#loadWrappedPreviewShader}) so the card
 * matches what the runtime produces. Strips are cached on disk (version-gated) so baking is a one-time
 * cost. Any single shader that fails to compile/link is skipped silently — the card keeps showing its
 * existing proxy animation, never crashing and never blocking the drawer.</p>
 *
 * <p><b>DEVICE STATUS (2026-07-10, SM-N960U / Adreno): WORKING.</b> 36+ of ~37 shaders bake and the
 * cards demo the real effects (the earlier "feature inert" read was an artifact of testing exactly
 * one shader — powerKaleido — which fails to compile at mediump on this driver; v2 retries such
 * failures at highp). Two hard-won driver rules for this file: (1) do NOT reintroduce
 * {@code GLES20.glGetShaderInfoLog(int)} / {@code glGetProgramInfoLog(int)} — on this driver their
 * bytes are invalid Modified UTF-8 and the native {@code NewStringUTF} ABORTS the whole process
 * under CheckJNI, uncatchable by any Java try/catch (that was the original P0 crash); (2) textures
 * must be uploaded GL-native (see {@link #flipVertically}) or asymmetric-UV shaders bake
 * upside-down while symmetric ones look fine.</p>
 */
public final class GlTransitionCardBaker {

    private static final String TAG = "GlTransitionCardBaker";

    /** Bump when the bake recipe (frame count, size, sample images, shader wrapping) changes. */
    private static final int VERSION = 2; // v2: upload-flip textures + highp retry
    /** Number of progress steps rendered per strip (progress 0..1 inclusive). */
    public static final int FRAME_COUNT = 14;
    private static final int FRAME_W = 160;
    private static final int FRAME_H = 96;
    private static final String CACHE_DIR = "gl_transition_cards";

    /** In-memory strip cache (id -> horizontal sprite strip, FRAME_COUNT frames of FRAME_W x FRAME_H). */
    private static final Map<String, Bitmap> STRIPS = new ConcurrentHashMap<>();
    /** Ids currently queued/baking or known-failed, so we never enqueue the same shader twice. */
    private static final Set<String> HANDLED = ConcurrentHashMap.newKeySet();

    private static final ExecutorService BAKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "gl-card-baker");
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    /** Outstanding jobs; EGL is only torn down once this hits 0 so a full-drawer bake reuses one context. */
    private static final java.util.concurrent.atomic.AtomicInteger PENDING =
            new java.util.concurrent.atomic.AtomicInteger();

    // Worker-thread-only EGL state (created lazily, torn down when the queue drains).
    private static EGLDisplay eglDisplay = EGL14.EGL_NO_DISPLAY;
    private static EGLContext eglContext = EGL14.EGL_NO_CONTEXT;
    private static EGLSurface eglSurface = EGL14.EGL_NO_SURFACE;
    private static Bitmap sampleFrom, sampleTo; // worker-thread-only source textures

    public interface Listener {
        /** Called on the MAIN thread when a strip becomes available for {@code id}. */
        void onStripReady(@NonNull String id, @NonNull Bitmap strip);
    }

    private GlTransitionCardBaker() {}

    /** Synchronously returns an already-baked in-memory strip, or {@code null} if not ready yet. */
    @Nullable
    public static Bitmap peek(@NonNull String id) {
        Bitmap b = STRIPS.get(id);
        return (b != null && !b.isRecycled()) ? b : null;
    }

    /**
     * Ensure a strip for {@code id} exists (disk-loaded or freshly baked), delivering it to
     * {@code listener} on the main thread when ready. No-ops if already in memory (the caller should
     * {@link #peek} first) or already handled/failed. Never throws.
     */
    public static void request(@NonNull Context context, @NonNull String id, @Nullable Listener listener) {
        if (id.isEmpty()) return;
        if (peek(id) != null) {
            if (listener != null) listener.onStripReady(id, STRIPS.get(id));
            return;
        }
        if (!HANDLED.add(id)) return; // already queued/baking/failed
        final Context app = context.getApplicationContext();
        PENDING.incrementAndGet();
        BAKER.execute(() -> {
            Bitmap strip = null;
            try {
                strip = loadFromDisk(app, id);
                if (strip == null) {
                    strip = bake(app, id);
                    if (strip != null) saveToDisk(app, id, strip);
                }
            } catch (Throwable t) {
                FLog.w(TAG, "bake failed for " + id + ": " + t.getMessage());
            } finally {
                // Tear down the GPU context only once the whole queue drains, so batch bakes share it.
                if (PENDING.decrementAndGet() == 0) teardownEgl();
            }
            if (strip != null) {
                FLog.d(TAG, "strip ready for " + id);
                STRIPS.put(id, strip);
                final Bitmap ready = strip;
                if (listener != null) MAIN.post(() -> listener.onStripReady(id, ready));
            } else {
                // Leave in HANDLED so we don't retry a broken shader every frame; card keeps its proxy.
                FLog.d(TAG, "no strip for " + id + " — card keeps proxy");
            }
        });
    }

    // ---- disk cache -----------------------------------------------------------------------------

    @NonNull
    private static File cacheFile(@NonNull Context context, @NonNull String id) {
        File dir = new File(context.getCacheDir(), CACHE_DIR + "/v" + VERSION);
        if (!dir.exists()) dir.mkdirs();
        String safe = id.replaceAll("[^A-Za-z0-9_.-]", "_");
        return new File(dir, safe + "_" + FRAME_COUNT + "x" + FRAME_W + "x" + FRAME_H + ".png");
    }

    @Nullable
    private static Bitmap loadFromDisk(@NonNull Context context, @NonNull String id) {
        File f = cacheFile(context, id);
        if (!f.exists() || f.length() == 0) return null;
        try {
            Bitmap b = BitmapFactory.decodeFile(f.getAbsolutePath());
            if (b != null && b.getWidth() == FRAME_W * FRAME_COUNT && b.getHeight() == FRAME_H) return b;
        } catch (Throwable ignored) { }
        return null;
    }

    private static void saveToDisk(@NonNull Context context, @NonNull String id, @NonNull Bitmap strip) {
        File f = cacheFile(context, id);
        try (FileOutputStream out = new FileOutputStream(f)) {
            strip.compress(Bitmap.CompressFormat.PNG, 100, out);
        } catch (Throwable t) {
            FLog.w(TAG, "cache write failed for " + id + ": " + t.getMessage());
            if (f.exists()) //noinspection ResultOfMethodCallIgnored
                f.delete();
        }
    }

    // ---- GL baking (worker thread only) ---------------------------------------------------------

    @Nullable
    private static Bitmap bake(@NonNull Context context, @NonNull String id) {
        if (!ensureEgl()) return null;
        ensureSamples(context);

        String fragment;
        try {
            fragment = GlTransitionShaderLoader.loadWrappedPreviewShader(context, id);
        } catch (Throwable t) {
            return null;
        }

        int program = buildProgram(fragment);
        if (program <= 0) {
            // Precision retry: a few shaders (e.g. powerKaleido's heavy trig) fail to compile at
            // mediump on Adreno (fp16). ES2 guarantees fragment highp only optionally, but this
            // GPU family supports it — one retry costs nothing and rescues those shaders.
            String highp = fragment.replaceFirst(
                    "precision mediump float;", "precision highp float;");
            if (!highp.equals(fragment)) {
                program = buildProgram(highp);
                if (program > 0) FLog.d(TAG, "compiled at highp after mediump failure: " + id);
            }
            if (program <= 0) return null;
        }

        int fromTex = uploadTexture(sampleFrom);
        int toTex = uploadTexture(sampleTo);
        Bitmap strip = null;
        try {
            strip = Bitmap.createBitmap(FRAME_W * FRAME_COUNT, FRAME_H, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(strip);
            Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
            ByteBuffer pixels = ByteBuffer.allocateDirect(FRAME_W * FRAME_H * 4).order(ByteOrder.nativeOrder());
            Bitmap frame = Bitmap.createBitmap(FRAME_W, FRAME_H, Bitmap.Config.ARGB_8888);

            GLES20.glViewport(0, 0, FRAME_W, FRAME_H);
            for (int i = 0; i < FRAME_COUNT; i++) {
                float progress = FRAME_COUNT == 1 ? 0f : i / (float) (FRAME_COUNT - 1);
                drawFrame(program, id, fromTex, toTex, progress);
                pixels.rewind();
                GLES20.glReadPixels(0, 0, FRAME_W, FRAME_H, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels);
                pixels.rewind();
                frame.copyPixelsFromBuffer(pixels);
                // GL origin is bottom-left → flip vertically while blitting into the strip slot.
                Matrix m = new Matrix();
                m.setScale(1f, -1f);
                m.postTranslate(i * FRAME_W, FRAME_H);
                canvas.drawBitmap(frame, m, paint);
            }
            frame.recycle();
        } catch (Throwable t) {
            FLog.w(TAG, "render failed for " + id + ": " + t.getMessage());
            if (strip != null) { strip.recycle(); strip = null; }
        } finally {
            GLES20.glDeleteTextures(2, new int[]{fromTex, toTex}, 0);
            GLES20.glDeleteProgram(program);
        }
        return strip;
    }

    private static void drawFrame(int program, @NonNull String id, int fromTex, int toTex, float progress) {
        GLES20.glClearColor(0f, 0f, 0f, 1f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        GLES20.glUseProgram(program);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fromTex);
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uFromTex"), 0);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, toTex);
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uToTex"), 1);

        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "progress"), progress);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "ratio"), FRAME_W / (float) FRAME_H);
        applyParamUniforms(program, id);

        int position = GLES20.glGetAttribLocation(program, "aFramePosition");
        GLES20.glEnableVertexAttribArray(position);
        GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 8,
                directBuffer(new float[]{-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f}));
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glDisableVertexAttribArray(position);
    }

    /**
     * Feed each shader's parameters their catalog (or external) default values. The live preview view
     * leaves these at 0; setting them here makes strength/blur/etc-driven effects read correctly.
     */
    private static void applyParamUniforms(int program, @NonNull String id) {
        GLTransitionCatalog.Entry entry = GLTransitionCatalog.find(id);
        if (entry != null) {
            if ("GridFlip".equals(id)) {
                int loc = GLES20.glGetUniformLocation(program, "bgcolor");
                if (loc >= 0) GLES20.glUniform4f(loc, 0f, 0f, 0f, 1f);
            }
            for (GLTransitionCatalog.Param p : entry.params) {
                int loc = GLES20.glGetUniformLocation(program, p.name);
                if (loc >= 0) GLES20.glUniform1f(loc, p.defaultValue);
            }
        } else {
            for (Map.Entry<String, Float> e : GlTransitionShaderLoader.getExternalParams(id).entrySet()) {
                int loc = GLES20.glGetUniformLocation(program, e.getKey());
                if (loc >= 0) GLES20.glUniform1f(loc, e.getValue());
            }
        }
    }

    private static int uploadTexture(@NonNull Bitmap bitmap) {
        int[] tex = new int[1];
        GLES20.glGenTextures(1, tex, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[0]);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, bitmap.getWidth(), bitmap.getHeight(),
                0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, bitmapBuffer(bitmap));
        return tex[0];
    }

    private static int buildProgram(@NonNull String fragment) {
        int v = compile(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER);
        int f = compile(GLES20.GL_FRAGMENT_SHADER, fragment);
        if (v <= 0 || f <= 0) {
            if (v > 0) GLES20.glDeleteShader(v);
            if (f > 0) GLES20.glDeleteShader(f);
            return 0;
        }
        int p = GLES20.glCreateProgram();
        GLES20.glAttachShader(p, v);
        GLES20.glAttachShader(p, f);
        GLES20.glLinkProgram(p);
        GLES20.glDeleteShader(v);
        GLES20.glDeleteShader(f);
        int[] status = new int[1];
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, status, 0);
        if (status[0] != GLES20.GL_TRUE) {
            // Same CheckJNI hazard as compile() — never call glGetProgramInfoLog(int) here.
            FLog.w(TAG, "program link failed");
            GLES20.glDeleteProgram(p);
            return 0;
        }
        return p;
    }

    private static int compile(int type, @NonNull String source) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);
        int[] status = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0);
        if (status[0] != GLES20.GL_TRUE) {
            // NB: do NOT call GLES20.glGetShaderInfoLog(int) — on Adreno/Samsung the driver's
            // info-log bytes are not valid Modified UTF-8, and the native NewStringUTF inside
            // that call ABORTS the whole process under CheckJNI (uncatchable by Java). Log our
            // own source instead (safe — it's a Java String we already hold).
            FLog.w(TAG, "shader compile failed (type=" + type + ", "
                    + source.length() + " chars): " + firstLine(source));
            GLES20.glDeleteShader(shader);
            return 0;
        }
        return shader;
    }

    /** First non-blank line of a shader source, for diagnostics without touching the GL info log. */
    @NonNull
    private static String firstLine(@NonNull String src) {
        for (String line : src.split("\n", 6)) {
            String t = line.trim();
            if (!t.isEmpty()) return t;
        }
        return "(empty)";
    }

    private static final String VERTEX_SHADER =
            "#version 100\n"
            + "attribute vec2 aFramePosition;\n"
            + "varying vec2 vTexSamplingCoord;\n"
            + "void main() {\n"
            + "  vTexSamplingCoord = aFramePosition * 0.5 + 0.5;\n"
            + "  gl_Position = vec4(aFramePosition, 0.0, 1.0);\n"
            + "}\n";

    // ---- EGL lifecycle (worker thread only) -----------------------------------------------------

    private static boolean ensureEgl() {
        if (eglContext != EGL14.EGL_NO_CONTEXT) return true;
        try {
            eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            if (eglDisplay == EGL14.EGL_NO_DISPLAY) return false;
            int[] ver = new int[2];
            if (!EGL14.eglInitialize(eglDisplay, ver, 0, ver, 1)) return false;
            int[] cfgAttr = {
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                    EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
                    EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                    EGL14.EGL_NONE
            };
            EGLConfig[] cfgs = new EGLConfig[1];
            int[] num = new int[1];
            if (!EGL14.eglChooseConfig(eglDisplay, cfgAttr, 0, cfgs, 0, 1, num, 0) || num[0] <= 0) {
                return false;
            }
            int[] ctxAttr = {EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE};
            eglContext = EGL14.eglCreateContext(eglDisplay, cfgs[0], EGL14.EGL_NO_CONTEXT, ctxAttr, 0);
            if (eglContext == EGL14.EGL_NO_CONTEXT) return false;
            int[] surfAttr = {EGL14.EGL_WIDTH, FRAME_W, EGL14.EGL_HEIGHT, FRAME_H, EGL14.EGL_NONE};
            eglSurface = EGL14.eglCreatePbufferSurface(eglDisplay, cfgs[0], surfAttr, 0);
            if (eglSurface == EGL14.EGL_NO_SURFACE) return false;
            return EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext);
        } catch (Throwable t) {
            FLog.w(TAG, "EGL init failed: " + t.getMessage());
            teardownEgl();
            return false;
        }
    }

    private static void teardownEgl() {
        try {
            if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE,
                        EGL14.EGL_NO_CONTEXT);
                if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface);
                if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext);
                EGL14.eglTerminate(eglDisplay);
            }
        } catch (Throwable ignored) {
        } finally {
            eglDisplay = EGL14.EGL_NO_DISPLAY;
            eglContext = EGL14.EGL_NO_CONTEXT;
            eglSurface = EGL14.EGL_NO_SURFACE;
            if (sampleFrom != null && !sampleFrom.isRecycled()) sampleFrom.recycle();
            if (sampleTo != null && !sampleTo.isRecycled()) sampleTo.recycle();
            sampleFrom = null;
            sampleTo = null;
        }
    }

    // ---- source frames --------------------------------------------------------------------------

    private static void ensureSamples(@NonNull Context context) {
        if (sampleFrom == null || sampleFrom.isRecycled()) {
            sampleFrom = flipVertically(decodeSample(context, "transition_frame_a", true));
        }
        if (sampleTo == null || sampleTo.isRecycled()) {
            sampleTo = flipVertically(decodeSample(context, "transition_frame_b", false));
        }
    }

    private static Bitmap decodeSample(@NonNull Context context, @NonNull String resName, boolean first) {
        try {
            int resId = context.getResources().getIdentifier(resName, "drawable", context.getPackageName());
            if (resId != 0) {
                Bitmap src = BitmapFactory.decodeResource(context.getResources(), resId);
                if (src != null) {
                    Bitmap scaled = Bitmap.createScaledBitmap(src, FRAME_W, FRAME_H, true);
                    if (scaled != src && !src.isRecycled()) src.recycle();
                    return scaled;
                }
            }
        } catch (Throwable ignored) { }
        return syntheticSample(first);
    }

    /**
     * Textures must be uploaded in GL-native orientation (row 0 = image BOTTOM): the readback
     * flip alone only fixes shaders whose UV sampling is vertically symmetric — asymmetric
     * shaders (zooms anchored off-center, burns, curls) rendered upside-down without this
     * (found on-device: Zoom Punch/Burn Soft inverted while Defocus looked fine).
     */
    private static Bitmap flipVertically(@NonNull Bitmap src) {
        Matrix m = new Matrix();
        m.setScale(1f, -1f);
        Bitmap flipped = Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), m, true);
        if (flipped != src && !src.isRecycled()) src.recycle();
        return flipped;
    }

    private static Bitmap syntheticSample(boolean first) {
        Bitmap bmp = Bitmap.createBitmap(FRAME_W, FRAME_H, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        int top = first ? Studio.VIDEO : Studio.DANGER;
        int bot = first ? Studio.FILM_EDGE : Studio.DANGER;
        p.setShader(new LinearGradient(0, 0, 0, FRAME_H, top, bot, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, FRAME_W, FRAME_H, p);
        p.setShader(null);
        p.setColor(Studio.INK);
        p.setTextSize(FRAME_H * 0.5f);
        p.setTextAlign(Paint.Align.CENTER);
        c.drawText(first ? "A" : "B", FRAME_W * 0.5f, FRAME_H * 0.68f, p);
        return bmp;
    }

    // ---- buffer helpers -------------------------------------------------------------------------

    private static ByteBuffer bitmapBuffer(@NonNull Bitmap bitmap) {
        int[] px = new int[bitmap.getWidth() * bitmap.getHeight()];
        bitmap.getPixels(px, 0, bitmap.getWidth(), 0, 0, bitmap.getWidth(), bitmap.getHeight());
        ByteBuffer buffer = ByteBuffer.allocateDirect(px.length * 4).order(ByteOrder.nativeOrder());
        for (int pixel : px) {
            buffer.put((byte) ((pixel >> 16) & 0xFF));
            buffer.put((byte) ((pixel >> 8) & 0xFF));
            buffer.put((byte) (pixel & 0xFF));
            buffer.put((byte) ((pixel >> 24) & 0xFF));
        }
        buffer.position(0);
        return buffer;
    }

    private static ByteBuffer directBuffer(@NonNull float[] values) {
        ByteBuffer buffer = ByteBuffer.allocateDirect(values.length * 4).order(ByteOrder.nativeOrder());
        buffer.asFloatBuffer().put(values);
        buffer.position(0);
        return buffer;
    }
}
