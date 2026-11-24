package com.fadcam.ui.faditor.motion.flow;

import java.io.IOException;
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

    /** Invoked on the worker. Post any longer work and recheck the generation when it runs. */
    public interface Listener {
        void ready(long sourceGeneration, FlowCache.Key key, FlowCache.Pair pair, boolean fromCache);
        void failed(long sourceGeneration, FlowCache.Key key, Exception problem);
    }

    private final Object lock = new Object();
    private final FlowCache cache;
    private final Listener listener;
    private final ExecutorService worker;
    private final List<Entry> pending = new ArrayList<>();
    private long generation = Long.MIN_VALUE;
    private long epoch;
    private long order;
    private boolean paused;
    private boolean closed;
    private Entry running;

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
                epoch++;
            }
            pending.clear();
            Set<String> scheduled = new HashSet<>();
            if (running != null && running.epoch == epoch) scheduled.add(running.id);
            for (Work item : work) {
                FlowCache.Key key = item.key();
                String id = id(key);
                if (!scheduled.add(id)) continue;
                long midpoint = key.fromPtsUs + (key.toPtsUs - key.fromPtsUs) / 2;
                long distance = midpoint < visibleStartUs ? visibleStartUs - midpoint
                        : midpoint > visibleEndUs ? midpoint - visibleEndUs : 0;
                pending.add(new Entry(epoch, distance, order++, id, key, item));
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
            pending.clear();
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
                running = entry;
            }
            run(entry);
            synchronized (lock) {
                running = null;
            }
        }
    }

    private void run(Entry entry) {
        Cancellation cancellation = () -> {
            synchronized (lock) { return !isCurrent(entry); }
        };
        try {
            FlowCache.Pair pair = cache.get(entry.key);
            boolean fromCache = pair != null;
            if (pair == null) pair = entry.work.analyze(cancellation);
            if (pair == null || cancellation.isCancelled()) return;
            if (!fromCache) {
                try {
                    cache.put(entry.key, pair, (temporary, target) -> {
                        synchronized (lock) {
                            if (!isCurrent(entry)) return false;
                            FlowCache.renameChunk(temporary, target);
                            return true;
                        }
                    });
                } catch (IOException unwritableCache) {
                    // The current frame can still use its valid in-memory analysis.
                }
            }
            synchronized (lock) {
                if (isCurrent(entry)) listener.ready(generation, entry.key, pair, fromCache);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (Exception problem) {
            synchronized (lock) {
                if (isCurrent(entry)) listener.failed(generation, entry.key, problem);
            }
        }
    }

    // Called with lock held, so membership, the final rename and delivery cannot race plan().
    private boolean isCurrent(Entry entry) { return !closed && epoch == entry.epoch; }

    private static String id(FlowCache.Key key) {
        byte[] digest = key.digest();
        StringBuilder out = new StringBuilder(digest.length * 2);
        for (byte b : digest) out.append(String.format("%02x", b & 0xff));
        return out.toString();
    }

    private static final class Entry {
        final long epoch;
        final long distance;
        final long order;
        final String id;
        final FlowCache.Key key;
        final Work work;

        Entry(long epoch, long distance, long order, String id, FlowCache.Key key, Work work) {
            this.epoch = epoch;
            this.distance = distance;
            this.order = order;
            this.id = id;
            this.key = key;
            this.work = work;
        }

        static int compare(Entry a, Entry b) {
            int byDistance = Long.compare(a.distance, b.distance);
            return byDistance != 0 ? byDistance : Long.compare(a.order, b.order);
        }
    }
}
