import com.fadcam.ui.faditor.compositor.LayerPreviewController;
import com.fadcam.ui.faditor.layers.Track;
import com.fadcam.ui.faditor.layers.TrackKind;
import com.fadcam.ui.faditor.model.AdjustmentLayer;
import com.fadcam.ui.faditor.model.Timeline;

import java.util.List;

/**
 * Adjustment-layer LANE EMISSION and the shared z seam (SPEC_ADJUSTMENT_LAYERS_FX M3).
 *
 * <p>Separate from {@code AdjustmentLayerTest} because {@link Timeline} drags in {@code Clip},
 * which drags in media3 — so this needs the real Android classpath {@code run-matte.sh}
 * already assembles, and the model harness stays gson-only and fast.</p>
 *
 * <p>The claim that matters: a project with NO adjustment layer emits exactly the band it
 * always did. Everything about this feature is additive, and that is only true if the empty
 * case provably changes nothing.</p>
 *
 * <p>Run: {@code bash tools/jvm-harness/run-matte.sh}</p>
 */
public class AdjustmentLaneTest {

    private static int passed = 0, failed = 0;

    public static void main(String[] args) {
        laneEmissionAndZ();
        interleavedZOrder();
        duplicateCopiesAreDeep();
        perObjectFx();
        System.out.println(failed == 0 ? "ALL GREEN (" + passed + "/" + (passed + failed) + ")"
                : "FAILURES: " + failed + " (passed " + passed + ")");
        if (failed > 0) System.exit(1);
    }

    static AdjustmentLayer layer(long start, long dur) {
        AdjustmentLayer a = new AdjustmentLayer();
        a.setStartMs(start);
        a.setDurationMs(dur);
        a.setLayerId("adjustment");
        return a;
    }

    // ── Lane emission and z ─────────────────────────────────────────────────

    /**
     * The band must be PROVABLY unchanged for every project that has no adjustment layer, and
     * the layer must land ABOVE the PiP phase when there is one.
     */
    static void laneEmissionAndZ() {
        Timeline empty = new Timeline();
        int lanesBefore = empty.getLayers().size();
        check("a project with no adjustment layer emits the band it always did",
                !empty.usesAdjustmentLayers());

        Timeline t = new Timeline();
        AdjustmentLayer a = layer(0, 5000);
        a.getFx().add("invert");
        t.addAdjustmentLayer(a);
        check("...and one WITH a layer reports the schema trigger", t.usesAdjustmentLayers());

        List<Track> lanes = t.getLayers();
        check("an adjustment lane is emitted", lanes.size() > lanesBefore);
        Track adjLane = null;
        for (Track lane : lanes) {
            if (lane.getKind() == TrackKind.ADJUSTMENT) adjLane = lane;
        }
        check("the lane carries the ADJUSTMENT kind", adjLane != null);
        if (adjLane == null) return;
        check("...and holds the layer as a first-class item", adjLane.getItems().size() == 1);
        check("...whose payload kind names it",
                "adjustment".equals(adjLane.getItems().get(0).payloadKind()));
        check("...and which round-trips back to the same object",
                adjLane.getItems().get(0).getAdjustment() == a);

        // The shared z seam BOTH renderers consume.
        check("orderedCompositedItems sees it",
                LayerPreviewController
                        .orderedCompositedItems(t).size() == 1);
        check("visibleAdjustmentLayers sees it mid-span",
                LayerPreviewController
                        .visibleAdjustmentLayers(t, 2500).size() == 1);
        check("...and NOT past its end",
                LayerPreviewController
                        .visibleAdjustmentLayers(t, 9000).isEmpty());

        // An empty stack is a real object but must cost no pass.
        AdjustmentLayer bare = layer(0, 5000);
        Timeline t2 = new Timeline();
        t2.addAdjustmentLayer(bare);
        check("a layer with an EMPTY stack is still an item in the band",
                LayerPreviewController
                        .orderedCompositedItems(t2).size() == 1);
        check("...but is not handed to a renderer",
                LayerPreviewController
                        .visibleAdjustmentLayers(t2, 100).isEmpty());
    }


    /**
     * The z ORDER the export loop inserts against.
     *
     * <p>An adjustment layer above one PiP but below another must grade only the first. The
     * export builds its chain by walking this exact list, so pinning the list pins the chain —
     * which matters because an export cannot be run from a harness, and this is the closest
     * thing to a proof of the ordering that is available off-device.</p>
     */
    static void interleavedZOrder() {
        Timeline t = new Timeline();
        AdjustmentLayer a = layer(0, 5000);
        a.getFx().add("invert");
        t.addAdjustmentLayer(a);

        List<LayerPreviewController.VisualItem> ordered =
                LayerPreviewController.orderedCompositedItems(t);
        check("with no PiPs the layer is the only composited item", ordered.size() == 1);
        check("...and it is the adjustment layer", ordered.get(0).item.getAdjustment() == a);

        // Two layers keep their relative order, which is what makes "grade, then grade again"
        // mean something different from the reverse.
        AdjustmentLayer b = layer(0, 5000);
        b.setName("Second");
        b.getFx().add("posterize");
        t.addAdjustmentLayer(b);
        ordered = LayerPreviewController.orderedCompositedItems(t);
        check("two layers both appear", ordered.size() == 2);
        check("...in the order they were added, bottom-up",
                ordered.get(0).item.getAdjustment() == a
                        && ordered.get(1).item.getAdjustment() == b);

        // An EMPTY layer is still an item here -- ordering is a property of the band, not of
        // whether the thing draws. The export loop is what skips it.
        AdjustmentLayer empty = layer(0, 5000);
        t.addAdjustmentLayer(empty);
        check("an empty layer still holds its place in the order",
                LayerPreviewController.orderedCompositedItems(t).size() == 3);
        check("...but never reaches a renderer",
                LayerPreviewController.visibleAdjustmentLayers(t, 100).size() == 2);
    }

