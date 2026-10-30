import com.fadcam.ui.faditor.sprite.BoardAttachPlan;

import java.util.HashSet;
import java.util.Set;

/**
 * Joy Brush "Send to Studio" into an existing project: where the board lands and which sheet id it keeps.
 */
public class BoardAttachPlanTest {

    static int fails, passes;

    static void check(boolean ok, String what) {
        if (ok) passes++; else { fails++; System.out.println("FAIL " + what); }
    }

    public static void main(String[] args) {
        // An empty project: the animation plays once and holds, whole canvas (like a new project).
        BoardAttachPlan.Placement empty = BoardAttachPlan.onExisting(0, 1500);
        check(empty.startMs == 0 && empty.endMs == 1500, "empty: one pass from 0");
        check("hold".equals(empty.endBehavior), "empty: holds");
        check(empty.sizeFraction == 1f, "empty: whole canvas");
        check(BoardAttachPlan.onExisting(-5, 1500).endMs == 1500, "a negative length counts as empty");

        // A 30 s project: the overlay runs over all of it, loops, half the canvas.
        BoardAttachPlan.Placement over = BoardAttachPlan.onExisting(30_000, 1500);
        check(over.startMs == 0 && over.endMs == 30_000, "over: whole project");
        check("loop".equals(over.endBehavior), "over: loops");
        check(over.sizeFraction == BoardAttachPlan.OVER_SIZE && over.sizeFraction == 0.5f, "over: half size");

        // A project shorter than one pass is never cut below the animation.
        BoardAttachPlan.Placement tiny = BoardAttachPlan.onExisting(400, 1500);
        check(tiny.endMs == 1500, "short project: the whole pass still shows");
        check("loop".equals(tiny.endBehavior), "short project: still loops");

        // A degenerate animation length is lifted to 1 ms.
        check(BoardAttachPlan.onExisting(0, 0).endMs == 1, "zero-length animation is 1 ms");
        check(BoardAttachPlan.onExisting(10_000, -3).endMs == 10_000, "negative animation length never shortens the overlay");

        // Sheet ids.
        Set<String> taken = new HashSet<>();
        taken.add("a");
        check("b".equals(BoardAttachPlan.sheetIdFor("b", taken, () -> "x")), "a free id is kept");
        check("x".equals(BoardAttachPlan.sheetIdFor("a", taken, () -> "x")), "a taken id is swapped");
        check("x".equals(BoardAttachPlan.sheetIdFor(null, taken, () -> "x")), "a missing id gets one");
        check("x".equals(BoardAttachPlan.sheetIdFor("", taken, () -> "x")), "an empty id gets one");
        final int[] n = {0};
        String[] seq = {"a", "a", "c"};
        check("c".equals(BoardAttachPlan.sheetIdFor("a", taken, () -> seq[n[0]++])), "a fresh id that is also taken is drawn again");

        System.out.println("BoardAttachPlanTest: " + passes + " passed, " + fails + " failed");
        if (fails > 0) System.exit(1);
    }
}
