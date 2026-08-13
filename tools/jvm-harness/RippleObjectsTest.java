import com.fadcam.ui.faditor.model.Clip;
import com.fadcam.ui.faditor.model.TextOverlayItem;
import com.fadcam.ui.faditor.model.Timeline;
import com.fadcam.ui.faditor.undo.EditActions;

import java.util.Map;

/**
 * Every edit that changes LENGTH at time T moves everything after T — clips and objects, anchored
 * or not — and the UNDO of that edit moves them back.
 *
 * <p><b>Why this exists.</b> {@code Timeline.applyAnchorShift} only ever moved riders that carried a
 * {@code hostClipId}. An object placed with no host sits at absolute time, so a trim slid the
 * footage out from under it and left it on the wrong words; three of the twelve images in the
 * project this was found in were unanchored. The failure is quiet — nothing errors, the object is
 * simply over the wrong sentence, and it compounds with every further edit. Undo made it worse,
 * because a trim that moved riders and an undo that did not left the project further out of sync on
 * every press.
 *
 * <p><b>Both mechanisms stay.</b> A host anchor survives clip REORDERING, which no time-shift can
 * express; ripple covers length. Gap mode is the industry's per-track sync lock (Premiere/Resolve)
 * expressed as one project-wide mode — the edit happens and nothing else moves.
 *
 * <p>Assertions here are stated as "the object stays on ITS content": the value checked is the one
 * that keeps an object over the same frame, so a wrong sign or a doubled shift cannot pass.</p>
 *
 * <p>Run: {@code bash tools/jvm-harness/run-anchor.sh}</p>
 */
public class RippleObjectsTest {

    static int fails = 0, checks = 0;

    /**
     * A stored anchor pointing at a clip that is not on the timeline is a rider that never moves
     * again — {@code applyAnchorShift} calls it an orphan every time. Load heals it; the point of
     * healing is not tidiness, it is that the rider RESUMES rippling, so that is what is asserted.
     */
    static void aDanglingAnchorIsHealedAndRipplesAgain() {
        Timeline t = threeClips("ripple");
        TextOverlayItem stranded = overlay(2200, 2600);
        t.addTextOverlay(stranded);
        stranded.setHostAnchor("a-clip-that-was-deleted-long-ago", 200);

        Map<String, Long> before = t.captureClipStarts();
        t.getClip(0).setOutPointMs(1500);
        Timeline.AnchorShiftResult r = t.applyAnchorShift(before);
        eq(stranded.getStartMs(), 2200, "dangling: it does not move — it is read as an orphan");
        check(r.orphanedOverlayIds.contains(stranded.getId()), "dangling: and reported as one");
        t.getClip(0).setOutPointMs(1000);                 // put the fixture back

        java.util.List<String> healed = t.healDanglingHostAnchors();
        check(healed.size() == 1, "heal: exactly the one broken anchor was touched");
        check(t.getClip(2).getId().equals(stranded.getHostClipId()),
                "heal: re-homed to the clip its own start sits in");
        eq(stranded.getStartMs(), 2200, "heal: its TIME was not changed");

        Map<String, Long> before2 = t.captureClipStarts();
        t.getClip(0).setOutPointMs(1500);
        t.applyAnchorShift(before2);
        eq(stranded.getStartMs(), 2700, "heal: and now it ripples again");

        // A valid anchor must not be disturbed, and an empty timeline must not be "healed" into
        // clearing every anchor it has.
        TextOverlayItem good = overlay(2400, 2500);
        t.addTextOverlay(good);
        t.attachOverlayToHostUnderStart(good);
        String goodHost = good.getHostClipId();
        check(t.healDanglingHostAnchors().isEmpty(), "heal: a second pass finds nothing (idempotent)");
        check(goodHost != null && goodHost.equals(good.getHostClipId()),
                "heal: a VALID anchor is left alone");
    }

