package com.fadcam.ui.faditor.export;

import android.opengl.GLES20;

import androidx.media3.common.GlTextureInfo;
import androidx.media3.common.util.GlUtil;

import com.fadcam.BuildConfig;
import com.fadcam.ui.faditor.motion.MotionShutter;
import com.fadcam.ui.faditor.motion.flow.HighFootageGatherGl;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Properties;

/** One archived calibration frame on the isolated debug fixture branch. */
final class HighFootageInputReadback {
    private HighFootageInputReadback() {}

    static void capture(File cache, HighFootageGatherGl.Frame frame, GlTextureInfo output,
                        int flowWidth, int flowHeight, long editorUs, MotionShutter.Schedule schedule)
            throws GlUtil.GlException, IOException {
        if (!BuildConfig.DEBUG) return;
        File folder = new File(cache, "native-high-" + editorUs);
        if (!folder.isDirectory() && !folder.mkdir()) throw new IOException("Cannot create readback folder");

        int[] framebuffer = new int[1], viewport = new int[4], texture = new int[1];
        GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, framebuffer, 0);
        GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, viewport, 0);
        GLES20.glGetIntegerv(GLES20.GL_TEXTURE_BINDING_2D, texture, 0);
        var before = frame.previousToCurrent();
        var after = frame.currentToNext();
        Properties receipt = new Properties();
        receipt.setProperty("editorUs", Long.toString(editorUs));
        receipt.setProperty("previousUs", Long.toString(before.fromPtsUs()));
        receipt.setProperty("currentUs", Long.toString(frame.currentPtsUs()));
        receipt.setProperty("nextUs", Long.toString(after.toPtsUs()));
        receipt.setProperty("sourceSpeed", Double.toString(frame.sourceSpeed()));
        receipt.setProperty("timesUs", Arrays.toString(schedule.timesUs()));
        receipt.setProperty("weights", Arrays.toString(schedule.weights()));
        receipt.setProperty("blend", Boolean.toString(GLES20.glIsEnabled(GLES20.GL_BLEND)));
        receipt.setProperty("packAlignment", Integer.toString(getInteger(GLES20.GL_PACK_ALIGNMENT)));
        try {
            read(folder, receipt, "previous", frame.previousTexture(), output.width, output.height);
            read(folder, receipt, "current", frame.currentTexture(), output.width, output.height);
            read(folder, receipt, "next", frame.nextTexture(), output.width, output.height);
            read(folder, receipt, "before-forward", before.forwardTexture(), flowWidth, flowHeight);
            read(folder, receipt, "before-backward", before.backwardTexture(), flowWidth, flowHeight);
            read(folder, receipt, "after-forward", after.forwardTexture(), flowWidth, flowHeight);
            read(folder, receipt, "after-backward", after.backwardTexture(), flowWidth, flowHeight);
            read(folder, receipt, "gathered", output.texId, output.width, output.height);
        } finally {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer[0]);
            GLES20.glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture[0]);
        }
        try (var stream = new FileOutputStream(new File(folder, "inputs.properties"))) {
            receipt.store(stream, null);
        }
    }

    private static int getInteger(int parameter) {
        int[] value = new int[1];
        GLES20.glGetIntegerv(parameter, value, 0);
        return value[0];
    }

    private static void read(File folder, Properties receipt, String name, int texture,
                             int width, int height) throws GlUtil.GlException, IOException {
        int[] filter = new int[2];
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
        GLES20.glGetTexParameteriv(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, filter, 0);
        GLES20.glGetTexParameteriv(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, filter, 1);
        receipt.setProperty(name, width + "," + height + "," + filter[0] + "," + filter[1]);

        ByteBuffer pixels = ByteBuffer.allocateDirect(Math.multiplyExact(Math.multiplyExact(width, height), 4));
        int framebuffer = GlUtil.createFboForTexture(texture);
        try {
            GlUtil.focusFramebufferUsingCurrentContext(framebuffer, width, height);
            GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels);
            GlUtil.checkGlError();
        } finally {
            GlUtil.deleteFbo(framebuffer);
        }
        pixels.position(0);
        try (var stream = new FileOutputStream(new File(folder, name + ".rgba"))) {
            var channel = stream.getChannel();
            while (pixels.hasRemaining()) channel.write(pixels);
        }
    }
}
