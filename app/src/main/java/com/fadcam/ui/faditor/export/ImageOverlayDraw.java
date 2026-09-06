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
     * Absolute ceiling on the decoded long edge for one image overlay — a 4K-class edge, the same
     * number the preview cache uses. The effective ceiling is usually tighter: see
     * {@link #EXPORT_DECODE_EDGE_MULTIPLE}.
     */
    private static final int EXPORT_DECODE_CEILING_EDGE = 4096;

    /**
     * How far past the OUTPUT frame one overlay may be decoded.
     *
     * <p><b>Deliberately modest, because the export's bitmap cache is unbounded.</b>
     * {@code CompositeExportOverlay} holds one decoded bitmap per image overlay for the whole
     * clip in a plain HashMap, and JoyRaptor's project has 78 of them. Doubling the linear resolution
     * quadruples the memory, so an unlimited zoom term here would be a blur bug traded for an
     * OOM. Two is the honest compromise: it is a visible sharpness gain on anything blown up past
     * the frame, and it is bounded.</p>
     *
     * <p><b>An un-zoomed image is completely unaffected.</b> The bound's FLOOR is the frame's own
     * long edge — exactly what shipped — so an overlay that is not scaled past the frame decodes
     * the same bitmap it always did and the 78-image project's memory does not move at all. Only
     * the images that were actually blurry cost anything more.</p>
     */
    private static final int EXPORT_DECODE_EDGE_MULTIPLE = 2;

    /**
     * Hard ceiling on ONE overlay's decoded bitmap, in bytes.
     *
     * <p>An edge bound says nothing about memory until it meets an aspect ratio — a panorama can
     * satisfy any edge and still be enormous — so the byte cap is the backstop. 32 MB is roughly
     * three 1080p frame buffers: generous for one picture, and small enough that several of them
     * in the cache above do not sink the render.</p>
     */
    private static final long EXPORT_DECODE_MAX_BYTES = 32L * 1024 * 1024;

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
            // HOW LARGE IS IT ACTUALLY DRAWN? The old bound was max(outW, outH) with NO zoom
            // term at all — so a chart the user scaled to six times the frame decoded at one
            // frame's worth of pixels and was then magnified six times into the file. The
            // preview had a zoom term (reading, as it happens, the wrong channel); the export
            // had none, which made the export the blurrier of the two.
            //
            // PipFrameOverlay already does this correctly for a PiP (`frameW * scale`); this is
            // the same idea through the item's own drawn-size factor, which folds sizeFraction,
            // its keyframe track, the per-axis multipliers and any corner-pin excursion into one
            // number that the preview cache reads too (TextOverlayItem.maxDrawnHeightFactor).
            float aspect = bounds.outHeight > 0
                    ? bounds.outWidth / (float) bounds.outHeight : 1f;
            float needH = outH * Math.max(0f, o.maxDrawnHeightFactor());
            float needW = outH * aspect * Math.max(0f, o.maxDrawnWidthFactor());
            int floorEdge = Math.max(outW, outH);   // never smaller than what shipped
            int ceilEdge = Math.min(floorEdge * EXPORT_DECODE_EDGE_MULTIPLE,
                    EXPORT_DECODE_CEILING_EDGE);
            int maxEdge = Math.max(floorEdge,
                    Math.min((int) Math.ceil(Math.max(needW, needH)), ceilEdge));
            int sample = 1;
            while (bounds.outHeight / (sample * 2) >= maxEdge
                    && bounds.outWidth / (sample * 2) >= 1) {
                sample *= 2;
            }
            // BYTE GUARD. The bound above is a resolution, and a resolution says nothing about
            // memory until it meets the source's aspect ratio — a very wide panorama can satisfy
            // a 4096 edge and still be enormous. An export holds one of these per image overlay
            // for the whole render, so the cap is per image and hard.
            while (sample < 64
                    && (long) (bounds.outWidth / sample) * (bounds.outHeight / sample) * 4L
                            > EXPORT_DECODE_MAX_BYTES) {
                sample *= 2;
            }
            android.graphics.BitmapFactory.Options opts =
                    new android.graphics.BitmapFactory.Options();
            opts.inSampleSize = sample;
            Bitmap raw;
            try (java.io.InputStream in = context.getContentResolver().openInputStream(uri)) {
                raw = android.graphics.BitmapFactory.decodeStream(in, null, opts);
            }
            if (raw == null) return null;
            // EXIF ORIENTATION. BitmapFactory ignores the tag, so a phone photo stored 3:4 with a
            // "rotate 90" flag decodes as a PORTRAIT bitmap here while every viewer — including the
            // editor's own preview — shows it landscape. The export then drew it in the stored
            // shape: an image the user placed as 4:3 landscape came out 3:4 and visibly squashed
            // (JoyRaptor, 2026-08-13). ImageBaseStillCache states the rule for image CLIPS ("EXIF is
            // not optional: media3's own bitmap loader applies it on export"), and displaySize
            // reads the same tag; this decoder — the one behind image OVERLAYS on BOTH export
            // paths — was the only one that skipped it.
            int rot = exifRotation(context, uri);
            if (rot == 0) return raw;
            android.graphics.Matrix m = new android.graphics.Matrix();
            m.postRotate(rot);
            Bitmap out = Bitmap.createBitmap(raw, 0, 0, raw.getWidth(), raw.getHeight(), m, true);
            if (out != raw) raw.recycle();   // never published, so a direct recycle is safe
            return out;
        } catch (Throwable t) {
            // Logged HERE, with the stack, because only this frame knows why the decode failed;
            // the caller can only report that it got nothing back.
            com.fadcam.FLog.w("ImageOverlayDraw", "image overlay " + o.getId()
                    + " could not be decoded from " + o.getImageUri(), t);
            return null;
        }
    }

    /**
     * Degrees this image must be rotated by to be seen the right way up, from its EXIF tag.
     *
     * <p>Read from a SECOND stream rather than the decode stream: {@code ExifInterface} consumes
     * what it reads, and a shared stream would leave the decoder either empty-handed or at the
     * wrong offset. Same shape as {@code ImageBaseStillCache.exifRotation} — deliberately the same
     * four cases and the same silent fallback to 0, because an unreadable tag must cost an image
     * its rotation, never its appearance in the export.</p>
     */
    private static int exifRotation(@NonNull android.content.Context context,
                                    @NonNull android.net.Uri uri) {
        try (java.io.InputStream is = context.getContentResolver().openInputStream(uri)) {
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
        // Per-axis scale, mirroring TextOverlayLayer's imageHeightPx/imageWidthPx: a split Scale
        // X/Y pair stretches one axis; linked keeps both multipliers at 1 so existing projects
        // are byte-identical.
        //
        // EACH AXIS FROM THE SAME UNSCALED BASE. The width used to be derived from the already-
        // scaled HEIGHT — `ih * aspect * sx` — which put sy into the width too, so width scaled
        // by sx*sy against a height scaling by sy. A uniform enlargement came out stretched.
        // See imageHeightPx for the full note and for why this is inert at scaleY == 1.
        float sx = o.animatedScaleX(timelineMs);
        float sy = o.animatedScaleY(timelineMs);
        float base = sizeFrac * outH;
        float ih = Math.max(1f, base * sy);
        float iw = Math.max(1f, base * aspect * sx);
        Paint ip = new Paint(Paint.FILTER_BITMAP_FLAG);
        // The preview composes the preset's alpha OVER the keyframed opacity ("compose, don't
        // replace"), so this multiplies rather than picking one.
        int ia = Math.round(opacity * ianim.alpha * 255f);
        ip.setAlpha(Math.max(0, Math.min(255, ia)));
        canvas.save();
        // ── THE MASK IS OPENED FIRST, BEFORE ANY OF THE ITEM'S OWN TRANSFORMS ────────────────
        //
        // It used to be opened AFTER the rotate and the scale, and that was a real bug: a mask
        // is authored in FRAME space and must stay put over the frame while the item moves under
        // it — the invariant both PiP paths state in as many words. Opened under the rotation,
        // the hole rotated WITH the picture, so rotating a masked image spun its own mask.
        // PipFrameOverlay (~157) has always done it in this order, and MaskPathBuilder.MaskScope
        // is built for it: its `content` save exists precisely to hold "the caller's transforms"
        // so endMask can drop them and erase the feather in mask space. Passing a transformed
        // canvas into beginMask defeated that too, which is why a feathered mask on a rotated
        // image softened the wrong edge.
        //
        // objectKf is deliberately NULL. Mask keys and mask LINK bases are captured in absolute
        // timeline ms, while an image's transform keys are LOCAL to its start — handing the local
        // set in as a link source would resolve every mask key against the wrong clock. That is
        // the same reason the drawer offers no "Move with the object" row for an image's mask.
        com.fadcam.ui.faditor.model.MaskPathBuilder.MaskScope maskSave =
                com.fadcam.ui.faditor.model.MaskPathBuilder.beginMask(
                        canvas, o.getCompositing(), null, timelineMs, outW, outH, 0f, 0f);
        // Same order as the text path and as the preview's View properties.
        //
        // SPEC B — the rotation pivot. The anchors come from the model's ONE shared definition
        // (TextOverlayItem.pivotOffsetFromCentreX/Y), the same numbers the preview's View pivot
        // and GL fold read, so a corner pivot spins about the corner here exactly as it does in
        // the editor. At the centre pivot the offsets are 0 and both anchors are (cx, cy) — the
        // identical matrix every project before this exported. The entrance scale moves to the
        // same anchor on purpose: the preview's View has ONE pivot shared by rotation and its
        // preset scale properties, so anchoring the scale here too is what keeps the two
        // surfaces composing identically when a pivot is set.
        //
        // SPEC B, pinned pictures — the pivot anchors to the PINNED quad (the picture the user
        // sees), evaluated at this frame's clock, exactly as the preview's fold does.
        float[] pins = null;
        if (o.hasCornerPin()) {
            pins = new float[com.fadcam.ui.faditor.model.CornerPin.SIZE];
            o.animatedCornerPin(timelineMs, pins);
        }
        float pvx = cx + o.pivotOffsetFromCentreX(iw, ih, pins);
        float pvy = cy + o.pivotOffsetFromCentreY(iw, ih, pins);
        canvas.translate(ianim.dx, ianim.dy);
        canvas.rotate(rot, pvx, pvy);
        canvas.scale(ianim.scaleX, ianim.scaleY, pvx, pvy);
        // MASK_WIPE's reveal. No ink-pad inset here, unlike the text path: that pad is a
        // TextOverlayRenderer artefact (transparent margin round the glyphs), and an image's
        // drawn rect IS its bounds — which is also what the preview clips.
        if (ianim.revealFrac < 1f) {
            canvas.clipRect(cx - iw / 2f, cy - ih / 2f,
                    cx - iw / 2f + iw * Math.max(0f, ianim.revealFrac), cy + ih / 2f);
        }
        // CORNER PIN — innermost, immediately around the draw, INSIDE rotate/scale and INSIDE
        // the mask bracket. Both of those placements are load-bearing:
        //  * inside rotate/scale, because the corners are pulled on the PICTURE and the pinned
        //    picture is then turned as a rigid whole — otherwise one corner drag would mean a
        //    different distortion at every rotation angle;
        //  * inside the mask bracket, for the same reason the rotation is: warping the item must
        //    not warp the hole it is seen through.
        // The matrix itself comes from the model, so the preview's copy of this cannot drift —
        // see TextOverlayItem.cornerPinMatrix.
        //
        // SPEC G MIRROR — between the wipe clip above and the pin below: bitmap → mirror →
        // pin → rotate/scale → translate, the mirror about the unpinned rect centre (cx, cy
        // in exactly this space). Clipping first keeps the wipe in destination space, which is
        // what the preview's view-level clip and the GL shader's unmirrored reveal compare
        // against. concat (not scale-about) because the anchor is already the right point in
        // the current user space. Unmirrored items skip the concat entirely.
        if (o.hasMirror()) {
            android.graphics.Matrix mirror = new android.graphics.Matrix();
            mirror.setScale(o.mirrorSignX(), o.mirrorSignY(), cx, cy);
            canvas.concat(mirror);
        }
        android.graphics.Matrix pin = new android.graphics.Matrix();
        if (o.cornerPinMatrix(pin, timelineMs, cx - iw / 2f, cy - ih / 2f, iw, ih)) {
            canvas.concat(pin);
        }
        canvas.drawBitmap(img,
                new android.graphics.Rect(0, 0, img.getWidth(), img.getHeight()),
                new android.graphics.RectF(cx - iw / 2f, cy - ih / 2f,
                        cx + iw / 2f, cy + ih / 2f), ip);
        com.fadcam.ui.faditor.model.MaskPathBuilder.endMask(canvas, maskSave);
        canvas.restore();
        return true;
    }
}