    /**
     * A text overlay is not the only thing sitting on the timeline. Only {@code TextOverlayItem}
     * can carry a host anchor, so a PiP, a music bed, an adjustment layer, a sprite and a detached
     * visualizer had NO mechanism whatsoever for following an edit — a trim early in the tape left
     * every one of them over different footage.
     */
    static void everyObjectFamilyRipplesNotJustTextOverlays() {
        Timeline t = threeClips("ripple");

        com.fadcam.ui.faditor.sprite.SpriteOverlayItem sprite =
                new com.fadcam.ui.faditor.sprite.SpriteOverlayItem("sprite-1", "sheet-1");
        sprite.setTimeRange(2200, 2600);
        t.addSpriteOverlay(sprite);

        Clip pip = clip(500);
        pip.setLayerId("layer-1");
        pip.setOverlayStartMs(2200);
        t.addOverlayClip(pip);

        com.fadcam.ui.faditor.model.AudioClip music =
                new com.fadcam.ui.faditor.model.AudioClip(null, 60_000);
        music.setOffsetMs(2200);
        t.addAudioClip(music, false);

        com.fadcam.ui.faditor.model.AdjustmentLayer adj =
                new com.fadcam.ui.faditor.model.AdjustmentLayer();
        adj.setStartMs(2200);
        adj.setDurationMs(400);
        t.addAdjustmentLayer(adj);

        Map<String, Long> before = t.captureClipStarts();
        t.getClip(0).setOutPointMs(1500);                 // +500 at the head
        Timeline.AnchorShiftResult r = t.applyAnchorShift(before);

        eq(sprite.getStartMs(), 2700, "sprite rippled");
        eq(pip.getOverlayStartMs(), 2700, "PiP rippled");
        eq(music.getOffsetMs(), 2700, "audio clip rippled");
        eq(adj.getStartMs(), 2700, "adjustment layer rippled");
        eq(adj.getDurationMs(), 400, "adjustment layer kept its LENGTH — a ripple is not a stretch");
        check(r.movedObjectIds.size() == 4,
                "all four are reported, apart from the overlay list (got "
                        + r.movedObjectIds.size() + ")");

        // Gap mode leaves every one of them alone.
        Timeline g = threeClips("gap");
        Clip gpip = clip(500);
        gpip.setLayerId("layer-1");
        gpip.setOverlayStartMs(2200);
        g.addOverlayClip(gpip);
        Map<String, Long> gbefore = g.captureClipStarts();
        g.getClip(0).setOutPointMs(1500);
        g.applyAnchorShift(gbefore);
        eq(gpip.getOverlayStartMs(), 2200, "gap mode: the PiP stays put");
    }

    /**
     * "Length change" is not only trim/delete/split. A clip's on-timeline span is
     * {@code hasLoopExtension() ? getVisualDurationMs() : getTrimmedDurationMs()}, and
     * {@code getTrimmedDurationMs} is {@code raw / speed} — so changing SPEED or resizing a LOOP
     * moves every later clip exactly as a trim does. Both were unbracketed in the editor.
     *
     * <p>This pins the model half: that the ripple math sees those two as length changes at all.
     * The editor-side brackets that feed it are activity code and cannot be reached from here.</p>
     */
    static void speedAndLoopAreLengthChangesToo() {
        Timeline t = threeClips("ripple");
        TextOverlayItem free = overlay(2200, 2600);
        t.addTextOverlay(free);

        Map<String, Long> before = t.captureClipStarts();
        t.getClip(0).setSpeedMultiplier(2.0f);            // 1000ms of source → 500ms on the tape
        t.applyAnchorShift(before);
        eq(free.getStartMs(), 1700, "speed: doubling clip 0's speed pulled the object back by 500");

        Timeline t2 = threeClips("ripple");
        TextOverlayItem free2 = overlay(2200, 2600);
        t2.addTextOverlay(free2);
        Map<String, Long> before2 = t2.captureClipStarts();
        t2.getClip(0).setLoopMode(Clip.LOOP_MODE_NORMAL);
        t2.getClip(0).setLoopAfterMs(300);                // the extension is part of the span
        t2.applyAnchorShift(before2);
        eq(free2.getStartMs(), 2500, "loop: extending clip 0 by 300 pushed the object right by 300");
    }

    /**
     * The reason the nesting guard keys on the outermost bracket's IDENTITY rather than counting
     * depth: a bracket that is opened and never closed — an edit path that returns early between
     * begin and end — must not disable rippling for the rest of the session. With a counter, one
     * leak left it permanently +1 and every later edit read as nested, i.e. silently no-op. That
     * failure would be invisible: no crash, no log, objects simply stop following edits.
     */
    static void aLeakedInnerBracketDoesNotSwitchRippleOffForGood() {
        Timeline t = threeClips("ripple");
        TextOverlayItem free = overlay(2200, 2600);
        t.addTextOverlay(free);

        Map<String, Long> outer = t.beginStructural();
        t.beginStructural();                          // inner — deliberately NEVER closed
        t.getClip(0).setOutPointMs(1500);
        t.endStructural(outer);
        eq(free.getStartMs(), 2700, "the outermost bracket still applied its shift");

        // And the NEXT edit, with the leak still in the past, ripples normally.
        Map<String, Long> next = t.beginStructural();
        t.getClip(0).setOutPointMs(1700);
        t.endStructural(next);
        eq(free.getStartMs(), 2900, "a later edit is not treated as nested by the stale bracket");

        // A map that never came from beginStructural applies directly — the standalone contract
        // the AI paths and this harness rely on.
        Map<String, Long> plain = t.captureClipStarts();
        t.getClip(0).setOutPointMs(1900);
        t.endStructural(plain);
        eq(free.getStartMs(), 3100, "a bare captureClipStarts map still applies on its own");
    }

