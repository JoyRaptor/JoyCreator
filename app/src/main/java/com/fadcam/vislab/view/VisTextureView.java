package com.fadcam.vislab.view;

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
import android.util.AttributeSet;
import android.util.Log;
import android.view.Choreographer;
import android.view.TextureView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.vislab.VisLab;
import com.fadcam.vislab.audio.Features;
import com.fadcam.vislab.gl.VisRenderer;
import com.fadcam.vislab.model.VisScene;

/**
 * A live VisLab picture: its own GL thread, an OpenGL ES 3 context, paced by the display
 * (Choreographer), drawing whatever {@link FeatureSource} hears. See-through by default, so it
 * can float over other apps or sit over video. Keeps itself smooth: when frames run long it
 * lowers the internal resolution, and raises it again when there is room.
 */
public class VisTextureView extends TextureView implements TextureView.SurfaceTextureListener {

    private static final String TAG = "VisTextureView";

    /** Supplies sound each frame, on the GL thread. */
    public interface FeatureSource {
        @NonNull Features next(double dtSec);
    }

    /** Optional: told about frame cost about once a second (for a stats readout). */
    public interface StatsListener {
        void onStats(int fps, float scale, @Nullable String error);
    }

    private HandlerThread thread;
    private Handler handler;
    private volatile FeatureSource source;
    private volatile VisScene scene;
    private volatile boolean paused;
    private volatile boolean autoQuality = true;
    private volatile StatsListener stats;
    private final VisRenderer renderer;

    private EGLDisplay display = EGL14.EGL_NO_DISPLAY;
    private EGLContext context = EGL14.EGL_NO_CONTEXT;
    private EGLSurface surface = EGL14.EGL_NO_SURFACE;
    private int surfW, surfH;
    private boolean running;
    private long lastFrameNs;
    private double clock;
    private int frames, slow, fast;
    private long statsStartNs;
    private final Features silence = new Features();

    public VisTextureView(@NonNull Context context) {
        this(context, null);
    }

    public VisTextureView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        renderer = VisLab.get(context).newRenderer();
        setOpaque(false);
        setSurfaceTextureListener(this);
    }

    @NonNull
    public VisRenderer renderer() {
        return renderer;
    }

    public void setScene(@Nullable VisScene s) {
        scene = s;
        renderer.setScene(s);
    }

    @Nullable
    public VisScene scene() {
        return scene;
    }

    public void setFeatureSource(@Nullable FeatureSource src) {
        source = src;
    }

    public void setPaused(boolean p) {
        paused = p;
    }

    public boolean isPaused() {
        return paused;
    }

    public void setAutoQuality(boolean on) {
        autoQuality = on;
    }

    public void setStatsListener(@Nullable StatsListener l) {
        stats = l;
    }

    // ------------------------------------------------------------------ surface

    @Override
    public void onSurfaceTextureAvailable(@NonNull SurfaceTexture st, int width, int height) {
        surfW = width;
        surfH = height;
        thread = new HandlerThread("vislab-gl");
        thread.start();
        handler = new Handler(thread.getLooper());
        handler.post(() -> {
            if (!setupEgl(st)) return;
            running = true;
            lastFrameNs = 0;
            Choreographer.getInstance().postFrameCallback(frameCallback);
        });
    }

    @Override
    public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture st, int width, int height) {
        surfW = width;
        surfH = height;
    }

    @Override
    public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture st) {
        HandlerThread t = thread;
        Handler h = handler;
        thread = null;
        handler = null;
        if (h != null) {
            final Object done = new Object();
            final boolean[] finished = {false};
            h.post(() -> {
                running = false;
                Choreographer.getInstance().removeFrameCallback(frameCallback);
                teardownEgl();
                synchronized (done) {
                    finished[0] = true;
                    done.notifyAll();
                }
            });
            // Wait briefly so the context dies before the surface does (avoids a driver warning
            // flood on some phones). Never hang the UI thread for long.
            synchronized (done) {
                long until = System.currentTimeMillis() + 500;
                while (!finished[0] && System.currentTimeMillis() < until) {
                    try {
                        done.wait(50);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        if (t != null) t.quitSafely();
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(@NonNull SurfaceTexture st) {
    }

    // ------------------------------------------------------------------ frames

    private final Choreographer.FrameCallback frameCallback = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            if (!running) return;
            Choreographer.getInstance().postFrameCallback(this);
            drawFrame(frameTimeNanos);
        }
    };

    private void drawFrame(long nowNs) {
        double dt = lastFrameNs == 0 ? 1.0 / 60 : (nowNs - lastFrameNs) / 1e9;
        lastFrameNs = nowNs;
        dt = Math.max(0, Math.min(0.1, dt));
        if (!EGL14.eglMakeCurrent(display, surface, surface, context)) return;
        FeatureSource src = source;
        Features f = src != null ? src.next(paused ? 0 : dt) : silence;
        if (!paused) clock += dt;
        try {
            renderer.render(f, clock, paused ? 0 : dt, 0, surfW, surfH);
        } catch (RuntimeException e) {
            Log.w(TAG, "frame failed", e);
        }
        EGL14.eglSwapBuffers(display, surface);
        keepSmooth(dt, nowNs);
    }

    private void keepSmooth(double dt, long nowNs) {
        frames++;
        // Ignore one-off stalls (an app switch, a screenshot): they are not the GPU.
        if (dt > 0.024 && dt < 0.08) slow++;
        else if (dt < 0.0175) fast++;
        if (statsStartNs == 0) statsStartNs = nowNs;
        if (nowNs - statsStartNs < 1_000_000_000L) return;
        if (autoQuality) {
            float s = renderer.renderScale();
            if (slow > frames * 0.3f && s > 0.45f) renderer.setRenderScale(s - 0.1f);
            else if (fast > frames * 0.9f && s < 1f) renderer.setRenderScale(s + 0.05f);
        }
        StatsListener l = stats;
        if (l != null) {
            final int fps = frames;
            final float sc = renderer.renderScale();
            final String err = renderer.lastError();
            post(() -> l.onStats(fps, sc, err));
        }
        frames = slow = fast = 0;
        statsStartNs = nowNs;
    }

    // ------------------------------------------------------------------ EGL

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
            renderer.init();
            GLES30.glClearColor(0, 0, 0, 0);
            return true;
        } catch (RuntimeException e) {
            Log.e(TAG, "VisLab GL setup failed", e);
            teardownEgl();
            return false;
        }
    }

    private void teardownEgl() {
        if (display != EGL14.EGL_NO_DISPLAY) {
            if (context != EGL14.EGL_NO_CONTEXT
                    && EGL14.eglMakeCurrent(display, surface, surface, context)) {
                renderer.release();
            } else {
                renderer.contextLost();
            }
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface);
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context);
            // No eglTerminate: the default display is shared with every other GL user in the app.
        }
        display = EGL14.EGL_NO_DISPLAY;
        context = EGL14.EGL_NO_CONTEXT;
        surface = EGL14.EGL_NO_SURFACE;
    }
}
