package cc.joycreator.avatar.draw;

import android.app.Activity;
import android.graphics.Bitmap;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.os.Bundle;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import cc.joycreator.avatar.file.CharacterAssets;
import cc.joycreator.avatar.file.CharacterDoc;
import cc.joycreator.avatar.solve.BitmapPics;
import cc.joycreator.avatar.solve.CharacterSolver;
import cc.joycreator.avatar.solve.Signals;
import cc.joycreator.avatar.solve.SolveOut;

/**
 * DEBUG ONLY (AVA-1 X3 proof). Draws the shipped Topps and Pip off-screen with {@link CharacterGl} (solved by CharacterSolver), a
 * few poses each, 512 x 512 at 1:1, and saves each picture as a PNG for comparing with the web pilot's picture of the same
 * character and pose. Also times the solve and the draw. Nothing on screen; finishes by itself.
 *
 *   adb shell am start -n com.fadcam.beta/cc.joycreator.avatar.draw.CharacterDrawBenchActivity
 *   results: adb logcat -s AvatarDrawBench ; pictures in Android/data/com.fadcam.beta/files/draw-bench/
 */
public class CharacterDrawBenchActivity extends Activity {
    private static final String TAG = "AvatarDrawBench";
    private static final int W = 512, H = 512;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        new Thread(this::run, "avatar-draw-bench").start();
    }

    private static Signals pose(String name) {
        Signals s = new Signals();
        switch (name) {
            case "turn": s.turnX = 0.6; s.turnY = -0.2; break;
            case "talk": s.mouth = 0.8; s.viseme = "Aa"; s.blinkL = 1; s.blinkR = 1; break;
            case "reach": s.hand.put("L", new Signals.Hand(380, 170)); s.hand.put("R", new Signals.Hand(130, 190)); break;
            default: break;
        }
        return s;
    }

    private void run() {
        EGLDisplay dpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        int[] ver = new int[2];
        EGL14.eglInitialize(dpy, ver, 0, ver, 1);
        int[] attrs = {EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT, EGL14.EGL_NONE};
        EGLConfig[] cfg = new EGLConfig[1];
        int[] n = new int[1];
        EGL14.eglChooseConfig(dpy, attrs, 0, cfg, 0, 1, n, 0);
        EGLContext ctx = EGL14.eglCreateContext(dpy, cfg[0], EGL14.EGL_NO_CONTEXT, new int[] {EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE}, 0);
        EGLSurface surf = EGL14.eglCreatePbufferSurface(dpy, cfg[0], new int[] {EGL14.EGL_WIDTH, W, EGL14.EGL_HEIGHT, H, EGL14.EGL_NONE}, 0);
        EGL14.eglMakeCurrent(dpy, surf, surf, ctx);
        File dir = new File(getExternalFilesDir(null), "draw-bench");
        dir.mkdirs();
        CharacterGl gl = new CharacterGl();
        try {
            for (String asset : new String[] {CharacterAssets.TOPPS, CharacterAssets.PIP}) {
                CharacterDoc doc = CharacterAssets.load(this, asset);
                BitmapPics pics = new BitmapPics(doc.json, null);
                String who = doc.name.toLowerCase();
                for (String p : new String[] {"rest", "turn", "talk", "reach"}) {
                    CharacterSolver sv = new CharacterSolver(doc.json, pics);
                    CharacterSolver.Opt opt = new CharacterSolver.Opt();
                    opt.live = true; opt.dt = 1 / 30.0;
                    Signals S = pose(p);
                    SolveOut out = null;
                    long t0 = System.nanoTime();
                    for (int i = 0; i < 8; i++) { S.t = i / 30.0; out = sv.eval(S, opt); }
                    long t1 = System.nanoTime();
                    gl.draw(out, pics, new CharacterGl.View(1, 0, 0), null, 0, W, H);
                    GLES20.glFinish();
                    long t2 = System.nanoTime();
                    gl.draw(out, pics, new CharacterGl.View(1, 0, 0), null, 0, W, H);       // a second draw: textures already uploaded
                    GLES20.glFinish();
                    long t3 = System.nanoTime();
                    ByteBuffer px = ByteBuffer.allocateDirect(W * H * 4).order(ByteOrder.nativeOrder());
                    GLES20.glReadPixels(0, 0, W, H, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, px);
                    Bitmap bm = flipped(px);
                    try (FileOutputStream f = new FileOutputStream(new File(dir, who + "-" + p + ".png"))) { bm.compress(Bitmap.CompressFormat.PNG, 100, f); }
                    bm.recycle();
                    Log.i(TAG, String.format("%s %s: %d pieces, solve %.2f ms a frame, first draw %.1f ms, draw %.2f ms, gl error %d",
                        who, p, out.items.size(), (t1 - t0) / 8e6, (t2 - t1) / 1e6, (t3 - t2) / 1e6, GLES20.glGetError()));
                }
                gl.forget();
            }
            Log.i(TAG, "DONE " + dir);
        } catch (Exception | Error e) {
            Log.e(TAG, "FAILED", e);
        } finally {
            gl.release();
            EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            EGL14.eglDestroySurface(dpy, surf);
            EGL14.eglDestroyContext(dpy, ctx);
            EGL14.eglTerminate(dpy);
            runOnUiThread(this::finish);
        }
    }

    /** glReadPixels gives the bottom row first and premultiplied colour; a Bitmap wants the top row first (and is premultiplied). */
    private static Bitmap flipped(ByteBuffer px) {
        Bitmap bm = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        ByteBuffer rows = ByteBuffer.allocateDirect(W * H * 4).order(ByteOrder.nativeOrder());
        byte[] line = new byte[W * 4];
        for (int y = 0; y < H; y++) { px.position((H - 1 - y) * W * 4); px.get(line); rows.put(line); }
        rows.position(0);
        bm.copyPixelsFromBuffer(rows);
        return bm;
    }
}
