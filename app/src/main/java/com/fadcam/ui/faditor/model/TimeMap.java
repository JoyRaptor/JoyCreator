package com.fadcam.ui.faditor.model;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * THE ONE ANSWER TO "WHERE DOES THIS EDITOR MOMENT LAND IN THE EXPORTED FILE" (job STU-5).
 *
 * <p>The editor's timeline is a plain sum of clip spans: a transition is drawn over the seam and
 * takes no time. The export overlaps the two clips at a transition instead, so the file is
 * shorter than the timeline, and everything after a seam sits earlier in the file by the
 * transitions before it (LEDGER §2d, proved on the Note 9 2026-08-03: a picture-in-picture at
 * editor 5501 ms appears at 4.90 s after one 600 ms transition).</p>
 *
 * <p>Overlays were corrected for that long ago ({@code ExportManager.editorTimeOffsetFor});
 * music, voiceover, picture-in-picture sound and the black tail were not, so a voiceover after
 * two transitions came in late and the file ended on black. They all ask this class now, and
 * {@code editorTimeOffsetFor} is {@link #offsetBeforeClip}.</p>
 *
 * <p>The mapping, for every seam {@code s} with an effective transition {@code T} and editor
 * point {@code P} (where the next clip starts on the editor timeline):
 * {@code file(t) = t - sum over seams of clamp(t - P, 0, T)}. Before a seam nothing is taken
 * away; from {@code P + T} on, the whole transition is; the incoming clip's first {@code T}
 * ms (which the export plays under the blend, not after it) fold onto the end of the blend.
 * The result never goes backwards, so a sound placed in that fold starts where the next
 * clip's own picture starts.</p>
 *
 * <p>Pure: no Android, no media. Built once per export from the timeline as it is then.</p>
 */
public final class TimeMap {

    /** Editor start of every master clip; one extra entry at the end = where the track ends. */
    private final long[] clipStartMs;
    /** Effective transition at seam s (after clip s), 0 where there is none. */
    private final long[] seamMs;

    private TimeMap(@NonNull long[] clipStartMs, @NonNull long[] seamMs) {
        this.clipStartMs = clipStartMs;
        this.seamMs = seamMs;
    }

    @NonNull
    public static TimeMap of(@NonNull Timeline timeline) {
        int n = timeline.getClipCount();
        long[] starts = new long[n + 1];
        long cursor = 0L;
        for (int i = 0; i < n; i++) {
            starts[i] = cursor;
            cursor += timeline.getClip(i).getVisualDurationMs();
        }
        starts[n] = cursor;
        long[] seams = new long[Math.max(0, n - 1)];
        for (int s = 0; s < seams.length; s++) {
            Transition t = transitionAtSeam(timeline, s);
            seams[s] = t == null ? 0L : effectiveTransitionMs(timeline, t, s);
        }
        return new TimeMap(starts, seams);
    }

    public int clipCount() { return clipStartMs.length - 1; }

    /** Editor start of master clip {@code i} (the sum of the visual spans before it). */
    public long clipEditorStartMs(int i) {
        return clipStartMs[Math.max(0, Math.min(i, clipStartMs.length - 1))];
    }

    /** Effective length of the transition after clip {@code seam}; 0 if none. */
    public long seamMs(int seam) {
        return seam >= 0 && seam < seamMs.length ? seamMs[seam] : 0L;
    }

    /** How much of clip {@code i}'s head the transition before it plays under the blend. */
    public long headMs(int i) { return seamMs(i - 1); }

    /** Every transition's effective length added up: how much shorter the file is. */
    public long totalSwallowedMs() {
        long all = 0L;
        for (long t : seamMs) all += t;
        return all;
    }

    /**
     * Editor time minus file time for anything hosted by master clip {@code i}: the transitions
     * at every seam before it. A clip off the master track (the black tail) lies past every seam.
     */
    public long offsetBeforeClip(int i) {
        if (i < 0 || i > seamMs.length) return totalSwallowedMs();
        long off = 0L;
        for (int s = 0; s < i && s < seamMs.length; s++) off += seamMs[s];
        return off;
    }

    /** Where editor time {@code editorMs} lands in the exported file. */
    public long editorToFileMs(long editorMs) {
        long out = editorMs;
        for (int s = 0; s < seamMs.length; s++) {
            long t = seamMs[s];
            if (t <= 0L) continue;
            long into = editorMs - clipStartMs[s + 1];
            if (into <= 0L) continue;
            out -= Math.min(into, t);
        }
        return out;
    }

    /**
     * Clip-local time (timeline ms from the clip's own start, the base of its fades, opacity,
     * placement and volume keys) of source position {@code sourceMs}. The export starts a clip
     * after a transition part-way in, so an item's clock does not begin at clip-local 0.
     */
    public static long clipLocalMsAt(@NonNull Clip clip, long sourceMs) {
        float speed = Math.max(0.1f, clip.getSpeedMultiplier());
        return Math.max(0L, Math.round((sourceMs - clip.getInPointMs()) / (double) speed));
    }

    // ── The transition rules, moved here from ExportManager unchanged ──────────────────────

    /** The transition on the seam after clip {@code seam}: the FIRST one listed, as the export reads it. */
    @Nullable
    public static Transition transitionAtSeam(@NonNull Timeline timeline, int seam) {
        for (Transition t : timeline.getTransitions()) {
            if (t.clipIndex == seam) return t;
        }
        return null;
    }

    /**
     * Effective TIMELINE-ms length of the transition at {@code seam}, clamped so it can never
     * take more than either clip it straddles can give it.
     */
    public static long effectiveTransitionMs(@NonNull Timeline timeline,
                                             @NonNull Transition trans, int seam) {
        long d = Math.max(0L, trans.durationMs);
        if (seam >= 0 && seam < timeline.getClipCount()) {
            d = Math.min(d, transitionBudgetOnClip(timeline, trans, seam, /* clipIsOutgoing = */ true));
        }
        int next = seam + 1;
        if (next >= 0 && next < timeline.getClipCount()) {
            d = Math.min(d, transitionBudgetOnClip(timeline, trans, next, /* clipIsOutgoing = */ false));
        }
        return d;
    }

    /**
     * How much of the clip at {@code clipIndex} the transition may claim: the whole trimmed
     * clip, unless the clip has a transition on both seams that together overspend it; then
     * each gets a share in proportion to its authored length.
     */
    static long transitionBudgetOnClip(@NonNull Timeline timeline, @NonNull Transition trans,
                                       int clipIndex, boolean clipIsOutgoing) {
        long clipLen = Math.max(0L, timeline.getClip(clipIndex).getTrimmedDurationMs());
        Transition other = transitionAtSeam(timeline, clipIsOutgoing ? clipIndex - 1 : clipIndex);
        if (other == null) return clipLen;
        long mine = Math.max(0L, trans.durationMs);
        long theirs = Math.max(0L, other.durationMs);
        if (mine + theirs <= clipLen || mine + theirs == 0) return clipLen;
        return Math.max(0L, (clipLen * mine) / (mine + theirs));
    }
}
