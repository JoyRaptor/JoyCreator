package com.fadcam.ui.faditor.overlay;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.ui.faditor.model.CornerPin;

/**
 * The preview's image-overlay view: an ordinary {@link ImageView} that can ALSO draw its bitmap
 * through a full 3x3 {@link Matrix}, so a corner-pinned picture is possible at all.
 *
 * <p><b>Why a subclass and not a new View class.</b> An image overlay's placement, z-order,
 * hit-testing, alpha, drawable attach/detach and the {@code view instanceof ImageView} branches in
 * {@code TextOverlayLayer.position} are all built around this being an ImageView. Routing pinned
 * images to a SEPARATE view type would have meant a second copy of every one of those, and a class
 * swap in the middle of playback the first time an animated pin crossed zero. Subclassing keeps
 * one view type for every image, so nothing above this file has to know which path is taken.</p>
 *
 * <p><b>The fast path is literally the old path.</b> With no pin and no inset, {@link #onDraw}
 * calls {@code super.onDraw} and this view IS an {@code ImageView} with {@code FIT_XY}, down to
 * the same drawable, the same bounds and the same filtering. Nothing about the 99% case is
 * re-implemented, so nothing about it can regress.</p>
 *
 * <p><b>The inset.</b> A pinned corner is drawn OUTSIDE the item's rectangle, and a child View's
 * drawing does not survive its parent's clip. So the layer lays this view out LARGER than the
 * picture by the largest corner excursion and passes that margin here; the picture is drawn into
 * the inset rect and the pulled corner lands in the margin. Same device {@code TextBoxView} uses
 * for glow and shadow excursion. The inset is zero for an unpinned item, which is what keeps the
 * fast path byte-identical.</p>
 */
public class CornerPinImageView extends ImageView {

    /** Live corner offsets (fractions of the picture's own size), packed as {@link CornerPin}. */
    private final float[] pin = new float[CornerPin.SIZE];
    /** Margin on every side, in px, holding the corner excursion. 0 = unpinned fast path. */
    private float insetPx;
    /**
     * SPEC G mirror flags. Applied OUTSIDE the pin, about the unpinned picture centre —
     * the order {@code TextOverlayItem.mirrorSignX/Y} defines and the export shares — so the
     * pin offsets keep meaning the same thing however these stand.
     */
    private boolean mirrorX, mirrorY;

