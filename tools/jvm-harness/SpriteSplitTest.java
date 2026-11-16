import com.fadcam.ui.faditor.avatar.AvatarParamTrack;
import com.fadcam.ui.faditor.sprite.FrameTrack;
import com.fadcam.ui.faditor.sprite.SequenceTiming;
import com.fadcam.ui.faditor.sprite.SpriteFrameResolver;
import com.fadcam.ui.faditor.sprite.SpriteOverlayItem;
import com.fadcam.ui.faditor.sprite.SpriteSheet;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Codex audit 11-15, STUDIO-01 and STUDIO-02: splitting an animated sprite must not change the
 * animation, and a duplicate (or a split's right half) must keep the recorded puppet motion.
 *
 * <p>The split is modelled exactly as the editor does it: {@code copyWithNewId}, then the right
 * half's {@code setTimeRange(cut, end)}, then {@link SpriteOverlayItem#carryOnFrom}. The oracle
 * is the ORIGINAL item: every millisecond after the cut must show the same cell (and the same
 * pose) on the right half as the uncut item showed there.</p>
 *
 * <p>Positive control: the same split WITHOUT {@code carryOnFrom} (the code before the fix) must
 * disagree with the oracle, so this test is proven to catch the bug it guards.</p>
 *
 * <p>Run: {@code bash tools/jvm-harness/run-spritesplit.sh}</p>
 */
public class SpriteSplitTest {

    private static int passed = 0, failed = 0;

    public static void main(String[] args) {
        for (String type : new String[]{"loop", "pingpong", "once"}) {
            for (String end : new String[]{"hold", "loop", "pingpong"}) {
                splitKeepsTheAnimation(type, end, 0, 12f, w(1, 4, 1, 2, 1), 777);
                splitKeepsTheAnimation(type, end, 2, 8f, w(1, 1, 3), 1300);
                splitKeepsTheAnimation(type, end, 0, 24f, w(1, 1, 1, 1, 1, 1), 2041);
            }
        }
        splitOfAMixedTrackKeepsEveryEntry();
        splitBeforeTheFirstEntryKeepsTheLeadIn();
        splitTwiceStillMatches();
        tapeMarksMatchAfterASplit();
        oldSplitWasWrong();
        duplicateKeepsAnIndependentPerformance();
        splitKeepsThePerformanceOnBothSides();
        keyCopyKeepsThePhase();

        System.out.println("SpriteSplitTest: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    static void check(String name, boolean ok) {
        if (ok) passed++;
        else { failed++; System.out.println("FAIL: " + name); }
    }

    static List<Integer> w(int... v) {
        List<Integer> out = new ArrayList<>();
        for (int x : v) out.add(x);
        return out;
    }

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

    static final long START = 500, END = 9500;

    static SpriteOverlayItem item(SpriteSheet s, String endBehavior, int startFrame) {
        SpriteOverlayItem it = SpriteOverlayItem.create(s.getId());
        it.setTimeRange(START, END);
        it.setEndBehavior(endBehavior);
        it.setSequenceStartFrame(startFrame);
        it.getFrameTrack().put(FrameTrack.Key.ofPreset(0, SpriteSheet.SEQUENCE_PRESET_ID));
        return it;
    }

    /** The editor's split, right half only (the left half just gets a shorter range). */
    static SpriteOverlayItem rightHalf(SpriteOverlayItem s, long cut, boolean withFix) {
        SpriteOverlayItem r = s.copyWithNewId("right");
        long start = s.getStartMs();
        r.setTimeRange(cut, s.getEndMs());
        if (withFix) r.carryOnFrom(cut - start);
        return r;
    }

    /** First time in [cut, END] where the right half disagrees with the uncut item, or -1. */
    static long firstMismatch(SpriteSheet sheet, SpriteOverlayItem whole,
                              SpriteOverlayItem right, long cut) {
        for (long t = cut; t <= END; t++) {
            int a = SpriteFrameResolver.resolveCellAt(sheet, whole, t);
            int b = SpriteFrameResolver.resolveCellAt(sheet, right, t);
            if (a != b) return t;
        }
        return -1;
    }

    // ── STUDIO-01: frame timing ─────────────────────────────────────────────────

    static void splitKeepsTheAnimation(String type, String end, int startFrame, float fps,
                                       List<Integer> weights, long cut) {
        SpriteSheet sheet = seq(weights.size(), fps, weights, type);
        SpriteOverlayItem whole = item(sheet, end, startFrame);
        SpriteOverlayItem right = rightHalf(whole, cut, true);
        long bad = firstMismatch(sheet, whole, right, cut);
        check("split keeps the animation: " + type + "/" + end + " fps " + fps + " start "
                + startFrame + " cut " + cut + (bad >= 0 ? " (differs at " + bad + ")" : ""),
                bad < 0);
    }

    static void splitOfAMixedTrackKeepsEveryEntry() {
        SpriteSheet sheet = seq(6, 10f, w(1, 2, 1, 1, 3, 1), "loop");
        SpriteSheet.Preset walk = new SpriteSheet.Preset("walk", "walk");
        walk.type = "pingpong";
        walk.frames.addAll(Arrays.asList(5, 4, 3));
        walk.weights.addAll(w(2, 1, 1));
        walk.fps = 6f;
        sheet.getPresets().add(walk);
        SpriteOverlayItem whole = item(sheet, "loop", 0);
        whole.getFrameTrack().put(FrameTrack.Key.ofCell(1500, 2));
        whole.getFrameTrack().put(FrameTrack.Key.ofPreset(2600, "walk"));
        whole.getFrameTrack().put(FrameTrack.Key.ofCell(5000, 1));
        whole.getFrameTrack().put(FrameTrack.Key.ofPreset(6100, SpriteSheet.SEQUENCE_PRESET_ID));
        for (long cut : new long[]{900, 2000, 2600, 3333, 5555, 7000}) {
            SpriteOverlayItem right = rightHalf(whole, START + cut, true);
            long bad = firstMismatch(sheet, whole, right, START + cut);
            check("mixed track split at local " + cut
                    + (bad >= 0 ? " (differs at " + bad + ")" : ""), bad < 0);
        }
    }

    static void splitBeforeTheFirstEntryKeepsTheLeadIn() {
        SpriteSheet sheet = seq(4, 8f, w(1, 1, 1, 1), "loop");
        SpriteOverlayItem whole = SpriteOverlayItem.create(sheet.getId());
        whole.setTimeRange(START, END);
        whole.getFrameTrack().put(FrameTrack.Key.ofPreset(1200, SpriteSheet.SEQUENCE_PRESET_ID));
        SpriteOverlayItem right = rightHalf(whole, START + 400, true);
        check("cut before the first entry", firstMismatch(sheet, whole, right, START + 400) < 0);
    }

    static void splitTwiceStillMatches() {
        SpriteSheet sheet = seq(5, 12f, w(1, 3, 1, 2, 1), "pingpong");
        SpriteOverlayItem whole = item(sheet, "pingpong", 1);
        SpriteOverlayItem r1 = rightHalf(whole, 1777, true);
        SpriteOverlayItem r2 = rightHalf(r1, 4321, true);
        check("a split of a split", firstMismatch(sheet, whole, r2, 4321) < 0);
    }

    static void tapeMarksMatchAfterASplit() {
        SpriteSheet sheet = seq(5, 12f, w(1, 4, 1, 2, 1), "loop");
        SpriteOverlayItem whole = item(sheet, "loop", 0);
        long cut = 1111, d = cut - START;
        SpriteOverlayItem right = rightHalf(whole, cut, true);
        List<SpriteFrameResolver.FrameChange> a =
                SpriteFrameResolver.frameChangesIn(sheet, whole, d, d + 3000, 500);
        List<SpriteFrameResolver.FrameChange> b =
                SpriteFrameResolver.frameChangesIn(sheet, right, 0, 3000, 500);
        // The right half's first mark is the picture already showing at the cut, at 0.
        check("tape: first mark at the cut", !b.isEmpty() && b.get(0).localMs == 0
                && b.get(0).cellIndex == SpriteFrameResolver.resolveCellAt(sheet, whole, cut));
        boolean same = true;
        int j = 1;
        for (SpriteFrameResolver.FrameChange c : a) {
            if (c.localMs == d) continue; // a change exactly at the cut is b's first mark
            if (j >= b.size() || b.get(j).localMs != c.localMs - d
                    || b.get(j).cellIndex != c.cellIndex) { same = false; break; }
            j++;
        }
        check("tape: every later mark lands where it did before the cut", same);
        // And every mark resolves to its own cell (the tape tells the truth).
        boolean truthful = true;
        for (SpriteFrameResolver.FrameChange c : b) {
            if (SpriteFrameResolver.resolveCellAt(sheet, right, cut + c.localMs) != c.cellIndex) {
                truthful = false;
            }
        }
        check("tape: right half marks resolve to their own cells", truthful);
    }

    static void oldSplitWasWrong() {
        SpriteSheet sheet = seq(5, 12f, w(1, 4, 1, 2, 1), "loop");
        SpriteOverlayItem whole = item(sheet, "loop", 0);
        SpriteOverlayItem old = rightHalf(whole, 777, false);
        check("control: the old split restarts the animation (the bug is detectable)",
                firstMismatch(sheet, whole, old, 777) >= 0);
    }

    // ── STUDIO-02: recorded puppet motion ───────────────────────────────────────

    static AvatarParamTrack performance() {
        AvatarParamTrack t = new AvatarParamTrack();
        for (int i = 0; i <= 40; i++) {
            Map<String, Float> p = new HashMap<>();
            p.put("headYaw", (float) Math.sin(i * 0.3));
            p.put("mouthOpen", (i % 7) / 7f);
            t.add(i * 100L, p);
        }
        return t;
    }

    static boolean near(Map<String, Float> a, Map<String, Float> b) {
        if (!a.keySet().equals(b.keySet())) return false;
        for (String k : a.keySet()) if (Math.abs(a.get(k) - b.get(k)) > 1e-4f) return false;
        return true;
    }

    static void duplicateKeepsAnIndependentPerformance() {
        SpriteOverlayItem a = SpriteOverlayItem.create("sheet");
        a.setAvatarRigId("rig");
        a.setAvatarTrack(performance());
        SpriteOverlayItem b = a.copyWithNewId("dup");
        check("duplicate keeps the recorded motion", b.getAvatarTrack() != null
                && b.getAvatarTrack().size() == a.getAvatarTrack().size()
                && b.hasAvatarPerformance() == a.hasAvatarPerformance());
        check("duplicate's motion is its own object", b.getAvatarTrack() != a.getAvatarTrack());
        Map<String, Float> extra = new HashMap<>();
        extra.put("headYaw", 9f);
        b.getAvatarTrack().add(99999, extra);
        check("changing the duplicate's motion leaves the original alone",
                a.getAvatarTrack().size() == 41);
    }

    static void splitKeepsThePerformanceOnBothSides() {
        SpriteOverlayItem a = SpriteOverlayItem.create("sheet");
        a.setTimeRange(START, END);
        a.setAvatarRigId("rig");
        a.setAvatarTrack(performance());
        long cut = START + 1234;
        SpriteOverlayItem r = rightHalf(a, cut, true);
        boolean ok = r.getAvatarTrack() != null;
        for (long t = cut; ok && t <= START + 4200; t += 7) {
            ok = near(a.getAvatarTrack().sampleAt(a.toLocalMs(t)),
                    r.getAvatarTrack().sampleAt(r.toLocalMs(t)));
        }
        check("split's right half carries on the performance from the cut", ok);
        check("split leaves the left half's performance whole", a.getAvatarTrack().size() == 41);
    }

    static void keyCopyKeepsThePhase() {
        FrameTrack.Key k = FrameTrack.Key.ofPreset(40, "p");
        k.phaseMs = 333;
        FrameTrack.Key c = k.copy();
        check("Key.copy keeps time, preset and phase",
                c != k && c.timeMs == 40 && "p".equals(c.presetId) && c.phaseMs == 333);
        check("a cell key has no phase", FrameTrack.Key.ofCell(5, 2).copy().phaseMs == 0);
    }
}
