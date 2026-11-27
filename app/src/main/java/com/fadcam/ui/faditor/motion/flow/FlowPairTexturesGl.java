package com.fadcam.ui.faditor.motion.flow;

import android.opengl.GLES20;

import androidx.media3.common.util.GlUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Owns the two packed RGBA8 textures for one exact analysed presentation pair. */
public final class FlowPairTexturesGl {
    private final int[] textures = new int[2];
    private final int[] saved = new int[2];
    private FlowCache.Pair resident;
    private HighFootageGatherGl.Pair handle;
    private boolean released;

    /**
     * Uploads an exact increasing-PTS pair, or returns null after clearing an unusable gap/freeze.
     * Reusing the same immutable resident pair performs no GL upload.
     */
    public HighFootageGatherGl.Pair set(FlowCache.Pair pair) throws GlUtil.GlException {
        requireOpen();
        if (!usable(pair)) {
            clear();
            return null;
        }
        if (resident == pair) return handle;

        GLES20.glGetIntegerv(GLES20.GL_ACTIVE_TEXTURE, saved, 0);
        GLES20.glGetIntegerv(GLES20.GL_TEXTURE_BINDING_2D, saved, 1);
        try {
            ensureTextures();
            upload(textures[0], pair.forward);
            upload(textures[1], pair.backward);
            GlUtil.checkGlError();
            resident = pair;
            handle = new HighFootageGatherGl.Pair(textures[0], textures[1],
                    pair.forward.fromPtsUs, pair.forward.toPtsUs);
            return handle;
        } catch (RuntimeException | GlUtil.GlException failure) {
            clear();
            throw failure;
        } finally {
            GLES20.glActiveTexture(saved[0]);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, saved[1]);
        }
    }

    /** Deletes owned names while the context is current. Safe to call repeatedly. */
    public void release() {
        if (released) return;
        clear();
        released = true;
    }

    /** For a context that has already been lost: forget stale names without issuing GL calls. */
    public void abandon() {
        if (released) return;
        textures[0] = textures[1] = 0;
        resident = null;
        handle = null;
        released = true;
    }

    private static boolean usable(FlowCache.Pair pair) {
        return pair != null && !pair.has(FlowCache.Flag.LONG_GAP)
                && !pair.has(FlowCache.Flag.DUPLICATE)
                && !pair.crossesCut()
                && pair.forward.fromPtsUs < pair.forward.toPtsUs;
    }

    private void ensureTextures() {
        if (textures[0] != 0) return;
        GLES20.glGenTextures(2, textures, 0);
        if (textures[0] <= 0 || textures[1] <= 0) {
            throw new IllegalStateException("Could not allocate footage flow textures");
        }
    }

    private static void upload(int texture, FlowField field) {
        byte[] rgba = FlowTexturePixels.rgba(field);
        ByteBuffer pixels = ByteBuffer.allocateDirect(rgba.length).order(ByteOrder.nativeOrder());
        pixels.put(rgba).flip();
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, field.width, field.height,
                0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels);
    }

    private void clear() {
        if (textures[0] != 0 || textures[1] != 0) GLES20.glDeleteTextures(2, textures, 0);
        textures[0] = textures[1] = 0;
        resident = null;
        handle = null;
    }

    private void requireOpen() {
        if (released) throw new IllegalStateException("Flow textures have been released");
    }
}
