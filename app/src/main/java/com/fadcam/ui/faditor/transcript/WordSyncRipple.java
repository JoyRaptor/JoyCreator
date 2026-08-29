package com.fadcam.ui.faditor.transcript;

import androidx.annotation.NonNull;

/**
 * What happens to the OTHER words when you drag one — the part of Word Sync that turns a
 * per-word tool into a bulk one (`SPEC_20260829_WORD_SYNC` §3.4).
 *
 * <p><b>Why this is the point of the feature.</b> Sloppy transcript timing does not drift
 * randomly; it drifts MONOTONICALLY. A recogniser that starts late is late for the rest of
 * the take, and one that runs slightly fast falls further behind with every word. Fixing
 * that one word at a time is the tedium JoyRaptor described: <i>"scooting can take a long while,
 * long press can take a bit with a lot of words… great for surgical edits but tedious with
 * sloppy transcripts."</i> One drag should fix fifty words.</p>
 *
 * <p>Pure Java, no {@code android.*}, so {@code tools/jvm-harness/run-wordsync.sh} can pin
 * the arithmetic against cases with known answers — the same reason {@code CaptionFit} takes
 * a measurer and {@code OnsetDetector} takes a sample callback. Timing maths that is only
 * ever checked by dragging on a phone is timing maths nobody can change safely.</p>
 */
public final class WordSyncRipple {

    /** How a drag propagates. */
    public enum Mode {
        /** Move only the dragged word. The existing surgical behaviour. */
        ONE,
        /** Move the dragged word and shift every later word by the SAME amount. */
        RIPPLE,
        /** Move the dragged word and redistribute every later word PROPORTIONALLY. */
        STRETCH
    }

    /**
     * Smallest gap left between two consecutive word starts. Not zero: two words sharing a
     * timestamp cannot be told apart by a tap, and a caption grouper that sees a zero-length
     * word has to invent a rule for it.
     */
    public static final long MIN_GAP_MS = 10;

    private WordSyncRipple() {}

    /**
     * Apply a drag and return the NEW start times. The input array is never modified — the
     * caller needs the old values to record one undo step (JoyRaptor's standing ruling: one
     * press, one step, however many words moved).
     *
     * @param starts    word start times, ascending
     * @param index     the word being dragged
     * @param desiredMs where the user wants that word to start (already onset-snapped)
     * @param mode      see {@link Mode}
     * @param anchorMs  the fixed point STRETCH scales against — the next manually pinned
     *                  word, or the end of the clip. Ignored by the other two modes.
     */
    @NonNull
    public static long[] apply(@NonNull long[] starts, int index, long desiredMs,
                               @NonNull Mode mode, long anchorMs) {
        long[] out = starts.clone();
        if (index < 0 || index >= starts.length) return out;

        // The dragged word may never cross its neighbours. Clamping here rather than in the
        // UI means every entry point gets the same rule, and the array cannot come back
        // out of order however the drag arrived.
        long lowerBound = (index > 0) ? starts[index - 1] + MIN_GAP_MS : Long.MIN_VALUE / 4;
        long upperBound;
        if (mode == Mode.ONE) {
            // Only ONE is bounded above by the next word: the other two carry it along.
            upperBound = (index + 1 < starts.length)
                    ? starts[index + 1] - MIN_GAP_MS : Long.MAX_VALUE / 4;
        } else {
            upperBound = Long.MAX_VALUE / 4;
        }
        long target = Math.max(lowerBound, Math.min(upperBound, desiredMs));
        long delta = target - starts[index];
        out[index] = target;
        if (delta == 0 || mode == Mode.ONE) return out;

        if (mode == Mode.RIPPLE) {
            // A constant offset - the fix for "the whole transcript starts late".
            for (int i = index + 1; i < starts.length; i++) out[i] = starts[i] + delta;
            return out;
        }

        // STRETCH - the fix for "it drifts further out as it goes". Everything between the
        // dragged word and the anchor is scaled so the anchor STAYS PUT; words near the drag
        // move a lot, words near the anchor barely move.
        long oldSpan = anchorMs - starts[index];
        long newSpan = anchorMs - target;
        if (oldSpan <= 0 || newSpan <= 0) {
            // The drag has crossed or reached the anchor, so there is no span left to scale
            // and the proportion is meaningless. Fall back to RIPPLE rather than dividing by
            // zero or silently collapsing every later word onto the anchor.
            for (int i = index + 1; i < starts.length; i++) out[i] = starts[i] + delta;
            return out;
        }
        double scale = newSpan / (double) oldSpan;
        for (int i = index + 1; i < starts.length; i++) {
            if (starts[i] >= anchorMs) {
                out[i] = starts[i];          // at or past the anchor: untouched by definition
            } else {
                long fromDrag = starts[i] - starts[index];
                out[i] = target + Math.round(fromDrag * scale);
            }
        }
        // Scaling can round two neighbours onto the same millisecond in a dense passage.
        // Enforce the gap afterwards rather than trying to be clever inside the loop.
        for (int i = Math.max(1, index + 1); i < out.length; i++) {
            if (out[i] < out[i - 1] + MIN_GAP_MS) out[i] = out[i - 1] + MIN_GAP_MS;
        }
        return out;
    }

    /**
     * The fixed point a STRETCH should scale against: the first PINNED word after
     * {@code index}, or {@code fallbackMs} (normally the clip end) when there is none.
     *
     * <p>Pinning is what makes stretch usable on a long take. Fix the timing at one landmark,
     * pin it, and later drags redistribute only up to that landmark instead of dragging the
     * whole rest of the transcript around every time.</p>
     */
    public static long anchorFor(@NonNull long[] starts, @NonNull boolean[] pinned,
                                 int index, long fallbackMs) {
        for (int i = index + 1; i < starts.length && i < pinned.length; i++) {
            if (pinned[i]) return starts[i];
        }
        return fallbackMs;
    }
}
