import com.fadcam.ui.faditor.model.AdjustmentLayer;
import com.fadcam.ui.faditor.model.Clip;
import com.fadcam.ui.faditor.model.CompositingSpec;
import com.fadcam.ui.faditor.model.TextOverlayItem;
import com.fadcam.ui.faditor.model.Timeline;
import com.fadcam.ui.faditor.sprite.SpriteOverlayItem;

import java.util.HashMap;
import java.util.Map;

/**
 * Slice F — "compact lanes" across EVERY lane family (video/PiP + image, sprites, text,
 * adjustment) with the track-matte exemption: a lane that takes part in a matte pairing —
 * the RECIPIENT (whose {@code CompositingSpec.mattePeerId} is set) and the lane holding the
 * matte PEER — must survive compaction completely undisturbed.
 *
 * <p>Runs against the real model classes, so it needs the real Android classpath
 * {@code run-matte.sh} already assembles (Timeline drags in Clip → media3).</p>
 *
 * <p>Run: {@code bash tools/jvm-harness/run-matte.sh}</p>
 */
public class CompactLaneTest {

    private static int passed = 0, failed = 0;

    private static void check(boolean c, String n) {
        System.out.println((c ? "PASS  " : "FAIL  ") + n);
        if (c) passed++; else failed++;
    }

    private static Clip overlay(String laneId, long startMs) {
        Clip c = new Clip(null, 5000);
        c.setLayerId(laneId);
        c.setOverlayStartMs(startMs);
        return c;
    }

    private static SpriteOverlayItem sprite(String laneId, long startMs, long endMs) {
        SpriteOverlayItem s = new SpriteOverlayItem("s" + startMs, "sheet");
        s.setLayerId(laneId);
        s.setTimeRange(startMs, endMs);
        return s;
    }

    private static TextOverlayItem text(String laneId, long startMs, long endMs) {
        TextOverlayItem t = new TextOverlayItem("t", 0xFF000000, 0.5f, 0.5f, 0.1f, 0f);
        t.setLayerId(laneId);
        t.setTimeRange(startMs, endMs);
        return t;
    }

    private static AdjustmentLayer adjustment(String laneId, long startMs, long durMs) {
        AdjustmentLayer a = new AdjustmentLayer();
        a.setLayerId(laneId);
        a.setStartMs(startMs);
        a.setDurationMs(durMs);
        return a;
    }

    public static void main(String[] args) {
        videoAndImageCompact();
        adjustmentCompact();
        textAndSpriteCompact();
        mattePairIsExempt();
        matteDefaultBucketIsExempt();
        matteCountReportsOnlyInhabitedLanes();
        System.out.println(failed == 0 ? "ALL GREEN (" + passed + "/" + (passed + failed) + ")"
                : "FAILURES: " + failed + " (passed " + passed + ")");
        if (failed > 0) System.exit(1);
    }

    // ── PiP / video / image overlays now compact (the old gap) ─────────────

    static void videoAndImageCompact() {
        Timeline t = new Timeline();
        // Three non-overlapping PiPs scattered across three lanes.
        t.addOverlayClip(overlay("video-a", 0L));
        t.addOverlayClip(overlay("video-b", 6_000L));
        t.addOverlayClip(overlay("video-c", 12_000L));

        Timeline.CompactResult r = t.compactOverlayLanes();
        check(r.moved == 3, "video/PiP: three scattered overlays collapse onto one lane");
        check(r.omittedLanes == 0, "video/PiP: nothing omitted without a matte");

        Map<String, Integer> perLane = new HashMap<>();
        for (Clip oc : t.getOverlayClips()) {
            String id = oc.getLayerId();
            perLane.put(id == null ? "default" : id, perLane.getOrDefault(
                    id == null ? "default" : id, 0) + 1);
        }
        check(perLane.size() == 1, "video/PiP: all three now share a single lane (" + perLane + ")");
    }

    // ── Adjustment layers now compact too ─────────────────────────────────

    static void adjustmentCompact() {
        Timeline t = new Timeline();
        t.addAdjustmentLayer(adjustment("adj-a", 0L, 5_000L));
        t.addAdjustmentLayer(adjustment("adj-b", 8_000L, 5_000L));
        t.addAdjustmentLayer(adjustment("adj-c", 15_000L, 5_000L));

        Timeline.CompactResult r = t.compactOverlayLanes();
        check(r.moved == 3, "adjustment: three scattered layers collapse");
        check(r.omittedLanes == 0, "adjustment: nothing omitted without a matte");

        Map<String, Integer> perLane = new HashMap<>();
        for (AdjustmentLayer a : t.getAdjustmentLayers()) {
            String id = a.getLayerId();
            perLane.put(id, perLane.getOrDefault(id, 0) + 1);
        }
        check(perLane.size() == 1, "adjustment: all three share one lane (" + perLane + ")");
    }

