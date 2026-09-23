package com.fadcam.ui.faditor.export;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.TextPaint;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.ui.faditor.transcript.CaptionFit;
import com.fadcam.ui.faditor.transcript.CaptionStyle;
import com.fadcam.ui.faditor.transcript.Transcript;

import java.util.ArrayList;
import java.util.List;
import com.fadcam.ui.faditor.Studio;

/**
 * Renders the animated on-screen caption to a full-frame bitmap for export.
 *
 * <p>This mirrors the on-screen {@code CaptionOverlayView} layout (phrase
 * grouping, centred multi-line wrapping, active-word emphasis) so the exported
 * file matches the preview. It is deliberately a separate class — the same
 * pattern used for {@code TextOverlayRenderer} vs the preview overlay — so the
 * preview path stays untouched.</p>
 *
 * <p>One reusable bitmap is held and re-drawn each frame to avoid per-frame
 * allocation during export.</p>
 */
public class CaptionExportRenderer {

    // (The 300ms emphasis duration lives on CaptionAnimator now — the one authority both this
    // renderer and the live preview evaluate, so it cannot be set to two different values.)

    @NonNull private final Transcript transcript;
    // NOT final: swappable via setStyle(), so a caption-style keyframe transition reuses this
    // renderer (and its full-frame bitmap) instead of building a new one.
    @NonNull private CaptionStyle style;
    private final float centerX;
    private final float centerY;
    private final float sizeFraction;
    /** Caption box width as a fraction of the frame width (0.3–1.0, default 0.9). */
    private final float boxWidthFraction;
    private final int outW;
    private final int outH;

    /**
     * Vertical growth anchor and horizontal justification inside the box
     * (SPEC_20260831_CAPTION_SLIDES) — the exact meaning CaptionOverlayView#drawSlide gives them:
     * anchor 0=centre, 1=top pinned (grows down), 2=bottom pinned (grows up);
     * justify 0=centre, 1=left edge of the box, 2=right edge of the box.
     *
     * <p>These were authored, persisted and drawn in the preview but this renderer had no field
     * for them at all, so every export re-centred the block. Set from the binding by
     * {@code CompositeExportOverlay}.</p>
     */
    private int anchor = 0;
    private int justify = 0;

    /**
     * FADE_KNOBS §2.5 caption opacity fade, as authored on the binding, plus the duration of the
     * span the fade is measured against (the caption's placement on the timeline). Zero
     * durations = no fade, which is what every project written before the knobs existed carries.
     */
    private long capFadeInMs = 0L;
    private long capFadeOutMs = 0L;
    private long capSpanDurationMs = 0L;
    /** This frame's fade multiplier, from {@link #fadeFactorAt}. 1 when no fade is authored. */
    private float frameFade = 1f;

    private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    /** Phrase grouping — the SAME class the preview uses, so both agree where a phrase begins. */
    @NonNull private com.fadcam.ui.faditor.transcript.CaptionPhrases grouping;

    // Fit cache for UNIFORM (SPEC §3.2: computed once per transcript+box+style, not per frame)
    private float cachedUniformSize = -1f;
    private float cachedUniformBoxW = -1f, cachedUniformBoxH = -1f, cachedUniformAuthored = -1f;

    // Text animation (SPEC_TEXT_ANIMATION). Defaults are the off state, so an export of a
    // project that predates the feature is byte-identical to what it was.
    @NonNull private com.fadcam.ui.faditor.transcript.CaptionAnimator.Preset animPreset =
            com.fadcam.ui.faditor.transcript.CaptionAnimator.Preset.NONE;
    @NonNull private com.fadcam.ui.faditor.transcript.CaptionAnimator.Granularity animGran =
            com.fadcam.ui.faditor.transcript.CaptionAnimator.Granularity.WORD;
    private float animInPct = 0f;
    private float animOutPct = 0f;
    // Per-phrase animation state, recomputed at the top of each drawPhrase.
    private final List<String> animWords = new ArrayList<>();
    private int animUnitCount = 1;
    private long animSpanStart = 0L, animSpanEnd = 1L, animInEff = 0L, animOutEff = 0L;
    /** The frame's source time — the animator's only clock. */
    private long frameSourceMs = 0L;

    @NonNull private final Bitmap bitmap;
    @NonNull private final Canvas canvas;
    private int lastDrawnWord = Integer.MIN_VALUE;

    public CaptionExportRenderer(@NonNull Transcript transcript, @NonNull CaptionStyle style,
                                 float centerX, float centerY, float sizeFraction,
                                 int outW, int outH) {
        this(transcript, style, centerX, centerY, sizeFraction, 0.9f, outW, outH);
    }

    public CaptionExportRenderer(@NonNull Transcript transcript, @NonNull CaptionStyle style,
                                 float centerX, float centerY, float sizeFraction,
                                 float boxWidthFraction, int outW, int outH) {
        this.transcript = transcript;
        this.style = style;
        this.centerX = centerX;
        this.centerY = centerY;
        this.sizeFraction = sizeFraction;
        this.boxWidthFraction = Math.max(0.3f, Math.min(1f, boxWidthFraction));
        this.outW = Math.max(1, outW);
        this.outH = Math.max(1, outH);
        this.bitmap = Bitmap.createBitmap(this.outW, this.outH, Bitmap.Config.ARGB_8888);
        this.canvas = new Canvas(bitmap);
        // Same grouping the preview uses - one style, one grouping, no drift. SLIDE styles
        // group one timing entry per phrase (SPEC_20260831_CAPTION_SLIDES).
        this.grouping = groupingFor(style);
    }

