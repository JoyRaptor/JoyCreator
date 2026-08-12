import com.fadcam.ui.faditor.model.Clip;
import com.fadcam.ui.faditor.model.Timeline;
import com.fadcam.ui.faditor.undo.EditActions;

/**
 * Split → undo must give back the clip that went in.
 *
 * <p>It did not. {@code FaditorPlayerManager.updateTrimEndOnly} wrote its new out-point straight
 * onto {@code currentClip}, and on the legacy player path that object IS the timeline's clip, not a
 * copy. {@code splitAtPlayhead} called it with clip A's out-point immediately after recording a
 * {@code SplitClipAction} holding a live reference to the ORIGINAL clip — so the original was
 * truncated to clip A's range, and undoing the split restored a clip missing clip B's entire span.
 * The project came back SHORTER than it went in, every later clip slid earlier, and the playhead
 * (a raw millisecond value nothing re-maps) then resolved into the wrong clip — reported as "I hit
 * undo and pressed play at the seam and it jumps to a completely different area". Autosave then
 * persisted the shortened timeline, making it data loss rather than a display glitch.
 *
 * <p>The assertion that catches it is simply that total duration is conserved across split+undo.
 * That is the invariant the whole timeline→source mapping rests on, and it needs no device.</p>
 *
 * <p>Run: {@code bash tools/jvm-harness/run-matte.sh}</p>
 */
public class SplitUndoTest {

    private static int passed = 0, failed = 0;

    public static void main(String[] args) {
        splitUndoRestoresExactRange();
        splitUndoConservesTotalDuration();
        theHalvesTileTheOriginal();
        redoAfterUndoStillSplits();

        System.out.println(failed == 0 ? "ALL GREEN (" + passed + "/" + (passed + failed) + ")"
                : "FAILURES: " + failed + " (passed " + passed + ")");
        if (failed > 0) System.exit(1);
    }

    /** One clip, in/out well apart so a split at the midpoint is legal (splitAt wants 100ms edges). */
    private static Timeline oneClip(long in, long out) {
        Timeline t = new Timeline();
        // sourceUri stays null — MatteVisibilityTest's idiom; none of this touches the URI.
        Clip c = new Clip(null, 600_000L);
        c.setInPointMs(in);
        c.setOutPointMs(out);
        t.addClip(c);
        return t;
    }

    private static long total(Timeline t) {
        long sum = 0;
        for (Clip c : t.getClips()) sum += c.getOutPointMs() - c.getInPointMs();
        return sum;
    }

    static void splitUndoRestoresExactRange() {
        Timeline t = oneClip(10_000L, 50_000L);
        Clip original = t.getClip(0);
        long beforeIn = original.getInPointMs(), beforeOut = original.getOutPointMs();

        int idx = t.splitAt(0, 30_000L);
        check("split succeeded", idx == 0 && t.getClipCount() == 2);
        EditActions.SplitClipAction action = new EditActions.SplitClipAction(
                t, 0, original, t.getClip(0), t.getClip(1), t.getTransitions());

        // REPRODUCE THE ACTUAL DEFECT: the player wrote clip A's out-point straight onto the
        // ORIGINAL clip — the detached object this undo record is holding — because on the legacy
        // path currentClip and the timeline's clip were the same object. Undo must survive that.
        original.setOutPointMs(30_000L);

        action.undo();
        check("undo leaves exactly one clip", t.getClipCount() == 1);
        Clip back = t.getClip(0);
        check("restored in-point is the original (" + back.getInPointMs() + ")",
                back.getInPointMs() == beforeIn);
        check("restored OUT-point is the original, not clip A's (" + back.getOutPointMs() + ")",
                back.getOutPointMs() == beforeOut);
    }

    static void splitUndoConservesTotalDuration() {
        Timeline t = oneClip(0L, 120_000L);
        long before = total(t);
        Clip original = t.getClip(0);
        t.splitAt(0, 45_000L);
        EditActions.SplitClipAction action = new EditActions.SplitClipAction(
                t, 0, original, t.getClip(0), t.getClip(1), t.getTransitions());
        check("the two halves still total the original", total(t) == before);
        action.undo();
        check("undo conserves total duration (" + total(t) + " vs " + before + ")",
                total(t) == before);
    }

    static void theHalvesTileTheOriginal() {
        Timeline t = oneClip(5_000L, 65_000L);
        t.splitAt(0, 20_000L);
        Clip a = t.getClip(0), b = t.getClip(1);
        check("A starts at the original in-point", a.getInPointMs() == 5_000L);
        check("A ends at the split", a.getOutPointMs() == 20_000L);
        check("B starts at the split — no gap, no overlap", b.getInPointMs() == 20_000L);
        check("B ends at the original out-point", b.getOutPointMs() == 65_000L);
        check("the halves carry DISTINCT ids", !a.getId().equals(b.getId()));
    }

    static void redoAfterUndoStillSplits() {
        Timeline t = oneClip(0L, 80_000L);
        Clip original = t.getClip(0);
        t.splitAt(0, 25_000L);
        EditActions.SplitClipAction action = new EditActions.SplitClipAction(
                t, 0, original, t.getClip(0), t.getClip(1), t.getTransitions());
        long before = total(t);
        action.undo();
        action.execute();
        check("redo restores two clips", t.getClipCount() == 2);
        check("redo conserves total duration", total(t) == before);
        check("redo's split point is unmoved", t.getClip(0).getOutPointMs() == 25_000L
                && t.getClip(1).getInPointMs() == 25_000L);
    }

    static void check(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (ok) passed++; else failed++;
    }
}
