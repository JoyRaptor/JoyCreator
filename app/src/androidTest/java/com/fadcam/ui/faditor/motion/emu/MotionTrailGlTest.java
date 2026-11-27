package com.fadcam.ui.faditor.motion.emu;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
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

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.fadcam.ui.faditor.compositor.RigidTextureShutterPass;
import com.fadcam.ui.faditor.motion.ItemMotionSampler;
import com.fadcam.ui.faditor.motion.MotionBreaks;
import com.fadcam.ui.faditor.motion.MotionShutter;
import com.fadcam.ui.faditor.motion.MotionShutter.Controls;
import com.fadcam.ui.faditor.motion.MotionShutter.Look;
import com.fadcam.ui.faditor.motion.MotionShutter.Timing;
import com.fadcam.ui.faditor.motion.RigidShutterPlan;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * STU-7 §K.2 on a real Android GL stack: the trail program compiles, and a white square moving 40 px a
 * frame draws as the look says. The camera shutter path is drawn too, so a change to it shows here.
 * Each case saves its picture (files/motion-emu/trail/).
 */
@RunWith(AndroidJUnit4.class)
public final class MotionTrailGlTest {

    private static final int W = 512, H = 192, SQUARE = 32;
    private static final long PERIOD = 41_667, NOW = 2_000_000;
    private static final int ROW = 80 + SQUARE / 2;   // the square's middle row

    /** Moves right 40 px a frame; at NOW its left edge is at x = 400. */
    private static final ItemMotionSampler MOVER = t -> {
        double frames = (t - NOW) / (double) PERIOD;
        return new ItemMotionSampler.Affine(SQUARE, 0, 0, SQUARE, 400 + frames * 40, 80);
    };

    private EGLDisplay display;
    private EGLContext context;
    private EGLSurface surface;
    private int source, backdrop, target, fbo;