    /**
     * The phrase grouping a style implies. One authority, called from the constructor and from
     * {@link #setStyle}, so a swapped style cannot end up grouped by the rules of the old one.
     */
    @NonNull
    private com.fadcam.ui.faditor.transcript.CaptionPhrases groupingFor(CaptionStyle s) {
        return s != null && s.slideGroup
                ? com.fadcam.ui.faditor.transcript.CaptionPhrases.ofSlides(transcript)
                : com.fadcam.ui.faditor.transcript.CaptionPhrases.of(
                        transcript, s != null ? s.maxWords : 6);
    }

    /**
     * Swap the caption style IN PLACE, keeping the full-frame bitmap.
     *
     * <p>Caption style KEYFRAMES change the style mid-clip. The export used to answer that by
     * constructing a whole new renderer at every transition — and the constructor allocates a
     * {@code Bitmap.createBitmap(outW, outH, ARGB_8888)}, 8.3 MB at 1080p, thrown to the GC each
     * time. A clip alternating between two styles paid that on every switch and, worse, threw
     * away the fit caches with it, forcing a fresh full-transcript UNIFORM re-fit at each one.</p>
     *
     * <p>Everything derived from the style is reset here. The complete list, from a field-by-field
     * pass over this class:</p>
     * <ul>
     *   <li>{@link #style} itself;</li>
     *   <li>{@link #grouping} — depends on {@code slideGroup} and {@code maxWords};</li>
     *   <li>the UNIFORM fit cache ({@code cachedUniformSize} and its three key fields) — the fit
     *       reads {@code fitMinScale}, {@code fitMaxLines}, {@code pill} and the typeface;</li>
     *   <li>the PER_CUE fit cache ({@code cachedCuePhraseIdx}, {@code cachedCueSize} and its
     *       three key fields) — same inputs, and its phrase index is meaningless once the
     *       grouping is rebuilt;</li>
     *   <li>{@code textPaint}'s shadow layer — the only paint state that is conditional on the
     *       style ({@code style.shadow}). Typeface, text size and every colour are re-applied
     *       unconditionally at the top of each draw, and {@code pillPaint}'s colour likewise, so
     *       none of them can carry over. Cleared anyway so a half-drawn frame can never inherit
     *       the previous style's glow.</li>
     * </ul>
     *
     * <p>Nothing else in the class is style-derived: the transcript, geometry and output size are
     * construction-time identity, and the animation/box-align/fade fields come from the binding,
     * not from the style.</p>
     */
    public void setStyle(@NonNull CaptionStyle newStyle) {
        if (newStyle == this.style) return;
        this.style = newStyle;
        this.grouping = groupingFor(newStyle);
        cachedUniformSize = -1f;
        cachedUniformBoxW = -1f;
        cachedUniformBoxH = -1f;
        cachedUniformAuthored = -1f;
        cachedCuePhraseIdx = Integer.MIN_VALUE;
        cachedCueSize = -1f;
        cachedCueBoxW = -1f;
        cachedCueBoxH = -1f;
        cachedCueAuthored = -1f;
        textPaint.clearShadowLayer();
    }

    /** Output frame size the overlay bitmap is rendered at. */
    public int getWidth() { return outW; }
    public int getHeight() { return outH; }

    /**
     * Box alignment from the binding — must be the SAME two ints the preview overlay was given
     * ({@code CaptionOverlayView#setAnchor} / {@code #setJustify}), or the export re-centres a
     * block the user deliberately pinned or left-aligned.
     */
    public void setBoxAlign(int anchor, int justify) {
        this.anchor = Math.max(0, Math.min(2, anchor));
        this.justify = Math.max(0, Math.min(2, justify));
    }

    /**
     * Caption opacity fade from the binding, measured over {@code spanDurationMs} — the caption's
     * own span on the timeline, which is the span the fade knob and its veil are drawn against
     * ({@code LayerRowRenderer}: {@code item.getDisplayDurationMs}).
     */
    public void setCaptionFade(long fadeInMs, long fadeOutMs, long spanDurationMs) {
        this.capFadeInMs = Math.max(0L, fadeInMs);
        this.capFadeOutMs = Math.max(0L, fadeOutMs);
        this.capSpanDurationMs = Math.max(0L, spanDurationMs);
    }

    /**
     * Opacity multiplier at a time LOCAL to the caption's span — the same shape as
     * {@code WaveformOverlayInstance#fadeFactorAt}, kept here rather than on the model because
     * the binding is another lane's file. A negative local time means "caller has no span clock",
     * which yields 1 so the pre-fade behaviour is reproduced exactly.
     */
    private float fadeFactorAt(long localMs) {
        if (capFadeInMs <= 0L && capFadeOutMs <= 0L) return 1f;
        if (localMs < 0L) return 1f;
        long dur = capSpanDurationMs > 0L ? capSpanDurationMs : localMs + capFadeOutMs + 1L;
        long local = Math.max(0L, localMs);
        if (capFadeInMs > 0L && local < capFadeInMs) {
            return Math.max(0f, (float) local / (float) capFadeInMs);
        }
        if (capFadeOutMs > 0L && local > dur - capFadeOutMs) {
            return Math.max(0f, Math.min(1f, (float) (dur - local) / (float) capFadeOutMs));
        }
        return 1f;
    }

