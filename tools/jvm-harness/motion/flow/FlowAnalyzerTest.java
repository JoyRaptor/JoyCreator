package com.fadcam.ui.faditor.motion.flow;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** STU-7 C6: visible-first analysis, cache reuse, pause and stale-generation cancellation. */
public final class FlowAnalyzerTest {
    private int assertions;

    public static void main(String[] args) throws Exception {
        FlowAnalyzerTest test = new FlowAnalyzerTest();
        test.visibleRangeRunsFirstAndCacheReuses();
        test.pauseAndResume();
        test.sourceGenerationCancelsStaleResult();
        test.sourceChangeDuringCacheCommit();
        test.storageFailureStillDeliversCurrentPair();
        System.out.println("FlowAnalyzerTest: " + test.assertions + " assertions");
    }

    private void visibleRangeRunsFirstAndCacheReuses() throws Exception {
        File root = Files.createTempDirectory("flow-analyzer-range").toFile();
        List<String> order = Collections.synchronizedList(new java.util.ArrayList<>());
        CountDownLatch ready = new CountDownLatch(3);
        AtomicInteger analyses = new AtomicInteger();
        FlowAnalyzer.Listener listener = listener(order, ready);
        FlowAnalyzer analyzer = new FlowAnalyzer(new FlowCache(root, 1_000_000), listener);
        FlowAnalyzer.Work early = work("early", 0, 10, analyses);
        FlowAnalyzer.Work visible = work("visible", 100, 110, analyses);
        FlowAnalyzer.Work late = work("late", 200, 210, analyses);
        analyzer.plan(1, 90, 120, Arrays.asList(early, late, visible));
        check(ready.await(3, TimeUnit.SECONDS), "range plan completes");
        check(order.get(0).equals("visible:false"), "visible PTS pair analyses first");
        check(analyses.get() == 3, "each uncached pair analyses once");

        CountDownLatch cachedReady = new CountDownLatch(1);
        order.clear();
        FlowAnalyzer cached = new FlowAnalyzer(new FlowCache(root, 1_000_000), listener(order, cachedReady));
        cached.plan(1, 90, 120, Collections.singletonList(visible));
        check(cachedReady.await(3, TimeUnit.SECONDS), "cached pair completes");
        check(order.get(0).equals("visible:true") && analyses.get() == 3,
                "disk cache bypasses the estimator");
        analyzer.close();
        cached.close();
        deleteTree(root);
    }

    private void pauseAndResume() throws Exception {
        File root = Files.createTempDirectory("flow-analyzer-pause").toFile();
        CountDownLatch ready = new CountDownLatch(1);
        AtomicInteger analyses = new AtomicInteger();
        FlowAnalyzer analyzer = new FlowAnalyzer(new FlowCache(root, 1_000_000),
                listener(Collections.synchronizedList(new java.util.ArrayList<>()), ready));
        analyzer.setPaused(true);
        analyzer.plan(1, 0, 20, Collections.singletonList(work("paused", 0, 10, analyses)));
        check(!ready.await(100, TimeUnit.MILLISECONDS) && analyses.get() == 0,
                "load pause starts no new analysis");
        analyzer.setPaused(false);
        check(ready.await(3, TimeUnit.SECONDS) && analyses.get() == 1,
                "resume continues queued analysis");
        analyzer.close();
        deleteTree(root);
    }

    private void sourceGenerationCancelsStaleResult() throws Exception {
        File root = Files.createTempDirectory("flow-analyzer-cancel").toFile();
        List<String> delivered = Collections.synchronizedList(new java.util.ArrayList<>());
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch ready = new CountDownLatch(1);
        FlowAnalyzer analyzer = new FlowAnalyzer(new FlowCache(root, 1_000_000), listener(delivered, ready));
        FlowAnalyzer.Work stale = new FlowAnalyzer.Work() {
            final FlowCache.Key key = FlowAnalyzerTest.key("stale", 0, 10);
            public FlowCache.Key key() { return key; }
            public FlowCache.Pair analyze(FlowAnalyzer.Cancellation cancellation) throws Exception {
                entered.countDown();
                while (!cancellation.isCancelled()) Thread.sleep(2);
                return pair(0, 10, 1);
            }
        };
        analyzer.plan(1, 0, 20, Collections.singletonList(stale));
        check(entered.await(3, TimeUnit.SECONDS), "old generation begins");
        AtomicInteger freshAnalyses = new AtomicInteger();
        analyzer.plan(2, 20, 40, Collections.singletonList(work("fresh", 20, 30, freshAnalyses)));
        check(ready.await(3, TimeUnit.SECONDS), "new generation completes");
        check(delivered.size() == 1 && delivered.get(0).equals("fresh:false"),
                "cancelled generation has no stale callback");
        check(new FlowCache(root, 1_000_000).get(key("stale", 0, 10)) == null,
                "cancelled generation writes no stale cache chunk");
        analyzer.close();
        deleteTree(root);
    }

