package com.fadcam.ui.faditor.compositor;

import android.content.Context;
import android.graphics.Bitmap;
import android.opengl.GLES20;

import com.fadcam.FLog;
import com.fadcam.ui.faditor.motion.PreviewMotionClock;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;

/** One debug-only read of the pre-present compositor output on a decoded frame. */
public final class MotionPreviewReadback {
    private static final String TAG = "MotionPreviewReadback";
    private final File folder;
    private final long earliestUs;
    private final long exactSourcePtsUs;
    private final boolean expectHigh;

    public MotionPreviewReadback(Context context, long earliestUs) {
        this(context, earliestUs, -1, false);
    }

    public static MotionPreviewReadback pausedFootage(Context context, long exactSourcePtsUs,
                                                      boolean expectHigh) {
        return new MotionPreviewReadback(context, 0, exactSourcePtsUs, expectHigh);
    }

    private MotionPreviewReadback(Context context, long earliestUs, long exactSourcePtsUs,
                                  boolean expectHigh) {
        folder = context.getCacheDir();
        this.earliestUs = earliestUs;
        this.exactSourcePtsUs = exactSourcePtsUs;
        this.expectHigh = expectHigh;
    }

    boolean accepts(PreviewMotionClock.Frame frame, boolean highDrawn, boolean currentFrame) {
        if (exactSourcePtsUs < 0) return frame.editorTimeUs() >= earliestUs;
        return currentFrame && frame.decodedPtsUs() == exactSourcePtsUs && highDrawn == expectHigh;
    }

    void capture(int framebuffer, int width, int height, long timeUs) {
        ByteBuffer pixels = ByteBuffer.allocateDirect(Math.multiplyExact(Math.multiplyExact(width, height), 4));
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer);
        GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels);
        new Thread(() -> save(pixels, width, height, timeUs), "studio-motion-readback").start();
    }

    private void save(ByteBuffer pixels, int width, int height, long timeUs) {
        Bitmap image = null;
        try {
            String name = exactSourcePtsUs >= 0
                    ? "motion_paused_" + (expectHigh ? "high_" : "sharp_") + exactSourcePtsUs
                    : "motion_preview_" + timeUs;
            if (exactSourcePtsUs >= 0) saveRaw(pixels.duplicate(), name);
            int[] argb = new int[width * height];
            for (int y = 0; y < height; y++) {
                int dst = (height - 1 - y) * width;
                for (int x = 0; x < width; x++) {
                    int r = pixels.get() & 255;
                    int g = pixels.get() & 255;
                    int b = pixels.get() & 255;
                    int a = pixels.get() & 255;
                    argb[dst + x] = (a << 24) | (r << 16) | (g << 8) | b;
                }
            }
            image = Bitmap.createBitmap(argb, width, height, Bitmap.Config.ARGB_8888);
            File png = new File(folder, name + ".png");
            try (FileOutputStream stream = new FileOutputStream(png)) {
                if (!image.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                    throw new java.io.IOException("Could not encode preview frame");
                }
            }
            FLog.i(TAG, "frame=" + png.getName() + " editorUs=" + timeUs + " size=" + width + "x" + height);
        } catch (Exception e) {
            FLog.w(TAG, "Preview frame capture failed", e);
        } finally {
            if (image != null) image.recycle();
        }
    }

    /** Same bottom-up RGBA layout as the pre-encode export readback. */
    private void saveRaw(ByteBuffer pixels, String name) throws java.io.IOException {
        try (FileOutputStream stream = new FileOutputStream(new File(folder, name + ".rgba"))) {
            byte[] chunk = new byte[65_536];
            while (pixels.hasRemaining()) {
                int count = Math.min(pixels.remaining(), chunk.length);
                pixels.get(chunk, 0, count);
                stream.write(chunk, 0, count);
            }
        }
    }
}
