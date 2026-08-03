package com.fadcam.ui.faditor.model;

/**
 * The arithmetic behind rider attachment (PLAN_TIMELINE_MANIPULATION_V1 §2.0, addendum §4A).
 *
 * <p><b>Why this class is android-free and static.</b> Same reason as {@link TransitionIndex} and
 * {@code CompositingSpec.featherRadiusPx}: the logic most likely to be subtly wrong is the logic
 * that must be provable in seconds, and {@code Timeline}/{@code Clip} drag in {@code android.net.Uri}
 * and media3, whose harness classpath is ~70KB — long enough to exceed the Windows command line and
 * fail SILENTLY. Everything here takes primitives, so {@code AnchorMathTest} compiles against
 * nothing but the annotation stubs.</p>
 *
 * <p><b>One authority.</b> Three rider kinds share this math — visualizers (shipped, via
 * {@code Timeline.attachVisualizerToHostUnderStart}), captions (specced, unbuilt) and layer items
 * (M11). They differ only in {@link RiderPolicy}, never in how a host is resolved or how a shift is
 * applied. A second implementation of any function here is a bug.</p>
 */
public final class AnchorMath {

    private AnchorMath() {}

    /**
     * The open-end sentinel. An item whose end is EXACTLY this value runs to the end of the
     * timeline; see {@code TextOverlayItem.setTimeRange} and {@code Timeline.textEndForPacking},
     * both of which test for exact equality.
     */
    public static final long OPEN_END = Long.MAX_VALUE;

    /** Returned by {@link #hostIndexForStart} when no clip covers the given time. */
    public static final int NO_HOST = -1;

    /**
     * Prefix-sum the on-timeline spans into absolute clip start times.
     *
     * <p>The caller supplies spans because the per-clip term is a {@code Clip} decision
     * ({@code hasLoopExtension() ? getVisualDurationMs() : getTrimmedDurationMs()}) that this
     * android-free class must not duplicate — duplicating it is how the editor and the export came
     * to disagree about total length in the first place.</p>
     */
    public static long[] startsFromSpans(long[] spans) {
        long[] starts = new long[spans.length];
        long t = 0;
        for (int i = 0; i < spans.length; i++) {
            starts[i] = t;
            t += Math.max(0, spans[i]);
        }
        return starts;
    }

    /**
     * The host clip for a rider starting at {@code itemStartMs}, or {@link #NO_HOST}.
     *
     * <p><b>HALF-OPEN — {@code start >= s && start < s + span}.</b> A seam-exact start therefore
     * binds to the LATER clip. This is not arbitrary: it is the rule the shipped
     * {@code attachVisualizerToHostUnderStart} already uses, and it is the only comparison that
     * makes "the clip under time T" a total function with no clip claiming the same instant twice.
     * {@code EditorTimelineView.xToTime} currently disagrees (inclusive on both ends, first match
     * wins, so it binds a seam to the EARLIER segment) — that hit-test is the one to change.</p>
     *
     * <p>Zero-length and negative spans cannot host anything: a clip occupying no time is under no
     * playhead position, so it is skipped rather than silently swallowing the seam.</p>
     */
    public static int hostIndexForStart(long[] starts, long[] spans, long itemStartMs) {
        for (int i = 0; i < starts.length && i < spans.length; i++) {
            if (spans[i] <= 0) continue;
            if (itemStartMs >= starts[i] && itemStartMs < starts[i] + spans[i]) return i;
        }
        return NO_HOST;
    }

    /**
     * Shift a rider's START by its host's delta.
     *
     * <p>Floored at 0 as a safety net only. It should be unreachable: a rider's start is always
     * {@code >=} its host's start, and a host's new start is always {@code >= 0}, so
     * {@code start + delta >= newHostStart >= 0}. The harness asserts that, because a floor that
     * ever fires means the caller paired a rider with the wrong host's delta.</p>
     */
    public static long shiftStart(long startMs, long deltaMs) {
        return Math.max(0, startMs + deltaMs);
    }

