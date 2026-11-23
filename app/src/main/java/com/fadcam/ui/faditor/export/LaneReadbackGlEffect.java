package com.fadcam.ui.faditor.export;

import android.content.Context;
import android.opengl.GLES20;

import androidx.annotation.NonNull;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.util.GlUtil;
import androidx.media3.common.util.Size;
import androidx.media3.effect.BaseGlShaderProgram;
import androidx.media3.effect.GlEffect;

import com.fadcam.FLog;
import com.fadcam.ui.faditor.compositor.lane.LaneCompositor;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;

/** Debug-only, pre-encode evidence. The RGBA bytes are in OpenGL bottom-up row order. */
final class LaneReadbackGlEffect implements GlEffect {
    static final class Samples {
        private final long[] editorTimesUs;
        private final boolean[] captured;

        Samples(long[] editorTimesUs) {
            this.editorTimesUs = java.util.Arrays.copyOf(editorTimesUs,
                    Math.min(editorTimesUs.length, 3));
            captured = new boolean[this.editorTimesUs.length];
        }

        synchronized long claim(long editorUs) {
            for (int i = 0; i < editorTimesUs.length; i++) {
                long request = editorTimesUs[i];
                if (!captured[i] && request >= 0 && request <= editorUs
                        && editorUs - request < 100_000L) {
                    captured[i] = true;
                    return request;
                }
            }
            return -1L;
        }
    }

    private final File folder;
    private final long editorOffsetUs;
    private final Samples samples;

    LaneReadbackGlEffect(Context context, long editorOffsetMs, Samples samples) {
        folder = context.getCacheDir();
        editorOffsetUs = editorOffsetMs * 1000L;
        this.samples = samples;
    }

    @NonNull
    @Override
    public BaseGlShaderProgram toGlShaderProgram(@NonNull Context ignored, boolean hdr)
            throws VideoFrameProcessingException {
        if (hdr) throw new VideoFrameProcessingException("Lane readback requires SDR");
        return new BaseGlShaderProgram(false, 2) {
            private final LaneCompositor copy = new LaneCompositor();
            private int width;
            private int height;

            @Override
            public boolean shouldClearTextureBuffer() { return false; }

            @NonNull
            @Override
            public Size configure(int inputWidth, int inputHeight) throws VideoFrameProcessingException {
                width = inputWidth;
                height = inputHeight;
                try {
                    copy.configure(width, height);
                    return new Size(width, height);
                } catch (GlUtil.GlException error) {
                    throw new VideoFrameProcessingException(error);
                }
            }

            @Override
            public void drawFrame(int inputTexture, long presentationTimeUs)
                    throws VideoFrameProcessingException {
                try {
                    copy.drawCopy(inputTexture, PipChainGl.boundFbo());
                    long editorUs = presentationTimeUs + editorOffsetUs;
                    long requested = samples.claim(editorUs);
                    if (requested >= 0L) capture(width, height, requested, editorUs);
                } catch (GlUtil.GlException error) {
                    throw new VideoFrameProcessingException(error);
                }
            }

            @Override
            public void release() throws VideoFrameProcessingException {
                try {
                    super.release();
                } finally {
                    try {
                        copy.release();
                    } catch (GlUtil.GlException error) {
                        throw new VideoFrameProcessingException(error);
                    }
                }
            }
        };
    }

    private void capture(int width, int height, long requested, long editorUs) throws GlUtil.GlException {
        int byteCount = Math.multiplyExact(Math.multiplyExact(width, height), 4);
        ByteBuffer pixels = ByteBuffer.allocateDirect(byteCount);
        GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels);
        GlUtil.checkGlError();
        pixels.position(0);
        new Thread(() -> {
            File out = new File(folder, "lane_frame_" + requested + ".rgba");
            try (FileOutputStream stream = new FileOutputStream(out)) {
                byte[] chunk = new byte[65_536];
                while (pixels.hasRemaining()) {
                    int count = Math.min(pixels.remaining(), chunk.length);
                    pixels.get(chunk, 0, count);
                    stream.write(chunk, 0, count);
                }
            } catch (java.io.IOException error) {
                FLog.w("LaneReadback", "Could not save " + out.getName(), error);
                return;
            }
            FLog.i("LaneReadback", "frame=" + out.getName() + " editorUs=" + editorUs
                    + " size=" + width + "x" + height);
        }, "lane-frame-readback").start();
    }
}