    /**
     * A ripple is a MOVE, so an animated object's keys travel with it — the opposite of what a
     * front TRIM must do, and the two live one method apart.
     *
     * <p>Object keys are stored local to the object's start, so shifting the start through
     * {@code setTimeRange} carries the animation for free and the object stays over the footage it
     * was placed against, animation and all. Rebasing them here — the thing
     * {@code setTrimmedTimeRange} exists to do for a trim handle — would hold the animation still
     * in project time while its object slid out from under it, which is exactly the desync the
     * ripple work was built to end.</p>
     *
     * <p>Pinned because the two methods now sit side by side, and "make these consistent" is a
     * plausible and completely wrong thing for the next reader to do.</p>
     */
    static void aRippleCarriesAnObjectsAnimationWithIt() {
        Timeline t = threeClips("ripple");
        TextOverlayItem free = overlay(2200, 2600);
        free.getKeyframes().getOrCreate(com.fadcam.ui.faditor.keyframe.KeyframeSet.X)
                .put(0, 0.2f, com.fadcam.ui.faditor.keyframe.Easing.LINEAR);
        free.getKeyframes().getOrCreate(com.fadcam.ui.faditor.keyframe.KeyframeSet.X)
                .put(300, 0.8f, com.fadcam.ui.faditor.keyframe.Easing.LINEAR);
        t.addTextOverlay(free);

        Map<String, Long> before = t.captureClipStarts();
        t.getClip(0).setOutPointMs(1500);          // +500 at the head
        t.applyAnchorShift(before);

        eq(free.getStartMs(), 2700, "ripple: the object moved with its footage");
        // LOCAL times untouched → both keys moved with it in PROJECT time.
        eq(free.getKeyframes().get(com.fadcam.ui.faditor.keyframe.KeyframeSet.X)
                .keyframes.get(0).timeMs, 0, "ripple: local key times are UNCHANGED");
        eq(free.getStartMs() + free.getKeyframes()
                .get(com.fadcam.ui.faditor.keyframe.KeyframeSet.X).keyframes.get(1).timeMs, 3000,
                "ripple: so the animation sits 500 later, exactly like its object");
    }

    static void check(boolean c, String n) {
        checks++;
        System.out.println((c ? "PASS  " : "FAIL  ") + n);
        if (!c) fails++;
    }

    static void eq(long a, long b, String n) {
        check(a == b, n + "  (got " + a + ", want " + b + ")");
    }

    /**
     * A clip of {@code durMs} on the timeline, with a source far longer than the span.
     * {@code setOutPointMs} CLAMPS to the source duration, so a fixture whose source equals its
     * span silently refuses every lengthening trim and the test then passes against a no-op.
     */
    static Clip clip(long durMs) {
        Clip c = new Clip(null, 600_000);
        c.setInPointMs(0);
        c.setOutPointMs(durMs);
        return c;
    }

    static TextOverlayItem overlay(long startMs, long endMs) {
        TextOverlayItem o = new TextOverlayItem("t", 0xFFFFFFFF, 0.5f, 0.5f, 0.1f, 0f);
        o.setTimeRange(startMs, endMs);
        return o;
    }

    /** Three clips of 1000 each → starts 0 / 1000 / 2000, total 3000. */
    static Timeline threeClips(String mode) {
        Timeline t = new Timeline();
        t.setRippleMode(mode);
        t.addClip(clip(1000));
        t.addClip(clip(1000));
        t.addClip(clip(1000));
        return t;
    }

    public static void main(String[] args) {
        trimRipplesUnanchoredObjects();
        trimUndoPutsThemBack();
        trimUndoIsExactOverManyEdits();
        deleteRipplesUnanchoredObjects();
        objectOnTheTrimmedClipItselfDoesNotMove();
        undoUnderTheEditorsOwnBracketDoesNotDoubleShift();
        gapModeMovesNothing();
        gapModeUndoAlsoMovesNothing();
        openEndedObjectKeepsItsSentinel();
        aDanglingAnchorIsHealedAndRipplesAgain();
        everyObjectFamilyRipplesNotJustTextOverlays();
        speedAndLoopAreLengthChangesToo();
        aLeakedInnerBracketDoesNotSwitchRippleOffForGood();
        aRippleCarriesAnObjectsAnimationWithIt();

        System.out.println();
        System.out.println(fails == 0 ? ("ALL PASS — " + checks + " checks")
                                      : (fails + " FAILED of " + checks));
        if (fails != 0) System.exit(1);
    }

