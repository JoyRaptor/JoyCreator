import com.fadcam.ui.faditor.model.AudioClip;
import com.fadcam.ui.faditor.model.Clip;
import com.fadcam.ui.faditor.model.RippleCheck;
import com.fadcam.ui.faditor.model.TextOverlayItem;
import com.fadcam.ui.faditor.model.Timeline;

import java.util.Map;

/**
 * STU-5, the owner's ripple check (11-27): an edit that makes the project shorter at a point says how much and how many
 * layers start after it, and the person's answer for THIS edit (move them with their clips, or leave them) is applied
 * by the timeline's own structural step without changing the project's ripple setting.
 */
public class RippleCheckTest {
    static int pass = 0, fail = 0;
    static void check(boolean ok, String name) { if (ok) pass++; else { fail++; System.out.println("FAIL " + name); } }
    static void eq(long got, long want, String name) { check(got == want, name + "  (got " + got + ", want " + want + ")"); }

    static Clip clip(long durMs) {
        Clip c = new Clip(null, 600_000);
        c.setInPointMs(0);
        c.setOutPointMs(durMs);
        return c;
    }

    static TextOverlayItem text(long start, long end) {
        TextOverlayItem t = new TextOverlayItem("Hi", 0xFFFFFFFF, 0.5f, 0.5f, 0.1f, 0f);
        t.setTimeRange(start, end);
        return t;
    }

    /** Two 10 s clips, a text over each, a song from 15 s. */
    static Object[] project(String mode) {
        Timeline tl = new Timeline();
        tl.addClip(clip(10_000));
        tl.addClip(clip(10_000));
        tl.setRippleMode(mode);
        TextOverlayItem before = text(2_000, 4_000);
        TextOverlayItem after = text(14_000, 16_000);
        tl.addTextOverlay(before);
        tl.addTextOverlay(after);
        AudioClip song = new AudioClip(null, 30_000);
        song.setOffsetMs(15_000);
        tl.addAudioClip(song);
        return new Object[]{tl, before, after, song};
    }

    /** The edit: the first clip shortened by 1 s inside a structural step, then [choice]. */
    static Object[] edit(String mode, RippleCheck.Choice choice) {
        Object[] p = project(mode);
        Timeline tl = (Timeline) p[0];
        long totalBefore = tl.getVideoTrackDurationMs();
        long point = tl.getClipStartMs(1);
        Map<String, Long> before = tl.beginStructural();
        tl.getClip(0).setOutPointMs(9_000);
        RippleCheck.Summary s = RippleCheck.summarize(tl, totalBefore, point);
        RippleCheck.finish(tl, before, choice);
        return new Object[]{tl, p[1], p[2], p[3], s};
    }

    public static void main(String[] args) {
        Object[] moved = edit("ripple", RippleCheck.Choice.MOVE);
        RippleCheck.Summary s = (RippleCheck.Summary) moved[4];
        eq(s.changeMs, -1_000, "the check says: 1 s shorter");
        eq(s.layers, 2, "and 2 layers start after the point (the text over the second clip, the song)");
        check(!s.nothingToAsk(), "so there is something to ask");
        eq(((TextOverlayItem) moved[2]).getStartMs(), 13_000, "Move: the text after the point moves with its clip");
        eq(((AudioClip) moved[3]).getOffsetMs(), 14_000, "Move: the song moves too");
        eq(((TextOverlayItem) moved[1]).getStartMs(), 2_000, "the text before the point stays");

        // LEAVE, in a project whose own setting is ripple: this edit leaves them, and the setting stays ripple.
        Object[] left = edit("ripple", RippleCheck.Choice.LEAVE);
        eq(((TextOverlayItem) left[2]).getStartMs(), 14_000, "Leave: the text stays where it was");
        eq(((AudioClip) left[3]).getOffsetMs(), 15_000, "Leave: the song stays");
        check("ripple".equals(((Timeline) left[0]).getRippleMode()), "the answer was for this edit: the project's setting is still ripple");

        // MOVE in a project set to gap: this edit moves them; the project stays gap.
        Object[] gapMoved = edit("gap", RippleCheck.Choice.MOVE);
        eq(((TextOverlayItem) gapMoved[2]).getStartMs(), 13_000, "Move in a gap project: the text moves for this edit");
        check("gap".equals(((Timeline) gapMoved[0]).getRippleMode()), "and the project stays gap");

        // ASK (no screen yet): the project's own setting decides, as today.
        Object[] asked = edit("ripple", RippleCheck.Choice.ASK);
        eq(((TextOverlayItem) asked[2]).getStartMs(), 13_000, "Ask with no screen: today's ripple setting decides");

        // Nothing placed after the point: nothing to ask.
        Timeline bare = new Timeline();
        bare.addClip(clip(10_000));
        bare.addClip(clip(10_000));
        long total = bare.getVideoTrackDurationMs();
        bare.getClip(0).setOutPointMs(9_000);
        check(RippleCheck.summarize(bare, total, bare.getClipStartMs(1)).nothingToAsk(), "no layer after the point: nothing to ask");

        check(RippleCheck.Choice.of("LEAVE") == RippleCheck.Choice.LEAVE, "a stored LEAVE (don't ask again) reads back");
        check(RippleCheck.Choice.of(null) == RippleCheck.Choice.ASK, "nothing stored: ask");
        check(!RippleCheck.ENABLED, "hidden: off until the owner's screen is built");

        // The hook (one place: the editor's endStructuralEdit): it asks after the person's own length edits, never
        // after Undo, Redo or a correction the app makes itself.
        for (String w : new String[]{"trim", "speed", "loopExtend", "loopResize", "loopMode", "delete", "deleteLinkedPair", "silenceCuts"})
            check(RippleCheck.asksAfter(w), "asks after " + w);
        for (String w : new String[]{"undo", "redo", "jumpUndo", "jumpRedo", "durationCorrection", "deleteLinkedPair:revert", "demoteToLayer:declined", "reorder", null})
            check(!RippleCheck.asksAfter(w), "never asks after " + w);

        // Where the edit was: the first clip trimmed, so the second clip moved from its old start, 10 s.
        Timeline three = new Timeline();
        three.addClip(clip(10_000));
        three.addClip(clip(10_000));
        three.addClip(clip(10_000));
        Map<String, Long> starts = three.captureClipStarts();
        long track = three.getVideoTrackDurationMs();
        three.getClip(0).setOutPointMs(8_000);
        eq(RippleCheck.pointOf(starts, three.captureClipStarts(), track), 10_000, "a trimmed first clip: the point is where the second clip started");
        // The middle clip deleted: the third moved from 20 s, but the deleted one went from 10 s.
        Map<String, Long> s2 = three.captureClipStarts();
        long track2 = three.getVideoTrackDurationMs();
        three.removeClip(1);
        eq(RippleCheck.pointOf(s2, three.captureClipStarts(), track2), 8_000, "a deleted clip: the point is where it started");
        // Only the last clip changed: no start moved, so the point is the old end of the clips.
        Map<String, Long> s3 = three.captureClipStarts();
        long track3 = three.getVideoTrackDurationMs();
        three.getClip(1).setOutPointMs(5_000);
        eq(RippleCheck.pointOf(s3, three.captureClipStarts(), track3), track3, "only the last clip changed: the point is the old end");

        System.out.println("RippleCheck: " + pass + " passed, " + fail + " failed");
        System.exit(fail == 0 ? 0 : 1);
    }
}
