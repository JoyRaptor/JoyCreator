package com.fadcam.ui.faditor.motion.flow;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * One-worker, range-first coordinator for analysed footage flow (STU-7 C6, spec section 6).
 *
 * <p>The host supplies decode/estimate work because a GPU estimator may require its own context.
 * This class owns only the hard scheduling contract: visible PTS pairs first, disk cache before
 * analysis, a cancellable source generation, pause under load, and no stale callback or cache
 * write after the source changes. Reprioritising a viewport does not cancel running source-space
 * analysis; that result remains valid across a seek.
 */
public final class FlowAnalyzer implements AutoCloseable {
    public interface Cancellation {
        boolean isCancelled();
    }

    public interface Work {
        FlowCache.Key key();
        FlowCache.Pair analyze(Cancellation cancellation) throws Exception;
    }

    public interface Listener {
        void ready(FlowCache.Key key, FlowCache.Pair pair, boolean fromCache);
        void failed(FlowCache.Key key, Exception problem);
    }

    private final Object lock = new Object();
    private final FlowCache cache;
    private final Listener listener;
    private final ExecutorService worker;
    private final List<Entry> pending = new ArrayList<>();
    private final Set<String> scheduled = new HashSet<>();
    private long generation = Long.MIN_VALUE;
    private long order;
    private boolean paused;
    private boolean closed;
    private String runningId;

    public FlowAnalyzer(FlowCache cache, Listener listener) {
        this(cache, listener, runnable -> {
            Thread thread = new Thread(runnable, "Studio flow analysis");
            thread.setDaemon(true);
            thread.setPriority(Thread.MIN_PRIORITY);
            return thread;
        });
    }

    FlowAnalyzer(FlowCache cache, Listener listener, ThreadFactory threadFactory) {
        if (cache == null || listener == null || threadFactory == null) {
            throw new IllegalArgumentException("missing flow analyzer dependency");
        }
        this.cache = cache;
        this.listener = listener;
        worker = Executors.newSingleThreadExecutor(threadFactory);
        worker.execute(this::runLoop);
    }

    /**
     * Replaces queued work for the current viewport. A changed generation means the source,
     * orientation or crop changed and cancels all older work. The same generation only
     * reprioritises pending pairs; an already-running pair can finish and populate the cache.
     */
    public void plan(long sourceGeneration, long visibleStartUs, long visibleEndUs,
                     List<? extends Work> work) {
        if (visibleEndUs < visibleStartUs) throw new IllegalArgumentException("reversed visible range");
        synchronized (lock) {
            if (closed) return;
            if (sourceGeneration != generation) {
                generation = sourceGeneration;
                pending.clear();
                scheduled.clear();
                if (runningId != null) scheduled.add(runningId);
            } else {
                pending.clear();
                scheduled.clear();
                if (runningId != null) scheduled.add(runningId);
            }
            for (Work item : work) {
                FlowCache.Key key = item.key();
                String id = id(key);
                if (!scheduled.add(id)) continue;
                long midpoint = key.fromPtsUs + (key.toPtsUs - key.fromPtsUs) / 2;
                long distance = midpoint < visibleStartUs ? visibleStartUs - midpoint
                        : midpoint > visibleEndUs ? midpoint - visibleEndUs : 0;
                pending.add(new Entry(sourceGeneration, distance, order++, id, item));
            }
            pending.sort(Entry::compare);
            lock.notifyAll();
        }
    }

    /** Pauses before the next pair. The running estimator must observe its own thermal limits. */
    public void setPaused(boolean paused) {
        synchronized (lock) {
            this.paused = paused;
            lock.notifyAll();
        }
    }

    public int pendingCount() {
        synchronized (lock) { return pending.size(); }
    }

    @Override public void close() {
        synchronized (lock) {
            closed = true;
            generation++;
            pending.clear();
            scheduled.clear();
            lock.notifyAll();
        }
        worker.shutdownNow();
    }

    private void runLoop() {
        while (true) {
            Entry entry;
            synchronized (lock) {
                while (!closed && (paused || pending.isEmpty())) {
                    try {
                        lock.wait();
                    } catch (InterruptedException interrupted) {
                        if (closed) return;
                    }
                }
                if (closed) return;
                entry = pending.remove(0);
                runningId = entry.id;
            }
            run(entry);
            synchronized (lock) {
                scheduled.remove(entry.id);
                if (entry.id.equals(runningId)) runningId = null;
            }
        }
    }

    private void run(Entry entry) {
        Cancellation cancellation = () -> {
            synchronized (lock) { return closed || generation != entry.generation; }
        };
        try {
            FlowCache.Pair pair = cache.get(entry.work.key());
            boolean fromCache = pair != null;
            if (pair == null) pair = entry.work.analyze(cancellation);
            if (pair == null || cancellation.isCancelled()) return;
            if (!fromCache) {
                cache.put(entry.work.key(), pair);
                if (cancellation.isCancelled()) return;
            }
            listener.ready(entry.work.key(), pair, fromCache);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (Exception problem) {
            if (!cancellation.isCancelled()) listener.failed(entry.work.key(), problem);
        }
    }

    private static String id(FlowCache.Key key) {
        byte[] digest = key.digest();
        StringBuilder out = new StringBuilder(digest.length * 2);
        for (byte b : digest) out.append(String.format("%02x", b & 0xff));
        return out.toString();
    }

    private static final class Entry {
        final long generation;
        final long distance;
        final long order;
        final String id;
        final Work work;

        Entry(long generation, long distance, long order, String id, Work work) {
            this.generation = generation;
            this.distance = distance;
            this.order = order;
            this.id = id;
            this.work = work;
        }

        static int compare(Entry a, Entry b) {
            int byDistance = Long.compare(a.distance, b.distance);
            return byDistance != 0 ? byDistance : Long.compare(a.order, b.order);
        }
    }
}
