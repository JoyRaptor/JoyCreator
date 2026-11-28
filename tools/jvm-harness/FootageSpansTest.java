import com.fadcam.ui.faditor.model.Clip;
import com.fadcam.ui.faditor.model.FootageSpans;
import com.fadcam.ui.faditor.model.PreviewClock;
import com.fadcam.ui.faditor.model.TimeMap;
import com.fadcam.ui.faditor.model.Timeline;
import com.fadcam.ui.faditor.model.Transition;

import java.util.Random;

/**
 * FootageSpans (STU-7, owner plan 11-28 step 1): which frames of a filmed clip may carry its own
 * Motion Blur, for the preview and the export alike.
 *
 * <p>Pins: a project with a transition still blurs every clip body; no frame under a blend is in
 * a span and no span crosses a seam; the span is the export's main-item trim to the millisecond
 * (checked against a frozen copy of ExportManager.buildComposition's arithmetic over random
 * timelines); the span's clock is the timeline's own placement of the clip.</p>
 *
 * <p>Planted fault: {@code --old-project-rule} applies the pre-11-28 rule (no footage blur
 * anywhere in a project that has a transition) and must fail.</p>
 *
 * Usage:  bash tools/jvm-harness/run-footagespans.sh
 */
public class FootageSpansTest {
    static int pass = 0, fail = 0;
    static boolean oldProjectRule;

    static void check(boolean ok, String name) {
        if (ok) { pass++; } else { fail++; System.out.println("FAIL " + name); }
    }

    static void eq(long got, long want, String name) {
        check(got == want, name + "  (got " + got + ", want " + want + ")");
    }

    /** The spans as the routes would see them, with the planted old rule when asked. */
    static FootageSpans.Span body(Timeline tl, int i) {
        if (oldProjectRule && tl.hasTransitions()) return null;
        return FootageSpans.body(tl, i);
    }

