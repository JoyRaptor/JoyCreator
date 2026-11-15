package com.fadcam.ui.faditor.model;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A clip's cut spans come from two places: struck transcript words, and silence gaps cut with
 * Clean. Striking or restoring a word rebuilds the word half from the transcript; this keeps the
 * silence half (job STU-B3, found 11-15: one strike used to wipe every Clean cut on the clip).
 *
 * <p>A span already on the clip counts as a silence cut, and is kept, when less than half of it
 * lies under words (struck or not, as padded for cutting); anything else came from the words and
 * is replaced by the fresh word spans. The result is sorted and merged. Pure.</p>
 */
public final class CutSpanMerge {

    private CutSpanMerge() { }

    /**
     * @param existing  the clip's spans now (source ms)
     * @param wordSpans the spans the struck words make now (source ms)
     * @param allWords  every word's range as padded for cutting, struck or not (source ms)
     */
    @NonNull
    public static List<long[]> rebuild(@NonNull List<long[]> existing,
                                       @NonNull List<long[]> wordSpans,
                                       @NonNull List<long[]> allWords) {
        List<long[]> out = new ArrayList<>();
        for (long[] s : existing) {
            long len = s[1] - s[0];
            if (len <= 0) continue;
            if (coveredMs(s[0], s[1], allWords) * 2 < len) out.add(new long[]{s[0], s[1]});
        }
        for (long[] w : wordSpans) if (w[1] > w[0]) out.add(new long[]{w[0], w[1]});
        Collections.sort(out, (a, b) -> Long.compare(a[0], b[0]));
        List<long[]> merged = new ArrayList<>();
        for (long[] s : out) {
            if (!merged.isEmpty() && s[0] <= merged.get(merged.size() - 1)[1]) {
                long[] last = merged.get(merged.size() - 1);
                last[1] = Math.max(last[1], s[1]);
            } else {
                merged.add(s);
            }
        }
        return merged;
    }

    /** How much of [a, b) the union of {@code ranges} covers. */
    static long coveredMs(long a, long b, @NonNull List<long[]> ranges) {
        List<long[]> clipped = new ArrayList<>();
        for (long[] r : ranges) {
            long x = Math.max(a, r[0]);
            long y = Math.min(b, r[1]);
            if (y > x) clipped.add(new long[]{x, y});
        }
        Collections.sort(clipped, (p, q) -> Long.compare(p[0], q[0]));
        long covered = 0L;
        long curA = Long.MIN_VALUE, curB = Long.MIN_VALUE;
        for (long[] r : clipped) {
            if (r[0] > curB) {
                if (curB > curA) covered += curB - curA;
                curA = r[0];
                curB = r[1];
            } else {
                curB = Math.max(curB, r[1]);
            }
        }
        if (curB > curA) covered += curB - curA;
        return covered;
    }
}
