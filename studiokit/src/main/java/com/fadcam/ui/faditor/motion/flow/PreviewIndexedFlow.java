package com.fadcam.ui.faditor.motion.flow;

import com.fadcam.ui.faditor.motion.PreviewMotionClock;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Owns one gated preview source. Renderer reads never wait for indexing or decoding. */
public final class PreviewIndexedFlow implements AutoCloseable {
    public interface Loader {
        Prepared open(FlowAnalyzer.Cancellation cancellation) throws Exception;
    }

    public interface CacheFactory {
        FlowCache open() throws java.io.IOException;
    }

    public record Prepared(VideoPresentationIndex index, IndexedFlowQueue.Source geometry,
                           DecodedFlowWork.Decoder decoder, AutoCloseable resource)
            implements AutoCloseable {
        @Override public void close() throws Exception { resource.close(); }
    }

    public record Source(String key, long startUs, long endUs, Loader loader) {}

    private record Request(Source source, PreviewMotionClock.Frame frame) {}
    private record Active(Source source, long generation, Prepared media) {}

    private final CacheFactory cacheFactory;
    private final Runnable onReady;
    private volatile IndexedFlowSession session;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(work -> {
        var thread = new Thread(work, "Studio footage index");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        return thread;
    });
    private Request request;
    private volatile Source source;
    private volatile Active active;
    private boolean scheduled;
    private boolean closed;
    // Accessed only by the worker.
    private Source attempted;
    private long plannedPts = Long.MIN_VALUE;

    public PreviewIndexedFlow(CacheFactory cacheFactory) {
        this(cacheFactory, () -> {});
    }

    public PreviewIndexedFlow(CacheFactory cacheFactory, Runnable onReady) {
        this.cacheFactory = cacheFactory;
        this.onReady = onReady;
    }

    /** Publish synchronously so a renderer holding the old timestamp loses access immediately. */
    public synchronized void setSource(Source next) {
        if (closed || source == next) return;
        source = next;
        schedule();
    }

    /** Called only with metadata matched to the currently latched decoder texture. */
    public synchronized void observe(PreviewMotionClock.Frame frame) {
        Source current = source;
        if (current == null || frame == null || !frame.hasDecodedPtsFor(current.key())
                || frame.decodedPtsUs() < current.startUs()
                || frame.decodedPtsUs() >= current.endUs()) return;
        request = new Request(current, frame);
        schedule();
    }

    public FlowCache.Pair ready(PreviewMotionClock.Frame frame, long fromUs, long toUs) {
        Active current = active;
        if (current == null || current.source() != source || frame == null
                || !frame.hasDecodedPtsFor(current.source().key())
                || (frame.decodedPtsUs() != fromUs && frame.decodedPtsUs() != toUs)) return null;
        FlowCache.Pair pair = session.ready(current.generation(), fromUs, toUs);
        return current == active && current.source() == source ? pair : null;
    }

    private void schedule() {
        if (scheduled) return;
        scheduled = true;
        worker.execute(this::drain);
    }

    private void drain() {
        while (true) {
            Source selected;
            Request latest;
            synchronized (this) {
                selected = source;
                latest = request;
                request = null;
                if (latest == null && (active == null || active.source() == selected)) {
                    scheduled = false;
                    if (closed && session != null) session.close();
                    return;
                }
            }
            if (active != null && active.source() != selected) retire();
            if (latest == null || latest.source() != selected) continue;
            if (attempted != selected) {
                attempted = selected;
                plannedPts = Long.MIN_VALUE;
                prepare(selected);
            }
            Active current = active;
            long pts = latest.frame().decodedPtsUs();
            if (current != null && current.source() == source && plannedPts != pts) {
                plannedPts = pts;
                session.plan(current.generation(), pts, selected.startUs(), selected.endUs(),
                        selected.startUs(), selected.endUs());
            }
        }
    }

    private void prepare(Source selected) {
        Prepared opened = null;
        try {
            opened = selected.loader().open(() -> source != selected);
            if (opened == null || source != selected) return;
            if (session == null) session = new IndexedFlowSession(cacheFactory.open(), onReady);
            long generation = session.setSource(opened.index(), opened.geometry(), opened.decoder());
            active = new Active(selected, generation, opened);
            opened = null;
        } catch (Exception unavailable) {
            // Missing analysis leaves the existing renderer fallback available.
        } finally {
            closeMedia(opened);
        }
    }

    private void retire() {
        Active previous = active;
        active = null;
        session.clearSource(previous.generation());
        closeMedia(previous.media());
    }

    private static void closeMedia(Prepared media) {
        if (media == null) return;
        try { media.close(); } catch (Exception unavailable) {
            // The source has already been fenced.
        }
    }

    @Override public synchronized void close() {
        if (closed) return;
        source = null;
        request = null;
        closed = true;
        schedule();
        worker.shutdown();
    }
}
