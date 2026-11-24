package com.fadcam.ui.faditor.tools;

/**
 * THE ONE PLACE THAT DECIDES A SNAP (STU-5, "one snapping authority", owner: snapping is "fiddly").
 *
 * <p>Every surface that pulls a dragged value onto something nearby asks here: the timeline (beats,
 * clip and fade edges, keyframes), the canvas (an object's centre and edges against the frame and
 * against other objects), and the handles (rotation steps and cardinals). Each surface still decides
 * WHICH points are candidates and HOW FAR it reaches (that is its feel, and its switch in
 * {@link SnapSettings}); this class decides, the same way for everyone, which candidate wins.</p>
 *
 * <p><b>Nothing moved when this was made.</b> Each method is the arithmetic a surface already ran,
 * moved here unchanged, including its tie rule and whether the edge of the reach counts; the tests
 * ({@code tools/jvm-harness/SnapAuthorityTest.java}) compare every method against a frozen copy of
 * the code it replaced on many random inputs. The single deliberate difference is noted on
 * {@link #nearestIndex(long, long[], int, long, boolean)}.</p>
 *
 * <p>Pure Java, no Android: a snap can be pinned by a test on any PC.</p>
 */
public final class SnapAuthority {

    private SnapAuthority() {}

    // ── Nearest of a list ───────────────────────────────────────────────────

    /**
     * The index of the candidate nearest {@code v} within {@code reach}, or -1. With
     * {@code inclusive} a candidate exactly {@code reach} away counts; without it, only nearer ones
     * do. A tie goes to the candidate listed FIRST.
     *
     * <p>Callers: overlay and sprite drop times (strict, 250 ms), opacity keyframes (inclusive),
     * timeline edges. The text overlay's drop-time snap used to keep the LAST edge within reach
     * instead of the nearest (its helper updated a copy of the best distance); routing it here makes
     * it pick the nearest edge like the sprite layer's identical snap always did.</p>
     */
    public static int nearestIndex(long v, long[] candidates, int count, long reach, boolean inclusive) {
        int best = -1;
        long bestD = 0L;
        int n = Math.min(count, candidates.length);
        for (int i = 0; i < n; i++) {
            long d = Math.abs(candidates[i] - v);
            if (d > reach || (!inclusive && d == reach)) continue;
            if (best < 0 || d < bestD) { best = i; bestD = d; }
        }
        return best;
    }

    /** {@link #nearestIndex(long, long[], int, long, boolean)} for positions (canvas fractions). */
    public static int nearestIndex(float v, float[] candidates, int count, float reach, boolean inclusive) {
        int best = -1;
        float bestD = 0f;
        int n = Math.min(count, candidates.length);
        for (int i = 0; i < n; i++) {
            float d = Math.abs(candidates[i] - v);
            if (d > reach || (!inclusive && d == reach)) continue;
            if (best < 0 || d < bestD) { best = i; bestD = d; }
        }
        return best;
    }

    /** {@code v} snapped to the nearest candidate within reach (see {@link #nearestIndex}), else {@code v}. */
    public static long snap(long v, long[] candidates, int count, long reach, boolean inclusive) {
        int i = nearestIndex(v, candidates, count, reach, inclusive);
        return i < 0 ? v : candidates[i];
    }

    /** {@code v} snapped to the nearest candidate within reach, else {@code v}. */
    public static float snap(float v, float[] candidates, int count, float reach, boolean inclusive) {
        int i = nearestIndex(v, candidates, count, reach, inclusive);
        return i < 0 ? v : candidates[i];
    }

    // ── Nearest of a sorted list (beats, word onsets) ───────────────────────

