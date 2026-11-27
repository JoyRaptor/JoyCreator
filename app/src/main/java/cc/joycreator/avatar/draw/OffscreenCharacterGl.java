package cc.joycreator.avatar.draw;

import android.graphics.Bitmap;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES20;

import cc.joycreator.avatar.solve.BitmapPics;
import cc.joycreator.avatar.solve.SolveOut;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * {@link CharacterGl} drawing into a picture instead of a screen, for the places that paint with a Canvas (Studio's
 * preview, its Canvas export and its GL export's sprite pictures). It keeps its own small GL context and surface, and
 * puts back whatever GL context the calling thread had (Studio's GL export runs on a thread whose context must not be
 * disturbed), so it can be called from any thread, one call at a time.
 *
 * <p>The picture is premultiplied ARGB, top row first, as a Bitmap wants it.</p>
 */
public final class OffscreenCharacterGl {
    private final int w, h;
    private EGLDisplay dpy = EGL14.EGL_NO_DISPLAY;
    private EGLContext ctx = EGL14.EGL_NO_CONTEXT;
    private EGLSurface surf = EGL14.EGL_NO_SURFACE;
    private CharacterGl gl;
    private ByteBuffer px, rows;
    private byte[] line;
    private Bitmap picture;

    /** A w x h drawing surface; throws when this GPU cannot give one (the caller then draws without the GPU). */
    public OffscreenCharacterGl(int w, int h) {
        this.w = Math.max(1, w);
        this.h = Math.max(1, h);
        Saved saved = Saved.now();
        try {
            dpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            int[] ver = new int[2];
            if (!EGL14.eglInitialize(dpy, ver, 0, ver, 1)) throw new IllegalStateException("no EGL display");
            EGLConfig[] cfg = new EGLConfig[1];
            int[] n = new int[1];
            int[] msaa = {EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_SAMPLE_BUFFERS, 1, EGL14.EGL_SAMPLES, 4, EGL14.EGL_NONE};
            int[] plain = {EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT, EGL14.EGL_NONE};
            if (!(EGL14.eglChooseConfig(dpy, msaa, 0, cfg, 0, 1, n, 0) && n[0] > 0)
                    && !(EGL14.eglChooseConfig(dpy, plain, 0, cfg, 0, 1, n, 0) && n[0] > 0)) {
                throw new IllegalStateException("no EGL config");
            }
            ctx = EGL14.eglCreateContext(dpy, cfg[0], EGL14.EGL_NO_CONTEXT,
                new int[] {EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE}, 0);
            if (ctx == null || ctx == EGL14.EGL_NO_CONTEXT) throw new IllegalStateException("no EGL context");
            surf = EGL14.eglCreatePbufferSurface(dpy, cfg[0], new int[] {EGL14.EGL_WIDTH, this.w, EGL14.EGL_HEIGHT, this.h, EGL14.EGL_NONE}, 0);
            if (surf == null || surf == EGL14.EGL_NO_SURFACE) throw new IllegalStateException("no EGL surface");
            if (!EGL14.eglMakeCurrent(dpy, surf, surf, ctx)) throw new IllegalStateException("EGL make current " + EGL14.eglGetError());
            gl = new CharacterGl();
        } catch (RuntimeException e) {
            saved.restore();
            release();
            throw e;
        }
        saved.restore();
    }

    public int width() { return w; }

    public int height() { return h; }

    /**
     * Draws one solved pose at {@code scale} (the character's drawing space to this picture's pixels) and returns the
     * picture. The same Bitmap is reused by the next call: draw it before calling again.
     */
    public Bitmap draw(SolveOut out, BitmapPics pics, float scale) {
        Saved saved = Saved.now();
        try {
            if (!EGL14.eglMakeCurrent(dpy, surf, surf, ctx)) throw new IllegalStateException("EGL make current " + EGL14.eglGetError());
            gl.draw(out, pics, new CharacterGl.View(scale, 0, 0), null, 0, w, h);
            GLES20.glFinish();
            if (px == null) {
                px = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder());
                rows = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder());
                line = new byte[w * 4];
            }
            px.position(0);
            GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, px);
            rows.position(0);
            for (int y = 0; y < h; y++) {          // GL gives the bottom row first
                px.position((h - 1 - y) * w * 4);
                px.get(line);
                rows.put(line);
            }
            rows.position(0);
            if (picture == null || picture.isRecycled()) picture = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            picture.copyPixelsFromBuffer(rows);
            return picture;
        } finally {
            saved.restore();
        }
    }

    /** Pictures changed (textures made from the old ones are dropped). */
    public void forget() {
        if (gl == null) return;
        Saved saved = Saved.now();
        try {
            if (EGL14.eglMakeCurrent(dpy, surf, surf, ctx)) gl.forget();
        } finally {
            saved.restore();
        }
    }

    public void release() {
        Saved saved = Saved.now();
        try {
            if (dpy != EGL14.EGL_NO_DISPLAY && ctx != EGL14.EGL_NO_CONTEXT && surf != EGL14.EGL_NO_SURFACE
                    && gl != null && EGL14.eglMakeCurrent(dpy, surf, surf, ctx)) {
                gl.release();
            }
            gl = null;
            if (dpy != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
                if (surf != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(dpy, surf);
                if (ctx != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(dpy, ctx);
            }
            surf = EGL14.EGL_NO_SURFACE;
            ctx = EGL14.EGL_NO_CONTEXT;
            if (picture != null) picture.recycle();
            picture = null;
        } finally {
            saved.restore();
        }
    }

    /** The calling thread's GL context and surfaces, to be put back after we borrow the thread. */
    private static final class Saved {
        final EGLDisplay d;
        final EGLContext c;
        final EGLSurface draw, read;

        private Saved(EGLDisplay d, EGLContext c, EGLSurface draw, EGLSurface read) {
            this.d = d;
            this.c = c;
            this.draw = draw;
            this.read = read;
        }

        static Saved now() {
            return new Saved(EGL14.eglGetCurrentDisplay(), EGL14.eglGetCurrentContext(),
                EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW), EGL14.eglGetCurrentSurface(EGL14.EGL_READ));
        }

        void restore() {
            if (c != null && c != EGL14.EGL_NO_CONTEXT && d != null && d != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(d, draw, read, c);
            } else {
                EGLDisplay any = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
                EGL14.eglMakeCurrent(any, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            }
        }
    }
}
