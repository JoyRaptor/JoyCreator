package com.fadcam.ui.lobby.arrival.stage;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES30;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Choreographer;
import android.view.TextureView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.ui.lobby.arrival.Show;

/**
 * A see-through GL layer over the whole chat window, alive only while Joybot arrives: he and his
 * effects are drawn here, then the chat's own Joybot takes over on his seat and this layer shuts its
 * GL down, so nothing renders while the chat sits still.
 *
 * <p>The EGL set-up and teardown follow VisLab's {@code VisTextureView} (ES 3, RGBA8888, its own
 * thread, vsync from that thread's Choreographer); the teardown returns only once the thread has
 * finished, as {@code ChromaKeyTextureView} does. Anything that goes wrong here is logged and the
 * chat simply shows Joybot on his seat: never an error on screen.</p>
 */
public final class StageView extends TextureView implements TextureView.SurfaceTextureListener {

    private static final String TAG = "JoybotStage";

    /** Called on the main thread once, when the stage has nothing more to show (or could not show it). */
    public interface Done { void onDone(boolean drew); }

    @Nullable private HandlerThread thread;
    @Nullable private Handler gl;
    @Nullable private SurfaceTexture texture;
    @Nullable private Show pending;
    @Nullable private Done done;
    private final Handler main = new Handler(Looper.getMainLooper());

    // GL thread only
    private EGLDisplay display = EGL14.EGL_NO_DISPLAY;
    private EGLContext context = EGL14.EGL_NO_CONTEXT;
    private EGLSurface surface = EGL14.EGL_NO_SURFACE;
    @Nullable private StageRenderer renderer;
    @Nullable private Show show;
    private boolean running;
    private final int[] wh = new int[1];
    // frame timing, logged when the show ends (owner: extras must not cost frames)
    private long lastFrameNs, renderNsSum, renderNsMax, gapNsMax;
    private int frames, slowGaps;

    public StageView(@NonNull Context c) { this(c, null); }

    public StageView(@NonNull Context c, @Nullable AttributeSet a) {
        super(c, a);
        setOpaque(false);
        setSurfaceTextureListener(this);
    }

    /** Start the arrival as soon as the layer has a surface. */
    public void play(@NonNull Show s, @NonNull Done d) {
        pending = s;
        done = d;
        if (texture != null) start();
    }

    /** End it now (the window turned, the chat closed). {@link Done} still fires. */
    public void stop() {
        pending = null;
        Handler h = gl;
        if (h == null) { report(false); return; }
        h.post(() -> finish(false));
    }

    private void start() {
        final Show s = pending;
        final SurfaceTexture st = texture;
        final Handler h = gl;
        pending = null;
        if (s == null || st == null || h == null) return;
        h.post(() -> {
            if (!setupEgl(st)) { report(false); return; }
            try {
                StageRenderer r = new StageRenderer(getContext(), s);
                r.init();
                renderer = r;
            } catch (RuntimeException e) {
                Log.w(TAG, "stage set-up failed", e);
                teardownEgl();
                report(false);
                return;
            }
            show = s;
            running = true;
            Choreographer.getInstance().postFrameCallback(frame);
        });
    }

