package com.fadcam.ui.faditor.motion.flow;

import com.fadcam.ui.faditor.motion.flow.FlowCache;
import com.fadcam.ui.faditor.motion.flow.FlowField;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.file.Files;

/** STU-7 C6: persistent pair precision, keying, corruption, interruption and LRV quota. */
public final class FlowCacheTest {
    private int assertions;

    public static void main(String[] args) throws Exception {
        FlowCacheTest test = new FlowCacheTest();
        test.roundTripAndKey();
        test.corruptionIsAMiss();
        test.interruptedReplaceKeepsPriorChunk();
        test.leastRecentlyViewedQuota();
        System.out.println("FlowCacheTest: " + test.assertions + " assertions");
    }

    private void roundTripAndKey() throws Exception {
        File root = temp("roundtrip");
        FlowCache cache = new FlowCache(root, 8 * 1024 * 1024);
        FlowCache.Key key = key("source-a", 0, "high", 0, 33_367);
        FlowCache.Pair expected = pair(9, 7, 0, 33_367, 1);
        cache.put(key, expected);
        FlowCache.Pair actual = cache.get(key);
        check(actual != null, "written pair is readable");
        check(actual.forward.fromPtsUs == 0 && actual.backward.fromPtsUs == 33_367,
                "forward and backward PTS survive");
        for (int y = 0; y < 7; y++) for (int x = 0; x < 9; x++) {
            check(Math.abs(actual.forward.u(x, y) - expected.forward.u(x, y)) <= 1f / 32f,
                    "u quantization stays within half a 1/16-pixel step");
            check(Math.abs(actual.backward.v(x, y) - expected.backward.v(x, y)) <= 1f / 32f,
                    "v quantization stays within half a 1/16-pixel step");
            check(Math.abs(actual.forward.confidence(x, y) - expected.forward.confidence(x, y))
                            <= 1f / 510f + 1e-6f,
                    "confidence stays within half an 8-bit step");
        }
        check(cache.get(key("source-a", 90, "high", 0, 33_367)) == null,
                "orientation is part of the key");
        check(cache.get(key("source-a", 0, "fast", 0, 33_367)) == null,
                "detail is part of the key");
        check(cache.get(key("source-a", 0, "high", 33_367, 66_734)) == null,
                "PTS pair is part of the key");
        deleteTree(root);
    }

    private void corruptionIsAMiss() throws Exception {
        File root = temp("corrupt");
        FlowCache cache = new FlowCache(root, 8 * 1024 * 1024);
        FlowCache.Key key = key("source-b", 0, "high", 0, 33_367);
        cache.put(key, pair(9, 7, 0, 33_367, 2));
        File chunk = onlyChunk(root);
        RandomAccessFile file = new RandomAccessFile(chunk, "rw");
        long at = file.length() - 2;
        file.seek(at);
        file.write(file.read() ^ 0x40);
        file.close();
        check(cache.get(key) == null, "a CRC-damaged chunk is a cache miss");
        deleteTree(root);
    }

    private void interruptedReplaceKeepsPriorChunk() throws Exception {
        File root = temp("interrupt");
        FlowCache.Key key = key("source-c", 0, "high", 0, 33_367);
        FlowCache.Pair original = pair(9, 7, 0, 33_367, 3);
        new FlowCache(root, 8 * 1024 * 1024).put(key, original);

        File existing = onlyChunk(root);
        FlowCache interrupted = new FlowCache(root, 8 * 1024 * 1024,
                (temporary, target) -> { throw new java.io.IOException("injected interruption"); });
        try {
            interrupted.put(key, pair(9, 7, 0, 33_367, 99));
            throw new AssertionError("injected interruption was not observed");
        } catch (java.io.IOException expected) { /* complete temp never replaces prior chunk */ }
        FlowCache.Pair recovered = new FlowCache(root, 8 * 1024 * 1024).get(key);
        check(recovered != null, "prior complete chunk survives a failed replacement");
        check(Math.abs(recovered.forward.u(4, 3) - original.forward.u(4, 3)) <= 1f / 32f,
                "failed replacement does not expose partial field data");
        check(existing.isFile(), "old chunk path remains complete");
        deleteTree(root);
    }

