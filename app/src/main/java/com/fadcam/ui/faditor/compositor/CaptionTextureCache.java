package com.fadcam.ui.faditor.compositor;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.TextPaint;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.FLog;
import com.fadcam.ui.faditor.model.Clip;
import com.fadcam.ui.faditor.transcript.CaptionFit;
import com.fadcam.ui.faditor.transcript.CaptionPhrases;
import com.fadcam.ui.faditor.transcript.CaptionStyle;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Texture cache for captions â€” SPEC_20260829_CAPTIONS_GL Â§3.1.
 *
 * <p>Captions are the third client of {@link OverlayTextureCache}'s pattern:
 * raster per CUE (phrase) at authored size (1.5Ã— supersample), key on CONTENT
 * never on POSE. Pose (centerX/Y/sizeFraction) is a quad transform per frame.
 *
 * <p>Cache key: clipId + binding index + phrase text + activeWordIdx + style
 * content hash + fitted size + video resolution. NOT centerX/Y/sizeFraction
 * animation phase.
 *
 * <p>LRU bounded 16 entries / 64 MB like OverlayTextureCache.
 */
public final class CaptionTextureCache {

    public static final float SUPERSAMPLE = 1.5f;
    private static final int MAX_ENTRIES = 16;
    private static final long MAX_BYTES = 64L * 1024L * 1024L;

