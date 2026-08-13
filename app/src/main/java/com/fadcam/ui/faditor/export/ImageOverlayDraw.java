package com.fadcam.ui.faditor.export;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;

import androidx.annotation.NonNull;

import com.fadcam.ui.faditor.model.TextOverlayItem;
import com.fadcam.ui.faditor.transcript.CaptionAnimator;

/**
 * ONE place that draws an image overlay onto an export canvas.
 *
 * <p>Extracted from {@link CompositeExportOverlay} when {@link ImageOverlayFrameOverlay} needed
 * the identical picture on its own frame-sized bitmap so a blend mode could composite it against
 * the video in GL. Two copies of this arithmetic is exactly how the preview and the export drift:
 * the geometry here is MIRRORED from {@code TextOverlayLayer.position}, and a second transcription
 * of it would have to be kept in step with both.</p>
 *
 * <p>Nothing about the destination is assumed except its size, so the same call serves the shared
 * composite canvas (where the image lands among its sibling overlays) and a dedicated transparent
 * frame (where it becomes a texture).</p>
 */
final class ImageOverlayDraw {

    private ImageOverlayDraw() {}

    /**
     * Decode an image overlay's source, downsampled so the decode is bounded by the OUTPUT frame
     * rather than by the source file: a 12-megapixel photo dropped on a 480p export would
     * otherwise be held at full size for every frame of the clip. The bound is the frame's larger
     * dimension, so an overlay scaled up to fill the frame still has pixels to spare.
     *
     * <p>Shared with {@link ImageOverlayFrameOverlay} so a blended image is decoded at the same
     * resolution as an unblended one — a different sample size would make the blend the only
     * thing that changed the image's sharpness.</p>
     *
     * @return the bitmap, or null (the CALLER logs; it knows which overlay and why it cared).
     */
    @androidx.annotation.Nullable
    static Bitmap decode(@NonNull android.content.Context context, @NonNull TextOverlayItem o,
                         int outW, int outH) {
        try {
            android.net.Uri uri = android.net.Uri.parse(o.getImageUri());
            android.graphics.BitmapFactory.Options bounds =
                    new android.graphics.BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (java.io.InputStream in = context.getContentResolver().openInputStream(uri)) {
                android.graphics.BitmapFactory.decodeStream(in, null, bounds);
            }
            int maxEdge = Math.max(outW, outH);
            int sample = 1;
            while (bounds.outHeight / (sample * 2) >= maxEdge
                    && bounds.outWidth / (sample * 2) >= 1) {
                sample *= 2;
            }
            android.graphics.BitmapFactory.Options opts =
                    new android.graphics.BitmapFactory.Options();
            opts.inSampleSize = sample;
            try (java.io.InputStream in = context.getContentResolver().openInputStream(uri)) {
                return android.graphics.BitmapFactory.decodeStream(in, null, opts);
            }
        } catch (Throwable t) {
            // Logged HERE, with the stack, because only this frame knows why the decode failed;
            // the caller can only report that it got nothing back.
            com.fadcam.FLog.w("ImageOverlayDraw", "image overlay " + o.getId()
                    + " could not be decoded from " + o.getImageUri(), t);
            return null;
        }
    }

    /**
     * Draw {@code img} for {@code o} at {@code timelineMs}. No-op for an item that is not
     * visible, fully transparent, or not an image — the callers' gates are duplicated here on
     * purpose, so a new caller cannot forget one.
     *
     * @param extraAlpha multiplied into the paint alpha. 1f for the canvas path; the GL path
     *                   passes 1f too and lets the shader mix by the resulting alpha.
     * @return true if anything was drawn.
     */
    static boolean draw(@NonNull Canvas canvas, @NonNull Bitmap img, @NonNull TextOverlayItem o,
                        long timelineMs, long projectDurationMs, int outW, int outH,
                        float extraAlpha) {
        if (!o.isImage() || !o.isVisibleAt(timelineMs)) return false;
        float opacity = o.animatedOpacity(timelineMs) * extraAlpha;
        if (opacity <= 0.001f) return false;

        float cx = o.animatedCenterX(timelineMs) * outW;
        float cy = o.animatedCenterY(timelineMs) * outH;
        float sizeFrac = o.animatedSizeFraction(timelineMs);
        float rot = o.animatedRotation(timelineMs);

        CaptionAnimator.Transform ianim = CaptionAnimator.textBoxTransformAt(
                CaptionAnimator.parsePreset(o.getTextAnimPreset()),
                timelineMs, o.motionRangeStartMs(), o.motionSpanMs(projectDurationMs),
                o.getTextAnimInPct(), o.getTextAnimOutPct(), sizeFrac * outH);
        float aspect = img.getHeight() > 0 ? img.getWidth() / (float) img.getHeight() : 1f;
        // Per-axis scale, mirroring TextOverlayLayer.position: a split Scale X/Y pair stretches
        // one axis; linked keeps both multipliers at 1 so existing projects are byte-identical.
        float sx = o.animatedScaleX(timelineMs);
        float sy = o.animatedScaleY(timelineMs);
        float ih = Math.max(1f, sizeFrac * sy * outH);
        float iw = Math.max(1f, ih * aspect * sx);
        Paint ip = new Paint(Paint.FILTER_BITMAP_FLAG);
        // The preview composes the preset's alpha OVER the keyframed opacity ("compose, don't
        // replace"), so this multiplies rather than picking one.
        int ia = Math.round(opacity * ianim.alpha * 255f);
        ip.setAlpha(Math.max(0, Math.min(255, ia)));
        canvas.save();
        // Same order as the text path and as the preview's View properties.
        canvas.translate(ianim.dx, ianim.dy);
        canvas.rotate(rot, cx, cy);
        canvas.scale(ianim.scaleX, ianim.scaleY, cx, cy);
        // MASK_WIPE's reveal. No ink-pad inset here, unlike the text path: that pad is a
        // TextOverlayRenderer artefact (transparent margin round the glyphs), and an image's
        // drawn rect IS its bounds — which is also what the preview clips.
        if (ianim.revealFrac < 1f) {
            canvas.clipRect(cx - iw / 2f, cy - ih / 2f,
                    cx - iw / 2f + iw * Math.max(0f, ianim.revealFrac), cy + ih / 2f);
        }
        // Compositing masks (§C family) — the same canvas-normalized shapes a PiP masks with,
        // through the same builder, so an image and a PiP cannot mask differently. Opened here,
        // INSIDE the item's own save/restore but around the draw itself.
        //
        // objectKf is deliberately NULL. Mask keys and mask LINK bases are captured in absolute
        // timeline ms, while an image's transform keys are LOCAL to its start — handing the local
        // set in as a link source would resolve every mask key against the wrong clock. That is
        // the same reason the drawer offers no "Move with the object" row for an image's mask.
        com.fadcam.ui.faditor.model.MaskPathBuilder.MaskScope maskSave =
                com.fadcam.ui.faditor.model.MaskPathBuilder.beginMask(
                        canvas, o.getCompositing(), null, timelineMs, outW, outH, 0f, 0f);
        canvas.drawBitmap(img,
                new android.graphics.Rect(0, 0, img.getWidth(), img.getHeight()),
                new android.graphics.RectF(cx - iw / 2f, cy - ih / 2f,
                        cx + iw / 2f, cy + ih / 2f), ip);
        com.fadcam.ui.faditor.model.MaskPathBuilder.endMask(canvas, maskSave);
        canvas.restore();
        return true;
    }
}
