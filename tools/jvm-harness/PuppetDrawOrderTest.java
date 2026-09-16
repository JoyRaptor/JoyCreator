import com.fadcam.ui.faditor.transform.mesh.*;

/**
 * PER-ISLAND DRAW ORDER — the halo, and which limb is in front.
 *
 * <p>JoyRaptor saw a hole the shape of one limb punched through the limb behind it, and edge
 * expansion made it worse. The cause was one draw call with blending DISABLED: every triangle
 * replaced what was under it, transparent pixels included, so an arm crossing the body wrote its
 * own transparent border straight over the body. A lattice never noticed because a grid cannot
 * overlap itself.
 *
 * <p>The fix has three parts and this file proves the two that are testable off a phone:
 * <ol>
 *   <li>triangles are GROUPED per island, so the pieces can be drawn separately at all;</li>
 *   <li>the groups have an explicit ORDER, animatable, per island rather than per pin.</li>
 * </ol>
 * The third — blending on, one draw call per group, back to front — lives in {@code MeshStampGl}
 * and needs a screen. It is listed as device-owed rather than claimed here.
 *
 * <p>Run: {@code bash tools/jvm-harness/run-puppet.sh}
 */
public class PuppetDrawOrderTest {

    private static int passed = 0, failed = 0;

    public static void main(String[] args) {
        System.out.println("-- grouping --");
        eachIslandIsItsOwnGroup();
        groupsCoverEveryTriangleExactlyOnce();
        everyGroupsTrianglesBelongToItsOwnIsland();
        aLatticeIsAlwaysOneGroup();
        theBuffersCarryTheGroupsToTheRenderer();

        System.out.println();
        System.out.println("-- order --");
        defaultOrderIsTheTracedOrder();
        depthDecidesWhoIsInFront();
        equalDepthsKeepTheirTracedOrder();
        depthIsAnimatable();
        aRebuildDoesNotStrandTheDepth();
        theOrderReachesTheBuffers();
        anUnbentPuppetStillReordersItself();
        depthSurvivesASaveAndLoad();
        aProjectWithNoDepthWritesNothingNew();

        System.out.println();
        System.out.println(failed == 0 ? "ALL GREEN (" + passed + "/" + (passed + failed) + ")"
                : "FAILURES: " + failed + " (passed " + passed + ")");
        if (failed > 0) System.exit(1);
    }

    // ── fixtures ────────────────────────────────────────────────────────────────────────────

    static final int W = 120, H = 120;

    static void rect(int[] a, int x0, int y0, int x1, int y1) {
        for (int y = y0; y < y1; y++) for (int x = x0; x < x1; x++) a[y * W + x] = 255;
    }

    /** Body plus two detached arms — the artwork that started all of this. */
    static float[][] threePieces() {
        int[] a = new int[W * H];
        rect(a, 45, 20, 75, 100);
        rect(a, 10, 35, 35, 65);
        rect(a, 85, 35, 110, 65);
        float[][] raw = AlphaContour.traceAll(a, W, H);
        float[][] out = new float[raw.length][];
        for (int i = 0; i < raw.length; i++) out[i] = AlphaContour.simplify(raw[i], 0.004f);
        return out;
    }

    static PuppetTopology puppet() {
        return new PuppetTopology(threePieces(), 5, new float[]{0.2f, 0.5f, 0.8f, 0.5f});
    }

    // ── grouping ────────────────────────────────────────────────────────────────────────────

    static void eachIslandIsItsOwnGroup() {
        PuppetTopology t = puppet();
        check("three pieces of art, three draw groups (" + t.groupCount() + ")",
                t.groupCount() == 3);
        check("...which is the island count", t.groupCount() == t.islandCount());
    }

    static void groupsCoverEveryTriangleExactlyOnce() {
        PuppetTopology t = puppet();
        int[] starts = new int[t.groupCount() + 1];
        t.groupIndexStart(starts);
        check("the first group starts at the beginning", starts[0] == 0);
        check("the last ends at the end (" + starts[t.groupCount()] + " of " + t.indexCount() + ")",
                starts[t.groupCount()] == t.indexCount());
        boolean ascending = true, whole = true;
        for (int i = 1; i < starts.length; i++) {
            if (starts[i] <= starts[i - 1]) ascending = false;
            if ((starts[i] - starts[i - 1]) % 3 != 0) whole = false;
        }
        check("no group is empty and none overlaps", ascending);
        // A group boundary inside a triangle would draw two thirds of one and split the third
        // across two passes — a torn edge that only shows on the overlap.
        check("and every group is a whole number of triangles", whole);
    }

