import com.fadcam.ui.faditor.model.Clip;
import com.fadcam.ui.faditor.model.PreviewClock;
import com.fadcam.ui.faditor.model.TimeMap;
import com.fadcam.ui.faditor.model.Timeline;
import com.fadcam.ui.faditor.model.Transition;

import java.util.Random;

/**
 * STU-5, the preview half of the one time authority (PreviewClock). Two promises:
 * <ol>
 *   <li>Where the preview was already right (clips at normal speed, no loop extensions, no clamped
 *       or doubled transitions) NOTHING moves: frozen copies of the old preview code agree with
 *       PreviewClock on thousands of random timelines.</li>
 *   <li>Where it was wrong, it now agrees with the EXPORT ({@link TimeMap}): pinned cases with the
 *       numbers worked by hand.</li>
 * </ol>
 * Run: {@code bash tools/jvm-harness/run-previewclock.sh}
 */
public class PreviewClockTest {

    static int passed, failed;

    static void check(String what, boolean ok) {
        if (ok) passed++; else { failed++; System.out.println("FAIL " + what); }
    }

    static Clip clip(long mediaMs, float speed) {
        Clip c = new Clip(null, 600_000);
        c.setInPointMs(0);
        c.setOutPointMs(mediaMs);
        c.setSpeedMultiplier(speed);
        return c;
    }

    static Timeline timeline(Clip[] clips, long[][] transitions) {
        Timeline tl = new Timeline();
        for (Clip c : clips) tl.addClip(c);
        for (long[] t : transitions) {
            tl.addTransition(new Transition(Transition.Type.CROSS_DISSOLVE, t[1], (int) t[0]));
        }
        return tl;
    }

    // ── frozen copies of the preview code before 2026-11-24 (FaditorEditorActivity) ──

    /** getAbsolutePlayheadMs: the sum of TRIMMED lengths before the clip, plus position / speed. */
    static long legacyAbs(Timeline tl, int idx, long pos) {
        long abs = 0;
        for (int i = 0; i < idx; i++) abs += tl.getClip(i).getTrimmedDurationMs();
        float speed = tl.getClip(idx).getSpeedMultiplier();
        return abs + (speed > 0 ? (long) (pos / speed) : pos);
    }

    /** segmentRelativeForAbsolute. */
    static long legacyRel(Timeline tl, int idx, long abs) {
        long segStart = 0;
        for (int i = 0; i < idx; i++) segStart += tl.getClip(i).getTrimmedDurationMs();
        float speed = tl.getClip(idx).getSpeedMultiplier();
        long off = Math.max(0L, abs - segStart);
        return speed > 0 ? (long) (off * speed) : off;
    }

    /** The playback poll's blend window: {start, length}, with the LAST transition listed on the seam. */
    static long[] legacyWindow(Timeline tl, int seam) {
        Transition t = null;
        for (int i = tl.getTransitions().size() - 1; i >= 0; i--) {
            if (tl.getTransitions().get(i).clipIndex == seam) { t = tl.getTransitions().get(i); break; }
        }
        if (t == null) return null;
        Clip clip = tl.getClip(seam);
        long clipDuration = Math.max(1L, clip.getTrimmedDurationMs());
        long src = Math.max(1L, Math.min(clipDuration, (long) (t.durationMs * clip.getSpeedMultiplier())));
        return new long[]{clipDuration - src, src};
    }

