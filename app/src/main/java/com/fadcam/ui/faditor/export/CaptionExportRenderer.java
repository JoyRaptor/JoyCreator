package com.fadcam.ui.faditor.export;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.TextPaint;

import androidx.annotation.NonNull;

import com.fadcam.ui.faditor.transcript.CaptionStyle;
import com.fadcam.ui.faditor.transcript.Transcript;

import java.util.ArrayList;
import java.util.List;

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
    @NonNull private final CaptionStyle style;
    private final float centerX;
    private final float centerY;
    private final float sizeFraction;
    private final int outW;
    private final int outH;

    private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    /** Phrase grouping — the SAME class the preview uses, so both agree where a phrase begins. */
    @NonNull private final com.fadcam.ui.faditor.transcript.CaptionPhrases grouping;

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
        this.transcript = transcript;
        this.style = style;
        this.centerX = centerX;
        this.centerY = centerY;
        this.sizeFraction = sizeFraction;
        this.outW = Math.max(1, outW);
        this.outH = Math.max(1, outH);
        this.bitmap = Bitmap.createBitmap(this.outW, this.outH, Bitmap.Config.ARGB_8888);
        this.canvas = new Canvas(bitmap);
        this.grouping = com.fadcam.ui.faditor.transcript.CaptionPhrases.of(transcript);
    }

    /** Output frame size the overlay bitmap is rendered at. */
    public int getWidth() { return outW; }
    public int getHeight() { return outH; }

    /**
     * Render the caption at the given source time into the reusable bitmap and
     * return it. A fully-transparent frame is returned when no word is active.
     */
    @NonNull
    public Bitmap render(long sourceMs) {
        int active = transcript.indexAtOrBeforeTime(sourceMs);
        frameSourceMs = sourceMs;
        canvas.drawColor(0, PorterDuff.Mode.CLEAR);
        lastDrawnWord = active;
        if (active < 0 || grouping.phraseOf(active) < 0) {
            return bitmap;
        }
        float emphasis = emphasisFor(active, sourceMs);
        drawPhrase(active, emphasis);
        return bitmap;
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

        float fontPx = sizeFraction * r.height();
        textPaint.setTextSize(fontPx);
        textPaint.setTypeface(style.typeface());
        if (style.shadow) {
            textPaint.setShadowLayer(fontPx * 0.12f, 0, fontPx * 0.05f, 0xDD000000);
        } else {
            textPaint.clearShadowLayer();
        }
        float space = textPaint.measureText(" ");

        float maxW = r.width() * 0.9f;
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

        float cx = r.left + centerX * r.width();
        float cy = r.top + centerY * r.height();
        float top = cy - totalH / 2f;

        if (style.pill) {
            float widest = widestLine(lines, space);
            float padH = fontPx * 0.4f, padV = fontPx * 0.25f;
            RectF pill = new RectF(cx - widest / 2f - padH, top - padV,
                    cx + widest / 2f + padH, top + totalH + padV);
            pillPaint.setColor(style.pillColor);
            canvas.drawRoundRect(pill, fontPx * 0.35f, fontPx * 0.35f, pillPaint);
        }

        float baseY = top - fm.ascent;
        int unitPos = 0;
        for (List<Integer> ln : lines) {
            float w = 0;
            for (int k = 0; k < ln.size(); k++) {
                w += (k > 0 ? space : 0) + textPaint.measureText(transcript.words.get(ln.get(k)).text);
            }
            float x = cx - w / 2f;
            for (int k = 0; k < ln.size(); k++) {
                int wi = ln.get(k);
                String word = transcript.words.get(wi).text;
                float ww = textPaint.measureText(word);
                drawWord(word, x, baseY, ww, wi == activeWordIdx, emphasisValue, fontPx, unitPos++);
                x += ww + space;
            }
            baseY += lineH;
        }
    }

    private float widestLine(@NonNull List<List<Integer>> lines, float space) {
        float widest = 0;
        for (List<Integer> ln : lines) {
            float w = 0;
            for (int k = 0; k < ln.size(); k++) {
                w += (k > 0 ? space : 0) + textPaint.measureText(transcript.words.get(ln.get(k)).text);
            }
            widest = Math.max(widest, w);
        }
        return widest;
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
        paintWord(shown, x, baseY, color, fontPx, pre.alpha);
        canvas.restore();
    }

    /** Scratch for {@code CaptionAnimator.revealClip} — see CaptionOverlayView#revealTmp. */
    private final float[] revealTmp = new float[4];

    /** Fill pass plus optional stroke-outline pass — mirrors CaptionOverlayView. */
    private void paintWord(String word, float x, float baseY, int fillColor, float fontPx,
                           float animAlpha) {
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
    }

}
