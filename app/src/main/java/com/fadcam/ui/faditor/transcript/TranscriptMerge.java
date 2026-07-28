package com.fadcam.ui.faditor.transcript;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Folding a freshly transcribed RANGE into a source-scoped transcript.
 *
 * <p>Under source scoping a transcription run no longer produces a rival transcript for one
 * clip — it contributes words for a span of the source into the single transcript for that
 * {@code (source, label)} pair. This is the operation that does it, and the one place data can
 * be silently destroyed, so its rules are explicit:</p>
 *
 * <ol>
 *   <li><b>Only the re-run span is replaced.</b> Words outside {@code [fromMs, toMs)} are kept
 *       byte-for-byte, INCLUDING their edit state — {@code struck} words the user deleted and
 *       {@code forceLineBreakAfter} marks they placed. Re-running one clip must never quietly
 *       undo editing done on another part of the same source.</li>
 *   <li><b>Inside the span the new words win outright.</b> They are the point of the re-run —
 *       typically better timings. Any edit state inside that span IS lost, because the words it
 *       attached to no longer exist; {@link #countEditsInRange} exists so a caller can warn
 *       before that happens rather than discover it afterwards.</li>
 *   <li><b>Order is by start time.</b> Callers and renderers assume ascending word order.</li>
 * </ol>
 *
 * <p>Because word times are SOURCE-absolute, contributions from different clips of the same
 * source compose without any offsetting — which is precisely why this refactor is possible at
 * all, and why merging disjoint clip runs reconstructs one continuous transcript.</p>
 */
public final class TranscriptMerge {

    private TranscriptMerge() {}

    /**
     * Merge {@code incoming} (covering {@code [fromMs, toMs)} of the source) into
     * {@code existing}, returning a NEW transcript. Neither input is mutated.
     */
    @NonNull
    public static Transcript mergeRange(@NonNull Transcript existing,
                                        @NonNull Transcript incoming,
                                        long fromMs, long toMs) {
        Transcript out = new Transcript();
        for (TranscriptWord w : existing.words) {
            if (w.startMs >= fromMs && w.startMs < toMs) continue;   // superseded by the re-run
            out.words.add(copyWord(w));
        }
        for (TranscriptWord w : incoming.words) {
            // Guard the boundary: a word the engine emitted outside the span it was asked for
            // would otherwise duplicate a neighbouring range's word on the next merge.
            if (w.startMs < fromMs || w.startMs >= toMs) continue;
            out.words.add(copyWord(w));
        }
        Collections.sort(out.words, (a, b) -> Long.compare(a.startMs, b.startMs));
        return out;
    }

    /**
     * How many words in {@code [fromMs, toMs)} carry user edit state that a re-run would
     * discard. Zero means the merge is lossless and needs no confirmation.
     */
    public static int countEditsInRange(@NonNull Transcript existing, long fromMs, long toMs) {
        int n = 0;
        for (TranscriptWord w : existing.words) {
            if (w.startMs < fromMs || w.startMs >= toMs) continue;
            if (w.struck || w.forceLineBreakAfter) n++;
        }
        return n;
    }

    /**
     * The words of {@code source} that fall inside a clip's {@code [inMs, outMs)} window.
     * Mirrors what the timeline/caption renderers already do, so a clip keeps behaving as a
     * window over the source transcript rather than an owner of a private copy.
     */
    @NonNull
    public static Transcript window(@NonNull Transcript source, long inMs, long outMs) {
        Transcript out = new Transcript();
        for (TranscriptWord w : source.words) {
            if (w.startMs < inMs || w.startMs >= outMs) continue;
            out.words.add(copyWord(w));
        }
        return out;
    }

    /** Ranges of {@code [fromMs,toMs)} still to run, given what is already covered. */
    @NonNull
    public static List<long[]> outstanding(@NonNull TranscriptCoverage coverage,
                                           long fromMs, long toMs) {
        return coverage.gaps(fromMs, toMs);
    }

    @NonNull
    private static TranscriptWord copyWord(@NonNull TranscriptWord w) {
        TranscriptWord c = new TranscriptWord(w.text, w.startMs, w.endMs);
        c.struck = w.struck;
        c.forceLineBreakAfter = w.forceLineBreakAfter;
        return c;
    }

    /** Convenience for tests/callers that want a plain list of the merged word texts. */
    @NonNull
    public static List<String> texts(@NonNull Transcript t) {
        List<String> out = new ArrayList<>(t.words.size());
        for (TranscriptWord w : t.words) out.add(w.text);
        return out;
    }
}
