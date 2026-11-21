package cc.joycreator.textlab;

import android.content.Context;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.opengl.GLES30;
import android.util.Log;

import androidx.annotation.Nullable;

/** One borrowed RGBA8 title texture, produced and sampled in order on Studio's export thread.
 * A private sharing context keeps all TextLab state out of Media3's program, viewport and FBO.
 * Preview and simultaneous exports keep independent animation state. */
public final class ExportTitleTexture {
    private final EGLDisplay display = EGL14.eglGetCurrentDisplay();
    private final EGLContext consumer = EGL14.eglGetCurrentContext();
    private EGLContext producer = EGL14.EGL_NO_CONTEXT;
    private EGLSurface surface = EGL14.EGL_NO_SURFACE;
    private final StudioTextLab.Item item = new StudioTextLab.Item();
    private final boolean fenceCapable;
    private long consumed;

    private ExportTitleTexture() {
        String version = GLES20.glGetString(GLES20.GL_VERSION);
        fenceCapable = version != null && version.startsWith("OpenGL ES 3");
    }

    /** A driver that cannot share ES3 textures can still use the existing bitmap path. */
    @Nullable
    public static ExportTitleTexture open(Context app) {
        ExportTitleTexture session = new ExportTitleTexture();
        EGLSurface draw = EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW);
        EGLSurface read = EGL14.eglGetCurrentSurface(EGL14.EGL_READ);
        if (session.consumer.equals(EGL14.EGL_NO_CONTEXT)) return null;
        boolean ready = false;
        try {
            session.createContext();
            session.makeProducerCurrent();
            session.item.r = new TextLabRenderer(StudioTextLab.get(app).rendererAssets(), app);
            session.item.r.output = TextLabRenderer.CLEAR;
            ready = true;
        } catch (Exception e) {
            Log.w("TextLab", "Using bitmap export for this title", e);
        } finally {
            try {
                session.restore(draw, read);
            } finally {
                if (!ready) session.destroy();
            }
        }
        return ready ? session : null;
    }

    private void createContext() {
        int[] attributes = {EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_NONE};
        EGLConfig[] configs = new EGLConfig[1];
        int[] count = new int[1];
        if (!EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) || count[0] == 0)
            throw new IllegalStateException("No pbuffer configuration");
        producer = EGL14.eglCreateContext(display, configs[0], consumer,
                new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE}, 0);
        if (producer.equals(EGL14.EGL_NO_CONTEXT)) throw new IllegalStateException("Cannot share context");
        surface = EGL14.eglCreatePbufferSurface(display, configs[0],
                new int[]{EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE}, 0);
        if (surface.equals(EGL14.EGL_NO_SURFACE)) throw new IllegalStateException("Cannot create pbuffer");
    }

    private void makeProducerCurrent() {
        if (!EGL14.eglMakeCurrent(display, surface, surface, producer))
            throw new IllegalStateException("Title context unavailable");
    }

    private void restore(EGLSurface draw, EGLSurface read) {
        if (!EGL14.eglMakeCurrent(display, draw, read, consumer))
            throw new IllegalStateException("Cannot restore export context");
    }

    private long signal() {
        if (fenceCapable) {
            long sync = GLES30.glFenceSync(GLES30.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
            if (sync != 0) {
                GLES20.glFlush();
                return sync;
            }
        }
        // An ES2 consumer cannot wait on GLsync. Complete this GPU operation before switching.
        GLES20.glFinish();
        return 0;
    }

    private static void waitFor(long fence) {
        if (fence == 0) return;
        GLES30.glWaitSync(fence, 0, GLES30.GL_TIMEOUT_IGNORED);
        GLES30.glDeleteSync(fence);
    }

    /** The returned name is valid in the current consumer context until the next draw. */
    public int draw(TitleLook look, String words, long localMs, double em) throws org.json.JSONException {
        if (!consumer.equals(EGL14.eglGetCurrentContext()))
            throw new IllegalStateException("Export context changed while drawing title");
        EGLSurface draw = EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW);
        EGLSurface read = EGL14.eglGetCurrentSurface(EGL14.EGL_READ);
        int texture;
        long produced;
        makeProducerCurrent();
        try {
            waitFor(consumed);
            consumed = 0;
            StudioTextLab.drawMoment(item, look, words, localMs, em);
            texture = item.r.clearTexture();
            produced = signal();
        } finally {
            restore(draw, read);
        }
        waitFor(produced);
        return texture;
    }

    public int width() { return item.r.width(); }
    public int height() { return item.r.height(); }
    public double em() { return item.r.em(); }

    /** Called immediately after the consumer queues its draw, before the producer may reuse the output. */
    public void sampled() { consumed = signal(); }

    public void release() {
        EGLDisplay oldDisplay = EGL14.eglGetCurrentDisplay();
        EGLContext oldContext = EGL14.eglGetCurrentContext();
        EGLSurface draw = EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW);
        EGLSurface read = EGL14.eglGetCurrentSurface(EGL14.EGL_READ);
        try {
            if (EGL14.eglMakeCurrent(display, surface, surface, producer)) {
                try {
                    waitFor(consumed);
                    consumed = 0;
                    item.r.release();
                } finally {
                    if (oldContext.equals(EGL14.EGL_NO_CONTEXT)) {
                        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, oldContext);
                    } else if (!EGL14.eglMakeCurrent(oldDisplay, draw, read, oldContext)) {
                        throw new IllegalStateException("Cannot restore context after releasing title");
                    }
                }
            }
        } finally {
            destroy();
        }
    }

    private void destroy() {
        if (!surface.equals(EGL14.EGL_NO_SURFACE)) EGL14.eglDestroySurface(display, surface);
        if (!producer.equals(EGL14.EGL_NO_CONTEXT)) EGL14.eglDestroyContext(display, producer);
        surface = EGL14.EGL_NO_SURFACE;
        producer = EGL14.EGL_NO_CONTEXT;
    }
}