    private void sourceChangeDuringCacheCommit() throws Exception {
        File root = Files.createTempDirectory("flow-analyzer-commit-race").toFile();
        CountDownLatch beforeRename = new CountDownLatch(1);
        CountDownLatch allowRename = new CountDownLatch(1);
        CountDownLatch ready = new CountDownLatch(1);
        AtomicInteger writes = new AtomicInteger();
        AtomicInteger analyses = new AtomicInteger();
        List<String> delivered = Collections.synchronizedList(new java.util.ArrayList<>());
        FlowCache cache = new FlowCache(root, 1_000_000, (temporary, target) -> {
            if (writes.incrementAndGet() != 1) return;
            beforeRename.countDown();
            try {
                if (!allowRename.await(3, TimeUnit.SECONDS)) throw new IOException("test rename timeout");
            } catch (InterruptedException stop) {
                Thread.currentThread().interrupt();
                throw new IOException(stop);
            }
        });
        try (FlowAnalyzer analyzer = new FlowAnalyzer(cache, listener(delivered, ready))) {
            var item = work("same-key", 0, 10, analyses);
            analyzer.plan(1, 0, 10, Collections.singletonList(item));
            check(beforeRename.await(3, TimeUnit.SECONDS), "old pair reaches publication fence");
            analyzer.plan(2, 0, 10, Collections.singletonList(item));
            allowRename.countDown();
            check(ready.await(3, TimeUnit.SECONDS), "replacement using the same key completes");
            check(writes.get() == 2 && analyses.get() == 2
                    && delivered.equals(Collections.singletonList("same-key:false")),
                    "cancelled rename leaves a cache miss for the replacement analysis");
            check(cache.get(item.key()) != null, "only the replacement publishes the cache chunk");
        } finally {
            allowRename.countDown();
            deleteTree(root);
        }
    }

    private void storageFailureStillDeliversCurrentPair() throws Exception {
        File root = Files.createTempDirectory("flow-analyzer-unwritable").toFile();
        CountDownLatch ready = new CountDownLatch(1);
        var delivered = Collections.synchronizedList(new java.util.ArrayList<String>());
        FlowCache cache = new FlowCache(root, 1_000_000, (temporary, target) -> {
            throw new IOException("injected storage failure");
        });
        try (FlowAnalyzer analyzer = new FlowAnalyzer(cache, listener(delivered, ready))) {
            var item = work("memory-only", 0, 10, new AtomicInteger());
            analyzer.plan(1, 0, 10, Collections.singletonList(item));
            check(ready.await(3, TimeUnit.SECONDS) && delivered.get(0).equals("memory-only:false"),
                    "current analysis is delivered when storage fails");
            check(cache.get(item.key()) == null, "failed cache write leaves no corrupt result");
        } finally {
            deleteTree(root);
        }
    }

    private static FlowAnalyzer.Work work(String source, long from, long to, AtomicInteger analyses) {
        return new FlowAnalyzer.Work() {
            final FlowCache.Key key = FlowAnalyzerTest.key(source, from, to);
            public FlowCache.Key key() { return key; }
            public FlowCache.Pair analyze(FlowAnalyzer.Cancellation cancellation) {
                analyses.incrementAndGet();
                return pair(from, to, source.hashCode());
            }
        };
    }

    private static FlowAnalyzer.Listener listener(List<String> delivered, CountDownLatch ready) {
        return new FlowAnalyzer.Listener() {
            public void ready(long generation, FlowCache.Key key, FlowCache.Pair pair, boolean fromCache) {
                delivered.add(key.sourceFingerprint + ":" + fromCache);
                ready.countDown();
            }
            public void failed(long generation, FlowCache.Key key, Exception problem) {
                throw new AssertionError("analysis failed for " + key.sourceFingerprint, problem);
            }
        };
    }

    private static FlowCache.Key key(String source, long from, long to) {
        return new FlowCache.Key(source, 0, 0, 0, 0, 0, from, to,
                "gpu-flow-r1", "high", 4, 3);
    }

    private static FlowCache.Pair pair(long from, long to, int seed) {
        float[] u = new float[12], v = new float[12], c = new float[12];
        for (int i = 0; i < 12; i++) {
            u[i] = (seed + i) % 7;
            v[i] = -u[i];
            c[i] = 1f;
        }
        return new FlowCache.Pair(new FlowField(4, 3, u, v, c, from, to),
                new FlowField(4, 3, v, u, c, to, from));
    }

    private static void deleteTree(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        if (!file.delete()) throw new AssertionError("could not delete " + file);
    }

    private void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }
}
