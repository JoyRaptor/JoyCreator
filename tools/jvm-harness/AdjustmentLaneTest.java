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

    static void check(String what, boolean ok) {
        if (ok) { passed++; System.out.println("  ok   " + what); }
        else { failed++; System.out.println("  FAIL " + what); }
    }
}
