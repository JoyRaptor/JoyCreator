package com.fadcam.ui.faditor.model;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * WHERE PLAYBACK AND EXPORT JUMP (job STU-B3, owner ruling 2026-11-15).
 *
 * <p>Struck transcript words and tapped silence gaps are kept on their clips as cut spans
 * ({@code removedSpans}, source ms), so a tap on the word brings it back. They stay on the
 * editor timeline at full length; playback and export JUMP over them: the whole program
 * (picture, every sound lane, titles, stickers, captions) skips from the span's start to its
 * end, together, so nothing drifts. This class turns every clip's spans into those jumps on
 * the editor's clock, the one list the preview's playhead, the timeline's black marks and the
 * export's {@link TimeMap} all read.</p>
 *
 * <p>Rules: a film clip's span counts only inside the clip's own main pass (not its loop
 * extensions); an audio clip's span counts inside that clip. No jump lies under a transition
 * (the outgoing tail or the incoming head the export blends), so the time map stays a
 * straight run there. Overlapping or touching jumps merge into one.</p>
 *
 * <p>Pure: no Android, no media. Rebuilt whenever it is asked for (cheap: one pass over the
 * clips).</p>
 */
public final class CutJumps {

    /** A jump shorter than this is not worth a seek; dropped (a 1 ms sliver from rounding). */
    public static final long MIN_JUMP_MS = 20L;

    private static final CutJumps NONE = new CutJumps(new long[0], new long[0]);

    /** Editor start of every jump, ascending; ends[i] > starts[i] and ends[i] < starts[i + 1]. */
    private final long[] starts;
    private final long[] ends;

    private CutJumps(@NonNull long[] starts, @NonNull long[] ends) {
        this.starts = starts;
        this.ends = ends;
    }

    @NonNull
    public static CutJumps none() { return NONE; }

    @NonNull
    public static CutJumps of(@NonNull Timeline timeline) {
        List<long[]> raw = new ArrayList<>();
        int n = timeline.getClipCount();
        long[] clipStart = new long[n + 1];
        long cursor = 0L;
        for (int i = 0; i < n; i++) {
            clipStart[i] = cursor;
            cursor += timeline.getClip(i).getVisualDurationMs();
        }
        clipStart[n] = cursor;

        for (int i = 0; i < n; i++) {
            Clip c = timeline.getClip(i);
            if (!c.hasRemovedSpans()) continue;
            float speed = Math.max(0.1f, c.getSpeedMultiplier());
            long loopBefore = c.hasLoopExtension() && !c.isImageClip() ? c.getLoopBeforeMs() : 0L;
            long mainStart = clipStart[i] + loopBefore;
            long mainEnd = mainStart + c.getTrimmedDurationMs();
            for (long[] s : c.getRemovedSpans()) {
                long a = Math.max(c.getInPointMs(), s[0]);
                long b = Math.min(c.getOutPointMs(), s[1]);
                if (b <= a) continue;
                long ea = mainStart + Math.round((a - c.getInPointMs()) / (double) speed);
                long eb = mainStart + Math.round((b - c.getInPointMs()) / (double) speed);
                raw.add(new long[]{Math.max(mainStart, ea), Math.min(mainEnd, eb)});
            }
        }
        for (AudioClip ac : timeline.getAudioClips()) {
            if (ac == null || !ac.hasRemovedSpans()) continue;
            long start = ac.getOffsetMs();
            long end = ac.getEndOnTimelineMs();
            for (long[] s : ac.getRemovedSpans()) {
                long a = Math.max(ac.getInPointMs(), s[0]);
                long b = Math.min(ac.getOutPointMs(), s[1]);
                if (b <= a) continue;
                long ea = start + (a - ac.getInPointMs());
                long eb = start + (b - ac.getInPointMs());
                raw.add(new long[]{Math.max(start, ea), Math.min(end, eb)});
            }
        }
        if (raw.isEmpty()) return NONE;

        // Keep every jump off the transitions: [P - T, P + T) around each seam's editor point P.
        List<long[]> blocked = new ArrayList<>();
        for (int s = 0; s + 1 < n; s++) {
            Transition t = TimeMap.transitionAtSeam(timeline, s);
            if (t == null) continue;
            long tMs = TimeMap.effectiveTransitionMs(timeline, t, s);
            if (tMs <= 0L) continue;
            long p = clipStart[s + 1];
            blocked.add(new long[]{p - tMs, p + tMs});
        }
        List<long[]> kept = new ArrayList<>();
        for (long[] r : raw) subtract(r[0], r[1], blocked, kept);
        return fromRanges(kept);
    }

