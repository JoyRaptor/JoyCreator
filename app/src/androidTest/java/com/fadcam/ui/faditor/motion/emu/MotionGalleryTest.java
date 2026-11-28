package com.fadcam.ui.faditor.motion.emu;

import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
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
import com.fadcam.ui.faditor.motion.MotionShutter.Controls;
import com.fadcam.ui.faditor.motion.MotionShutter.Look;
import com.fadcam.ui.faditor.motion.MotionShutter.Timing;
import com.fadcam.ui.faditor.motion.RigidShutterPlan;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * A look book of the motion blur for the owner (STU-7 §K): four subjects (a swinging bar, a fast
 * spin, a fast slide, a detailed picture that curves and zooms) at every setting from a real
 * camera up to deliberately silly, each drawn by the real shader pass. Saved as one labelled sheet
 * per subject plus every tile alone, in files/motion-emu/gallery/.
 */
@RunWith(AndroidJUnit4.class)
public final class MotionGalleryTest {

    private static final int W = 400, H = 260;
    private static final long PERIOD = 41_667, NOW = 2_000_000;

    private record Setting(String name, Controls controls) {}

    private static final Setting[] SETTINGS = {
            new Setting("Natural 180", Controls.NATURAL),
            new Setting("Heavy 270", new Controls(270, Timing.CENTERED, 0, 100)),
            new Setting("720 behind (2 frames)", new Controls(720, Timing.TRAILING, 0, 100)),
            new Setting("Streak (8 fr, fade 60)", new Controls(8 * 360, Timing.TRAILING, 0, 100, 60, Look.SMEAR, 4)),
            new Setting("32 frames, no fade", new Controls(32 * 360, Timing.TRAILING, 0, 100, 0, Look.SMEAR, 4)),
            new Setting("32 frames, fade 100", new Controls(32 * 360, Timing.TRAILING, 0, 100, 100, Look.SMEAR, 4)),
            new Setting("Copies 7, 8 fr", new Controls(8 * 360, Timing.TRAILING, 0, 100, 0, Look.COPIES, 7)),
            new Setting("Both 5, fade 80", new Controls(8 * 360, Timing.TRAILING, 0, 100, 80, Look.BOTH, 5)),
            new Setting("Streak, mix 50", new Controls(8 * 360, Timing.TRAILING, 0, 50, 60, Look.SMEAR, 4)),
            new Setting("Natural + fade 100", Controls.NATURAL.withLook(100, Look.SMEAR, 4)),
    };

    /** uv (0..1) to canvas: w x h, turned by {@code a} about uv point (pu, pv) placed at (cx, cy). */
    private static ItemMotionSampler.Affine pose(double cx, double cy, double w, double h, double a,
                                                 double pu, double pv) {
        double c = Math.cos(a), s = Math.sin(a);
        double xx = w * c, xy = -h * s, yx = w * s, yy = h * c;
        return new ItemMotionSampler.Affine(xx, xy, yx, yy, cx - (xx * pu + xy * pv), cy - (yx * pu + yy * pv));
    }

    private static double frames(long t) { return (t - NOW) / (double) PERIOD; }