    private void leastRecentlyViewedQuota() throws Exception {
        FlowCache.Key a = key("source-d-a", 0, "high", 0, 33_367, 96, 64);
        FlowCache.Key b = key("source-d-b", 0, "high", 0, 33_367, 96, 64);
        FlowCache.Key c = key("source-d-c", 0, "high", 0, 33_367, 96, 64);
        File measure = temp("quota-measure");
        FlowCache measuring = new FlowCache(measure, 8 * 1024 * 1024);
        measuring.put(a, pair(96, 64, 0, 33_367, 4));
        measuring.put(b, pair(96, 64, 0, 33_367, 5));
        measuring.put(c, pair(96, 64, 0, 33_367, 6));
        File[] measured = measure.listFiles((dir, name) -> name.endsWith(".flow"));
        if (measured == null || measured.length != 3) throw new AssertionError("measurement chunks");
        long quota = bytes(measure);
        long smallest = Long.MAX_VALUE;
        for (File file : measured) smallest = Math.min(smallest, file.length());
        quota -= smallest; // Enough for any two chunks, never enough for all three.
        deleteTree(measure);

        File root = temp("quota");
        FlowCache wide = new FlowCache(root, 8 * 1024 * 1024);
        wide.put(a, pair(96, 64, 0, 33_367, 4));
        Thread.sleep(20);
        wide.put(b, pair(96, 64, 0, 33_367, 5));
        Thread.sleep(20);
        check(wide.get(a) != null, "viewing an old chunk refreshes recency");
        Thread.sleep(20);
        FlowCache bounded = new FlowCache(root, quota);
        bounded.put(c, pair(96, 64, 0, 33_367, 6));
        check(bounded.get(a) != null, "recently viewed chunk remains under quota");
        check(bounded.get(b) == null, "least recently viewed chunk is evicted first");
        check(bounded.get(c) != null, "new chunk remains under quota");
        check(bytes(root) <= quota, "disk bytes stay under configured quota");
        deleteTree(root);
    }

    private static FlowCache.Key key(String source, int orientation, String detail,
                                     long fromPtsUs, long toPtsUs) {
        return key(source, orientation, detail, fromPtsUs, toPtsUs, 9, 7);
    }

    private static FlowCache.Key key(String source, int orientation, String detail,
                                     long fromPtsUs, long toPtsUs, int width, int height) {
        return new FlowCache.Key(source, orientation, 1, 2, 3, 4,
                fromPtsUs, toPtsUs, "gpu-flow-r1", detail, width, height);
    }

    private static FlowCache.Pair pair(int width, int height, long fromPtsUs, long toPtsUs, int seed) {
        int n = width * height;
        float[] fu = new float[n], fv = new float[n], fc = new float[n];
        float[] bu = new float[n], bv = new float[n], bc = new float[n];
        for (int i = 0; i < n; i++) {
            fu[i] = ((i * 37 + seed * 13) % 401 - 200) / 37f;
            fv[i] = ((i * 29 + seed * 17) % 307 - 153) / 31f;
            fc[i] = ((i * 43 + seed * 19) & 255) / 255f;
            bu[i] = -fu[i] + ((i & 3) - 1) * 0.01f;
            bv[i] = -fv[i] + ((i & 7) - 3) * 0.006f;
            bc[i] = 1f - fc[i];
        }
        return new FlowCache.Pair(
                new FlowField(width, height, fu, fv, fc, fromPtsUs, toPtsUs),
                new FlowField(width, height, bu, bv, bc, toPtsUs, fromPtsUs));
    }

    private static File temp(String name) throws Exception {
        return Files.createTempDirectory("flow-cache-" + name).toFile();
    }

    private static File onlyChunk(File root) {
        File[] files = root.listFiles((dir, name) -> name.endsWith(".flow"));
        if (files == null || files.length != 1) throw new AssertionError("expected one chunk");
        return files[0];
    }

    private static long bytes(File root) {
        long total = 0;
        File[] files = root.listFiles((dir, name) -> name.endsWith(".flow"));
        if (files != null) for (File file : files) total += file.length();
        return total;
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
