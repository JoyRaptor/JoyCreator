package com.fadcam.ui.faditor.export;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.PorterDuff;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.util.Size;
import androidx.media3.effect.BitmapOverlay;

import com.fadcam.ui.faditor.model.TextOverlayItem;

/**
 * The frame source feeding {@link ImageBlendGlEffect}: ONE image overlay drawn (positioned,
 * scaled, rotated, faded, wiped) into a frame-sized transparent bitmap per presentation time.
 *
 * <p>Deliberately the same shape as {@link PipFrameOverlay} — positioning stays on the proven
 * Canvas path so the shader can blend with plain full-frame UVs. The picture itself comes from
 * {@link ImageOverlayDraw}, the same call {@link CompositeExportOverlay} makes, so a blended image
 * lands exactly where an unblended one would.</p>
 */
final class ImageOverlayFrameOverlay extends BitmapOverlay {

    private final Context context;
    private final TextOverlayItem item;
    private final long projectDurationMs;
    /** Composition→editor time correction; see {@link PipFrameOverlay}'s note (LEDGER §2d). */
    private final long editorTimeOffsetMs;

    @Nullable private Bitmap bitmap;
    @Nullable private Canvas canvas;
    /** Identity-stable, never mutated — safe for BitmapOverlay's texture cache. */
    @Nullable private Bitmap transparent;
    /** Decoded once for this instance's lifetime. Null AFTER {@link #decoded} means "failed". */
    @Nullable private Bitmap source;
    private boolean decoded;
    private int frameW = 1, frameH = 1;

    ImageOverlayFrameOverlay(@NonNull Context context, @NonNull TextOverlayItem item,
                             long projectDurationMs, long editorTimeOffsetMs) {
        this.context = context.getApplicationContext();
        this.item = item;
        this.projectDurationMs = projectDurationMs;
        this.editorTimeOffsetMs = editorTimeOffsetMs;
    }

    @Override
    public void configure(@NonNull Size videoSize) {
        super.configure(videoSize);
        frameW = Math.max(1, videoSize.getWidth());
        frameH = Math.max(1, videoSize.getHeight());
    }

    @NonNull
    @Override
    public Bitmap getBitmap(long presentationTimeUs) {
        if (bitmap == null || bitmap.getWidth() != frameW || bitmap.getHeight() != frameH) {
            bitmap = Bitmap.createBitmap(frameW, frameH, Bitmap.Config.ARGB_8888);
            canvas = new Canvas(bitmap);
            transparent = Bitmap.createBitmap(frameW, frameH, Bitmap.Config.ARGB_8888);
        }
        long timelineMs = presentationTimeUs / 1000 + editorTimeOffsetMs;   // →editor time (§2d)
        // Empty frames return the IDENTITY-STABLE transparent bitmap rather than the cleared
        // scratch: BitmapOverlay re-uploads only on instance change, so handing back the mutated
        // scratch would leave the LAST drawn frame on screen past the overlay's window — the
        // exact trap PipFrameOverlay documents.
        if (!item.isVisibleAt(timelineMs)) return transparent;
        if (!decoded) {
            decoded = true;
            source = ImageOverlayDraw.decode(context, item, frameW, frameH);
            if (source == null) {
                com.fadcam.FLog.w("ImageOverlayFrameOverlay", "image overlay " + item.getId()
                        + " decoded to null — its blend will render as nothing");
            }
        }
        if (source == null || source.isRecycled()) return transparent;

        canvas.drawColor(0, PorterDuff.Mode.CLEAR);
        if (!ImageOverlayDraw.draw(canvas, source, item, timelineMs, projectDurationMs,
                frameW, frameH, 1f)) {
            return transparent;
        }
        // NEW instance per frame, matching PipFrameOverlay. The patched BitmapOverlay also
        // compares getGenerationId(), so reusing the scratch would in fact re-upload — but the
        // in-repo precedent copies, the failure mode if that reading is wrong is a frozen first
        // frame for the whole clip, and this is not the change to find that out on. If the copy
        // is ever removed, remove it from BOTH classes in one commit and prove it on a device.
        return Bitmap.createBitmap(bitmap);
    }

    @Override
    public void release() throws androidx.media3.common.VideoFrameProcessingException {
        super.release();
        if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
        bitmap = null;
        canvas = null;
        if (transparent != null && !transparent.isRecycled()) transparent.recycle();
        transparent = null;
        if (source != null && !source.isRecycled()) source.recycle();
        source = null;
    }
}