    /**
     * Render the caption at the given source time into the reusable bitmap and
     * return it. A fully-transparent frame is returned when no word is active.
     */
    @NonNull
    public Bitmap render(long sourceMs) {
        return render(sourceMs, -1L);
    }

    /**
     * As {@link #render(long)}, plus the frame's time LOCAL to the caption's timeline span, which
     * is what the opacity fade is measured against. Pass -1 when no such clock is available.
     */
    @NonNull
    public Bitmap render(long sourceMs, long spanLocalMs) {
        int active = transcript.indexAtOrBeforeTime(sourceMs);
        float fade = fadeFactorAt(spanLocalMs);
        boolean hasPhrase = active >= 0 && grouping.phraseOf(active) >= 0;
        float emphasis = hasPhrase ? emphasisFor(active, sourceMs) : 0f;
        // SAME PIXELS AS LAST FRAME? With no entrance/exit preset the picture is a pure function
        // of (word, emphasis, fade, style): between words — most frames — nothing moves, so the
        // raster and the upload can be skipped. A preset animates per unit per frame: never cached.
        String sig = animPreset == com.fadcam.ui.faditor.transcript.CaptionAnimator.Preset.NONE
                ? active + "|" + Math.round(emphasis * 1000f) + "|" + Math.round(fade * 1000f)
                        + "|" + style.id
                : null;
        if (sig != null && sig.equals(lastSig)) {
            lastChanged = false;
            return bitmap;
        }
        lastSig = sig;
        lastChanged = true;
        frameSourceMs = sourceMs;
        frameFade = fade;
        canvas.drawColor(0, PorterDuff.Mode.CLEAR);
        hasBounds = false;
        lastDrawnWord = active;
        if (!hasPhrase) {
            return bitmap;
        }
        drawPhrase(active, emphasis);
        return bitmap;
    }

    // ── Tight bounds (GL caption path, 2026-09-23) ──────────────────────────────────────
    @Nullable private String lastSig = null;
    private boolean lastChanged = true;
    private boolean hasBounds = false;
    private final RectF drawnBounds = new RectF();

    /** False when the last {@link #render} returned the previous frame's pixels unchanged. */
    public boolean lastRenderChanged() { return lastChanged; }

    /** The last rendered bitmap (outW x outH), valid until the next render. */
    @NonNull
    public Bitmap lastBitmap() { return bitmap; }

    /**
     * Where the last render actually drew, in bitmap pixels, padded for the active word's
     * emphasis scale, shadow and glow; false when it drew nothing.
     */
    public boolean tightBounds(@NonNull android.graphics.Rect out) {
        if (!hasBounds) return false;
        float pad = Math.max(8f, (drawnBounds.bottom - drawnBounds.top) * 0.6f);
        out.set(Math.max(0, (int) Math.floor(drawnBounds.left - pad)),
                Math.max(0, (int) Math.floor(drawnBounds.top - pad)),
                Math.min(outW, (int) Math.ceil(drawnBounds.right + pad)),
                Math.min(outH, (int) Math.ceil(drawnBounds.bottom + pad)));
        return out.width() > 0 && out.height() > 0;
    }

    private void includeBounds(float l, float t, float r, float b) {
        if (!hasBounds) {
            drawnBounds.set(l, t, r, b);
            hasBounds = true;
        } else {
            drawnBounds.union(l, t, r, b);
        }
    }

    // ── Layout (mirrors CaptionOverlayView) ──────────────────────────

    /**
     * The clip's text-animation settings, as stored names. Must be set from the SAME Clip
     * fields the preview reads, or the two paths animate differently again.
     */
    public void setCaptionAnimation(String presetName, String granularityName,
                                    float inPct, float outPct) {
        animPreset = com.fadcam.ui.faditor.transcript.CaptionAnimator.parsePreset(presetName);
        animGran = com.fadcam.ui.faditor.transcript.CaptionAnimator
                .parseGranularity(granularityName);
        animInPct = com.fadcam.ui.faditor.transcript.CaptionAnimator.clampZonePct(inPct);
        animOutPct = com.fadcam.ui.faditor.transcript.CaptionAnimator.clampZonePct(outPct);
    }

    private float emphasisFor(int activeWordIdx, long sourceMs) {
        // Delegates to the ONE authority. This used to "approximate the preview interpolators"
        // in its own arithmetic — see CaptionAnimator for why approximating the other renderer
        // is exactly the thing that must not happen here.
        return com.fadcam.ui.faditor.transcript.CaptionAnimator.emphasis(
                style, sourceMs, transcript.words.get(activeWordIdx).startMs);
    }

