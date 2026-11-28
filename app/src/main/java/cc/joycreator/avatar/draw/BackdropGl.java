package cc.joycreator.avatar.draw;

import android.graphics.Bitmap;
import android.opengl.GLES20;
import android.opengl.GLUtils;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * What is behind the character on the glass when the frame is on: Video Studio's own canvas texture over black (scaled to cover, as
 * {@code CanvasFrameView} paints it) and, where the picture is, the plain dark stage (the pilot's radial dark with its quiet
 * grid). It is drawn on the GL surface because the surface sits behind the window's views: whatever stands in the picture, or
 * part-way out of it, is drawn over this by the character's own drawing ({@link CharacterGl#keep}).
 *
 * <p>Call on the GL thread. {@link #release()} frees the texture (the surface went away).</p>
 */
public final class BackdropGl {
    private static final String VS = "attribute vec2 p; attribute vec2 t; attribute vec4 c; uniform vec2 sz; varying vec2 vt; varying vec4 vc;"
            + "void main(){ gl_Position = vec4(p.x/sz.x*2.0-1.0, 1.0-p.y/sz.y*2.0, 0.0, 1.0); vt = t; vc = c; }";
    private static final String FS = "precision mediump float; uniform sampler2D tx; uniform float useTex; varying vec2 vt; varying vec4 vc;"
            + "void main(){ vec4 col = useTex > 0.5 ? vec4(texture2D(tx, vt).rgb, 1.0) : vc; gl_FragColor = col; }";

    private int prog, aP, aT, aC, uSz, uTex, uUse, texId;
    private Bitmap pending;
    private int texW, texH;
    private final FloatBuffer buf = ByteBuffer.allocateDirect(4 * 8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();

    /** The texture to use: handed over once, uploaded on the GL thread when next drawn. */
    public void setTexture(Bitmap b) {
        pending = b;
    }

    private static int shader(int type, String src) {
        int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, src);
        GLES20.glCompileShader(s);
        return s;
    }

    private void ensure() {
        if (prog != 0) return;
        prog = GLES20.glCreateProgram();
        GLES20.glAttachShader(prog, shader(GLES20.GL_VERTEX_SHADER, VS));
        GLES20.glAttachShader(prog, shader(GLES20.GL_FRAGMENT_SHADER, FS));
        GLES20.glLinkProgram(prog);
        aP = GLES20.glGetAttribLocation(prog, "p");
        aT = GLES20.glGetAttribLocation(prog, "t");
        aC = GLES20.glGetAttribLocation(prog, "c");
        uSz = GLES20.glGetUniformLocation(prog, "sz");
        uTex = GLES20.glGetUniformLocation(prog, "tx");
        uUse = GLES20.glGetUniformLocation(prog, "useTex");
    }

    private void upload() {
        Bitmap b = pending;
        if (b == null) return;
        pending = null;
        if (texId == 0) {
            int[] t = new int[1];
            GLES20.glGenTextures(1, t, 0);
            texId = t[0];
        }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, b, 0);
        texW = b.getWidth();
        texH = b.getHeight();
    }

    private void quad(float x0, float y0, float x1, float y1, float[] uv, float[] topLeft, float[] bottomLeft) {
        // 4 vertices: x, y, u, v, r, g, b, a  (a triangle strip: TL, TR, BL, BR)
        float[] v = {
                x0, y0, uv[0], uv[1], topLeft[0], topLeft[1], topLeft[2], 1,
                x1, y0, uv[2], uv[1], topLeft[0], topLeft[1], topLeft[2], 1,
                x0, y1, uv[0], uv[3], bottomLeft[0], bottomLeft[1], bottomLeft[2], 1,
                x1, y1, uv[2], uv[3], bottomLeft[0], bottomLeft[1], bottomLeft[2], 1};
        FloatBuffer f = ByteBuffer.allocateDirect(v.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        f.put(v).position(0);
        int stride = 8 * 4;
        f.position(0);
        GLES20.glVertexAttribPointer(aP, 2, GLES20.GL_FLOAT, false, stride, f);
        f.position(2);
        GLES20.glVertexAttribPointer(aT, 2, GLES20.GL_FLOAT, false, stride, f);
        f.position(4);
        GLES20.glVertexAttribPointer(aC, 4, GLES20.GL_FLOAT, false, stride, f);
        GLES20.glEnableVertexAttribArray(aP);
        GLES20.glEnableVertexAttribArray(aT);
        GLES20.glEnableVertexAttribArray(aC);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
    }

    /**
     * Paints the whole surface: black, the texture (cover), then the plain dark stage inside {@code pic} (x0, y0, x1, y1 in pixels).
     * The blend is switched off here and left for the character's drawing to set again.
     */
    public void draw(int width, int height, float[] pic) {
        ensure();
        upload();
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        GLES20.glViewport(0, 0, width, height);
        GLES20.glClearColor(0, 0, 0, 1);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        GLES20.glDisable(GLES20.GL_BLEND);
        GLES20.glUseProgram(prog);
        GLES20.glUniform2f(uSz, width, height);
        float[] black = {0, 0, 0};
        if (texId != 0 && texW > 0) {
            float sc = Math.max(width / (float) texW, height / (float) texH), fu = width / (texW * sc), fv = height / (texH * sc);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId);
            GLES20.glUniform1i(uTex, 0);
            GLES20.glUniform1f(uUse, 1f);
            quad(0, 0, width, height, new float[]{(1 - fu) / 2, (1 - fv) / 2, (1 + fu) / 2, (1 + fv) / 2}, black, black);
        }
        if (pic != null) {
            GLES20.glUniform1f(uUse, 0f);
            // the pilot's stage: #1a1a22 high in the picture, #0d0d10 in the middle, #050507 at the foot (a vertical wash)
            quad(pic[0], pic[1], pic[2], pic[3], new float[]{0, 0, 1, 1}, new float[]{0.078f, 0.078f, 0.098f}, new float[]{0.027f, 0.027f, 0.04f});
        }
    }

    public void release() {
        if (texId != 0) {
            GLES20.glDeleteTextures(1, new int[]{texId}, 0);
            texId = 0;
        }
        prog = 0;
    }
}
