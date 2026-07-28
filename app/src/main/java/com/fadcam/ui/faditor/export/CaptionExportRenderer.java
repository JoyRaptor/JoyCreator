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

    private final int[] wordPhrase;
    private final List<int[]> phrases = new ArrayList<>();

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
        this.wordPhrase = buildPhrases();
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
        canvas.drawColor(0, PorterDuff.Mode.CLEAR);
        lastDrawnWord = active;
        if (active < 0 || active >= wordPhrase.length) {
            return bitmap;
        }
        float emphasis = emphasisFor(active, sourceMs);
        drawPhrase(active, emphasis);
        return bitmap;
    }

    // ── Layout (mirrors CaptionOverlayView) ──────────────────────────

    private int[] buildPhrases() {
        if (transcript.words.isEmpty()) return new int[0];
        int n = transcript.words.size();
        int[] wp = new int[n];
        int start = 0;
        for (int i = 1; i <= n; i++) {
            boolean brk = i == n;
            if (!brk) {
                long gap = transcript.words.get(i).startMs - transcript.words.get(i - 1).endMs;
                brk = gap > 550 || (i - start) >= 6
                        || transcript.words.get(i - 1).forceLineBreakAfter;
            }
            if (brk) {
                int phraseIdx = phrases.size();
                phrases.add(new int[]{start, i - 1});
                for (int j = start; j < i; j++) wp[j] = phraseIdx;
                start = i;
            }
        }
        return wp;
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
        int[] phrase = phrases.get(wordPhrase[activeWordIdx]);

        // Struck (removed) words should not appear in captions.
        List<Integer> visible = new ArrayList<>();
        for (int i = phrase[0]; i <= phrase[1]; i++) {
            if (!transcript.words.get(i).struck) visible.add(i);
        }
        if (visible.isEmpty()) return;

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
                drawWord(word, x, baseY, ww, wi == activeWordIdx, emphasisValue, fontPx);
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

    private void drawWord(String word, float x, float baseY, float ww,
                          boolean active, float emphasisValue, float fontPx) {
        if (!active) {
            paintWord(word, x, baseY, style.baseColor, fontPx);
            return;
        }
        com.fadcam.ui.faditor.transcript.CaptionAnimator.Transform tf =
                com.fadcam.ui.faditor.transcript.CaptionAnimator.transform(
                        style, emphasisValue, fontPx);
        float scale = tf.scale;
        float dy = tf.dy;
        float wordCx = x + ww / 2f;
        float wordCy = baseY - (textPaint.getFontMetrics().descent
                - textPaint.getFontMetrics().ascent) * 0.35f;
        canvas.save();
        canvas.translate(0, dy);
        canvas.scale(scale, scale, wordCx, wordCy);
        paintWord(word, x, baseY, style.activeColor, fontPx);
        canvas.restore();
    }

    /** Fill pass plus optional stroke-outline pass — mirrors CaptionOverlayView. */
    private void paintWord(String word, float x, float baseY, int fillColor, float fontPx) {
        if (style.outline) {
            textPaint.setStyle(Paint.Style.STROKE);
            textPaint.setStrokeWidth(Math.max(1f, fontPx * 0.08f));
            textPaint.setColor(style.outlineColor);
            canvas.drawText(word, x, baseY, textPaint);
            textPaint.setStyle(Paint.Style.FILL);
        }
        textPaint.setColor(fillColor);
        canvas.drawText(word, x, baseY, textPaint);
    }

}
