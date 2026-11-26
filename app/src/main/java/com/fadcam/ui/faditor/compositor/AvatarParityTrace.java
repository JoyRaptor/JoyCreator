package com.fadcam.ui.faditor.compositor;

import android.opengl.GLES20;

import androidx.media3.common.util.GlUtil;

import com.fadcam.FLog;
import com.fadcam.ui.faditor.motion.MotionShutter;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.zip.CRC32;

/** Instrumentation for the isolated avatar proof APK. */
public final class AvatarParityTrace {
    private static final ThreadLocal<ByteBuffer> PIXELS = new ThreadLocal<>();

    private AvatarParityTrace() {}

    public static void capture(String path, long editorUs, long periodUs, MotionShutter.Schedule schedule,
                               int base, int stamp, int output, int width, int height) {
        int[] prior = new int[1];
        GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, prior, 0);
        try {
            long baseCrc = textureCrc(base, width, height);
            long stampCrc = textureCrc(stamp, width, height);
            long outputCrc = framebufferCrc(output, width, height);
            FLog.i("AvatarParity", path + " editorUs=" + editorUs + " periodUs=" + periodUs
                    + " taps=" + Arrays.toString(schedule.timesUs())
                    + " weights=" + Arrays.toString(schedule.weights())
                    + " base=" + baseCrc + " stamp=" + stampCrc + " output=" + outputCrc);
        } catch (GlUtil.GlException | RuntimeException error) {
            FLog.w("AvatarParity", "Could not read proof texture", error);
        } finally {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, prior[0]);
        }
    }

    private static long textureCrc(int texture, int width, int height) throws GlUtil.GlException {
        int fbo = GlUtil.createFboForTexture(texture);
        try {
            return framebufferCrc(fbo, width, height);
        } finally {
            GlUtil.deleteFbo(fbo);
        }
    }

    private static long framebufferCrc(int fbo, int width, int height) throws GlUtil.GlException {
        int bytes = Math.multiplyExact(Math.multiplyExact(width, height), 4);
        ByteBuffer buffer = PIXELS.get();
        if (buffer == null || buffer.capacity() < bytes) {
            buffer = ByteBuffer.allocateDirect(bytes);
            PIXELS.set(buffer);
        }
        buffer.clear().limit(bytes);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo);
        GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer);
        GlUtil.checkGlError();
        var crc = new CRC32();
        crc.update(buffer);
        return crc.getValue();
    }
}
