package com.fadcam.ui.faditor.motion.emu;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.os.SystemClock;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.fadcam.ui.faditor.compositor.RigidTextureShutterPass;
import com.fadcam.ui.faditor.motion.ItemMotionSampler;
import com.fadcam.ui.faditor.motion.MotionBreaks;
import com.fadcam.ui.faditor.motion.MotionShutter.Controls;
import com.fadcam.ui.faditor.motion.MotionShutter.Look;
import com.fadcam.ui.faditor.motion.MotionShutter.Timing;
import com.fadcam.ui.faditor.motion.RigidShutterPlan;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * What the motion blur programs cost on this device's GPU (STU-7 §8: the Note 9 is the floor).
 * A 600 x 600 picture on a 1080 x 1920 frame, drawn {@link #RUNS} times per case with glFinish,
 * after a warm-up; logs the median milliseconds per draw. Run on the phone with
 * {@code am instrument -e class ...MotionTimingTest}. No pass mark here: the numbers are read
 * against the §8 budget (5 ms P95 added at 720p) by a person.
 */
@RunWith(AndroidJUnit4.class)
public final class MotionTimingTest {

    private static final int W = 1080, H = 1920, SIZE = 600, RUNS = 40;
    private static final long PERIOD = 33_333, NOW = 2_000_000;

    private record Case(String name, Controls controls, ItemMotionSampler sampler) {}

    private static ItemMotionSampler slide(double pxPerFrame) {
        return t -> new ItemMotionSampler.Affine(SIZE, 0, 0, SIZE,
                240 + pxPerFrame * (t - NOW) / (double) PERIOD, 660);
    }

    private static ItemMotionSampler spin(double degPerFrame) {
        return t -> {
            double a = Math.toRadians(degPerFrame) * (t - NOW) / (double) PERIOD;
            double c = Math.cos(a), s = Math.sin(a);
            return new ItemMotionSampler.Affine(SIZE * c, -SIZE * s, SIZE * s, SIZE * c,
                    540 - 300 * c + 300 * s, 960 - 300 * s - 300 * c);
        };
    }

    @Test public void timings() throws Exception {
        EGLDisplay display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        int[] version = new int[2];
        EGL14.eglInitialize(display, version, 0, version, 1);
        EGLConfig[] configs = new EGLConfig[1];
        int[] n = new int[1];
        EGL14.eglChooseConfig(display, new int[]{EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT, EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_NONE}, 0, configs, 0, 1, n, 0);
        EGLContext context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
                new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE}, 0);
        EGLSurface surface = EGL14.eglCreatePbufferSurface(display, configs[0],
                new int[]{EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE}, 0);
        assertTrue(EGL14.eglMakeCurrent(display, surface, surface, context));
        try {
            Bitmap art = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888);
            art.eraseColor(Color.WHITE);
            int source = texture(art);
            Bitmap back = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
            back.eraseColor(Color.DKGRAY);
            int backdrop = texture(back);
            int target = texture(Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888));
            int[] f = new int[1];
            GLES20.glGenFramebuffers(1, f, 0);
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, f[0]);
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                    GLES20.GL_TEXTURE_2D, target, 0);
            GLES20.glViewport(0, 0, W, H);
            RigidTextureShutterPass pass = new RigidTextureShutterPass(RigidTextureShutterPass.Source.PICTURE);
            Case[] cases = {
                    new Case("sharp (no blur)", null, slide(4)),
                    new Case("camera 6-tap: Natural, slow slide", Controls.NATURAL, slide(4)),
                    new Case("smooth: Natural, fast slide", Controls.NATURAL, slide(80)),
                    new Case("smooth: Heavy, fast spin", new Controls(270, Timing.CENTERED, 0, 100), spin(40)),
                    new Case("trail: Streak 8 fr", new Controls(8 * 360, Timing.TRAILING, 0, 100, 60, Look.SMEAR, 4), slide(40)),
                    new Case("trail: 32 fr", new Controls(32 * 360, Timing.TRAILING, 0, 100, 0, Look.SMEAR, 4), slide(20)),
                    new Case("trail: Copies 7", new Controls(8 * 360, Timing.TRAILING, 0, 100, 0, Look.COPIES, 7), slide(40)),
                    new Case("trail: Both 5", new Controls(8 * 360, Timing.TRAILING, 0, 100, 60, Look.BOTH, 5), slide(40)),
                    new Case("PREVIEW Heavy, fast spin", new Controls(270, Timing.CENTERED, 0, 100), spin(40)),
                    new Case("PREVIEW Streak 8 fr", new Controls(8 * 360, Timing.TRAILING, 0, 100, 60, Look.SMEAR, 4), slide(40)),
                    new Case("PREVIEW 32 fr", new Controls(32 * 360, Timing.TRAILING, 0, 100, 0, Look.SMEAR, 4), slide(20)),
                    new Case("PREVIEW Both 5", new Controls(8 * 360, Timing.TRAILING, 0, 100, 60, Look.BOTH, 5), slide(40)),
                    new Case("PREVIEW Both 7, Mix 80", new Controls(8 * 360, Timing.TRAILING, 0, 80, 60, Look.BOTH, 7), slide(40)),
            };
            MotionEmu.log("timing on " + android.os.Build.MODEL + " / " + GLES20.glGetString(GLES20.GL_RENDERER)
                    + ", " + W + "x" + H + " frame, " + SIZE + " px picture, median of " + RUNS);
            for (Case c : cases) {
                RigidShutterPlan plan = c.controls() == null
                        ? RigidShutterPlan.sharp(c.sampler(), NOW, W, H)
                        : c.name().startsWith("PREVIEW")
                        ? RigidTextureShutterPass.planPreview(c.controls(), c.sampler(), NOW, PERIOD, MotionBreaks.none(), W, H)
                        : RigidTextureShutterPass.plan(c.controls(), c.sampler(), NOW, PERIOD, MotionBreaks.none(), W, H);
                assertNotNull(c.name(), plan);
                if (c.name().startsWith("PREVIEW") && plan.trail() != null) {
                    // the preview stays in one small program: never the export's 48 or 64 taps
                    assertTrue(c.name() + " preview program " + plan.trail().program(),
                            plan.trail().program() <= RigidTextureShutterPass.PREVIEW_TAPS);
                }
                for (int i = 0; i < 5; i++) pass.draw(source, W, H, plan, backdrop, 1f);   // compile + warm
                GLES20.glFinish();
                long[] ns = new long[RUNS];
                for (int i = 0; i < RUNS; i++) {
                    long t0 = SystemClock.elapsedRealtimeNanos();
                    pass.draw(source, W, H, plan, backdrop, 1f);
                    GLES20.glFinish();
                    ns[i] = SystemClock.elapsedRealtimeNanos() - t0;
                }
                java.util.Arrays.sort(ns);
                MotionEmu.log(String.format(java.util.Locale.ROOT, "timing %-36s median %6.2f ms  p90 %6.2f ms  (%s)",
                        c.name(), ns[RUNS / 2] / 1e6, ns[RUNS * 9 / 10] / 1e6,
                        plan.trail() == null ? "camera program"
                                : plan.trail().program() + "-tap program, " + plan.taps().size() + " taps"));
            }
            pass.release();
        } finally {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            EGL14.eglDestroySurface(display, surface);
            EGL14.eglDestroyContext(display, context);
            EGL14.eglTerminate(display);
        }
    }

    private static int texture(Bitmap b) {
        int[] t = new int[1];
        GLES20.glGenTextures(1, t, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t[0]);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, b, 0);
        return t[0];
    }
}
