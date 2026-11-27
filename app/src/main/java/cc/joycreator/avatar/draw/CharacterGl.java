package cc.joycreator.avatar.draw;

import android.graphics.Bitmap;
import android.opengl.GLES20;
import android.opengl.GLUtils;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import cc.joycreator.avatar.solve.BitmapPics;
import cc.joycreator.avatar.solve.Item;
import cc.joycreator.avatar.solve.SolveOut;

/**
 * Puts a solved character on glass: the web pilot's {@code AL.GL} (tools/avatarlab/al-gl.js) in GLES 2, step for step. The
 * solver ({@code CharacterSolver}) hands over posed meshes; this draws them in order with premultiplied alpha, paints each mask
 * ("show only inside", "hidden where") into an off-screen texture from the masking parts' own soft alpha and multiplies the
 * drawing by it, and switches to the colour-set program only while a colour set is in play.
 *
 * <p>Call everything on one GL thread with a current context. {@link #release()} frees what it made.</p>
 */
public final class CharacterGl {
    /** Character pixels to canvas pixels: canvas = character * s + (ox, oy). */
    public static final class View {
        public final float s, ox, oy;

        public View(float s, float ox, float oy) { this.s = s; this.ox = ox; this.oy = oy; }
    }

    /** A colour laid over one piece: [r, g, b, amount], or null. */
    public interface Tint { float[] of(Item it); }

    private static final class Prog { int pr, p, t, uv, ur, uq, ua, uc, uk, us, um, uh = -1, ug = -1, ux = -1, un = -1; }

    private static final class Tex { int id, ver; }

    private static final class Target { int tex, fb, w, h; }

    private final Map<String, Tex> tex = new HashMap<>();
    private Prog base, col, cur;
    private Target mk, mk2;
    private int vb, tb, ib, w, h;
    private boolean tone, inMask;
    private View view;
    /** a scene draws many things into one picture: do not clear first */
    public boolean keep;

    private int buf() { int[] b = new int[1]; GLES20.glGenBuffers(1, b, 0); return b[0]; }

