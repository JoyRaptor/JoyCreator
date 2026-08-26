package com.fadcam.ui.faditor.compositor;

import android.content.Context;
import android.graphics.RectF;
import android.net.Uri;
import android.util.AttributeSet;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.FLog;
import com.fadcam.ui.faditor.keyframe.KeyframeSet;
import com.fadcam.ui.faditor.layers.TimedItem;
import com.fadcam.ui.faditor.model.Clip;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Preview surface for floating IMAGE-kind {@link TimedItem}s (PLAN §3.2, M-COMP-1 scope
 * item 4 — "IMAGE item plumbing"). Read-only in M-COMP-1: there is no creation UI yet
 * (nothing can produce an IMAGE track), so this view exists purely so that once M10 lets
 * users author one, preview already renders it correctly. Deliberately has no gesture
 * handling (unlike {@link com.fadcam.ui.faditor.overlay.TextOverlayLayer}) — add it
 * alongside the eventual creation UI, not here.
 *
 * <p>Mirrors {@code TextOverlayLayer}'s position/scale/opacity math exactly (same
 * {@link KeyframeSet} evaluator, same normalised-center convention against the video
 * content rect) so a future image layer behaves identically to a text/PNG overlay.</p>
 */
public class LayerImageOverlayView extends FrameLayout {

    /** Supplies the pixel rect of the visible video content (same contract as TextOverlayLayer.Callback). */
    public interface RectProvider {
        @NonNull RectF getVideoContentRect();
    }

    private final List<TimedItem> items = new ArrayList<>();
    @Nullable private RectProvider rectProvider;
    private long currentTimeMs = 0;

    // ── GL pilot: rasterised overlay texture (on-demand, not per-frame) ──────────
    // The view stays in layout as an invisible hit-test surface (alpha 0, still VISIBLE
    // so it receives touch), while drawing moves to an offscreen bitmap that FxPreview
    // composites as a GL texture at its real z. Bitmap is handed to the GL thread via
    // stillTrash() discipline — never recycled on the UI thread while GL may be uploading.
    @Nullable private android.graphics.Bitmap cachedOverlayBitmap;
    private int cachedW = -1, cachedH = -1;
    private long cachedForTimeMs = Long.MIN_VALUE;
    @NonNull private Set<String> cachedVisibleIds = new HashSet<>();
    private int rasterCount = 0;
    private long lastRasterCostMs = 0;
    private long totalRasterCostMs = 0;

