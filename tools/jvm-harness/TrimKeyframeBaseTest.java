import com.fadcam.ui.faditor.keyframe.Easing;
import com.fadcam.ui.faditor.keyframe.KeyframeSet;
import com.fadcam.ui.faditor.model.TextOverlayItem;

/**
 * Trimming an object's FRONT must not move its keyframes in project time.
 *
 * <p>An overlay's keys are stored in LOCAL time — {@code localTime(t) = t - startMs} — so the two
 * edges behaved differently for free. Dragging the RIGHT edge never moved a key, because the start
 * is the time base and the right edge does not touch it. Dragging the LEFT edge moved the base, so
 * every key slid later in project time along with it.
 *
 * <p>Reported as: <i>"I have images, I've done a bunch of keyframing, I pull on the front trim and
 * the keyframes go with the front trim. If I wanted that I could just move the object in time and
 * extend the back end. Extending from the beginning should give the same behaviour as the end:
 * frames added at the front, no keyframe moves. Keyframes only move if I'm dragging the object on
 * the timeline or moving individual keys."</i>
 *
 * <p>So the assertions here are all written in PROJECT time — the number the user can see — not in
 * the local times the fix actually rewrites.</p>
 *
 * <p>Run: {@code bash tools/jvm-harness/run-anchor.sh}</p>
 */
public class TrimKeyframeBaseTest {

    static int fails = 0, checks = 0;

    /**
     * A sprite/sequence object stores its keys against the SAME local base, so the front handle had
     * the identical bug. Found by reading, not by report — the fix for images would otherwise have
     * left the other family that uses {@code localTime} behaving the old way.
     *
     * <p>The frame CADENCE is a separate question the gesture still owns: for a sequence the left
     * edge also decides which frames survive. This is only about where the animation sits.</p>
     */
    static void aSpriteBehavesTheSameWay() {
        com.fadcam.ui.faditor.sprite.SpriteOverlayItem s =
                new com.fadcam.ui.faditor.sprite.SpriteOverlayItem("sprite-1", "sheet-1");
        s.setTimeRange(5000, 9000);
        s.getKeyframes().getOrCreate(KeyframeSet.X).put(0, 0.1f, Easing.LINEAR);
        s.getKeyframes().getOrCreate(KeyframeSet.X).put(1000, 0.9f, Easing.LINEAR);

        s.setTrimmedTimeRange(3000, 9000);
        eq(s.getStartMs() + s.getKeyframes().get(KeyframeSet.X).keyframes.get(0).timeMs, 5000,
                "sprite front trim: first key held its project time");
        eq(s.getStartMs() + s.getKeyframes().get(KeyframeSet.X).keyframes.get(1).timeMs, 6000,
                "sprite front trim: and the second");

        // A FRESH sprite for the move case: after the trim above the first key legitimately sits
        // 2000ms into the object, so a move would carry it to 10000 — correct, but it would test
        // the trim twice instead of testing the move.
        com.fadcam.ui.faditor.sprite.SpriteOverlayItem m =
                new com.fadcam.ui.faditor.sprite.SpriteOverlayItem("sprite-2", "sheet-1");
        m.setTimeRange(5000, 9000);
        m.getKeyframes().getOrCreate(KeyframeSet.X).put(0, 0.1f, Easing.LINEAR);
        m.setTimeRange(8000, 12000);   // a MOVE takes the animation along, as for any object
        eq(m.getStartMs() + m.getKeyframes().get(KeyframeSet.X).keyframes.get(0).timeMs, 8000,
                "sprite move: the animation travelled with it");
    }

    /**
     * "Drag the handle out and back and nothing has changed" has to survive CLOSING the project.
     *
     * <p>Trimming the front later pushes early keys before the object's own zero, and the whole
     * reason they are kept rather than clamped is reversibility. That only holds if a negative
     * time round-trips through the file: a clamp in the codec — or in {@code KeyframeTrack.put} on
     * the way back in — would silently collapse them to 0 on the next open, and the animation
     * would be quietly different from the one the user left.</p>
     */
    static void negativeKeyTimesSurviveSaveAndLoad() {
        TextOverlayItem o = animated();
        o.setTrimmedTimeRange(6000, 9000);            // start moves PAST the first key
        long localBefore = o.getKeyframes().get(KeyframeSet.X).keyframes.get(0).timeMs;
        check(localBefore < 0, "setup: the passed key really is at a negative local time ("
                + localBefore + ")");

        com.google.gson.JsonObject json =
                com.fadcam.ui.faditor.keyframe.KeyframeCodec.toJson(o.getKeyframes());
        KeyframeSet back = com.fadcam.ui.faditor.keyframe.KeyframeCodec.fromJson(json);
        eq(back.get(KeyframeSet.X).keyframes.get(0).timeMs, localBefore,
                "save/load: the negative time came back exactly");
        eq(back.get(KeyframeSet.X).keyframes.size(), 2,
                "save/load: both keys survived, not collapsed onto one instant");
    }

