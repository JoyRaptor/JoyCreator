package com.fadcam.ui.faditor.overlay;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.TextPaint;

import androidx.annotation.NonNull;

import com.fadcam.ui.faditor.model.TextOverlayItem;

/**
 * Rasterises a {@link TextOverlayItem} to a {@link Bitmap} sized in output-frame
 * pixels so it can be fed to Media3's {@code BitmapOverlay} at scale 1:1.
 *
 * <p>The font size is a fraction of the output height, exactly matching the
 * on-screen preview (which uses the same fraction of the video-content height),
 * so what you place is what you export.</p>
 */
public final class TextOverlayRenderer {

    private TextOverlayRenderer() {}

    /**
     * The type size this overlay rasterises at, in output pixels.
     *
     * <p>Exposed because MASK_WIPE's caller needs to know where the INK sits inside the returned
     * bitmap, not just how big the bitmap is. Derived here rather than recomputed at the call site
     * so the two cannot drift apart the way a copied formula would.</p>
     */
    public static float fontPxFor(@NonNull TextOverlayItem o, int outH) {
        return Math.max(8f, o.getSizeFraction() * outH);
    }

    /**
     * The transparent margin this renderer leaves around the text, in output pixels.
     *
     * <p>It exists so a shadow or an outline is not clipped by the bitmap edge. It also means the
     * bitmap is WIDER than the ink, which matters to a reveal mask: wiping across the bitmap would
     * spend the first and last few percent of the animation uncovering empty padding, and would
     * put the mask edge in a different place than the preview does — the preview's
     * {@code TextView} is measured to the text itself and carries no such margin. So the export
     * insets by this before wiping. See {@code TextOverlayLayer#applyReveal}.</p>
     */
    public static int padPxFor(@NonNull TextOverlayItem o, int outH) {
        return (int) (fontPxFor(o, outH) * 0.35f);
    }

    /**
     * @param o       the overlay to render
     * @param outW    output frame width in pixels
     * @param outH    output frame height in pixels
     * @return an ARGB bitmap containing the rendered, shadowed text
     */
    @NonNull
    public static Bitmap render(@NonNull TextOverlayItem o, int outW, int outH) {
        float fontPx = fontPxFor(o, outH);

        TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(o.getColorInt());
        paint.setTextSize(fontPx);
        paint.setTypeface(o.getTypeface());     // bold/italic already baked in — see getTypeface()
        paint.setUnderlineText(o.isUnderline());
        // CENTER only — this path (per-object GL FX only, see TextFxGlEffect) draws one bitmap
        // rasterised to its own text bounds rather than a positioned box, so LEFT/RIGHT/JUSTIFY
        // have no box edge to align against. Known, documented gap: an item with BOTH an active
        // FX stack AND a non-CENTER alignment renders centred here while the (no-FX) preview and
        // export path (TextBoxRenderer) honour the alignment — see SPEC_TEXT_DRAWER report.
        paint.setShadowLayer(shadowRadiusFor(o, fontPx),
                com.fadcam.ui.faditor.model.TextOverlayItem.shadowDx(
                        o.getShadowAngleDeg(), o.getShadowDistancePx(), fontPx),
                com.fadcam.ui.faditor.model.TextOverlayItem.shadowDy(
                        o.getShadowAngleDeg(), o.getShadowDistancePx(), fontPx),
                o.getShadowColorInt());

        String raw = o.getText() == null || o.getText().isEmpty() ? " " : o.getText();
        String text = o.applyCase(raw);
        String[] lines = text.split("\n", -1);

        float maxLineW = 1f;
        for (String line : lines) {
            maxLineW = Math.max(maxLineW, paint.measureText(line));
        }
        Paint.FontMetrics fm = paint.getFontMetrics();
        float lineH = fm.descent - fm.ascent;
        int pad = padPxFor(o, outH);

        int w = (int) Math.ceil(maxLineW) + pad * 2;
        int h = (int) Math.ceil(lineH * lines.length) + pad * 2;
        w = Math.max(1, Math.min(w, outW > 0 ? outW * 2 : w));
        h = Math.max(1, h);

        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        if (o.getBackgroundColorInt() != android.graphics.Color.TRANSPARENT) {
            Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
            bg.setColor(o.getBackgroundColorInt());
            canvas.drawRoundRect(new android.graphics.RectF(0, 0, w, h),
                    fontPx * 0.35f, fontPx * 0.35f, bg);
        }

        float x = w / 2f;
        float y = pad - fm.ascent;
        for (String line : lines) {
            if (o.getStrokeWidthPx() > 0f && o.getStrokeColorInt() != android.graphics.Color.TRANSPARENT) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(com.fadcam.ui.faditor.model.TextOverlayItem.decorRadiusPx(o.getStrokeWidthPx(), fontPx));
                paint.setColor(o.getStrokeColorInt());
                canvas.drawText(line, x, y, paint);
            }
            if (o.getGlowRadiusPx() > 0f && o.getGlowColorInt() != android.graphics.Color.TRANSPARENT) {
                paint.setShadowLayer(com.fadcam.ui.faditor.model.TextOverlayItem.decorRadiusPx(o.getGlowRadiusPx(), fontPx), 0f, 0f, o.getGlowColorInt());
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(o.getColorInt());
                canvas.drawText(line, x, y, paint);
            }
            paint.setShadowLayer(shadowRadiusFor(o, fontPx),
                    com.fadcam.ui.faditor.model.TextOverlayItem.shadowDx(
                            o.getShadowAngleDeg(), o.getShadowDistancePx(), fontPx),
                    com.fadcam.ui.faditor.model.TextOverlayItem.shadowDy(
                            o.getShadowAngleDeg(), o.getShadowDistancePx(), fontPx),
                    o.getShadowColorInt());
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(o.getColorInt());
            canvas.drawText(line, x, y, paint);
            y += lineH;
        }
        return bmp;
    }

    private static float shadowRadiusFor(@NonNull TextOverlayItem o, float fontPx) {
        return o.getShadowRadiusPx() > 0f
                ? com.fadcam.ui.faditor.model.TextOverlayItem.decorRadiusPx(o.getShadowRadiusPx(), fontPx)
                : fontPx * 0.10f;
    }
}
