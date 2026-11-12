import com.fadcam.ui.faditor.model.Clip;
import com.fadcam.ui.faditor.model.TimeMap;
import com.fadcam.ui.faditor.model.Timeline;
import com.fadcam.ui.faditor.model.Transition;

import java.util.Random;

/**
 * TimeMap (job STU-5): where an editor moment lands in the exported file.
 *
 * Pins numbers MEASURED on the Note 9 (LEDGER §2d, 2026-08-03: one 600 ms transition at editor
 * 3200, a picture-in-picture authored at 5501 appeared at 4.90 s, the content came to 13126 ms
 * of a 13726 ms timeline), the voiceover example from the 11-11 code review, the transition
 * clamps, and that offsetBeforeClip is the sum ExportManager.editorTimeOffsetFor used to make.
 *
 * Usage:  bash tools/jvm-harness/run-timemap.sh
 */
public class TimeMapTest {
    static int pass = 0, fail = 0;

    static void check(boolean ok, String name) {
        if (ok) { pass++; } else { fail++; System.out.println("FAIL " + name); }
    }

    static void eq(long got, long want, String name) {
        check(got == want, name + "  (got " + got + ", want " + want + ")");
    }

    /** A master clip of {@code durMs} on the timeline; the uri stays null (android.jar stubs). */
    static Clip clip(long durMs) {
        Clip c = new Clip(null, 600_000);
        c.setInPointMs(0);
        c.setOutPointMs(durMs);
        return c;
    }

    static Timeline timeline(long[] clips, long[][] transitions) {
        Timeline tl = new Timeline();
        for (long d : clips) tl.addClip(clip(d));
        for (long[] t : transitions) {
            tl.addTransition(new Transition(Transition.Type.CROSS_DISSOLVE, t[1], (int) t[0]));
        }
        return tl;
    }

    /** The old ExportManager.editorTimeOffsetFor loop, kept here as the reference. */
    static long legacyOffset(Timeline tl, int idx) {
        long off = 0L;
        for (Transition t : tl.getTransitions()) {
            if (t.clipIndex < idx) off += TimeMap.effectiveTransitionMs(tl, t, t.clipIndex);
        }
        return off;
    }

