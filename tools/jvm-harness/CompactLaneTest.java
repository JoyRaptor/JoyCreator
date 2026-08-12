import com.fadcam.ui.faditor.model.AdjustmentLayer;
import com.fadcam.ui.faditor.model.Clip;
import com.fadcam.ui.faditor.model.CompositingSpec;
import com.fadcam.ui.faditor.model.TextOverlayItem;
import com.fadcam.ui.faditor.layers.TrackKind;
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
        emptyLaneIsRemoved();
        twoLanesCollapseToOne();
        adjacentStackedItemsGainNoLane();
        zOrderSurvivesCompaction();
        compactingTwiceIsANoOp();
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
        // Two move: the bottom lane the family already occupies is KEPT (it carries that lane's
        // name/flags), so only the clips above it change lane. The old packer minted a brand-new
        // id for every lane instead, which is how "compact" used to hand back MORE rows.
        check(r.moved == 2, "video/PiP: three scattered overlays collapse onto one lane");
        check(r.omittedLanes == 0, "video/PiP: nothing omitted without a matte");
        check("video-a".equals(t.getOverlayClips().get(0).getLayerId()),
                "video/PiP: the surviving lane is an EXISTING id, not a minted one");

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
        check(r.moved == 2, "adjustment: three scattered layers collapse onto the lowest lane");
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
        check(r.moved == 2, "text+sprite: both families collapse to one lane each (regression)");
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
        check(lanes.size() == 2, "text+sprite: one surviving lane per family (" + lanes + ")");
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
        // The one clip that MAY move is already alone on the only non-exempt lane, so the honest
        // answer is "nothing to do" — the old packer reported a move because it renamed the lane.
        check(r.moved == 0, "matte pair: the unrelated clip is already on the only free lane");
        check("video-other".equals(t.getOverlayClips().get(2).getLayerId()),
                "matte pair: the unrelated clip keeps its own lane id");
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

    // ══ JoyRaptor 2026-08-12: the three symptoms of "compact did the opposite" ══════════

    /** SYMPTOM (a): "there are several lanes that have literally no items". */
    static void emptyLaneIsRemoved() {
        Timeline t = new Timeline();
        String emptyId = t.createLayerTrack(TrackKind.LAYER, "Nothing here");
        String usedId = t.createLayerTrack(TrackKind.LAYER, "Has a text");
        t.getTextOverlays().add(text(usedId, 0L, 3_000L));

        Timeline.CompactResult r = t.compactOverlayLanes();
        check(r.removedLanes.size() == 1, "empty lane: exactly one definition removed");
        check(emptyId.equals(r.removedLanes.get(0).def.getId()),
                "empty lane: the removed definition is the EMPTY one");
        check(t.getLayerTrackDef(emptyId) == null, "empty lane: definition is gone");
        check(t.getLayerTrackDef(usedId) != null, "empty lane: the inhabited lane survives");
        check(laneIds(t).size() == 1, "empty lane: one row left (" + laneIds(t) + ")");
    }

    /** SYMPTOM (b), positive half: two items that CAN share a lane end up sharing one. */
    static void twoLanesCollapseToOne() {
        Timeline t = new Timeline();
        TextOverlayItem first = text("lane-lo", 0L, 3_000L);
        TextOverlayItem second = text("lane-hi", 5_000L, 3_000L);
        t.getTextOverlays().add(first);
        t.getTextOverlays().add(second);

        Timeline.CompactResult r = t.compactOverlayLanes();
        check(r.moved == 1, "collapse: the upper item moves down");
        check("lane-lo".equals(first.getLayerId()) && "lane-lo".equals(second.getLayerId()),
                "collapse: both now sit on the lower lane");
        check(laneIds(t).size() == 1, "collapse: two rows became one (" + laneIds(t) + ")");
    }

    /**
     * SYMPTOM (b), the reported half: "there was a few stacked items, cleanly one over another,
     * that now have a gap — a lane put in between them". Two items that OVERLAP in time need two
     * lanes and are ALREADY on two adjacent lanes: compaction must be a complete no-op.
     */
    static void adjacentStackedItemsGainNoLane() {
        Timeline t = new Timeline();
        TextOverlayItem lower = text("stack-lo", 0L, 6_000L);
        TextOverlayItem upper = text("stack-hi", 2_000L, 6_000L); // overlaps [2000,6000)
        t.getTextOverlays().add(lower);
        t.getTextOverlays().add(upper);
        int rowsBefore = laneIds(t).size();

        Timeline.CompactResult r = t.compactOverlayLanes();
        check(r.moved == 0, "stacked: nothing moves — the two lanes are already minimal");
        check(r.removedLanes.isEmpty(), "stacked: no lane definition removed");
        check("stack-lo".equals(lower.getLayerId()) && "stack-hi".equals(upper.getLayerId()),
                "stacked: both items keep their own lane id");
        check(laneIds(t).size() == rowsBefore && rowsBefore == 2,
                "stacked: still exactly two rows, no lane inserted (" + laneIds(t) + ")");
    }

    /**
     * Paint order survives. The lower-z item starts LATER, which is exactly the shape that made the
     * old start-order greedy invert the stack: it packed whichever item started first onto the
     * bottom lane. Here the third, non-overlapping item still compacts down while the overlapping
     * pair keeps its order.
     */
    static void zOrderSurvivesCompaction() {
        Timeline t = new Timeline();
        TextOverlayItem top = text("z-top", 0L, 6_000L);
        TextOverlayItem bottom = text("z-bottom", 3_000L, 6_000L); // overlaps top on [3000,6000)
        TextOverlayItem late = text("z-late", 20_000L, 2_000L);
        t.getTextOverlays().add(top);
        t.getTextOverlays().add(bottom);
        t.getTextOverlays().add(late);
        t.getOrCreateTrackFlags("z-bottom").zIndex = 0;
        t.getOrCreateTrackFlags("z-top").zIndex = 10;
        t.getOrCreateTrackFlags("z-late").zIndex = 5;
        java.util.List<TextOverlayItem> before = paintOrder(t);

        Timeline.CompactResult r = t.compactOverlayLanes();
        check(r.moved > 0, "z-order: the non-overlapping item still compacts (" + r.moved + ")");
        check(before.equals(paintOrder(t)), "z-order: bottom→top paint order is unchanged");
        check(laneIds(t).size() == 2, "z-order: three lanes became two (" + laneIds(t) + ")");
    }

    /** Compaction is a fixed point: running it again finds nothing left to do. */
    static void compactingTwiceIsANoOp() {
        Timeline t = new Timeline();
        t.addOverlayClip(overlay("video-a", 0L));
        t.addOverlayClip(overlay("video-b", 6_000L));
        t.getTextOverlays().add(text("text-a", 0L, 3_000L));
        t.getTextOverlays().add(text("text-b", 1_000L, 3_000L)); // overlaps — needs its own lane

        t.compactOverlayLanes();
        Timeline.CompactResult again = t.compactOverlayLanes();
        check(again.moved == 0 && again.removedLanes.isEmpty(),
                "idempotent: a second compaction moves nothing and removes nothing");
    }

    // ── helpers ────────────────────────────────────────────────────────────

    /** Every floating row id, in the band's emission order. */
    private static java.util.List<String> laneIds(Timeline t) {
        java.util.List<String> ids = new java.util.ArrayList<>();
        for (com.fadcam.ui.faditor.layers.Track track : t.getLayers()) ids.add(track.getId());
        return ids;
    }

    /** The text overlays in the compositor's bottom→top paint order — the z that actually ships. */
    private static java.util.List<TextOverlayItem> paintOrder(Timeline t) {
        java.util.List<TextOverlayItem> out = new java.util.ArrayList<>();
        for (com.fadcam.ui.faditor.compositor.LayerPreviewController.VisualItem v
                : com.fadcam.ui.faditor.compositor.LayerPreviewController.orderedVisualItems(t)) {
            if (v.item.getTextOverlay() != null) out.add(v.item.getTextOverlay());
        }
        return out;
    }
}
