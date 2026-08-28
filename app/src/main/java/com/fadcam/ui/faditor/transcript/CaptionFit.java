package com.fadcam.ui.faditor.transcript;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared, pure-model caption fitting. Both {@link CaptionOverlayView} (preview)
 * and {@link com.fadcam.ui.faditor.export.CaptionExportRenderer} (export) call
 * this — one measurement path, so the two cannot drift (LEDGER §3g, SPEC §5.1).
 *
 * <p>No {@code android.*} imports so the JVM harness can exercise it.
 * Callers supply a {@link Measurer} that delegates to their {@code Paint}.</p>
 */
public final class CaptionFit {

    private CaptionFit() {}

    public interface Measurer {
        /** Width of {@code text} at {@code textSizePx}. */
        float widthOf(@NonNull String text, float textSizePx);
        /** Line height at {@code textSizePx}. Default approximates preview/export Paint metrics. */
        default float lineHeight(float textSizePx) {
            return textSizePx * 1.35f;
        }
    }

    /**
     * Fit one cue's words into the box. Returns the largest size that fits,
     * never below {@code authoredSize * minScale}. If it still does not fit at
     * the floor, returns the floor — clipping is the caller's to handle.
     *
     * @param words        visible words of the phrase (in order)
     * @param authoredSize size the style asked for (px)
     * @param boxW         available width (px), already accounting for horizontal padding (e.g. *0.9)
     * @param boxH         available height (px)
     * @param minScale     floor as fraction of authoredSize (e.g. 0.45)
     * @param maxLines     0 = unlimited, else 1..N
     * @param m            measurer
     * @param hasPill      whether a background pill adds vertical padding
     */
    public static float fitSizeForWords(@NonNull List<String> words,
                                        float authoredSize,
                                        float boxW, float boxH,
                                        float minScale, int maxLines,
                                        @NonNull Measurer m,
                                        boolean hasPill) {
        if (words.isEmpty() || boxW <= 0 || boxH <= 0 || authoredSize <= 0) return authoredSize;
        float minSize = Math.max(1f, authoredSize * clampMinScale(minScale));
        if (fits(words, authoredSize, boxW, boxH, maxLines, m, hasPill)) return authoredSize;
        if (!fits(words, minSize, boxW, boxH, maxLines, m, hasPill)) return minSize;
        float lo = minSize;
        float hi = authoredSize;
        for (int i = 0; i < 16; i++) {
            float mid = (lo + hi) * 0.5f;
            if (mid == lo || mid == hi) break;
            if (fits(words, mid, boxW, boxH, maxLines, m, hasPill)) lo = mid;
            else hi = mid;
            if (hi - lo < 0.5f) break;
        }
        return lo;
    }

    /** Whether the cue at {@code size} fits the box (and maxLines). */
    public static boolean fits(@NonNull List<String> words, float size,
                               float boxW, float boxH, int maxLines,
                               @NonNull Measurer m, boolean hasPill) {
        for (String w : words) {
            if (m.widthOf(w, size) > boxW) return false;
        }
        int lines = countLines(words, size, boxW, m);
        if (maxLines > 0 && lines > maxLines) return false;
        float lineH = m.lineHeight(size);
        float totalH = lines * lineH;
        if (hasPill) totalH += size * 0.5f;
        return totalH <= boxH;
    }

    /** Greedy wrap — mirrors the line-building in both renderers. */
    public static int countLines(@NonNull List<String> words, float size,
                                 float boxW, @NonNull Measurer m) {
        if (words.isEmpty()) return 0;
        int lines = 1;
        float spaceW = m.widthOf(" ", size);
        float lineW = m.widthOf(words.get(0), size);
        for (int i = 1; i < words.size(); i++) {
            float ww = m.widthOf(words.get(i), size);
            if (lineW + spaceW + ww > boxW) {
                lines++;
                lineW = ww;
            } else {
                lineW += spaceW + ww;
            }
        }
        return lines;
    }

    /**
     * UNIFORM mode: measure every phrase and use the smallest size any of them needs.
     * Caller should cache the result per transcript+box+style (see SPEC §3.2).
     */
    public static float uniformSizeForTranscript(@NonNull Transcript transcript,
                                                 @NonNull CaptionStyle style,
                                                 float authoredSize,
                                                 float boxW, float boxH,
                                                 @NonNull Measurer m) {
        CaptionPhrases grouping = CaptionPhrases.of(transcript, style.maxWords);
        if (grouping.phrases.isEmpty()) return authoredSize;
        float fittedMin = authoredSize;
        boolean first = true;
        for (int i = 0; i < grouping.phrases.size(); i++) {
            List<Integer> visIdx = grouping.visibleWords(i);
            if (visIdx.isEmpty()) continue;
            List<String> words = new ArrayList<>(visIdx.size());
            for (int wi : visIdx) words.add(transcript.words.get(wi).text);
            float fitted = fitSizeForWords(words, authoredSize, boxW, boxH,
                    style.fitMinScale, style.fitMaxLines, m, style.pill);
            if (first) { fittedMin = fitted; first = false; }
            else fittedMin = Math.min(fittedMin, fitted);
            if (fittedMin <= authoredSize * clampMinScale(style.fitMinScale) + 0.5f) break;
        }
        return first ? authoredSize : fittedMin;
    }

    private static float clampMinScale(float s) {
        if (s < 0.15f) return 0.15f;
        if (s > 1f) return 1f;
        return s;
    }
}
