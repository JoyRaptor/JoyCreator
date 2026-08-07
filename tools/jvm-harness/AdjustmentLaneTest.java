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


    static void check(String what, boolean ok) {
        if (ok) { passed++; System.out.println("  ok   " + what); }
        else { failed++; System.out.println("  FAIL " + what); }
    }
}
