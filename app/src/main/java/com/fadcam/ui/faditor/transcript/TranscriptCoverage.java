package com.fadcam.ui.faditor.transcript;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Which parts of a SOURCE have actually been transcribed, in source milliseconds.
 *
 * <p>A transcript belongs to the source; each clip is a window over it. Transcribing a clip
 * therefore does not produce a new transcript, it EXTENDS this coverage. Recording coverage
 * separately from the words is what makes that possible: a stretch with no words could be
 * silence that was transcribed, or a stretch nobody has run yet, and only coverage tells them
 * apart. Without it "transcribe the rest" would have to guess from word gaps and would
 * re-transcribe every pause.</p>
 *
 * <p>Ranges are half-open {@code [start, end)}, kept sorted, non-overlapping and coalesced, so
 * {@link #gaps} is a simple walk and {@link #covers} a binary-search-free scan over a list that
 * is realistically a handful of entries.</p>
 */
public class TranscriptCoverage {

    /** Sorted, disjoint, coalesced {@code [start, end)} ranges in SOURCE ms. */
    private final List<long[]> ranges = new ArrayList<>();

    public TranscriptCoverage() {}

    public TranscriptCoverage(@NonNull List<long[]> initial) {
        for (long[] r : initial) add(r[0], r[1]);
    }

    /** An immutable-ish view of the covered ranges, sorted and coalesced. */
    @NonNull
    public List<long[]> ranges() {
        return Collections.unmodifiableList(ranges);
    }

    public boolean isEmpty() {
        return ranges.isEmpty();
    }

    /** Total covered duration in ms. */
    public long coveredMs() {
        long t = 0;
        for (long[] r : ranges) t += r[1] - r[0];
        return t;
    }

    /**
     * Record {@code [startMs, endMs)} as transcribed, coalescing with anything it touches.
     * Ranges that merely ABUT are merged too — a clip boundary is not a hole.
     */
    public void add(long startMs, long endMs) {
        if (endMs <= startMs) return;
        ranges.add(new long[]{startMs, endMs});
        normalise();
    }

    /** True if every millisecond of {@code [startMs, endMs)} is already covered. */
    public boolean covers(long startMs, long endMs) {
        if (endMs <= startMs) return true;
        for (long[] r : ranges) {
            if (r[0] <= startMs && r[1] >= endMs) return true;
            if (r[0] > startMs) break;      // sorted: no later range can start early enough
        }
        return false;
    }

    /**
     * The parts of {@code [fromMs, toMs)} that are NOT yet covered — i.e. exactly what
     * "transcribe the rest" has to run, and nothing already done.
     */
    @NonNull
    public List<long[]> gaps(long fromMs, long toMs) {
        List<long[]> out = new ArrayList<>();
        long cursor = fromMs;
        for (long[] r : ranges) {
            if (r[1] <= cursor) continue;
            if (r[0] >= toMs) break;
            if (r[0] > cursor) out.add(new long[]{cursor, Math.min(r[0], toMs)});
            cursor = Math.max(cursor, r[1]);
            if (cursor >= toMs) break;
        }
        if (cursor < toMs) out.add(new long[]{cursor, toMs});
        return out;
    }

    /** Sort by start, then merge overlapping AND abutting ranges. */
    private void normalise() {
        if (ranges.size() < 2) return;
        Collections.sort(ranges, (a, b) -> Long.compare(a[0], b[0]));
        List<long[]> merged = new ArrayList<>(ranges.size());
        long[] cur = ranges.get(0).clone();
        for (int i = 1; i < ranges.size(); i++) {
            long[] r = ranges.get(i);
            if (r[0] <= cur[1]) {                 // overlap or abut
                cur[1] = Math.max(cur[1], r[1]);
            } else {
                merged.add(cur);
                cur = r.clone();
            }
        }
        merged.add(cur);
        ranges.clear();
        ranges.addAll(merged);
    }

    @NonNull
    public TranscriptCoverage copy() {
        TranscriptCoverage c = new TranscriptCoverage();
        for (long[] r : ranges) c.ranges.add(r.clone());
        return c;
    }

    @Override
    @NonNull
    public String toString() {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < ranges.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(ranges.get(i)[0]).append("..").append(ranges.get(i)[1]);
        }
        return sb.append(']').toString();
    }
}
