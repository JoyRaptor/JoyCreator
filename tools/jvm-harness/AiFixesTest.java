import com.fadcam.ui.faditor.ai.AiEditMath;
import com.fadcam.ui.faditor.ai.EditScript;
import com.fadcam.ui.faditor.ai.EditScriptApplier;
import com.fadcam.ui.faditor.model.Clip;
import com.fadcam.ui.faditor.model.FaditorProject;
import com.fadcam.ui.faditor.model.TextOverlayItem;
import com.fadcam.ui.faditor.model.Timeline;
import com.fadcam.ui.faditor.model.Transition;

import java.util.Arrays;
import java.util.List;

/**
 * The AI review fixes of 2026-11-12 that can be pinned off the phone: B1 (move clip lands where it
 * says), B2 (delete and speed go through the bracketed EditScript path; delete drops the clip's
 * transitions), B3 (source clock to editor timeline time), B5 (an op that changed nothing is not
 * "applied").
 *
 * Usage:  bash tools/jvm-harness/run-aifixes.sh
 */
public class AiFixesTest {
    static int pass = 0, fail = 0;

    static void check(boolean ok, String name) {
        if (ok) { pass++; } else { fail++; System.out.println("FAIL " + name); }
    }

    static void eq(long got, long want, String name) {
        check(got == want, name + "  (got " + got + ", want " + want + ")");
    }

    static Clip clip(long durMs) {
        Clip c = new Clip(null, 600_000);
        c.setInPointMs(0);
        c.setOutPointMs(durMs);
        return c;
    }

    static FaditorProject project(Clip... cs) {
        FaditorProject p = new FaditorProject("t");
        for (Clip c : cs) p.getTimeline().addClip(c);
        return p;
    }

    static EditScriptApplier.Result run(FaditorProject p, String op) throws Exception {
        String json = "{\"version\":1,\"description\":\"t\",\"operations\":[" + op + "]}";
        return new EditScriptApplier().apply(p, EditScript.fromJson(json));
    }

    static List<String> ids(String... s) { return Arrays.asList(s); }