    /**
     * Shift a rider's END by its host's delta — <b>preserving {@link #OPEN_END} EXACTLY</b>.
     *
     * <p><b>This method exists because the obvious one-liner corrupts data.</b>
     * {@code TextOverlayItem.setTimeRange} stores {@code (end <= start) ? OPEN_END : end}, so a
     * NEGATIVE delta applied naively gives {@code OPEN_END + delta}, which is still greater than
     * the start and is therefore silently persisted as a CLOSED end at an astronomical value. The
     * packing helper treats only exact {@code OPEN_END} as open, so that item then owns its lane
     * forever and the load-time enforcer exiles every sibling to its own row. That is the same
     * family as the {@code 2^61-1} stranding in LEDGER §1l — arithmetic performed on a sentinel.</p>
     *
     * <p>Never inline this. The sentinel must be tested for equality before any arithmetic.</p>
     */
    public static long shiftEnd(long endMs, long deltaMs) {
        if (endMs == OPEN_END) return OPEN_END;
        return Math.max(0, endMs + deltaMs);
    }

    /**
     * The end of a rider under {@link RiderPolicy#SHIFT_TRUNCATE} — clamped into the host's span,
     * which is what the shipped visualizer behaviour does ({@code resyncAttachedVisualizers}).
     *
     * <p>An open-ended rider truncates to exactly the host's end; a closed one to the earlier of
     * its own shifted end and the host's end. Truncation is deliberate for a rider BOUND to its
     * host's content (a visualizer reads that clip's audio; outliving it is meaningless) and is
     * deliberately NOT applied to layer items, whose duration belongs to the user.</p>
     */
    public static long truncatedEnd(long shiftedEndMs, long hostStartMs, long hostSpanMs) {
        long hostEnd = hostStartMs + Math.max(0, hostSpanMs);
        if (shiftedEndMs == OPEN_END) return hostEnd;
        return Math.min(shiftedEndMs, hostEnd);
    }

    /**
     * A rider's new {@code [start, end]} after its host moved by {@code hostDeltaMs} — the one
     * place a {@link RiderPolicy} is applied.
     *
     * <p>Returns a 2-element array rather than allocating a holder: this runs once per rider per
     * structural edit, and a ripple on a busy project touches every rider at once.</p>
     *
     * <p><b>Orphans are NOT handled here.</b> A rider whose host was deleted has no delta to apply,
     * and choosing between re-anchoring and deleting is a user-facing decision (addendum §4A's
     * prompt). Callers must resolve the host first; passing a fabricated delta would turn a
     * question into a silent answer.</p>
     */
    public static long[] shiftRider(long startMs, long endMs, long hostDeltaMs,
                                    RiderPolicy policy, long newHostStartMs, long hostSpanMs) {
        long s = shiftStart(startMs, hostDeltaMs);
        long e = shiftEnd(endMs, hostDeltaMs);
        // SHIFT_RESOURCE re-reads its host's content but moves identically to SHIFT_ONLY; the
        // re-sourcing half belongs to the caption-attach slice, which has no code yet. Treating it
        // as SHIFT_ONLY here is the documented interim, not an oversight.
        if (policy == RiderPolicy.SHIFT_TRUNCATE) {
            e = truncatedEnd(e, newHostStartMs, hostSpanMs);
            // Truncation can only shorten. If it would invert the window (host shorter than the
            // rider's own start offset), collapse to a zero-length window at the start rather than
            // emitting end < start, which setTimeRange would silently reinterpret as OPEN_END.
            if (e < s) e = s;
        }
        return new long[]{s, e};
    }

    /**
     * Offset of a rider within its host, clamped to stay inside the host's span.
     *
     * <p>Clamped to {@code span - 1} rather than {@code span} so the stored offset always resolves
     * back to the SAME host under {@link #hostIndexForStart}'s half-open rule — an offset of
     * exactly {@code span} would re-resolve to the next clip and the attachment would walk forward
     * one clip on every save/load cycle.</p>
     */
    public static long offsetWithinHost(long itemStartMs, long hostStartMs, long hostSpanMs) {
        long span = Math.max(1, hostSpanMs);
        return Math.max(0, Math.min(itemStartMs - hostStartMs, span - 1));
    }
}