    /** Lengthening clip 0 by 500 pushes an unanchored object on clip 2 right by exactly 500. */
    static void trimRipplesUnanchoredObjects() {
        Timeline t = threeClips("ripple");
        TextOverlayItem free = overlay(2200, 2600);   // 200ms into clip 2, NOT attached
        t.addTextOverlay(free);
        check(free.getHostClipId() == null, "setup: the object really is unanchored");

        Map<String, Long> before = t.captureClipStarts();
        t.getClip(0).setOutPointMs(1500);
        Timeline.AnchorShiftResult r = t.applyAnchorShift(before);

        eq(free.getStartMs(), 2700, "trim: the unanchored object rode the +500 with its footage");
        eq(free.getEndMs(), 3100, "trim: and kept its duration");
        eq(free.getStartMs() - t.getClipStartMs(2), 200,
                "trim: it is still 200ms into clip 2 — the only thing that actually matters");
        check(r.movedOverlayIds.contains(free.getId()), "trim: the move is reported for undo");
    }

    /** The regression that made every undo worse than the edit: undo must move them back. */
    static void trimUndoPutsThemBack() {
        Timeline t = threeClips("ripple");
        TextOverlayItem free = overlay(2200, 2600);
        t.addTextOverlay(free);
        Clip c0 = t.getClip(0);

        EditActions.TrimAction action = new EditActions.TrimAction(t, c0, 0, 1000, 0, 1500);
        action.execute();
        eq(free.getStartMs(), 2700, "trim via the undo action: object rippled");

        action.undo();
        eq(free.getStartMs(), 2200, "UNDO: the object came back to where the user put it");
        eq(free.getEndMs(), 2600, "UNDO: with its duration intact");

        action.execute();
        eq(free.getStartMs(), 2700, "REDO: and forward again");
    }

    /**
     * Drift is the real symptom: each edit is small, and it is the SUM that lands an object on the
     * wrong sentence. Five trims out and five undos back must return the exact original value —
     * an off-by-one in the shift would survive a single round trip but not this.
     */
    static void trimUndoIsExactOverManyEdits() {
        Timeline t = threeClips("ripple");
        TextOverlayItem free = overlay(2200, 2600);
        t.addTextOverlay(free);
        Clip c0 = t.getClip(0);

        EditActions.TrimAction[] actions = new EditActions.TrimAction[5];
        long out = 1000;
        for (int i = 0; i < actions.length; i++) {
            long next = out + 137;                    // deliberately not a round number
            actions[i] = new EditActions.TrimAction(t, c0, 0, out, 0, next);
            actions[i].execute();
            out = next;
        }
        eq(free.getStartMs(), 2200 + 5 * 137, "five trims: the object tracked every one");

        for (int i = actions.length - 1; i >= 0; i--) actions[i].undo();
        eq(free.getStartMs(), 2200, "five undos: back to the exact original, no drift");
        eq(free.getEndMs(), 2600, "five undos: end too");
        eq(t.getClip(0).getOutPointMs(), 1000, "five undos: and the clip itself is restored");
    }

    /** Deleting a clip pulls everything after it left, objects included. */
    static void deleteRipplesUnanchoredObjects() {
        Timeline t = threeClips("ripple");
        TextOverlayItem free = overlay(2200, 2600);
        t.addTextOverlay(free);

        Map<String, Long> before = t.captureClipStarts();
        t.removeClip(0);
        t.applyAnchorShift(before);

        eq(free.getStartMs(), 1200, "delete: the object moved left by the deleted clip's length");
        eq(free.getStartMs() - t.getClipStartMs(1), 200,
                "delete: still 200ms into the clip it was placed over");
    }

    /**
     * An object on the clip being trimmed must NOT move: that clip's own start never changed, and
     * the content under the object is exactly where it was. Moving it is the classic double-shift.
     */
    static void objectOnTheTrimmedClipItselfDoesNotMove() {
        Timeline t = threeClips("ripple");
        TextOverlayItem onIt = overlay(200, 400);     // inside clip 0
        t.addTextOverlay(onIt);

        Map<String, Long> before = t.captureClipStarts();
        t.getClip(0).setOutPointMs(1500);             // clip 0 grows; its START is unchanged
        Timeline.AnchorShiftResult r = t.applyAnchorShift(before);

        eq(onIt.getStartMs(), 200, "trim: an object on the trimmed clip itself does not move");
        check(!r.movedOverlayIds.contains(onIt.getId()),
                "trim: and it is not reported as moved");
    }

