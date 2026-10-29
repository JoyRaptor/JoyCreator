package com.fadcam.ui.faditor.fx;

import android.opengl.GLES20;

import androidx.annotation.NonNull;

import java.util.HashMap;
import java.util.Map;

/**
 * The previous frame of each FEEDBACK pass (Echo Trails), kept between frames by the export
 * ({@code AdjustmentLayerGlEffect}) and the live preview ({@code FxPreviewTextureView}) alike.
 *
 * <p>Two frame-sized pictures per pass: this frame writes one while reading the other, then they
 * swap. The history only counts across consecutive moments: the first frame, a jump of more
 * than {@link #JUMP_MS} (a seek, a gap where the layer was off) or a new size starts again from
 * the picture itself ({@link FxCompiler#U_PREV_VALID} = 0), so trails never carry over from
 * somewhere else on the timeline. Drawing the same moment again (paused) shows one ordinary step
 * over the current picture without advancing the history (see {@link #begin}).</p>
 *
 * <p>GL thread only. Plain GLES20, so both renderers share it.</p>
 */
public final class FxHistory {

    /** A longer gap between two drawn frames is a jump, not playback. */
    static final long JUMP_MS = 250;

    /** One pass's history: where this frame writes, what it reads, and the step since then. */
    public static final class Target {
        final int[] tex = new int[2];
        final int[] fbo = new int[2];
        int w, h, cur;
        boolean filled;
        long lastMs = Long.MIN_VALUE;
        /** Set by {@link #begin}. */
        boolean valid, hold;
        float dt;

        /** The framebuffer this frame draws the pass into. */
        public int writeFbo() { return fbo[cur]; }
        /** The picture this frame wrote, for the next pass to read once it is drawn. */
        public int writeTex() { return tex[cur]; }
        /** Last frame's picture. */
        public int prevTex() { return tex[1 - cur]; }
        /** 1 when {@link #prevTex} really is last frame's output, else 0. */
        public float prevValid() { return valid ? 1f : 0f; }
        /** Seconds since last frame (one 1/30 s step when the same moment is drawn again). */
        public float dt() { return dt; }
    }

    @NonNull private final Map<String, Target> targets = new HashMap<>();

    /**
     * Ready {@code key}'s history for a frame at {@code editorMs}, {@code w x h}. Call before
     * drawing the pass, then {@link #end} once it is drawn.
     */
    @NonNull
    public Target begin(@NonNull String key, int w, int h, long editorMs) {
        Target t = targets.get(key);
        if (t == null) {
            t = new Target();
            targets.put(key, t);
        }
        if (t.w != w || t.h != h || t.tex[0] == 0) {
            free(t);
            for (int i = 0; i < 2; i++) {
                int[] id = new int[1];
                GLES20.glGenTextures(1, id, 0);
                t.tex[i] = id[0];
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id[0]);
                GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0,
                        GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
                GLES20.glGenFramebuffers(1, id, 0);
                t.fbo[i] = id[0];
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, id[0]);
                GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                        GLES20.GL_TEXTURE_2D, t.tex[i], 0);
            }
            t.w = w;
            t.h = h;
            t.filled = false;
        }
        long gap = t.lastMs == Long.MIN_VALUE ? -1 : editorMs - t.lastMs;
        t.valid = t.filled && gap >= 0 && gap <= JUMP_MS;
        // The same moment drawn again (paused, a slider being dragged): show one ordinary step
        // of trails over the CURRENT picture, so the edit is visible, and keep the history as it
        // was (end() does not commit), so pausing neither freezes nor smears the trails.
        t.hold = t.valid && gap == 0;
        t.dt = t.valid && !t.hold ? gap / 1000f : 1f / 30f;
        return t;
    }

    /** The pass is drawn: what it wrote becomes next frame's history (unless it was a redraw). */
    public void end(@NonNull Target t, long editorMs) {
        if (t.hold) return;
        t.filled = true;
        t.lastMs = editorMs;
        t.cur = 1 - t.cur;
    }

    /** Delete every history picture (the renderer is done, or the frame size changed). */
    public void release() {
        for (Target t : targets.values()) free(t);
        targets.clear();
    }

    /** The GL context went away with the pictures in it: forget them without deleting. */
    public void forget() {
        targets.clear();
    }

    private static void free(@NonNull Target t) {
        for (int i = 0; i < 2; i++) {
            if (t.fbo[i] != 0) GLES20.glDeleteFramebuffers(1, new int[]{t.fbo[i]}, 0);
            if (t.tex[i] != 0) GLES20.glDeleteTextures(1, new int[]{t.tex[i]}, 0);
            t.fbo[i] = 0;
            t.tex[i] = 0;
        }
        t.filled = false;
    }
}
