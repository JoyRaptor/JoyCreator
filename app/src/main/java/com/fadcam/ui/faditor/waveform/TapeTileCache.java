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
    /** LRU ceiling (on-screen tiles + a small margin; 1440px screen ⇒ ~3 visible per item). */
    private static final int MAX_TILES = 24;

    private final TapeWaveformRenderer baker;
    private final RectF bakeRect = new RectF();
    private final RectF dst = new RectF();
    private boolean directVectorMode = false;
    /**
     * Per-clip last-seen item width (F2d): tiles only bake once the width repeats — i.e.
     * the zoom is at rest. During a live pinch every frame has a NEW width, and each bake
     * is a full-item vector render clipped to 512px, so baking N visible tiles per frame
     * would cost N× the plain vector draw the pinch gets instead.
     */
    private final java.util.HashMap<String, Integer> lastWidthByClip = new java.util.HashMap<>();

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
     * <p>F2d (PERF_SPEC_LONGFILE_20260718): the old {@code wF > 8192f} guard fell back to a
     * FULL vector render every frame — at editing zoom 8192px is only ~10-45s of timeline, so
     * every real clip on a long project took the fallback and the tile cache never engaged
     * (>1s draw passes on the 45-min project). Now the caller passes the visible content
     * window and only intersecting tiles are baked/blit, so item width no longer matters.</p>
     *
     * @param clipKey  stable per-source identity (audio clip id, or source-uri|span for the drawer).
     * @param serial   the {@link BandedTimelineWaveformCache.Shaped#serial} of {@code shaped}.
     * @param visLeft  left edge of the visible viewport in the same content space as {@code rect}.
     * @param visRight right edge of the visible viewport (pass {@code Float.MAX_VALUE} with
     *                 {@code -Float.MAX_VALUE} left to draw everything, e.g. offscreen bakes).
     */
    public void draw(@NonNull Canvas canvas, @NonNull RectF rect, @NonNull BandedWaveformData raw,
                     @NonNull ShapedTape shaped, long serial, @NonNull TapeWaveformStyle style,
                     long clipInMs, long clipDurMs, @NonNull String clipKey,
                     float visLeft, float visRight) {
        final float wF = rect.width();
        final int H = Math.max(1, Math.round(rect.height()));
        // Direct vector for tiny rects, degenerate sizes, or live drag — WINDOWED to the
        // viewport (F6b): a full-rect vector pass is O(item width) and on a 45-min layer
        // that's a multi-second main-thread render (the double-tap-drawer ANR, 2026-07-18).
        if (directVectorMode || wF < 2f) {
            drawVectorWindowed(canvas, rect, raw, shaped, style, clipInMs, clipDurMs, visLeft, visRight);
            return;
        }
        final int Wpx = Math.max(1, Math.round(wF));
        // Zoom-in-motion: width changed since the last frame → draw vector this frame and
        // bake only once the width settles (see lastWidthByClip doc).
        Integer lastW = lastWidthByClip.put(clipKey, Wpx);
        if (lastW == null || lastW != Wpx) {
            drawVectorWindowed(canvas, rect, raw, shaped, style, clipInMs, clipDurMs, visLeft, visRight);
            return;
        }
        final int tileCount = (Wpx + TILE_W - 1) / TILE_W;
        // Only tiles intersecting the visible window (±0 margin: a tile is 512px, the blit is
        // cheap, and bakes are the expensive part — bake exactly what shows).
        int tStart = 0, tEnd = tileCount;
        if (visRight > visLeft) {
            tStart = Math.max(0, (int) Math.floor((visLeft - rect.left) / TILE_W));
            tEnd = Math.min(tileCount, (int) Math.floor((visRight - rect.left) / TILE_W) + 1);
        }
        for (int t = tStart; t < tEnd; t++) {
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

    /** Back-compat overload: draw with no viewport culling (bakes every tile). */
    public void draw(@NonNull Canvas canvas, @NonNull RectF rect, @NonNull BandedWaveformData raw,
                     @NonNull ShapedTape shaped, long serial, @NonNull TapeWaveformStyle style,
                     long clipInMs, long clipDurMs, @NonNull String clipKey) {
        draw(canvas, rect, raw, shaped, serial, style, clipInMs, clipDurMs, clipKey,
                -Float.MAX_VALUE, Float.MAX_VALUE);
    }

    /**
     * F6b: the renderer's time↔x mapping is linear ({@code x/W * clipDurMs}), so rendering a
     * sub-span at the same ms-per-px is pixel-equivalent to cropping a full-item render — but
     * O(window) instead of O(item). The old "offset rect trick" positioned the FULL item per
     * bake; on the 45-min project one bake was a multi-second columns()+Path pass over ~100k px,
     * ×(settle frame + each visible tile) ⇒ the double-tap-drawer ANR (2026-07-18). Overscan
     * gives the edge smoothing (3-tap) and spark detection (±6px) real neighbor context so
     * tile seams stay invisible; the bitmap/window clip discards it.
     */
    private static final int OVERSCAN_PX = 8;

    /** Reusable rect for windowed direct-vector draws. */
    private final RectF winRect = new RectF();

    /** Vector-draw only the part of {@code rect} inside [visLeft, visRight], via time sub-span. */
    private void drawVectorWindowed(@NonNull Canvas canvas, @NonNull RectF rect,
                                    @NonNull BandedWaveformData raw, @NonNull ShapedTape shaped,
                                    @NonNull TapeWaveformStyle style, long clipInMs, long clipDurMs,
                                    float visLeft, float visRight) {
        final float xL = Math.max(rect.left, visLeft - OVERSCAN_PX);
        final float xR = Math.min(rect.right, visRight + OVERSCAN_PX);
        if (xR <= xL) return;
        if (xL <= rect.left && xR >= rect.right) {
            baker.draw(canvas, rect, raw, shaped, style, clipInMs, clipDurMs);
            return;
        }
        final double w = Math.max(1f, rect.width());
        final long msL = clipInMs + (long) ((xL - rect.left) / w * clipDurMs);
        final long msR = clipInMs + (long) Math.ceil((xR - rect.left) / w * clipDurMs);
        winRect.set(xL, rect.top, xR, rect.bottom);
        baker.draw(canvas, winRect, raw, shaped, style, msL, Math.max(1, msR - msL));
    }

    /** Bake tile {@code t} by rendering just its time sub-span (see OVERSCAN_PX doc). */
    @NonNull
    private Bitmap bakeTile(int t, int tileW, int H, int Wpx, float rectLeft,
                            @NonNull BandedWaveformData raw, @NonNull ShapedTape shaped,
                            @NonNull TapeWaveformStyle style, long clipInMs, long clipDurMs) {
        Bitmap bmp = Bitmap.createBitmap(tileW, H, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        final int x0 = t * TILE_W;
        final int padL = Math.min(OVERSCAN_PX, x0);
        final int padR = Math.min(OVERSCAN_PX, Math.max(0, Wpx - (x0 + tileW)));
        final long msL = clipInMs + (long) ((double) (x0 - padL) / Wpx * clipDurMs);
        final long msR = clipInMs + (long) Math.ceil((double) (x0 + tileW + padR) / Wpx * clipDurMs);
        bakeRect.set(-padL, 0f, tileW + padR, H);
        baker.draw(c, bakeRect, raw, shaped, style, msL, Math.max(1, msR - msL));
        return bmp;
    }

    /** Drop and recycle every tile (e.g. on teardown). */
    public void clear() {
        for (Iterator<Map.Entry<String, Bitmap>> it = tiles.entrySet().iterator(); it.hasNext(); ) {
            Bitmap b = it.next().getValue();
            if (b != null && !b.isRecycled()) b.recycle();
            it.remove();
        }
        lastWidthByClip.clear();
    }
}
