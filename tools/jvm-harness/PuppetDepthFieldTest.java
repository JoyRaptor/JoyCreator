import com.fadcam.ui.faditor.transform.mesh.*;

/**
 * THE 3/4 STANCE: a shoulder behind the body, a hand in front of it, and the change happening
 * somewhere ALONG the arm.
 *
 * <p>JoyRaptor, 2026-09-15: <i>"Shoulder is behind body, elbow is behind body, hand may reach
 * behind their head or in front of their nose or belly. Could we use the falloff heat map style
 * thing to determine a threshold where the tris stop rendering behind layer 2 and start being in
 * front? ... nearly the entire forearm or directly 1/2 of a chain is either in front or behind."</i>
 *
 * <p>Per-island ordering cannot express that — it can only put a WHOLE limb in front or behind. So
 * depth became a FIELD, blended across the mesh by the same weights that bend it, and the triangles
 * are sorted by it. These prove the parts that do not need a screen:
 *
 * <ul>
 *   <li>the field really does cross over between two pins, not at the edge of a piece;</li>
 *   <li>the crossing lands where the influence changes hands — no threshold to tune;</li>
 *   <li>a pin's depth cannot leak onto a limb it is not attached to;</li>
 *   <li>bending the character does not re-order it, because depth comes from rest, not pose;</li>
 *   <li>a picture with no depth is byte-for-byte the mesh it always was.</li>
 * </ul>
 *
 * <p>Run: {@code bash tools/jvm-harness/run-puppet.sh}
 */
public class PuppetDepthFieldTest {

    private static int passed = 0, failed = 0;

    public static void main(String[] args) {
        System.out.println("-- the field --");
        theArmCrossesOverHalfwayAlong();
        theCrossingSitsBetweenTheTwoPins();
        depthCannotLeakOntoAnotherPiece();
        groupAndHandleDepthAdd();

        System.out.println();
        System.out.println("-- the draw list --");
        trianglesAreSortedBackToFront();
        noDepthMeansTheUntouchedMesh();
        bendingDoesNotReorder();
        changingDepthDoesReorder();
        theSortIsStable();
        everyTriangleSurvivesTheSort();

        System.out.println();
        System.out.println("-- keeping it --");
        handleDepthSurvivesASaveAndLoad();
        handleDepthFollowsItsPinThroughARebuild();

        System.out.println();
        System.out.println(failed == 0 ? "ALL GREEN (" + passed + "/" + (passed + failed) + ")"
                : "FAILURES: " + failed + " (passed " + passed + ")");
        if (failed > 0) System.exit(1);
    }

    // ── fixtures ────────────────────────────────────────────────────────────────────────────

    /** A horizontal limb, four pins along it: shoulder, upper arm, elbow, wrist. */
    static float[] limb() {
        return new float[]{0.05f, 0.4f, 0.95f, 0.4f, 0.95f, 0.6f, 0.05f, 0.6f};
    }

    static float[] limbPins() {
        return new float[]{0.15f, 0.5f, 0.38f, 0.5f, 0.62f, 0.5f, 0.85f, 0.5f};
    }

    static PuppetTopology arm() {
        return new PuppetTopology(new float[][]{limb()}, 5, limbPins());
    }

    /** Depth at every vertex, for the given per-pin depths. */
    static float[] field(PuppetTopology t, float[] pinZ, float[] groupZ) {
        float[] out = new float[t.vertexCount()];
        return t.vertexField(pinZ, groupZ, out) ? out : null;
    }

    static float[] rest(PuppetTopology t) {
        float[] r = new float[t.vertexCount() * 2];
        t.buildRest(r, null, null);
        return r;
    }

    // ── the field ───────────────────────────────────────────────────────────────────────────

    /** THE headline: shoulder behind, wrist in front, and the limb changes hands in the middle. */
    static void theArmCrossesOverHalfwayAlong() {
        PuppetTopology t = arm();
        float[] z = field(t, new float[]{-1f, -1f, 1f, 1f}, null);
        check("the field builds", z != null);
        if (z == null) return;
        float[] r = rest(t);

        float leftMost = 1f, rightMost = -1f;
        int behind = 0, front = 0;
        for (int v = 0; v < t.vertexCount(); v++) {
            if (z[v] < 0f) { behind++; leftMost = Math.min(leftMost, r[v * 2]); }
            if (z[v] > 0f) { front++; rightMost = Math.max(rightMost, r[v * 2]); }
        }
        System.out.printf("    %d vertices behind, %d in front%n", behind, front);
        // Neither end may swallow the whole limb: that is exactly the per-piece behaviour this
        // replaced, and it is what a 3/4 stance cannot use.
        check("part of the limb is behind", behind > 3);
        check("and part is in front", front > 3);
        check("roughly half each, as a chain divides (" + behind + " vs " + front + ")",
                Math.abs(behind - front) <= Math.max(4, t.vertexCount() / 3));
    }

