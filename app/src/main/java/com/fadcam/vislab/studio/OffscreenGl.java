package com.fadcam.vislab.studio;

import android.graphics.Bitmap;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES30;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * VisLab's own GL thread for Studio: an OpenGL ES 3 context with a 1x1 pbuffer, a framebuffer
 * per size, and a read-back into an Android {@link Bitmap}. Studio's preview draws Bitmaps and
 * its export (Media3) asks for one per frame ON MEDIA3'S GL THREAD with Media3's context current,
 * so VisLab must never touch that thread's GL state: it renders here and hands the picture over.
 *
 * <p>All GL work runs on {@link #handler()}; {@link #readInto} must be called there, after drawing
 * into {@link #framebuffer}. The output is premultiplied RGBA (VisLab blends ONE,
 * ONE_MINUS_SRC_ALPHA), which is what an ARGB_8888 Bitmap holds.</p>
 */
final class OffscreenGl {

    private static final String TAG = "VisStudioGl";

    private final HandlerThread thread = new HandlerThread("vislab-studio-gl");
    private final Handler handler;
    private EGLDisplay display = EGL14.EGL_NO_DISPLAY;
    private EGLContext context = EGL14.EGL_NO_CONTEXT;
    private EGLSurface surface = EGL14.EGL_NO_SURFACE;
    private boolean ready;
    private int fbo, rbo, fbW, fbH;
    @Nullable private ByteBuffer pixels;
    @Nullable private byte[] row, row2;

    OffscreenGl() {
        thread.start();
        handler = new Handler(thread.getLooper());
        handler.post(this::setup);
    }

    @NonNull
    Handler handler() {
        return handler;
    }

    /** GL thread: the context is up. */
    boolean ready() {
        return ready;
    }

    private void setup() {
        try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            int[] v = new int[2];
            if (!EGL14.eglInitialize(display, v, 0, v, 1)) throw new IllegalStateException("eglInitialize");
            int[] attr = {
                    EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                    EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
                    EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                    EGL14.EGL_NONE
            };
            EGLConfig[] cfg = new EGLConfig[1];
            int[] num = new int[1];
            if (!EGL14.eglChooseConfig(display, attr, 0, cfg, 0, 1, num, 0) || num[0] == 0) {
                throw new IllegalStateException("no ES3 pbuffer config");
            }
            context = EGL14.eglCreateContext(display, cfg[0], EGL14.EGL_NO_CONTEXT,
                    new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE}, 0);
            if (context == EGL14.EGL_NO_CONTEXT) throw new IllegalStateException("no ES3 context");
            surface = EGL14.eglCreatePbufferSurface(display, cfg[0],
                    new int[]{EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE}, 0);
            if (!EGL14.eglMakeCurrent(display, surface, surface, context)) throw new IllegalStateException("eglMakeCurrent");
            ready = true;
        } catch (RuntimeException e) {
            Log.e(TAG, "VisLab Studio GL setup failed: VisLab looks will not draw in Studio", e);
        }
    }

    /** GL thread: a framebuffer of {@code w} x {@code h} (re-made on a size change). */
    int framebuffer(int w, int h) {
        if (fbo != 0 && w == fbW && h == fbH) return fbo;
        if (fbo != 0) {
            GLES30.glDeleteFramebuffers(1, new int[]{fbo}, 0);
            GLES30.glDeleteRenderbuffers(1, new int[]{rbo}, 0);
        }
        int[] id = new int[1];
        GLES30.glGenRenderbuffers(1, id, 0);
        rbo = id[0];
        GLES30.glBindRenderbuffer(GLES30.GL_RENDERBUFFER, rbo);
        GLES30.glRenderbufferStorage(GLES30.GL_RENDERBUFFER, GLES30.GL_RGBA8, w, h);
        GLES30.glGenFramebuffers(1, id, 0);
        fbo = id[0];
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo);
        GLES30.glFramebufferRenderbuffer(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
                GLES30.GL_RENDERBUFFER, rbo);
        fbW = w;
        fbH = h;
        pixels = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder());
        row = new byte[w * 4];
        row2 = new byte[w * 4];
        return fbo;
    }

    /** GL thread: the framebuffer's picture, the right way up, into {@code out} (w x h ARGB_8888). */
    void readInto(@NonNull Bitmap out) {
        int w = fbW, h = fbH;
        ByteBuffer px = pixels;
        if (px == null || out.getWidth() != w || out.getHeight() != h) return;
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo);
        px.clear();
        GLES30.glReadPixels(0, 0, w, h, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, px);
        // GL rows run bottom-up; a Bitmap's run top-down.
        int stride = w * 4;
        for (int y = 0; y < h / 2; y++) {
            int a = y * stride, b = (h - 1 - y) * stride;
            px.position(a);
            px.get(row, 0, stride);
            px.position(b);
            px.get(row2, 0, stride);
            px.position(a);
            px.put(row2, 0, stride);
            px.position(b);
            px.put(row, 0, stride);
        }
        px.position(0);
        out.copyPixelsFromBuffer(px);
    }
}
