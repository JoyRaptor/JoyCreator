package com.fadcam.ui.faditor.motion.flow;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Submits bounded, lazy analysis work for one visible continuity segment in source time. */
public final class IndexedFlowQueue {
    private static final int MAX_QUEUED_PAIRS = 32;

    /** Identity and geometry come from the same media revision used by the decoder. */
    public record Source(String fingerprint, int orientation, int left, int top, int right,
                         int bottom, String estimatorRevision, String detail, int width,
                         int height, long maxGapUs) {
        public Source {
            Objects.requireNonNull(fingerprint);
            Objects.requireNonNull(estimatorRevision);
            Objects.requireNonNull(detail);
            if (width <= 0 || height <= 0 || maxGapUs <= 0 || right <= left || bottom <= top) {
                throw new IllegalArgumentException("invalid footage analysis geometry");
            }
        }

        private FlowCache.Key key(VideoPresentationIndex.Pair pair) {
            return new FlowCache.Key(fingerprint, orientation, left, top, right, bottom,
                    pair.fromPtsUs(), pair.toPtsUs(), estimatorRevision, detail, width, height, maxGapUs);
        }
    }

    private final FlowAnalyzer analyzer;
    private final VideoPresentationIndex index;
    private final Source source;
    private final DecodedFlowWork.Decoder decoder;
    private final long generation;

    public IndexedFlowQueue(FlowAnalyzer analyzer, VideoPresentationIndex index, Source source,
                            DecodedFlowWork.Decoder decoder, long generation) {
        this.analyzer = Objects.requireNonNull(analyzer);
        this.index = Objects.requireNonNull(index);
        this.source = Objects.requireNonNull(source);
        this.decoder = Objects.requireNonNull(decoder);
        this.generation = generation;
    }

    public void plan(long playheadUs, long visibleStartUs, long visibleEndUs,
                     long segmentStartUs, long segmentEndUs) {
        submit(visibleStartUs, visibleEndUs, visiblePairs(playheadUs, visibleStartUs, visibleEndUs,
                segmentStartUs, segmentEndUs), Set.of());
    }

    List<VideoPresentationIndex.Pair> visiblePairs(long playheadUs, long visibleStartUs,
            long visibleEndUs, long segmentStartUs, long segmentEndUs) {
        return index.visiblePairs(playheadUs, visibleStartUs, visibleEndUs,
                segmentStartUs, segmentEndUs, source.maxGapUs(), MAX_QUEUED_PAIRS);
    }

    void submit(long visibleStartUs, long visibleEndUs, List<VideoPresentationIndex.Pair> pairs,
                Set<VideoPresentationIndex.Pair> ready) {
        var work = new ArrayList<DecodedFlowWork>();
        for (var pair : pairs) {
            if (!ready.contains(pair)) work.add(new DecodedFlowWork(source.key(pair), decoder));
        }
        analyzer.plan(generation, visibleStartUs, visibleEndUs, work);
    }
}
