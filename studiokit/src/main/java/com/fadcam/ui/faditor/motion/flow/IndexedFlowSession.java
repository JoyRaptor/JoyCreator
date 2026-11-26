package com.fadcam.ui.faditor.motion.flow;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Session ownership for background footage analysis. Changes to the active source cancel queued
 * work. A renderer can read the exact resident pair without waiting for decode, disk I/O or a lock.
 */
public final class IndexedFlowSession implements AutoCloseable {
    private static final long DEFAULT_RESIDENT_BYTES = 8L * 1024 * 1024;
    private static final int MAX_RESIDENT_PAIRS = 8;
    // Each direction can retain two vector components plus confidence, all floats.
    private static final int BYTES_PER_PAIR_PIXEL = 2 * 3 * Float.BYTES;

    private final FlowAnalyzer analyzer;
    private final long residentBytes;
    private volatile SourceWindow active;
    private long generation;
    private boolean closed;

    public IndexedFlowSession(FlowCache cache) {
        this(cache, DEFAULT_RESIDENT_BYTES);
    }

    IndexedFlowSession(FlowCache cache, long residentBytes) {
        this(cache, residentBytes, () -> {});
    }

    IndexedFlowSession(FlowCache cache, Runnable onReady) {
        this(cache, DEFAULT_RESIDENT_BYTES, onReady);
    }

    private IndexedFlowSession(FlowCache cache, long residentBytes, Runnable onReady) {
        if (residentBytes <= 0) throw new IllegalArgumentException("invalid flow memory budget");
        this.residentBytes = residentBytes;
        analyzer = new FlowAnalyzer(cache, new FlowAnalyzer.Listener() {
            @Override public void ready(long readyGeneration, FlowCache.Key key, FlowCache.Pair pair,
                                        boolean fromCache) {
                SourceWindow window = active;
                if (window != null && window.generation == readyGeneration
                        && window.publish(new VideoPresentationIndex.Pair(key.fromPtsUs, key.toPtsUs), pair)) {
                    onReady.run();
                }
            }

            @Override public void failed(long failedGeneration, FlowCache.Key key, Exception problem) {
                // A failed or pending pair is absent. The renderer can draw its ordinary fallback.
            }
        });
    }

    /** Attach a fully indexed source. A replacement always gets a new token, even at the same PTS. */
    public synchronized long setSource(VideoPresentationIndex index, IndexedFlowQueue.Source source,
                                       DecodedFlowWork.Decoder decoder) {
        if (closed) throw new IllegalStateException("flow session is closed");
        Objects.requireNonNull(source);
        long pixels = (long) source.width() * source.height();
        int residentLimit = (int) Math.min(MAX_RESIDENT_PAIRS,
                residentBytes / BYTES_PER_PAIR_PIXEL / pixels);
        long nextGeneration = Math.incrementExact(generation);
        var queue = new IndexedFlowQueue(analyzer, index, source, decoder, nextGeneration);
        generation = nextGeneration;
        active = new SourceWindow(generation, queue, residentLimit);
        analyzer.plan(generation, 0, 0, List.of());
        return generation;
    }

    /** Drop the active source and outstanding work. Late stop calls for old sources are ignored. */
    public synchronized void clearSource(long sourceGeneration) {
        if (active == null || active.generation != sourceGeneration) return;
        active = null;
        generation = Math.incrementExact(generation);
        analyzer.plan(generation, 0, 0, List.of());
    }

    /**
     * Reprioritize visible pairs in source time. The interval ends are exclusive. Replanning
     * inside one source retains common exact pairs while immediately excluding old cut ranges.
     */
    public synchronized void plan(long sourceGeneration, long playheadUs, long visibleStartUs,
                                  long visibleEndUs, long segmentStartUs, long segmentEndUs) {
        SourceWindow window = active;
        if (window == null || window.generation != sourceGeneration) return;
        if (visibleEndUs < visibleStartUs) throw new IllegalArgumentException("reversed visible range");
        List<VideoPresentationIndex.Pair> pairs = window.residentLimit == 0 ? List.of()
                : window.queue.visiblePairs(playheadUs, visibleStartUs, visibleEndUs,
                        segmentStartUs, segmentEndUs);
        window.restrictTo(pairs.subList(0, Math.min(pairs.size(), window.residentLimit)));
        // The window lock has been released before taking the analyzer lock. The analyzer
        // worker may publish while holding its own lock, then take the window lock.
        window.queue.submit(visibleStartUs, visibleEndUs, pairs, window.ready.keySet());
    }

    /** Return the current exact presentation pair, or null so the renderer can fall back. */
    public FlowCache.Pair ready(long sourceGeneration, long fromPtsUs, long toPtsUs) {
        SourceWindow window = active;
        if (window == null || window.generation != sourceGeneration) return null;
        var timestamps = new VideoPresentationIndex.Pair(fromPtsUs, toPtsUs);
        FlowCache.Pair pair = window.ready.get(timestamps);
        return pair != null && window.ready.get(timestamps) == pair && active == window ? pair : null;
    }

    public synchronized void setPaused(boolean paused) {
        if (!closed) analyzer.setPaused(paused);
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        active = null;
        analyzer.close();
    }

    private static final class SourceWindow {
        final long generation;
        final IndexedFlowQueue queue;
        final int residentLimit;
        private Set<VideoPresentationIndex.Pair> wanted = Set.of();
        volatile Map<VideoPresentationIndex.Pair, FlowCache.Pair> ready = Map.of();

        SourceWindow(long generation, IndexedFlowQueue queue, int residentLimit) {
            this.generation = generation;
            this.queue = queue;
            this.residentLimit = residentLimit;
        }

        synchronized void restrictTo(List<VideoPresentationIndex.Pair> nearest) {
            wanted = new HashSet<>(nearest);
            var kept = new HashMap<>(ready);
            kept.keySet().retainAll(wanted);
            ready = Map.copyOf(kept);
        }

        synchronized boolean publish(VideoPresentationIndex.Pair timestamps, FlowCache.Pair pair) {
            if (!wanted.contains(timestamps)) return false;
            var next = new HashMap<>(ready);
            next.put(timestamps, pair);
            ready = Map.copyOf(next);
            return true;
        }
    }
}
