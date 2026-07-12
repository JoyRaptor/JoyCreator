package com.fadcam.ui.faditor.waveform;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.RectF;

import androidx.annotation.NonNull;

import com.fadcam.ui.faditor.model.BandedWaveformData;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A3: renders each clip's tape into fixed-width bitmap TILES once and blits them while the
 * timeline pans / plays, instead of re-running the full vector {@link TapeWaveformRenderer#draw}
 * (path building, per-band gradient allocation, glow strokes, spark scan, column scans) on every
 * 60fps {@code onDraw} for every visible audio item. The playhead stays a separate overlay pass,
 * so it keeps redrawing per frame over the blitted tiles — nothing in the tape changes between
 * frames while scrolling, only the viewport offset does.
 *
 * <h3>Tiling &amp; seams</h3>
 * A tile spans {@link #TILE_W} pixels of the item's own content space (0 = item left edge). A
 * tile is baked by rendering the FULL item into an offset rect so the tile bitmap is an exact
 * crop of the single full-width render — adjacent tiles therefore meet with zero seam. Tiles are
 * blit 1:1 (src size == dst size, no scaling) at integer content offsets from the shared item
 * left, so tile T+1's left is exactly tile T's right.
 *
 * <h3>Invalidation (provably complete)</h3>
 * A tile key is {@code clipKey | serial | contentWidthPx | tileIndex}:
 * <ul>
 *   <li><b>serial</b> — {@link BandedTimelineWaveformCache.Shaped#serial}. A re-extract
 *       (waveform-ready swap-in) and a {@code reshapeAll()} are the only ways shaped data changes,
 *       and both mint a new {@code Shaped} with a new serial ⇒ old tiles become unreachable.</li>
 *   <li><b>contentWidthPx</b> — the item's pixel width, which is exactly its zoom tier (and also
 *       changes on trim). A zoom/trim change ⇒ new width ⇒ rebake.</li>
 * </ul>
 * Unreachable tiles are evicted by the LRU. There is no separate style-epoch to forget to bump.
 *
 * <h3>Live customization (JoyRaptor)</h3>
 * While a settings slider is under the user's finger the timeline is at rest, where vector mode is
 * already smooth — call {@link #setDirectVectorMode}{@code (true)} on drag start to draw the
 * visible tapes with {@link TapeWaveformRenderer#draw} directly (real-time reshape), and
 * {@code (false)} on release. After release the next {@code reshapeAll()} mints new serials, so the
 * blit path rebakes the (few) visible tiles automatically — no background thread needed.
 *
 * <h3>Memory bound</h3>
 * A tile is {@code TILE_W * H * 4} bytes; a 512x48px ARGB_8888 tile ≈ 98 KB. {@link #MAX_TILES}
 * = 16 ⇒ ceiling ≈ 1.6 MB. Evicted bitmaps are recycled immediately.
 */
public final class TapeTileCache {

    /** Tile width in item content pixels. */
    public static final int TILE_W = 512;
    /** LRU ceiling (on-screen tiles + a small margin). */
    private static final int MAX_TILES = 16;

    private final TapeWaveformRenderer baker;
    private final RectF bakeRect = new RectF();
    private final RectF dst = new RectF();
    private boolean directVectorMode = false;

    /** Access-ordered LRU; evicts + recycles the eldest bitmap past the ceiling. */
    private final LinkedHashMap<String, Bitmap> tiles =
            new LinkedHashMap<String, Bitmap>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Bitmap> e) {
                    if (size() > MAX_TILES) {
                        Bitmap b = e.getValue();
                        if (b != null && !b.isRecycled()) b.recycle();
                        return true;
                    }
                    return false;
                }
            };

    public TapeTileCache(float density) {
        this.baker = new TapeWaveformRenderer(density);
    }

    /** Drag-time bypass: {@code true} draws vector directly (real-time reshape), no tiles. */
    public void setDirectVectorMode(boolean on) {
        this.directVectorMode = on;
    }

    public boolean isDirectVectorMode() {
        return directVectorMode;
    }

    /**
     * Draw the tape into {@code rect} — blitting cached tiles when possible, else baking them
     * (or, in direct-vector mode, drawing straight through). Visual output is identical to a
     * direct {@link TapeWaveformRenderer#draw} at the same size.
     *
     * @param clipKey stable per-source identity (audio clip id, or source-uri|span for the drawer).
     * @param serial  the {@link BandedTimelineWaveformCache.Shaped#serial} of {@code shaped}.
     */
    public void draw(@NonNull Canvas canvas, @NonNull RectF rect, @NonNull BandedWaveformData raw,
                     @NonNull ShapedTape shaped, long serial, @NonNull TapeWaveformStyle style,
                     long clipInMs, long clipDurMs, @NonNull String clipKey) {
        final float wF = rect.width();
        final int H = Math.max(1, Math.round(rect.height()));
        // Direct vector for tiny rects, degenerate sizes, or live drag.
        if (directVectorMode || wF < 2f || wF > 8192f) {
            baker.draw(canvas, rect, raw, shaped, style, clipInMs, clipDurMs);
            return;
        }
        final int Wpx = Math.max(1, Math.round(wF));
        final int tileCount = (Wpx + TILE_W - 1) / TILE_W;
        for (int t = 0; t < tileCount; t++) {
            final int contentX = t * TILE_W;
            final int tileW = Math.min(TILE_W, Wpx - contentX);
            if (tileW <= 0) break;
            String key = clipKey + "|" + serial + "|" + Wpx + "|" + t;
            Bitmap bmp = tiles.get(key);
            if (bmp == null || bmp.isRecycled()) {
                bmp = bakeTile(t, tileW, H, Wpx, rect.left, raw, shaped, style, clipInMs, clipDurMs);
                tiles.put(key, bmp);
            }
            dst.set(rect.left + contentX, rect.top,
                    rect.left + contentX + tileW, rect.top + H);
            canvas.drawBitmap(bmp, null, dst, null);
        }
    }

    /** Bake tile {@code t} as an exact crop of the full-width render (offset rect trick). */
    @NonNull
    private Bitmap bakeTile(int t, int tileW, int H, int Wpx, float rectLeft,
                            @NonNull BandedWaveformData raw, @NonNull ShapedTape shaped,
                            @NonNull TapeWaveformStyle style, long clipInMs, long clipDurMs) {
        Bitmap bmp = Bitmap.createBitmap(tileW, H, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        // Position the FULL item so content-x [t*TILE_W, ...) lands at bitmap x 0: the bitmap is
        // only tileW x H, so the renderer's out-of-bounds paths are clipped — the retained slice
        // is a pixel-exact window into the same single render every tile shares (no seams).
        final float left = -(float) (t * TILE_W);
        bakeRect.set(left, 0f, left + Wpx, H);
        baker.draw(c, bakeRect, raw, shaped, style, clipInMs, clipDurMs);
        return bmp;
    }

    /** Drop and recycle every tile (e.g. on teardown). */
    public void clear() {
        for (Iterator<Map.Entry<String, Bitmap>> it = tiles.entrySet().iterator(); it.hasNext(); ) {
            Bitmap b = it.next().getValue();
            if (b != null && !b.isRecycled()) b.recycle();
            it.remove();
        }
    }
}
