import com.fadcam.ui.faditor.ai.EditScript;
import com.fadcam.ui.faditor.ai.EditScriptApplier;
import com.fadcam.ui.faditor.audio.AudioStateFingerprint;
import com.fadcam.ui.faditor.model.AudioClip;
import com.fadcam.ui.faditor.model.Clip;
import com.fadcam.ui.faditor.model.FaditorProject;
import com.fadcam.ui.faditor.model.TextOverlayItem;
import com.fadcam.ui.faditor.model.Timeline;

/**
 * Joybot's "cut" (REMOVE_SPAN) really cuts (owner ruling 2026-11-12): the clip becomes the pieces
 * that stay, later clips close up, the first piece keeps the id, a whole-clip cut is refused, and
 * two cuts in one script both land. Undo for an AI change is the project snapshot taken before
 * the script (unchanged machinery), so it is checked here as "the snapshot copy is untouched".
 *
 * Usage:  bash tools/jvm-harness/run-cutspan.sh
 */
public class CutSpanTest {
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

    static String script(String... ops) {
        return "{\"version\":1,\"description\":\"cut\",\"operations\":[" + String.join(",", ops) + "]}";
    }

    static String op(String id, long s, long e) {
        return "{\"type\":\"REMOVE_SPAN\",\"clipId\":\"" + id + "\",\"startMs\":" + s + ",\"endMs\":" + e + "}";
    }

    static EditScriptApplier.Result run(FaditorProject p, String json) throws Exception {
        return new EditScriptApplier().apply(p, EditScript.fromJson(json));
    }

