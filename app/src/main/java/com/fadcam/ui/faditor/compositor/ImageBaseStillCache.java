package com.fadcam.ui.faditor.compositor;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.FLog;
import com.fadcam.ui.faditor.model.Clip;

import java.io.InputStream;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * The decoded picture of the IMAGE master clip under the playhead, for
 * {@link FxPreviewTextureView#setBaseStill}.
 *
 * <p><b>Why this exists.</b> An image clip never reaches the player, so the live FX chain — which
 * takes the decoder's surface — could not see it: an adjustment layer graded every video clip and
 * left the photo untouched, while the export graded both (image clips get the same
 * {@code videoEffects} list). Handing the chain a bitmap is what closes that gap, and something
 * has to own that bitmap.</p>
 *
 * <p><b>ONE bitmap at a time.</b> Only the clip under the playhead can be the base, and a full
 * photo is expensive; a second entry would buy nothing but memory. Crossing to a different image
 * clip retires the old one, crossing to a VIDEO clip does not — the cache is kept so scrubbing
 * back and forth over a cut does not re-decode on every pass.</p>
 *
 * <p><b>Retirement is deferred, not immediate.</b> A bitmap handed to the chain may be mid
 * {@code texImage2D} on the GL thread, and freeing pixel memory under an upload is a native crash
 * no try/catch reaches. Retired bitmaps go to the view's {@code stillTrash}, which the GL thread
 * drains itself — the same discipline {@code OverlayVideoPreviewView.recycleStill} follows.</p>
 */
public final class ImageBaseStillCache {

    private static final String TAG = "FxImageBase";

    /**
     * A cap, not a target. The chain runs at whatever size this bitmap is, so a 48-megapixel photo
     * would allocate three RGBA FBOs of that size and run every effect pass over them. 1920 is the
     * same order as the video clips the chain already handles.
     */
    private static final int MAX_EDGE = 1920;

    /** Fired on the main thread when a decode lands, so the caller can push a fresh tick. */
    public interface Ready {
        void onBaseStillReady();
    }

    @NonNull private final Context ctx;
    @NonNull private final Ready ready;
    @NonNull private final Handler main = new Handler(Looper.getMainLooper());
    @NonNull private final ExecutorService io = Executors.newSingleThreadExecutor();

    @Nullable private Bitmap have;
    @Nullable private String haveId;
    @Nullable private String pendingId;
    /**
     * The clip whose decode failed, so a missing or corrupt file is attempted once rather than
     * once per playhead tick. Latched on the ID, not a boolean, so the next image clip still gets
     * its chance — the same shape as {@code FxPreviewTextureView.failedKey}.
     */
    @Nullable private String failedId;
    @Nullable private ConcurrentLinkedQueue<Bitmap> trash;
    private boolean released;

    public ImageBaseStillCache(@NonNull Context ctx, @NonNull Ready ready) {
        this.ctx = ctx.getApplicationContext();
        this.ready = ready;
    }

    /** @see FxPreviewTextureView#stillTrash() */
    public void setTrash(@Nullable ConcurrentLinkedQueue<Bitmap> q) {
        this.trash = q;
    }

    /**
     * {@code clip}'s picture if it is already decoded, else null while a decode is kicked. A null
     * answer simply means the chain stages the decoder this frame, which is the same "absent until
     * ready" behaviour the PiP stills have.
     */
    @Nullable
    public Bitmap bitmapFor(@Nullable Clip clip) {
        if (released || clip == null || !clip.isImageClip()) return null;
        Uri uri = clip.getSourceUri();
        if (uri == null) return null;
        String id = clip.getId();
        if (id.equals(haveId) && have != null && !have.isRecycled()) return have;
        if (id.equals(failedId)) return null;
        if (!id.equals(pendingId)) {
            pendingId = id;
            decodeAsync(id, uri);
        }
        return null;
    }

    private void decodeAsync(@NonNull String id, @NonNull Uri uri) {
        try {
            io.execute(() -> {
                final Bitmap bmp = decode(uri);
                main.post(() -> {
                    if (released) {
                        if (bmp != null) bmp.recycle();
                        return;
                    }
                    if (!id.equals(pendingId)) {   // the playhead moved on while we decoded
                        retire(bmp);
                        return;
                    }
                    pendingId = null;
                    if (bmp == null) { failedId = id; return; }
                    Bitmap old = have;
                    have = bmp;
                    haveId = id;
                    // PUBLISH FIRST, retire second. onBaseStillReady drives a sync that hands the
                    // NEW bitmap to the GL thread; only after that is the old one certainly no
                    // longer the one the chain is about to upload.
                    ready.onBaseStillReady();
                    retire(old);
                });
            });
        } catch (RejectedExecutionException ignored) {
            // Released — images simply stop refreshing.
        }
    }

    /**
     * Decode {@code uri} downsampled to {@link #MAX_EDGE}, EXIF orientation applied.
     *
     * <p>EXIF is not optional: media3's own bitmap loader applies it on export, so a phone photo
     * decoded raw here would preview sideways against an upright render. {@code displaySize} in
     * the editor already reads the same tag for the same reason.</p>
     */
    @Nullable
    private Bitmap decode(@NonNull Uri uri) {
        try {
            android.graphics.BitmapFactory.Options bounds =
                    new android.graphics.BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream is = ctx.getContentResolver().openInputStream(uri)) {
                android.graphics.BitmapFactory.decodeStream(is, null, bounds);
            }
            int w = bounds.outWidth, h = bounds.outHeight;
            if (w <= 0 || h <= 0) return null;
            android.graphics.BitmapFactory.Options opts =
                    new android.graphics.BitmapFactory.Options();
            opts.inSampleSize = 1;
            while (Math.max(w, h) / opts.inSampleSize > MAX_EDGE) opts.inSampleSize *= 2;
            Bitmap raw;
            try (InputStream is = ctx.getContentResolver().openInputStream(uri)) {
                raw = android.graphics.BitmapFactory.decodeStream(is, null, opts);
            }
            if (raw == null) return null;
            int rot = exifRotation(uri);
            if (rot == 0) return raw;
            android.graphics.Matrix m = new android.graphics.Matrix();
            m.postRotate(rot);
            Bitmap out = Bitmap.createBitmap(raw, 0, 0, raw.getWidth(), raw.getHeight(), m, true);
            if (out != raw) raw.recycle();   // never published, so a direct recycle is safe
            return out;
        } catch (Exception | OutOfMemoryError e) {
            FLog.w(TAG, "image base decode failed for " + uri, e);
            return null;
        }
    }

    private int exifRotation(@NonNull Uri uri) {
        try (InputStream is = ctx.getContentResolver().openInputStream(uri)) {
            if (is == null) return 0;
            int o = new androidx.exifinterface.media.ExifInterface(is).getAttributeInt(
                    androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION,
                    androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL);
            if (o == androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90) return 90;
            if (o == androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_180) return 180;
            if (o == androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_270) return 270;
        } catch (Exception ignored) { }
        return 0;
    }

    /** @see #trash — a published bitmap is freed by the GL thread, never here. */
    private void retire(@Nullable Bitmap b) {
        if (b == null || b.isRecycled()) return;
        ConcurrentLinkedQueue<Bitmap> q = trash;
        if (q != null) q.offer(b); else b.recycle();
    }

    /**
     * Activity teardown. What is held still goes through {@link #retire} rather than a direct
     * recycle — the GL thread's own release drains that queue, and the ordering between the two
     * teardowns is not guaranteed.
     */
    public void release() {
        released = true;
        io.shutdownNow();
        retire(have);
        have = null;
        haveId = null;
        pendingId = null;
    }
}
