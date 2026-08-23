package com.fadcam.ui.faditor.transcript;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;

public final class TranscriptParagraphs {

    public static final long GAP_BREAK_MS = 1500L;

    public static final int MIN_WORDS = 20;

    @NonNull public final int[] wordParagraph;
    @NonNull public final List<int[]> paragraphs;

    private final Transcript transcript;

    private TranscriptParagraphs(Transcript t, @NonNull int[] wordParagraph, @NonNull List<int[]> paragraphs) {
        this.transcript = t;
        this.wordParagraph = wordParagraph;
        this.paragraphs = paragraphs;
    }

    @NonNull
    public static TranscriptParagraphs of(Transcript t) {
        List<int[]> out = new ArrayList<>();
        if (t == null || t.words.isEmpty()) {
            return new TranscriptParagraphs(t, new int[0], out);
        }
        int n = t.words.size();
        int[] wp = new int[n];
        int start = 0;
        for (int i = 1; i <= n; i++) {
            boolean brk = i == n;
            if (!brk) {
                boolean forced = t.words.get(i - 1).forceLineBreakAfter;
                if (forced) {
                    brk = true;
                } else {
                    long gap = t.words.get(i).startMs - t.words.get(i - 1).endMs;
                    if (gap < 0) gap = 0;
                    int curLen = i - start;
                    if (gap > GAP_BREAK_MS && curLen >= MIN_WORDS) {
                        brk = true;
                    }
                }
            }
            if (brk) {
                int idx = out.size();
                out.add(new int[]{start, i - 1});
                for (int j = start; j < i; j++) wp[j] = idx;
                start = i;
            }
        }
        return new TranscriptParagraphs(t, wp, out);
    }

    public int paragraphOf(int wordIdx) {
        if (wordIdx < 0 || wordIdx >= wordParagraph.length) return -1;
        return wordParagraph[wordIdx];
    }

    public int paragraphCount() {
        return paragraphs.size();
    }
}