    private void drawPhrase(int activeWordIdx, float emphasisValue) {
        RectF r = new RectF(0, 0, outW, outH);
        int phraseIdx = grouping.phraseOf(activeWordIdx);

        // Struck (removed) words are not drawn, so they hold no animation slot either.
        List<Integer> visible = grouping.visibleWords(phraseIdx);
        if (visible.isEmpty()) return;

        // SLIDE mode: one timing entry = one wrapped box (SPEC_20260831_CAPTION_SLIDES).
        if (grouping.slideMode) {
            drawSlidePhrase(r, phraseIdx);
            return;
        }

        // The PHRASE is the animating object, matching the preview exactly.
        animWords.clear();
        for (int i : visible) animWords.add(transcript.words.get(i).text);
        long[] span = grouping.spanMs(phraseIdx);
        animUnitCount = com.fadcam.ui.faditor.transcript.CaptionAnimator
                .unitCount(animWords, animGran);
        animSpanStart = span != null ? span[0] : 0L;
        animSpanEnd = span != null ? span[1] : 1L;
        animInEff = com.fadcam.ui.faditor.transcript.CaptionAnimator
                .zoneForSpan(animInPct, animSpanEnd - animSpanStart);
        animOutEff = com.fadcam.ui.faditor.transcript.CaptionAnimator
                .zoneForSpan(animOutPct, animSpanEnd - animSpanStart);

        float authoredPx = sizeFraction * r.height();
        float boxW = r.width() * boxWidthFraction;
        float boxH = r.height() * 0.9f;
        // SPEC_20260831_CAPTION_SLIDES_UX: truncation off = width-only fit — an effectively
        // infinite box height so only width constrains and the column may run off screen.
        // Must mirror CaptionOverlayView exactly (the §3g rule).
        float fitBoxH = style.fitTruncate ? r.height() * 0.9f : r.height() * 8f;
        float fontPx;
        if (style.fitMode == CaptionStyle.FitMode.OFF) {
            fontPx = authoredPx;
        } else if (style.fitMode == CaptionStyle.FitMode.UNIFORM) {
            fontPx = getUniformFittedSize(authoredPx, boxW, fitBoxH);
        } else {
            List<String> phraseWords = new ArrayList<>(visible.size());
            for (int wi : visible) phraseWords.add(transcript.words.get(wi).text);
            fontPx = getCueFittedSize(phraseIdx, phraseWords, authoredPx, boxW, fitBoxH);
        }
        textPaint.setTextSize(fontPx);
        textPaint.setTypeface(style.typeface());
        if (style.shadow) {
            textPaint.setShadowLayer(fontPx * 0.12f, 0, fontPx * 0.05f, styleShadowColor());
        } else {
            textPaint.clearShadowLayer();
        }
        float space = textPaint.measureText(" ");

        float maxW = boxW;
        List<List<Integer>> lines = new ArrayList<>();
        List<Integer> line = new ArrayList<>();
        float lineW = 0;
        for (int vi = 0; vi < visible.size(); vi++) {
            int i = visible.get(vi);
            float w = textPaint.measureText(transcript.words.get(i).text);
            if (!line.isEmpty() && lineW + space + w > maxW) {
                lines.add(line);
                line = new ArrayList<>();
                lineW = 0;
            }
            line.add(i);
            lineW += (line.size() > 1 ? space : 0) + w;
        }
        if (!line.isEmpty()) lines.add(line);

        Paint.FontMetrics fm = textPaint.getFontMetrics();
        float lineH = (fm.descent - fm.ascent) * 1.15f;
        float totalH = lineH * lines.size();

        // SPEC_20260831_CAPTION_SLIDES_UX: same truncation as CaptionOverlayView's karaoke
        // path — drop trailing lines until the block fits the box, then mark the cut with an
        // ellipsis on the last kept word's DISPLAYED text (the transcript is never mutated).
        // Kept lines keep their word indices, so karaoke emphasis and animation are untouched.
        int ellipsisWordIdx = -1;
        if (style.fitTruncate && style.fitMode != CaptionStyle.FitMode.OFF && totalH > boxH) {
            while (lines.size() > 1 && lineH * lines.size() > boxH) {
                lines.remove(lines.size() - 1);
            }
            if (!lines.isEmpty()) {
                List<Integer> last = lines.get(lines.size() - 1);
                ellipsisWordIdx = last.get(last.size() - 1);
            }
            totalH = lineH * lines.size();
        }

        float cx = r.left + centerX * r.width();
        float cy = r.top + centerY * r.height();
        // Anchor — the SAME three-way expression CaptionOverlayView#drawSlide uses: which part of
        // the box sits at centerY when the text height changes.
        float top;
        if (anchor == 1) top = cy;
        else if (anchor == 2) top = cy - totalH;
        else top = cy - totalH / 2f;

        if (style.pill) {
            float widest = widestLine(lines, space, ellipsisWordIdx);
            float padH = fontPx * 0.4f, padV = fontPx * 0.25f;
            RectF pill = new RectF(cx - widest / 2f - padH, top - padV,
                    cx + widest / 2f + padH, top + totalH + padV);
            pillPaint.setColor(com.fadcam.ui.faditor.transcript.CaptionAnimator
                    .applyAlpha(style.pillColor, frameFade));
            includeBounds(pill.left, pill.top, pill.right, pill.bottom);
            canvas.drawRoundRect(pill, fontPx * style.pillCornerScale,
                    fontPx * style.pillCornerScale, pillPaint);
        }

        float baseY = top - fm.ascent;
        int unitPos = 0;
        for (List<Integer> ln : lines) {
            float w = 0;
            for (int k = 0; k < ln.size(); k++) {
                w += (k > 0 ? space : 0)
                        + textPaint.measureText(displayWord(ln.get(k), ellipsisWordIdx));
            }
            // Justify — the SAME three-way expression CaptionOverlayView#drawSlide uses: the line
            // sits against the box's left edge, its right edge, or centred on the anchor.
            float x;
            if (justify == 1) x = cx - boxW / 2f;
            else if (justify == 2) x = cx + boxW / 2f - w;
            else x = cx - w / 2f;
            for (int k = 0; k < ln.size(); k++) {
                int wi = ln.get(k);
                String word = displayWord(wi, ellipsisWordIdx);
                float ww = textPaint.measureText(word);
                drawWord(word, x, baseY, ww, wi == activeWordIdx, emphasisValue, fontPx, unitPos++);
                x += ww + space;
            }
            baseY += lineH;
        }
    }