    private final Choreographer.FrameCallback frame = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            if (!running) return;
            Show s = show;
            StageRenderer r = renderer;
            if (s == null || r == null || !EGL14.eglMakeCurrent(display, surface, surface, context)) { finish(false); return; }
            long t = Math.max(0L, (frameTimeNanos - s.startNanos) / 1_000_000L);
            if (lastFrameNs != 0L) {
                long gap = frameTimeNanos - lastFrameNs;
                gapNsMax = Math.max(gapNsMax, gap);
                if (gap > 20_000_000L) slowGaps++;
            }
            lastFrameNs = frameTimeNanos;
            long r0 = System.nanoTime();
            boolean more;
            try {
                EGL14.eglQuerySurface(display, surface, EGL14.EGL_WIDTH, wh, 0);
                int w = wh[0];
                EGL14.eglQuerySurface(display, surface, EGL14.EGL_HEIGHT, wh, 0);
                int h = wh[0];
                more = w > 0 && h > 0 && r.render(t, w, h);
            } catch (RuntimeException e) {
                Log.w(TAG, "stage frame failed", e);
                finish(false);
                return;
            }
            EGL14.eglSwapBuffers(display, surface);
            long rn = System.nanoTime() - r0;
            renderNsSum += rn;
            renderNsMax = Math.max(renderNsMax, rn);
            frames++;
            if (more) Choreographer.getInstance().postFrameCallback(this);
            else finish(true);
        }
    };

    /** GL thread: clear the layer, free GL, tell the main thread. */
    private void finish(boolean drew) {
        if (!running && renderer == null) { report(drew); return; }
        if (frames > 0) {
            Log.i(TAG, "stage " + frames + " frames, gaps over 20 ms " + slowGaps + ", worst gap " + gapNsMax / 100_000 / 10f
                    + " ms, draw+swap avg " + renderNsSum / frames / 100_000 / 10f + " ms, worst " + renderNsMax / 100_000 / 10f + " ms");
        }
        running = false;
        Choreographer.getInstance().removeFrameCallback(frame);
        if (surface != EGL14.EGL_NO_SURFACE && EGL14.eglMakeCurrent(display, surface, surface, context)) {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
            GLES30.glClearColor(0, 0, 0, 0);
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT);
            EGL14.eglSwapBuffers(display, surface);
        }
        teardownEgl();
        report(drew);
    }

    private void report(boolean drew) {
        main.post(() -> {
            Done d = done;
            done = null;
            if (d != null) d.onDone(drew);
        });
    }

    // ── surface ─────────────────────────────────────────────────────────────

    @Override
    public void onSurfaceTextureAvailable(@NonNull SurfaceTexture st, int width, int height) {
        texture = st;
        HandlerThread t = new HandlerThread("joybot-stage");
        t.start();
        thread = t;
        gl = new Handler(t.getLooper());
        if (pending != null) start();
    }

    @Override
    public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture st, int width, int height) {}

    @Override
    public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture st) {
        HandlerThread t = thread;
        Handler h = gl;
        thread = null;
        gl = null;
        texture = null;
        if (h != null) {
            final Object lock = new Object();
            final boolean[] over = {false};
            h.post(() -> {
                if (running || renderer != null) finish(false);
                synchronized (lock) { over[0] = true; lock.notifyAll(); }
            });
            synchronized (lock) {
                long until = System.currentTimeMillis() + 300;
                while (!over[0] && System.currentTimeMillis() < until) {
                    try { lock.wait(30); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
                }
            }
            if (t != null) t.quitSafely();
            // Still drawing (a stuck driver): let the thread release the surface itself.
            return over[0];
        }
        if (t != null) t.quitSafely();
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(@NonNull SurfaceTexture st) {}

    // ── EGL (as VisTextureView) ─────────────────────────────────────────────

    private boolean setupEgl(@NonNull SurfaceTexture st) {
        try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            int[] v = new int[2];
            if (!EGL14.eglInitialize(display, v, 0, v, 1)) throw new IllegalStateException("eglInitialize");
            int[] attr = {
                    EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                    EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
                    EGL14.EGL_NONE
            };
            EGLConfig[] cfg = new EGLConfig[1];
            int[] num = new int[1];
            if (!EGL14.eglChooseConfig(display, attr, 0, cfg, 0, 1, num, 0) || num[0] == 0) {
                throw new IllegalStateException("no ES3 RGBA8888 config");
            }
            context = EGL14.eglCreateContext(display, cfg[0], EGL14.EGL_NO_CONTEXT,
                    new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE}, 0);
            if (context == EGL14.EGL_NO_CONTEXT) throw new IllegalStateException("no ES3 context");
            surface = EGL14.eglCreateWindowSurface(display, cfg[0], st, new int[]{EGL14.EGL_NONE}, 0);
            if (!EGL14.eglMakeCurrent(display, surface, surface, context)) throw new IllegalStateException("eglMakeCurrent");
            GLES30.glClearColor(0, 0, 0, 0);
            return true;
        } catch (RuntimeException e) {
            Log.w(TAG, "stage GL set-up failed", e);
            teardownEgl();
            return false;
        }
    }

    private void teardownEgl() {
        if (display != EGL14.EGL_NO_DISPLAY) {
            StageRenderer r = renderer;
            renderer = null;
            if (r != null && context != EGL14.EGL_NO_CONTEXT && EGL14.eglMakeCurrent(display, surface, surface, context)) {
                try { r.release(); } catch (RuntimeException ignored) { /* the context goes anyway */ }
            }
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface);
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context);
            // No eglTerminate: the default display is shared with every other GL user in the app.
        }
        display = EGL14.EGL_NO_DISPLAY;
        context = EGL14.EGL_NO_CONTEXT;
        surface = EGL14.EGL_NO_SURFACE;
        show = null;
    }
}