    /** A filmed master clip, Motion Blur on, [inMs, outMs) of a 10-minute file. */
    static Clip clip(long inMs, long outMs, float speed) {
        Clip c = new Clip(null, 600_000);
        c.setInPointMs(inMs);
        c.setOutPointMs(outMs);
        c.setSpeedMultiplier(speed);
        c.setRigidMotionBlurEnabled(true);
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

    /** Frozen copy of ExportManager.buildComposition's main-item trim (2026-11-28). */
    static long[] exportMainItem(Timeline timeline, int ci) {
        Clip clip = timeline.getClip(ci);
        Transition trans = TimeMap.transitionAtSeam(timeline, ci);
        Transition prevTrans = ci > 0 ? TimeMap.transitionAtSeam(timeline, ci - 1) : null;
        long clipInMs = clip.getInPointMs();
        long clipOutMs = clip.getOutPointMs();
        if (prevTrans != null) {
            long headTransMs = TimeMap.effectiveTransitionMs(timeline, prevTrans, ci - 1);
            long overlapSourceMs = Math.round(headTransMs * clip.getSpeedMultiplier());
            clipInMs = Math.min(clipOutMs, clipInMs + overlapSourceMs);
        }
        long mainOutMs = clipOutMs;
        if (trans != null) {
            long tailTransMs = TimeMap.effectiveTransitionMs(timeline, trans, ci);
            long overlapSourceMs = Math.round(tailTransMs * clip.getSpeedMultiplier());
            mainOutMs = Math.max(clipInMs, clipOutMs - overlapSourceMs);
        }
        long endMs = Math.min(mainOutMs, clip.getSourceDurationMs()); // buildClipItem
        return new long[]{clipInMs, endMs};
    }

    public static void main(String[] args) {
        for (String a : args) if (a.equals("--old-project-rule")) oldProjectRule = true;

        // ── One 600 ms transition between the first two of three clips ─────────────────────
        Timeline t = timeline(new Clip[]{clip(0, 3000, 1f), clip(1000, 5000, 1f), clip(0, 3000, 1f)},
                new long[][]{{0, 600}});
        FootageSpans.Span a = body(t, 0), b = body(t, 1), c = body(t, 2);
        check(a != null, "transition: the outgoing clip still blurs its body");
        check(b != null, "transition: the incoming clip still blurs its body");
        check(c != null, "transition: a clip away from the transition blurs");
        if (a != null) {
            eq(a.sourceStartUs(), 0, "outgoing: source starts at its in-point");
            eq(a.sourceEndUs(), 2_400_000, "outgoing: source ends where the blend opens");
            eq(a.editorStartUs(), 0, "outgoing: editor start");
            eq(a.editorEndUs(), 2_400_000, "outgoing: editor end = seam - transition");
        }
        if (b != null) {
            eq(b.sourceStartUs(), 1_600_000, "incoming: source starts past the head under the blend");
            eq(b.sourceEndUs(), 5_000_000, "incoming: source ends at its out-point");
            eq(b.editorStartUs(), 3_600_000, "incoming: editor start = seam + transition");
            eq(b.editorEndUs(), 7_000_000, "incoming: editor end");
        }
        if (c != null) {
            eq(c.editorStartUs(), 7_000_000, "third clip: editor start");
            eq(c.editorEndUs(), 10_000_000, "third clip: editor end");
        }
        // No frame inside the overlap belongs to any span, on either clip.
        boolean overlapSharp = true;
        for (long ms = 2400; ms < 3600; ms += 5) {
            for (int i = 0; i < 3; i++) {
                FootageSpans.Span s = body(t, i);
                if (s != null && ms * 1000 >= s.editorStartUs() && ms * 1000 < s.editorEndUs()) overlapSharp = false;
            }
        }
        check(overlapSharp, "transition: every frame in the overlap stays sharp");
        // A piece reaching under the blend is not covered; the export's main item is.
        if (a != null) {
            check(!a.covers(0, 2_500_000), "a window reaching under the blend is not covered");
            check(a.covers(0, 2_400_000), "the export's main item is covered");
        }

        // ── Speed: the head and tail are taken in source time, the clock in editor time ───
        Timeline fast = timeline(new Clip[]{clip(0, 4000, 1f), clip(0, 8000, 2f)}, new long[][]{{0, 500}});
        FootageSpans.Span f = body(fast, 1);
        check(f != null, "2x incoming clip blurs");
        if (f != null) {
            eq(f.sourceStartUs(), 1_000_000, "2x: head is 500 editor ms = 1000 source ms");
            eq(f.editorStartUs(), 4_500_000, "2x: editor start = seam + 500");
            eq(f.editorUs(3_000_000), 4_000_000 + 1_500_000, "2x: source 3 s sits 1.5 s into the clip");
        }

        // ── Ineligible clips and empty bodies ────────────────────────────────────────────────
        Clip off = clip(0, 3000, 1f);
        off.setRigidMotionBlurEnabled(false);
        check(FootageSpans.body(timeline(new Clip[]{off}, new long[][]{}), 0) == null, "blur off: no span");
        Timeline eaten = timeline(new Clip[]{clip(0, 1000, 1f), clip(0, 1000, 1f), clip(0, 1000, 1f)},
                new long[][]{{0, 900}, {1, 900}});
        check(FootageSpans.body(eaten, 1) == null, "a clip both blends use up has no span");

        // ── The export trims the main item exactly here, on random timelines ────────────────
        Random rnd = new Random(28_11_2026L);
        int agreements = 0, clipsSeen = 0;
        float[] speeds = {0.25f, 0.5f, 1f, 1.5f, 2f, 3f};
        for (int round = 0; round < 400; round++) {
            int n = 2 + rnd.nextInt(4);
            Clip[] cs = new Clip[n];
            for (int i = 0; i < n; i++) {
                long in = rnd.nextInt(5000);
                cs[i] = clip(in, in + 200 + rnd.nextInt(6000), speeds[rnd.nextInt(speeds.length)]);
            }
            int k = rnd.nextInt(n);
            long[][] ts = new long[k][];
            for (int s = 0; s < k; s++) ts[s] = new long[]{rnd.nextInt(n - 1), 100 + rnd.nextInt(2000)};
            Timeline tl = timeline(cs, ts);
            TimeMap map = TimeMap.of(tl);
            for (int i = 0; i < n; i++) {
                clipsSeen++;
                long[] item = exportMainItem(tl, i);
                FootageSpans.Span s = body(tl, i);
                boolean ok;
                if (item[1] <= item[0]) {
                    ok = s == null;
                } else {
                    ok = s != null && s.sourceStartUs() == item[0] * 1000 && s.sourceEndUs() == item[1] * 1000
                            && s.covers(item[0] * 1000, item[1] * 1000)
                            // the clock is the timeline's own: PreviewClock at the span's in-point
                            && s.editorUs(cs[i].getInPointMs() * 1000L) == map.clipEditorStartMs(i) * 1000L
                            && Math.abs(s.editorUs(item[0] * 1000) / 1000
                                    - PreviewClock.editorMs(tl, i, item[0] - cs[i].getInPointMs())) <= 1
                            // never under a blend
                            && s.editorStartUs() >= (map.clipEditorStartMs(i) + map.headMs(i)) * 1000 - 1000
                            && s.editorEndUs() <= (map.clipEditorStartMs(i + 1) - map.seamMs(i)) * 1000 + 1000;
                }
                if (ok) agreements++;
                else if (agreements + 5 > clipsSeen) System.out.println("  mismatch round " + round + " clip " + i
                        + " item=" + item[0] + ".." + item[1] + " span=" + (s == null ? "none"
                        : s.sourceStartUs() / 1000 + ".." + s.sourceEndUs() / 1000));
            }
        }
        eq(agreements, clipsSeen, "random timelines: span == export main item, on the timeline's clock");

        System.out.println("FootageSpansTest: " + pass + " passed, " + fail + " failed");
        if (fail > 0) throw new AssertionError("footage spans: " + fail + " failed");
    }
}
