package com.fadcam.ui.faditor.puppet;

/**
 * TURNS KEY TIMES INTO WHAT THE TAPE DRAWS — bars for performances, diamonds for hand-made keys.
 *
 * <p>SPEC_20260915_PUPPET_UI §01 chose "Option C": a recorded move draws as one long diamond with
 * pointed caps, with its surviving keys inside it at half strength. The obvious way to build that
 * is to store takes as objects. This does not, and the reason is worth stating.
 *
 * <h3>A take is not a new kind of thing. It is a DENSITY.</h3>
 * <p>A live take drops a key every frame; a hand-made key is placed one at a time, seconds apart.
 * So the two are already distinguishable from the times alone — no second primitive, no take
 * table to keep in step with the track, nothing extra to serialise, migrate or lose. And it keeps
 * the promise the design made to JoyRaptor: <i>"the word take never appears"</i>. It cannot
 * appear, because there is no such object.
 *
 * <p>The consequence is honest and worth knowing: thin a take hard enough and it stops being a
 * bar and becomes the handful of diamonds it was reduced to. That is not a bug — at four keys
 * over three seconds it IS a handful of keys, and drawing a bar around them would claim a
 * performance that is no longer there.
 *
 * <p>Pure arithmetic, no Android, so {@code PuppetTapeTest} can pin the boundaries.
 */
public final class PuppetTapeMarks {

    private PuppetTapeMarks() {}

    /**
     * Keys closer together than this are the same performance, in ms.
     *
     * <p>Four frames at 30fps. A take even at its most thinned lands well inside it; a human
     * placing keys by hand — jump to next key, adjust, jump again — cannot land two within four
     * frames often enough to matter, and if they do, a bar is a fair description of what they
     * made anyway.
     */
    public static final long RUN_GAP_MS = 134L;

    /**
     * Keys in a row before a run counts as a performance rather than a cluster.
     *
     * <p>Three is the smallest number that has an inside: two keys are a start and an end with no
     * middle, and drawing a bar around them would say "performance" about a move from A to B.
     */
    public static final int MIN_RUN_KEYS = 3;

    /** One thing to draw: a bar with an inside, or a single key. */
    public static final class Mark {
        /** Start and end in the same units the times came in — item-local ms. */
        public final long fromMs, toMs;
        /** How many keys this mark covers. 1 for a single. */
        public final int count;
        /** True when this is a performance: draw it as a long diamond with its keys inside. */
        public final boolean bar;

        Mark(long fromMs, long toMs, int count, boolean bar) {
            this.fromMs = fromMs;
            this.toMs = toMs;
            this.count = count;
            this.bar = bar;
        }

        public long durationMs() { return Math.max(0L, toMs - fromMs); }

        @Override public String toString() {
            return (bar ? "bar[" : "key[") + fromMs + ".." + toMs + " x" + count + "]";
        }
    }

    /**
     * Group ascending key times into bars and singles.
     *
     * @param times   ascending, as {@code MeshPoseTrack.componentTimes} returns them
     * @param gapMs   the run gap; pass {@link #RUN_GAP_MS} unless testing
     * @return one entry per thing to draw, in time order. Empty in, empty out.
     */
    public static Mark[] group(long[] times, long gapMs) {
        if (times == null || times.length == 0) return new Mark[0];
        if (gapMs <= 0) gapMs = RUN_GAP_MS;

        // Worst case every key is its own mark, so one allocation of that size and a copy at the
        // end beats growing a list — this runs per row, per frame, while the timeline scrolls.
        Mark[] scratch = new Mark[times.length];
        int n = 0;

        int runStart = 0;
        for (int i = 1; i <= times.length; i++) {
            boolean breakHere = (i == times.length) || (times[i] - times[i - 1] > gapMs);
            if (!breakHere) continue;
            int count = i - runStart;
            if (count >= MIN_RUN_KEYS) {
                scratch[n++] = new Mark(times[runStart], times[i - 1], count, true);
            } else {
                // A run too short to be a performance is drawn as the keys it actually is.
                for (int k = runStart; k < i; k++) {
                    scratch[n++] = new Mark(times[k], times[k], 1, false);
                }
            }
            runStart = i;
        }

        Mark[] out = new Mark[n];
        System.arraycopy(scratch, 0, out, 0, n);
        return out;
    }

    /** Convenience with the shipping gap. */
    public static Mark[] group(long[] times) {
        return group(times, RUN_GAP_MS);
    }

    /**
     * The keys that fall INSIDE a bar, excluding its two ends.
     *
     * <p>The bar's pointed caps ARE its first and last key — drawing them again reads as two keys
     * a frame apart, which is exactly the jitter the whole design is trying to make visible
     * rather than create. JoyRaptor, 2026-09-15: <i>"the angle tips of the bar itself makes the
     * keyframe shape"</i>.
     */
    public static long[] insideOf(long[] times, Mark mark) {
        if (times == null || mark == null || !mark.bar) return new long[0];
        int lo = -1, hi = -1;
        for (int i = 0; i < times.length; i++) {
            if (times[i] == mark.fromMs && lo < 0) lo = i;
            if (times[i] == mark.toMs) hi = i;
        }
        if (lo < 0 || hi < 0 || hi - lo < 2) return new long[0];
        long[] out = new long[hi - lo - 1];
        System.arraycopy(times, lo + 1, out, 0, out.length);
        return out;
    }
}
