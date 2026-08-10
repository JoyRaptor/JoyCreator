package com.fadcam.ui.faditor.overlay;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;

import androidx.annotation.NonNull;

import com.fadcam.ui.faditor.model.TextOverlayItem;

/**
 * Rasterises a {@link TextOverlayItem} to a {@link Bitmap} sized in output-frame
 * pixels so it can be fed to Media3's {@code BitmapOverlay} at scale 1:1.
 *
 * <p>The font size is a fraction of the output height, exactly matching the
 * on-screen preview (which uses the same fraction of the video-content height),
 * so what you place is what you export.</p>
 *
 * <p><b>All of the rendering is {@link TextBoxRenderer}.</b> This class only owns the
 * frame-size plumbing: {@link #fontPxFor}, {@link #padPxFor} and the bitmap sizing. It used
 * to hand-roll the whole draw (paint, lines, stroke/glow/shadow passes, alignment) — a
 * second copy of the box's pixels that W5-2's per-selection spans would have had to be
 * taught separately. Delegating keeps the per-object-FX GL path (the only consumer) on the
 * same one layout and the same run resolution as the preview and the no-FX export.</p>
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
     *
     * <p>Stays honest only while it mirrors {@code TextBoxRenderer.PAD_EM} — the same 0.35em the
     * shared renderer pads every box with. If one changes, so must this.</p>
     */
    public static int padPxFor(@NonNull TextOverlayItem o, int outH) {
        return (int) (fontPxFor(o, outH) * TextBoxRenderer.padEm());
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

        String shown = TextBoxRenderer.textAt(o, 0L, 0L);
        float[] size = new float[2];
        TextBoxRenderer.measure(o, shown, fontPx, size);

        int w = (int) Math.ceil(size[0]);
        int h = (int) Math.ceil(size[1]);
        w = Math.max(1, Math.min(w, outW > 0 ? outW * 2 : w));
        h = Math.max(1, h);

        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        // The whole box — pill, lines, glyphs, run-accurate colours and typefaces, spans
        // included — through the SAME renderer the preview and the no-FX export use. Static
        // frame: animate=false (the GL path animates the FRAME, and the textured item only
        // needs the keyframed style values, which the caller pre-bakes into the item).
        TextBoxRenderer.draw(canvas, o, shown, 0f, 0f, fontPx, 0L, 0L, false, 1f);
        return bmp;
    }
}
