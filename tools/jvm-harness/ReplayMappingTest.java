import com.fadcam.ui.faditor.avatar.AvatarParamTrack;
import com.fadcam.ui.faditor.sprite.SpriteOverlayItem;

import java.util.HashMap;
import java.util.Map;

/**
 * JVM harness for the bake-to-keyframes REPLAY TIME MAPPING: the contract that
 * preview (SpriteOverlayView) and export (CompositeExportOverlay) both sample a
 * placed avatar item's AvatarParamTrack at max(0, item.toLocalMs(timelineMs)) —
 * absolute timeline time → item-local track time. Track sampling determinism is
 * ParamTrackTest's job; this covers the mapping layer end-to-end plus the
 * hasAvatarPerformance render gate. Run like the other harnesses (javac vs the
 * app's built classes + gson + annotation-jvm; see Opencode-work.md TASK 2).
 */
public class ReplayMappingTest {

    private static int passed = 0, failed = 0;

    public static void main(String[] args) {
        localMapping();
        clampBeforeStart();
        endToEndSampleThroughItemTime();
        openEndedAndDegenerateRange();
        performanceGate();
        System.out.println(failed == 0 ? "ALL GREEN (" + passed + "/" + (passed + failed) + ")"
                : "FAILURES: " + failed + " (passed " + passed + ")");
        if (failed > 0) System.exit(1);
    }

    /** timelineMs → itemLocalMs is a pure shift by startMs. */
    static void localMapping() {
        SpriteOverlayItem item = new SpriteOverlayItem("i1", "sheet");
        item.setTimeRange(2000, 10_000);
        check("map: at start → 0", item.toLocalMs(2000) == 0);
        check("map: mid → shifted", item.toLocalMs(5500) == 3500);
        check("map: at end inclusive", item.isVisibleAt(10_000)
                && item.toLocalMs(10_000) == 8000);
        check("map: past end not visible", !item.isVisibleAt(10_001));
    }

    /** Consumers clamp with max(0, ·): a playhead before the item's start must
     *  read the take's first sample, never a negative track time. */
    static void clampBeforeStart() {
        SpriteOverlayItem item = new SpriteOverlayItem("i2", "sheet");
        item.setTimeRange(3000, Long.MAX_VALUE);
        long raw = item.toLocalMs(1000);
        check("clamp: raw is negative", raw == -2000);
        check("clamp: consumer clamp lands at 0", Math.max(0, raw) == 0);
    }

    /** Absolute time → local time → track sample, the exact consumer chain. */
    static void endToEndSampleThroughItemTime() {
        SpriteOverlayItem item = new SpriteOverlayItem("i3", "sheet");
        item.setTimeRange(4000, Long.MAX_VALUE);
        AvatarParamTrack t = new AvatarParamTrack();
        t.add(0, map("yaw", -1f));
        t.add(1000, map("yaw", 1f));
        item.setAvatarRigId("rig-a");
        item.setAvatarTrack(t);

        // Playhead at absolute 4500 = item-local 500 = the lerp midpoint.
        long local = Math.max(0, item.toLocalMs(4500));
        Map<String, Float> mid = item.getAvatarTrack().sampleAt(local);
        check("e2e: midpoint lerp through item time", near(mid.get("yaw"), 0f));
        // Before the item: clamped to the first sample (hold, not a fade-in).
        Map<String, Float> pre = item.getAvatarTrack()
                .sampleAt(Math.max(0, item.toLocalMs(1000)));
        check("e2e: before start holds first sample", near(pre.get("yaw"), -1f));
        // Way after the take: clamped to the last sample (hold-don't-fade).
        Map<String, Float> post = item.getAvatarTrack()
                .sampleAt(Math.max(0, item.toLocalMs(60_000)));
        check("e2e: after take holds last sample", near(post.get("yaw"), 1f));
    }

    /** The degenerate-range guard must keep replay time mapping sane. */
    static void openEndedAndDegenerateRange() {
        SpriteOverlayItem item = new SpriteOverlayItem("i4", "sheet");
        item.setTimeRange(5000, 5000); // end ≤ start → coerced open-ended
        check("range: degenerate coerces open-ended", item.getEndMs() == Long.MAX_VALUE);
        check("range: still visible late", item.isVisibleAt(99_000));
        check("range: mapping unaffected", item.toLocalMs(6000) == 1000);
        item.setTimeRange(-500, 2000); // negative start clamps to 0
        check("range: negative start clamps", item.getStartMs() == 0
                && item.toLocalMs(700) == 700);
    }

    /** hasAvatarPerformance = rigId AND a non-empty track — the render gate
     *  both consumers key the puppet-vs-static-PNG branch on. */
    static void performanceGate() {
        SpriteOverlayItem item = new SpriteOverlayItem("i5", "sheet");
        check("gate: plain sprite off", !item.hasAvatarPerformance());
        item.setAvatarRigId("rig-b");
        check("gate: rig alone off (no take)", !item.hasAvatarPerformance());
        item.setAvatarTrack(new AvatarParamTrack());
        check("gate: empty take off", !item.hasAvatarPerformance());
        item.getAvatarTrack().add(0, map("yaw", 0.5f));
        check("gate: rig + samples on", item.hasAvatarPerformance());
        item.setAvatarRigId(null);
        check("gate: rig unlink turns it off", !item.hasAvatarPerformance());
        item.setAvatarRigId("rig-b");
        item.setAvatarTrack(null);
        check("gate: track cleared turns it off", !item.hasAvatarPerformance());
    }

    // ── helpers (ParamTrackTest idiom) ────────────────────────────────────

    static Map<String, Float> map(Object... kv) {
        Map<String, Float> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], (Float) kv[i + 1]);
        return m;
    }

    static boolean near(Float actual, float expected) {
        return actual != null && Math.abs(actual - expected) < 1e-4f;
    }

    static void check(String name, boolean ok) {
        if (ok) { passed++; System.out.println("  ✓ " + name); }
        else { failed++; System.out.println("  ✗ FAIL " + name); }
    }
}
