import com.fadcam.ui.faditor.motion.flow.SourceFrameRing;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * STU-7 C6: the source-frame ring never pairs frames across a seek, a cut, a gap or a shape
 * change, never leaks or double-releases a frame, and lets stale async work be dropped.
 *
 * <p>Negative control: {@code --naive} runs the same checks on a ring that just keeps the last N
 * frames (what a quick implementation would do); it must fail.
 */
public final class SourceFrameRingTest {
    private int assertions;

    /** The operations under test, so the real ring and the naive one run the same checks. */
    private interface Ring {
        void push(long pts, String source, int w, int h, int rot, int handle);
        Integer previous(long pts);
        Integer next(long pts);
        void invalidate();
        long generation();
        int size();
    }

    public static void main(String[] args) {
        boolean naive = args.length > 0 && args[0].equals("--naive");
        SourceFrameRingTest t = new SourceFrameRingTest();
        t.run(naive);
        System.out.println("SourceFrameRingTest: " + t.assertions + " assertions");
    }

    private final Map<Integer, Integer> released = new HashMap<>();

    private void run(boolean naive) {
        Ring r = naive ? naiveRing(3) : realRing(3, 100_000);
        long f = 33_367;   // 29.97 fps
        // Fresh start: no neighbour yet, so the first frame is drawn sharp.
        r.push(0, "clipA", 64, 36, 0, 1);
        check(r.previous(0) == null, "first frame has no previous");
        r.push(f, "clipA", 64, 36, 0, 2);
        check(eq(r.previous(f), 1), "playing: previous is the last decoded frame");
        r.push(2 * f, "clipA", 64, 36, 0, 3);
        check(eq(r.previous(2 * f), 2) && eq(r.next(f), 3), "centred: both neighbours");
        // Capacity: the oldest frame leaves and is released once.
        r.push(3 * f, "clipA", 64, 36, 0, 4);
        check(r.size() == 3 && count(1) == 1, "oldest released exactly once at capacity");
        // Paused redraw of the same PTS: the duplicate is released, the held frame stays.
        r.push(3 * f, "clipA", 64, 36, 0, 40);
        check(count(40) == 1 && eq(r.previous(3 * f), 3), "paused redraw keeps the held frame");
        // Seek backwards to a point between held frames: never pair it with the old run.
        long seek = 2 * f + 10_000;
        r.push(seek, "clipA", 64, 36, 0, 5);
        check(r.previous(seek) == null, "after a seek back the frame is sharp until a neighbour");
        check(count(2) == 1 && count(3) == 1 && count(4) == 1, "seek releases the old run");
        // A cut: a different clip in the same slot.
        r.push(seek + f, "clipB", 64, 36, 0, 6);
        check(r.previous(seek + f) == null, "a cut never pairs two shots");
        // A long gap (dropped frames, a jump) counts as a break.
        long far = seek + f + 200_000;
        r.push(far, "clipB", 64, 36, 0, 7);
        check(r.previous(far) == null, "a long gap is a break");
        // A rotation or size change.
        r.push(far + f, "clipB", 64, 36, 90, 8);
        check(r.previous(far + f) == null, "a rotation change is a break");
        // Explicit invalidate bumps the generation so stale async results are dropped.
        long gen = r.generation();
        r.invalidate();
        check(r.generation() != gen && r.size() == 0, "invalidate empties and bumps the generation");
        // Every handle pushed has been released at most once, and all but none are held now.
        for (int h : new int[]{1, 2, 3, 4, 5, 6, 7, 8, 40}) check(count(h) == 1, "handle " + h + " released once");
    }

    private Ring realRing(int capacity, long maxGap) {
        SourceFrameRing<Integer> ring = new SourceFrameRing<>(capacity, maxGap, this::release);
        return new Ring() {
            public void push(long pts, String s, int w, int h, int rot, int handle) {
                ring.push(new SourceFrameRing.Frame<>(pts, s, w, h, rot, handle));
            }
            public Integer previous(long pts) { var x = ring.previous(pts); return x == null ? null : x.handle; }
            public Integer next(long pts) { var x = ring.next(pts); return x == null ? null : x.handle; }
            public void invalidate() { ring.invalidate(); }
            public long generation() { return ring.generation(); }
            public int size() { return ring.size(); }
        };
    }

    /** Keeps the last N frames by PTS order of arrival; no breaks, no generations. */
    private Ring naiveRing(int capacity) {
        List<long[]> held = new ArrayList<>();
        return new Ring() {
            public void push(long pts, String s, int w, int h, int rot, int handle) {
                // It gets paused redraws right, so the control proves the BREAK checks bite.
                if (!held.isEmpty() && held.get(held.size() - 1)[0] == pts) { release(handle); return; }
                held.add(new long[]{pts, handle});
                while (held.size() > capacity) release((int) held.remove(0)[1]);
            }
            public Integer previous(long pts) {
                long[] best = null;
                for (long[] e : held) if (e[0] < pts && (best == null || e[0] > best[0])) best = e;
                return best == null ? null : (int) best[1];
            }
            public Integer next(long pts) {
                long[] best = null;
                for (long[] e : held) if (e[0] > pts && (best == null || e[0] < best[0])) best = e;
                return best == null ? null : (int) best[1];
            }
            public void invalidate() { for (long[] e : held) release((int) e[1]); held.clear(); }
            public long generation() { return 0; }
            public int size() { return held.size(); }
        };
    }

    private void release(int handle) { released.merge(handle, 1, Integer::sum); }

    private int count(int handle) { return released.getOrDefault(handle, 0); }

    private static boolean eq(Integer a, int b) { return a != null && a == b; }

    private void check(boolean ok, String what) {
        assertions++;
        if (!ok) throw new AssertionError(what);
    }
}
