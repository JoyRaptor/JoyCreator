package com.fadcam.ui.faditor.motion.flow;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Host coverage for exact renderer lookup during source, trim and load changes. */
public final class IndexedFlowSessionTest {
    private static final int SIZE = 8;
    private static final int PAIR_BYTES = SIZE * SIZE * 2 * 3 * Float.BYTES;
    private static final LumaImage FLAT = new LumaImage(SIZE, SIZE, new float[SIZE * SIZE]);
    private int assertions;

    public static void main(String[] args) throws Exception {
        var test = new IndexedFlowSessionTest();
        Path root = Files.createTempDirectory("studio-flow-session");
        try {
            test.replacementRejectsOldSource(root.resolve("replacement"));
            test.keepsOnlyNearestExactPairs(root.resolve("resident"));
            test.warmPairsAndOversizedSource(root.resolve("warm"));
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
        System.out.println("IndexedFlowSessionTest: " + test.assertions + " assertions");
    }

    private void replacementRejectsOldSource(Path root) throws Exception {
        var cache = new FlowCache(root.toFile(), 1_000_000);
        var index = index();
        var oldDecode = new CountDownLatch(1);
        var finishOld = new CountDownLatch(1);
        try (var session = new IndexedFlowSession(cache)) {
            long old = session.setSource(index, source("old"), (pts, width, height) -> {
                if (pts == 300) {
                    oldDecode.countDown();
                    if (!finishOld.await(3, TimeUnit.SECONDS)) throw new IOException("old decode timeout");
                }
                return FLAT;
            });
            session.plan(old, 100, 100, 150, 100, 201);
            check(awaitPair(session, old, 100, 200) != null, "old source publishes its exact pair");
            session.plan(old, 200, 200, 250, 200, 301);
            check(oldDecode.await(3, TimeUnit.SECONDS), "second old pair begins decoding");
            long replacement = session.setSource(index, source("replacement"), (pts, width, height) -> FLAT);
            check(old != replacement && session.ready(old, 100, 200) == null,
                    "source replacement immediately hides old renderer results");
            check(session.ready(replacement, 200, 300) == null,
                    "matching PTS is sharp while replacement work is unavailable");
            session.clearSource(old);
            session.plan(old, 100, 100, 150, 100, 201);
            session.plan(replacement, 200, 200, 250, 200, 301);
            finishOld.countDown();
            check(awaitPair(session, replacement, 200, 300) != null,
                    "replacement survives late old-source actions");
            check(cache.get(key("old", 200, 300)) == null,
                    "in-flight old pair writes no stale cache chunk");
            check(session.ready(old, 200, 300) == null, "old token cannot obtain replacement flow");
            session.clearSource(replacement);
            check(session.ready(replacement, 200, 300) == null, "clear hides all renderer results");
        } finally {
            finishOld.countDown();
        }
    }

    private void keepsOnlyNearestExactPairs(Path root) throws Exception {
        var cache = new FlowCache(root.toFile(), 1_000_000);
        try (var session = new IndexedFlowSession(cache, 2 * PAIR_BYTES)) {
            long token = session.setSource(index(), source("bounded"), (pts, width, height) -> FLAT);
            session.setPaused(true);
            session.plan(token, 300, 0, 700, 0, 700);
            check(session.ready(token, 300, 400) == null, "paused worker never blocks the renderer");
            session.setPaused(false);
            check(awaitPair(session, token, 300, 400) != null, "playhead pair is resident");
            check(awaitPair(session, token, 200, 300) != null, "nearby preceding pair is resident");
            check(awaitCachePair(cache, key("bounded", 500, 600)),
                    "more visible pairs warm disk beyond the resident budget");
            check(session.ready(token, 500, 600) == null, "farther ready pair stays outside memory budget");
            check(session.ready(token, 300, 401) == null && session.ready(token, 400, 300) == null,
                    "renderer lookup requires exact sorted pair endpoints");

            session.setPaused(true);
            session.plan(token, 400, 100, 650, 300, 600);
            check(session.ready(token, 200, 300) == null, "cut replan hides an old pair immediately");
            check(session.ready(token, 300, 400) != null, "overlapping pair stays ready during replan");
            check(session.ready(token, 400, 500) == null, "new window never substitutes old flow");
            session.setPaused(false);
            check(awaitPair(session, token, 400, 500) != null, "new window publishes exact replacement");
            session.close();
            check(session.ready(token, 400, 500) == null, "close invalidates the renderer token");
        }
    }

    private void warmPairsAndOversizedSource(Path root) throws Exception {
        var cache = new FlowCache(root.toFile(), 1_000_000);
        var decoderCalls = new AtomicInteger();
        try (var session = new IndexedFlowSession(cache, PAIR_BYTES)) {
            long token = session.setSource(index(), source("shared"), (pts, width, height) -> {
                decoderCalls.incrementAndGet();
                return FLAT;
            });
            session.plan(token, 200, 200, 250, 200, 301);
            check(awaitPair(session, token, 200, 300) != null, "first session analyzes the pair");
            check(decoderCalls.get() == 2, "first pair decodes both frames");
        }
        try (var session = new IndexedFlowSession(cache, PAIR_BYTES)) {
            long token = session.setSource(index(), source("shared"), (pts, width, height) -> {
                throw new IOException("warm lookup decoded again");
            });
            session.plan(token, 200, 200, 250, 200, 301);
            check(awaitPair(session, token, 200, 300) != null, "fresh session reads warm cache");
        }
        try (var session = new IndexedFlowSession(cache, PAIR_BYTES - 1)) {
            long token = session.setSource(index(), source("shared"), (pts, width, height) -> FLAT);
            session.plan(token, 200, 200, 250, 200, 301);
            check(awaitPair(session, token, 200, 300, 150) == null,
                    "pair too large for the budget cannot publish even from disk");
        }
    }

    private static VideoPresentationIndex index() {
        var builder = new VideoPresentationIndex.Builder();
        for (int pts = 100; pts <= 600; pts += 100) builder.add(pts);
        return builder.finish();
    }

    private static IndexedFlowQueue.Source source(String fingerprint) {
        return new IndexedFlowQueue.Source(fingerprint, 0, 0, 0, SIZE * 4, SIZE * 4,
                "host-v1", "quarter", SIZE, SIZE, 150);
    }

    private static FlowCache.Key key(String source, long from, long to) {
        return new FlowCache.Key(source, 0, 0, 0, SIZE * 4, SIZE * 4, from, to,
                "host-v1", "quarter", SIZE, SIZE, 150);
    }

    private static FlowCache.Pair awaitPair(IndexedFlowSession session, long token, long from, long to)
            throws InterruptedException {
        return awaitPair(session, token, from, to, 3000);
    }

    private static FlowCache.Pair awaitPair(IndexedFlowSession session, long token, long from, long to,
                                            long timeoutMs) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        do {
            FlowCache.Pair pair = session.ready(token, from, to);
            if (pair != null) return pair;
            Thread.sleep(5);
        } while (System.nanoTime() < deadline);
        return null;
    }

    private static boolean awaitCachePair(FlowCache cache, FlowCache.Key key) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        do {
            if (cache.get(key) != null) return true;
            Thread.sleep(5);
        } while (System.nanoTime() < deadline);
        return false;
    }

    private void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }
}
