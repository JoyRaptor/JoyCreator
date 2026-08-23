package com.fadcam.ui.faditor.waveform;

import androidx.annotation.NonNull;

/**
 * Align two recordings of the same moment by cross-correlating their amplitude envelopes —
 * SPEC_AUDIO_UX_V1 row {@code D7}, "multi-speaker align (clap sync)".
 *
 * <p><b>The problem this solves.</b> Two people record themselves separately and both capture
 * the same clap, or the same laugh, or just the same room. The takes start at different moments
 * and drift apart by seconds. Lining them up by dragging while listening is the most tedious
 * job in multi-person editing, and exactly the kind a computer is better at than an ear.</p>
 *
 * <p><b>Why envelopes rather than raw samples.</b> Correlating raw PCM is the textbook answer
 * and the wrong one here: it needs both decoded takes in memory at once, it is enormously more
 * work, and it is sensitive to the two microphones having different frequency responses — which
 * two different phones always do. Envelopes are already extracted for drawing, are thousands of
 * times smaller, and encode the thing that actually matches: WHEN energy arrived. A clap is a
 * clap in both envelopes even when it is a different colour of clap.</p>
 *
 * <p><b>Refusing is a first-class outcome.</b> This WILL be asked to align two takes that share
 * nothing, and the honest answer then is "I could not find it". An offset applied on a guess
 * silently ruins an edit and the user has no way to know it was a guess — so see
 * {@link #MIN_CONFIDENCE} and {@link Result#isUsable()}.</p>
 */
public final class TrackAligner {

    private TrackAligner() {}

    /**
     * Below this, refuse to report an alignment.
     *
     * <p><b>Set from measurement, not taste.</b> On the harness fixtures a genuine match scores
     * <b>0.92–0.94</b> and two unrelated noise takes score <b>0.17</b>. 0.5 sits in the middle of
     * that gap with wide margin either side, so it is not a knob anyone needs to tune — it is a
     * line drawn through empty space. The first value tried here was 0.15, which let unrelated
     * takes through; see {@code tools/jvm-harness/run-align.sh}.</p>
     */
    public static final float MIN_CONFIDENCE = 0.5f;

    /** What {@link #align} concluded. */
    public static final class Result {
        /**
         * How far track B is offset from track A, in ms.
         * Positive = B's content happens LATER than A's.
         */
        public final long offsetMs;
        /** 0..1 — how far the winning alignment stood above the field. */
        public final float confidence;

        Result(long offsetMs, float confidence) {
            this.offsetMs = offsetMs;
            this.confidence = confidence;
        }

        /** True when the offset is worth applying. See {@link #MIN_CONFIDENCE}. */
        public boolean isUsable() { return confidence >= MIN_CONFIDENCE; }
    }

    /**
     * Find the offset that best aligns {@code b} to {@code a}.
     *
     * @param a               reference envelope.
     * @param b               envelope to align against it.
     * @param framesPerSecond frame rate of BOTH envelopes; they must share one.
     * @param maxOffsetMs     how far apart the takes might plausibly be. Bounding this is not an
     *                        optimisation — an unbounded search finds spurious matches in long
     *                        recordings, because given enough lag anything correlates with
     *                        anything.
     */
    @NonNull
    public static Result align(@NonNull int[] a, @NonNull int[] b,
                               double framesPerSecond, long maxOffsetMs) {
        if (a.length < 4 || b.length < 4 || framesPerSecond <= 0 || maxOffsetMs <= 0) {
            return new Result(0, 0f);
        }
        final double msPerFrame = 1000.0 / framesPerSecond;
        final int maxLag = (int) Math.min(
                Math.round(maxOffsetMs / msPerFrame), Math.max(a.length, b.length) - 1);
        if (maxLag < 1) return new Result(0, 0f);

        // Mean-subtract so a track that is simply LOUDER does not dominate, and a constant
        // background hiss cannot masquerade as a match.
        float[] fa = centred(a);
        float[] fb = centred(b);

        // Every lag must compare a SUBSTANTIAL span, not a sliver. This is the difference
        // between working and appearing to work: with a small floor, an extreme lag where a
        // handful of frames happen to agree scores a perfect 1.0 and beats the real match across
        // hundreds of frames. Measured — an 8-frame floor reported -4930ms at confidence 1.0 for
        // takes whose true offset was 800ms.
        final int minOverlap = Math.max(16, Math.min(fa.length, fb.length) / 2);

        double best = -Double.MAX_VALUE, sum = 0;
        int bestLag = 0, considered = 0;
        for (int lag = -maxLag; lag <= maxLag; lag++) {
            double score = correlate(fa, fb, lag, minOverlap);
            if (score == NO_SCORE) continue;
            sum += score;
            considered++;
            if (score > best) { best = score; bestLag = lag; }
        }
        if (considered == 0 || best <= 0) return new Result(0, 0f);

        // Confidence is the margin over the FIELD, not over the winner. Dividing by the winner
        // flatters any winner to 1.0 — unrelated noise still produces some best-of-the-bunch lag.
        // Correlation is bounded at 1, so this margin is directly meaningful.
        double mean = sum / considered;
        float confidence = (float) Math.max(0, Math.min(1, best - mean));
        return new Result(Math.round(bestLag * msPerFrame), confidence);
    }

    /** Copy, converted to float with the mean removed. */
    private static float[] centred(@NonNull int[] src) {
        double sum = 0;
        for (int v : src) sum += v;
        float mean = (float) (sum / src.length);
        float[] out = new float[src.length];
        for (int i = 0; i < src.length; i++) out[i] = src[i] - mean;
        return out;
    }

    /** Sentinel for "this lag does not overlap enough to be evidence" — skipped, not scored. */
    private static final double NO_SCORE = Double.NEGATIVE_INFINITY;

    /**
     * Normalised correlation of {@code b} shifted by {@code lag} against {@code a}.
     *
     * <p>Normalising by each side's own energy is what lets a quiet take match a loud one:
     * without it the louder recording dominates every score and the answer follows volume
     * rather than timing.</p>
     */
    private static double correlate(@NonNull float[] a, @NonNull float[] b, int lag,
                                    int minOverlap) {
        int start = Math.max(0, -lag);
        int end = Math.min(a.length, b.length - lag);
        int n = end - start;
        // Skipped rather than scored low: a sliver of overlap is not weak evidence, it is NO
        // evidence, and letting it into the mean would drag the confidence baseline around.
        if (n < minOverlap) return NO_SCORE;
        double dot = 0, na = 0, nb = 0;
        for (int i = start; i < end; i++) {
            float x = a[i], y = b[i + lag];
            dot += x * y;
            na += x * x;
            nb += y * y;
        }
        if (na <= 0 || nb <= 0) return NO_SCORE;
        return dot / Math.sqrt(na * nb);
    }
}
