package com.fadcam.ui.faditor.export;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.ui.faditor.model.TextOverlayItem;

/**
 * The full-frame picture of a text that carries its own effects: what the effects run on. ONE drawing for the export
 * ({@link TextFxGlEffect}) and the editor's preview (the GL composite, through {@code TextOverlayLayer.fxPipFor}), so
 * the two cannot place or style the words differently (STU-C6, 11-28: the preview showed no effect on a text at all
 * while the export did).
 */
public final class TextFxRaster {
    private TextFxRaster() {}

    /** Where to draw: a cleared {@code outW x outH} canvas, asked for only once there is something to draw. */
    public interface Target { @NonNull Canvas begin(); }

    /**
     * Draws [item] as it stands at [timelineMs] into a {@code outW x outH} frame from [target]: the animated transform,
     * its style, its spans, a TextLab look's picture if it wears one, turned and corner-pinned as the plain text is.
     * False when there is nothing to draw (the target was not asked for).
     */
    public static boolean draw(@NonNull Context context, @NonNull TextOverlayItem item, long timelineMs,
                               int outW, int outH, @NonNull Target target,
                               @NonNull Matrix pinScratch, @NonNull Paint lookPaint) {
        // The ANIMATED transform, through the same accessors CompositeExportOverlay uses.
        // Rendering the AUTHORED values instead was a real bug caught by the first export:
        // an overlay with a scale keyframe came out at its authored size, so styling a
        // caption silently resized it.
        TextOverlayItem frameItem = new TextOverlayItem(
                item.getText(), item.getColorInt(),
                item.animatedCenterX(timelineMs), item.animatedCenterY(timelineMs),
                item.animatedSizeFraction(timelineMs), item.animatedRotation(timelineMs));
        frameItem.setStrokeColorInt(item.getStrokeColorInt());
        frameItem.setStrokeWidthPx(item.animatedStrokeWidthPx(timelineMs));
        frameItem.setShadowColorInt(item.getShadowColorInt());
        frameItem.setShadowRadiusPx(item.animatedShadowRadiusPx(timelineMs));
        frameItem.setShadowAngleDeg(item.animatedShadowAngleDeg(timelineMs));
        frameItem.setShadowDistancePx(item.animatedShadowDistancePx(timelineMs));
        frameItem.setGlowColorInt(item.getGlowColorInt());
        frameItem.setGlowRadiusPx(item.animatedGlowRadiusPx(timelineMs));
        frameItem.setBackgroundColorInt(item.getBackgroundColorInt());
        frameItem.setFontFamily(item.getFontFamily());
        frameItem.setBold(item.isBold());
        frameItem.setItalic(item.isItalic());
        frameItem.setUnderline(item.isUnderline());
        frameItem.setTextCase(item.getTextCase());
        frameItem.setTextAlign(item.getTextAlign());
        // the wrap width and "fit width" shape the lines: without them an FX'd title exported on one line
        frameItem.setWrapEm(item.getWrapEm());
        frameItem.setFitWidth(item.isFitWidth());
        // W5-2 §3.8: the per-selection spans ride into the GL rasteriser too — render()
        // delegates to TextBoxRenderer, which resolves them exactly like the preview and
        // the no-FX export, so an FX'd caption cannot lose its formatting at export.
        frameItem.setStyleSpans(item.getStyleSpans());
        // A title wearing a TextLab look (TEXT-1): its look's picture at this moment is what the FX work on, drawn
        // by TextLab at this letter size; turned, pinned and placed below exactly as the plain words are.
        // k: how much to grow it when TextLab had to draw it smaller than asked (its size cap).
        Bitmap textBmp = null;
        float k = 1f;
        if (item.hasTextLook()) {
            float fontPx = Math.max(1f, frameItem.getSizeFraction() * outH);
            cc.joycreator.textlab.StudioTextLab.Frame f = cc.joycreator.textlab.StudioTextLab.get(context)
                    .renderForExport(cc.joycreator.textlab.StudioTextLab.exportKey(item.getId()),
                            item.getTextLook(), item.getText(), item.lookTime(timelineMs), fontPx);
            if (f != null) {
                textBmp = f.bitmap;
                k = (float) (fontPx / f.em);
            }
        }
        if (textBmp == null) textBmp = TextOverlayRenderer.render(frameItem, outW, outH);
        if (textBmp == null || textBmp.isRecycled()) return false;
        // COMPOSE ONTO A FULL FRAME. render() returns a bitmap sized to the TEXT, which
        // CompositeExportOverlay then positions with canvas.drawBitmap(cx - w/2, ...).
        Canvas c = target.begin();
        float cx = frameItem.getCenterX() * outW;
        float cy = frameItem.getCenterY() * outH;
        float rot = frameItem.getRotationDeg();
        c.save();
        if (rot != 0f) c.rotate(rot, cx, cy);
        // SPEC ZC — THE CORNER PIN, on the FX path too, built by the SAME TextOverlayItem.cornerPinMatrix the preview
        // and the plain export call, concat-ed at the same point — inside the rotate, immediately around the draw. Asked
        // of the ORIGINAL item, not frameItem: the pin lives on the authored overlay.
        float tw = textBmp.getWidth() * k, th = textBmp.getHeight() * k;
        if (item.cornerPinMatrix(pinScratch, timelineMs, cx - tw / 2f, cy - th / 2f, tw, th)) {
            c.concat(pinScratch);
        }
        if (k == 1f) {
            c.drawBitmap(textBmp, cx - tw / 2f, cy - th / 2f, null);
        } else {
            c.drawBitmap(textBmp, null, new RectF(cx - tw / 2f, cy - th / 2f, cx + tw / 2f, cy + th / 2f), lookPaint);
        }
        c.restore();
        textBmp.recycle();
        return true;
    }

    /** A new {@code outW x outH} picture of [item] at [timelineMs] (the preview's), or null when there is nothing. */
    @Nullable
    public static Bitmap picture(@NonNull Context context, @NonNull TextOverlayItem item, long timelineMs, int outW, int outH) {
        final Bitmap[] made = {null};
        boolean drawn = draw(context, item, timelineMs, outW, outH, () -> {
            made[0] = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888);
            return new Canvas(made[0]);
        }, new Matrix(), new Paint(Paint.FILTER_BITMAP_FLAG));
        return drawn ? made[0] : null;
    }
}
