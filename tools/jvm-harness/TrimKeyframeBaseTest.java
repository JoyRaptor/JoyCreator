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