    /**
     * SLIDE draw path — the export twin of {@code CaptionOverlayView#drawSlide}
     * (SPEC_20260831_CAPTION_SLIDES): one timing entry = one wrapped box shown for the
     * entry's whole span; paragraph entries wrap BY WORD via {@code layoutWords}; no karaoke
     * emphasis; the preset entrance/exit runs per word across the slide's span. Kept in
     * lockstep with the preview — the §3g rule.
     */
    private void drawSlidePhrase(@NonNull RectF r, int phraseIdx) {
        List<String> words = grouping.layoutWords(phraseIdx);
        if (words.isEmpty()) return;

        animWords.clear();
        animWords.addAll(words);
        long[] span = grouping.spanMs(phraseIdx);
        animUnitCount = com.fadcam.ui.faditor.transcript.CaptionAnimator
                .unitCount(animWords, animGran);
        animSpanStart = span != null ? span[0] : 0L;
        animSpanEnd = span != null ? span[1] : 1L;
        animInEff = com.fadcam.ui.faditor.transcript.CaptionAnimator
                .zoneForSpan(animInPct, animSpanEnd - animSpanStart);
        animOutEff = com.fadcam.ui.faditor.transcript.CaptionAnimator
                .zoneForSpan(animOutPct, animSpanEnd - animSpanStart);

        float authoredPx = sizeFraction * r.height();
        float boxW = r.width() * boxWidthFraction;
        float boxH = r.height() * 0.9f;
        // SPEC_20260831_CAPTION_SLIDES_UX: truncation off = width-only fit — an effectively
        // infinite box height so only width constrains and the column may run off screen.
        // Must mirror CaptionOverlayView#drawSlide exactly (the §3g rule).
        float fitBoxH = style.fitTruncate ? r.height() * 0.9f : r.height() * 8f;
        float fontPx;
        if (style.fitMode == CaptionStyle.FitMode.OFF) {
            fontPx = authoredPx;
        } else if (style.fitMode == CaptionStyle.FitMode.UNIFORM) {
            fontPx = getUniformFittedSize(authoredPx, boxW, fitBoxH);
        } else {
            fontPx = getCueFittedSize(phraseIdx, words, authoredPx, boxW, fitBoxH);
        }
        textPaint.setTextSize(fontPx);
        textPaint.setTypeface(style.typeface());
        if (style.shadow) {
            textPaint.setShadowLayer(fontPx * 0.12f, 0, fontPx * 0.05f, styleShadowColor());
        } else {
            textPaint.clearShadowLayer();
        }
        float space = textPaint.measureText(" ");

        List<List<String>> lines = new ArrayList<>();
        List<String> line = new ArrayList<>();
        float lineW = 0;
        for (String w : words) {
            float ww = textPaint.measureText(w);
            if (!line.isEmpty() && lineW + space + ww > boxW) {
                lines.add(line);
                line = new ArrayList<>();
                lineW = 0;
            }
            line.add(w);
            lineW += (line.size() > 1 ? space : 0) + ww;
        }
        if (!line.isEmpty()) lines.add(line);

        Paint.FontMetrics fm = textPaint.getFontMetrics();
        float lineH = (fm.descent - fm.ascent) * 1.15f;
        float totalH = lineH * lines.size();

        // SPEC_20260831_CAPTION_SLIDES_UX: same truncation as CaptionOverlayView#drawSlide —
        // drop trailing lines until the block fits the box, then ellipsize the last kept word.
        // Slide lines hold the word strings themselves, so the suffix is appended in place.
        if (style.fitTruncate && style.fitMode != CaptionStyle.FitMode.OFF && totalH > boxH) {
            while (lines.size() > 1 && lineH * lines.size() > boxH) {
                lines.remove(lines.size() - 1);
            }
            if (!lines.isEmpty()) {
                List<String> last = lines.get(lines.size() - 1);
                String lastWord = last.get(last.size() - 1);
                if (!lastWord.endsWith("…")) last.set(last.size() - 1, lastWord + "…");
            }
            totalH = lineH * lines.size();
        }

        float cx = r.left + centerX * r.width();
        float cy = r.top + centerY * r.height();
        // Anchor — mirror of CaptionOverlayView#drawSlide.
        float top;
        if (anchor == 1) top = cy;
        else if (anchor == 2) top = cy - totalH;
        else top = cy - totalH / 2f;

        float widest = 0;
        for (List<String> ln : lines) {
            float w = 0;
            for (int k = 0; k < ln.size(); k++) {
                w += (k > 0 ? space : 0) + textPaint.measureText(ln.get(k));
            }
            widest = Math.max(widest, w);
        }
        if (style.pill) {
            float padH = fontPx * 0.4f, padV = fontPx * 0.25f;
            RectF pill = new RectF(cx - widest / 2f - padH, top - padV,
                    cx + widest / 2f + padH, top + totalH + padV);
            pillPaint.setColor(com.fadcam.ui.faditor.transcript.CaptionAnimator
                    .applyAlpha(style.pillColor, frameFade));
            includeBounds(pill.left, pill.top, pill.right, pill.bottom);
            canvas.drawRoundRect(pill, fontPx * style.pillCornerScale,
                    fontPx * style.pillCornerScale, pillPaint);
        }

        float baseY = top - fm.ascent;
        int unitPos = 0;
        for (List<String> ln : lines) {
            float w = 0;
            for (int k = 0; k < ln.size(); k++) {
                w += (k > 0 ? space : 0) + textPaint.measureText(ln.get(k));
            }
            // Justify — mirror of CaptionOverlayView#drawSlide.
            float x;
            if (justify == 1) x = cx - boxW / 2f;
            else if (justify == 2) x = cx + boxW / 2f - w;
            else x = cx - w / 2f;
                for (int k = 0; k < ln.size(); k++) {
                    String word = ln.get(k);
                    float ww = textPaint.measureText(word);
                    // No karaoke emphasis in slide mode — `active` is false, so the emphasis
                    // value is never consulted; pass the neutral 1f.
                    drawWord(word, x, baseY, ww, false, 1f, fontPx, unitPos++);
                    x += ww + space;
                }
            baseY += lineH;
        }
    }