    public LayerImageOverlayView(Context context) { super(context); }
    public LayerImageOverlayView(Context context, AttributeSet attrs) { super(context, attrs); }
    public LayerImageOverlayView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
    }

    public void setRectProvider(@Nullable RectProvider provider) {
        this.rectProvider = provider;
    }

    /**
     * Replace the set of IMAGE items to render. Only items whose payload is a still-image
     * {@link Clip} (IMAGE track items per {@code TimedItem} payload discriminator) make
     * sense here; callers should pre-filter to a single (currently inert) IMAGE track's
     * items. Rebuilds child views.
     */
    public void setItems(@NonNull List<TimedItem> newItems) {
        items.clear();
        items.addAll(newItems);
        // Invalidate raster cache — content changed
        synchronized (this) {
            cachedVisibleIds = new HashSet<>();
            cachedForTimeMs = Long.MIN_VALUE;
        }
        removeAllViews();
        for (TimedItem item : items) {
            Clip clip = item.getClip();
            if (clip == null) continue;
            ImageView iv = new ImageView(getContext());
            iv.setScaleType(ImageView.ScaleType.FIT_XY);
            try {
                iv.setImageURI(clip.getSourceUri());
            } catch (Exception ignored) { }
            iv.setLayoutParams(new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
            iv.setTag(item);
            addView(iv);
        }
        setPlayheadMs(currentTimeMs);
    }

    // ── Pilot measurement accessors (for report §5) ─────────────────────────
    public synchronized int getRasterCount() { return rasterCount; }
    public synchronized long getLastRasterCostMs() { return lastRasterCostMs; }
    public synchronized long getTotalRasterCostMs() { return totalRasterCostMs; }
    @Nullable public synchronized android.graphics.Bitmap getCachedOverlayBitmap() { return cachedOverlayBitmap; }
    public synchronized int getCachedBitmapBytes() {
        android.graphics.Bitmap b = cachedOverlayBitmap;
        return b == null || b.isRecycled() ? 0 : b.getByteCount();
    }

    /**
     * Rasterise this layer's visible IMAGE items to a full-frame bitmap at video
     * resolution, on demand — NOT per frame. Content changes on edit, on keyframe
     * movement, and on playhead crossings that alter what is visible. The bitmap is
     * handed to FxPreviewTextureView via stillTrash() and must not be recycled here.
     *
     * <p>Reuses the same POSITION math as {@link #position} (same KeyframeSet evaluator,
     * same normalised-center convention) so the texture and the hit-test cannot disagree.
     * Uses videoW x videoH as the content rect (full frame, no letterbox) because the
     * GL chain runs at video resolution.</p>
     *
     * <p>Returns null when nothing is visible at playheadMs (no texture needed). The
     * returned bitmap is the cached instance when nothing changed — caller must not
     * recycle it.</p>
     */
    @Nullable
    public synchronized android.graphics.Bitmap getOverlayBitmap(int videoW, int videoH, long playheadMs) {
        if (videoW <= 0 || videoH <= 0) return null;
        // Determine visible ids at playheadMs
        Set<String> visibleIds = new HashSet<>();
        List<TimedItem> visibleItems = new ArrayList<>();
        for (TimedItem it : items) {
            Clip c = it.getClip();
            if (c == null) continue;
            long start = it.getTimelineStartMs();
            long end = start + it.getDisplayDurationMs(Long.MAX_VALUE);
            boolean visible = playheadMs >= start && (end == Long.MAX_VALUE || playheadMs < end);
            if (visible) {
                visibleIds.add(it.getId());
                visibleItems.add(it);
            }
        }
        if (visibleItems.isEmpty()) {
            // Nothing to draw — keep cache but return null so GL skips compositing
            return null;
        }
        // Dirty check: size, playhead, visible set, or animated transform
        boolean dirty = cachedOverlayBitmap == null || cachedOverlayBitmap.isRecycled()
                || cachedW != videoW || cachedH != videoH
                || cachedForTimeMs != playheadMs
                || !cachedVisibleIds.equals(visibleIds);
        if (!dirty) {
            // Check animated transforms: if any visible item has a KeyframeSet with keyframes,
            // its position/alpha changes every frame and we must re-rasterise even with same ids
            for (TimedItem it : visibleItems) {
                KeyframeSet ks = it.getTransform();
                if (ks != null && !ks.isEmpty()) { dirty = true; break; }
            }
        }
        if (!dirty) return cachedOverlayBitmap;

        long t0 = android.os.SystemClock.elapsedRealtime();
        android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(videoW, videoH, android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas canvas = new android.graphics.Canvas(bmp);
        canvas.drawColor(android.graphics.Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR);
        // Content rect is full video frame at video resolution (no letterbox)
        RectF r = new RectF(0, 0, videoW, videoH);
        android.graphics.Paint rectPaint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        for (TimedItem item : visibleItems) {
            Clip clip = item.getClip();
            if (clip == null) continue;
            KeyframeSet transform = item.getTransform();
            long localMs = Math.max(0, playheadMs - item.getTimelineStartMs());
            float cx = 0.5f, cy = 0.5f, scale = 0.5f, rotation = 0f, opacity = 1f;
            if (transform != null) {
                cx = transform.valueAt(KeyframeSet.X, localMs, cx);
                cy = transform.valueAt(KeyframeSet.Y, localMs, cy);
                scale = transform.valueAt(KeyframeSet.SCALE, localMs, scale);
                rotation = transform.valueAt(KeyframeSet.ROTATION, localMs, rotation);
                opacity = transform.valueAt(KeyframeSet.OPACITY, localMs, opacity);
            }
            opacity = Math.max(0f, Math.min(1f, opacity));
            if (opacity < 0.01f) continue;
            // For pilot measurement we draw a placeholder rect (real image decode would add
            // decode+upload cost; placeholder isolates raster+GL cost). If drawable exists,
            // draw it; else draw rect.
            float h = scale * r.height();
            // Use placeholder aspect 1 for pilot; real image aspect would come from drawable
            float aspect = 1f;
            // Try to get real aspect from ImageView drawable if already laid out
            for (int i = 0; i < getChildCount(); i++) {
                View child = getChildAt(i);
                if (child.getTag() == item && child instanceof ImageView) {
                    android.graphics.drawable.Drawable d = ((ImageView) child).getDrawable();
                    if (d != null && d.getIntrinsicHeight() > 0) {
                        aspect = d.getIntrinsicWidth() / (float) d.getIntrinsicHeight();
                    }
                    break;
                }
            }
            float w = h * aspect;
            float centerX = r.left + cx * r.width();
            float centerY = r.top + cy * r.height();
            float left = centerX - w / 2f;
            float top = centerY - h / 2f;
            // Draw placeholder rect with opacity
            rectPaint.setColor(android.graphics.Color.argb(Math.round(opacity * 255), 100, 150, 200));
            rectPaint.setStyle(android.graphics.Paint.Style.FILL);
            canvas.save();
            if (rotation != 0f) canvas.rotate(rotation, centerX, centerY);
            canvas.drawRect(left, top, left + w, top + h, rectPaint);
            // If we have real bitmap for clip, draw it here (pilot leaves placeholder)
            canvas.restore();
        }
        long cost = android.os.SystemClock.elapsedRealtime() - t0;
        // Do NOT recycle previous bitmap here — it may still be held by FxPreviewTextureView's
        // layerOverlayBitmap and be in flight on GL thread (trap 6.4). The previous bitmap will
        // be queued to stillTrash when FxPreviewTextureView.setLayerOverlayBitmap replaces it,
        // which the GL thread drains at the start of drawFrame before any upload touches it.
        cachedOverlayBitmap = bmp;
        cachedW = videoW;
        cachedH = videoH;
        cachedForTimeMs = playheadMs;
        cachedVisibleIds = visibleIds;
        rasterCount++;
        lastRasterCostMs = cost;
        totalRasterCostMs += cost;
        FLog.d("GLPilot", "rasterised layer_image_overlay " + videoW + "x" + videoH
                + " visible=" + visibleItems.size() + " cost=" + cost + "ms count=" + rasterCount
                + " bytes=" + bmp.getByteCount());
        return bmp;
    }

    /** Re-evaluate each item's time window + transform for the given absolute timeline time. */
    public void setPlayheadMs(long timelineMs) {
        currentTimeMs = timelineMs;
        for (int i = 0; i < getChildCount(); i++) {
            View v = getChildAt(i);
            Object tag = v.getTag();
            if (tag instanceof TimedItem) {
                position(v, (TimedItem) tag);
            }
        }
    }

    private void position(@NonNull View view, @NonNull TimedItem item) {
        if (rectProvider == null) { view.setVisibility(GONE); return; }
        RectF r = rectProvider.getVideoContentRect();
        if (r.width() <= 0 || r.height() <= 0) { view.setVisibility(GONE); return; }

        Clip clip = item.getClip();
        if (clip == null) { view.setVisibility(GONE); return; }

        long start = item.getTimelineStartMs();
        long end = start + item.getDisplayDurationMs(Long.MAX_VALUE);
        boolean visible = currentTimeMs >= start && (end == Long.MAX_VALUE || currentTimeMs < end);
        if (!visible) { view.setVisibility(GONE); return; }
        view.setVisibility(VISIBLE);

        // Transform envelope: identity default, evaluated via the SAME KeyframeSet
        // evaluator TextOverlayItem uses (KeyframeSet#valueAt) — no new interpolation
        // code (PLAN §3.2 / final-report item 4).
        KeyframeSet transform = item.getTransform();
        long localMs = Math.max(0, currentTimeMs - start);
        float cx = 0.5f, cy = 0.5f, scale = 0.5f, rotation = 0f, opacity = 1f;
        if (transform != null) {
            cx = transform.valueAt(KeyframeSet.X, localMs, cx);
            cy = transform.valueAt(KeyframeSet.Y, localMs, cy);
            scale = transform.valueAt(KeyframeSet.SCALE, localMs, scale);
            rotation = transform.valueAt(KeyframeSet.ROTATION, localMs, rotation);
            opacity = transform.valueAt(KeyframeSet.OPACITY, localMs, opacity);
        }
        view.setAlpha(Math.max(0f, Math.min(1f, opacity)));

        float aspect = 1f;
        if (view instanceof ImageView) {
            android.graphics.drawable.Drawable d = ((ImageView) view).getDrawable();
            if (d != null && d.getIntrinsicHeight() > 0) {
                aspect = d.getIntrinsicWidth() / (float) d.getIntrinsicHeight();
            }
        }
        int h = Math.round(scale * r.height());
        int w = Math.round(h * aspect);

        float centerX = r.left + cx * r.width();
        float centerY = r.top + cy * r.height();

        LayoutParams lp = (LayoutParams) view.getLayoutParams();
        lp.width = Math.max(1, w);
        lp.height = Math.max(1, h);
        lp.leftMargin = Math.round(centerX - w / 2f);
        lp.topMargin = Math.round(centerY - h / 2f);
        view.setLayoutParams(lp);
        view.setRotation(rotation);

        // BlendMode: NORMAL only (alpha-over above, via setAlpha). Non-NORMAL blend on the
        // View layer is a later, verified step (PLAN Part 10 #4) — TODO wire
        // android.graphics.BlendMode / PorterDuff on this ImageView's Paint once verified
        // to match the export GlEffect blend closely enough (item.getBlendMode()).
    }
}
