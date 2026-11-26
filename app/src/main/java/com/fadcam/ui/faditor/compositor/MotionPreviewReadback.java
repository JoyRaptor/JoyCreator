package com.fadcam.ui.faditor.compositor;

import android.content.Context;
import android.graphics.Bitmap;
import android.opengl.GLES20;
import android.view.TextureView;
import android.view.View;

import androidx.annotation.Nullable;

import com.fadcam.FLog;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;

/** Debug-only reads of the compositor buffer or its last presented texture. */
public final class MotionPreviewReadback {
    public static final String EXTRA_PAUSED_SEEK_MS = "studio_debug_motion_paused_seek_ms";
    private static final String TAG = "MotionPreviewReadback";
    private final File folder;
    private final long earliestUs;

    public MotionPreviewReadback(Context context, long earliestUs) {
        folder = context.getCacheDir();
        this.earliestUs = earliestUs;
    }

    boolean accepts(long timeUs) {
        return timeUs >= earliestUs;
    }

    void capture(int framebuffer, int width, int height, long timeUs) {
        ByteBuffer pixels = ByteBuffer.allocateDirect(Math.multiplyExact(Math.multiplyExact(width, height), 4));
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer);
        GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels);
        new Thread(() -> save(pixels, width, height, timeUs), "studio-motion-readback").start();
    }

    /** Read the held output without requesting a compositor draw that might hide a stale frame. */
    public static void capturePresented(Context context, @Nullable TextureView view, long editorMs) {
        if (!com.fadcam.BuildConfig.DEBUG) return;
        if (view == null || view.getVisibility() != View.VISIBLE || !view.isAvailable()) {
            FLog.w(TAG, "Paused seek has no presented texture at editorMs=" + editorMs);
            return;
        }
        Bitmap image = view.getBitmap();
        if (image == null) {
            FLog.w(TAG, "Paused seek returned no pixels at editorMs=" + editorMs);
            return;
        }
        File png = new File(context.getCacheDir(), "motion_paused_" + editorMs + ".png");
        new Thread(() -> {
            try (FileOutputStream stream = new FileOutputStream(png)) {
                if (!image.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                    throw new java.io.IOException("Could not encode presented preview");
                }
                FLog.i(TAG, "frame=" + png.getName() + " editorMs=" + editorMs
                        + " size=" + image.getWidth() + "x" + image.getHeight());
            } catch (Exception e) {
                FLog.w(TAG, "Presented preview capture failed", e);
            } finally {
                image.recycle();
            }
        }, "studio-motion-paused-readback").start();
    }

    private void save(ByteBuffer pixels, int width, int height, long timeUs) {
        Bitmap image = null;
        try {
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
            File png = new File(folder, "motion_preview_" + timeUs + ".png");
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
}