    static void everyGroupsTrianglesBelongToItsOwnIsland() {
        PuppetTopology t = puppet();
        int[] starts = new int[t.groupCount() + 1];
        t.groupIndexStart(starts);
        int[] vstart = t.islandStart();
        short[] idx = new short[t.indexCount()];
        t.buildRest(new float[t.vertexCount() * 2], null, idx);
        boolean clean = true;
        for (int g = 0; g < t.groupCount(); g++) {
            for (int i = starts[g]; i < starts[g + 1]; i++) {
                int v = idx[i] & 0xFFFF;
                if (v < vstart[g] || v >= vstart[g + 1]) clean = false;
            }
        }
        // If a group's triangles reached into another island's vertices, drawing it separately
        // would drag a piece of the neighbour along with it.
        check("group g draws only island g's vertices", clean);
    }

    static void aLatticeIsAlwaysOneGroup() {
        MeshTopology l = new LatticeTopology(LatticeTopology.L2);
        int[] starts = new int[2];
        l.groupIndexStart(starts);
        // A grid cannot overlap itself, so it never needs a second pass — and the renderer's loop
        // runs exactly once, which is the old behaviour to the instruction.
        check("a lattice is one group", l.groupCount() == 1);
        check("covering all of it", starts[0] == 0 && starts[1] == l.indexCount());
    }

    static void theBuffersCarryTheGroupsToTheRenderer() {
        MeshBuffers b = new MeshBuffers();
        b.bind(puppet());
        check("the buffers know the group count (" + b.groupCount() + ")", b.groupCount() == 3);
        check("and where each starts",
                b.groupIndexStart[0] == 0 && b.groupIndexStart[3] == b.indexCount());
        // The renderer holds buffers, never topologies, and must never learn which KIND filled
        // them — the same rule that kept it from ever learning what a pin was.
        check("the order starts as the traced one",
                b.groupOrder[0] == 0 && b.groupOrder[1] == 1 && b.groupOrder[2] == 2);
    }

    // ── order ───────────────────────────────────────────────────────────────────────────────

    static void defaultOrderIsTheTracedOrder() {
        MeshWarpSpec spec = new MeshWarpSpec(puppet());
        int[] order = new int[3];
        check("an unordered puppet still answers", spec.groupOrderAt(0L, order));
        check("with the traced order — body first, since it is biggest",
                order[0] == 0 && order[1] == 1 && order[2] == 2);
        check("and it does not claim to have depth", !spec.hasGroupDepth());
    }

    static void depthDecidesWhoIsInFront() {
        MeshWarpSpec spec = new MeshWarpSpec(puppet());
        spec.setGroupZ(0, 0f);          // body
        spec.setGroupZ(1, 1f);          // left arm in front
        spec.setGroupZ(2, -1f);         // right arm behind
        int[] order = new int[3];
        spec.groupOrderAt(0L, order);
        // Back to front: the thing drawn first is furthest away.
        check("the piece sent behind is drawn first", order[0] == 2);
        check("the body next", order[1] == 0);
        check("and the piece brought forward last", order[2] == 1);
        check("and it knows it has depth now", spec.hasGroupDepth());
    }

    static void equalDepthsKeepTheirTracedOrder() {
        MeshWarpSpec spec = new MeshWarpSpec(puppet());
        spec.setGroupZ(0, 2f);
        spec.setGroupZ(1, 2f);
        spec.setGroupZ(2, 2f);
        int[] order = new int[3];
        spec.groupOrderAt(0L, order);
        // A sort that was not stable would swap two pieces that were never told which is in front,
        // and the swap would happen on some frames and not others: a flicker nobody could explain.
        check("pieces at the same depth do not shuffle",
                order[0] == 0 && order[1] == 1 && order[2] == 2);
    }

    static void depthIsAnimatable() {
        MeshWarpSpec spec = new MeshWarpSpec(puppet());
        MeshPoseTrack z = spec.groupZTrack();
        check("the depth track is one float per piece, not per pin", z.arity() == 3);
        z.put(0L, new float[]{0f, -1f, 0f}, null);     // the arm starts behind
        z.put(1000L, new float[]{0f, 1f, 0f}, null);   // and ends in front
        int[] early = new int[3], late = new int[3];
        spec.groupOrderAt(0L, early);
        spec.groupOrderAt(1000L, late);
        check("early on, the arm is drawn first (behind)", early[0] == 1);
        check("later, it is drawn last (in front)", late[2] == 1);
        // An arm passing behind the body and out the other side is the whole reason this animates.
        check("so the pieces really do swap over time", early[0] != late[0]);
    }

