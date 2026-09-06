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
 * Texture cache for captions - SPEC_20260829_CAPTIONS_GL S3.1.
 *
 * <p>Captions are the third client of {@link OverlayTextureCache}'s pattern:
 * raster per CUE (phrase) at authored size (1.5x supersample), key on CONTENT
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

    /** One predicate, one place - texture vs Canvas fallback. Reuses OverlayTextureCache predicate shape. */
    public static boolean canUseTexture(@NonNull Clip clip) {
        if (clip == null) return false;
        // POSE and ALPHA properties the GL path cannot express.
        //
        // The quad that carries this texture is built in FxLivePreviewController#buildCaptionOverlays:
        // it centres the tight bitmap on (centerX, centerY) and draws it at alpha 1. So a binding that
        // asks for a non-centre growth anchor, a left/right justification inside the box, or an
        // opacity fade has no way to reach the screen through the texture path — the raster would be
        // centred and opaque no matter what the user authored, and the GL preview would disagree with
        // BOTH the Canvas preview and the export. Correct-and-slower beats silently-wrong: fall the
        // whole clip's captions back to the Canvas overlay, which honours all three.
        for (Clip.CaptionBinding b : clip.getCaptionBindings()) {
            if (b == null || !b.enabled) continue;
            if (b.anchor != 0 || b.justify != 0) return false;
            if (b.fadeInMs > 0 || b.fadeOutMs > 0) return false;
        }
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
        // LETTER granularity animates each glyph independently - needs per-glyph quads, not one phrase quad
        if ("LETTER".equals(gran)) return false;
        return true;
    }

    @NonNull
    private static String keyFor(@NonNull String clipId, int bindingIdx,
                                 @NonNull String renderedPhrase, int activeWordIdx,
                                 @NonNull CaptionStyle style, float fittedFontPx,
                                 float boxWidthFraction, int videoW, int videoH) {
        StringBuilder sb = new StringBuilder(256);
        sb.append(clipId).append('#').append(bindingIdx).append('|');
        sb.append(renderedPhrase).append('|');
        sb.append(activeWordIdx).append('|');
        sb.append(style.id).append(',');
        sb.append(style.baseColor).append(',').append(style.activeColor).append(',');
        sb.append(style.fontKey).append(',').append(style.bold).append(',');
        sb.append(style.pill).append(',').append(style.pillColor).append(',');
        // Corner radius is baked into the raster: without it a Corners-slider change
        // reuses the previously rounded bitmap and the GL path shows a stale radius.
        sb.append(style.pillCornerScale).append(',');
        sb.append(style.outline).append(',').append(style.outlineColor).append(',');
        sb.append(style.shadow).append(',');
        sb.append(style.fitMode.name()).append(',').append(style.fitTruncate).append('|');
        sb.append(fittedFontPx).append('|');
        // The wrap uses the box width, not just the fitted size: two widths can need the same
        // font size yet wrap differently, so the width fraction must be part of the key.
        // (Wireless-install round trip 2026-08-31: forced repackage so the APK carries this fix. Second trigger.)
        sb.append(Math.max(0.3f, Math.min(1f, boxWidthFraction))).append('|');
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

        // Resolve phrase / active word — SLIDE styles group one timing entry per phrase
        // (SPEC_20260831_CAPTION_SLIDES), and layoutWords splits paragraph entries so they
        // wrap by word inside the box.
        CaptionPhrases grouping = style.slideGroup
                ? CaptionPhrases.ofSlides(nt.transcript)
                : CaptionPhrases.of(nt.transcript, style.maxWords);
        int activeIdx = nt.transcript.indexAtOrBeforeTime(sourceMs);
        if (activeIdx < 0) return null;
        int phraseIdx = grouping.phraseOf(activeIdx);
        if (phraseIdx < 0) return null;
        List<Integer> visible = grouping.visibleWords(phraseIdx);
        if (visible.isEmpty()) return null;
        // Layout words: slide mode may split an entry's paragraph text into real words here.
        List<String> layoutWords = grouping.layoutWords(phraseIdx);
        // Per-word highlight is hard case: active word color changes pixels -> key includes activeIdx (few per sec, fine)
        StringBuilder phraseSb = new StringBuilder();
        for (int i = 0; i < layoutWords.size(); i++) {
            if (i > 0) phraseSb.append(' ');
            phraseSb.append(layoutWords.get(i));
        }
        String renderedPhrase = phraseSb.toString();

        // Fitted font size at video resolution
        float boxW = videoW * Math.max(0.3f, Math.min(1f, binding.boxWidthFraction));
        float authoredPx = binding.sizeFraction * videoH;
        float boxH = videoH * 0.9f;
        // SPEC_20260831_CAPTION_SLIDES_UX: truncation off = width-only fit — an effectively
        // infinite box height (frame height * 8, the same rule as preview + export) so only
        // width constrains and the column may run off screen.
        float fitBoxH = style.fitTruncate ? boxH : videoH * 8f;
        float fittedPx = fittedFontPx(nt.transcript, style, authoredPx, boxW, fitBoxH, renderedPhrase, layoutWords);
        if (fittedPx <= 0) fittedPx = authoredPx;

        String key = keyFor(clip.getId(), bindingIdx, renderedPhrase, activeIdx, style, fittedPx, binding.boxWidthFraction, videoW, videoH);
        Bitmap cached = map.get(key);
        if (cached != null && !cached.isRecycled()) return cached;

        Bitmap bmp = rasterizeCaption(binding, style, nt.transcript, grouping, phraseIdx, layoutWords, activeIdx, fittedPx, videoW, videoH);
        if (bmp == null) return null;
        put(key, bmp);
        return bmp;
    }

    private static float fittedFontPx(@NonNull com.fadcam.ui.faditor.transcript.Transcript transcript,
                                      @NonNull CaptionStyle style, float authoredPx,
                                      float boxW, float boxH, @NonNull String renderedPhrase,
                                      @NonNull List<String> layoutWords) {
        // Build measurer from a temp TextPaint like CaptionOverlayView
        TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        // ONE typeface resolution for the whole fit, not one per measured word. The fitter calls
        // widthOf on the order of 10^5 times for a long transcript in UNIFORM mode, and
        // style.typeface() is a family switch plus a substring + map lookup + Typeface.create on
        // every call. This is the same defect that made CaptionOverlayView.onDraw ANR; the face
        // is identical for every measurement in a fit, so hoisting changes no result.
        final android.graphics.Typeface fitFace = style.typeface();
        tp.setTypeface(fitFace);
        CaptionFit.Measurer m = new CaptionFit.Measurer() {
            @Override public float widthOf(@NonNull String text, float textSizePx) {
                tp.setTextSize(textSizePx); tp.setTypeface(fitFace);
                return tp.measureText(text);
            }
            @Override public float lineHeight(float textSizePx) {
                tp.setTextSize(textSizePx); tp.setTypeface(fitFace);
                Paint.FontMetrics fm = tp.getFontMetrics();
                return (fm.descent - fm.ascent) * 1.15f;
            }
        };
        if (style.fitMode == CaptionStyle.FitMode.OFF) return authoredPx;
        if (style.fitMode == CaptionStyle.FitMode.UNIFORM) {
            return CaptionFit.uniformSizeForTranscript(transcript, style, authoredPx, boxW, boxH, m);
        } else {
            return CaptionFit.fitSizeForWords(layoutWords, authoredPx, boxW, boxH,
                    style.fitMinScale, style.fitMaxLines, m, style.pill);
        }
    }

    @Nullable
    private static Bitmap rasterizeCaption(@NonNull Clip.CaptionBinding binding,
                                           @NonNull CaptionStyle style,
                                           @NonNull com.fadcam.ui.faditor.transcript.Transcript transcript,
                                           @NonNull CaptionPhrases grouping,
                                           int phraseIdx, @NonNull List<String> layoutWords,
                                           int activeWordIdx,
                                           float fittedPx, int videoW, int videoH) {
        boolean slide = grouping.slideMode;
        float fontPxSup = Math.max(1f, fittedPx * SUPERSAMPLE);
        TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        tp.setTypeface(style.typeface());
        tp.setTextSize(fontPxSup);
        if (style.shadow) tp.setShadowLayer(fontPxSup * 0.12f, 0, fontPxSup * 0.05f, 0xDD000000);
        else tp.clearShadowLayer();

        float space = tp.measureText(" ");
        float maxWSup = videoW * Math.max(0.3f, Math.min(1f, binding.boxWidthFraction)) * SUPERSAMPLE;

        // Wrap into lines (same as CaptionOverlayView). SLIDE mode wraps the layout words —
        // an entry holding a whole paragraph wraps by word inside the box. `lineSrc` carries,
        // per layout word, the ENTRY index it came from (-1 in slide mode, where there is no
        // per-word highlight), so normal mode keeps its exact karaoke raster.
        List<List<String>> lines = new ArrayList<>();
        List<List<Integer>> lineSrc = new ArrayList<>();
        List<String> line = new ArrayList<>();
        List<Integer> lineIdxs = new ArrayList<>();
        float lineW = 0;
        // Hoisted out of the loop: CaptionPhrases.visibleWords BUILDS a fresh ArrayList by
        // scanning the phrase on every call, so asking it once per layout word made this wrap
        // quadratic in the phrase length. It cannot change while we lay out one phrase, so one
        // lookup serves the whole loop. Cache-miss path only — this raster runs once per
        // (phrase, active word), not per frame.
        List<Integer> visibleEntries = slide ? null : grouping.visibleWords(phraseIdx);
        for (int i = 0; i < layoutWords.size(); i++) {
            String w = layoutWords.get(i);
            int src = (visibleEntries != null && i < visibleEntries.size())
                    ? visibleEntries.get(i) : -1;
            float ww = tp.measureText(w);
            if (!line.isEmpty() && lineW + space + ww > maxWSup) {
                lines.add(line); lineSrc.add(lineIdxs); line = new ArrayList<>(); lineIdxs = new ArrayList<>(); lineW = 0;
            }
            line.add(w);
            lineIdxs.add(src);
            lineW += (line.size() > 1 ? space : 0) + ww;
        }
        if (!line.isEmpty()) { lines.add(line); lineSrc.add(lineIdxs); }

        Paint.FontMetrics fm = tp.getFontMetrics();
        float lineH = (fm.descent - fm.ascent) * 1.15f;
        float totalHSup = lineH * lines.size();

        // SPEC_20260831_CAPTION_SLIDES_UX: same truncation rule as the Canvas preview — drop
        // trailing lines until the block fits the box, then ellipsize the last kept word.
        // lineSrc is parallel to lines and must lose the same rows.
        float boxHSup = videoH * 0.9f * SUPERSAMPLE;
        if (style.fitTruncate && style.fitMode != CaptionStyle.FitMode.OFF && totalHSup > boxHSup) {
            while (lines.size() > 1 && lineH * lines.size() > boxHSup) {
                lines.remove(lines.size() - 1);
                lineSrc.remove(lineSrc.size() - 1);
            }
            if (!lines.isEmpty()) {
                List<String> last = lines.get(lines.size() - 1);
                String lastWord = last.get(last.size() - 1);
                if (!lastWord.endsWith("…")) last.set(last.size() - 1, lastWord + "…");
            }
            totalHSup = lineH * lines.size();
        }

        float widest = 0;
        for (List<String> ln : lines) {
            float w = 0;
            for (int k = 0; k < ln.size(); k++) w += (k>0?space:0) + tp.measureText(ln.get(k));
            widest = Math.max(widest, w);
        }

        float padH = style.pill ? fontPxSup * 0.4f : 0f;
        float padV = style.pill ? fontPxSup * 0.25f : 0f;

        // BLEED — ink that lands OUTSIDE the text block's own box, which the tight bitmap used to
        // clip away for every no-pill style (padH/padV are 0 unless a pill is drawn, and the
        // built-in styles all draw a shadow). Three sources, all present below in this method:
        //   * the drop shadow: radius fontPxSup*0.12 with a +0.05 y-offset  -> 0.17 at the bottom;
        //   * the outline stroke: width fontPxSup*0.08, half of it outside  -> 0.04;
        //   * the active word's baked 1.15 emphasis about its own centre    -> 7.5% of its size.
        // The bleed is applied SYMMETRICALLY (the worst case of every side used on all sides) and
        // the draw origin is shifted by exactly the same amount, so the block stays dead-centre in
        // the bitmap. That matters because the quad places the texture by its CENTRE
        // (FxLivePreviewController#buildCaptionOverlays derives halfW/halfH from the bitmap size):
        // a symmetric inflation grows the quad about the same centre and the caption's on-screen
        // pose does not move. Asymmetric padding would have shifted it.
        float inkBleed = fontPxSup * (0.17f + 0.04f);
        // SLIDE mode has no karaoke emphasis (see the `active` computation below), so no overshoot.
        float bleedH = inkBleed + (slide ? 0f : widest * 0.075f);
        float bleedV = inkBleed + (slide ? 0f : lineH * 0.15f);
        int bmpW = Math.max(1, (int)Math.ceil(widest + padH*2 + bleedH*2));
        int bmpH = Math.max(1, (int)Math.ceil(totalHSup + padV*2 + bleedV*2));
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
            // The text block's top edge inside the bitmap: the vertical bleed, then the pill pad.
            // Horizontal placement stays centre-derived ((bmpW ± w)/2), so it absorbs the bleed
            // automatically and the block remains centred in the inflated bitmap.
            float blockTop = bleedV + padV;
            if (style.pill) {
                RectF pill = new RectF(
                        (bmpW - widest)/2f - padH, blockTop - padV,
                        (bmpW + widest)/2f + padH, blockTop + totalHSup + padV);
                c.drawRoundRect(pill, fontPxSup * style.pillCornerScale,
                        fontPxSup * style.pillCornerScale, pillPaint);
            }

            float baseY = blockTop - fm.ascent;
            for (int li = 0; li < lines.size(); li++) {
                List<String> ln = lines.get(li);
                List<Integer> srcs = lineSrc.get(li);
                float w = 0;
                for (int k=0;k<ln.size();k++) w += (k>0?space:0) + tp.measureText(ln.get(k));
                float x = (bmpW - w) / 2f;
                for (int k=0;k<ln.size();k++) {
                    String word = ln.get(k);
                    float ww = tp.measureText(word);
                    // SLIDE mode: no karaoke emphasis — the whole slide reads as one block.
                    boolean active = !slide && srcs.get(k) == activeWordIdx;
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
                        // The outline colour is used AS AUTHORED, alpha included. Forcing it to
                        // argb(255, …) here made a deliberately semi-transparent outline draw solid
                        // in the GL preview while both Canvas paths honoured the alpha — the same
                        // style read two different ways on the same screen.
                        tp.setColor(style.outlineColor);
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

