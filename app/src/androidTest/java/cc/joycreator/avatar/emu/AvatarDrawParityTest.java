package cc.joycreator.avatar.emu;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import cc.joycreator.avatar.draw.CharacterGl;
import cc.joycreator.avatar.file.CharacterAssets;
import cc.joycreator.avatar.file.CharacterDoc;
import cc.joycreator.avatar.solve.BitmapPics;
import cc.joycreator.avatar.solve.CharacterSolver;
import cc.joycreator.avatar.solve.Item;
import cc.joycreator.avatar.solve.Signals;
import cc.joycreator.avatar.solve.SolveOut;

/**
 * Avatar Studio's phone drawing against the web pilot's (AVA-1 X2 + X3), on whatever GPU this runs on (the cloud
 * emulator's SwiftShader, or a phone). Topps and Pip, five poses each, solved by {@link CharacterSolver} and drawn by
 * {@link CharacterGl} off-screen at 512 x 512, compared with the pilot's own picture of the same file and pose
 * ({@code androidTest/assets/avatar-golden/}, made by AL.GL in tools/avatarlab).
 *
 * <p>Edge smoothing differs between drawers (the browser's multisampling against this GPU's), so a difference ON an
 * outline is allowed; a difference anywhere else is a fault. Both pictures and a difference picture are saved for the
 * run's artifact. {@code -e fault shift} plants a fault (one piece moved 2 px) and the test must then fail.</p>
 */
@RunWith(AndroidJUnit4.class)
public class AvatarDrawParityTest {
    private static final int W = 512, H = 512;
    /** A difference above this many levels (of 255, premultiplied) counts. */
    private static final int BAD = 32;
    /** At most this share of the drawn pixels may differ by more than BAD away from an outline. */
    private static final double MAX_OFF_EDGE = 0.001;
    /** At most this share of the drawn pixels may differ by more than 8 levels at all (outlines included). */
    private static final double MAX_OVER_8 = 0.04;

    /** The pilot's poses, exactly as CharacterDrawBenchActivity made the phone side of the golden pictures. */
    static Signals pose(String name, CharacterDoc doc) {
        Signals s = new Signals();
        switch (name) {
            case "turn": s.turnX = 0.6; s.turnY = -0.2; break;
            case "talk": s.mouth = 0.8; s.viseme = "Aa"; s.blinkL = 1; s.blinkR = 1; break;
            case "reach": s.hand.put("L", new Signals.Hand(380, 170)); s.hand.put("R", new Signals.Hand(130, 190)); break;
            case "colour": {
                org.json.JSONArray pal = doc.json.optJSONArray("palettes");
                if (pal != null) for (int k = 0; k < pal.length(); k++) s.tone.put(pal.optJSONObject(k).optString("id"), 1.0);
                break;
            }
            default: break;
        }
        return s;
    }

    static final String[] POSES = {"rest", "turn", "talk", "reach", "colour"};