    static void aRebuildDoesNotStrandTheDepth() {
        MeshWarpSpec spec = new MeshWarpSpec(puppet());
        spec.setGroupZ(1, 3f);
        spec.groupZTrack().put(0L, new float[]{0f, 3f, 0f}, null);

        // Rebuild with the SAME three pieces but a different pin — the common case.
        PuppetTopology again = new PuppetTopology(threePieces(), 5,
                new float[]{0.2f, 0.5f, 0.8f, 0.5f, 0.5f, 0.5f});
        boolean ok = PuppetPoseRemap.apply(spec, again, new int[]{0, 1, -1});
        check("the spec moves onto the rebuilt topology", ok);
        if (!ok) return;
        check("and the depth survived, because the pieces did",
                Math.abs(spec.groupZ()[1] - 3f) < 1e-6f);
        int[] order = new int[3];
        spec.groupOrderAt(0L, order);
        check("so the arm is still in front", order[2] == 1);
    }

    static void theOrderReachesTheBuffers() {
        MeshWarpSpec spec = new MeshWarpSpec(puppet());
        spec.setGroupZ(2, -5f);
        float[] pose = new float[spec.arity()];
        pose[1] = 0.1f;                                // an actual bend, so the mesh path runs
        System.arraycopy(pose, 0, spec.handles(), 0, pose.length);

        MeshEngine engine = new MeshEngine();
        boolean live = engine.update(spec, 0L);
        check("the engine solved a bent puppet", live);
        if (!live) return;
        int[] o = engine.buffers().groupOrder;
        // Without this the ordering would exist in the model and never reach a pixel.
        check("the renderer's buffers got the order (" + o[0] + "," + o[1] + "," + o[2] + ")",
                o[0] == 2);
    }

    static void anUnbentPuppetStillReordersItself() {
        MeshWarpSpec spec = new MeshWarpSpec(puppet());
        spec.setGroupZ(1, 4f);                         // an arm moved in front, but NO bend at all
        MeshEngine engine = new MeshEngine();
        boolean live = engine.update(spec, 0L);
        // Bailing on an identity pose is what keeps "no deformation costs nothing" true, and it
        // stays true for everything with one group. But a character whose arm has been put in
        // front of its body is a different picture even at rest, and this is the only place that
        // reordering happens.
        check("an unbent puppet with ordered pieces still takes the mesh path", live);
        if (!live) return;
        int[] o = engine.buffers().groupOrder;
        check("with the arm drawn last", o[2] == 1);
        MeshBuffers b = engine.buffers();
        boolean atRest = true;
        for (int i = 0; i < b.vertexCount() * 2; i++) {
            if (Math.abs(b.positions[i] - b.rest[i]) > 1e-6f) atRest = false;
        }
        check("...and the picture itself is undeformed, as it should be", atRest);
    }

    static void depthSurvivesASaveAndLoad() {
        MeshWarpSpec spec = new MeshWarpSpec(puppet());
        spec.setGroupZ(1, -2f);
        spec.groupZTrack().put(0L, new float[]{0f, -2f, 0f}, null);
        spec.groupZTrack().put(900L, new float[]{0f, 2f, 0f}, null);

        MeshWarpSpec back = MeshWarpSpec.fromJson(spec.toJson());
        check("the warp round-trips", back != null);
        if (back == null) return;
        check("the static depth came back", Math.abs(back.groupZ()[1] + 2f) < 1e-6f);
        check("and the depth keyframes did",
                back.groupZTrackOrNull() != null && back.groupZTrackOrNull().size() == 2);
        int[] early = new int[3], late = new int[3];
        back.groupOrderAt(0L, early);
        back.groupOrderAt(900L, late);
        // Without this the arm would be back in front of the body every time the project opened,
        // and the work of placing it would quietly be gone.
        check("so the arm still passes from behind to in front", early[0] == 1 && late[2] == 1);
    }

    static void aProjectWithNoDepthWritesNothingNew() {
        MeshWarpSpec spec = new MeshWarpSpec(puppet());
        float[] h = spec.handles();
        h[1] = 0.1f;                                   // an ordinary bend, no depth authored
        String json = spec.toJson().toString();
        // Every project that exists today has no depth. None of their files may change shape.
        check("a puppet with no depth writes no depth keys", !json.contains("\"gz\""));
    }

    // ── plumbing ────────────────────────────────────────────────────────────────────────────

    static void check(String what, boolean ok) {
        if (ok) { passed++; System.out.println("  ok   " + what); }
        else { failed++; System.out.println("  FAIL " + what); }
    }
}
