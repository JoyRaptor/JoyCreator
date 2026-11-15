package com.fadcam.ui.faditor.fx;

import android.opengl.GLES20;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.ThreadLocalRandom;

/**
 * One export's Echo Trails memory, shared by every clip's effect chain so trails run on across a
 * cut (VL-07), the way the live preview's single {@link FxHistory} already does.
 *
 * <p>Media3 builds a fresh shader program for every clip of a sequence, on the same GL context.
 * A history owned by that program started empty at each clip boundary. The programs borrow this
 * one instead; when the last borrower gives it back (the moment between two clips) it is kept,
 * not freed, for the next clip's program to borrow a few milliseconds later.</p>
 *
 * <p><b>Only the context that made the pictures may use them.</b> A new export brings a new
 * context, possibly at the same handle, whose texture names mean something else entirely. So
 * the history carries a 1x1 picture holding a random colour, read back on every borrow: only
 * the context that made it can return that colour. Anything else starts a clean history and
 * forgets (never deletes) the old names, which died with their context. While another live
 * export holds it, a borrower gets null and keeps its own, the old per-clip behaviour.</p>
 *
 * <p>GL thread only.</p>
 */
public final class SharedFxHistory {

    private SharedFxHistory() { }

    @Nullable private static FxHistory history;
    private static int users;
    private static int tokenTex, tokenFbo, tokenRgba;

    /** The shared history, or null while another context's export holds it. */
    @Nullable
    public static synchronized FxHistory borrow() {
        if (history != null) {
            if (madeHere()) {
                users++;
                return history;
            }
            if (users > 0) return null;
            history.forget();
            history = null;
        }
        if (!makeToken()) return null;
        history = new FxHistory();
        users = 1;
        return history;
    }

    /** A program is done with {@code h}. Kept for the next clip; its context frees it at the end. */
    public static synchronized void giveBack(@NonNull FxHistory h) {
        if (h == history) users = Math.max(0, users - 1);
    }

    /** True when the current context is the one that made the token (and so the history). */
    private static boolean madeHere() {
        if (tokenFbo == 0 || !GLES20.glIsFramebuffer(tokenFbo) || !GLES20.glIsTexture(tokenTex)) {
            return false;
        }
        int[] prev = new int[1];
        GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, prev, 0);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, tokenFbo);
        boolean ok = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
                == GLES20.GL_FRAMEBUFFER_COMPLETE;
        if (ok) {
            ByteBuffer px = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder());
            GLES20.glReadPixels(0, 0, 1, 1, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, px);
            ok = px.getInt(0) == tokenRgba;
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, prev[0]);
        drainOwnErrors();
        return ok;
    }

    /** A fresh 1x1 picture of a random colour in the current context; false if GL refused. */
    private static boolean makeToken() {
        ByteBuffer px = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder());
        px.putInt(0, ThreadLocalRandom.current().nextInt());
        tokenRgba = px.getInt(0);
        int[] id = new int[1];
        int[] prevFbo = new int[1];
        int[] prevTex = new int[1];
        GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, prevFbo, 0);
        GLES20.glGetIntegerv(GLES20.GL_TEXTURE_BINDING_2D, prevTex, 0);
        GLES20.glGenTextures(1, id, 0);
        tokenTex = id[0];
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tokenTex);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST);
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, 1, 1, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, px);
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 4);
        GLES20.glGenFramebuffers(1, id, 0);
        tokenFbo = id[0];
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, tokenFbo);
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D, tokenTex, 0);
        boolean ok = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
                == GLES20.GL_FRAMEBUFFER_COMPLETE;
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, prevFbo[0]);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, prevTex[0]);
        drainOwnErrors();
        if (!ok) {
            GLES20.glDeleteFramebuffers(1, new int[]{tokenFbo}, 0);
            GLES20.glDeleteTextures(1, new int[]{tokenTex}, 0);
            tokenFbo = 0;
            tokenTex = 0;
        }
        return ok;
    }

    /** The probes above may raise errors of their own; never leave them for the next step. */
    private static void drainOwnErrors() {
        for (int i = 0; i < 8 && GLES20.glGetError() != GLES20.GL_NO_ERROR; i++) { /* drain */ }
    }
}