    private final LinkedHashMap<String, Bitmap> map =
            new LinkedHashMap<String, Bitmap>(16, 0.75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<String, Bitmap> eldest) { return false; }
            };
    private long currentBytes = 0L;

    /** One predicate, one place â€” texture vs Canvas fallback. Reuses OverlayTextureCache predicate shape. */
    public static boolean canUseTexture(@NonNull Clip clip) {
        if (clip == null) return false;
        String preset = clip.getCaptionAnimPreset();
        if (preset == null || "NONE".equals(preset)) return true;
        float in = clip.getCaptionAnimInPct();
        float out = clip.getCaptionAnimOutPct();
        // No in/out zone = preset stored but not armed (handles at ends) -> still texture path
        if (in <= 0.001f && out <= 0.001f) return true;
        // Any real entrance/exit animation needs per-unit per-frame pixel work:
        // MATRIX (substitution), ODOMETER (roll), MASK_WIPE (reveal), UNSCRAMBLE (scatter),
        // GHOST (blur), NEON_FLICKER (flicker) and even FADE/RISE/BEAM at LETTER granularity
        // animate every frame. Fallback to Canvas for those.
        return false;
    }

    public static boolean canUseTextureForGranularity(@NonNull Clip clip, @NonNull String gran) {
        if (!canUseTexture(clip)) return false;
        // LETTER granularity animates each glyph independently â€” needs per-glyph quads, not one phrase quad
        if ("LETTER".equals(gran)) return false;
        return true;
    }

    @NonNull
    private static String keyFor(@NonNull String clipId, int bindingIdx,
                                 @NonNull String renderedPhrase, int activeWordIdx,
                                 @NonNull CaptionStyle style, float fittedFontPx,
                                 int videoW, int videoH) {
        StringBuilder sb = new StringBuilder(256);
        sb.append(clipId).append('#').append(bindingIdx).append('|');
        sb.append(renderedPhrase).append('|');
        sb.append(activeWordIdx).append('|');
        sb.append(style.id).append(',');
        sb.append(style.baseColor).append(',').append(style.activeColor).append(',');
        sb.append(style.fontKey).append(',').append(style.bold).append(',');
        sb.append(style.pill).append(',').append(style.pillColor).append(',');
        sb.append(style.outline).append(',').append(style.outlineColor).append(',');
        sb.append(style.shadow).append(',');
        sb.append(style.fitMode.name()).append('|');
        sb.append(fittedFontPx).append('|');
        sb.append(videoW).append('x').append(videoH).append('|');
        sb.append(SUPERSAMPLE);
        return sb.toString();
    }

    @Nullable
    public synchronized Bitmap getOrCreate(@NonNull Clip clip, int bindingIdx,
                                           @NonNull Clip.CaptionBinding binding,
                                           long sourceMs,
                                           int videoW, int videoH) {
        if (videoW <= 0 || videoH <= 0) return null;
        com.fadcam.ui.faditor.transcript.NamedTranscript nt = clip.transcriptForBinding(binding);
        if (nt == null || nt.transcript == null || nt.transcript.isEmpty()) return null;
        CaptionStyle style = CaptionStyle.byId(binding.styleId);
        if ("hidden".equals(style.id)) return null;

        // Resolve phrase / active word
        CaptionPhrases grouping = CaptionPhrases.of(nt.transcript, style.maxWords);
        int activeIdx = nt.transcript.indexAtOrBeforeTime(sourceMs);
        if (activeIdx < 0) return null;
        int phraseIdx = grouping.phraseOf(activeIdx);
        if (phraseIdx < 0) return null;
        List<Integer> visible = grouping.visibleWords(phraseIdx);
        if (visible.isEmpty()) return null;
        // Per-word highlight is hard case: active word color changes pixels -> key includes activeIdx (few per sec, fine)
        StringBuilder phraseSb = new StringBuilder();
        for (int i = 0; i < visible.size(); i++) {
            if (i > 0) phraseSb.append(' ');
            phraseSb.append(nt.transcript.words.get(visible.get(i)).text);
        }
        String renderedPhrase = phraseSb.toString();

        // Fitted font size at video resolution
        float authoredPx = binding.sizeFraction * videoH;
        float boxW = videoW * 0.9f;
        float boxH = videoH * 0.9f;
        float fittedPx = fittedFontPx(nt.transcript, style, authoredPx, boxW, boxH, renderedPhrase, visible);
        if (fittedPx <= 0) fittedPx = authoredPx;

        String key = keyFor(clip.getId(), bindingIdx, renderedPhrase, activeIdx, style, fittedPx, videoW, videoH);
        Bitmap cached = map.get(key);
        if (cached != null && !cached.isRecycled()) return cached;

        Bitmap bmp = rasterizeCaption(binding, style, nt.transcript, grouping, phraseIdx, visible, activeIdx, fittedPx, videoW, videoH);
        if (bmp == null) return null;
        put(key, bmp);
        return bmp;
    }

    private static float fittedFontPx(@NonNull com.fadcam.ui.faditor.transcript.Transcript transcript,
                                      @NonNull CaptionStyle style, float authoredPx,
                                      float boxW, float boxH, @NonNull String renderedPhrase,
                                      @NonNull List<Integer> visible) {
        // Build measurer from a temp TextPaint like CaptionOverlayView
        TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        tp.setTypeface(style.typeface());
        CaptionFit.Measurer m = new CaptionFit.Measurer() {
            @Override public float widthOf(@NonNull String text, float textSizePx) {
                tp.setTextSize(textSizePx); tp.setTypeface(style.typeface());
                return tp.measureText(text);
            }
            @Override public float lineHeight(float textSizePx) {
                tp.setTextSize(textSizePx); tp.setTypeface(style.typeface());
                Paint.FontMetrics fm = tp.getFontMetrics();
                return (fm.descent - fm.ascent) * 1.15f;
            }
        };
        if (style.fitMode == CaptionStyle.FitMode.OFF) return authoredPx;
        if (style.fitMode == CaptionStyle.FitMode.UNIFORM) {
            return CaptionFit.uniformSizeForTranscript(transcript, style, authoredPx, boxW, boxH, m);
        } else {
            List<String> phraseWords = new ArrayList<>(visible.size());
            for (int wi : visible) phraseWords.add(transcript.words.get(wi).text);
            return CaptionFit.fitSizeForWords(phraseWords, authoredPx, boxW, boxH,
                    style.fitMinScale, style.fitMaxLines, m, style.pill);
        }
    }

    @Nullable
    private static Bitmap rasterizeCaption(@NonNull Clip.CaptionBinding binding,
                                           @NonNull CaptionStyle style,
                                           @NonNull com.fadcam.ui.faditor.transcript.Transcript transcript,
                                           @NonNull CaptionPhrases grouping,
                                           int phraseIdx, @NonNull List<Integer> visible,
                                           int activeWordIdx,
                                           float fittedPx, int videoW, int videoH) {
        float fontPxSup = Math.max(1f, fittedPx * SUPERSAMPLE);
        TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        tp.setTypeface(style.typeface());
        tp.setTextSize(fontPxSup);
        if (style.shadow) tp.setShadowLayer(fontPxSup * 0.12f, 0, fontPxSup * 0.05f, 0xDD000000);
        else tp.clearShadowLayer();

        float space = tp.measureText(" ");
        float maxWSup = videoW * 0.9f * SUPERSAMPLE;

        // Wrap into lines (same as CaptionOverlayView)
        List<List<Integer>> lines = new ArrayList<>();
        List<Integer> line = new ArrayList<>();
        float lineW = 0;
        for (int vi = 0; vi < visible.size(); vi++) {
            int wordIdx = visible.get(vi);
            String w = transcript.words.get(wordIdx).text;
            float ww = tp.measureText(w);
            if (!line.isEmpty() && lineW + space + ww > maxWSup) {
                lines.add(line); line = new ArrayList<>(); lineW = 0;
            }
            line.add(wordIdx);
            lineW += (line.size() > 1 ? space : 0) + ww;
        }
        if (!line.isEmpty()) lines.add(line);

        Paint.FontMetrics fm = tp.getFontMetrics();
        float lineH = (fm.descent - fm.ascent) * 1.15f;
        float totalHSup = lineH * lines.size();

        float widest = 0;
        for (List<Integer> ln : lines) {
            float w = 0;
            for (int k = 0; k < ln.size(); k++) w += (k>0?space:0) + tp.measureText(transcript.words.get(ln.get(k)).text);
            widest = Math.max(widest, w);
        }

        float padH = style.pill ? fontPxSup * 0.4f : 0f;
        float padV = style.pill ? fontPxSup * 0.25f : 0f;
        int bmpW = Math.max(1, (int)Math.ceil(widest + padH*2));
        int bmpH = Math.max(1, (int)Math.ceil(totalHSup + padV*2));
        if (bmpW > 4096 || bmpH > 4096) {
            FLog.w("CaptionTexCache", "caption raster too large " + bmpW + "x" + bmpH);
            return null;
        }
        try {
            Bitmap bmp = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bmp);
            c.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR);

            Paint pillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            pillPaint.setColor(style.pillColor);
            // Pill background spanning widest line, centered in bitmap
            if (style.pill) {
                float pillLeft = (bmpW - widest) / 2f - padH;
                float pillTop = padV - padV; // actually 0?
                // Recompute pill rect centered: bitmap center is bmpW/2, bmpH/2
                // We'll draw pill centered: use same math as CaptionOverlayView but offset to bitmap top-left
                float top = padV;
                // totalHSup centered vertically? For tight bitmap, top is padV, not centered on video.
                // That's fine for tight texture â€” quad will place center correctly.
                RectF pill = new RectF(
                        (bmpW - widest)/2f - padH, top - padV,
                        (bmpW + widest)/2f + padH, top + totalHSup + padV);
                c.drawRoundRect(pill, fontPxSup * 0.35f, fontPxSup * 0.35f, pillPaint);
            }

            float baseY = padV - fm.ascent;
            for (List<Integer> ln : lines) {
                float w = 0;
                for (int k=0;k<ln.size();k++) w += (k>0?space:0) + tp.measureText(transcript.words.get(ln.get(k)).text);
                float x = (bmpW - w) / 2f;
                for (int k=0;k<ln.size();k++) {
                    int wi = ln.get(k);
                    String word = transcript.words.get(wi).text;
                    float ww = tp.measureText(word);
                    boolean active = wi == activeWordIdx;
                    int fillColor = active ? style.activeColor : style.baseColor;
                    // Emphasis scale baked at settled 1.15 for active word (avoid per-frame raster during 300ms easing)
                    // We do not animate per-frame; we bake final emphasis.
                    float scale = active ? 1.15f : 1f;
                    // Draw with scale about word center
                    c.save();
                    float ucx = x + ww/2f;
                    float ucy = baseY - (fm.descent - fm.ascent)*0.35f;
                    if (scale != 1f) c.scale(scale, scale, ucx, ucy);
                    // Outline stroke
                    if (style.outline) {
                        tp.setStyle(Paint.Style.STROKE);
                        tp.setStrokeWidth(Math.max(1f, fontPxSup * 0.08f));
                        tp.setColor(Color.argb(255, Color.red(style.outlineColor), Color.green(style.outlineColor), Color.blue(style.outlineColor)));
                        // Apply alpha 1 (caption overall alpha handled by quad)
                        c.drawText(word, x, baseY, tp);
                        tp.setStyle(Paint.Style.FILL);
                    }
                    tp.setColor(fillColor);
                    // tp still has shadow from above; ensure fill
                    c.drawText(word, x, baseY, tp);
                    c.restore();
                    x += ww + space;
                }
                baseY += lineH;
            }
            return bmp;
        } catch (OutOfMemoryError e) {
            FLog.w("CaptionTexCache", "OOM caption raster", e);
            return null;
        }
    }

    private void put(@NonNull String key, @NonNull Bitmap bmp) {
        long bytes = bmp.getByteCount();
        while ((map.size() >= MAX_ENTRIES || currentBytes + bytes > MAX_BYTES) && !map.isEmpty()) {
            String eldest = map.keySet().iterator().next();
            Bitmap ev = map.remove(eldest);
            if (ev != null && !ev.isRecycled()) { currentBytes -= ev.getByteCount(); ev.recycle(); }
        }
        map.put(key, bmp);
        currentBytes += bytes;
        FLog.d("CaptionTexCache", "put key=" + key.hashCode() + " " + bmp.getWidth() + "x" + bmp.getHeight()
                + " bytes=" + bytes + " total=" + currentBytes + " entries=" + map.size());
    }

    public synchronized void clear() {
        for (Bitmap b: map.values()) if (b!=null && !b.isRecycled()) b.recycle();
        map.clear(); currentBytes=0L;
    }
    public synchronized int entryCount(){ return map.size(); }
    public synchronized long byteCount(){ return currentBytes; }
}
