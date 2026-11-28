package com.fadcam.ui.faditor.motion.emu;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Surface;

import androidx.annotation.NonNull;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.fadcam.SharedPreferencesManager;
import com.fadcam.ui.faditor.export.ExportManager;
import com.fadcam.ui.faditor.model.Clip;
import com.fadcam.ui.faditor.model.FaditorProject;
import com.fadcam.ui.faditor.model.Transition;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * STU-7 (owner plan 11-28, step 1): filmed video with Motion Blur in a project WITH a transition.
 *
 * <p>Films a short synthetic clip (a checkered block sliding across still stripes), puts it in a
 * project twice with a 400 ms cross-dissolve between the two, and renders single frames through
 * the real export ({@link ExportManager#renderFrame}, the Inspect route) with Motion Blur on and
 * off. Away from the seam the two must differ (the footage blurs); inside the transition's
 * overlap, both the outgoing tail and the incoming head, they must match (the blend stays
 * sharp). Before 11-28 the project-wide rule kept every frame sharp, so the first check fails on
 * that code. Pictures: files/motion-emu/footage-transition/.</p>
 */
@RunWith(AndroidJUnit4.class)
public final class MotionFootageTransitionTest {

    private static final int W = 640, H = 360, FPS = 30, FRAMES = 60;
    /** Each clip plays [0, 1900) ms of the 2 s film; the dissolve takes 400 ms at the seam. */
    private static final long CLIP_MS = 1900, BLEND_MS = 400;

    @Test public void footageBlursAwayFromTheSeamAndStaysSharpInTheBlend() throws Exception {
        Context app = MotionEmu.ins().getTargetContext();
        File film = new File(app.getCacheDir(), "motion-emu-slide.mp4");
        film(film);
        assertTrue("the synthetic film was written", film.length() > 1000);
        File dir = MotionEmu.dir("footage-transition");

        // Editor times: clip 0 body, its tail under the blend, clip 1's head under the blend,
        // clip 1's body. The overlap is [1500, 2300) ms.
        long[] times = {900, 1700, 2100, 3000};
        boolean[] blend = {false, true, true, false};
        String[] names = {"clip0-body", "blend-outgoing", "blend-incoming", "clip1-body"};
        for (int i = 0; i < times.length; i++) {
            Bitmap on = render(app, project(film, true), times[i]);
            Bitmap off = render(app, project(film, false), times[i]);
            MotionEmu.save(on, new File(dir, names[i] + "-blur.png"));
            MotionEmu.save(off, new File(dir, names[i] + "-sharp.png"));
            double d = differs(on, off);
            MotionEmu.log("footage-transition " + names[i] + " at " + times[i] + " ms: blur differs from sharp on "
                    + Math.round(d * 10000) / 100.0 + "% of pixels");
            if (blend[i]) {
                assertTrue(names[i] + ": a frame inside the transition stays sharp (" + d + ")", d < 0.0005);
            } else {
                assertTrue(names[i] + ": footage away from the seam blurs in a project with a transition (" + d + ")",
                        d > 0.002);
            }
        }
    }

    private static FaditorProject project(File film, boolean blur) {
        FaditorProject p = new FaditorProject("motion-emu-transition");
        for (int i = 0; i < 2; i++) {
            Clip c = new Clip(Uri.fromFile(film), FRAMES * 1000L / FPS);
            c.setOutPointMs(CLIP_MS);
            c.setAudioMuted(true);
            c.setRigidMotionBlurEnabled(true);
            p.getTimeline().addClip(c);
        }
        p.getTimeline().addTransition(new Transition(Transition.Type.CROSS_DISSOLVE, BLEND_MS, 0));
        p.setMotionBlurEnabled(blur);
        return p;
    }

    /** One composed frame through the export's own single-frame route. */
    private static Bitmap render(Context app, FaditorProject p, long ms) throws Exception {
        HandlerThread thread = new HandlerThread("motion-emu-export");
        thread.start();
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Bitmap> frame = new AtomicReference<>();
        AtomicReference<Exception> error = new AtomicReference<>();
        try {
            new Handler(thread.getLooper()).post(() -> new ExportManager(app, SharedPreferencesManager.getInstance(app))
                    .renderFrame(p, ms, new ExportManager.FrameCallback() {
                        @Override public void onFrame(@NonNull Bitmap b) { frame.set(b); done.countDown(); }
                        @Override public void onError(@NonNull Exception e) { error.set(e); done.countDown(); }
                    }));
            assertTrue("the frame at " + ms + " ms renders in time", done.await(400, TimeUnit.SECONDS));
        } finally {
            thread.quitSafely();
        }
        if (error.get() != null) throw error.get();
        assertNotNull("a frame at " + ms + " ms", frame.get());
        return frame.get();
    }

    /** Share of pixels that differ by more than 24 levels in any channel. */
    private static double differs(Bitmap a, Bitmap b) {
        assertTrue("same size", a.getWidth() == b.getWidth() && a.getHeight() == b.getHeight());
        int w = a.getWidth(), h = a.getHeight(), hit = 0;
        int[] pa = new int[w * h], pb = new int[w * h];
        a.getPixels(pa, 0, w, 0, 0, w, h);
        b.getPixels(pb, 0, w, 0, 0, w, h);
        for (int i = 0; i < pa.length; i++) {
            int d = 0;
            for (int s = 0; s < 32; s += 8) d = Math.max(d, Math.abs(((pa[i] >>> s) & 255) - ((pb[i] >>> s) & 255)));
            if (d > 24) hit++;
        }
        return hit / (double) pa.length;
    }

    /** Two seconds of H.264: still grey stripes, a checkered block sliding 8 px a frame. */
    private static void film(File out) throws Exception {
        MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, W, H);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE, 6_000_000);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, FPS);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        MediaCodec encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        Surface input = encoder.createInputSurface();
        encoder.start();
        MediaMuxer muxer = new MediaMuxer(out.getPath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        EGLDisplay display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        int[] version = new int[2];
        EGL14.eglInitialize(display, version, 0, version, 1);
        EGLConfig[] configs = new EGLConfig[1];
        int[] count = new int[1];
        EGL14.eglChooseConfig(display, new int[]{EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, 0x3142 /* EGL_RECORDABLE_ANDROID */, 1,
                EGL14.EGL_NONE}, 0, configs, 0, 1, count, 0);
        EGLContext context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
                new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE}, 0);
        EGLSurface surface = EGL14.eglCreateWindowSurface(display, configs[0], input, new int[]{EGL14.EGL_NONE}, 0);
        assertTrue("encoder surface is current", EGL14.eglMakeCurrent(display, surface, surface, context));
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        int[] track = {-1};
        try {
            for (int i = 0; i < FRAMES; i++) {
                draw(i);
                EGLExt.eglPresentationTimeANDROID(display, surface, i * 1_000_000_000L / FPS);
                EGL14.eglSwapBuffers(display, surface);
                drain(encoder, muxer, info, track, false);
            }
            encoder.signalEndOfInputStream();
            drain(encoder, muxer, info, track, true);
        } finally {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            EGL14.eglDestroySurface(display, surface);
            EGL14.eglDestroyContext(display, context);
            EGL14.eglTerminate(display);
            encoder.stop();
            encoder.release();
            input.release();
            if (track[0] >= 0) muxer.stop();
            muxer.release();
        }
    }

    private static void draw(int frame) {
        GLES20.glViewport(0, 0, W, H);
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST);
        GLES20.glClearColor(0.22f, 0.22f, 0.25f, 1f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        GLES20.glEnable(GLES20.GL_SCISSOR_TEST);
        GLES20.glClearColor(0.42f, 0.42f, 0.45f, 1f);
        for (int x = 0; x < W; x += 40) {
            GLES20.glScissor(x, 0, 10, H);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        }
        int left = 20 + 8 * frame, bottom = 120, cell = 20;
        for (int cy = 0; cy < 6; cy++) {
            for (int cx = 0; cx < 6; cx++) {
                float v = ((cx + cy) & 1) == 0 ? 0.95f : 0.05f;
                GLES20.glClearColor(v, v * 0.9f, v * 0.6f, 1f);
                GLES20.glScissor(left + cx * cell, bottom + cy * cell, cell, cell);
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            }
        }
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST);
    }

    private static void drain(MediaCodec encoder, MediaMuxer muxer, MediaCodec.BufferInfo info, int[] track,
                              boolean endOfStream) {
        int idle = 0;
        while (true) {
            int index = encoder.dequeueOutputBuffer(info, 10_000);
            if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!endOfStream || ++idle > 500) return;
            } else if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                track[0] = muxer.addTrack(encoder.getOutputFormat());
                muxer.start();
            } else if (index >= 0) {
                ByteBuffer data = encoder.getOutputBuffer(index);
                if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0;
                if (info.size > 0 && data != null && track[0] >= 0) {
                    data.position(info.offset);
                    data.limit(info.offset + info.size);
                    muxer.writeSampleData(track[0], data, info);
                }
                encoder.releaseOutputBuffer(index, false);
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return;
            }
        }
    }
}