    static void theCrossingSitsBetweenTheTwoPins() {
        PuppetTopology t = arm();
        float[] z = field(t, new float[]{-1f, -1f, 1f, 1f}, null);
        float[] r = rest(t);
        float lastBehind = 0f, firstFront = 1f;
        for (int v = 0; v < t.vertexCount(); v++) {
            if (z[v] < 0f) lastBehind = Math.max(lastBehind, r[v * 2]);
            if (z[v] > 0f) firstFront = Math.min(firstFront, r[v * 2]);
        }
        System.out.printf("    behind reaches x=%.3f, front starts at x=%.3f"
                + "  (pins at 0.38 and 0.62)%n", lastBehind, firstFront);
        // The pins that straddle the change are at 0.38 and 0.62, so the handover belongs between
        // them — and it lands there because that is where the influence changes hands, not because
        // a threshold was tuned to put it there.
        check("the handover happens between the two pins that disagree",
                firstFront > 0.3f && lastBehind < 0.72f);
    }

    static void depthCannotLeakOntoAnotherPiece() {
        // Two separate limbs. A pin on one is sent forward; the other must not move in depth.
        float[] left = {0.05f, 0.4f, 0.4f, 0.4f, 0.4f, 0.6f, 0.05f, 0.6f};
        float[] right = {0.6f, 0.4f, 0.95f, 0.4f, 0.95f, 0.6f, 0.6f, 0.6f};
        float[] pins = {0.2f, 0.5f, 0.8f, 0.5f};
        PuppetTopology t = new PuppetTopology(new float[][]{left, right}, 4, pins);
        float[] z = field(t, new float[]{5f, 0f}, null);
        check("a two-piece field builds", z != null);
        if (z == null) return;
        float[] r = rest(t);
        float worstRight = 0f;
        for (int v = 0; v < t.vertexCount(); v++) {
            if (r[v * 2] > 0.55f) worstRight = Math.max(worstRight, Math.abs(z[v]));
        }
        // The weight of a pin on another island is exactly zero, so its depth contributes exactly
        // nothing. An arm's depth cannot bleed into the body it is crossing.
        check("the other piece's depth is untouched (" + fmt(worstRight) + ")", worstRight < 1e-6f);
    }

    static void groupAndHandleDepthAdd() {
        PuppetTopology t = arm();
        float[] both = field(t, new float[]{0f, 0f, 2f, 2f}, new float[]{10f});
        check("a piece sent behind still has a hand reaching forward out of it",
                both != null && both[0] >= 9.9f && max(both) > 11f);
    }

    // ── the draw list ───────────────────────────────────────────────────────────────────────

    static MeshWarpSpec spec(float[] pinZ) {
        MeshWarpSpec s = new MeshWarpSpec(arm());
        for (int i = 0; i < pinZ.length; i++) s.setHandleZ(i, pinZ[i]);
        return s;
    }

    static void trianglesAreSortedBackToFront() {
        MeshEngine e = new MeshEngine();
        MeshWarpSpec s = spec(new float[]{-1f, -1f, 1f, 1f});
        check("an ordered limb takes the mesh path", e.update(s, 0L));
        MeshBuffers b = e.buffers();
        float[] z = field(arm(), new float[]{-1f, -1f, 1f, 1f}, new float[]{0f});
        float prev = -Float.MAX_VALUE;
        boolean ascending = true;
        for (int i = 0; i + 2 < b.indexCount(); i += 3) {
            float d = (z[b.drawIndices[i] & 0xFFFF] + z[b.drawIndices[i + 1] & 0xFFFF]
                    + z[b.drawIndices[i + 2] & 0xFFFF]) / 3f;
            if (d < prev - 1e-5f) ascending = false;
            prev = d;
        }
        // Back to front: the furthest triangle is submitted first, and GL blends in submission
        // order, which is what makes one draw call enough.
        check("every triangle is drawn behind the next one", ascending);
    }

    static void noDepthMeansTheUntouchedMesh() {
        MeshEngine e = new MeshEngine();
        MeshWarpSpec s = new MeshWarpSpec(arm());
        float[] h = s.handles();
        h[1] = 0.1f;                                   // an ordinary bend, no depth at all
        check("a plain bend solves", e.update(s, 0L));
        MeshBuffers b = e.buffers();
        boolean identical = true;
        for (int i = 0; i < b.indexCount(); i++) {
            if (b.drawIndices[i] != b.indices[i]) identical = false;
        }
        // Every project that exists today has no depth. None of them may start drawing in a
        // different order than they did yesterday.
        check("the draw list is the mesh's own order, untouched", identical);
    }

    static void bendingDoesNotReorder() {
        MeshEngine e = new MeshEngine();
        MeshWarpSpec s = spec(new float[]{-1f, -1f, 1f, 1f});
        e.update(s, 0L);
        int before = e.buffers().drawOrderStamp;
        short[] snapshot = e.buffers().drawIndices.clone();

        float[] h = s.handles();
        h[1] = 0.2f;                                   // now bend it hard
        h[7] = -0.2f;
        e.update(s, 0L);
        boolean same = e.buffers().drawOrderStamp == before;
        for (int i = 0; i < e.buffers().indexCount(); i++) {
            if (snapshot[i] != e.buffers().drawIndices[i]) same = false;
        }
        // A z-order that shifted as the limb moved would pop the character inside out mid-gesture
        // for no reason the animator could see. Depth comes from the REST weights and the authored
        // numbers, never from the pose.
        check("bending the character does not re-sort it", same);
    }

