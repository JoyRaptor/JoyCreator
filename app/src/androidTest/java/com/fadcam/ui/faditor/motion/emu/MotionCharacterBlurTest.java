package com.fadcam.ui.faditor.motion.emu;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.opengl.GLUtils;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.fadcam.ui.faditor.compositor.CharacterMotionRaster;
import com.fadcam.ui.faditor.compositor.RigidSpriteMotion;
import com.fadcam.ui.faditor.compositor.RigidTextureShutterPass;
import com.fadcam.ui.faditor.motion.ItemMotionSampler;
import com.fadcam.ui.faditor.motion.MotionItemAdapters;
import com.fadcam.ui.faditor.motion.MotionShutter.Controls;
import com.fadcam.ui.faditor.motion.MotionShutter.Look;
import com.fadcam.ui.faditor.motion.MotionShutter.Timing;
import com.fadcam.ui.faditor.motion.RigidShutterPlan;
import com.fadcam.ui.faditor.sprite.FrameTrack;
import com.fadcam.ui.faditor.sprite.SpriteOverlayItem;
import com.fadcam.ui.faditor.sprite.SpriteSheet;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

import cc.joycreator.avatar.file.CharacterAssets;
import cc.joycreator.avatar.perform.Take;
import cc.joycreator.avatar.studio.CharacterLoader;
import cc.joycreator.avatar.studio.ItemPuppet;
import cc.joycreator.avatar.studio.ItemPuppets;
import cc.joycreator.avatar.studio.StudioCharacter;

/**
 * STU-7: an Avatar Studio character in Studio, moving fast across the frame while it acts (head
 * turned, mouth open), with Motion Blur on. The blur must carry its performance, not its standing
 * picture. Saves the pictures for the owner's review page (files/motion-emu/character/).
 */
@RunWith(AndroidJUnit4.class)
public final class MotionCharacterBlurTest {

    private static final int W = 720, H = 405;
    private static final long PERIOD = 33_333;

