package com.fadcam.ui.faditor.export;

import android.graphics.Bitmap;
import android.graphics.Canvas;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.util.Size;
import androidx.media3.effect.BitmapOverlay;
import androidx.media3.effect.StaticOverlaySettings;

import com.fadcam.ui.faditor.slides.SlideDeck;
import com.fadcam.ui.faditor.slides.SlideDeckRenderer;

/**
 * Export BitmapOverlay for a SlideDeck — ONE renderer shared with preview, ping-pong buffers.
 *
 * <p>Rasterises only when cue index changes or box/style changes. Holds ONE bitmap and swaps
 * when cue changes (SPEC §2). Per-frame work in export process is therefore cheap.</p>
 */
public class SlideDeckOverlay extends BitmapOverlay {

    @Nullable private final SlideDeck deck;
    private final int outW;
    private final int outH;
    private final long editorTimeOffsetMs;
    private final long projectDurationMs;

    private Bitmap bitmap;
    private Canvas canvas;
    // Ping-pong buffers for export: two bitmaps alternating to avoid allocating per frame
    @Nullable private Bitmap bitmapB;
    @Nullable private Canvas canvasB;
    @Nullable private Bitmap lastReturned;
    @Nullable private Bitmap pendingRecycle;

    // Cache state
    private int cachedCue = -2;
    private int cachedW = -1, cachedH = -1;
    @Nullable private Bitmap cachedCard;
    private long costDrawNanos = 0;
    private int costFrames = 0;

    public SlideDeckOverlay(@NonNull SlideDeck deck, int outW, int outH, long editorTimeOffsetMs, long projectDurationMs) {
        this.deck = deck;
        this.outW = Math.max(1,outW);
        this.outH = Math.max(1,outH);
        this.editorTimeOffsetMs = editorTimeOffsetMs;
        this.projectDurationMs = projectDurationMs;
    }

    @Override public void configure(@NonNull Size size) {
        int w = Math.max(1, size.getWidth());
        int h = Math.max(1, size.getHeight());
        if (bitmap != null && bitmap.getWidth()==w && bitmap.getHeight()==h) return;
        if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
        bitmap = Bitmap.createBitmap(w,h, Bitmap.Config.ARGB_8888);
        canvas = new Canvas(bitmap);
        if (bitmapB != null && (bitmapB.getWidth()!=w || bitmapB.getHeight()!=h)) {
            if (!bitmapB.isRecycled()) bitmapB.recycle();
            bitmapB = null; canvasB=null;
        }
        if (bitmapB==null) {
            bitmapB = Bitmap.createBitmap(w,h, Bitmap.Config.ARGB_8888);
            canvasB = new Canvas(bitmapB);
        }
    }

    @NonNull @Override public Bitmap getBitmap(long presentationTimeUs) {
        long drawStart = System.nanoTime();
        if (bitmap==null || bitmap.isRecycled()) {
            bitmap = Bitmap.createBitmap(Math.max(1,outW), Math.max(1,outH), Bitmap.Config.ARGB_8888);
            canvas = new Canvas(bitmap);
        }
        // Toggle ping-pong: use bitmap/b vs lastReturned logic — simple double buffer
        Bitmap target = (costFrames %2==0) ? bitmap : bitmapB;
        Canvas targetCanvas = (costFrames %2==0) ? canvas : canvasB;
        if (target==null || target.isRecycled()) {
            target = Bitmap.createBitmap(Math.max(1,outW), Math.max(1,outH), Bitmap.Config.ARGB_8888);
            targetCanvas = new Canvas(target);
            if (costFrames%2==0) { bitmap=target; canvas=targetCanvas; } else { bitmapB=target; canvasB=targetCanvas; }
        }
        targetCanvas.drawColor(0, android.graphics.PorterDuff.Mode.CLEAR);
        if (deck==null || deck.slideCount()==0) {
            costFrames++;
            return target;
        }
        long timelineMs = presentationTimeUs/1000 + editorTimeOffsetMs;
        int cue = deck.cueAtMs(timelineMs);
        if (cue <0) { costFrames++; return target; }

        int w = target.getWidth(), h = target.getHeight();
        // Rasterise only on cue change or size change
        if (SlideDeckRenderer.needsRebuild(cachedCue, cue, cachedW, w, cachedH, h) || cachedCard==null || cachedCard.isRecycled()) {
            if (cachedCard != null && !cachedCard.isRecycled()) cachedCard.recycle();
            cachedCard = SlideDeckRenderer.render(deck, cue, w, h);
            cachedCue = cue; cachedW = w; cachedH = h;
        }
        if (cachedCard != null && !cachedCard.isRecycled()) {
            float cx = deck.getCenterX()*w;
            float cy = deck.getCenterY()*h;
            float bw = cachedCard.getWidth(), bh = cachedCard.getHeight();
            android.graphics.RectF dst = new android.graphics.RectF(cx-bw/2, cy-bh/2, cx+bw/2, cy+bh/2);
            targetCanvas.drawBitmap(cachedCard, null, dst, null);
        }
        costFrames++;
        costDrawNanos += System.nanoTime()-drawStart;
        lastReturned = target;
        return target;
    }

    @Override
    public void release() throws androidx.media3.common.VideoFrameProcessingException {
        // BitmapOverlay.release() declares VideoFrameProcessingException; the override has to
        // declare it too. Matches PipFrameOverlay.release() and CompositeExportOverlay.release().
        super.release();
        if (cachedCard != null && !cachedCard.isRecycled()) cachedCard.recycle();
        cachedCard=null;
        if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
        if (bitmapB != null && !bitmapB.isRecycled()) bitmapB.recycle();
    }
}