    static void changingDepthDoesReorder() {
        MeshEngine e = new MeshEngine();
        MeshWarpSpec s = spec(new float[]{-1f, -1f, 1f, 1f});
        e.update(s, 0L);
        short[] before = e.buffers().drawIndices.clone();
        s.setHandleZ(3, -5f);                          // the wrist goes behind after all
        e.update(s, 0L);
        boolean changed = false;
        for (int i = 0; i < e.buffers().indexCount(); i++) {
            if (before[i] != e.buffers().drawIndices[i]) changed = true;
        }
        check("moving a pin's depth does re-sort it", changed);
    }

    static void theSortIsStable() {
        MeshEngine e = new MeshEngine();
        // Every pin at the same depth: nothing has a reason to move, so nothing may.
        MeshWarpSpec s = spec(new float[]{3f, 3f, 3f, 3f});
        e.update(s, 0L);
        MeshBuffers b = e.buffers();
        boolean identical = true;
        for (int i = 0; i < b.indexCount(); i++) {
            if (b.drawIndices[i] != b.indices[i]) identical = false;
        }
        // An unstable sort would shuffle equal-depth triangles differently on different frames,
        // and adjacent triangles that share an edge would shimmer along it.
        check("triangles at one depth keep the order they were built in", identical);
    }

    static void everyTriangleSurvivesTheSort() {
        MeshEngine e = new MeshEngine();
        MeshWarpSpec s = spec(new float[]{-2f, 0f, 1f, 4f});
        e.update(s, 0L);
        MeshBuffers b = e.buffers();
        int tris = b.indexCount() / 3;
        boolean[] seen = new boolean[tris];
        int found = 0;
        for (int k = 0; k < tris; k++) {
            for (int t = 0; t < tris; t++) {
                if (seen[t]) continue;
                if (b.drawIndices[k * 3] == b.indices[t * 3]
                        && b.drawIndices[k * 3 + 1] == b.indices[t * 3 + 1]
                        && b.drawIndices[k * 3 + 2] == b.indices[t * 3 + 2]) {
                    seen[t] = true;
                    found++;
                    break;
                }
            }
        }
        // A sort that dropped or duplicated a triangle would tear a hole in the character that
        // only showed at certain depths.
        check("the sorted list is a permutation — nothing lost, nothing doubled ("
                + found + "/" + tris + ")", found == tris);
    }

    // ── keeping it ──────────────────────────────────────────────────────────────────────────

    static void handleDepthSurvivesASaveAndLoad() {
        MeshWarpSpec s = spec(new float[]{-1f, -1f, 1f, 1f});
        s.handleZTrack().put(0L, new float[]{-1f, -1f, 1f, 1f}, null);
        s.handleZTrack().put(800L, new float[]{1f, 1f, -1f, -1f}, null);
        MeshWarpSpec back = MeshWarpSpec.fromJson(s.toJson());
        check("the warp round-trips", back != null);
        if (back == null) return;
        check("the per-pin depth came back", Math.abs(back.handleZ()[3] - 1f) < 1e-6f);
        check("and its keyframes did",
                back.handleZTrackOrNull() != null && back.handleZTrackOrNull().size() == 2);
        float[] late = new float[4];
        back.handleZAt(800L, late);
        // An arm that swings from behind the body to in front of it over a second is the whole
        // point of keyframing this, so losing it on reload would lose the shot.
        check("the arm has swapped over by the end", late[3] < 0f);
    }

    static void handleDepthFollowsItsPinThroughARebuild() {
        MeshWarpSpec s = spec(new float[]{-1f, -1f, 1f, 1f});
        float[] pins = new float[]{0.15f, 0.5f, 0.38f, 0.5f, 0.62f, 0.5f, 0.85f, 0.5f,
                0.5f, 0.45f};
        PuppetTopology grown = new PuppetTopology(new float[][]{limb()}, 5, pins);
        boolean ok = PuppetPoseRemap.apply(s, grown, new int[]{0, 1, 2, 3, -1});
        check("a pin is added", ok);
        if (!ok) return;
        check("the existing pins kept their depth", Math.abs(s.handleZ()[3] - 1f) < 1e-6f);
        check("the new one starts flat", s.handleZ()[4] == 0f);
        check("and the list is the right length now", s.handleZ().length == 5);
    }

    // ── plumbing ────────────────────────────────────────────────────────────────────────────

    static float max(float[] a) {
        float m = -Float.MAX_VALUE;
        for (float f : a) m = Math.max(m, f);
        return m;
    }

    static String fmt(float f) { return String.format("%.4f", f); }

    static void check(String what, boolean ok) {
        if (ok) { passed++; System.out.println("  ok   " + what); }
        else { failed++; System.out.println("  FAIL " + what); }
    }
}
