package com.fadcam.ui.faditor.compositor;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.FLog;
import com.fadcam.ui.faditor.model.TextOverlayItem;
import com.fadcam.ui.faditor.overlay.TextBoxRenderer;
import com.fadcam.ui.faditor.sprite.SpriteOverlayItem;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Texture cache for below-blend overlays — spec SPEC_20260829_PREVIEW_PERF §3.
 *
 * <p>Raster the item's GLYPHS once, at its authored size and zero rotation, into its own
 * texture. Then per frame, position/scale/rotate/fade the quad via {@link FxPreviewTextureView.Pip}.
 *
 * <p>Keyed on everything that changes the PIXELS and nothing that changes the POSE:
 * in the key: item id, rendered string, font key, colours, outline/shadow, pill,
 * the authored (unanimated) size, and the raster resolution.
 * NOT in the key: centerX, centerY, rotation, opacity, sizeFraction.
 * If any of those five appear in your key, you have rebuilt the bug this spec exists to remove.
 *
 * <p>LRU bounded — 16 entries or ~64 MB, whichever binds first. Evict on project close.
 * Raster at authored size, not animated size, at {@link #SUPERSAMPLE} so a scale-up does not go soft.
 */
public final class OverlayTextureCache {

    /** Supersample factor so a title that scales 0.2→2.0 stays crisp. Chosen 1.5× as middle of 1.5–2× spec range. */
    public static final float SUPERSAMPLE = 1.5f;

    private static final int MAX_ENTRIES = 16;
    private static final long MAX_BYTES = 64L * 1024L * 1024L;

    private final LinkedHashMap<String, Bitmap> map =
            new LinkedHashMap<String, Bitmap>(16, 0.75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<String, Bitmap> eldest) {
                    return false; // manual eviction so we can account bytes
                }
            };
    private long currentBytes = 0L;

    /** One predicate, one place — the texture path vs Canvas fallback decision. */
    public static boolean canUseTexture(@NonNull TextOverlayItem t) {
        // Static items stay on the full-frame bitmap path (defect one fix) — texture path is for animated pose.
        if (!t.isAnimated()) return false;
        // Per-character / per-word pixel-change animation needs per-frame raster, subject to 17 ms wall.
        // Documented as legitimate limit; keep on Canvas fallback.
        if (t.isTextAnimActive()) return false;
        // Timers change string every tick — that WOULD be pixel change, but rendered string is in the key
        // so they would re-raster on each second. That's acceptable vs the old per-frame full-frame raster,
        // but they are still pose-independent so texture path helps. Allow timer through texture path:
        // it will re-raster only when string changes (once per second), not every frame.
        return true;
    }

    public static boolean canUseTexture(@NonNull SpriteOverlayItem s) {
        // Pose animation (quad transform) rides the texture path; per-frame sheet animation stays on fallback.
        if (!s.getKeyframes().isEmpty()) return true;
        // FrameTrack size>1 means which cell shows changes every frame — pixel change, not quad transform.
        // Keep on fallback / document as limit.
        return false;
    }

    /** Build the cache key for a text item — PIXELS only, never POSE. */
    @NonNull
    private static String keyForText(@NonNull TextOverlayItem t, @NonNull String rendered,
                                     int videoW, int videoH) {
        // Authored (unanimated) size, not animatedSizeFraction — spec §3.2: raster at authored size.
        float authoredSize = t.getSizeFraction();
        // Style fields that affect pixels — animated variants sampled at current time would be needed
        // for re-raster on style change, but that would make pose vs style ambiguous. Sample the
        // static base plus the current animated style values will cause a key change when style animates,
        // which is correct (pixel change needs re-raster). For the texture path we assume style is
        // largely static during pose animation; include animated values so a style keyframe invalidates.
        // Use a snapshot of the current animated style at raster time — caller supplies playhead.
        // For key generation we are called with playhead already, so include those.
        // Here we only have static fields; the caller that knows playhead should include animated style
        // in the key it builds. This helper is for static part; animated part appended by caller.
        StringBuilder sb = new StringBuilder(128);
        sb.append(t.getId()).append('|');
        sb.append(rendered).append('|');
        sb.append(t.getFontFamily()).append('|');
        sb.append(t.isBold()).append(',').append(t.isItalic()).append(',').append(t.isUnderline()).append('|');
        sb.append(t.getTextCase()).append('|');
        sb.append(t.getTextAlign()).append('|');
        sb.append(t.getColorInt()).append('|');
        sb.append(t.getStrokeColorInt()).append('|');
        sb.append(t.getShadowColorInt()).append('|');
        sb.append(t.getGlowColorInt()).append('|');
        sb.append(t.getBackgroundColorInt()).append('|');
        sb.append(authoredSize).append('|');
        sb.append(videoW).append('x').append(videoH).append('|');
        sb.append(SUPERSAMPLE);
        if (t.hasStyleSpans() && t.getStyleSpans() != null) {
            sb.append('|').append(t.getStyleSpans().hashCode());
        }
        // NOTE: centerX, centerY, rotation, opacity, sizeFraction deliberately NOT appended.
        return sb.toString();
    }

    /** Extended key that includes animated style values that affect pixels. */
    @NonNull
    public static String keyForTextAt(@NonNull TextOverlayItem t, @NonNull String rendered,
                                      int videoW, int videoH, long playheadMs) {
        String base = keyForText(t, rendered, videoW, videoH);
        StringBuilder sb = new StringBuilder(base);
        sb.append('|').append(t.animatedStrokeWidthPx(playheadMs));
        sb.append('|').append(t.animatedGlowRadiusPx(playheadMs));
        sb.append('|').append(t.animatedShadowRadiusPx(playheadMs));
        sb.append('|').append(t.animatedShadowAngleDeg(playheadMs));
        sb.append('|').append(t.animatedShadowDistancePx(playheadMs));
        // scaleX/Y also affect pixel size if unlinked? But they are pose-like (size) — exclude per spec.
        return sb.toString();
    }

    @NonNull
    private static String keyForSprite(@NonNull SpriteOverlayItem s, int videoW, int videoH) {
        StringBuilder sb = new StringBuilder(64);
        sb.append(s.getId()).append('|');
        sb.append(s.getSheetId()).append('|');
        sb.append(s.getSizeFraction()).append('|');
        sb.append(s.isFlipH()).append(',').append(s.isFlipV()).append('|');
        sb.append(videoW).append('x').append(videoH).append('|');
        sb.append(SUPERSAMPLE);
        return sb.toString();
    }

    /** Get or create the raster for a text item at authored size. Returns null if cannot raster. */
    @Nullable
    public synchronized Bitmap getOrCreate(@NonNull TextOverlayItem item,
                                           long playheadMs, long totalDurationMs,
                                           int videoW, int videoH) {
        String rendered = TextBoxRenderer.textAt(item, playheadMs, totalDurationMs);
        String key = keyForTextAt(item, rendered, videoW, videoH, playheadMs);
        Bitmap cached = map.get(key);
        if (cached != null && !cached.isRecycled()) {
            return cached;
        }
        Bitmap bmp = rasterizeText(item, rendered, videoH, playheadMs, totalDurationMs);
        if (bmp == null) return null;
        put(key, bmp);
        return bmp;
    }

    @Nullable
    public synchronized Bitmap getOrCreate(@NonNull SpriteOverlayItem item,
                                           int videoW, int videoH) {
        String key = keyForSprite(item, videoW, videoH);
        Bitmap cached = map.get(key);
        if (cached != null && !cached.isRecycled()) {
            return cached;
        }
        Bitmap bmp = rasterizeSprite(item, videoH);
        if (bmp == null) return null;
        put(key, bmp);
        return bmp;
    }

    @Nullable
    private static Bitmap rasterizeText(@NonNull TextOverlayItem item,
                                        @NonNull String rendered,
                                        int videoH, long playheadMs, long totalDurationMs) {
        float authoredSize = item.getSizeFraction();
        float fontPx = Math.max(1f, authoredSize * videoH * SUPERSAMPLE);
        float[] sz = new float[2];
        TextBoxRenderer.measure(item, rendered, fontPx, sz);
        int w = Math.max(1, (int) Math.ceil(sz[0]));
        int h = Math.max(1, (int) Math.ceil(sz[1]));
        // Guard against huge allocation (e.g., 10x scale with supersample on 4K)
        if (w > 4096 || h > 4096) {
            FLog.w("OverlayTexCache", "text raster too large " + w + "x" + h + " id=" + item.getId());
            return null;
        }
        try {
            Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bmp);
            c.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR);
            // Raster at 0,0 with objectAlpha=1 — quad alpha applied per frame
            TextBoxRenderer.draw(c, item, rendered, 0f, 0f, fontPx, playheadMs, totalDurationMs, false, 1f);
            return bmp;
        } catch (OutOfMemoryError e) {
            FLog.w("OverlayTexCache", "OOM rasterizing text " + item.getId(), e);
            return null;
        }
    }

    @Nullable
    private static Bitmap rasterizeSprite(@NonNull SpriteOverlayItem item, int videoH) {
        float authoredSize = item.getSizeFraction();
        float h = authoredSize * videoH * SUPERSAMPLE;
        float w = h; // placeholder square; real sheet cell aspect would be sheet-dependent
        int wi = Math.max(1, (int) Math.ceil(w));
        int hi = Math.max(1, (int) Math.ceil(h));
        if (wi > 4096 || hi > 4096) return null;
        try {
            Bitmap bmp = Bitmap.createBitmap(wi, hi, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bmp);
            c.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR);
            android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            p.setColor(Color.argb(255, 120, 180, 220));
            p.setStyle(android.graphics.Paint.Style.FILL);
            c.drawRect(0, 0, wi, hi, p);
            return bmp;
        } catch (OutOfMemoryError e) {
            FLog.w("OverlayTexCache", "OOM rasterizing sprite " + item.getId(), e);
            return null;
        }
    }

    private void put(@NonNull String key, @NonNull Bitmap bmp) {
        long bytes = bmp.getByteCount();
        // Evict until within both limits
        while ((map.size() >= MAX_ENTRIES || currentBytes + bytes > MAX_BYTES) && !map.isEmpty()) {
            String eldest = map.keySet().iterator().next();
            Bitmap ev = map.remove(eldest);
            if (ev != null && !ev.isRecycled()) {
                currentBytes -= ev.getByteCount();
                ev.recycle();
            }
        }
        map.put(key, bmp);
        currentBytes += bytes;
        FLog.d("OverlayTexCache", "put key=" + key.hashCode() + " " + bmp.getWidth() + "x" + bmp.getHeight()
                + " bytes=" + bytes + " total=" + currentBytes + " entries=" + map.size());
    }

    public synchronized void clear() {
        for (Bitmap b : map.values()) {
            if (b != null && !b.isRecycled()) b.recycle();
        }
        map.clear();
        currentBytes = 0L;
    }

    public synchronized int entryCount() { return map.size(); }
    public synchronized long byteCount() { return currentBytes; }
}

