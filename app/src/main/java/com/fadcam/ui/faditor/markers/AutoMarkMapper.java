package com.fadcam.ui.faditor.markers;

import java.util.ArrayList;
import java.util.List;

/**
 * FOLD-1 auto-markers (spec: tasks/SPEC_AUTO_MARKERS_20261124.md): where, on the timeline, a moment that Detect found in a
 * recording lands, given how the clip is used. Pure Java (no Android classes) so it is tested off the device.
 *
 * <p>A moment is at {@code momentMs} in the SOURCE file. The clip plays source {@code inMs..outMs} from timeline time
 * {@code startMs}, at {@code speed} (2 = twice as fast), forwards or reversed. A moment outside the played part is not marked
 * (it is counted so the person can be told). A moment that began before the played part but is still going at its start
 * ({@code endMs} inside it) is marked at the clip's first moment.</p>
 */
public final class AutoMarkMapper {
    private AutoMarkMapper() {}

    /** One moment: when it began and ended in the source file, and what to call the mark. */
    public static final class Moment {
        public final long startMs, endMs;
        public final String label;
        /** How sure Detect was (0..1); 0 when unknown. Used to pick the best ones. */
        public final float confidence;
        /** The recording this moment is in, so a picture can be taken from it; may be null. */
        public final String sourceUri;

        public Moment(long startMs, long endMs, String label) {
            this(startMs, endMs, label, 0f, null);
        }

        public Moment(long startMs, long endMs, String label, float confidence, String sourceUri) {
            this.startMs = startMs;
            this.endMs = endMs;
            this.label = label;
            this.confidence = confidence;
            this.sourceUri = sourceUri;
        }
    }

    /** One mark to drop: where on the timeline, and its name. */
    public static final class Mark {
        public final long timelineMs;
        public final String label;
        /** Carried from the moment: how sure Detect was, and where in the recording to take a picture from. */
        public final float confidence;
        public final String sourceUri;
        public final long sourceMs;

        public Mark(long timelineMs, String label) {
            this(timelineMs, label, 0f, null, 0L);
        }

        public Mark(long timelineMs, String label, float confidence, String sourceUri, long sourceMs) {
            this.timelineMs = timelineMs;
            this.label = label;
            this.confidence = confidence;
            this.sourceUri = sourceUri;
            this.sourceMs = sourceMs;
        }
    }

    /** The marks, in timeline order, and how many moments fell in parts the clip does not play. */
    public static final class Result {
        public final List<Mark> marks = new ArrayList<>();
        public int outside;
    }

    public static Result map(List<Moment> moments, long inMs, long outMs, float speed, boolean reversed, long startMs) {
        Result r = new Result();
        float sp = speed > 0f ? speed : 1f;
        for (Moment m : moments) {
            long end = Math.max(m.startMs, m.endMs);
            if (end < inMs || m.startMs > outMs) {
                r.outside++;
                continue;
            }
            // where the played part first meets this moment: its start (its end, going backwards), or the clip's edge if it began earlier
            long played = reversed ? Math.min(end, outMs) : Math.max(m.startMs, inMs);
            long fromEdge = reversed ? outMs - played : played - inMs;
            r.marks.add(new Mark(startMs + Math.round(fromEdge / (double) sp), m.label, m.confidence, m.sourceUri, played));
        }
        r.marks.sort((a, b) -> Long.compare(a.timelineMs, b.timelineMs));
        return r;
    }

    /** The {@code n} most confident marks, kept in timeline order (a moment with unknown confidence counts as 0). */
    public static List<Mark> best(List<Mark> marks, int n) {
        if (marks.size() <= n) return new ArrayList<>(marks);
        List<Mark> byConfidence = new ArrayList<>(marks);
        byConfidence.sort((a, b) -> Float.compare(b.confidence, a.confidence));
        List<Mark> top = new ArrayList<>(byConfidence.subList(0, n));
        top.sort((a, b) -> Long.compare(a.timelineMs, b.timelineMs));
        return top;
    }

    /**
     * Drop marks that sit within {@code toleranceMs} of an existing mark with the same label (adding twice must not double
     * them). {@code existing} holds the project's marks as (timeline ms, label).
     */
    public static List<Mark> withoutDuplicates(List<Mark> wanted, List<Mark> existing, long toleranceMs) {
        List<Mark> out = new ArrayList<>();
        outer:
        for (Mark w : wanted) {
            for (Mark e : existing) {
                if (e.label.equals(w.label) && Math.abs(e.timelineMs - w.timelineMs) <= toleranceMs) continue outer;
            }
            for (Mark o : out) {
                if (o.label.equals(w.label) && Math.abs(o.timelineMs - w.timelineMs) <= toleranceMs) continue outer;
            }
            out.add(w);
        }
        return out;
    }
}