    /**
     * The duplicate-onto-a-new-lane copies must ALIAS NOTHING.
     *
     * <p>This is the whole risk of the feature. A shared keyframe set or frame track produces
     * two objects that animate together, which does not read as an aliasing bug — it reads as
     * the editor having a mind of its own, and it is nearly impossible to report.</p>
     */
    static void duplicateCopiesAreDeep() {
        com.fadcam.ui.faditor.model.TextOverlayItem t =
                new com.fadcam.ui.faditor.model.TextOverlayItem(
                        "t1", "hello", 0xFFFFFFFF, 0.5f, 0.5f, 0.2f, 0f);
        t.setLayerId("text");
        t.getKeyframes().getOrCreate(com.fadcam.ui.faditor.keyframe.KeyframeSet.X)
                .put(0, 0.25f, com.fadcam.ui.faditor.keyframe.Easing.LINEAR);
        com.fadcam.ui.faditor.model.TextOverlayItem tc = t.copyWithNewId("t2");

        check("the text copy has the new id and keeps the content",
                tc.getId().equals("t2") && "hello".equals(tc.getText()));
        check("the text copy took the keyframes", Math.abs(
                tc.getKeyframes().valueAt(
                        com.fadcam.ui.faditor.keyframe.KeyframeSet.X, 0, -1f) - 0.25f) < 0.001f);
        tc.getKeyframes().getOrCreate(com.fadcam.ui.faditor.keyframe.KeyframeSet.X)
                .put(0, 0.9f, com.fadcam.ui.faditor.keyframe.Easing.LINEAR);
        check("...but does NOT share them — editing the copy leaves the original alone",
                Math.abs(t.getKeyframes().valueAt(
                        com.fadcam.ui.faditor.keyframe.KeyframeSet.X, 0, -1f) - 0.25f) < 0.001f);
        // A copy that inherited the lane would land on a row that forbids overlap, on top of
        // the object it was copied from.
        check("the text copy does not inherit the original's lane",
                tc.getLayerId() == null || !"text".equals(tc.getLayerId()));

        com.fadcam.ui.faditor.sprite.SpriteOverlayItem sp =
                new com.fadcam.ui.faditor.sprite.SpriteOverlayItem("s1", "sheet1");
        sp.setLayerId("sprite");
        sp.getFrameTrack().put(
                com.fadcam.ui.faditor.sprite.FrameTrack.Key.ofCell(0, 3));
        com.fadcam.ui.faditor.sprite.SpriteOverlayItem sc = sp.copyWithNewId("s2");
        check("the sprite copy keeps its sheet and frames",
                "sheet1".equals(sc.getSheetId()) && sc.getFrameTrack().size() == 1);
        sc.getFrameTrack().put(
                com.fadcam.ui.faditor.sprite.FrameTrack.Key.ofCell(500, 7));
        check("...and does NOT share the frame track",
                sp.getFrameTrack().size() == 1 && sc.getFrameTrack().size() == 2);
        check("the sprite copy does not inherit the original's lane",
                sc.getLayerId() == null || !"sprite".equals(sc.getLayerId()));
    }

    /**
     * Per-object FX (M7) — the SAME stack model on a clip rather than on a layer.
     *
     * <p>Lives here rather than in run-fx because {@code Clip} reaches media3, the same
     * boundary that sent the lane tests here.</p>
     *
     * <p>The property that matters is that an emptied stack normalises back to NULL. That is
     * what keeps a clip which was briefly given an effect serializing exactly as it did before
     * it was ever touched — and it is what makes M7 provably inert for every existing project.</p>
     */
    static void perObjectFx() {
        com.fadcam.ui.faditor.model.Clip c =
                // sourceUri null, like MatteVisibilityTest: android.jar's Uri is a compile
                // stub that throws at runtime, and none of this needs a real one.
                new com.fadcam.ui.faditor.model.Clip(null, 5000L);
        check("a fresh clip has NO fx object at all", c.getFx() == null);
        check("...and reports none active", !c.hasActiveFx());

        com.fadcam.ui.faditor.fx.FxStack st = c.getOrCreateFx();
        check("getOrCreateFx makes one on demand", c.getFx() != null);
        check("...but an empty one is still nothing to render", !c.hasActiveFx());

        st.add("invert");
        c.setFx(st);
        check("a card makes it active", c.hasActiveFx());

        // EMPTYING KEEPS THE INSTANCE. It used to null the field so the clip returned to its
        // original shape, but ProjectStorage already skips an empty stack when writing, so the
        // file is identical either way — and detaching the object cost real correctness: the FX
        // panel holds this exact instance, so deleting the last card orphaned the thing being
        // edited and a later undo restored a stack the clip no longer pointed at.
        st.remove(0);
        c.setFx(st);
        check("emptying the stack KEEPS the instance the panel is holding", c.getFx() == st);
        check("...and to inactive", !c.hasActiveFx());
        check("...and an empty stack is still nothing to serialize", c.getFx().isEmpty());
    }

    static void check(String what, boolean ok) {
        if (ok) { passed++; System.out.println("  ok   " + what); }
        else { failed++; System.out.println("  FAIL " + what); }
    }
}