    /** Merge, sort and drop slivers; the one way a CutJumps is made from ranges. */
    @NonNull
    static CutJumps fromRanges(@NonNull List<long[]> ranges) {
        List<long[]> list = new ArrayList<>();
        for (long[] r : ranges) if (r[1] > r[0]) list.add(new long[]{r[0], r[1]});
        if (list.isEmpty()) return NONE;
        Collections.sort(list, (x, y) -> Long.compare(x[0], y[0]));
        List<long[]> merged = new ArrayList<>();
        long[] cur = list.get(0);
        for (int i = 1; i < list.size(); i++) {
            long[] r = list.get(i);
            if (r[0] <= cur[1]) cur[1] = Math.max(cur[1], r[1]);
            else { merged.add(cur); cur = r; }
        }
        merged.add(cur);
        List<long[]> out = new ArrayList<>();
        for (long[] r : merged) if (r[1] - r[0] >= MIN_JUMP_MS) out.add(r);
        if (out.isEmpty()) return NONE;
        long[] a = new long[out.size()];
        long[] b = new long[out.size()];
        for (int i = 0; i < out.size(); i++) { a[i] = out.get(i)[0]; b[i] = out.get(i)[1]; }
        return new CutJumps(a, b);
    }

    private static void subtract(long a, long b, @NonNull List<long[]> blocked,
                                 @NonNull List<long[]> out) {
        List<long[]> pieces = new ArrayList<>();
        pieces.add(new long[]{a, b});
        for (long[] k : blocked) {
            List<long[]> next = new ArrayList<>();
            for (long[] p : pieces) {
                if (k[1] <= p[0] || k[0] >= p[1]) { next.add(p); continue; }
                if (k[0] > p[0]) next.add(new long[]{p[0], k[0]});
                if (k[1] < p[1]) next.add(new long[]{k[1], p[1]});
            }
            pieces = next;
        }
        out.addAll(pieces);
    }

    public boolean isEmpty() { return starts.length == 0; }

    public int count() { return starts.length; }

    public long startMs(int i) { return starts[i]; }

    public long endMs(int i) { return ends[i]; }

    /** Every jump's length added up: how much shorter the file is than the timeline. */
    public long totalMs() {
        long all = 0L;
        for (int i = 0; i < starts.length; i++) all += ends[i] - starts[i];
        return all;
    }

    /** How much of the timeline before {@code editorMs} is jumped over (inside a jump: the part so far). */
    public long cutBeforeMs(long editorMs) {
        long cut = 0L;
        for (int i = 0; i < starts.length && starts[i] < editorMs; i++) {
            cut += Math.min(editorMs, ends[i]) - starts[i];
        }
        return cut;
    }

    /** Where playback lands from {@code editorMs}: the jump's end inside a jump, else itself. */
    public long landingMs(long editorMs) {
        int i = indexAt(editorMs);
        return i < 0 ? editorMs : ends[i];
    }

    /** The jump holding {@code editorMs} (start inclusive, end exclusive), or -1. */
    public int indexAt(long editorMs) {
        for (int i = 0; i < starts.length; i++) {
            if (editorMs < starts[i]) return -1;
            if (editorMs < ends[i]) return i;
        }
        return -1;
    }

    /** The first jump starting at or after {@code editorMs}, or -1. */
    public int nextIndexFrom(long editorMs) {
        for (int i = 0; i < starts.length; i++) if (starts[i] >= editorMs) return i;
        return -1;
    }

    /**
     * The parts of editor range [{@code fromMs}, {@code toMs}) that play: the range less every
     * jump, in order. The whole range when nothing is cut inside it.
     */
    @NonNull
    public List<long[]> keptRanges(long fromMs, long toMs) {
        List<long[]> out = new ArrayList<>();
        long cur = fromMs;
        for (int i = 0; i < starts.length && cur < toMs; i++) {
            if (ends[i] <= cur) continue;
            if (starts[i] >= toMs) break;
            if (starts[i] > cur) out.add(new long[]{cur, starts[i]});
            cur = Math.max(cur, ends[i]);
        }
        if (cur < toMs) out.add(new long[]{cur, toMs});
        return out;
    }
}