    public static void main(String[] args) {
        // ── No transitions: the file is the timeline ──────────────────────────────────────
        TimeMap plain = TimeMap.of(timeline(new long[]{3000, 4000}, new long[][]{}));
        eq(plain.editorToFileMs(0), 0, "no transitions: 0");
        eq(plain.editorToFileMs(6999), 6999, "no transitions: identity");
        eq(plain.totalSwallowedMs(), 0, "no transitions: nothing swallowed");
        eq(plain.offsetBeforeClip(1), 0, "no transitions: no offset");

        // ── The §2d device fixture ─────────────────────────────────────────────────────────
        Timeline f = timeline(new long[]{3200, 5526, 5000}, new long[][]{{0, 600}});
        TimeMap m = TimeMap.of(f);
        eq(m.clipEditorStartMs(1), 3200, "fixture: clip 1 starts at the seam");
        eq(m.offsetBeforeClip(0), 0, "fixture: clip 0 offset");
        eq(m.offsetBeforeClip(1), 600, "fixture: clip 1 offset (device: 600)");
        eq(m.offsetBeforeClip(2), 600, "fixture: clip 2 offset");
        eq(m.editorToFileMs(5501), 4901, "fixture: PiP at 5501 shows at 4.90 s (measured)");
        eq(m.editorToFileMs(13726), 13126, "fixture: 13726 ms of timeline is 13126 ms of file (measured)");
        eq(m.totalSwallowedMs(), 600, "fixture: swallowed");
        eq(m.editorToFileMs(3000), 3000, "fixture: the outgoing tail is not moved");
        eq(m.editorToFileMs(3500), 3200, "fixture: the incoming head folds onto the blend's end");
        eq(m.editorToFileMs(3800), 3200, "fixture: fold ends at P + T");
        eq(m.editorToFileMs(3900), 3300, "fixture: after the fold, minus the whole transition");

        // ── Code review D5: two 0.6 s transitions, a voiceover after both ──────────────────
        TimeMap two = TimeMap.of(timeline(new long[]{5000, 5000, 5000},
                new long[][]{{0, 600}, {1, 600}}));
        eq(two.editorToFileMs(12000), 10800, "D5: voiceover at 12 s plays at 10.8 s");
        eq(two.editorToFileMs(15000), 13800, "E2: the file ends 1.2 s early, no black tail");
        eq(two.offsetBeforeClip(5), 1200, "off the master track: past every seam");
        eq(two.offsetBeforeClip(-1), 1200, "not on the master track: past every seam");

        // ── Clamps ─────────────────────────────────────────────────────────────────────────
        TimeMap longT = TimeMap.of(timeline(new long[]{5000, 1000}, new long[][]{{0, 3000}}));
        eq(longT.seamMs(0), 1000, "a transition longer than its clip is cut to the clip");
        TimeMap both = TimeMap.of(timeline(new long[]{5000, 1000, 5000},
                new long[][]{{0, 800}, {1, 800}}));
        eq(both.seamMs(0), 500, "a short clip with two transitions: each gets its share (in)");
        eq(both.seamMs(1), 500, "a short clip with two transitions: each gets its share (out)");
        eq(both.headMs(2), 500, "headMs is the seam before");
        eq(both.headMs(0), 0, "the first clip has no head transition");

        // ── Clip-local time of a head-trimmed clip at 2x ───────────────────────────────────
        Clip fast = clip(4000);
        fast.setSpeedMultiplier(2f);
        long overlapSourceMs = Math.round(600 * fast.getSpeedMultiplier()); // as the export trims
        eq(TimeMap.clipLocalMsAt(fast, fast.getInPointMs() + overlapSourceMs), 600,
                "2x clip after a 600 ms transition: its item starts at clip-local 600");
        eq(TimeMap.clipLocalMsAt(fast, 0), 0, "clip-local of the in-point is 0");

        // ── Random timelines: same offsets as the old loop, never runs backwards ───────────
        Random r = new Random(20261112L);
        for (int round = 0; round < 400; round++) {
            int n = 1 + r.nextInt(6);
            long[] clips = new long[n];
            for (int i = 0; i < n; i++) clips[i] = 200 + r.nextInt(6000);
            java.util.List<long[]> ts = new java.util.ArrayList<>();
            for (int s = 0; s < n - 1; s++) {
                if (r.nextInt(3) > 0) ts.add(new long[]{s, 100 + r.nextInt(3000)});
            }
            Timeline tl = timeline(clips, ts.toArray(new long[0][]));
            TimeMap tm = TimeMap.of(tl);
            for (int i = 0; i < n; i++) {
                if (tm.offsetBeforeClip(i) != legacyOffset(tl, i)) {
                    check(false, "random " + round + ": clip " + i + " offset differs from the old loop");
                }
                // A clip's body (past its head fold) sits at editor start minus its offset.
                long body = tm.clipEditorStartMs(i) + tm.headMs(i);
                if (tm.editorToFileMs(body) != body - tm.offsetBeforeClip(i)) {
                    check(false, "random " + round + ": clip " + i + " body is not at start - offset");
                }
            }
            long end = tm.clipEditorStartMs(n);
            long prev = Long.MIN_VALUE;
            boolean mono = true;
            for (long t = 0; t <= end; t += 37) {
                long fm = tm.editorToFileMs(t);
                if (fm < prev) mono = false;
                prev = fm;
            }
            check(mono, "random " + round + ": never runs backwards");
            eq(tm.editorToFileMs(end), end - tm.totalSwallowedMs(), "random " + round + ": end of track");
        }

        System.out.println("TimeMapTest: " + pass + " passed, " + fail + " failed");
        System.exit(fail == 0 ? 0 : 1);
    }
}