    // Reused, never allocated in onDraw: this view is redrawn on every playhead tick.
    private final Matrix pinMatrix = new Matrix();
    private final Matrix drawMatrix = new Matrix();
    private final Matrix mirrorMatrix = new Matrix();
    private final RectF srcRect = new RectF();
    private final RectF dstRect = new RectF();
    /** FILTER_BITMAP only — the exact paint {@code ImageOverlayDraw} draws the export with. */
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);

    public CornerPinImageView(@NonNull Context context) {
        super(context);
        setScaleType(ScaleType.FIT_XY);
    }

    /**
     * Set the corner pin for the frame about to be drawn.
     *
     * @param off8    the offsets at the current time, or null / all-zero for undistorted
     * @param insetPx the excursion margin the layer added on every side (0 when unpinned)
     */
    public void setCornerPin(@Nullable float[] off8, float insetPx) {
        boolean changed = this.insetPx != insetPx;
        this.insetPx = insetPx;
        for (int i = 0; i < CornerPin.SIZE; i++) {
            float v = (off8 != null && off8.length >= CornerPin.SIZE) ? off8[i] : 0f;
            if (pin[i] != v) { pin[i] = v; changed = true; }
        }
        // Only when something actually moved: this is called on every tick and an unconditional
        // invalidate would redraw the whole layer at rest.
        if (changed) invalidate();
    }

    /**
     * SPEC G: set the mirror flags for the frame about to be drawn. Like the pin, a no-op
     * when nothing changed; unmirrored is the fast path every existing project takes.
     */
    public void setMirror(boolean mx, boolean my) {
        if (mirrorX != mx || mirrorY != my) {
            mirrorX = mx;
            mirrorY = my;
            invalidate();
        }
    }

    /**
     * The excursion margin this view was inflated by, in px — 0 for an unpinned picture.
     *
     * <p>Exposed so callers that need THE PICTURE'S rect rather than the view's can subtract it.
     * The selection box and the transform handles both want the picture: a pinned image's view is
     * deliberately larger than what it draws, and chrome hung on the view's edges would stand a
     * whole excursion clear of the thing the user placed. Exactly the same reason
     * {@code TextBoxView.boxInsetPx} exists for glyph excursion.</p>
     */
    public float boxInsetPx() { return insetPx; }

    /** True when this frame needs the matrix path rather than plain {@code ImageView} drawing. */
    private boolean usesMatrix() {
        return insetPx > 0.5f || !CornerPin.isFlat(pin) || mirrorX || mirrorY;
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        if (!usesMatrix()) {
            // THE FAST PATH — unchanged ImageView behaviour for every image that has never been
            // pinned. Same drawable, same FIT_XY, same filtering.
            super.onDraw(canvas);
            return;
        }
        Drawable d = getDrawable();
        Bitmap bmp = (d instanceof BitmapDrawable) ? ((BitmapDrawable) d).getBitmap() : null;
        // Anything that is not a plain bitmap (or a bitmap freed underneath us) falls back rather
        // than drawing nothing: an image shown UNPINNED for a frame is recoverable, a blank one
        // reads as the overlay having vanished.
        if (bmp == null || bmp.isRecycled() || bmp.getWidth() <= 0 || bmp.getHeight() <= 0) {
            super.onDraw(canvas);
            return;
        }
        float w = getWidth() - insetPx * 2f;
        float h = getHeight() - insetPx * 2f;
        if (w <= 0f || h <= 0f) { super.onDraw(canvas); return; }

        // The picture's own rect inside this (inflated) view — FIT_XY, exactly as before.
        srcRect.set(0f, 0f, bmp.getWidth(), bmp.getHeight());
        dstRect.set(insetPx, insetPx, insetPx + w, insetPx + h);
        drawMatrix.setRectToRect(srcRect, dstRect, Matrix.ScaleToFit.FILL);

        // ...then the homography, INNERMOST relative to everything the parent applies. The View's
        // rotation, scale and translation are applied by the parent ABOVE this canvas, so a
        // concat here is necessarily inside them — which is the composition order the export
        // mirrors deliberately (see TextOverlayItem.cornerPinMatrix).
        if (CornerPin.buildMatrix(pinMatrix, insetPx, insetPx, w, h, pin)) {
            // postConcat multiplies on the destination side, so this is pin . base: the pin
            // maps the placed rect onto its distorted corners. (This order is load-bearing:
            // pin-alone pictures have always previewed correctly through exactly this line.)
            drawMatrix.postConcat(pinMatrix);
        }

        // SPEC G mirror, OUTSIDE the pin and about the unpinned picture centre: view =
        // mirror . pin . base, the order the export draws (ImageOverlayDraw concats the
        // mirror first and the pin second, so the pin applies first). postConcat keeps it
        // on the destination side with the destination-space pivot above. Skipped whole
        // when unmirrored, so the matrix the pin path concatenates is bit-for-bit what it
        // was. ( Assembling it with preConcat put the mirror on the SOURCE side with this
        // destination-space pivot — mirroring the bitmap about the wrong line and shoving
        // the picture half a frame sideways while the handles stayed put. JoyRaptor 2026-09-07:
        // corner flip "flipped on a side axis", image half out of its quad.)
        if (mirrorX || mirrorY) {
            mirrorMatrix.setScale(mirrorX ? -1f : 1f, mirrorY ? -1f : 1f,
                    insetPx + w / 2f, insetPx + h / 2f);
            drawMatrix.postConcat(mirrorMatrix);
        }
        canvas.drawBitmap(bmp, drawMatrix, paint);
    }
}