    private float widestLine(@NonNull List<List<Integer>> lines, float space, int ellipsisWordIdx) {
        float widest = 0;
        for (List<Integer> ln : lines) {
            float w = 0;
            for (int k = 0; k < ln.size(); k++) {
                w += (k > 0 ? space : 0)
                        + textPaint.measureText(displayWord(ln.get(k), ellipsisWordIdx));
            }
            widest = Math.max(widest, w);
        }
        return widest;
    }

    /**
     * Displayed text of transcript word {@code wi} — SPEC_20260831_CAPTION_SLIDES_UX truncation.
     * Mirror of CaptionOverlayView#displayWord: the ellipsis is appended to the LAST kept
     * word's DISPLAYED text only (the transcript is never mutated, so karaoke indices and
     * animation stay intact); a word that already ends with "…" is left alone.
     */
    @NonNull
    private String displayWord(int wi, int ellipsisWordIdx) {
        String t = transcript.words.get(wi).text;
        if (wi != ellipsisWordIdx || t.endsWith("…")) return t;
        return t + "…";
    }

    @NonNull
    private CaptionFit.Measurer createMeasurer() {
        // Resolved ONCE per fit — see the same hoist in CaptionOverlayView.createMeasurer and
        // CaptionTextureCache. In PER_CUE mode this measurer is rebuilt per exported frame, so an
        // unhoisted style.typeface() here multiplied a per-word cost by every frame of the export.
        final android.graphics.Typeface fitFace = style.typeface();
        return new CaptionFit.Measurer() {
            @Override public float widthOf(@NonNull String text, float textSizePx) {
                textPaint.setTextSize(textSizePx);
                textPaint.setTypeface(fitFace);
                return textPaint.measureText(text);
            }
            @Override public float lineHeight(float textSizePx) {
                textPaint.setTextSize(textSizePx);
                textPaint.setTypeface(fitFace);
                Paint.FontMetrics fm = textPaint.getFontMetrics();
                return (fm.descent - fm.ascent) * 1.15f;
            }
        };
    }

    // PER_CUE fit cache (SPEC §3.2's sibling to the UNIFORM one). A phrase's fitted size is a pure
    // function of (phrase, box, authored size, style) and the style is fixed for a renderer
    // instance — yet drawPhrase/drawSlidePhrase called createMeasurer() + fitSizeForWords INSIDE
    // the per-frame draw, so a 3-second cue re-ran a binary search over its words ~90 times to
    // arrive at the same number. That is an export-time cost proportional to cue LENGTH, which is
    // exactly backwards. Keyed on the cue index plus every other input, so a mid-export change to
    // any of them still re-fits.
    private int cachedCuePhraseIdx = Integer.MIN_VALUE;
    private float cachedCueSize = -1f;
    private float cachedCueBoxW = -1f, cachedCueBoxH = -1f, cachedCueAuthored = -1f;

    private float getCueFittedSize(int phraseIdx, @NonNull List<String> words,
                                   float authoredSize, float boxW, float boxH) {
        if (cachedCueSize > 0f && cachedCuePhraseIdx == phraseIdx
                && cachedCueBoxW == boxW && cachedCueBoxH == boxH
                && cachedCueAuthored == authoredSize) {
            return cachedCueSize;
        }
        float fitted = CaptionFit.fitSizeForWords(words, authoredSize, boxW, boxH,
                style.fitMinScale, style.fitMaxLines, createMeasurer(), style.pill);
        cachedCuePhraseIdx = phraseIdx;
        cachedCueSize = fitted;
        cachedCueBoxW = boxW; cachedCueBoxH = boxH; cachedCueAuthored = authoredSize;
        return fitted;
    }

    private float getUniformFittedSize(float authoredSize, float boxW, float boxH) {
        if (cachedUniformSize > 0
                && cachedUniformBoxW == boxW && cachedUniformBoxH == boxH
                && cachedUniformAuthored == authoredSize) {
            return cachedUniformSize;
        }
        CaptionFit.Measurer m = createMeasurer();
        float fitted = CaptionFit.uniformSizeForTranscript(transcript, style, authoredSize, boxW, boxH, m);
        cachedUniformSize = fitted;
        cachedUniformBoxW = boxW; cachedUniformBoxH = boxH; cachedUniformAuthored = authoredSize;
        return fitted;
    }