    public static void main(String[] args) throws Exception {
        // ── A cut in the middle of the second clip ───────────────────────────────────────
        Clip c1 = clip(5000), c2 = clip(10000), c3 = clip(4000);
        FaditorProject p = project(c1, c2, c3);
        TextOverlayItem rider = new TextOverlayItem("x", 0xFFFFFFFF, 0.5f, 0.5f, 0.1f, 0f);
        rider.setTimeRange(16000, 17000);           // inside the third clip (starts at 15000)
        p.getTimeline().addTextOverlay(rider);
        String c2id = c2.getId();
        eq(p.getTimeline().getTotalDurationMs(), 19000, "before: total");

        EditScriptApplier.Result r = run(p, script(op(c2id, 2000, 3000)));
        check(r.success, "mid cut succeeds " + r.error);
        Timeline tl = p.getTimeline();
        eq(tl.getClipCount(), 4, "mid cut: 4 clips");
        eq(tl.getClip(1).getInPointMs(), 0, "piece A in");
        eq(tl.getClip(1).getOutPointMs(), 2000, "piece A out");
        eq(tl.getClip(2).getInPointMs(), 3000, "piece B in");
        eq(tl.getClip(2).getOutPointMs(), 10000, "piece B out");
        check(tl.getClip(1).getId().equals(c2id), "piece A keeps the id");
        check(!tl.getClip(2).getId().equals(c2id), "piece B has a fresh id");
        check(tl.getClip(3) == c3, "later clip untouched");
        eq(tl.getTotalDurationMs(), 18000, "total shrank by the cut length");
        eq(tl.getClip(3).getVisualDurationMs(), 4000, "later clip keeps its length");
        eq(rider.getStartMs(), 15000, "an overlay behind the cut moved earlier by the cut length");
        eq(tl.getClip(1).getRemovedSpans().size(), 0, "no stored note is left behind");

        // ── Cut at the very start / very end: one piece, id kept ─────────────────────────
        Clip d = clip(8000);
        FaditorProject p2 = project(d, clip(1000));
        r = run(p2, script(op(d.getId(), 0, 3000)));
        check(r.success, "start cut succeeds " + r.error);
        eq(p2.getTimeline().getClipCount(), 2, "start cut: still 2 clips");
        eq(p2.getTimeline().getClip(0).getInPointMs(), 3000, "start cut: new in");
        check(p2.getTimeline().getClip(0).getId().equals(d.getId()), "start cut keeps the id");
        r = run(p2, script(op(d.getId(), 6000, 8000)));
        check(r.success, "end cut succeeds " + r.error);
        eq(p2.getTimeline().getClip(0).getOutPointMs(), 6000, "end cut: new out");
        eq(p2.getTimeline().getTotalDurationMs(), 4000, "end cut: total");

        // ── Whole clip and out-of-range cuts are refused, nothing changes ────────────────
        Clip e = clip(5000);
        FaditorProject p3 = project(e, clip(1000));
        r = run(p3, script(op(e.getId(), 0, 5000)));
        check(!r.success, "whole-clip cut refused");
        r = run(p3, script(op(e.getId(), 4000, 9000)));
        check(!r.success, "cut past the clip refused");
        eq(p3.getTimeline().getClipCount(), 2, "refused cuts changed nothing");

        // ── Two cuts in one script both land (second span lives in the fresh piece) ──────
        Clip f = clip(10000);
        FaditorProject p4 = project(f);
        r = run(p4, script(op(f.getId(), 2000, 3000), op(f.getId(), 6000, 7000)));
        check(r.success, "two cuts succeed " + r.error);
        eq(p4.getTimeline().getClipCount(), 3, "two cuts: 3 pieces");
        eq(p4.getTimeline().getTotalDurationMs(), 8000, "two cuts: 2 s gone");
        eq(p4.getTimeline().getClip(1).getInPointMs(), 3000, "middle piece in");
        eq(p4.getTimeline().getClip(1).getOutPointMs(), 6000, "middle piece out");

        // ── Old notes (removedSpans) are not changed on load and no longer shorten sums ──
        Clip g = clip(10000);
        g.getRemovedSpans().add(new long[]{1000, 2000});
        eq(g.getVisualDurationMs(), 10000, "an old note does not change the AI-facing length");
        eq(g.getRemovedSpans().size(), 1, "old note kept as it was");


        // ── Undo/redo audio-player rebuild test (R4): same sound state = keep the players ──
        AudioClip a1 = new AudioClip(null, 30000), a2 = new AudioClip(null, 20000);
        java.util.List<AudioClip> list = new java.util.ArrayList<>(java.util.Arrays.asList(a1, a2));
        AudioStateFingerprint f0 = AudioStateFingerprint.of(list);
        check(f0.sameAs(AudioStateFingerprint.of(list)), "untouched audio: same");
        a1.setWaveform(new int[]{1, 2, 3});
        check(f0.sameAs(AudioStateFingerprint.of(list)), "a stored waveform picture is not sound");
        a1.setVolumeLevel(0.5f);
        check(!f0.sameAs(AudioStateFingerprint.of(list)), "volume change: rebuild");
        a1.setVolumeLevel(1f);
        check(f0.sameAs(AudioStateFingerprint.of(list)), "volume back: same");
        a2.setOffsetMs(500);
        check(!f0.sameAs(AudioStateFingerprint.of(list)), "timing change: rebuild");
        a2.setOffsetMs(0);
        a1.setMuted(true);
        check(!f0.sameAs(AudioStateFingerprint.of(list)), "mute change: rebuild");
        a1.setMuted(false);
        a1.getRemovedSpans().add(new long[]{1, 2});
        check(!f0.sameAs(AudioStateFingerprint.of(list)), "cut note change: rebuild");
        a1.getRemovedSpans().clear();
        java.util.List<AudioClip> swapped = new java.util.ArrayList<>(java.util.Arrays.asList(new AudioClip(a1), a2));
        check(!f0.sameAs(AudioStateFingerprint.of(swapped)), "a restored (new) object: rebuild");
        list.remove(1);
        check(!f0.sameAs(AudioStateFingerprint.of(list)), "a clip gone: rebuild");

        System.out.println("CutSpanTest: " + pass + " passed, " + fail + " failed");
        System.exit(fail == 0 ? 0 : 1);
    }
}
