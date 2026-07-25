import com.fadcam.ui.faditor.model.Transition;
import com.fadcam.ui.faditor.model.TransitionIndex;

import java.util.ArrayList;
import java.util.List;

/**
 * Transition index bookkeeping vs undo (tasks/AUDIT_TRANSITION_INDEX_UNDO.md).
 *
 * The bug this pins down: the shift helpers mutate Transition.clipIndex IN PLACE, and
 * re-adding a clip does not reverse that — so an undo that restores clips without restoring
 * the transition list leaves transitions on the WRONG seams. That is worse than losing them:
 * the transition still plays, just at a cut the user never chose.
 *
 * Compile+run (from the repo root; only the annotation stubs are needed):
 *   javac -d tools/jvm-harness/out3 \
 *       tools/jvm-harness/stubs/androidx/annotation/*.java \
 *       app/src/main/java/com/fadcam/ui/faditor/model/Transition.java \
 *       app/src/main/java/com/fadcam/ui/faditor/model/TransitionIndex.java \
 *       tools/jvm-harness/TransitionIndexTest.java
 *   java -cp tools/jvm-harness/out3 TransitionIndexTest
 */
public class TransitionIndexTest {
    static int fails = 0;

    static void check(boolean c, String n) {
        System.out.println((c ? "PASS  " : "FAIL  ") + n);
        if (!c) fails++;
    }

    static List<Transition> lst(int... indices) {
        List<Transition> out = new ArrayList<>();
        for (int i : indices) out.add(new Transition(Transition.Type.CROSS_DISSOLVE, 500, i));
        return out;
    }

    static String idx(List<Transition> ts) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < ts.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(ts.get(i).clipIndex);
        }
        return sb.append(']').toString();
    }

    public static void main(String[] a) {
        // 1. DELETE renumbers later seams and drops the adjacent ones.
        List<Transition> t = lst(0, 2, 4);
        TransitionIndex.removeForDeletedClip(t, 3);   // drops clipIndex 3 and 2
        check(idx(t).equals("[0,3]"), "delete: adjacent dropped, later renumbered -> " + idx(t));

        // 2. THE REGRESSION: snapshot/restore must reverse a delete EXACTLY.
        //    Without restore, [0,2,4] came back as [0,3] — a transition that lived between
        //    clips 4 and 5 silently moved to the seam between 3 and 4.
        List<Transition> live = lst(0, 2, 4);
        List<Transition> before = TransitionIndex.snapshot(live);
        TransitionIndex.removeForDeletedClip(live, 3);
        TransitionIndex.restore(live, before);
        check(idx(live).equals("[0,2,4]"), "delete+restore is exact -> " + idx(live));

        // 3. The snapshot must be DEEP: mutating the live list afterwards must not reach it.
        List<Transition> src = lst(7);
        List<Transition> snap = TransitionIndex.snapshot(src);
        src.get(0).clipIndex = 99;
        check(snap.get(0).clipIndex == 7, "snapshot is deep — clipIndex not aliased");

        // 4. ...and restore must hand back independent objects too, or the next in-place
        //    shift would corrupt the snapshot and break a second undo.
        List<Transition> tgt = new ArrayList<>();
        TransitionIndex.restore(tgt, snap);
        tgt.get(0).clipIndex = 42;
        check(snap.get(0).clipIndex == 7, "restore copies — snapshot survives later edits");

        // 5. Restore must also reverse INSERT and SPLIT (the sites still unaudited).
        List<Transition> ins = lst(1, 3);
        List<Transition> insBefore = TransitionIndex.snapshot(ins);
        TransitionIndex.shiftAfterInsert(ins, 2);
        check(idx(ins).equals("[1,4]"), "insert shifts from the index up -> " + idx(ins));
        TransitionIndex.restore(ins, insBefore);
        check(idx(ins).equals("[1,3]"), "insert+restore is exact -> " + idx(ins));

        List<Transition> sp = lst(0, 2, 5);
        List<Transition> spBefore = TransitionIndex.snapshot(sp);
        TransitionIndex.shiftAfterSplit(sp, 2);
        check(idx(sp).equals("[0,3,6]"), "split shifts from the index up -> " + idx(sp));
        TransitionIndex.restore(sp, spBefore);
        check(idx(sp).equals("[0,2,5]"), "split+restore is exact -> " + idx(sp));

        // 6. Deleting clip 0 has no clipIndex -1 seam to drop, and must not go negative.
        List<Transition> zero = lst(0, 1);
        TransitionIndex.removeForDeletedClip(zero, 0);
        check(idx(zero).equals("[0]"), "delete clip 0: no negative indices -> " + idx(zero));

        // 7. Non-index fields survive the round trip (paramOverrides is mutable — a shallow
        //    copy would share the map).
        List<Transition> rich = new ArrayList<>();
        Transition r = new Transition(Transition.Type.CROSS_DISSOLVE, 750, 1, 0.25f);
        r.glTransitionId = "tangentMotionBlur";
        r.paramOverrides = new java.util.HashMap<>();
        r.paramOverrides.put("strength", 0.8f);
        rich.add(r);
        List<Transition> richSnap = TransitionIndex.snapshot(rich);
        rich.get(0).paramOverrides.put("strength", 0.1f);
        Transition rs = richSnap.get(0);
        check(rs.durationMs == 750 && rs.fuzziness == 0.25f
                        && "tangentMotionBlur".equals(rs.glTransitionId)
                        && rs.paramOverrides.get("strength") == 0.8f,
                "snapshot preserves duration/fuzziness/glId and deep-copies paramOverrides");

        System.out.println(fails == 0 ? "\nALL PASS" : "\n" + fails + " FAILURE(S)");
        if (fails > 0) System.exit(1);
    }
}