    @Test public void lookBook() throws Exception {
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
            int backdrop = texture(backdrop());
            int target = texture(Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888));
            int[] f = new int[1];
            GLES20.glGenFramebuffers(1, f, 0);
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, f[0]);
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                    GLES20.GL_TEXTURE_2D, target, 0);
            RigidTextureShutterPass pass = new RigidTextureShutterPass(RigidTextureShutterPass.Source.PICTURE);

            // 1. a black-and-white striped bar swinging like a pendulum about its left end
            sheet(pass, backdrop, "swing", stripedBar(), t ->
                    pose(70, 70, 230, 26, Math.toRadians(70) * Math.sin(2 * Math.PI * frames(t) / 18.0 + 1.2), 0, 0.5));
            // 2. a checker square spinning 90 degrees a frame about its middle
            sheet(pass, backdrop, "spin", checker(), t ->
                    pose(200, 130, 90, 90, Math.PI / 2 * frames(t), 0.5, 0.5));
            // 3. a white disc sliding 30 px a frame, left to right
            sheet(pass, backdrop, "slide", disc(), t ->
                    pose(300 + 30 * frames(t), 130, 60, 60, 0, 0.5, 0.5));
            // 4. a detailed picture arcing upward and growing, tilting as it goes
            sheet(pass, backdrop, "picture", picture(), t -> {
                double fr = frames(t);
                double scale = 1.0 + 0.04 * fr;   // smaller in the past
                return pose(280 + 14 * fr, 150 - 0.9 * fr * fr - 8 * fr, 110 * scale, 110 * scale,
                        Math.toRadians(4 * fr), 0.5, 0.5);
            });
            // Clips: the blur in motion, 48 frames each (2 s at 24 fps), saved frame by frame.
            ItemMotionSampler swing = t ->
                    pose(70, 70, 230, 26, Math.toRadians(70) * Math.sin(2 * Math.PI * frames(t) / 18.0 + 1.2), 0, 0.5);
            Controls streak = new Controls(8 * 360, Timing.TRAILING, 0, 100, 60, Look.SMEAR, 4);
            clip(pass, backdrop, "swing-sharp", stripedBar(), swing, null, false);
            clip(pass, backdrop, "swing-natural", stripedBar(), swing, Controls.NATURAL, false);
            clip(pass, backdrop, "swing-streak-export", stripedBar(), swing, streak, false);
            clip(pass, backdrop, "swing-streak-preview", stripedBar(), swing, streak, true);
            clip(pass, backdrop, "spin-natural", checker(), t ->
                    pose(200, 130, 90, 90, Math.PI / 2 * frames(t), 0.5, 0.5), Controls.NATURAL, false);
            clip(pass, backdrop, "slide-copies", disc(), t ->
                    pose(200 + 160 * Math.sin(2 * Math.PI * frames(t) / 24.0), 130, 60, 60, 0, 0.5, 0.5),
                    new Controls(8 * 360, Timing.TRAILING, 0, 100, 40, Look.COPIES, 6), false);
            clip(pass, backdrop, "picture-streak", picture(), t -> {
                double a = 2 * Math.PI * frames(t) / 48.0;
                return pose(200 + 120 * Math.cos(a), 130 + 70 * Math.sin(2 * a), 100, 100,
                        Math.toRadians(20 * Math.sin(a)), 0.5, 0.5);
            }, streak, false);
            pass.release();
        } finally {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            EGL14.eglDestroySurface(display, surface);
            EGL14.eglDestroyContext(display, context);
            EGL14.eglTerminate(display);
        }
    }

    private void clip(RigidTextureShutterPass pass, int backdrop, String name, Bitmap art,
                      ItemMotionSampler sampler, Controls controls, boolean preview) throws Exception {
        int source = texture(art);
        File dir = MotionEmu.dir("clips/" + name);
        for (int k = 0; k < 48; k++) {
            Bitmap frame = draw(pass, source, backdrop, sampler, controls, NOW + k * PERIOD, preview);
            MotionEmu.save(frame, new File(dir, String.format(java.util.Locale.ROOT, "f%03d.png", k)));
        }
        MotionEmu.log("clip " + name + " saved");
    }

    private void sheet(RigidTextureShutterPass pass, int backdrop, String subject, Bitmap art,
                       ItemMotionSampler sampler) throws Exception {
        int source = texture(art);
        int cols = 2, rows = (SETTINGS.length + 1) / 2, label = 26, gap = 6;
        Bitmap sheet = Bitmap.createBitmap(cols * (W + gap) + gap, rows * (H + label + gap) + gap + 34,
                Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(sheet);
        c.drawColor(0xFF101014);
        Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        text.setColor(0xFFF2F2F5);
        text.setTextSize(20);
        text.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        c.drawText("Motion blur look book: " + subject, gap, 24, text);
        text.setTextSize(16);
        text.setTypeface(Typeface.MONOSPACE);
        File dir = MotionEmu.dir("gallery");
        for (int i = 0; i < SETTINGS.length; i++) {
            Bitmap tile = draw(pass, source, backdrop, sampler, SETTINGS[i].controls());
            int x = gap + (i % cols) * (W + gap), y = 34 + gap + (i / cols) * (H + label + gap);
            c.drawText(SETTINGS[i].name(), x, y + 18, text);
            c.drawBitmap(tile, x, y + label, null);
            MotionEmu.save(tile, new File(dir, subject + "-" + i + ".png"));
        }
        MotionEmu.save(sheet, new File(dir, "sheet-" + subject + ".png"));
        MotionEmu.log("gallery sheet " + subject + " saved");
    }

    private static Bitmap draw(RigidTextureShutterPass pass, int source, int backdrop,
                               ItemMotionSampler sampler, Controls controls) throws Exception {
        return draw(pass, source, backdrop, sampler, controls, NOW, false);
    }

    /** One frame at {@code atUs}; {@code preview} draws the live preview's lighter plan. */
    private static Bitmap draw(RigidTextureShutterPass pass, int source, int backdrop,
                               ItemMotionSampler sampler, Controls controls, long atUs,
                               boolean preview) throws Exception {
        RigidShutterPlan plan = controls == null ? null : preview
                ? RigidTextureShutterPass.planPreview(controls, sampler, atUs, PERIOD, MotionBreaks.none(), W, H)
                : RigidTextureShutterPass.plan(controls, sampler, atUs, PERIOD, MotionBreaks.none(), W, H);
        GLES20.glViewport(0, 0, W, H);
        GLES20.glClearColor(0, 0, 0, 1);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        if (plan == null) plan = RigidShutterPlan.sharp(sampler, atUs, W, H);
        pass.draw(source, W, H, plan, backdrop, 1f);
        ByteBuffer px = ByteBuffer.allocateDirect(W * H * 4).order(ByteOrder.nativeOrder());
        GLES20.glReadPixels(0, 0, W, H, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, px);
        int[] argb = new int[W * H];
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                int i = (y * W + x) * 4;
                argb[(H - 1 - y) * W + x] = Color.argb(255, px.get(i) & 0xFF, px.get(i + 1) & 0xFF,
                        px.get(i + 2) & 0xFF);
            }
        }
        Bitmap out = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        out.setPixels(argb, 0, W, 0, 0, W, H);
        return out;
    }

    // ── art: simple and high contrast first, then something with detail ──

    private static Bitmap backdrop() {
        Bitmap b = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.drawColor(0xFF2A2C34);
        Paint p = new Paint();
        p.setColor(0xFF33363F);
        for (int x = 0; x < W; x += 40) for (int y = (x / 40) % 2 * 40; y < H; y += 80) c.drawRect(x, y, x + 40, y + 40, p);
        return b;
    }

    private static Bitmap stripedBar() {
        Bitmap b = Bitmap.createBitmap(230, 26, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.drawColor(Color.WHITE);
        Paint p = new Paint();
        p.setColor(Color.BLACK);
        for (int x = 10; x < 230; x += 28) c.drawRect(x, 0, x + 12, 26, p);
        return b;
    }

    private static Bitmap checker() {
        Bitmap b = Bitmap.createBitmap(90, 90, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint();
        for (int i = 0; i < 6; i++) for (int j = 0; j < 6; j++) {
            p.setColor((i + j) % 2 == 0 ? Color.WHITE : Color.BLACK);
            c.drawRect(i * 15, j * 15, i * 15 + 15, j * 15 + 15, p);
        }
        p.setColor(0xFFFF3B30);
        c.drawRect(0, 0, 15, 15, p);   // one red corner, to follow the turn
        return b;
    }

    private static Bitmap disc() {
        Bitmap b = Bitmap.createBitmap(60, 60, Bitmap.Config.ARGB_8888);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.WHITE);
        new Canvas(b).drawCircle(30, 30, 28, p);
        return b;
    }

    private static Bitmap picture() {
        Bitmap b = Bitmap.createBitmap(110, 110, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new LinearGradient(0, 0, 110, 110, 0xFF35F6BF, 0xFFFF008C, Shader.TileMode.CLAMP));
        c.drawRoundRect(new RectF(0, 0, 110, 110), 18, 18, p);
        p.setShader(new RadialGradient(55, 50, 34, 0xFFFFF3B0, 0xFFE48A2B, Shader.TileMode.CLAMP));
        c.drawCircle(55, 50, 34, p);
        p.setShader(null);
        p.setColor(0xFF1B1B22);
        c.drawCircle(43, 44, 5, p);
        c.drawCircle(67, 44, 5, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(4);
        c.drawArc(new RectF(40, 46, 70, 70), 20, 140, false, p);
        p.setStyle(Paint.Style.FILL);
        p.setTextSize(18);
        p.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        p.setColor(Color.WHITE);
        c.drawText("JOY", 36, 102, p);
        return b;
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
