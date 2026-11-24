package com.fadcam.ui.faditor.compositor;

import android.graphics.Bitmap;
import android.graphics.Canvas;

import androidx.annotation.Nullable;

import com.fadcam.ui.faditor.keyframe.KeyframeSet;
import com.fadcam.ui.faditor.model.TextOverlayItem;
import com.fadcam.ui.faditor.overlay.TextBoxRenderer;
import com.fadcam.ui.faditor.transcript.CaptionAnimator;

/** An upright, padded title tile. Placement and the shutter are applied after rasterization. */
public record RigidTitleRaster(String text, float fontPx, int width, int height, float left, float top) {
    private static final int MAX_EDGE = 2048;

    public static boolean supports(TextOverlayItem item) {
        return item.isAnimated() && supportsPlain(item);
    }

    public static boolean supportsPlain(TextOverlayItem item) {
        if (item.isImage() || item.isLinked() || item.isProxy() || item.hasMesh()
                || item.hasCornerPin() || item.hasTextLook() || item.isTimer() || item.isGeneratedSlide()
                || item.hasStyleSpans() || item.hasActiveFx() || item.wantsGlExport()
                || item.getStrokeWidthPx() > 0f || item.getGlowRadiusPx() > 0f || item.getShadowRadiusPx() > 0f
                || item.getImageFadeInMs() != 0 || item.getImageFadeOutMs() != 0
                || CaptionAnimator.parsePreset(item.getTextAnimPreset()) != CaptionAnimator.Preset.NONE) {
            return false;
        }
        for (var track : item.getKeyframes().tracks()) {
            if (!track.isEmpty() && !KeyframeSet.X.equals(track.property)
                    && !KeyframeSet.Y.equals(track.property) && !KeyframeSet.ROTATION.equals(track.property)) {
                return false;
            }
        }
        return true;
    }

    public static RigidTitleRaster measure(TextOverlayItem item, long timeMs, long projectDurationMs, int layoutH) {
        String text = TextBoxRenderer.textAt(item, timeMs, projectDurationMs);
        float fontPx = Math.max(1f, item.animatedSizeFraction(timeMs) * layoutH);
        float[] size = new float[2];
        TextBoxRenderer.measure(item, text, fontPx, size);
        float pad = Math.max(4f, fontPx);
        int width = (int) Math.ceil(size[0] + 2f * pad);
        int height = (int) Math.ceil(size[1] + 2f * pad);
        return new RigidTitleRaster(text, fontPx, width, height,
                (width - size[0]) * 0.5f, (height - size[1]) * 0.5f);
    }

    public boolean fits() {
        return width > 0 && height > 0 && width <= MAX_EDGE && height <= MAX_EDGE;
    }

    @Nullable
    public Bitmap draw(TextOverlayItem item, long timeMs, long durationMs, float opacity) {
        if (!fits()) return null;
        Bitmap bitmap = null;
        try {
            bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            TextBoxRenderer.draw(new Canvas(bitmap), item, text, left, top, fontPx,
                    timeMs, durationMs, true, opacity);
            return bitmap;
        } catch (RuntimeException | OutOfMemoryError error) {
            if (bitmap != null) bitmap.recycle();
            com.fadcam.FLog.w("RigidTitleRaster", "Title source unavailable", error);
            return null;
        }
    }
}