    /** Progress of one animating unit of the current phrase, from the ONE authority. */
    private int unitIndex(int wordPos, int charIdx) {
        return com.fadcam.ui.faditor.transcript.CaptionAnimator
                .unitIndexOf(animWords, animGran, wordPos, charIdx);
    }

    private float unitProgress(int unitIdx) {
        return com.fadcam.ui.faditor.transcript.CaptionAnimator.unitProgress(
                frameSourceMs, animSpanStart, animSpanEnd, animInEff, animOutEff,
                unitIdx, animUnitCount);
    }

    private void drawWord(String word, float x, float baseY, float ww,
                          boolean active, float emphasisValue, float fontPx, int wordPos) {
        Paint.FontMetrics bfm = textPaint.getFontMetrics();
        includeBounds(x, baseY + bfm.ascent, x + ww, baseY + bfm.descent);
        int color = active ? style.activeColor : style.baseColor;

        // LETTER granularity is the one case that cannot draw the word as a single run: each
        // glyph carries its own transform, so it is measured and placed individually. Mirrors
        // CaptionOverlayView exactly - the two must lay glyphs out the same way or the export
        // will not match what the editor showed.
        if (animPreset != com.fadcam.ui.faditor.transcript.CaptionAnimator.Preset.NONE
                && animGran == com.fadcam.ui.faditor.transcript.CaptionAnimator
                        .Granularity.LETTER) {
            float gx = x;
            for (int i = 0; i < word.length(); i++) {
                String ch = word.substring(i, i + 1);
                float cw = textPaint.measureText(ch);
                int uidx = unitIndex(wordPos, i);
                drawUnit(ch, gx, baseY, cw, color, fontPx, unitProgress(uidx),
                        active, emphasisValue, uidx);
                gx += cw;
            }
            return;
        }

        int uidx = unitIndex(wordPos, 0);
        drawUnit(word, x, baseY, ww, color, fontPx,
                animPreset == com.fadcam.ui.faditor.transcript.CaptionAnimator.Preset.NONE
                        ? 1f : unitProgress(uidx),
                active, emphasisValue, uidx);
    }

    /**
     * Draw one unit with both transforms composed: the PRESET (entrance/exit, driven by the
     * tape handles) and, for the active word only, the style's active-word EMPHASIS.
     */
    private void drawUnit(String text, float x, float baseY, float w, int color, float fontPx,
                          float progress, boolean active, float emphasisValue, int unitIdx) {
        // unitIdx matters to UNSCRAMBLE, which gives each unit its own scatter direction. Must stay
        // identical to CaptionOverlayView#drawUnit.
        com.fadcam.ui.faditor.transcript.CaptionAnimator.Transform pre =
                com.fadcam.ui.faditor.transcript.CaptionAnimator
                        .presetTransform(animPreset, progress, fontPx, unitIdx);
        float scaleX = pre.scaleX, scaleY = pre.scaleY, dx = pre.dx, dy = pre.dy;
        if (active) {
            com.fadcam.ui.faditor.transcript.CaptionAnimator.Transform emp =
                    com.fadcam.ui.faditor.transcript.CaptionAnimator
                            .transform(style, emphasisValue, fontPx);
            scaleX *= emp.scaleX;
            scaleY *= emp.scaleY;
            dy += emp.dy;
        }
        if (pre.alpha <= 0.004f) return;
        // MASK_WIPE keeps alpha at 1, so the test above never fires for it — mirrors
        // CaptionOverlayView#drawUnit.
        if (!com.fadcam.ui.faditor.transcript.CaptionAnimator
                .revealDrawsAnything(pre.revealFrac)) {
            return;
        }

        float ucx = x + w / 2f;
        float ucy = baseY - (textPaint.getFontMetrics().descent
                - textPaint.getFontMetrics().ascent) * 0.35f;
        // The SECOND animated channel — mirrors CaptionOverlayView#drawUnit exactly. Because
        // substituteUnit is a pure function of (text, progress, unitIdx) and both surfaces derive
        // progress from media time, the export cannot draw a different character than the preview
        // showed. That is the whole reason the churn is not random.
        String shown = com.fadcam.ui.faditor.transcript.CaptionAnimator
                .substituteUnit(animPreset, text, progress, unitIdx);

        canvas.save();
        canvas.translate(dx, dy);
        canvas.scale(scaleX, scaleY, ucx, ucy);
        // The THIRD animated channel — mirrors CaptionOverlayView#drawUnit exactly, including
        // being applied INSIDE the transform so the mask travels with the glyph. Both surfaces
        // derive revealFrac from the same evaluator and turn it into a rect with the same shared
        // helper, so neither can wipe from a different edge or to a different depth.
        if (pre.revealFrac < 1f) {
            com.fadcam.ui.faditor.transcript.CaptionAnimator
                    .revealClip(x, baseY, w, fontPx, pre.revealFrac, revealTmp);
            canvas.clipRect(revealTmp[0], revealTmp[1], revealTmp[2], revealTmp[3]);
        }
        // The FOURTH animated channel — mirrors CaptionOverlayView#drawUnit exactly. ODOMETER is
        // the only preset that rolls; for the rest this is one call and a branch. Both surfaces
        // take the two glyph rows AND the travel distance from the same two helpers, so neither
        // can roll to a different character or by a different distance than the other showed.
        com.fadcam.ui.faditor.transcript.CaptionAnimator.rollUnit(animPreset, shown, progress,
                rollTmp);
        if (rollTmp.rolling) {
            com.fadcam.ui.faditor.transcript.CaptionAnimator
                    .rollClip(x, baseY, w, fontPx, rollClipTmp);
            canvas.clipRect(rollClipTmp[0], rollClipTmp[1], rollClipTmp[2], rollClipTmp[3]);
            float slotH = rollClipTmp[3] - rollClipTmp[1];
            canvas.save();
            canvas.translate(0f, rollTmp.phase * slotH);
            paintWord(rollTmp.incoming, x, baseY, color, fontPx, pre.alpha, pre.glowPx,
                    pre.blurPx);
            canvas.restore();
            canvas.save();
            canvas.translate(0f, (rollTmp.phase - 1f) * slotH);
            paintWord(rollTmp.outgoing, x, baseY, color, fontPx, pre.alpha, pre.glowPx,
                    pre.blurPx);
            canvas.restore();
        } else {
            paintWord(shown, x, baseY, color, fontPx, pre.alpha, pre.glowPx, pre.blurPx);
        }
        canvas.restore();
    }