    // ── Text + sprites keep compacting (regression) ──────────────────────

    static void textAndSpriteCompact() {
        Timeline t = new Timeline();
        t.getTextOverlays().add(text("text-a", 0L, 3_000L));
        t.getTextOverlays().add(text("text-b", 9_000L, 3_000L));
        t.getSpriteOverlays().add(sprite("sprite-a", 0L, 4_000L));
        t.getSpriteOverlays().add(sprite("sprite-b", 7_000L, 4_000L));

        Timeline.CompactResult r = t.compactOverlayLanes();
        check(r.moved == 4, "text+sprite: all four collapse (regression)");
        check(r.omittedLanes == 0, "text+sprite: nothing omitted without a matte");

        Map<String, Integer> lanes = new HashMap<>();
        for (TextOverlayItem o : t.getTextOverlays()) {
            String id = o.getLayerId() == null ? "text-default" : o.getLayerId();
            lanes.put(id, lanes.getOrDefault(id, 0) + 1);
        }
        for (SpriteOverlayItem s : t.getSpriteOverlays()) {
            String id = s.getLayerId() == null ? "sprite-default" : s.getLayerId();
            lanes.put(id, lanes.getOrDefault(id, 0) + 1);
        }
        check(lanes.size() == 2, "text+sprite: two default buckets, one lane each (" + lanes + ")");
    }

    // ── A matte pair (recipient + peer) is exempt, undisturbed ─────────────

    static void mattePairIsExempt() {
        Timeline t = new Timeline();
        Clip recipient = overlay("matte-rec", 0L);
        Clip peer = overlay("matte-peer", 0L);
        CompositingSpec cs = new CompositingSpec();
        cs.mattePeerId = peer.getId();
        recipient.setCompositing(cs);
        t.addOverlayClip(recipient);
        t.addOverlayClip(peer);
        // A third, unrelated PiP that CAN move.
        t.addOverlayClip(overlay("video-other", 8_000L));

        Timeline.CompactResult r = t.compactOverlayLanes();
        check(r.moved == 1, "matte pair: only the unrelated clip moves");
        check(r.omittedLanes == 2, "matte pair: recipient lane + peer lane both omitted");
        check("matte-rec".equals(recipient.getLayerId()), "matte pair: recipient lane undisturbed");
        check("matte-peer".equals(peer.getLayerId()), "matte pair: peer lane undisturbed");
    }

    // ── The matte pair sitting on the DEFAULT bucket also blocks it ───────

    static void matteDefaultBucketIsExempt() {
        Timeline t = new Timeline();
        Clip recipient = new Clip(null, 5000);          // layerId null → default video lane
        recipient.setOverlayStartMs(0L);
        Clip peer = new Clip(null, 5000);               // also on the default lane
        peer.setOverlayStartMs(6_000L);
        CompositingSpec cs = new CompositingSpec();
        cs.mattePeerId = peer.getId();
        recipient.setCompositing(cs);
        t.addOverlayClip(recipient);
        t.addOverlayClip(peer);
        // An unrelated clip must NOT be folded onto the exempt default lane.
        t.addOverlayClip(overlay("video-other", 12_000L));

        Timeline.CompactResult r = t.compactOverlayLanes();
        check(r.omittedLanes == 1, "default bucket matte: one lane omitted (the default)");
        check(recipient.getLayerId() == null, "default matte: recipient stays on default lane");
        check(peer.getLayerId() == null, "default matte: peer stays on default lane");
        check(t.getOverlayClips().get(2).getLayerId() != null,
                "default matte: unrelated clip NOT folded onto the exempt default lane");
    }

    // ── The omitted count reflects lanes that actually carry items ─────────

    static void matteCountReportsOnlyInhabitedLanes() {
        Timeline t = new Timeline();
        Clip recipient = overlay("lane-x", 0L);
        CompositingSpec cs = new CompositingSpec();
        cs.mattePeerId = "a-missing-peer";
        recipient.setCompositing(cs);
        t.addOverlayClip(recipient);
        // The dangling peer id resolves to nothing: only the recipient lane is exempt.
        Timeline.CompactResult r = t.compactOverlayLanes();
        check(r.omittedLanes == 1, "dangling matte id: only the recipient lane is omitted");
    }
}
