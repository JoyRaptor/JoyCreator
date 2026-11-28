import com.fadcam.ui.faditor.keyframe.Easing;
import com.fadcam.ui.faditor.keyframe.KeyframeSet;
import com.fadcam.ui.faditor.model.Clip;
import com.fadcam.ui.faditor.model.FootageSpans;
import com.fadcam.ui.faditor.model.PingPongLeg;
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

    static boolean oldClipRules;

    /** The spans as the routes would see them, with the planted old rule when asked. */
    static FootageSpans.Span body(Timeline tl, int i) {
        if (oldProjectRule && tl.hasTransitions()) return null;
        return FootageSpans.body(tl, i);
    }

    /** The clip gate, or (planted) the pre-11-28 one: any crop or armed transform stayed sharp. */
    static boolean eligible(Clip c) {
        if (oldClipRules && (c.effectiveCropFractions() != null
                || (c.isSpineTransformArmed() && c.hasSpineTransform()))) return false;
        return FootageSpans.eligible(c);
    }

    static boolean oldLoopRule;

    /** The spans of a looped clip, or (planted) none: the pre-11-28 rule kept every loop sharp. */
    static FootageSpans.Span loopBody(Timeline tl, int i) {
        return oldLoopRule && tl.getClip(i).hasLoopExtension() ? null : FootageSpans.body(tl, i);
    }

    static FootageSpans.Span loopPass(Timeline tl, int i, boolean before, int rep) {
        return oldLoopRule ? null : FootageSpans.loopPass(tl, i, before, rep);
    }

    static Clip loop(Clip c, int mode, long beforeMs, long afterMs) {
        c.setLoopMode(mode);
        c.setLoopBeforeMs(beforeMs);
        c.setLoopAfterMs(afterMs);
        return c;
    }

    static Clip withSpineKeys(float... xs) {
        Clip c = clip(0, 3000, 1f);
        var track = c.getOrCreateSpineTransform().getOrCreate(KeyframeSet.X);
        for (int k = 0; k < xs.length; k++) track.put(k * 1000L, xs[k], Easing.LINEAR);
        return c;
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
        for (String a : args) {
            if (a.equals("--old-project-rule")) oldProjectRule = true;
            if (a.equals("--old-clip-rules")) oldClipRules = true;
            if (a.equals("--old-loop-rule")) oldLoopRule = true;
        }

        // ── Step 2: crop and an armed placement (owner plan 11-28) ─────────────────────────
        Clip cropped = clip(0, 3000, 1f);
        cropped.setCropPreset("9:16");
        check(cropped.effectiveCropFractions() != null, "fixture: the 9:16 preset crops");
        check(eligible(cropped), "crop: a cropped clip blurs (gather on the source, crop after)");
        Clip custom = clip(0, 3000, 1f);
        custom.setCustomCropBounds(0.1f, 0.2f, 0.7f, 0.9f);
        check(eligible(custom), "crop: a custom crop blurs");
        Clip held = withSpineKeys(0.3f);
        check(held.isSpineTransformArmed() && held.hasSpineTransform(), "fixture: one key arms the placement");
        check(eligible(held), "armed placement that holds still: the footage blurs");
        Clip moving = withSpineKeys(0.3f, 0.7f);
        check(!eligible(moving), "moving placement: its own shutter, the footage stays sharp");
        check(FootageSpans.placementMoves(moving) && !FootageSpans.placementMoves(held),
                "placementMoves: two keys move, one key holds");
        Clip unarmed = clip(0, 3000, 1f);
        check(eligible(unarmed) && !FootageSpans.placementMoves(unarmed), "no placement: blurs");

        // ── Step 3: NORMAL loops blur inside each pass, sharp across each loop seam ────────
        Clip looped = loop(clip(1000, 3000, 1f), Clip.LOOP_MODE_NORMAL, 2500, 1000);
        Timeline lt = timeline(new Clip[]{clip(0, 2000, 1f), looped, clip(0, 2000, 1f)}, new long[][]{});
        check(eligible(looped), "loop: a NORMAL loop blurs");
        FootageSpans.Span lm = loopBody(lt, 1);
        check(lm != null, "loop: the clip's own pass has a span");
        if (lm != null) {
            eq(lm.editorStartUs(), 4_500_000, "loop: own pass starts after the 2.5 s loop-before");
            eq(lm.editorEndUs(), 6_500_000, "loop: own pass ends 2 s later");
        }
        FootageSpans.Span b0 = loopPass(lt, 1, true, 0), b1 = loopPass(lt, 1, true, 1),
                a0 = loopPass(lt, 1, false, 0);
        check(b0 != null && b1 != null && a0 != null, "loop: every repeat has a span");
        check(loopPass(lt, 1, true, 2) == null && loopPass(lt, 1, false, 1) == null, "loop: no extra repeats");
        if (b0 != null && b1 != null && a0 != null && lm != null) {
            eq(b0.editorStartUs(), 2_000_000, "loop-before 0 starts at the clip");
            eq(b0.sourceEndUs(), 3_000_000, "loop-before 0 plays the whole pass");
            eq(b1.editorStartUs(), 4_000_000, "loop-before 1 starts one pass later");
            eq(b1.sourceEndUs(), 1_500_000, "loop-before 1 is cut short to 500 ms");
            eq(a0.editorStartUs(), 6_500_000, "loop-after 0 starts after the own pass");
            eq(a0.editorEndUs(), 7_500_000, "loop-after 0 lasts 1 s");
            FootageSpans.Span[] order = {b0, b1, lm, a0};
            boolean seamsFenced = true;
            for (int k = 1; k < order.length; k++) {
                // Each pass ends at or before the next one starts: the jump back to the in-point
                // is a fence both sides of, never inside one span.
                if (order[k - 1].editorEndUs() > order[k].editorStartUs()) seamsFenced = false;
                if (order[k].sourceStartUs() != 1_000_000) seamsFenced = false;
            }
            check(seamsFenced, "loop: every loop seam is a fence (passes never overlap)");
            check(lm.equals(FootageSpans.passStartingAt(lt, 1, 4500)), "legacy: offset 0 is the own pass");
            FootageSpans.Span after = FootageSpans.passStartingAt(lt, 1, 6500);
            check(after != null && after.pass() == FootageSpans.Pass.AFTER && after.rep() == 0,
                    "legacy: one wrap is loop-after 0");
            check(FootageSpans.passStartingAt(lt, 1, 5000) == null, "legacy: no pass starts mid-pass");
        }
        check(!eligible(loop(clip(0, 2000, 1f), Clip.LOOP_MODE_PING_PONG, 0, 1000)),
                "ping-pong: backward legs come from another file, stays sharp");
        check(!eligible(loop(clip(0, 2000, 1f), Clip.LOOP_MODE_STILL, 0, 1000)), "still loop: stays sharp");
        Timeline lx = timeline(new Clip[]{loop(clip(0, 2000, 1f), Clip.LOOP_MODE_NORMAL, 0, 1000),
                clip(0, 2000, 1f)}, new long[][]{{0, 300}});
        check(loopBody(lx, 0) == null, "a loop touching a transition stays sharp");
        check(loopBody(lx, 1) != null, "...but its neighbour across the transition blurs");

        // The export lays each repeat exactly here (frozen copy of buildLoopExtensionItem).
        Random lr = new Random(11_28L);
        int loopOk = 0, loopSeen = 0;
        for (int round = 0; round < 300; round++) {
            long in = lr.nextInt(3000);
            float sp = new float[]{0.5f, 1f, 2f}[lr.nextInt(3)];
            Clip lc = loop(clip(in, in + 300 + lr.nextInt(3000), sp), Clip.LOOP_MODE_NORMAL,
                    lr.nextInt(5000), lr.nextInt(5000));
            if (!lc.hasLoopExtension()) continue;
            Timeline tl = timeline(new Clip[]{clip(0, 1000 + lr.nextInt(2000), 1f), lc}, new long[][]{});
            long passMs = lc.getTrimmedDurationMs();
            long cursor = TimeMap.of(tl).clipEditorStartMs(1);
            for (int side = 0; side < 2; side++) {
                boolean before = side == 0;
                long ext = before ? lc.getLoopBeforeMs() : lc.getLoopAfterMs();
                if (ext <= 0) continue;
                int reps = (int) Math.ceil(ext / (double) passMs);
                for (int r = 0; r < reps; r++) {
                    loopSeen++;
                    long played = Math.min(passMs, ext - (long) r * passMs);
                    long span = Math.max(1L, (long) (played * sp));
                    PingPongLeg leg = PingPongLeg.of(before, reps, r, lc.getInPointMs(), lc.getOutPointMs(), span, false);
                    long editorStart = before ? cursor + Math.min((long) r * passMs, lc.getLoopBeforeMs())
                            : cursor + lc.getLoopBeforeMs() + passMs + (long) r * passMs;
                    FootageSpans.Span s = loopPass(tl, 1, before, r);
                    if (s != null && s.covers(leg.startMs * 1000, leg.endMs * 1000)
                            && s.editorUs(leg.startMs * 1000) == editorStart * 1000) loopOk++;
                }
            }
        }
        eq(loopOk, loopSeen, "random loops: span == export repeat, on the timeline's clock");

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