    public static void main(String[] args) throws Exception {
        // ── B1: move_clip_to. Final index semantics: "end" = size-1, "3" = index 2 ────────
        List<String> abcd = ids("a", "b", "c", "d");
        check(AiEditMath.moveId(abcd, 0, 3).equals(ids("b", "c", "d", "a")), "B1 first -> end");
        check(AiEditMath.moveId(abcd, 3, 0).equals(ids("d", "a", "b", "c")), "B1 last -> start");
        check(AiEditMath.moveId(abcd, 0, 2).equals(ids("b", "c", "a", "d")), "B1 forward to middle (position 3)");
        check(AiEditMath.moveId(abcd, 3, 1).equals(ids("a", "d", "b", "c")), "B1 backward to middle (position 2)");
        check(AiEditMath.moveId(abcd, 1, 2).equals(ids("a", "c", "b", "d")), "B1 forward by one");
        check(AiEditMath.moveId(abcd, 2, 1).equals(ids("a", "c", "b", "d")), "B1 backward by one");
        check(AiEditMath.moveId(abcd, 2, 2).equals(abcd), "B1 same place");
        check(AiEditMath.moveId(abcd, 1, 3).get(3).equals("b"), "B1 middle -> end ends last");
        check(AiEditMath.moveId(abcd, 2, 0).get(0).equals("c"), "B1 middle -> start starts first");
        check(abcd.equals(ids("a", "b", "c", "d")), "B1 input list untouched");

        // ── B3: source clock -> timeline clock ────────────────────────────────────────────
        // A clip that starts 10 s into the project, plays its source from 4 s at 1x.
        eq(AiEditMath.sourceToTimelineMs(10_000, 4_000, 1f, 4_000), 10_000, "B3 in point = clip start");
        eq(AiEditMath.sourceToTimelineMs(10_000, 4_000, 1f, 9_500), 15_500, "B3 5.5 s into the clip");
        eq(AiEditMath.sourceToTimelineMs(0, 0, 1f, 7_000), 7_000, "B3 first clip, untrimmed: unchanged");
        eq(AiEditMath.sourceToTimelineMs(10_000, 4_000, 2f, 8_000), 12_000, "B3 2x speed halves the distance");
        eq(AiEditMath.sourceToTimelineMs(10_000, 4_000, 0f, 5_000), 11_000, "B3 bad speed treated as 1x");

        // ── B2: delete_clip as an op: bracketed, transitions of its seams go ─────────────
        Clip c1 = clip(5000), c2 = clip(10000), c3 = clip(4000), c4 = clip(3000);
        FaditorProject p = project(c1, c2, c3, c4);
        Timeline tl = p.getTimeline();
        tl.addTransition(new Transition(Transition.Type.CROSS_DISSOLVE, 500, 0));  // c1|c2
        tl.addTransition(new Transition(Transition.Type.CROSS_DISSOLVE, 500, 1));  // c2|c3
        tl.addTransition(new Transition(Transition.Type.CROSS_DISSOLVE, 500, 2));  // c3|c4
        TextOverlayItem title = new TextOverlayItem("x", 0xFFFFFFFF, 0.5f, 0.5f, 0.1f, 0f);
        title.setTimeRange(16000, 17000);                 // inside c3 (starts at 15000)
        tl.addTextOverlay(title);
        EditScriptApplier.Result r = run(p, "{\"type\":\"DELETE_CLIP\",\"clipId\":\"" + c2.getId() + "\"}");
        check(r.success, "B2 delete succeeds " + r.error);
        eq(r.appliedCount, 1, "B2 delete counted once");
        eq(tl.getClipCount(), 3, "B2 delete: 3 clips left");
        check(tl.getClip(1) == c3, "B2 delete: next clip closed up");
        eq(tl.getTransitions().size(), 1, "B2 delete: the two seams of the clip lost their transitions");
        eq(tl.getTransitions().get(0).clipIndex, 1, "B2 delete: the later transition renumbered (c3|c4 is now seam 1)");
        eq(title.getStartMs(), 6000, "B2 delete: a title behind the footage moved with it (was 16000, 10 s gone)");
        r = run(p, "{\"type\":\"DELETE_CLIP\",\"clipId\":\"nope\"}");
        check(!r.success, "B2 delete of a missing clip is refused");
        eq(tl.getClipCount(), 3, "B2 refused delete changes nothing");

        // ── B2: set_clip_speed through the op: what follows moves with the footage ───────
        Clip s1 = clip(6000), s2 = clip(4000);
        FaditorProject p2 = project(s1, s2);
        TextOverlayItem t2 = new TextOverlayItem("y", 0xFFFFFFFF, 0.5f, 0.5f, 0.1f, 0f);
        t2.setTimeRange(7000, 8000);                      // inside s2 (starts at 6000)
        p2.getTimeline().addTextOverlay(t2);
        r = run(p2, "{\"type\":\"SET_CLIP_SPEED\",\"clipId\":\"" + s1.getId() + "\",\"speed\":2.0}");
        check(r.success, "B2 speed succeeds " + r.error);
        eq(Math.round(s1.getSpeedMultiplier() * 100), 200, "B2 speed set to 2x");
        eq(t2.getStartMs(), 4000, "B2 speed: a title after the clip moved 3 s earlier with the footage");
        r = run(p2, "{\"type\":\"SET_CLIP_SPEED\",\"clipId\":\"" + s1.getId() + "\",\"speed\":8}");
        check(r.success, "B2 speed 8 clamps");
        eq(Math.round(s1.getSpeedMultiplier() * 100), 400, "B2/B9 speed clamps to 4x");

        // ── B5: an op that changed nothing is not "applied" ──────────────────────────────
        FaditorProject p3 = project(clip(5000));
        r = run(p3, "{\"type\":\"ADD_VISUALIZER\",\"presetId\":\"bars\",\"durationMs\":3000,\"audioSourceRef\":\"ghost\"}");
        check(!r.success, "B5 visualizer on a clip that is not there is refused, not applied");
        r = run(p3, "{\"type\":\"ADD_VISUALIZER\",\"presetId\":\"bars\",\"durationMs\":3000}");
        check(r.success && r.appliedCount == 1, "B5 visualizer with no source still lands");

        System.out.println("AiFixesTest: " + pass + " passed, " + fail + " failed");
        System.exit(fail == 0 ? 0 : 1);
    }
}