    /** Scratch for {@code CaptionAnimator.revealClip} — see CaptionOverlayView#revealTmp. */
    private final float[] revealTmp = new float[4];

    /** Scratch for the roll channel — see CaptionOverlayView#rollTmp. */
    private final com.fadcam.ui.faditor.transcript.CaptionAnimator.Roll rollTmp =
            new com.fadcam.ui.faditor.transcript.CaptionAnimator.Roll();
    private final float[] rollClipTmp = new float[4];

    /** Fill pass plus optional stroke-outline pass — mirrors CaptionOverlayView. */
    /** The style's own shadow, as the per-frame setup applies it — mirror of the preview's. */
    /**
     * The style's drop-shadow colour, faded WITH the glyphs. Left at a constant Studio.alpha(Studio.GROUND, 0xDD) the
     * caption's shadow stayed fully opaque while the text faded out, which reads as a black
     * smear where the caption used to be — the same reason TextBoxRenderer fades shadow+glow with
     * its glyphs. The preset's per-unit alpha is deliberately NOT folded in here: the shadow is
     * set once per frame, and the binding's fade is the only per-frame constant.
     */
    private int styleShadowColor() {
        return com.fadcam.ui.faditor.transcript.CaptionAnimator.applyAlpha(Studio.alpha(Studio.GROUND, 0xDD), frameFade);
    }

    private void applyStyleShadow(float fontPx) {
        if (style.shadow) {
            textPaint.setShadowLayer(fontPx * 0.12f, 0, fontPx * 0.05f, styleShadowColor());
        } else {
            textPaint.clearShadowLayer();
        }
    }

    private void paintWord(String word, float x, float baseY, int fillColor, float fontPx,
                           float animAlphaIn, float presetGlowPx, float presetBlurPx) {
        // FADE_KNOBS §2.5: the binding's opacity fade rides the SAME alpha the preset animation
        // already uses, so every pass that fades for an entrance fades for a caption fade too —
        // fill, outline and glow — with no second alpha chain to keep in sync.
        final float animAlpha = animAlphaIn * frameFade;
        // GHOST's blur — mirrors CaptionOverlayView#paintWord exactly. No layer switch is needed
        // on this side: the export already draws into a software Bitmap canvas, where
        // BlurMaskFilter is honoured. That asymmetry is the whole reason the preview needed
        // LAYER_TYPE_SOFTWARE and this does not, and it is why blurring only ONE of the two
        // surfaces would have been a preview/export divergence rather than a saving.
        boolean blurred = presetBlurPx > 0.25f;
        if (blurred) {
            textPaint.setMaskFilter(new android.graphics.BlurMaskFilter(
                    presetBlurPx, android.graphics.BlurMaskFilter.Blur.NORMAL));
        }
        // The PRESET's own glow (NEON_FLICKER), in the word's own fill colour. Captions have
        // no per-object glow to modulate, which is exactly why the preset supplies one — see
        // CaptionAnimator.Transform#glowPx. Drawn before the outline/fill passes and cleared
        // straight after, then the style's shadow is restored so the next word is unaffected.
        if (presetGlowPx > 0.25f) {
            textPaint.setStyle(Paint.Style.FILL);
            textPaint.setColor(com.fadcam.ui.faditor.transcript.CaptionAnimator
                    .applyAlpha(fillColor, animAlpha));
            textPaint.setShadowLayer(presetGlowPx, 0, 0,
                    com.fadcam.ui.faditor.transcript.CaptionAnimator
                            .applyAlpha(fillColor, animAlpha));
            canvas.drawText(word, x, baseY, textPaint);
            applyStyleShadow(fontPx);
        }
        if (style.outline) {
            textPaint.setStyle(Paint.Style.STROKE);
            textPaint.setStrokeWidth(Math.max(1f, fontPx * 0.08f));
            // BOTH passes fade. Fading only the fill leaves the outline at full opacity, so a
            // departing word reads as an empty outline of itself instead of as text going away.
            textPaint.setColor(com.fadcam.ui.faditor.transcript.CaptionAnimator
                    .applyAlpha(style.outlineColor, animAlpha));
            canvas.drawText(word, x, baseY, textPaint);
            textPaint.setStyle(Paint.Style.FILL);
        }
        textPaint.setColor(com.fadcam.ui.faditor.transcript.CaptionAnimator
                .applyAlpha(fillColor, animAlpha));
        canvas.drawText(word, x, baseY, textPaint);
        if (blurred) textPaint.setMaskFilter(null);
    }

}
