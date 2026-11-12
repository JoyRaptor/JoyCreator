package com.fadcam.ui.faditor.export;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.PorterDuff;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Two reusable full-frame pictures, handed to a {@code BitmapOverlay} in turn, plus one fixed
 * empty picture. Replaces "a new full-frame Bitmap every exported frame" (code review 11-11,
 * E17): at 1080x1920 that is 8 MB allocated and dropped per frame per pass, about 240 MB a
 * second, which the 09-22 memory trace showed as an export that slows to a crawl.
 *
 * <p>WHY TWO IS ENOUGH (the same reasoning {@link CompositeExportOverlay} and
 * {@link PipFrameOverlay} already ship with). The patched {@code BitmapOverlay.getTextureId}
 * calls {@code getBitmap} and re-uploads when the returned instance differs from the last one
 * it saw ({@code bitmap != lastBitmap}); the upload, {@code GLUtils.texImage2D}, reads the
 * pixels during the call. So a picture is free to draw into again as soon as a DIFFERENT
 * picture has been handed over after it.
 *
 * <p>THE ONE RULE: {@link #begin} draws into whichever of the two was NOT handed over last.
 * A plain toggle is not enough for a caller that hands the same picture back several frames
 * running (the static fast path in {@link ImageOverlayFrameOverlay}): toggling there could
 * redraw the very instance media3 holds as {@code lastBitmap}, which it would then skip
 * uploading, and the picture would freeze.
 *
 * <p>The empty picture is never drawn into, so handing it back frame after frame uploads once.
 * Not thread-safe: one per overlay, used on the GL thread that calls {@code getBitmap}.
 */
final class OverlayFramePair {

    @Nullable private Bitmap a, b, empty;
    @Nullable private Canvas canvasA, canvasB;
    /** The picture most recently handed to media3, or null. */
    @Nullable private Bitmap handedOver;
    /** The picture {@link #begin} last opened for drawing. */
    @Nullable private Bitmap drawing;

    /** (Re)allocates for a frame of {@code w x h}; a no-op when the size is unchanged. */
    private void ensure(int w, int h) {
        w = Math.max(1, w);
        h = Math.max(1, h);
        if (a != null && !a.isRecycled() && a.getWidth() == w && a.getHeight() == h) return;
        release();
        a = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        empty = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        canvasA = new Canvas(a);
        canvasB = new Canvas(b);
    }

    /**
     * A cleared canvas on the picture that was not handed over last, with no transform or clip
     * left over from an earlier frame (a frame that threw between save and restore). Finish with
     * {@link #finish()}, or hand back {@link #empty} instead.
     */
    @NonNull
    Canvas begin(int w, int h) {
        ensure(w, h);
        boolean useB = handedOver == a;
        drawing = useB ? b : a;
        Canvas c = useB ? canvasB : canvasA;
        c.restoreToCount(1);
        c.drawColor(0, PorterDuff.Mode.CLEAR);
        return c;
    }

    /** The picture opened by {@link #begin}, now handed over. */
    @NonNull
    Bitmap finish() {
        if (drawing == null) throw new IllegalStateException("finish() without begin()");
        handedOver = drawing;
        return drawing;
    }

    /** The fixed empty picture, handed over. */
    @NonNull
    Bitmap empty(int w, int h) {
        ensure(w, h);
        handedOver = empty;
        return empty;
    }

    /** The picture handed over last (to hand it back unchanged), or null. */
    @Nullable
    Bitmap last() {
        return handedOver != null && !handedOver.isRecycled() ? handedOver : null;
    }

    void release() {
        for (Bitmap bm : new Bitmap[]{a, b, empty}) {
            if (bm != null && !bm.isRecycled()) bm.recycle();
        }
        a = b = empty = null;
        canvasA = canvasB = null;
        handedOver = null;
        drawing = null;
    }
}