    @Before public void gl() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        int[] version = new int[2];
        assertTrue("EGL init", EGL14.eglInitialize(display, version, 0, version, 1));
        int[] attrs = {EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_NONE};
        EGLConfig[] configs = new EGLConfig[1];
        int[] n = new int[1];
        assertTrue("EGL config", EGL14.eglChooseConfig(display, attrs, 0, configs, 0, 1, n, 0) && n[0] > 0);
        context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
                new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE}, 0);
        surface = EGL14.eglCreatePbufferSurface(display, configs[0],
                new int[]{EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE}, 0);
        assertTrue("EGL current", EGL14.eglMakeCurrent(display, surface, surface, context));

        Bitmap white = Bitmap.createBitmap(SQUARE, SQUARE, Bitmap.Config.ARGB_8888);
        white.eraseColor(Color.WHITE);
        source = texture(white);
        Bitmap black = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        black.eraseColor(Color.BLACK);
        backdrop = texture(black);
        target = texture(Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888));
        int[] f = new int[1];
        GLES20.glGenFramebuffers(1, f, 0);
        fbo = f[0];
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo);
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D, target, 0);
        assertEquals("framebuffer complete", GLES20.GL_FRAMEBUFFER_COMPLETE,
                GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER));
    }

    @After public void release() {
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
        EGL14.eglDestroySurface(display, surface);
        EGL14.eglDestroyContext(display, context);
        EGL14.eglTerminate(display);
    }

    @Test public void cameraShutterUnchanged() throws Exception {
        int[] row = draw("camera-natural", Controls.NATURAL);
        assertTrue("the square is drawn where it is now", row[416] > 100);
        assertTrue("nothing far behind it", row[200] == 0);
        assertTrue("the 180° streak reaches a little behind its left edge", row[396] > 0);
    }

    @Test public void longSmearIsContinuousAlongThePath() throws Exception {
        Controls trail = new Controls(8 * 360, Timing.TRAILING, 0, 100, 0, Look.SMEAR, 4);
        int[] row = draw("trail-smear-8", trail);
        int gaps = 0;
        for (int x = 96; x <= 430; x++) if (row[x] == 0) gaps++;
        MotionEmu.log("smear gaps between the oldest and newest pose: " + gaps);
        assertEquals("a Smear has no gap between where it was 8 frames ago and now", 0, gaps);
        assertTrue("and nothing before it started", row[40] == 0);
        int lo = 255, hi = 0;
        for (int x = 130; x <= 380; x++) { lo = Math.min(lo, row[x]); hi = Math.max(hi, row[x]); }
        MotionEmu.log("smear evenness across the middle: " + lo + ".." + hi);
        assertTrue("an even stroke, no stripes: " + lo + ".." + hi, hi - lo <= 24);
    }

    @Test public void copiesLeaveGaps() throws Exception {
        Controls copies = new Controls(8 * 360, Timing.TRAILING, 0, 100, 0, Look.COPIES, 3);
        int[] row = draw("trail-copies-3", copies);
        // poses at x = 80, 240, 400 (each 32 wide)
        assertTrue("oldest copy", row[96] > 0);
        assertTrue("middle copy", row[256] > 0);
        assertTrue("newest copy", row[416] > 0);
        assertEquals("a gap between the oldest and middle copy", 0, row[180]);
        assertEquals("a gap between the middle and newest copy", 0, row[340]);
    }

    @Test public void fadeThinsTheOldEnd() throws Exception {
        Controls faded = new Controls(8 * 360, Timing.TRAILING, 0, 100, 100, Look.SMEAR, 4);
        int[] row = draw("trail-smear-fade100", faded);
        MotionEmu.log("fade 100: old end " + row[120] + ", new end " + row[400]);
        assertTrue("Fade 100: the old end is darker than the new end", row[120] < row[400]);
    }

    @Test public void bothDrawsAndMixKeepsTheSharpThing() throws Exception {
        Controls both = new Controls(8 * 360, Timing.TRAILING, 0, 50, 0, Look.BOTH, 4);
        int[] row = draw("trail-both-mix50", both);
        assertTrue("Both: something along the path", row[200] > 0);
        assertTrue("Mix 50: the sharp square stands out at its place", row[416] > row[200]);
    }

    @Test public void stillThingIsSharpRoute() {
        ItemMotionSampler still = t -> new ItemMotionSampler.Affine(SQUARE, 0, 0, SQUARE, 100, 80);
        Controls trail = new Controls(8 * 360, Timing.TRAILING, 0, 100, 0, Look.SMEAR, 4);
        assertNull("nothing moves: no trail plan", RigidTextureShutterPass.plan(trail, still, NOW, PERIOD,
                MotionBreaks.none(), W, H));
    }

    /** Draws {@code c} for MOVER at NOW, saves the picture, returns the middle row's grey (0..255). */
    private int[] draw(String name, Controls c) throws Exception {
        boolean planted = "trail-gap".equals(MotionEmu.fault()) && c.isLongTrail();
        ItemMotionSampler sampler = planted
                ? t -> MOVER.at(t < NOW - 2 * PERIOD ? NOW - 8 * PERIOD : t)   // planted: a jump in the path
                : MOVER;
        RigidShutterPlan plan = RigidTextureShutterPass.plan(c, sampler, NOW, PERIOD, MotionBreaks.none(), W, H);
        assertNotNull(name + ": a moving square gets a plan", plan);
        RigidTextureShutterPass pass = new RigidTextureShutterPass(RigidTextureShutterPass.Source.PICTURE);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo);
        GLES20.glViewport(0, 0, W, H);
        GLES20.glClearColor(0, 0, 0, 1);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        pass.draw(source, W, H, plan, backdrop, 1f);
        ByteBuffer px = ByteBuffer.allocateDirect(W * H * 4).order(ByteOrder.nativeOrder());
        GLES20.glReadPixels(0, 0, W, H, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, px);
        pass.release();
        Bitmap out = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        int[] row = new int[W];
        int[] argb = new int[W * H];
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                int i = (y * W + x) * 4;
                int r = px.get(i) & 0xFF, g = px.get(i + 1) & 0xFF, b = px.get(i + 2) & 0xFF;
                // GL rows run bottom-up; the canvas the pass draws is top-down.
                int canvasY = H - 1 - y;
                argb[canvasY * W + x] = Color.argb(255, r, g, b);
                if (canvasY == ROW) row[x] = g;
            }
        }
        out.setPixels(argb, 0, W, 0, 0, W, H);
        MotionEmu.save(out, new File(MotionEmu.dir("trail"), name + ".png"));
        return row;
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

    @SuppressWarnings("unused")
    private static final int MAX = MotionShutter.MAX_TRAIL_ANGLE;
}
