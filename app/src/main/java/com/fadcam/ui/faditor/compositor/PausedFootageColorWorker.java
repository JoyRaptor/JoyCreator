package com.fadcam.ui.faditor.compositor;

import android.content.Context;
import android.net.Uri;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES20;

import androidx.media3.common.util.GlProgram;
import androidx.media3.common.util.GlUtil;

import com.fadcam.FLog;
import com.fadcam.ui.faditor.fx.FxGlSource;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Decodes color donors off the draw thread and transfers finished textures to its share group. */
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
final class PausedFootageColorWorker {
    record Stage(int width, int height, int x, int y, int contentWidth, int contentHeight,
                 int sourceWidth, int sourceHeight) {}

    record Key(String sourceKey, Uri uri, long continuityEpoch, long pauseEpoch, long centreUs,
               long previousUs, long nextUs, Stage stage) {}

    record Colors(Key key, int previous, int next) {
        void release() {
            GLES20.glDeleteTextures(2, new int[]{previous, next}, 0);
        }
    }

    private final Context context;
    private final EGLDisplay display;
    private final EGLContext preview;
    private final Runnable onReady;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(work -> {
        Thread thread = new Thread(work, "Studio paused footage color");
        thread.setDaemon(true);
        return thread;
    });

    private Key desired;
    private Key attempted;
    private Colors ready;
    private boolean scheduled;
    private boolean closed;

    PausedFootageColorWorker(Context context, EGLDisplay display, EGLContext preview, Runnable onReady) {
        this.context = context.getApplicationContext();
        this.display = display;
        this.preview = preview;
        this.onReady = onReady;
    }

    /** Called with the preview context current. The caller owns the returned textures. */
    synchronized Colors request(Key key) {
        if (closed) return null;
        if (!Objects.equals(desired, key)) {
            desired = key;
            attempted = null;
            if (ready != null) ready.release();
            ready = null;
        }
        if (ready != null) {
            Colors result = ready;
            ready = null;
            return result;
        }
        if (key != null && !key.equals(attempted) && !scheduled) {
            scheduled = true;
            worker.execute(this::drain);
        }
        return null;
    }

    synchronized void close(boolean contextCurrent) {
        desired = null;
        closed = true;
        if (ready != null && contextCurrent) ready.release();
        ready = null;
        worker.shutdownNow();
    }

    private synchronized boolean cancelled(Key key) {
        // Identity also cancels a request when its values recur after an intervening request.
        return desired != key || closed || Thread.currentThread().isInterrupted();
    }

    private void drain() {
        while (true) {
            Key key;
            synchronized (this) {
                key = desired;
                if (closed || key == null || key.equals(attempted)) {
                    scheduled = false;
                    return;
                }
                attempted = key;
            }
            try {
                prepare(key);
            } catch (Exception failure) {
                if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
                if (!cancelled(key)) {
                    FLog.w("PausedFootageColor", "Exact paused color donors unavailable", failure);
                }
            }
        }
    }

    private void prepare(Key key) throws Exception {
        int[] count = new int[1];
        EGLConfig[] configs = new EGLConfig[1];
        int[] configAttributes = {EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_NONE};
        if (!EGL14.eglChooseConfig(display, configAttributes, 0, configs, 0, 1, count, 0)
                || count[0] == 0) throw new IOException("No color donor EGL config");
        EGLContext shared = EGL14.eglCreateContext(display, configs[0], preview,
                new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE}, 0);
        if (shared.equals(EGL14.EGL_NO_CONTEXT)) throw new IOException("No shared color donor context");
        EGLSurface pbuffer = EGL14.EGL_NO_SURFACE;
        try {
            pbuffer = EGL14.eglCreatePbufferSurface(display, configs[0],
                    new int[]{EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE}, 0);
            if (pbuffer.equals(EGL14.EGL_NO_SURFACE)
                    || !EGL14.eglMakeCurrent(display, pbuffer, pbuffer, shared)) {
                throw new IOException("No color donor EGL surface");
            }
            if (!cancelled(key)) decode(key);
        } finally {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_CONTEXT);
            if (!pbuffer.equals(EGL14.EGL_NO_SURFACE)) EGL14.eglDestroySurface(display, pbuffer);
            EGL14.eglDestroyContext(display, shared);
            EGL14.eglReleaseThread();
        }
    }

    private void decode(Key key) throws Exception {
        Stage stage = key.stage();
        int[] textures = new int[2];
        try {
            GlProgram copy = new GlProgram(FxGlSource.VERTEX_SHADER, FxPreviewTextureView.STAGE_FRAGMENT);
            try {
                copy.setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), 4);
                copy.setFloatUniform("uRotation", 0f);
                ExactFootageColorReader.read(context, key.uri(), stage.sourceWidth(), stage.sourceHeight(),
                        key.previousUs(), key.nextUs(), () -> cancelled(key), (ptsUs, oes, transform) -> {
                            int slot = ptsUs == key.previousUs() ? 0 : 1;
                            textures[slot] = GlUtil.createTexture(stage.width(), stage.height(), false);
                            stage(copy, stage, textures[slot], oes, transform);
                        });
            } finally {
                copy.delete();
            }
            if (textures[0] == 0 || (key.nextUs() != Long.MIN_VALUE && textures[1] == 0)) return;
            // Ensure producer commands are complete before a different context samples the textures.
            GLES20.glFinish();
            GlUtil.checkGlError();
            synchronized (this) {
                if (cancelled(key)) return;
                ready = new Colors(key, textures[0], textures[1]);
                textures[0] = textures[1] = 0;
            }
            onReady.run();
        } finally {
            GLES20.glDeleteTextures(2, textures, 0);
        }
    }

    private static void stage(GlProgram copy, Stage stage, int texture, int oes, float[] transform)
            throws GlUtil.GlException {
        int framebuffer = GlUtil.createFboForTexture(texture);
        try {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer);
            GLES20.glClearColor(0f, 0f, 0f, 0f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            GLES20.glViewport(stage.x(), stage.y(), stage.contentWidth(), stage.contentHeight());
            copy.use();
            copy.setFloatsUniform("uTexMatrix", transform);
            copy.setSamplerTexIdUniform("uOesTexture", oes, 0);
            copy.bindAttributesAndUniforms();
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            GlUtil.checkGlError();
        } finally {
            GLES20.glDeleteFramebuffers(1, new int[]{framebuffer}, 0);
        }
    }
}
