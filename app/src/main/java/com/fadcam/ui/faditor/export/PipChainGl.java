package com.fadcam.ui.faditor.export;

import android.opengl.GLES20;

import androidx.annotation.NonNull;
import androidx.media3.common.util.GlUtil;

import com.fadcam.ui.faditor.compositor.FxPreviewTextureView;
import com.fadcam.ui.faditor.compositor.PipGl;
import com.fadcam.ui.faditor.fx.FxGlSource;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.List;

/**
 * Composite a bottom-to-top list of still Pips over a Media3 input frame into its output FBO:
 * one full-frame pass per Pip via {@link PipGl#drawStill} (the preview's drawPip), ping-ponging
 * through two frame-sized textures, the last pass writing straight into the output. No Pips =
 * a plain copy. Shared by the export's GL image and caption passes. GL thread only.
 */
final class PipChainGl {
    private static final float[] QUAD = {
            -1f, -1f, 0f, 1f,
             1f, -1f, 0f, 1f,
            -1f,  1f, 0f, 1f,
             1f,  1f, 0f, 1f,
    };

    final PipGl.Programs programs = new PipGl.Programs();
    private final FloatBuffer quad;
    private final int[] pingTex = new int[2];
    private final int[] pingFbo = new int[2];
    private int copyProgram = 0;
    int w = 1, h = 1;

    PipChainGl() {
        quad = ByteBuffer.allocateDirect(QUAD.length * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        quad.put(QUAD).position(0);
    }

    /** (Re)size the ping-pong targets. */
    void configure(int width, int height) throws GlUtil.GlException {
        int nw = Math.max(1, width), nh = Math.max(1, height);
        if (nw == w && nh == h && pingFbo[0] != 0) return;
        releasePing();
        w = nw;
        h = nh;
        for (int i = 0; i < 2; i++) {
            pingTex[i] = GlUtil.createTexture(w, h, false);
            pingFbo[i] = GlUtil.createFboForTexture(pingTex[i]);
        }
    }

    /** The FBO Media3 bound for this frame's output. */
    static int boundFbo() {
        int[] fb = new int[1];
        GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, fb, 0);
        return fb[0];
    }

    /**
     * The flip an external picture needs: a Surface buffer is top-row-first like a bitmap upload,
     * so this is exactly the still variant's {@code (u, 1 - v)}, and the pixels land the same.
     */
    static final float[] FLIP_V = {
            1f, 0f, 0f, 0f,
            0f, -1f, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 1f, 0f, 1f,
    };

    void composite(int inputTexId, int outFbo, @NonNull List<FxPreviewTextureView.Pip> pips,
                   @NonNull List<Integer> stillTex) throws GlUtil.GlException {
        composite(inputTexId, outFbo, pips, stillTex, null);
    }

    /** @param external per Pip: true = its texture is external (OES), fed through a Surface */
    void composite(int inputTexId, int outFbo, @NonNull List<FxPreviewTextureView.Pip> pips,
                   @NonNull List<Integer> stillTex, @androidx.annotation.Nullable List<Boolean> external)
            throws GlUtil.GlException {
        GLES20.glDisable(GLES20.GL_BLEND);
        int n = pips.size();
        if (n == 0) {
            copy(inputTexId, outFbo);
        } else {
            int src = inputTexId;
            for (int i = 0; i < n; i++) {
                boolean last = i == n - 1;
                int dstFbo = last ? outFbo : pingFbo[i % 2];
                boolean oes = external != null && Boolean.TRUE.equals(external.get(i));
                boolean drawn = oes
                        ? PipGl.drawExternal(pips.get(i), programs, src, stillTex.get(i), FLIP_V,
                                dstFbo, w, h, quad)
                        : PipGl.drawStill(pips.get(i), programs, src, stillTex.get(i), dstFbo, w, h,
                                quad);
                if (!drawn) {
                    copy(src, dstFbo);   // a refused program drops the item, not the frame
                }
                src = pingTex[i % 2];
            }
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, outFbo);
        GlUtil.checkGlError();
    }

    private void copy(int srcTex, int dstFbo) {
        if (copyProgram == 0) {
            copyProgram = PipGl.buildProgram(FxGlSource.VERTEX_SHADER,
                    FxGlSource.PASSTHROUGH_FRAGMENT);
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, dstFbo);
        GLES20.glViewport(0, 0, w, h);
        GLES20.glUseProgram(copyProgram);
        int aPos = GLES20.glGetAttribLocation(copyProgram, "aFramePosition");
        if (aPos >= 0) {
            quad.position(0);
            GLES20.glVertexAttribPointer(aPos, 4, GLES20.GL_FLOAT, false, 0, quad);
            GLES20.glEnableVertexAttribArray(aPos);
        }
        int loc = GLES20.glGetUniformLocation(copyProgram, "uTexSampler");
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, srcTex);
        if (loc >= 0) GLES20.glUniform1i(loc, 0);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
    }

    private void releasePing() {
        for (int i = 0; i < 2; i++) {
            try {
                if (pingFbo[i] != 0) GlUtil.deleteFbo(pingFbo[i]);
                if (pingTex[i] != 0) GlUtil.deleteTexture(pingTex[i]);
            } catch (Exception ignored) { }
            pingFbo[i] = 0;
            pingTex[i] = 0;
        }
    }

    void release() {
        releasePing();
        programs.release();
        if (copyProgram != 0) {
            try { GLES20.glDeleteProgram(copyProgram); } catch (RuntimeException ignored) { }
            copyProgram = 0;
        }
    }
}