    /**
     * The editor brackets undo and redo WHOLESALE ({@code performUndo} → {@code beginStructuralEdit}
     * … {@code endStructuralEdit}), so an action that also shifts riders on its own is the second
     * shift of the same delta: the object lands twice as far as the footage moved, in the opposite
     * direction from the original bug and just as wrong.
     *
     * <p>Written as the editor writes it — outer bracket around the action — because that nesting IS
     * the case under test. A guard inside {@code Timeline} makes the inner shift a no-op while an
     * outer bracket is open, so an action stays correct both nested and standalone (the AI and
     * script paths call actions with no bracket at all).</p>
     */
    static void undoUnderTheEditorsOwnBracketDoesNotDoubleShift() {
        Timeline t = threeClips("ripple");
        TextOverlayItem free = overlay(2200, 2600);
        t.addTextOverlay(free);
        EditActions.TrimAction action =
                new EditActions.TrimAction(t, t.getClip(0), 0, 1000, 0, 1500);

        Map<String, Long> outer = t.beginStructural();
        action.execute();
        t.endStructural(outer);
        eq(free.getStartMs(), 2700, "nested execute: object moved ONCE (+500)");

        Map<String, Long> outer2 = t.beginStructural();
        action.undo();
        t.endStructural(outer2);
        eq(free.getStartMs(), 2200, "nested undo: object moved back ONCE, not twice");
        eq(t.getClip(0).getOutPointMs(), 1000, "nested undo: the clip is restored");

        // Standalone — no outer bracket — the action must still ripple by itself.
        action.execute();
        eq(free.getStartMs(), 2700, "standalone execute: the action still ripples on its own");
    }

    /** Gap mode: the edit happens, nothing else moves. */
    static void gapModeMovesNothing() {
        Timeline t = threeClips("gap");
        TextOverlayItem free = overlay(2200, 2600);
        TextOverlayItem rider = overlay(2400, 2500);
        t.addTextOverlay(free);
        t.addTextOverlay(rider);
        t.attachOverlayToHostUnderStart(rider);
        check(rider.getHostClipId() != null, "setup: the second object IS anchored");

        Map<String, Long> before = t.captureClipStarts();
        t.getClip(0).setOutPointMs(1500);
        Timeline.AnchorShiftResult r = t.applyAnchorShift(before);

        eq(free.getStartMs(), 2200, "gap: the unanchored object stays at its absolute time");
        eq(rider.getStartMs(), 2400, "gap: so does the anchored one");
        check(r.movedOverlayIds.isEmpty(), "gap: nothing reported as moved");
        eq(t.getClip(0).getOutPointMs(), 1500, "gap: the trim itself still happened");
    }

    /** …and the undo of a gap-mode edit must not "helpfully" put anything back. */
    static void gapModeUndoAlsoMovesNothing() {
        Timeline t = threeClips("gap");
        TextOverlayItem free = overlay(2200, 2600);
        t.addTextOverlay(free);

        EditActions.TrimAction action = new EditActions.TrimAction(t, t.getClip(0), 0, 1000, 0, 1500);
        action.execute();
        eq(free.getStartMs(), 2200, "gap: object unmoved by the trim");
        action.undo();
        eq(free.getStartMs(), 2200, "gap: and unmoved by the undo — no phantom shift back");
        eq(t.getClip(0).getOutPointMs(), 1000, "gap: the undo still restored the clip");
    }

    /**
     * An open-ended object runs to the end of the timeline. Its end is a SENTINEL
     * ({@code AnchorMath.OPEN_END}); arithmetic on it produces a huge CLOSED end that
     * {@code setTimeRange} happily stores, and the object then owns its lane forever.
     */
    static void openEndedObjectKeepsItsSentinel() {
        Timeline t = threeClips("ripple");
        TextOverlayItem open = overlay(2200, Long.MAX_VALUE);
        t.addTextOverlay(open);

        Map<String, Long> before = t.captureClipStarts();
        t.removeClip(0);                              // negative delta — the corrupting direction
        t.applyAnchorShift(before);

        eq(open.getStartMs(), 1200, "open-ended: the start rippled");
        eq(open.getEndMs(), Long.MAX_VALUE, "open-ended: the END SENTINEL survived exactly");
    }
}