    static void check(boolean c, String n) {
        checks++;
        System.out.println((c ? "PASS  " : "FAIL  ") + n);
        if (!c) fails++;
    }

    static void eq(long a, long b, String n) {
        check(a == b, n + "  (got " + a + ", want " + b + ")");
    }

    /** An overlay from 5000 to 9000 with X keys at project 5000 and 6000. */
    static TextOverlayItem animated() {
        TextOverlayItem o = new TextOverlayItem("t", 0xFFFFFFFF, 0.5f, 0.5f, 0.1f, 0f);
        o.setTimeRange(5000, 9000);
        o.getKeyframes().getOrCreate(KeyframeSet.X).put(0, 0.1f, Easing.LINEAR);
        o.getKeyframes().getOrCreate(KeyframeSet.X).put(1000, 0.9f, Easing.LINEAR);
        return o;
    }

    /** Project time of the key at index {@code i} of the X track. */
    static long projectTimeOfKey(TextOverlayItem o, int i) {
        return o.getStartMs() + o.getKeyframes().get(KeyframeSet.X).keyframes.get(i).timeMs;
    }

    public static void main(String[] args) {
        frontTrimLeavesKeysWhereTheyAre();
        backTrimLeavesKeysWhereTheyAre();
        movingTheObjectTakesItsKeysWithIt();
        outAndBackIsExactlyReversible();
        shorteningTheFrontKeepsKeysItPassed();
        aSpriteBehavesTheSameWay();
        negativeKeyTimesSurviveSaveAndLoad();

        System.out.println();
        System.out.println(fails == 0 ? ("ALL PASS — " + checks + " checks")
                                      : (fails + " FAILED of " + checks));
        if (fails != 0) System.exit(1);
    }

    /** The bug, stated as the user sees it: extend the front, the animation must not move. */
    static void frontTrimLeavesKeysWhereTheyAre() {
        TextOverlayItem o = animated();
        eq(projectTimeOfKey(o, 0), 5000, "setup: first key sits at project 5000");
        eq(projectTimeOfKey(o, 1), 6000, "setup: second key at project 6000");

        o.setTrimmedTimeRange(3000, 9000);          // pull the front 2s earlier

        eq(o.getStartMs(), 3000, "front trim: the object really did start earlier");
        eq(projectTimeOfKey(o, 0), 5000, "front trim: the first key did NOT move");
        eq(projectTimeOfKey(o, 1), 6000, "front trim: nor the second");
        check(o.getKeyframes().get(KeyframeSet.X).keyframes.get(0).timeMs == 2000,
                "front trim: the LOCAL time absorbed the change instead (2000)");
    }

    /** The edge that was already right — pinned so the two cannot drift apart. */
    static void backTrimLeavesKeysWhereTheyAre() {
        TextOverlayItem o = animated();
        o.setTrimmedTimeRange(5000, 14000);
        eq(o.getEndMs(), 14000, "back trim: the end moved");
        eq(projectTimeOfKey(o, 0), 5000, "back trim: keys unmoved, as they always were");
        eq(projectTimeOfKey(o, 1), 6000, "back trim: second key unmoved");
    }

    /** The gesture that SHOULD carry the animation: dragging the object along the timeline. */
    static void movingTheObjectTakesItsKeysWithIt() {
        TextOverlayItem o = animated();
        o.setTimeRange(8000, 12000);                // a MOVE — duration preserved
        eq(projectTimeOfKey(o, 0), 8000, "move: the animation travelled with the object");
        eq(projectTimeOfKey(o, 1), 9000, "move: and kept its shape");
    }

    /**
     * A trim handle has to be reversible: drag it out, drag it back, nothing has changed. This is
     * what a clamp at zero would break, and why shiftAll keeps negative local times.
     */
    static void outAndBackIsExactlyReversible() {
        TextOverlayItem o = animated();
        o.setTrimmedTimeRange(0, 9000);
        o.setTrimmedTimeRange(5000, 9000);
        eq(o.getStartMs(), 5000, "out and back: start restored");
        eq(projectTimeOfKey(o, 0), 5000, "out and back: first key exactly restored");
        eq(projectTimeOfKey(o, 1), 6000, "out and back: second key exactly restored");
        eq(o.getKeyframes().get(KeyframeSet.X).keyframes.get(0).timeMs, 0,
                "out and back: local times are back to the originals too");
    }

    /** Trimming the front LATER, past a key: the key keeps its project time, now outside. */
    static void shorteningTheFrontKeepsKeysItPassed() {
        TextOverlayItem o = animated();
        o.setTrimmedTimeRange(5500, 9000);          // start moves PAST the first key
        eq(projectTimeOfKey(o, 0), 5000,
                "shortening: the passed key keeps its project time (now before the window)");
        eq(projectTimeOfKey(o, 1), 6000, "shortening: the visible key did not move either");
        o.setTrimmedTimeRange(5000, 9000);
        eq(projectTimeOfKey(o, 0), 5000, "shortening then restoring: nothing was destroyed");
    }
}