    /**
     * The index of the onset nearest {@code t} within {@code reach} (inclusive) in an ascending
     * array, or -1. On an exact tie between two neighbours the LATER one wins. Word sync's snap
     * ({@code OnsetDetector.snap}) and its magnet indicator both read this, so the indicator lights
     * exactly when the snap will happen.
     */
    public static int sortedIndex(long[] ascending, long t, long reach) {
        if (ascending.length == 0 || reach <= 0) return -1;
        int lo = 0, hi = ascending.length - 1;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (ascending[mid] < t) lo = mid + 1; else hi = mid;
        }
        int best = lo;
        long bestDist = Math.abs(ascending[lo] - t);
        if (lo > 0) {
            long d = Math.abs(ascending[lo - 1] - t);
            if (d < bestDist) { best = lo - 1; bestDist = d; }
        }
        return bestDist <= reach ? best : -1;
    }

    /** {@code t} on the nearest onset within reach (see {@link #sortedIndex}), else {@code t}. */
    public static long sorted(long[] ascending, long t, long reach) {
        int i = sortedIndex(ascending, t, reach);
        return i < 0 ? t : ascending[i];
    }

    /**
     * The beat snap's own search ({@code BeatDetector.snap}), kept exactly: a bisection that keeps
     * the nearest beat it passes. It agrees with {@link #sorted} except, on an exact tie between two
     * beats, on which of the two it lands; kept as it was so no beat-snapped edit moves.
     */
    public static long sortedBisect(long[] ascending, long t, long reach) {
        if (ascending.length == 0 || reach <= 0) return t;
        int lo = 0, hi = ascending.length - 1, best = 0;
        long bestDist = Long.MAX_VALUE;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            long d = Math.abs(ascending[mid] - t);
            if (d < bestDist) { bestDist = d; best = mid; }
            if (ascending[mid] < t) lo = mid + 1; else hi = mid - 1;
        }
        return bestDist <= reach ? ascending[best] : t;
    }

    // ── A box against a frame or another box (the canvas) ───────────────────

    /**
     * {shift, guide} for the closest of centre-to-centre, near edge-to-near edge and far edge-to-far
     * edge within {@code reach}, or null. Inclusive; on a tie the LATER pair wins. One axis at a
     * time: {@code lo..hi} is the dragged box, {@code frameLo..frameHi} the frame (or another box).
     */
    public static float[] axis(float lo, float hi, float frameLo, float frameHi, float reach) {
        float[][] pairs = {
                {(lo + hi) / 2f, (frameLo + frameHi) / 2f},
                {lo, frameLo},
                {hi, frameHi},
        };
        float[] best = null;
        float bestD = reach;
        for (float[] p : pairs) {
            float d = Math.abs(p[1] - p[0]);
            if (d <= bestD) { bestD = d; best = new float[]{p[1] - p[0], p[1]}; }
        }
        return best;
    }

    /** Like {@link #axis}, plus edge-to-opposite-edge, so two objects can sit flush side by side. */
    public static float[] axisFlush(float lo, float hi, float oLo, float oHi, float reach) {
        float[] best = axis(lo, hi, oLo, oHi, reach);
        float bestD = best == null ? reach : Math.abs(best[0]);
        float[][] flush = {{lo, oHi}, {hi, oLo}};
        for (float[] p : flush) {
            float d = Math.abs(p[1] - p[0]);
            if (d <= bestD) { bestD = d; best = new float[]{p[1] - p[0], p[1]}; }
        }
        return best;
    }

    // ── Angles (the handles) ────────────────────────────────────────────────

    /** {@code deg} on the nearest multiple of {@code stepDeg} when within {@code reachDeg} (inclusive). */
    public static float angleStep(float deg, int stepDeg, float reachDeg) {
        if (stepDeg <= 0 || reachDeg <= 0f) return deg;
        float nearest = Math.round(deg / stepDeg) * (float) stepDeg;
        return Math.abs(deg - nearest) <= reachDeg ? nearest : deg;
    }

    /**
     * {@code deg} pulled onto the nearest whole quarter turn (0, 90, 180, 270, or the full turn)
     * when nearer than {@code reachDeg}; the correction is added to {@code deg}, so a small twist
     * either side of level lands on level and never on minus a full turn.
     */
    public static float cardinal(float deg, float reachDeg) {
        float norm = ((deg % 360f) + 360f) % 360f;
        for (float c : new float[]{0f, 90f, 180f, 270f, 360f}) {
            if (Math.abs(norm - c) < reachDeg) return deg + (c - norm);
        }
        return deg;
    }
}
