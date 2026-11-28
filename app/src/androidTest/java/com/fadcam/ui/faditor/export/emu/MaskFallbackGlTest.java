package com.fadcam.ui.faditor.export.emu;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.util.Log;

import androidx.media3.effect.BaseGlShaderProgram;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.fadcam.ui.faditor.export.AdjustmentLayerGlEffect;
import com.fadcam.ui.faditor.model.AdjustmentLayer;
import com.fadcam.ui.faditor.model.CompositingSpec;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * GL-MASK (Codex audit round 4) on a real Android GL stack: an adjustment layer whose four mask
 * shapes will not fit one program on a small GPU still grades through ALL four. The export's
 * real program draws the layer normally, then with the GPU's mask budget forced down to 3, 2 and
 * 1 shapes (it then folds the masks in passes); every picture must match the normal one. Before
 * the fix the export fell back to the first shape alone, which this test catches.
 */
@RunWith(AndroidJUnit4.class)
public final class MaskFallbackGlTest {

    private static final String TAG = "MaskFallbackGl";
    private static final int W = 192, H = 108;

    private EGLDisplay display;
    private EGLContext context;
    private EGLSurface surface;
    private int input, target, fbo;

    @Before public void gl() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        int[] version = new int[2];
        assertTrue("EGL init", EGL14.eglInitialize(display, version, 0, version, 1));
        int[] attrs = {EGL14.EGL_RENDERABLE_TYPE, 0x40 /* EGL_OPENGL_ES3_BIT_KHR */,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_NONE};
        EGLConfig[] configs = new EGLConfig[1];
        int[] n = new int[1];
        assertTrue("EGL config", EGL14.eglChooseConfig(display, attrs, 0, configs, 0, 1, n, 0) && n[0] > 0);
        context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
                new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE}, 0);
        surface = EGL14.eglCreatePbufferSurface(display, configs[0],
                new int[]{EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE}, 0);
        assertTrue("EGL current", EGL14.eglMakeCurrent(display, surface, surface, context));

        // A picture with something to invert everywhere: a colour ramp across, a light ramp down.
        Bitmap ramp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
            ramp.setPixel(x, y, 0xFF000000 | ((x * 255 / W) << 16) | ((y * 255 / H) << 8) | (255 - x * 255 / W));
        }
        input = texture(ramp);
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
        AdjustmentLayerGlEffect.testShapeBudget = Integer.MAX_VALUE;
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
        EGL14.eglDestroySurface(display, surface);
        EGL14.eglDestroyContext(display, context);
        EGL14.eglTerminate(display);
    }

    /** Invert through four shapes: a feathered box (add), a round hole (subtract), a turned box (intersect), a far box (add). */
    private static AdjustmentLayer layer(int shapeCount) {
        AdjustmentLayer a = new AdjustmentLayer();
        a.setStartMs(0L);
        a.setDurationMs(4_000L);
        a.getFx().add("invert");
        CompositingSpec cs = a.getCompositing();
        cs.invertMasks = true;   // a window: the grade shows inside the shapes
        float[][] shapes = {
                {0.35f, 0.50f, 0.50f, 0.70f, 0f, 0f, 0.06f, CompositingSpec.MODE_ADD},
                {0.38f, 0.48f, 0.18f, 0.30f, 1f, 0f, 0f, CompositingSpec.MODE_SUBTRACT},
                {0.45f, 0.45f, 0.60f, 0.55f, 0f, 15f, 0.04f, CompositingSpec.MODE_INTERSECT},
                {0.82f, 0.30f, 0.16f, 0.25f, 0.3f, 0f, 0f, CompositingSpec.MODE_ADD},
        };
        for (int i = 0; i < shapeCount; i++) {
            float[] s = shapes[i];
            CompositingSpec.MaskShape m = cs.addShape();
            m.cx = s[0]; m.cy = s[1]; m.w = s[2]; m.h = s[3]; m.corner = s[4]; m.rotationDeg = s[5];
            m.feather = s[6]; m.mode = (int) s[7];
        }
        return a;
    }

    /** The export's own program for [layer], drawn once into the target; its pixels. */
    private int[] render(AdjustmentLayer layer, String name) throws Exception {
        Context ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        BaseGlShaderProgram program = new AdjustmentLayerGlEffect(ctx, layer, 0L).toGlShaderProgram(ctx, false);
        try {
            program.configure(W, H);
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo);
            GLES20.glViewport(0, 0, W, H);
            GLES20.glClearColor(0f, 0f, 0f, 0f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            program.drawFrame(input, 0L);
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo);
            ByteBuffer buf = ByteBuffer.allocateDirect(W * H * 4).order(ByteOrder.nativeOrder());
            GLES20.glReadPixels(0, 0, W, H, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf);
            assertEquals("no GL error drawing " + name, GLES20.GL_NO_ERROR, GLES20.glGetError());
            int[] px = new int[W * H * 4];
            for (int i = 0; i < px.length; i++) px[i] = buf.get(i) & 0xFF;
            return px;
        } finally {
            program.release();
        }
    }

    private static int maxDiff(int[] a, int[] b) {
        int d = 0;
        for (int i = 0; i < a.length; i++) if (i % 4 != 3) d = Math.max(d, Math.abs(a[i] - b[i]));
        return d;
    }

    private static int countDiff(int[] a, int[] b, int over) {
        int n = 0;
        for (int i = 0; i < a.length; i += 4) {
            int d = Math.max(Math.abs(a[i] - b[i]), Math.max(Math.abs(a[i + 1] - b[i + 1]), Math.abs(a[i + 2] - b[i + 2])));
            if (d > over) n++;
        }
        return n;
    }

    @Test public void everyMaskShapeCountsWhenTheWholeMaskWillNotFitOneProgram() throws Exception {
        int[] whole = render(layer(4), "four shapes, one program");
        int[] firstOnly = render(layer(1), "the first shape alone");
        int differs = countDiff(whole, firstOnly, 40);
        Log.i(TAG, "pixels where the other three shapes change the picture: " + differs + " of " + (W * H));
        assertTrue("the later shapes matter in this picture (" + differs + " pixels)", differs > W * H / 20);
        for (int budget = 3; budget >= 1; budget--) {
            AdjustmentLayerGlEffect.testShapeBudget = budget;
            int[] passes = render(layer(4), "four shapes, " + budget + " a pass");
            int d = maxDiff(whole, passes);
            int off = countDiff(whole, passes, 2);
            Log.i(TAG, "budget " + budget + ": largest difference " + d + ", pixels off by more than 2: " + off);
            assertTrue("with " + budget + " shape(s) a pass the picture matches the one-program grade: "
                    + off + " pixels differ by more than 2 (largest " + d + ")", off == 0);
        }
    }

    private static int texture(Bitmap b) {
        int[] t = new int[1];
        GLES20.glGenTextures(1, t, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t[0]);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, b, 0);
        return t[0];
    }
}