    @Test
    public void toppsAndPipMatchThePilot() throws Exception {
        Context app = Emu.ins().getTargetContext(), test = Emu.ins().getContext();
        File out = Emu.dir("draw");
        List<String> failed = new ArrayList<>();
        StringBuilder table = new StringBuilder("who-pose        drawn   over8  overBAD  offEdge  verdict\n");
        EglBox egl = new EglBox();
        CharacterGl gl = new CharacterGl();
        try {
            Log.i(Emu.TAG, "GL: " + GLES20.glGetString(GLES20.GL_RENDERER) + " / " + GLES20.glGetString(GLES20.GL_VERSION) + ", samples " + egl.samples);
            for (String asset : new String[] {CharacterAssets.TOPPS, CharacterAssets.PIP}) {
                CharacterDoc doc = CharacterAssets.load(app, asset);
                BitmapPics pics = new BitmapPics(doc.json, null);
                String who = doc.name.toLowerCase(Locale.ROOT);
                for (String p : POSES) {
                    CharacterSolver sv = new CharacterSolver(doc.json, pics);
                    CharacterSolver.Opt opt = new CharacterSolver.Opt();
                    opt.live = true; opt.dt = 1 / 30.0;
                    Signals S = pose(p, doc);
                    SolveOut so = null;
                    for (int i = 0; i < 8; i++) { S.t = i / 30.0; so = sv.eval(S, opt); }
                    if ("shift".equals(Emu.fault())) plantShift(so);
                    Log.i(Emu.TAG, "drawing " + who + " " + p + ": " + so.items.size() + " pieces");
                    gl.draw(so, pics, new CharacterGl.View(1, 0, 0), null, 0, W, H);
                    GLES20.glFinish();
                    assertEquals("GL error drawing " + who + " " + p, GLES20.GL_NO_ERROR, GLES20.glGetError());
                    Bitmap phone = egl.read();
                    Bitmap pilot;
                    try (InputStream in = test.getAssets().open("avatar-golden/" + who + "-" + p + ".png")) { pilot = BitmapFactory.decodeStream(in); }
                    Diff d = compare(phone, pilot);
                    String name = who + "-" + p;
                    Emu.save(phone, new File(out, name + "-phone.png"));
                    Emu.save(d.picture, new File(out, name + "-diff.png"));
                    boolean ok = d.offEdge <= MAX_OFF_EDGE * d.drawn && d.over8 <= MAX_OVER_8 * d.drawn;
                    if (!ok) failed.add(name);
                    table.append(String.format(Locale.ROOT, "%-14s %6d %7d %8d %8d  %s\n", name, d.drawn, d.over8, d.overBad, d.offEdge, ok ? "ok" : "FAULT"));
                    phone.recycle(); pilot.recycle(); d.picture.recycle();
                }
                gl.forget();
            }
        } finally {
            gl.release();
            egl.close();
        }
        Log.i(Emu.TAG, "draw parity\n" + table);
        try (FileOutputStream f = new FileOutputStream(new File(out, "parity.txt"))) { f.write(table.toString().getBytes("UTF-8")); }
        assertTrue("differs from the pilot away from outlines: " + failed + "\n" + table, failed.isEmpty());
    }

    /** The planted fault: the second-largest piece drawn 2 px to the right. */
    private static void plantShift(SolveOut so) {
        Item big = null, second = null;
        double a1 = -1, a2 = -1;
        for (Item it : so.items) {
            if (it.v == null || it.v.length < 2 || it.ghost) continue;
            float x0 = Float.MAX_VALUE, y0 = Float.MAX_VALUE, x1 = -Float.MAX_VALUE, y1 = -Float.MAX_VALUE;
            for (int i = 0; i < it.v.length; i += 2) { x0 = Math.min(x0, it.v[i]); x1 = Math.max(x1, it.v[i]); y0 = Math.min(y0, it.v[i + 1]); y1 = Math.max(y1, it.v[i + 1]); }
            double a = (double) (x1 - x0) * (y1 - y0);
            if (a > a1) { second = big; a2 = a1; big = it; a1 = a; } else if (a > a2) { second = it; a2 = a; }
        }
        if (second == null) return;
        float[] v = second.v.clone();
        for (int i = 0; i < v.length; i += 2) v[i] += 2;
        second.v = v;
    }

    static final class Diff {
        int drawn, over8, overBad, offEdge;
        Bitmap picture;
    }