    @Test public void aCharacterBlursWithItsPerformance() throws Exception {
        Context app = MotionEmu.ins().getTargetContext();
        File assets = new File(app.getCacheDir(), "motion-emu-assets");
        assets.mkdirs();
        StudioCharacter.Prepared p = StudioCharacter.prepareNow(app, CharacterLoader.assetRef(CharacterAssets.TOPPS), assets);
        SpriteSheet sheet = new SpriteSheet(p.sheetId(), p.name, Uri.fromFile(p.picture).toString());
        sheet.setGrid(1, 1);
        Bitmap standing = BitmapFactory.decodeFile(p.picture.getAbsolutePath());
        assertNotNull("the standing picture loads", standing);
        float aspect = standing.getWidth() / (float) standing.getHeight();

        SpriteOverlayItem item = SpriteOverlayItem.create(sheet.getId());
        item.getFrameTrack().put(FrameTrack.Key.ofCell(0, 0));
        item.setTimeRange(0, 2000);
        item.setSizeFraction(0.55f);
        item.setCharacterRef(p.ref);
        item.setCharacterUid(p.uid);
        item.setCharacterTake(StudioCharacter.json(acting()));
        // a fast move across the frame: left to right in two seconds
        item.setCenter(0.15f, 0.55f);
        item.addKeyframeAt(0);
        item.setCenter(0.85f, 0.55f);
        item.addKeyframeAt(2000);
        item.setRigidMotionBlurEnabled(true);
        assertTrue("a moving character counts as blurrable", RigidSpriteMotion.supports(item));

        ItemPuppet puppet = ItemPuppets.forItem(app, item, null, null, null);
        assertNotNull("the character has its puppet", puppet);

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
        File dir = MotionEmu.dir("character");
        try {
            long atMs = 1000;
            Bitmap acting = CharacterMotionRaster.draw(puppet, item, atMs, aspect, 512, null);
            assertNotNull("the character draws its performance", acting);
            // the character draw may have switched GL contexts and back: make ours current again
            EGL14.eglMakeCurrent(display, surface, surface, context);
            MotionEmu.save(acting, new File(dir, "performance-at-1s.png"));
            Bitmap standingScaled = Bitmap.createScaledBitmap(standing, acting.getWidth(), acting.getHeight(), true);
            double changed = differs(acting, standingScaled);
            MotionEmu.log("character: performance differs from the standing picture on " + Math.round(changed * 1000) / 10.0 + "% of pixels");
            assertTrue("the blur carries the acting (turned head, open mouth), not the standing picture: " + changed,
                    changed > 0.01);

            int source = texture(acting);
            Bitmap back = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
            back.eraseColor(0xFF2A2C34);
            int backdrop = texture(back);
            int target = texture(Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888));
            int[] fb = new int[1];
            GLES20.glGenFramebuffers(1, fb, 0);
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fb[0]);
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                    GLES20.GL_TEXTURE_2D, target, 0);
            RigidTextureShutterPass pass = new RigidTextureShutterPass(RigidTextureShutterPass.Source.PREMULTIPLIED_OVERLAY);
            ItemMotionSampler sampler = MotionItemAdapters.sprite(item, W, H, aspect);
            var fences = RigidSpriteMotion.fences(item, 0, 2_000_000);
            long atUs = atMs * 1000;
            Bitmap sharp = render(pass, source, backdrop, RigidShutterPlan.sharp(sampler, atUs, W, H));
            MotionEmu.save(sharp, new File(dir, "character-sharp.png"));
            String[] names = {"character-natural", "character-heavy", "character-streak"};
            Controls[] settings = {Controls.NATURAL, new Controls(270, Timing.CENTERED, 0, 100),
                    new Controls(8 * 360, Timing.TRAILING, 0, 100, 60, Look.SMEAR, 4)};
            for (int i = 0; i < names.length; i++) {
                item.setRigidMotionShutter(settings[i]);
                var plan = RigidSpriteMotion.plan(item, sheet, sampler, fences, atUs, PERIOD, W, H);
                assertNotNull(names[i] + ": a moving character gets a blur plan", plan);
                Bitmap blurred = render(pass, source, backdrop, plan);
                MotionEmu.save(blurred, new File(dir, names[i] + ".png"));
                double d = differs(blurred, sharp);
                MotionEmu.log("character " + names[i] + ": differs from sharp on " + Math.round(d * 1000) / 10.0 + "% of pixels");
                assertTrue(names[i] + " blurs the moving character", d > 0.005);
            }
            pass.release();
        } finally {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            EGL14.eglDestroySurface(display, surface);
            EGL14.eglDestroyContext(display, context);
            EGL14.eglTerminate(display);
        }
    }

    /** Two seconds of acting at 30 a second: head turned, looking up, mouth open. */
    private static Take acting() {
        Take t = new Take();
        t.n = 60;
        List<Object> turn = new ArrayList<>(), up = new ArrayList<>(), mouth = new ArrayList<>(), vis = new ArrayList<>();
        for (int i = 0; i < t.n; i++) { turn.add(0.6); up.add(-0.2); mouth.add(0.8); vis.add("Aa"); }
        t.ch.put("turnX", turn);
        t.ch.put("turnY", up);
        t.ch.put("mouth", mouth);
        t.ch.put("vis", vis);
        return t;
    }

    private static Bitmap render(RigidTextureShutterPass pass, int source, int backdrop, RigidShutterPlan plan)
            throws Exception {
        GLES20.glViewport(0, 0, W, H);
        GLES20.glClearColor(0, 0, 0, 1);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        pass.draw(source, W, H, plan, backdrop, 1f);
        ByteBuffer px = ByteBuffer.allocateDirect(W * H * 4).order(ByteOrder.nativeOrder());
        GLES20.glReadPixels(0, 0, W, H, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, px);
        int[] argb = new int[W * H];
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                int i = (y * W + x) * 4;
                argb[(H - 1 - y) * W + x] = Color.argb(255, px.get(i) & 0xFF, px.get(i + 1) & 0xFF, px.get(i + 2) & 0xFF);
            }
        }
        Bitmap out = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        out.setPixels(argb, 0, W, 0, 0, W, H);
        return out;
    }

    /** Share of pixels that differ by more than 24 levels in any channel. */
    private static double differs(Bitmap a, Bitmap b) {
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
