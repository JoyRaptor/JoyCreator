package com.fadcam.vislab.gl;

import android.opengl.GLES30;

import androidx.annotation.NonNull;

/**
 * Dual-filter bloom (Bjorge, ARM, SIGGRAPH 2015): downsample with a bright-pass on the first
 * step, then upsample adding each level into the next larger one. Built for phone GPUs: every
 * pass is a handful of bilinear taps at half resolution or less.
 */
final class Bloom {

    private final GlTarget[] levels = new GlTarget[6];
    private int w, h;
    /** How many levels the last {@link #run} used; the Finish pass divides by it. */
    int used = 3;

    void ensure(int fullW, int fullH, @NonNull GlTarget.Format fmt) {
        if (fullW == w && fullH == h && levels[0] != null) return;
        free();
        w = fullW;
        h = fullH;
        int bw = Math.max(1, fullW >> 1), bh = Math.max(1, fullH >> 1);
        for (int i = 0; i < levels.length; i++) {
            levels[i] = GlTarget.create(bw, bh, fmt, 1, true);
            bw = Math.max(1, bw >> 1);
            bh = Math.max(1, bh >> 1);
        }
    }

    int result() {
        return levels[0].tex();
    }

    void run(@NonNull VisRenderer r, @NonNull GlTarget src, float threshold, float radius) {
        int n = 3 + Math.round(Math.max(0f, Math.min(1f, radius)) * 3f);
        used = n;
        GLES30.glDisable(GLES30.GL_BLEND);
        GlProgram down = r.program("fullscreen.vert", "bloom_down.frag");
        int fromTex = src.tex(), fromW = src.w, fromH = src.h;
        for (int i = 0; i < n; i++) {
            GlTarget dst = levels[i];
            r.bindTarget(dst);
            r.useBare(down);
            r.tex(down, "uSrc", fromTex, 0);
            GLES30.glUniform2f(down.u("uHalf"), 0.5f / fromW, 0.5f / fromH);
            GLES30.glUniform4f(down.u("uBloomCfg"), threshold, 0.4f, i == 0 ? 1f : 0f, 0f);
            r.drawFullscreen();
            fromTex = dst.tex();
            fromW = dst.w;
            fromH = dst.h;
        }
        GLES30.glEnable(GLES30.GL_BLEND);
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE);
        GlProgram up = r.program("fullscreen.vert", "bloom_up.frag");
        for (int i = n - 1; i > 0; i--) {
            GlTarget s = levels[i], d = levels[i - 1];
            r.bindTarget(d);
            r.useBare(up);
            r.tex(up, "uSrc", s.tex(), 0);
            GLES30.glUniform2f(up.u("uHalf"), 0.5f / s.w, 0.5f / s.h);
            GLES30.glUniform1f(up.u("uWeight"), 1f);
            r.drawFullscreen();
        }
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA);
    }

    void free() {
        for (int i = 0; i < levels.length; i++) {
            GlTarget.free(levels[i]);
            levels[i] = null;
        }
        w = h = 0;
    }
}