    private static int shader(int type, String src) {
        int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, src);
        GLES20.glCompileShader(s);
        int[] ok = new int[1];
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) { String log = GLES20.glGetShaderInfoLog(s); GLES20.glDeleteShader(s); throw new IllegalStateException("character shader: " + log); }
        return s;
    }

    private Prog build(String fs, boolean withTone) {
        Prog P = new Prog();
        P.pr = GLES20.glCreateProgram();
        GLES20.glAttachShader(P.pr, shader(GLES20.GL_VERTEX_SHADER, CharacterGlSource.VS));
        GLES20.glAttachShader(P.pr, shader(GLES20.GL_FRAGMENT_SHADER, fs));
        GLES20.glBindAttribLocation(P.pr, 0, "p");
        GLES20.glBindAttribLocation(P.pr, 1, "t");
        GLES20.glLinkProgram(P.pr);
        int[] ok = new int[1];
        GLES20.glGetProgramiv(P.pr, GLES20.GL_LINK_STATUS, ok, 0);
        if (ok[0] == 0) throw new IllegalStateException("character program: " + GLES20.glGetProgramInfoLog(P.pr));
        GLES20.glUseProgram(P.pr);
        P.p = 0; P.t = 1;
        P.uv = GLES20.glGetUniformLocation(P.pr, "uv"); P.ur = GLES20.glGetUniformLocation(P.pr, "ur"); P.uq = GLES20.glGetUniformLocation(P.pr, "uq");
        P.ua = GLES20.glGetUniformLocation(P.pr, "ua"); P.uc = GLES20.glGetUniformLocation(P.pr, "uc"); P.uk = GLES20.glGetUniformLocation(P.pr, "uk");
        P.us = GLES20.glGetUniformLocation(P.pr, "us"); P.um = GLES20.glGetUniformLocation(P.pr, "um");
        if (withTone) { P.uh = GLES20.glGetUniformLocation(P.pr, "uh"); P.ug = GLES20.glGetUniformLocation(P.pr, "ug"); P.ux = GLES20.glGetUniformLocation(P.pr, "ux"); P.un = GLES20.glGetUniformLocation(P.pr, "un"); }
        GLES20.glUniform1i(P.us, 0);
        GLES20.glUniform1i(P.um, 1);
        return P;
    }

    private void ensure() {
        if (base != null) return;
        base = build(CharacterGlSource.FS, false);
        vb = buf(); tb = buf(); ib = buf();
    }

    private int texture(String img, BitmapPics pics) {
        Bitmap b = pics.bitmap(img);
        if (b == null) return 0;
        Tex t = tex.get(img);
        int ver = pics.ver(img);
        if (t != null && t.ver == ver) return t.id;
        if (t != null) GLES20.glDeleteTextures(1, new int[] {t.id}, 0);
        int[] id = new int[1];
        GLES20.glGenTextures(1, id, 0);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id[0]);
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, b, 0);           // a Bitmap is premultiplied, as the pilot uploads its pictures
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        t = new Tex(); t.id = id[0]; t.ver = ver;
        tex.put(img, t);
        return t.id;
    }

    private Target maskTarget(boolean second) {
        Target m = second ? mk2 : mk;
        if (m != null && m.w == w && m.h == h) return m;
        if (m != null) { GLES20.glDeleteTextures(1, new int[] {m.tex}, 0); GLES20.glDeleteFramebuffers(1, new int[] {m.fb}, 0); }
        m = new Target(); m.w = w; m.h = h;
        int[] id = new int[1];
        GLES20.glGenTextures(1, id, 0); m.tex = id[0];
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, m.tex);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glGenFramebuffers(1, id, 0); m.fb = id[0];
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, m.fb);
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, m.tex, 0);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, outFb);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        if (second) mk2 = m; else mk = m;
        return m;
    }

    private int outFb;

    private void one(Item it, double alpha, float[] tc, boolean masked, BitmapPics pics) {
        int t = texture(it.img, pics);
        if (t == 0 || alpha <= 0.003) return;
        Prog L = cur;
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vb);
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, it.v.length * 4, floats(it.v), GLES20.GL_DYNAMIC_DRAW);
        GLES20.glVertexAttribPointer(L.p, 2, GLES20.GL_FLOAT, false, 0, 0);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, tb);
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, it.uv.length * 4, floats(it.uv), GLES20.GL_DYNAMIC_DRAW);
        GLES20.glVertexAttribPointer(L.t, 2, GLES20.GL_FLOAT, false, 0, 0);
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, ib);
        GLES20.glBufferData(GLES20.GL_ELEMENT_ARRAY_BUFFER, it.idx.length * 2, shorts(it.idx), GLES20.GL_DYNAMIC_DRAW);
        GLES20.glUniform4f(L.uc, tc != null ? tc[0] : 0, tc != null ? tc[1] : 0, tc != null ? tc[2] : 0, tc != null ? tc[3] : 0);
        GLES20.glUniform1f(L.ua, (float) alpha);
        GLES20.glUniform1f(L.uk, masked ? 1 : 0);
        if (L.un >= 0) GLES20.glUniform1f(L.un, (tone && !inMask && !it.nc && !it.ghost) ? 1 : 0);
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, it.idx.length, GLES20.GL_UNSIGNED_SHORT, 0);
    }

    /** Paint "how much shows here" for one mask: inside-shape first (or everything), then cut the cutters out. */
    private void paintMask(Item.Mask mask, BitmapPics pics) {
        Target m = maskTarget(false);
        inMask = true;
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, m.fb);
        if (mask.inside != null) {
            GLES20.glClearColor(0, 0, 0, 0); GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA);
            for (Item it : mask.inside) one(it, 1, null, false, pics);
        } else { GLES20.glClearColor(1, 1, 1, 1); GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT); }
        /* more shapes it must ALSO be inside: keep only where every one is */
        if (mask.more != null) for (List<Item> list : mask.more) {
            Target m2 = maskTarget(true);
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, m2.fb); GLES20.glClearColor(0, 0, 0, 0); GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA);
            for (Item it : list) one(it, 1, null, false, pics);
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, m.fb); GLES20.glBlendFunc(GLES20.GL_ZERO, GLES20.GL_SRC_ALPHA); quad(m2.tex);
        }
        GLES20.glBlendFunc(GLES20.GL_ZERO, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        if (mask.cuts != null) for (List<Item> list : mask.cuts) for (Item it : list) one(it, 1, null, false, pics);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, outFb);
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, m.tex); GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        inMask = false;
    }

    /** One picture over the whole canvas (a mask multiplied into another). */
    private void quad(int t) {
        Prog L = cur;
        GLES20.glUniform4f(L.uv, 1, 1, 0, 0);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vb);
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, 32, floats(new float[] {0, 0, w, 0, 0, h, w, h}), GLES20.GL_DYNAMIC_DRAW);
        GLES20.glVertexAttribPointer(L.p, 2, GLES20.GL_FLOAT, false, 0, 0);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, tb);
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, 32, floats(new float[] {0, 1, 1, 1, 0, 0, 1, 0}), GLES20.GL_DYNAMIC_DRAW);
        GLES20.glVertexAttribPointer(L.t, 2, GLES20.GL_FLOAT, false, 0, 0);
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, ib);
        GLES20.glBufferData(GLES20.GL_ELEMENT_ARRAY_BUFFER, 12, shorts(new int[] {0, 1, 2, 1, 3, 2}), GLES20.GL_DYNAMIC_DRAW);
        GLES20.glUniform4f(L.uc, 0, 0, 0, 0); GLES20.glUniform1f(L.ua, 1); GLES20.glUniform1f(L.uk, 0);
        if (L.un >= 0) GLES20.glUniform1f(L.un, 0);
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, 6, GLES20.GL_UNSIGNED_SHORT, 0);
        GLES20.glUniform4f(L.uv, view.s, view.s, view.ox, view.oy);
    }

    /**
     * Draws one frame into the framebuffer {@code fb} (0 = the screen) of size {@code width x height}.
     * {@code tint} may be null; the colour sets come with the frame ({@link SolveOut#toneStops}).
     */
    public void draw(SolveOut out, BitmapPics pics, View v, Tint tint, int fb, int width, int height) {
        ensure();
        w = width; h = height; outFb = fb; view = v;
        boolean toneOn = out.toneStops != null && !out.toneStops.isEmpty();
        Prog P = base;
        if (toneOn) { if (col == null) col = build(CharacterGlSource.FS_TONE, true); P = col; }
        cur = P; tone = toneOn; inMask = false;
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fb);
        GLES20.glViewport(0, 0, w, h);
        if (!keep) { GLES20.glClearColor(0, 0, 0, 0); GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT); }
        GLES20.glUseProgram(P.pr);
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        GLES20.glDisable(GLES20.GL_CULL_FACE);
        GLES20.glUniform4f(P.uv, v.s, v.s, v.ox, v.oy);
        GLES20.glUniform2f(P.ur, w, h);
        GLES20.glUniform2f(P.uq, w, h);
        if (toneOn) toneUniforms(P, out, v);
        GLES20.glEnableVertexAttribArray(P.p);
        GLES20.glEnableVertexAttribArray(P.t);
        String last = null;
        for (Item it : out.items) {
            Item.Mask mk0 = it.mask;
            if (mk0 != null && !mk0.key.equals(last)) { paintMask(mk0, pics); last = mk0.key; }
            one(it, it.a, tint != null ? tint.of(it) : null, mk0 != null, pics);
        }
    }

    private void toneUniforms(Prog L, SolveOut out, View v) {
        float[] uh = new float[32], ug = new float[32];
        for (int i0 = 0; i0 < 8; i0++) {
            double[] q = i0 < out.toneStops.size() ? out.toneStops.get(i0) : null;          // { from, to, sat, val, width, level, soft, blotch }
            if (q == null) { ug[i0 * 4] = 0.01f; ug[i0 * 4 + 1] = -9; ug[i0 * 4 + 2] = 1; continue; }
            double from = q[0], to = q[1], sat = nan(q[2], 1), val = nan(q[3], 1), width = nan(q[4], 40), lv = q[5], soft = nan(q[6], 0.22), blotch = nan(q[7], 0.12);
            double dh = jsMod(jsMod(to - from, 360) + 540, 360) - 180, sf = Math.max(0.02, soft), bl = blotch;
            uh[i0 * 4] = (float) (jsMod(jsMod(from, 360) + 360, 360) / 360); uh[i0 * 4 + 1] = (float) (dh / 360);
            uh[i0 * 4 + 2] = (float) Math.log(Math.max(0.05, sat)); uh[i0 * 4 + 3] = (float) Math.log(Math.max(0.05, val));
            ug[i0 * 4] = (float) (Math.max(4, width) / 360); ug[i0 * 4 + 1] = (float) (lv * (1 + sf + 3 * bl) - 1.5 * bl); ug[i0 * 4 + 2] = (float) sf; ug[i0 * 4 + 3] = (float) bl;
        }
        GLES20.glUniform4fv(L.uh, 8, uh, 0);
        GLES20.glUniform4fv(L.ug, 8, ug, 0);
        double[] b = out.toneBox;
        double cx = (b[0] + b[2]) / 2, cy = (b[1] + b[3]) / 2, R = Math.max(8, Math.sqrt((b[2] - b[0]) * (b[2] - b[0]) + (b[3] - b[1]) * (b[3] - b[1])) / 2);
        double an = out.toneDir * Math.PI / 180, ca = Math.cos(an) / (2 * R), sa = Math.sin(an) / (2 * R);
        GLES20.glUniform4f(L.ux, (float) (ca / v.s), (float) (-sa / v.s), (float) (0.5 + (-v.ox / v.s - cx) * ca + ((h - v.oy) / v.s - cy) * sa), (float) (6.2832 / Math.max(6, R * 0.5 * v.s)));
    }

    private static double nan(double v, double def) { return Double.isNaN(v) ? def : v; }

    /** JavaScript's % (the sign follows the dividend). */
    private static double jsMod(double a, double b) { return a % b; }

    private static FloatBuffer floats(float[] a) {
        FloatBuffer f = ByteBuffer.allocateDirect(a.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        f.put(a).position(0);
        return f;
    }

    private static ShortBuffer shorts(int[] a) {
        ShortBuffer s = ByteBuffer.allocateDirect(a.length * 2).order(ByteOrder.nativeOrder()).asShortBuffer();
        for (int x : a) s.put((short) x);
        s.position(0);
        return s;
    }

    /** Forget the textures (the character's pictures changed, or the surface went away). */
    public void forget() {
        for (Tex t : tex.values()) GLES20.glDeleteTextures(1, new int[] {t.id}, 0);
        tex.clear();
    }

    public void release() {
        forget();
        if (mk != null) { GLES20.glDeleteTextures(1, new int[] {mk.tex}, 0); GLES20.glDeleteFramebuffers(1, new int[] {mk.fb}, 0); mk = null; }
        if (mk2 != null) { GLES20.glDeleteTextures(1, new int[] {mk2.tex}, 0); GLES20.glDeleteFramebuffers(1, new int[] {mk2.fb}, 0); mk2 = null; }
        if (base != null) { GLES20.glDeleteProgram(base.pr); base = null; }
        if (col != null) { GLES20.glDeleteProgram(col.pr); col = null; }
        if (vb != 0) GLES20.glDeleteBuffers(3, new int[] {vb, tb, ib}, 0);
        vb = tb = ib = 0;
    }
}
