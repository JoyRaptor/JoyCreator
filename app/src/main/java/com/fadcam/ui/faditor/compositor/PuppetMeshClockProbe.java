package com.fadcam.ui.faditor.compositor;

import android.opengl.GLES20;

import com.fadcam.FLog;

import java.nio.ByteBuffer;
import java.util.zip.CRC32;

/** One-frame diagnostic for the bundled authored-puppet fixture. */
public final class PuppetMeshClockProbe {
    private PuppetMeshClockProbe() {}

    public static boolean at(long editorUs) {
        return com.fadcam.BuildConfig.DEBUG && editorUs == 1_233_333L;
    }

    public static void record(String route, long editorUs, long localMs, float cx, float cy,
                              float w, float h, float rotation, int baseTexture, int baseFbo,
                              int width, int height) {
        int[] binding = new int[1];
        GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, binding, 0);
        int framebuffer = baseFbo;
        int[] scratch = null;
        try {
            if (framebuffer == 0) {
                scratch = new int[1];
                GLES20.glGenFramebuffers(1, scratch, 0);
                framebuffer = scratch[0];
                if (framebuffer == 0) return;
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer);
                GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                        GLES20.GL_TEXTURE_2D, baseTexture, 0);
            } else {
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer);
            }
            if (GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
                    != GLES20.GL_FRAMEBUFFER_COMPLETE) return;
            ByteBuffer pixels = ByteBuffer.allocateDirect(width * height * 4);
            GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels);
            CRC32 crc = new CRC32();
            crc.update(pixels);
            FLog.i("PuppetMeshClock", route + " editorUs=" + editorUs + " localMs=" + localMs
                    + " pose=" + cx + "," + cy + "," + w + "," + h + "," + rotation
                    + " base=" + crc.getValue());
        } finally {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, binding[0]);
            if (scratch != null) GLES20.glDeleteFramebuffers(1, scratch, 0);
        }
    }
}