    public static void main(String[] args) {
        Random r = new Random(20261124L);

        // 1. Where the preview was right, nothing moves.
        int absBad = 0, relBad = 0, winBad = 0, tried = 0;
        for (int i = 0; i < 4000; i++) {
            int n = 2 + r.nextInt(4);
            Clip[] cs = new Clip[n];
            for (int k = 0; k < n; k++) cs[k] = clip(1000 + r.nextInt(4000), 1f);
            long[][] tr = new long[0][];
            if (r.nextBoolean()) {   // one modest transition on one seam: within both clips' budgets
                int seam = r.nextInt(n - 1);
                tr = new long[][]{{seam, 100 + r.nextInt(400)}};
            }
            Timeline tl = timeline(cs, tr);
            int idx = r.nextInt(n);
            long pos = r.nextInt((int) cs[idx].getTrimmedDurationMs());
            if (PreviewClock.editorMs(tl, idx, pos) != legacyAbs(tl, idx, pos)) absBad++;
            long abs = tl.getClipStartMs(idx) + r.nextInt((int) cs[idx].getTrimmedDurationMs());
            if (PreviewClock.clipMediaMs(tl, idx, abs) != legacyRel(tl, idx, abs)) relBad++;
            if (tr.length > 0) {
                int seam = (int) tr[0][0];
                long[] old = legacyWindow(tl, seam);
                tried++;
                if (old == null || PreviewClock.blendStartMediaMs(tl, seam) != old[0]
                        || PreviewClock.blendMediaMs(tl, seam) != old[1]) winBad++;
            }
        }
        check("normal speed, no loops: the playhead clock is unchanged (" + absBad + " of 4000 differ)", absBad == 0);
        check("normal speed, no loops: a seek lands where it did (" + relBad + " differ)", relBad == 0);
        check("normal speed, one transition: the blend opens and lasts as before (" + winBad + " of " + tried + " differ)",
                winBad == 0 && tried > 1000);

        // 2. Where it was wrong, it now agrees with the export.
        // (a) A clip at 2x before a 600 ms transition: media 4000 = 2000 on the timeline.
        Timeline fast = timeline(new Clip[]{clip(4000, 2f), clip(2000, 1f)}, new long[][]{{0, 600}});
        check("2x clip: the blend is the export's 600 ms of timeline = 1200 ms of the clip's media",
                PreviewClock.blendMediaMs(fast, 0) == 1200);
        check("2x clip: it opens at media 2800 (timeline 1400), not at media 800 (timeline 400) as before",
                PreviewClock.blendStartMediaMs(fast, 0) == 2800 && legacyWindow(fast, 0)[0] == 800);
        check("2x clip: the blend's length in timeline time is exactly the export's",
                PreviewClock.blendMediaMs(fast, 0) / 2 == TimeMap.effectiveTransitionMs(fast, fast.getTransitions().get(0), 0));

        // (b) A clip at 0.5x: media 2000 = 4000 on the timeline. The old window opened past the media's end.
        Timeline slow = timeline(new Clip[]{clip(2000, 0.5f), clip(2000, 1f)}, new long[][]{{0, 600}});
        check("0.5x clip: the blend opens inside the clip (media 1700) instead of past its end (3700)",
                PreviewClock.blendStartMediaMs(slow, 0) == 1700 && legacyWindow(slow, 0)[0] == 3700);

        // (c) A transition longer than the incoming clip: the export clamps it to that clip.
        Timeline shortB = timeline(new Clip[]{clip(2000, 1f), clip(500, 1f)}, new long[][]{{0, 800}});
        check("a transition longer than the next clip blends for the export's 500 ms, not 800",
                PreviewClock.blendMediaMs(shortB, 0) == 500 && legacyWindow(shortB, 0)[1] == 800);

        // (d) Two transitions on one seam: the export reads the FIRST; the preview read the last.
        Timeline dup = timeline(new Clip[]{clip(3000, 1f), clip(3000, 1f)}, new long[][]{{0, 300}, {0, 900}});
        check("two transitions on one seam: the preview now plays the one the export plays (the first)",
                PreviewClock.transitionAt(dup, 0) == dup.getTransitions().get(0)
                        && PreviewClock.blendMediaMs(dup, 0) == 300 && legacyWindow(dup, 0)[1] == 900);

        // (e) A clip with a loop extension before the one playing: the timeline puts the next clip later.
        Clip looped = clip(2000, 1f);
        looped.setLoopMode(1);
        looped.setLoopAfterMs(1000);
        Timeline loops = timeline(new Clip[]{looped, clip(2000, 1f)}, new long[0][]);
        boolean extended = loops.getClipStartMs(1) == 3000;
        check("the fixture really has a loop extension (the timeline starts the next clip at 3000)", extended);
        check("after a looped clip, the playhead and overlays use the timeline's own clip start",
                !extended || (PreviewClock.editorMs(loops, 1, 0) == 3000 && legacyAbs(loops, 1, 0) == 2000));
        check("after a looped clip, a seek to 3500 lands 500 ms into the next clip (was 1500, past it)",
                !extended || (PreviewClock.clipMediaMs(loops, 1, 3500) == 500 && legacyRel(loops, 1, 3500) == 1500));

        // (f) The playhead during a blend is the editor clock (no jump at the blend's end).
        Timeline three = timeline(new Clip[]{clip(4000, 2f), clip(2000, 1f), clip(2000, 1f)},
                new long[][]{{0, 600}, {1, 600}});
        check("during the second blend, B at media 1400 is editor 3400 (the old blend playhead said 1800)",
                PreviewClock.editorMs(three, 1, 1400) == 3400);

        System.out.println("PreviewClockTest: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }
}
