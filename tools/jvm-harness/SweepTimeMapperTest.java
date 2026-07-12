import com.fadcam.ui.faditor.avatar.SweepTimeMapper;

/**
 * JVM harness for the point-at-video timeline→clip walk (SweepTimeMapper): the
 * pure half of the sweep's time mapping. The second hop (clip-local visual ms →
 * source ms) is Clip.mapToSourceMs — the existing single authority the sweep
 * consumes, deliberately NOT re-tested here. Run like the other harnesses
 * (javac vs the app's built classes + gson + annotation-jvm).
 */
public class SweepTimeMapperTest {

    private static int passed = 0, failed = 0;

    public static void main(String[] args) {
        singleClip();
        gaplessCumulativeWalk();
        boundariesAreStartInclusiveEndExclusive();
        degenerateAndEmptySpans();
        localMsMatchesWalk();
        System.out.println(failed == 0 ? "ALL GREEN (" + passed + "/" + (passed + failed) + ")"
                : "FAILURES: " + failed + " (passed " + passed + ")");
        if (failed > 0) System.exit(1);
    }

    static void singleClip() {
        long[] spans = {5000};
        check("single: t=0 → clip 0", SweepTimeMapper.clipIndexAt(spans, 0) == 0);
        check("single: mid → clip 0", SweepTimeMapper.clipIndexAt(spans, 2500) == 0);
        check("single: local = timeline", SweepTimeMapper.clipLocalMs(spans, 0, 2500) == 2500);
        check("single: past end → -1", SweepTimeMapper.clipIndexAt(spans, 5000) == -1);
        check("single: negative → -1", SweepTimeMapper.clipIndexAt(spans, -1) == -1);
    }

    /** Three clips laid gaplessly: the walk mirrors Timeline.segmentStartMs. */
    static void gaplessCumulativeWalk() {
        long[] spans = {3000, 2000, 4000};
        check("walk: inside clip 1", SweepTimeMapper.clipIndexAt(spans, 3500) == 1);
        check("walk: clip 1 local", SweepTimeMapper.clipLocalMs(spans, 1, 3500) == 500);
        check("walk: inside clip 2", SweepTimeMapper.clipIndexAt(spans, 8999) == 2);
        check("walk: clip 2 local", SweepTimeMapper.clipLocalMs(spans, 2, 8999) == 3999);
        check("walk: total end → -1", SweepTimeMapper.clipIndexAt(spans, 9000) == -1);
    }

    /** Seams belong to the NEXT clip: [start, start+span) per clip. */
    static void boundariesAreStartInclusiveEndExclusive() {
        long[] spans = {3000, 2000};
        check("seam: t=2999 is clip 0", SweepTimeMapper.clipIndexAt(spans, 2999) == 0);
        check("seam: t=3000 is clip 1", SweepTimeMapper.clipIndexAt(spans, 3000) == 1);
        check("seam: clip 1 local 0", SweepTimeMapper.clipLocalMs(spans, 1, 3000) == 0);
    }

    /** Zero/negative spans occupy no timeline width; empty timeline maps nothing. */
    static void degenerateAndEmptySpans() {
        long[] spans = {3000, 0, 2000};
        check("degenerate: seam skips 0-span clip",
                SweepTimeMapper.clipIndexAt(spans, 3000) == 2);
        check("degenerate: local past the 0-span clip",
                SweepTimeMapper.clipLocalMs(spans, 2, 3000) == 0);
        long[] neg = {3000, -500, 2000};
        check("degenerate: negative span treated as 0",
                SweepTimeMapper.clipIndexAt(neg, 3000) == 2
                        && SweepTimeMapper.clipLocalMs(neg, 2, 3000) == 0);
        check("empty: no clips → -1", SweepTimeMapper.clipIndexAt(new long[0], 0) == -1);
        check("bad index: local → -1",
                SweepTimeMapper.clipLocalMs(spans, 3, 100) == -1
                        && SweepTimeMapper.clipLocalMs(spans, -1, 100) == -1);
    }

    /** clipLocalMs(index from clipIndexAt) is always in [0, span). */
    static void localMsMatchesWalk() {
        long[] spans = {1234, 777, 4567};
        boolean ok = true;
        for (long t = 0; t < 1234 + 777 + 4567; t += 111) {
            int i = SweepTimeMapper.clipIndexAt(spans, t);
            long local = SweepTimeMapper.clipLocalMs(spans, i, t);
            if (i < 0 || local < 0 || local >= spans[i]) { ok = false; break; }
        }
        check("sweep: every sampled t lands in-range", ok);
    }

    static void check(String name, boolean cond) {
        if (cond) { passed++; System.out.println("  ok  " + name); }
        else { failed++; System.out.println("FAIL  " + name); }
    }
}
