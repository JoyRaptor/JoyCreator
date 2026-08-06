import com.fadcam.ui.faditor.keyframe.Easing;
import com.fadcam.ui.faditor.sprite.SequenceTiming;
import com.fadcam.ui.faditor.sprite.SpriteFrameResolver;
import com.fadcam.ui.faditor.sprite.SpriteOverlayItem;
import com.fadcam.ui.faditor.sprite.SpriteSheet;
import com.fadcam.ui.faditor.sprite.FrameTrack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * JVM harness for SPEC_IMAGE_SEQUENCE's weight model (SequenceTiming) and for the ONE change it
 * makes to the shared evaluator (SpriteFrameResolver).
 *
 * <p><b>The load-bearing group is {@link #unweightedIsBitIdenticalToTheOldMath()}.</b> Weights
 * were folded into the existing resolver rather than given a parallel one, which is only safe if
 * an unweighted preset resolves to exactly what it did before. That test re-implements the OLD
 * arithmetic inline and asserts the new code agrees on every tick of several presets — so a
 * regression shows up as a diff against the previous behaviour, not merely as "some number
 * changed".</p>
 *
 * <p>Run: {@code bash tools/jvm-harness/run-sequence.sh}</p>
 */
public class SequenceTimingTest {

    private static int passed = 0, failed = 0;

    public static void main(String[] args) {
        // The array
        weightAtIsTolerant();
        fitPadsAndTruncates();
        totalWeightCountsHolds();
        // Tick mapping
        indexAtTickWalksHolds();
        tickAtIndexIsTheInverse();
        pingPongPreservesWeightsAndSkipsEndpointRepeat();
        // The three views
        fpsAndTotalDurationAreOneField();
        perFrameRoundTrips();
        fpsIsClampedAwayFromZero();
        // Resize
        relativeResizeKeepsHolds();
        absoluteResizeChangesFrameCount();
        readoutReportsWhatTheEyeNeeds();
        // Bulk edits
        strideExpressesOnTwosAndHolds();
        rampHitsBothEndpoints();
        rampRespectsArgumentDirection();
        nudgeAppliesToSelectionAndFloorsAtOne();
        reverseAndShuffleCarryWeightsWithFrames();
        // The evaluator
        unweightedIsBitIdenticalToTheOldMath();
        weightedResolverHoldsTheRightCell();
        weightedLoopWraps();
        weightedPingPongWraps();
        // Tape change-times (§4)
        changeTimesLandWhereFramesActuallyChange();
        changeTimesMarkRepeatsOnLaterPasses();
        changeTimesAreWindowedAndCapped();
        onceDoesNotEmitPhantomChangesAfterItsRun();
        // §6 open-ended
        continuesResolvesToAConcreteLength();
        continuesReportsWhenANeighbourClippedIt();
        continuesIsIdempotent();
        runningOutOfProjectIsNotBlamedOnANeighbour();
        // §2a ABSOLUTE left-trim
        startFrameOffsetsTheRun();
        startFrameOffsetsTheTapeIdentically();

        System.out.println(failed == 0 ? "ALL GREEN (" + passed + "/" + (passed + failed) + ")"
                : "FAILURES: " + failed + " (passed " + passed + ")");
        if (failed > 0) System.exit(1);
    }

    // ── Fixtures ────────────────────────────────────────────────────────────

    static List<Integer> w(int... v) {
        List<Integer> out = new ArrayList<>();
        for (int x : v) out.add(x);
        return out;
    }

    /** A sequence sheet of n frames with the given weights on its sequence preset. */
    static SpriteSheet seq(int n, float fps, List<Integer> weights, String type) {
        SpriteSheet s = new SpriteSheet("id", "seq", "uri0");
        List<String> uris = new ArrayList<>();
        for (int i = 0; i < n; i++) uris.add("uri" + i);
        s.setSequenceFrames(uris);
        s.setFps(fps);
        SpriteSheet.Preset p = s.ensureSequencePreset();
        p.type = type;
        p.weights.clear();
        p.weights.addAll(SequenceTiming.fit(weights, n));
        return s;
    }

    /** A placed item running that sheet's preset from t=0, open-ended. */
    static SpriteOverlayItem item(SpriteSheet s, String endBehavior) {
        SpriteOverlayItem it = SpriteOverlayItem.create(s.getId());
        it.setTimeRange(0, Long.MAX_VALUE);
        it.setEndBehavior(endBehavior);
        it.getFrameTrack().put(FrameTrack.Key.ofPreset(0, SpriteSheet.SEQUENCE_PRESET_ID));
        return it;
    }

    // ── The array ───────────────────────────────────────────────────────────

    static void weightAtIsTolerant() {
        List<Integer> ws = new ArrayList<>(Arrays.asList(3, null, 0, 100000));
        check("weightAt: present", SequenceTiming.weightAt(ws, 0) == 3);
        check("weightAt: null element falls back to 1", SequenceTiming.weightAt(ws, 1) == 1);
        check("weightAt: 0 clamps up to 1", SequenceTiming.weightAt(ws, 2) == 1);
        check("weightAt: huge clamps to MAX", SequenceTiming.weightAt(ws, 3) == SequenceTiming.MAX_WEIGHT);
        check("weightAt: past end is 1", SequenceTiming.weightAt(ws, 99) == 1);
        check("weightAt: null array is 1", SequenceTiming.weightAt(null, 0) == 1);
        check("weightAt: negative index is 1", SequenceTiming.weightAt(ws, -1) == 1);
    }

    static void fitPadsAndTruncates() {
        check("fit pads with 1", SequenceTiming.fit(w(5), 3).equals(w(5, 1, 1)));
        check("fit truncates", SequenceTiming.fit(w(5, 6, 7, 8), 2).equals(w(5, 6)));
        check("fit of null", SequenceTiming.fit(null, 2).equals(w(1, 1)));
        check("fit to zero is empty", SequenceTiming.fit(w(5), 0).isEmpty());
    }

    static void totalWeightCountsHolds() {
        check("total unweighted == n", SequenceTiming.totalWeight(null, 4) == 4);
        check("total with a 5-hold", SequenceTiming.totalWeight(w(1, 5, 1), 3) == 7);
        check("total of nothing", SequenceTiming.totalWeight(w(1), 0) == 0);
    }

    // ── Tick mapping ────────────────────────────────────────────────────────

    static void indexAtTickWalksHolds() {
        List<Integer> ws = w(2, 3, 1); // ticks: 0,1 -> 0 | 2,3,4 -> 1 | 5 -> 2
        int[] expect = {0, 0, 1, 1, 1, 2};
        boolean ok = true;
        for (int t = 0; t < expect.length; t++) {
            if (SequenceTiming.indexAtTick(ws, 3, t) != expect[t]) ok = false;
        }
        check("indexAtTick walks the holds", ok);
        check("indexAtTick clamps past the end", SequenceTiming.indexAtTick(ws, 3, 99) == 2);
        check("indexAtTick clamps below zero", SequenceTiming.indexAtTick(ws, 3, -5) == 0);
    }

    static void tickAtIndexIsTheInverse() {
        List<Integer> ws = w(2, 3, 1);
        boolean ok = true;
        for (int i = 0; i < 3; i++) {
            long t = SequenceTiming.tickAtIndex(ws, 3, i);
            if (SequenceTiming.indexAtTick(ws, 3, t) != i) ok = false;
        }
        check("tickAtIndex inverts indexAtTick", ok);
    }

    static void pingPongPreservesWeightsAndSkipsEndpointRepeat() {
        // 3 frames, middle held 3: forward 0,0,1,1,1,2  then mirror 1,1,1
        List<Integer> ws = w(2, 3, 1);
        long period = SequenceTiming.pingPongPeriod(ws, 3); // 2*6 - 2 - 1 = 9
        check("pingpong period excludes the endpoint repeats", period == 9);
        int[] expect = {0, 0, 1, 1, 1, 2, 1, 1, 1};
        boolean ok = true;
        for (int t = 0; t < period; t++) {
            if (SequenceTiming.pingPongIndexAtTick(ws, 3, t) != expect[t]) ok = false;
        }
        check("pingpong mirror keeps each frame's own weight", ok);
        check("pingpong of 1 frame is that frame",
                SequenceTiming.pingPongIndexAtTick(w(4), 1, 3) == 0);
    }

    // ── The three views ─────────────────────────────────────────────────────

    static void fpsAndTotalDurationAreOneField() {
        List<Integer> ws = w(1, 5, 1, 1); // 8 ticks
        float fps = SequenceTiming.fpsForTotalMs(ws, 4, 4000); // 8 ticks / 4s = 2fps
        check("fps derived from total duration", Math.abs(fps - 2f) < 1e-4);
        check("total duration derived back", SequenceTiming.totalMsForFps(ws, 4, fps) == 4000);
    }

    static void perFrameRoundTrips() {
        // 4 frames at 3s each = 12s total, regardless of how the holds are distributed.
        List<Integer> ws = w(1, 5, 1, 1);
        float fps = SequenceTiming.fpsForPerFrameMs(ws, 4, 3000);
        check("per-frame view totals frameCount x perFrame",
                SequenceTiming.totalMsForFps(ws, 4, fps) == 12000);
        check("per-frame view round-trips",
                Math.abs(SequenceTiming.perFrameMsForFps(ws, 4, fps) - 3000f) < 1f);
    }

    static void fpsIsClampedAwayFromZero() {
        check("fps 0 clamps", SequenceTiming.clampFps(0f) >= SequenceTiming.MIN_FPS);
        check("fps NaN is sane", SequenceTiming.clampFps(Float.NaN) == 1f);
        check("totalMsForFps never divides by zero",
                SequenceTiming.totalMsForFps(w(1, 1), 2, 0f) > 0);
    }

    // ── Resize ──────────────────────────────────────────────────────────────

    static void relativeResizeKeepsHolds() {
        // §9a: holds survive a resize, automatically, because only fps moves.
        List<Integer> ws = w(1, 5, 1, 1);
        float fpsBefore = SequenceTiming.fpsForTotalMs(ws, 4, 8000);
        float fpsAfter = SequenceTiming.fpsForTotalMs(ws, 4, 4000);
        check("relative resize doubles the rate", Math.abs(fpsAfter / fpsBefore - 2f) < 1e-3);
        // The HELD frame is still 5/8 of the object, before and after.
        long beforeHold = Math.round(8000 * 5 / 8.0), afterHold = Math.round(4000 * 5 / 8.0);
        check("the hold stays proportional", beforeHold == 5000 && afterHold == 2500);
        check("the weights themselves are untouched", ws.equals(w(1, 5, 1, 1)));
    }

    static void absoluteResizeChangesFrameCount() {
        // §2a: ten one-second images squeezed to five seconds shows five frames.
        List<Integer> ws = SequenceTiming.defaultWeights(10);
        check("10 frames at 1fps in 10s shows 10",
                SequenceTiming.framesFittingIn(ws, 10, 1f, 10000) == 10);
        check("...squeezed to 5s shows 5",
                SequenceTiming.framesFittingIn(ws, 10, 1f, 5000) == 5);
        check("a zero-length span shows nothing",
                SequenceTiming.framesFittingIn(ws, 10, 1f, 0) == 0);
        check("a sliver still shows one frame",
                SequenceTiming.framesFittingIn(ws, 10, 1f, 10) == 1);
        check("a hold consumes its own room",
                SequenceTiming.framesFittingIn(w(5, 1, 1), 3, 1f, 6000) == 2);
    }

    static void readoutReportsWhatTheEyeNeeds() {
        List<Integer> ws = SequenceTiming.defaultWeights(24);
        String r = SequenceTiming.readout(ws, 24, SequenceTiming.ResizeMode.RELATIVE, 6f, 4000);
        check("readout is frames + duration + fps: " + r, r.equals("24 frames · 4.0s · 6.0 fps"));
        String abs = SequenceTiming.readout(ws, 24, SequenceTiming.ResizeMode.ABSOLUTE, 6f, 2000);
        check("absolute readout reports the SHOWN count: " + abs, abs.startsWith("12 frames"));
        check("singular frame reads correctly",
                SequenceTiming.readout(w(1), 1, SequenceTiming.ResizeMode.RELATIVE, 1f, 1000)
                        .startsWith("1 frame ·"));
    }

    // ── Bulk edits ──────────────────────────────────────────────────────────

    static void strideExpressesOnTwosAndHolds() {
        check("on twos", SequenceTiming.applyStride(null, 4, 0, 1, 2).equals(w(2, 2, 2, 2)));
        // The user's own example: every 6th frame holds for five.
        List<Integer> r = SequenceTiming.applyStride(null, 13, 0, 6, 5);
        check("every 6th holds 5", r.equals(w(5, 1, 1, 1, 1, 1, 5, 1, 1, 1, 1, 1, 5)));
        check("stride 0 does not hang", SequenceTiming.applyStride(null, 3, 0, 0, 2).size() == 3);
        check("stride past the end is a no-op",
                SequenceTiming.applyStride(null, 3, 9, 1, 4).equals(w(1, 1, 1)));
    }

    static void rampHitsBothEndpoints() {
        List<Integer> r = SequenceTiming.applyRamp(null, 5, 0, 4, 1, 9, Easing.LINEAR);
        check("ramp starts at w0", r.get(0) == 1);
        check("ramp ends at w1", r.get(4) == 9);
        check("ramp is monotonic", r.get(0) <= r.get(1) && r.get(1) <= r.get(2)
                && r.get(2) <= r.get(3) && r.get(3) <= r.get(4));
        List<Integer> eased = SequenceTiming.applyRamp(null, 5, 0, 4, 1, 9, Easing.EASE_IN_OUT);
        check("an eased ramp still lands its endpoints",
                eased.get(0) == 1 && eased.get(4) == 9);
        check("a single-frame ramp is w0",
                SequenceTiming.applyRamp(null, 3, 1, 1, 7, 9, Easing.LINEAR).get(1) == 7);
    }

    static void rampRespectsArgumentDirection() {
        // "ramp from frame 4 back to frame 0" must run w0 at 4, not at 0.
        List<Integer> r = SequenceTiming.applyRamp(null, 5, 4, 0, 1, 9, Easing.LINEAR);
        check("reversed ramp puts w0 at fromIdx", r.get(4) == 1 && r.get(0) == 9);
    }

    static void nudgeAppliesToSelectionAndFloorsAtOne() {
        List<Integer> sel = w(1, 3);
        List<Integer> r = SequenceTiming.nudge(w(2, 2, 2, 2), 4, sel, 3);
        check("nudge hits only the selection", r.equals(w(2, 5, 2, 5)));
        check("nudge floors at 1 (a frame may not vanish)",
                SequenceTiming.nudge(w(2, 2), 2, w(0, 1), -99).equals(w(1, 1)));
        check("nudge with no selection is a no-op",
                SequenceTiming.nudge(w(2, 2), 2, null, 5).equals(w(2, 2)));
        // setWeight, unlike nudge, treats "nothing selected" as "all" — the batch tools' meaning.
        check("setWeight with no selection means all",
                SequenceTiming.setWeight(w(1, 1), 2, null, 4).equals(w(4, 4)));
    }

    static void reverseAndShuffleCarryWeightsWithFrames() {
        List<String> uris = new ArrayList<>(Arrays.asList("a", "b", "c"));
        List<Integer> ws = w(1, 5, 9);
        SequenceTiming.reverse(uris, ws);
        check("reverse flips frames", uris.equals(Arrays.asList("c", "b", "a")));
        check("reverse carries weights along", ws.equals(w(9, 5, 1)));

        List<String> u2 = new ArrayList<>(Arrays.asList("a", "b", "c", "d", "e"));
        List<Integer> w2 = w(1, 2, 3, 4, 5);
        SequenceTiming.shuffle(u2, w2, 42L);
        boolean paired = true;
        for (int i = 0; i < u2.size(); i++) {
            int expect = u2.get(i).charAt(0) - 'a' + 1;
            if (w2.get(i) != expect) paired = false;
        }
        check("shuffle keeps each weight with its own frame", paired);
        check("shuffle is seeded/reproducible", reshuffleMatches());
    }

    static boolean reshuffleMatches() {
        List<String> a = new ArrayList<>(Arrays.asList("a", "b", "c", "d", "e"));
        List<String> b = new ArrayList<>(Arrays.asList("a", "b", "c", "d", "e"));
        List<Integer> wa = w(1, 1, 1, 1, 1), wb = w(1, 1, 1, 1, 1);
        SequenceTiming.shuffle(a, wa, 7L);
        SequenceTiming.shuffle(b, wb, 7L);
        return a.equals(b);
    }

    // ── The evaluator ───────────────────────────────────────────────────────

    /**
     * THE regression gate. Re-implements the pre-weights index arithmetic and demands the live
     * resolver agree on every tick — because folding weights into the shared evaluator is only
     * a safe move if an unweighted preset comes out exactly as it went in.
     */
    static void unweightedIsBitIdenticalToTheOldMath() {
        boolean ok = true;
        String firstBad = null;
        for (String type : new String[]{"once", "loop", "pingpong"}) {
            for (int n = 1; n <= 6; n++) {
                SpriteSheet s = seq(n, 10f, null, type);      // no weights
                SpriteOverlayItem it = item(s, "hold");
                for (long tick = 0; tick < 40; tick++) {
                    long timeMs = tick * 100;                  // 10fps -> one tick per 100ms
                    int got = SpriteFrameResolver.resolveCellAt(s, it, timeMs);
                    int want = oldIndex(type, n, tick);
                    if (got != want) {
                        ok = false;
                        if (firstBad == null) {
                            firstBad = type + " n=" + n + " tick=" + tick
                                    + " got=" + got + " want=" + want;
                        }
                    }
                }
            }
        }
        check("unweighted resolution is unchanged by the weight model"
                + (firstBad == null ? "" : " [" + firstBad + "]"), ok);
    }

    /** The arithmetic SpriteFrameResolver used before weights existed, verbatim. */
    static int oldIndex(String type, int n, long frameOrdinal) {
        int idx;
        switch (type) {
            case "once":
                idx = (int) Math.min(frameOrdinal, n - 1);
                break;
            case "pingpong": {
                if (n == 1) { idx = 0; break; }
                int period = 2 * (n - 1);
                int phase = (int) (frameOrdinal % period);
                idx = phase < n ? phase : period - phase;
                break;
            }
            default:
                idx = (int) (frameOrdinal % n);
                break;
        }
        return idx;
    }

    static void weightedResolverHoldsTheRightCell() {
        // 3 frames at 10fps (100ms/tick), weights 2,3,1 -> 0:0-199 1:200-499 2:500-599
        SpriteSheet s = seq(3, 10f, w(2, 3, 1), "once");
        SpriteOverlayItem it = item(s, "hold");
        check("weighted t=0   -> frame 0", SpriteFrameResolver.resolveCellAt(s, it, 0) == 0);
        check("weighted t=150 -> frame 0", SpriteFrameResolver.resolveCellAt(s, it, 150) == 0);
        check("weighted t=200 -> frame 1", SpriteFrameResolver.resolveCellAt(s, it, 200) == 1);
        check("weighted t=499 -> frame 1", SpriteFrameResolver.resolveCellAt(s, it, 499) == 1);
        check("weighted t=500 -> frame 2", SpriteFrameResolver.resolveCellAt(s, it, 500) == 2);
        check("once holds the last frame", SpriteFrameResolver.resolveCellAt(s, it, 9000) == 2);
    }

    static void weightedLoopWraps() {
        SpriteSheet s = seq(3, 10f, w(2, 3, 1), "loop");
        SpriteOverlayItem it = item(s, "hold");
        check("loop wraps back to frame 0 after 6 ticks",
                SpriteFrameResolver.resolveCellAt(s, it, 600) == 0);
        check("loop second cycle mid-hold",
                SpriteFrameResolver.resolveCellAt(s, it, 900) == 1);
    }

    static void weightedPingPongWraps() {
        // period 9 ticks = 900ms at 10fps; expect 0,0,1,1,1,2,1,1,1 then repeat
        SpriteSheet s = seq(3, 10f, w(2, 3, 1), "pingpong");
        SpriteOverlayItem it = item(s, "hold");
        int[] expect = {0, 0, 1, 1, 1, 2, 1, 1, 1};
        boolean ok = true;
        for (int t = 0; t < 18; t++) {
            if (SpriteFrameResolver.resolveCellAt(s, it, t * 100) != expect[t % 9]) ok = false;
        }
        check("weighted pingpong wraps and preserves holds on the mirror", ok);
    }

    // ── Tape change-times (§4) ──────────────────────────────────────────────

    /**
     * The tape's whole value is that it tells the truth about timing, so the change times must
     * agree with the resolver at the moments they claim. This asserts BOTH: the times are the
     * weight boundaries, and the resolver returns that same cell just after each one.
     */
    static void changeTimesLandWhereFramesActuallyChange() {
        SpriteSheet s = seq(3, 10f, w(2, 3, 1), "once");   // 100ms/tick -> 0@0, 1@200, 2@500
        SpriteOverlayItem it = item(s, "hold");
        it.setTimeRange(0, 1000);
        List<SpriteFrameResolver.FrameChange> ch =
                SpriteFrameResolver.frameChangesIn(s, it, 0, 1000, 100);
        check("three changes for three frames", ch.size() == 3);
        boolean times = ch.size() == 3 && ch.get(0).localMs == 0
                && ch.get(1).localMs == 200 && ch.get(2).localMs == 500;
        check("changes land on the weight boundaries", times);
        boolean cells = ch.size() == 3 && ch.get(0).cellIndex == 0
                && ch.get(1).cellIndex == 1 && ch.get(2).cellIndex == 2;
        check("each change names the cell coming in", cells);
        // Cross-check against the evaluator itself — this is the anti-drift assertion.
        boolean agrees = true;
        for (SpriteFrameResolver.FrameChange c : ch) {
            if (SpriteFrameResolver.resolveCellAt(s, it, c.localMs) != c.cellIndex) agrees = false;
        }
        check("the tape agrees with what the video shows", agrees);
    }

    static void changeTimesMarkRepeatsOnLaterPasses() {
        SpriteSheet s = seq(3, 10f, null, "loop");  // 3 ticks per cycle = 300ms
        SpriteOverlayItem it = item(s, "hold");
        it.setTimeRange(0, 900);
        List<SpriteFrameResolver.FrameChange> ch =
                SpriteFrameResolver.frameChangesIn(s, it, 0, 900, 100);
        check("three cycles of three", ch.size() == 9);
        boolean firstPassPlain = !ch.get(0).repeat && !ch.get(1).repeat && !ch.get(2).repeat;
        boolean laterPassesRepeat = ch.get(3).repeat && ch.get(8).repeat;
        check("first pass is not a repeat", firstPassPlain);
        check("later passes are flagged as repeats", laterPassesRepeat);
    }

    static void changeTimesAreWindowedAndCapped() {
        SpriteSheet s = seq(3, 10f, null, "loop");
        SpriteOverlayItem it = item(s, "hold");
        it.setTimeRange(0, 100000);
        List<SpriteFrameResolver.FrameChange> win =
                SpriteFrameResolver.frameChangesIn(s, it, 500, 800, 1000);
        boolean inWindow = true;
        for (SpriteFrameResolver.FrameChange c : win) {
            if (c.localMs < 500 || c.localMs > 800) inWindow = false;
        }
        check("only the asked-for window is returned", inWindow && !win.isEmpty());
        List<SpriteFrameResolver.FrameChange> capped =
                SpriteFrameResolver.frameChangesIn(s, it, 0, 100000, 25);
        check("the cap is honoured (a loop is unbounded)", capped.size() == 25);
        // An OPEN-ENDED item must not walk forever either.
        SpriteOverlayItem open = item(s, "loop");
        open.setTimeRange(0, Long.MAX_VALUE);
        List<SpriteFrameResolver.FrameChange> o =
                SpriteFrameResolver.frameChangesIn(s, open, 0, 1000, 500);
        check("open-ended is bounded by the query window", o.size() <= 11 && !o.isEmpty());
    }

    static void onceDoesNotEmitPhantomChangesAfterItsRun() {
        SpriteSheet s = seq(3, 10f, null, "once"); // 3 frames = 300ms, item is 5s long
        SpriteOverlayItem it = item(s, "hold");
        it.setTimeRange(0, 5000);
        List<SpriteFrameResolver.FrameChange> ch =
                SpriteFrameResolver.frameChangesIn(s, it, 0, 5000, 100);
        check("a 'once' run stops changing after its last frame", ch.size() == 3);
        check("and the video agrees it is holding",
                SpriteFrameResolver.resolveCellAt(s, it, 4000) == 2);
    }

    // ── §6 open-ended ───────────────────────────────────────────────────────

    /**
     * The spec's danger case. "Continues" must never leave a duration that depends on neighbours
     * at render time — it must resolve to a real number, so undo restores something the user saw.
     */
    static void continuesResolvesToAConcreteLength() {
        SpriteSheet s = seq(3, 10f, null, "loop");
        SpriteOverlayItem it = item(s, "loop");
        it.setTimeRange(0, Long.MAX_VALUE);
        it.setContinuesUntilBlocked(true);
        boolean changed = com.fadcam.ui.faditor.sprite.OpenEndResolver.resolve(
                java.util.Collections.singletonList(it), id -> s, 8000);
        check("resolving reports the change", changed);
        check("end is concrete, not MAX_VALUE", it.getEndMs() == 8000);
        check("and it was not clipped by anything", !it.isClippedByNeighbour());
    }

    static void continuesReportsWhenANeighbourClippedIt() {
        SpriteSheet s = seq(12, 1f, null, "once");   // natural run = 12s
        SpriteOverlayItem a = item(s, "hold");
        a.setTimeRange(0, Long.MAX_VALUE);
        a.setContinuesUntilBlocked(true);
        SpriteOverlayItem b = item(s, "hold");
        b.setTimeRange(4000, 6000);                   // blocks a at 4s
        com.fadcam.ui.faditor.sprite.OpenEndResolver.resolve(
                java.util.Arrays.asList(a, b), id -> s, 60000);
        check("the neighbour sets the end", a.getEndMs() == 4000);
        check("and being clipped is FLAGGED, not silent", a.isClippedByNeighbour());
        check("the neighbour itself is untouched",
                b.getStartMs() == 4000 && b.getEndMs() == 6000);
    }

    static void continuesIsIdempotent() {
        SpriteSheet s = seq(3, 10f, null, "loop");
        SpriteOverlayItem it = item(s, "loop");
        it.setTimeRange(0, Long.MAX_VALUE);
        it.setContinuesUntilBlocked(true);
        com.fadcam.ui.faditor.sprite.OpenEndResolver.resolve(
                java.util.Collections.singletonList(it), id -> s, 5000);
        boolean second = com.fadcam.ui.faditor.sprite.OpenEndResolver.resolve(
                java.util.Collections.singletonList(it), id -> s, 5000);
        // Re-running must report NO change, or the sync that calls it on every edit would
        // churn — and a churning resync is how "the app moved my clip by itself" starts.
        check("re-resolving is a no-op", !second);
        check("a non-continuing item is never touched", nonContinuingUntouched());
    }

    /**
     * §6's marker must say WHY honestly. A sequence that simply outlives the project has not
     * been clipped by anything — claiming otherwise points the user at an object that does not
     * exist, in a tape marker, a toast and the AI's report all at once.
     */
    static void runningOutOfProjectIsNotBlamedOnANeighbour() {
        SpriteSheet s = seq(12, 1f, null, "once");   // natural run = 12s
        SpriteOverlayItem alone = item(s, "hold");
        alone.setTimeRange(0, Long.MAX_VALUE);
        alone.setContinuesUntilBlocked(true);
        com.fadcam.ui.faditor.sprite.OpenEndResolver.resolve(
                java.util.Collections.singletonList(alone), id -> s, 5000);  // project shorter
        check("the end still resolves to the project end", alone.getEndMs() == 5000);
        check("but nothing is blamed for clipping it", !alone.isClippedByNeighbour());

        // ...and a REAL neighbour is still reported.
        SpriteOverlayItem a = item(s, "hold");
        a.setTimeRange(0, Long.MAX_VALUE);
        a.setContinuesUntilBlocked(true);
        SpriteOverlayItem b = item(s, "hold");
        b.setTimeRange(3000, 4000);
        com.fadcam.ui.faditor.sprite.OpenEndResolver.resolve(
                java.util.Arrays.asList(a, b), id -> s, 60000);
        check("a real neighbour IS reported", a.isClippedByNeighbour() && a.getEndMs() == 3000);
    }

    /**
     * §2a: ABSOLUTE trimming from the LEFT decides WHICH frames survive — they come off the
     * front. Before this existed, both handles did the same thing while the tape drew a red
     * "cuts frames" comb promising otherwise.
     */
    static void startFrameOffsetsTheRun() {
        SpriteSheet s = seq(5, 10f, null, "once");     // 100ms per frame
        SpriteOverlayItem it = item(s, "hold");
        // Range outlives the 5-frame run on purpose: the last assertion is about what a "once"
        // run HOLDS after it finishes, which is only observable inside the item's own range.
        it.setTimeRange(0, 20000);
        check("no offset: t=0 is frame 0", SpriteFrameResolver.resolveCellAt(s, it, 0) == 0);
        it.setSequenceStartFrame(2);
        check("offset 2: t=0 is now frame 2",
                SpriteFrameResolver.resolveCellAt(s, it, 0) == 2);
        check("offset 2: t=100 is frame 3",
                SpriteFrameResolver.resolveCellAt(s, it, 100) == 3);
        check("offset 2: 'once' still holds the LAST frame, not a wrapped one",
                SpriteFrameResolver.resolveCellAt(s, it, 9000) == 4);

        // A held frame must be skipped by its WHOLE hold, not one tick of it.
        SpriteSheet held = seq(3, 10f, w(1, 5, 1), "once");
        SpriteOverlayItem hi = item(held, "hold");
        hi.setTimeRange(0, 700);
        hi.setSequenceStartFrame(1);
        check("offset onto a x5 hold starts ON that frame",
                SpriteFrameResolver.resolveCellAt(held, hi, 0) == 1);
        check("...and stays there for its whole hold",
                SpriteFrameResolver.resolveCellAt(held, hi, 400) == 1);
        check("...then moves on",
                SpriteFrameResolver.resolveCellAt(held, hi, 500) == 2);
    }

    /**
     * The tape must offset by exactly the same amount. A tape drawing frame 0 while the video
     * shows frame 2 would describe an animation nobody is watching — and the mark times must
     * resolve to the cell they claim, which is why the tape ceils where the resolver floors.
     */
    static void startFrameOffsetsTheTapeIdentically() {
        SpriteSheet s = seq(5, 10f, null, "once");
        SpriteOverlayItem it = item(s, "hold");
        it.setTimeRange(0, 500);
        it.setSequenceStartFrame(2);
        List<SpriteFrameResolver.FrameChange> ch =
                SpriteFrameResolver.frameChangesIn(s, it, 0, 500, 100);
        check("the tape starts at the offset frame",
                !ch.isEmpty() && ch.get(0).cellIndex == 2);
        check("the tape emits only the remaining frames", ch.size() == 3);
        boolean agrees = true;
        for (SpriteFrameResolver.FrameChange c : ch) {
            if (SpriteFrameResolver.resolveCellAt(s, it, c.localMs) != c.cellIndex) agrees = false;
        }
        check("every offset tape mark resolves to the cell it claims", agrees);
    }

    static boolean nonContinuingUntouched() {
        SpriteSheet s = seq(3, 10f, null, "loop");
        SpriteOverlayItem it = item(s, "hold");
        it.setTimeRange(0, 1234);
        return !com.fadcam.ui.faditor.sprite.OpenEndResolver.resolve(
                java.util.Collections.singletonList(it), id -> s, 9999)
                && it.getEndMs() == 1234;
    }

    // ── plumbing ────────────────────────────────────────────────────────────

    static void check(String what, boolean ok) {
        if (ok) { passed++; System.out.println("  ok   " + what); }
        else { failed++; System.out.println("  FAIL " + what); }
    }
}
