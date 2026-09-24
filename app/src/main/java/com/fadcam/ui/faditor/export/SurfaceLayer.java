package com.fadcam.ui.faditor.export;

import android.graphics.Canvas;
import android.graphics.SurfaceTexture;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Surface;

import androidx.annotation.NonNull;

import com.fadcam.ui.faditor.compositor.PipGl;

/**
 * ZERO-COPY picture for the export's GL runs: the CPU draws into a Surface buffer that the GPU
 * samples as an external texture, so there is no texture upload (captions and animated text,
 * 2026-09-24). GL thread only, except the frame-available callback.
 *
 * <p><b>Latch only what was just drawn.</b> A posted buffer reaches the SurfaceTexture
 * asynchronously; latching straight after posting can pick up the PREVIOUS frame, which made a
 * single-frame export at 0:00 come out without its caption (Note 9, 2026-09-24) and would lag a
 * video by a frame. {@link #postAndLatch} waits for the frame-available signal first.</p>
 */
final class SurfaceLayer {

    private static final long LATCH_TIMEOUT_MS = 250L;
    private static Handler callbacks;

    private static synchronized Handler callbacks() {
        if (callbacks == null) {
            HandlerThread t = new HandlerThread("export-surface-frames");
            t.start();
            callbacks = new Handler(t.getLooper());
        }
        return callbacks;
    }

    final int oesTex;
    private final SurfaceTexture st;
    private final Surface surface;
    private final Object lock = new Object();
    private long available = 0L;
    private int w, h;

    SurfaceLayer(int width, int height) {
        oesTex = PipGl.newExternalTexture();
        st = new SurfaceTexture(oesTex);
        w = Math.max(1, width);
        h = Math.max(1, height);
        st.setDefaultBufferSize(w, h);
        st.setOnFrameAvailableListener(t -> {
            synchronized (lock) {
                available++;
                lock.notifyAll();
            }
        }, callbacks());
        surface = new Surface(st);
    }

    void resize(int width, int height) {
        int nw = Math.max(1, width), nh = Math.max(1, height);
        if (nw == w && nh == h) return;
        w = nw;
        h = nh;
        st.setDefaultBufferSize(w, h);
    }

    @NonNull
    Canvas lock() {
        return surface.lockCanvas(null);
    }

    /**
     * Post {@code c} (from {@link #lock}) and latch it onto {@link #oesTex}. Returns false when
     * the frame did not arrive within the timeout (the texture then shows the last one).
     */
    boolean postAndLatch(@NonNull Canvas c) throws InterruptedException {
        long before;
        synchronized (lock) {
            before = available;
        }
        surface.unlockCanvasAndPost(c);
        long deadline = System.currentTimeMillis() + LATCH_TIMEOUT_MS;
        boolean arrived;
        synchronized (lock) {
            long left;
            while (available == before && (left = deadline - System.currentTimeMillis()) > 0) {
                lock.wait(left);
            }
            arrived = available != before;
        }
        st.updateTexImage();
        return arrived;
    }

    void release() {
        try { surface.release(); } catch (RuntimeException ignored) { }
        try { st.release(); } catch (RuntimeException ignored) { }
        try {
            android.opengl.GLES20.glDeleteTextures(1, new int[]{oesTex}, 0);
        } catch (RuntimeException ignored) { }
    }
}
