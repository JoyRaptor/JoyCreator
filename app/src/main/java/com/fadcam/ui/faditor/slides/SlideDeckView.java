package com.fadcam.ui.faditor.slides;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Preview view that draws CURRENT slide's cached bitmap. Rasterises only when cue changes
 * or box/style changes — holds ONE bitmap.
 */
public class SlideDeckView extends View {

    @Nullable private SlideDeck deck;
    @Nullable private Bitmap cached;
    private int cachedCue = -2;
    private int cachedW = -1, cachedH = -1;
    private long currentMs = 0;
    private final RectF dst = new RectF();

    public SlideDeckView(Context c, AttributeSet a) { super(c,a); }
    public SlideDeckView(Context c) { super(c); }

    public void setDeck(@Nullable SlideDeck d) {
        deck = d; cachedCue = -2; if (cached != null) { cached.recycle(); cached=null; }
        invalidate();
    }

    public void setCurrentMs(long ms) { currentMs = ms; invalidate(); }

    @Override protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);
        if (deck == null || deck.slideCount()==0) return;
        int cue = deck.cueAtMs(currentMs);
        int w = getWidth(), h = getHeight();
        if (w <=0 || h <=0) return;
        if (cue <0) {
            // No slide yet — draw nothing (transparent)
            return;
        }
        if (SlideDeckRenderer.needsRebuild(cachedCue, cue, cachedW, w, cachedH, h) || cached==null || cached.isRecycled()) {
            if (cached != null && !cached.isRecycled()) cached.recycle();
            Bitmap bmp = SlideDeckRenderer.render(deck, cue, w, h);
            cached = bmp;
            cachedCue = cue; cachedW = w; cachedH = h;
        }
        if (cached == null || cached.isRecycled()) return;
        // Center card according to deck geometry
        float cx = deck.getCenterX()*w;
        float cy = deck.getCenterY()*h;
        float bw = cached.getWidth(), bh = cached.getHeight();
        dst.set(cx-bw/2, cy-bh/2, cx+bw/2, cy+bh/2);
        canvas.drawBitmap(cached, null, dst, null);
    }

    @Override protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (cached != null && !cached.isRecycled()) cached.recycle();
        cached=null;
    }
}