    /**
     * Premultiplied RGBA, worst channel. An outline is where the pilot's own picture changes by more than 48 levels across
     * 3 x 3 pixels, widened by one pixel. The difference picture: grey = a difference on an outline, red = elsewhere.
     */
    static Diff compare(Bitmap phone, Bitmap pilot) {
        int n = W * H;
        int[] a = new int[n], b = new int[n];
        phone.getPixels(a, 0, W, 0, 0, W, H);
        pilot.getPixels(b, 0, W, 0, 0, W, H);
        int[][] pa = premul(a), pb = premul(b);
        boolean[] edge = new boolean[n];
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
            int range = 0;
            for (int c = 0; c < 4; c++) {
                int lo = 255, hi = 0;
                for (int oy = -1; oy <= 1; oy++) for (int ox = -1; ox <= 1; ox++) {
                    int qx = Math.min(W - 1, Math.max(0, x + ox)), qy = Math.min(H - 1, Math.max(0, y + oy));
                    int v = pb[c][qy * W + qx];
                    lo = Math.min(lo, v); hi = Math.max(hi, v);
                }
                range = Math.max(range, hi - lo);
            }
            edge[y * W + x] = range > 48;
        }
        boolean[] band = new boolean[n];
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
            if (!edge[y * W + x]) continue;
            for (int oy = -1; oy <= 1; oy++) for (int ox = -1; ox <= 1; ox++) {
                int qx = x + ox, qy = y + oy;
                if (qx >= 0 && qy >= 0 && qx < W && qy < H) band[qy * W + qx] = true;
            }
        }
        Diff d = new Diff();
        int[] pic = new int[n];
        for (int i = 0; i < n; i++) {
            if (pa[3][i] > 8 || pb[3][i] > 8) d.drawn++;
            int worst = 0;
            for (int c = 0; c < 4; c++) worst = Math.max(worst, Math.abs(pa[c][i] - pb[c][i]));
            if (worst > 8) d.over8++;
            if (worst > BAD) {
                d.overBad++;
                if (!band[i]) d.offEdge++;
            }
            pic[i] = worst > BAD && !band[i] ? 0xffff2020 : worst > 8 ? 0xff808080 : 0xff000000 | (pb[3][i] / 6) * 0x010101;
        }
        d.picture = Bitmap.createBitmap(pic, W, H, Bitmap.Config.ARGB_8888);
        return d;
    }

    private static int[][] premul(int[] px) {
        int n = px.length;
        int[][] o = new int[4][n];
        for (int i = 0; i < n; i++) {
            int p = px[i], al = p >>> 24;
            o[0][i] = ((p >> 16) & 255) * al / 255;
            o[1][i] = ((p >> 8) & 255) * al / 255;
            o[2][i] = (p & 255) * al / 255;
            o[3][i] = al;
        }
        return o;
    }

    /** An off-screen 512 x 512 GLES 2 surface, 4x multisampled when the GPU offers it (as the room's stage asks). */
    static final class EglBox {
        final EGLDisplay dpy;
        final EGLContext ctx;
        final EGLSurface surf;
        int samples;

        /** {@code -e msaa 0} asks for no multisampling (the emulator's SwiftShader can stall on a multisampled pbuffer). */
        EglBox() {
            int want = Integer.parseInt(Emu.arg("msaa", "4"));
            Log.i(Emu.TAG, "EGL: display, asking for " + want + " samples");
            dpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            int[] ver = new int[2];
            EGL14.eglInitialize(dpy, ver, 0, ver, 1);
            EGLConfig[] cfg = new EGLConfig[1];
            int[] n = new int[1];
            int[] msaa = {EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_SAMPLE_BUFFERS, 1, EGL14.EGL_SAMPLES, want, EGL14.EGL_NONE};
            int[] plain = {EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT, EGL14.EGL_NONE};
            if (want > 0 && EGL14.eglChooseConfig(dpy, msaa, 0, cfg, 0, 1, n, 0) && n[0] > 0) samples = want;
            else { EGL14.eglChooseConfig(dpy, plain, 0, cfg, 0, 1, n, 0); samples = 0; }
            Log.i(Emu.TAG, "EGL: config chosen (" + samples + " samples), making the context");
            ctx = EGL14.eglCreateContext(dpy, cfg[0], EGL14.EGL_NO_CONTEXT, new int[] {EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE}, 0);
            surf = EGL14.eglCreatePbufferSurface(dpy, cfg[0], new int[] {EGL14.EGL_WIDTH, W, EGL14.EGL_HEIGHT, H, EGL14.EGL_NONE}, 0);
            if (!EGL14.eglMakeCurrent(dpy, surf, surf, ctx)) throw new IllegalStateException("no GL context: " + EGL14.eglGetError());
            Log.i(Emu.TAG, "EGL: current");
        }

        /** glReadPixels gives the bottom row first; a Bitmap wants the top row first (both premultiplied). */
        Bitmap read() {
            ByteBuffer px = ByteBuffer.allocateDirect(W * H * 4).order(ByteOrder.nativeOrder());
            GLES20.glReadPixels(0, 0, W, H, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, px);
            ByteBuffer rows = ByteBuffer.allocateDirect(W * H * 4).order(ByteOrder.nativeOrder());
            byte[] line = new byte[W * 4];
            for (int y = 0; y < H; y++) { px.position((H - 1 - y) * W * 4); px.get(line); rows.put(line); }
            rows.position(0);
            Bitmap bm = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
            bm.copyPixelsFromBuffer(rows);
            return bm;
        }

        void close() {
            EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            EGL14.eglDestroySurface(dpy, surf);
            EGL14.eglDestroyContext(dpy, ctx);
            EGL14.eglTerminate(dpy);
        }
    }
}
