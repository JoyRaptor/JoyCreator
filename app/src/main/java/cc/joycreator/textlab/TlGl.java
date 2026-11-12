package cc.joycreator.textlab;

import android.opengl.GLES30;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.vislab.gl.GlTarget;
import com.fadcam.vislab.gl.ShaderLib;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * TextLab's small GL layer, the twin of {@code TL.Gl} in {@code tools/textlab-web/tl-gl.js}:
 * assemble a shader by the one contract, make render targets (VisLab's {@link GlTarget}, with a
 * depth buffer added for the field), upload the engine's float pictures, draw a fullscreen pass.
 * GL thread only.
 */
final class TlGl {

    /** A render target, and the depth buffer the field needs (0 when it has none). */
    static final class Target {
        final GlTarget t;
        final int depth;
        final int w, h;

        Target(GlTarget t, int depth) {
            this.t = t;
            this.depth = depth;
            this.w = t.w;
            this.h = t.h;
        }

        int fbo() {
            return t.fb;
        }

        int tex() {
            return t.tex();
        }
    }

    static final String PRECISION = "precision highp float;\nprecision highp int;\nprecision highp sampler2D;\n";

    private final ShaderLib lib;
    private final Map<String, TlProgram> programs = new HashMap<>();
    private final int vao;

    TlGl(@NonNull ShaderLib lib) {
        this.lib = lib;
        TlProgram.noise = noiseTexture();
        int[] id = new int[1];
        GLES30.glGenVertexArrays(1, id, 0);
        vao = id[0];
        GLES30.glBindVertexArray(vao);
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1);
    }

    /** THE ASSEMBLY CONTRACT: the same as {@code TL.assemble} and VisLab's ShaderLib. */
    @NonNull
    String assemble(@NonNull String name, @NonNull List<String> defines) {
        StringBuilder d = new StringBuilder();
        for (String x : defines) d.append("#define ").append(x).append('\n');
        return lib.assemble(name, d.toString());
    }

    @NonNull
    TlProgram program(@NonNull String vert, @NonNull String frag, @NonNull List<String> defines) {
        String key = vert + "|" + frag + "|" + String.join(",", defines);
        TlProgram p = programs.get(key);
        if (p == null) {
            p = new TlProgram(assemble(vert, defines), assemble(frag, defines),
                    frag + " [" + String.join(" ", defines) + "]");
            programs.put(key, p);
        }
        return p;
    }

    /** kind: half (RGBA16F, the usual) or byte (RGBA8). depth: a depth buffer too. */
    @NonNull
    Target target(double w, double h, boolean half, boolean depth) {
        int iw = Math.max(1, (int) Math.round(w)), ih = Math.max(1, (int) Math.round(h));
        GlTarget t = GlTarget.create(iw, ih, half ? GlTarget.RGBA16F : GlTarget.RGBA8, 1, true);
        int rb = 0;
        if (depth) {
            int[] id = new int[1];
            GLES30.glGenRenderbuffers(1, id, 0);
            rb = id[0];
            GLES30.glBindRenderbuffer(GLES30.GL_RENDERBUFFER, rb);
            GLES30.glRenderbufferStorage(GLES30.GL_RENDERBUFFER, GLES30.GL_DEPTH_COMPONENT24, iw, ih);
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, t.fb);
            GLES30.glFramebufferRenderbuffer(GLES30.GL_FRAMEBUFFER, GLES30.GL_DEPTH_ATTACHMENT,
                    GLES30.GL_RENDERBUFFER, rb);
            int st = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER);
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
            if (st != GLES30.GL_FRAMEBUFFER_COMPLETE) {
                t.free();
                GLES30.glDeleteRenderbuffers(1, id, 0);
                throw new IllegalStateException("this GPU cannot draw into a field with depth");
            }
        }
        return new Target(t, rb);
    }

    /** The smaller, blurrier copies of a target's picture (a satin surface reads those). */
    void mips(@NonNull Target t) {
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, t.tex());
        GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR_MIPMAP_LINEAR);
    }

    void free(@Nullable Target t) {
        if (t == null) return;
        t.t.free();
        if (t.depth != 0) GLES30.glDeleteRenderbuffers(1, new int[]{t.depth}, 0);
    }

    static void params(int filter) {
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, filter);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, filter);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE);
    }

    private static int newTexture() {
        int[] id = new int[1];
        GLES30.glGenTextures(1, id, 0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, id[0]);
        return id[0];
    }

    static void deleteTexture(int tex) {
        if (tex != 0) GLES30.glDeleteTextures(1, new int[]{tex}, 0);
    }

    private static FloatBuffer floats(@NonNull float[] data) {
        FloatBuffer b = ByteBuffer.allocateDirect(data.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        b.put(data).position(0);
        return b;
    }

    /** Two channels of floats (a piece's distance and its extra fact). Rows bottom to top. */
    int pairTexture(int w, int h, @NonNull float[] data) {
        int tex = newTexture();
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RG16F, w, h, 0, GLES30.GL_RG, GLES30.GL_FLOAT, floats(data));
        params(GLES30.GL_LINEAR);
        return tex;
    }

    /** Four exact floats per texel, never filtered (the emitter's point list). */
    int pointTexture(int w, int h, @NonNull float[] data, int old) {
        int tex = old;
        if (tex == 0) tex = newTexture();
        else GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex);
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA32F, w, h, 0, GLES30.GL_RGBA, GLES30.GL_FLOAT, floats(data));
        params(GLES30.GL_NEAREST);
        return tex;
    }

    /**
     * The engine's dice: 256 x 256 random bytes, repeating, from a fixed recipe (xorshift32 from
     * 0x9E3779B9, the top byte of each step) so every device rolls the same picture.
     */
    static int noiseTexture() {
        int n = 256;
        ByteBuffer data = ByteBuffer.allocateDirect(n * n);
        int x = 0x9e3779b9;
        for (int i = 0; i < n * n; i++) {
            x ^= x << 13;
            x ^= x >>> 17;
            x ^= x << 5;
            data.put((byte) (x >>> 24));
        }
        data.position(0);
        int tex = newTexture();
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1);
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_R8, n, n, 0, GLES30.GL_RED, GLES30.GL_UNSIGNED_BYTE, data);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_REPEAT);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_REPEAT);
        return tex;
    }

    /** A picture the person gave (the photo stage), as an RGBA8 texture on {@code unit}. */
    static int bitmapTexture(@NonNull android.graphics.Bitmap bmp, int old) {
        int tex = old;
        if (tex == 0) tex = newTexture();
        else GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex);
        android.opengl.GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bmp, 0);
        params(GLES30.GL_LINEAR);
        return tex;
    }

    void bind(@Nullable Target t, int w, int h) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, t != null ? t.fbo() : 0);
        GLES30.glViewport(0, 0, t != null ? t.w : w, t != null ? t.h : h);
    }

    void bind(@NonNull Target t) {
        bind(t, 0, 0);
    }

    /** A fullscreen pass into {@code t}. */
    void pass(@NonNull Target t) {
        bind(t);
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3);
    }

    void release() {
        for (TlProgram p : programs.values()) p.delete();
        programs.clear();
        deleteTexture(TlProgram.noise);
        TlProgram.noise = 0;
        GLES30.glDeleteVertexArrays(1, new int[]{vao}, 0);
    }
}
