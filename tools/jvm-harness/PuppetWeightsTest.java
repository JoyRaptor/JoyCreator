import com.fadcam.ui.faditor.transform.mesh.*;

/**
 * SPEC_20260915_PUPPET_UI sections 4 and 5, proved off device.
 *
 * <p>Two independent things land here and they are tested together because they are the two halves
 * of one feature — posing a limb without wrecking the limb beside it, and recording that pose
 * without wrecking the pin beside it.
 *
 * <ul>
 *   <li><b>Weights across the body.</b> The headline claim is that straight-line distance "breaks
 *       once bones exist — influence crosses the gap between two hands". That is not asserted here,
 *       it is <b>MEASURED</b>: the same shape, the same drag, solved both ways, and the numbers
 *       printed. A test that only asserted the new path would pass just as happily if the old path
 *       had never been broken.</li>
 *   <li><b>Per-handle keys.</b> Recording pin B must not erase pin A — the bug section 5.1 predicts
 *       and the one that makes live overdub impossible.</li>
 *   <li><b>A chain thins on shared key times.</b> Section 5.2's wobble, shown to be
 *       unrepresentable rather than merely avoided.</li>
 * </ul>
 *
 * <p>Run: {@code bash tools/jvm-harness/run-puppet.sh}
 */
public class PuppetWeightsTest {

    private static int passed = 0, failed = 0;

    public static void main(String[] args) {
        System.out.println("-- weights: across the body, not through the air --");
        theTwoHandsBug();
        twoPinsCannotLocaliseAtAll();
        weightsArePartitionOfUnity();
        weightsAreDeterministic();
        weightOverrideShiftsInfluenceAndKeepsUnity();
        weightOverrideSurvivesTheWireFormat();
        aVertexOnAPinFollowsItExactly();
        noNaNInTheTable();
        buildCost();

        System.out.println();
        System.out.println("-- per-handle keys, and chains --");
        recordingOnePinDoesNotEraseAnother();
        punchInInheritsTheAnimatedValue();
        aChainIsWrittenAtomically();
        aChainThinsOnSharedTimes();
        aPoseThatMattersToOneComponentSurvives();
        onePinsTapeIgnoresAnotherPinsPoses();
        badInputIsRefusedNotStored();

        System.out.println();
        System.out.println("-- the registry --");
        puppetRoundTripsThroughTheRegistry();

        System.out.println();
        System.out.println(failed == 0 ? "ALL GREEN (" + passed + "/" + (passed + failed) + ")"
                : "FAILURES: " + failed + " (passed " + passed + ")");
        if (failed > 0) System.exit(1);
    }

    // ── fixtures ────────────────────────────────────────────────────────────────────────────

    /**
     * A HORSESHOE: two prongs rising from a common base, separated by a narrow slot.
     *
     * <p>This is the whole test in one shape. The two prong tips are 0.2 apart through the air and
     * about 1.3 apart along the body. It is exactly a character standing with both hands together,
     * and it is the case straight-line weights get wrong.
     *
     * <pre>
     *   |    |  |    |      prongs, tips at y=0.9
     *   |    |__|    |      slot down to y=0.35
     *   |             |
     *   |_____________|     base
     * </pre>
     */
    static float[] horseshoeRing() {
        return new float[]{
                0.05f, 0.05f,
                0.95f, 0.05f,
                0.95f, 0.95f,
                0.55f, 0.95f,
                0.55f, 0.35f,
                0.45f, 0.35f,
                0.45f, 0.95f,
                0.05f, 0.95f,
        };
    }

    /** One pin near the tip of each prong. Close in space, far apart along the shape. */
    static float[] tipPins() {
        return new float[]{0.25f, 0.85f, 0.75f, 0.85f};
    }

    /**
     * The same two tips plus two anchors in the base — a REAL rig, in the sense section 3 of the UI
     * spec means: a chain starts at an anchor. See {@link #twoPinsCannotLocaliseAtAll} for why the
     * headline measurement would be meaningless without them.
     */
    static float[] riggedPins() {
        return new float[]{0.25f, 0.85f, 0.75f, 0.85f, 0.20f, 0.15f, 0.80f, 0.15f};
    }

    /**
     * What the straight-line falloff gives pin {@code pin} at vertex {@code v} — normalised, so it
     * is directly comparable with a row of the table. The reference implementation the new code has
     * to beat, written out rather than inferred.
     */
    static float straightLineWeight(float[] rest, float[] pins, int v, int pin) {
        float sum = 0f, mine = 0f;
        for (int i = 0; i < pins.length / 2; i++) {
            float dx = rest[v * 2] - pins[i * 2], dy = rest[v * 2 + 1] - pins[i * 2 + 1];
            float w = 1f / Math.max(1e-12f, dx * dx + dy * dy);
            sum += w;
            if (i == pin) mine = w;
        }
        return sum > 0f ? mine / sum : 0f;
    }

    static MeshBuffers solvedWith(PuppetTopology t, float[] pose, boolean geodesic) {
        MeshBuffers b = new MeshBuffers();
        b.bind(t);
        PuppetDeformer d = new PuppetDeformer(geodesic);
        return d.solve(t, b, pose) ? b : null;
    }

    /** How far the vertices on the RIGHT prong moved, at their worst. */
    static float worstRightProngMove(PuppetTopology t, MeshBuffers b) {
        float worst = 0f;
        for (int v = 0; v < t.vertexCount(); v++) {
            float x = b.rest[v * 2], y = b.rest[v * 2 + 1];
            if (x < 0.6f || y < 0.5f) continue;          // right prong only, above the base
            float dx = b.positions[v * 2] - x, dy = b.positions[v * 2 + 1] - y;
            worst = Math.max(worst, (float) Math.sqrt(dx * dx + dy * dy));
        }
        return worst;
    }

    // ── weights ─────────────────────────────────────────────────────────────────────────────

    /**
     * THE headline. Drag the left prong's pin; the right prong must stay put. Measured both ways,
     * because the number that matters is the RATIO, and a ratio needs the old path to still exist.
     */
    static void theTwoHandsBug() {
        float[] pins = riggedPins();
        PuppetTopology t = new PuppetTopology(horseshoeRing(), 7, pins);

        // ── the claim itself: the DISTANCE ────────────────────────────────────────────────
        // Stated at the level it is actually made at. Everything below is a consequence of this
        // one number, and the consequences are damped — normalising the weights means a vertex an
        // inch from its own pin gives every other pin a small share however far away it is.
        float[] rest = new float[t.vertexCount() * 2];
        short[] idx = new short[t.indexCount()];
        t.buildRest(rest, null, idx);
        float[] dist = new float[t.vertexCount()];
        check("the distance field builds",
                PuppetWeights.distancesFrom(rest, idx, pins[0], pins[1], dist));

        int hand = -1;
        float best = Float.MAX_VALUE;
        for (int v = 0; v < t.vertexCount(); v++) {       // the far HAND: top of the right prong
            float dx = rest[v * 2] - 0.75f, dy = rest[v * 2 + 1] - 0.88f;
            float d = dx * dx + dy * dy;
            if (d < best) { best = d; hand = v; }
        }
        float air = (float) Math.hypot(rest[hand * 2] - pins[0], rest[hand * 2 + 1] - pins[1]);
        float body = dist[hand];
        System.out.printf("    left hand to right hand:  through the air %.3f,"
                + " across the body %.3f   (%.1fx further)%n", air, body, body / air);
        check("the two hands are genuinely close through the air (" + fmt(air) + ")", air < 0.6f);
        // 2.0x, not the 2.9x this read when it was written. The number came DOWN when the
        // triangulator started subdividing long boundary edges and relaxing the interior, and it
        // came down because the measurement got better: a coarse mesh has to zigzag between few
        // vertices, which OVERSTATES the distance along the body. A denser graph measures closer
        // to the true path. The property is unchanged; the old number was partly an artefact.
        check("and several times further apart across the body (" + fmt(body) + ", "
                        + String.format("%.1f", body / air) + "x)",
                body > air * 2.0f);

        // ── the consequence in the weights ────────────────────────────────────────────────
        PuppetWeights table = t.weights();
        float wAir = 0f, wBody = 0f;
        for (int v = 0; v < t.vertexCount(); v++) {
            if (rest[v * 2] < 0.6f || rest[v * 2 + 1] < 0.75f) continue;
            wAir = Math.max(wAir, straightLineWeight(rest, pins, v, 0));
            wBody = Math.max(wBody, table.weight(v, 0));
        }
        System.out.printf("    left hand's grip on the right hand:  through the air %.4f,"
                + " across the body %.4f%n", wAir, wBody);
        check("the grip loosens measurably (" + fmt(wBody) + " vs " + fmt(wAir) + ")",
                wBody < wAir * 0.7f);

        // ── and at the level of the picture ────────────────────────────────────────────────
        float[] pose = new float[t.handleArity()];
        pose[1] = 0.30f;                                  // haul the LEFT tip upward
        MeshBuffers solvedAir = solvedWith(t, pose, false);
        MeshBuffers solvedBody = solvedWith(t, pose, true);
        check("both solvers ran on the horseshoe", solvedAir != null && solvedBody != null);
        if (solvedAir == null || solvedBody == null) return;

        float leakAir = worstRightProngMove(t, solvedAir);
        float leakBody = worstRightProngMove(t, solvedBody);
        System.out.printf("    right prong drags along:  through the air %.4f,"
                + " across the body %.4f   (%.1fx less)%n",
                leakAir, leakBody, leakAir / Math.max(1e-6f, leakBody));

        // Positive control. If the old path did NOT leak, the shape is wrong and the comparison
        // below would pass for the wrong reason — the likeliest way this whole test could lie.
        check("straight-line distance really does drag the far prong (" + fmt(leakAir) + ")",
                leakAir > 0.04f);
        check("measuring across the body cuts that roughly in half ("
                        + fmt(leakBody) + " vs " + fmt(leakAir) + ")",
                leakBody < leakAir * 0.6f);

        // And the pin that WAS dragged still does its job — a weighting that simply damped
        // everything would pass the test above and be useless.
        float nearMove = 0f;
        for (int v = 0; v < t.vertexCount(); v++) {
            float x = solvedBody.rest[v * 2], y = solvedBody.rest[v * 2 + 1];
            if (x > 0.4f || y < 0.5f) continue;           // left prong
            float dx = solvedBody.positions[v * 2] - x, dy = solvedBody.positions[v * 2 + 1] - y;
            nearMove = Math.max(nearMove, (float) Math.sqrt(dx * dx + dy * dy));
        }
        check("the dragged prong still moves properly (" + fmt(nearMove) + " of 0.30)",
                nearMove > 0.15f);
    }

    /**
     * A FINDING, recorded so nobody chases it as a weights bug: with only TWO pins, moving one
     * rotates the entire picture and no weighting can prevent it.
     *
     * <p>That is MLS, not the falloff. With two control points the map has barely more freedom
     * than a similarity transform, so "hold the far side still" is not among the things it can
     * express — the far side is held by OTHER PINS or not at all. Both measurements below are
     * large and roughly equal, and they are supposed to be.
     *
     * <p><b>What it means for the UI:</b> section 3's "tap the hip first" is not a convention, it
     * is load-bearing. A puppet with two pins and no anchor will swing bodily when either is
     * dragged, and the honest fix is an anchor, not a knob.
     */
    static void twoPinsCannotLocaliseAtAll() {
        PuppetTopology t = new PuppetTopology(horseshoeRing(), 7, tipPins());
        float[] pose = new float[t.handleArity()];
        pose[1] = 0.30f;
        float air = worstRightProngMove(t, solvedWith(t, pose, false));
        float body = worstRightProngMove(t, solvedWith(t, pose, true));
        System.out.printf("    unanchored 2-pin puppet swings bodily either way: %.4f / %.4f%n",
                air, body);
        check("two pins alone cannot hold the far side, with either distance measure"
                        + " — this is MLS, not the weights",
                air > 0.15f && body > 0.15f);
    }

    static void weightsArePartitionOfUnity() {
        PuppetTopology t = new PuppetTopology(horseshoeRing(), 7, tipPins());
        PuppetWeights w = t.weights();
        check("a pinned puppet has a weight table", w != null);
        if (w == null) return;
        float worst = 0f;
        for (int v = 0; v < w.vertexCount(); v++) {
            float sum = 0f;
            for (int i = 0; i < w.pinCount(); i++) sum += w.weight(v, i);
            worst = Math.max(worst, Math.abs(1f - sum));
        }
        check("every vertex's weights sum to 1 (worst drift " + fmt(worst) + ")", worst < 1e-4f);
    }

    /**
     * The per-pin WEIGHT OVERRIDE, both halves of it.
     *
     * <p>Until 2026-09-16 {@code PuppetPin.weight} was saved, round-tripped through JSON and read
     * by nothing. Two things have to be true now that it is wired: turning one pin up really does
     * take influence from its neighbour, and doing so leaves every row still summing to one —
     * because the scale is applied BEFORE normalisation precisely so that no vertex is left
     * partly undriven.
     */
    static void weightOverrideShiftsInfluenceAndKeepsUnity() {
        float[] ring = horseshoeRing(), pins = tipPins();
        PuppetWeights auto = new PuppetTopology(ring, 7, pins).weights();

        float[] louder = new float[pins.length / 2];
        java.util.Arrays.fill(louder, 1f);
        louder[0] = 3f;
        PuppetWeights over = new PuppetTopology(new float[][]{ring}, 7, pins,
                PuppetWeights.SOFTNESS_NEUTRAL, null, null, null, louder).weights();

        check("an overridden puppet still has a table", auto != null && over != null);
        if (auto == null || over == null) return;

        // Partition of unity survives the override. This is the property a naive "multiply after
        // normalising" would have destroyed, and it would have shown up as a character that
        // shrinks towards its pins rather than one that bends.
        float worst = 0f;
        for (int v = 0; v < over.vertexCount(); v++) {
            float sum = 0f;
            for (int i = 0; i < over.pinCount(); i++) sum += over.weight(v, i);
            worst = Math.max(worst, Math.abs(1f - sum));
        }
        check("an override leaves every vertex summing to 1 (worst drift " + fmt(worst) + ")",
                worst < 1e-4f);

        // And it actually did something: pin 0 owns strictly more of the mesh than it did.
        float before = 0f, after = 0f;
        for (int v = 0; v < auto.vertexCount(); v++) {
            before += auto.weight(v, 0);
            after += over.weight(v, 0);
        }
        check("turning a pin up gives it more of the character ("
                + fmt(before) + " -> " + fmt(after) + ")", after > before * 1.05f);

        // Zero is not mute: the pin keeps its keys and its place, it just stops arguing.
        float[] quiet = new float[pins.length / 2];
        java.util.Arrays.fill(quiet, 1f);
        quiet[0] = 0f;
        PuppetWeights off = new PuppetTopology(new float[][]{ring}, 7, pins,
                PuppetWeights.SOFTNESS_NEUTRAL, null, null, null, quiet).weights();
        boolean clean = off != null;
        if (off != null) {
            for (int v = 0; v < off.vertexCount() && clean; v++) {
                float sum = 0f;
                for (int i = 0; i < off.pinCount(); i++) {
                    float x = off.weight(v, i);
                    if (Float.isNaN(x) || Float.isInfinite(x)) { clean = false; break; }
                    sum += x;
                }
                if (Math.abs(1f - sum) > 1e-3f) clean = false;
            }
        }
        check("an override of zero is finite and still sums to 1", clean);
    }

    /** V5 carries the override through the wire format a reloaded project rebuilds from. */
    static void weightOverrideSurvivesTheWireFormat() {
        float[] ring = horseshoeRing(), pins = tipPins();
        float[] scale = new float[pins.length / 2];
        java.util.Arrays.fill(scale, 1f);
        if (scale.length > 1) scale[1] = 2.5f;
        PuppetTopology t = new PuppetTopology(new float[][]{ring}, 7, pins,
                PuppetWeights.SOFTNESS_NEUTRAL, null, null, null, scale);
        MeshTopology back = MeshTopologies.create("puppet", t.params());
        check("a V5 puppet reloads", back instanceof PuppetTopology);
        if (!(back instanceof PuppetTopology)) return;
        float[] got = ((PuppetTopology) back).weightScale();
        boolean same = got.length == scale.length;
        for (int i = 0; same && i < got.length; i++) same = Math.abs(got[i] - scale[i]) < 1e-5f;
        check("with its overrides intact", same);
        // The stamp is what decides whether a cached mesh is reused; two puppets that bend
        // differently must never share one.
        check("and a different override is a different topology",
                t.topologyId() != new PuppetTopology(ring, 7, pins).topologyId());
    }

    static void weightsAreDeterministic() {
        float[] ring = horseshoeRing(), pins = tipPins();
        PuppetWeights a = new PuppetTopology(ring, 7, pins).weights();
        PuppetWeights b = new PuppetTopology(ring, 7, pins).weights();
        boolean same = a != null && b != null
                && a.vertexCount() == b.vertexCount() && a.pinCount() == b.pinCount();
        if (same) {
            for (int v = 0; v < a.vertexCount() && same; v++) {
                for (int i = 0; i < a.pinCount(); i++) {
                    if (a.weight(v, i) != b.weight(v, i)) { same = false; break; }
                }
            }
        }
        // Not a nicety: a project reopened tomorrow rebuilds this table from the stored contour,
        // and if it came out different the character would be posed differently than it was saved.
        check("the same puppet builds the same table, bit for bit", same);
    }

    /**
     * A pin dropped exactly on a vertex must carry that vertex exactly. This is the property that
     * normalising the weights would have quietly destroyed, and the reason those rows are held out
     * of the smoothing passes.
     */
    static void aVertexOnAPinFollowsItExactly() {
        float[] ring = horseshoeRing();
        // Pin 0 sits ON contour point 0; pin 1 is somewhere ordinary.
        float[] pins = new float[]{ring[0], ring[1], 0.75f, 0.85f};
        PuppetTopology t = new PuppetTopology(ring, 6, pins);
        float[] pose = new float[t.handleArity()];
        pose[0] = 0.07f;
        pose[1] = 0.05f;
        MeshBuffers b = solvedWith(t, pose, true);
        check("solve with a pin on a vertex succeeds", b != null);
        if (b == null) return;
        int onPin = -1;
        for (int v = 0; v < t.vertexCount(); v++) {
            if (Math.abs(b.rest[v * 2] - pins[0]) < 1e-6f
                    && Math.abs(b.rest[v * 2 + 1] - pins[1]) < 1e-6f) { onPin = v; break; }
        }
        check("the pin's vertex exists in the mesh", onPin >= 0);
        if (onPin < 0) return;
        float ex = b.positions[onPin * 2] - (pins[0] + pose[0]);
        float ey = b.positions[onPin * 2 + 1] - (pins[1] + pose[1]);
        float err = (float) Math.sqrt(ex * ex + ey * ey);
        check("it lands exactly under the finger (err " + fmt(err) + ")", err < 1e-5f);
    }

    static void noNaNInTheTable() {
        boolean clean = true;
        // Including the cases that have no sensible answer: two pins on the same spot, and a pin
        // dropped on a transparent pixel outside the traced shape.
        float[][] pinSets = {
                tipPins(),
                new float[]{0.5f, 0.5f, 0.5f, 0.5f},
                new float[]{0.5f, 0.6f, 5.0f, -3.0f},
                new float[]{0.25f, 0.85f},
        };
        for (float[] pins : pinSets) {
            PuppetWeights w = new PuppetTopology(horseshoeRing(), 5, pins).weights();
            if (w == null) { clean = false; continue; }
            for (int v = 0; v < w.vertexCount(); v++) {
                for (int i = 0; i < w.pinCount(); i++) {
                    float x = w.weight(v, i);
                    if (Float.isNaN(x) || Float.isInfinite(x) || x < 0f || x > 1.0001f) {
                        clean = false;
                    }
                }
            }
        }
        check("no NaN, no infinity and nothing out of [0,1], including degenerate pin sets", clean);
    }

    /**
     * The wait when a pin is dropped. Section 4 budgets 1-3 ms; measured, not asserted, because the
     * only thing that would make this wrong is a mesh far denser than any of these.
     */
    static void buildCost() {
        System.out.println("    build cost (the pause after dropping a pin):");
        boolean allUnderBudget = true;
        int[] interiors = {5, 9, 14};
        int[] pinCounts = {2, 6, 12};
        for (int interior : interiors) {
            for (int pc : pinCounts) {
                float[] pins = new float[pc * 2];
                for (int i = 0; i < pc; i++) {
                    double a = i * 2 * Math.PI / pc;
                    pins[i * 2] = 0.5f + 0.25f * (float) Math.cos(a);
                    pins[i * 2 + 1] = 0.5f + 0.25f * (float) Math.sin(a);
                }
                PuppetTopology t = new PuppetTopology(horseshoeRing(), interior, pins);
                PuppetTriangulator.Mesh ignored = null;   // keep the shape honest, not reused
                float[] verts = new float[t.vertexCount() * 2];
                short[] idx = new short[t.indexCount()];
                t.buildRest(verts, null, idx);
                for (int i = 0; i < 20; i++) PuppetWeights.build(verts, idx, pins);   // warm up
                long t0 = System.nanoTime();
                int iters = 100;
                for (int i = 0; i < iters; i++) PuppetWeights.build(verts, idx, pins);
                double ms = (System.nanoTime() - t0) / 1e6 / iters;
                boolean ok = ms < 3.0;
                allUnderBudget &= ok;
                System.out.printf("      interior=%-3d pins=%-3d verts=%-4d  %.4f ms%s%n",
                        interior, pc, t.vertexCount(), ms, ok ? "" : "   <-- OVER");
            }
        }
        check("every realistic puppet binds inside the spec's 3 ms", allUnderBudget);
    }

    // ── per-handle keys ─────────────────────────────────────────────────────────────────────

    /** The exact bug section 5.1 predicts: record pin B, and pin A is gone. */
    static void recordingOnePinDoesNotEraseAnother() {
        MeshPoseTrack tr = new MeshPoseTrack(4);          // two pins, two components each
        tr.put(0L, new float[]{0f, 0f, 0f, 0f}, null);
        tr.put(1000L, new float[]{0.4f, 0f, 0f, 0f}, null);   // pin A animates, pin B is still

        // Now overdub pin B at the midpoint, the way a live take would.
        boolean ok = tr.putHandle(500L, 1, 2, new float[]{0f, 0.25f}, null);
        check("a per-handle write is accepted", ok);

        float[] out = new float[4];
        tr.valueAt(500L, out);
        check("pin B recorded (" + fmt(out[3]) + ")", Math.abs(out[3] - 0.25f) < 1e-5f);
        // The whole point. A naive whole-pose write would have put 0 here and thrown away half of
        // pin A's animation without a word.
        check("pin A kept the value it was interpolating to (" + fmt(out[0]) + ", wanted 0.20)",
                Math.abs(out[0] - 0.20f) < 1e-4f);
    }

    /** Section 5.5, anchor in: the first sample of a punch-in inherits the animated value. */
    static void punchInInheritsTheAnimatedValue() {
        MeshPoseTrack tr = new MeshPoseTrack(2);
        tr.put(0L, new float[]{0f, 0f}, null);
        tr.put(1000L, new float[]{1f, 0f}, null);
        // The finger grabs the pin at t=400 and does not move it yet.
        float[] before = new float[2];
        tr.valueAt(400L, before);
        tr.putComponents(400L, new int[]{1}, new float[]{0.05f}, null);
        float[] after = new float[2];
        tr.valueAt(400L, after);
        check("the in-point does not jump (" + fmt(before[0]) + " -> " + fmt(after[0]) + ")",
                Math.abs(before[0] - after[0]) < 1e-5f);
    }

    static void aChainIsWrittenAtomically() {
        MeshPoseTrack tr = new MeshPoseTrack(6);          // three pins: shoulder, elbow, wrist
        tr.put(0L, new float[6], null);
        boolean ok = tr.putComponents(500L,
                new int[]{0, 1, 2, 3, 4, 5},
                new float[]{0.01f, 0.02f, 0.05f, 0.06f, 0.12f, 0.10f}, null);
        check("the whole chain writes in one call", ok);
        // One pose. Not three. That is the one-gesture-one-undo rule surviving IK.
        check("and it is ONE key, so one press of undo takes the whole limb back",
                tr.size() == 2);
    }

    /**
     * Section 5.2. Thin an arm and every pin's keys must land on the SAME instants, or the chain
     * solves to a different shape between them and the limb wobbles.
     */
    static void aChainThinsOnSharedTimes() {
        MeshPoseTrack tr = new MeshPoseTrack(6);
        // A live take: 21 samples of a smooth, perfectly linear ramp on all three pins. Linear
        // means every interior sample is redundant, so a correct thinner takes it down to two.
        for (int i = 0; i <= 20; i++) {
            long t = i * 50L;
            float f = i / 20f;
            tr.put(t, new float[]{f * 0.1f, 0f, f * 0.2f, 0f, f * 0.3f, 0f}, null);
        }
        check("the take recorded 21 poses", tr.size() == 21);
        int removed = tr.simplifyRange(0L, 1000L, 0.001f);
        check("thinning dropped the redundant samples (" + removed + " of 19)", removed == 19);

        long[] wrist = tr.componentTimes(new int[]{4, 5}, 0.001f);
        long[] elbow = tr.componentTimes(new int[]{2, 3}, 0.001f);
        boolean identical = wrist.length == elbow.length;
        for (int i = 0; identical && i < wrist.length; i++) identical = wrist[i] == elbow[i];
        check("wrist and elbow keys land on exactly the same instants", identical);
    }

    static void aPoseThatMattersToOneComponentSurvives() {
        MeshPoseTrack tr = new MeshPoseTrack(6);
        for (int i = 0; i <= 20; i++) {
            long t = i * 50L;
            float f = i / 20f;
            float[] v = new float[]{f * 0.1f, 0f, f * 0.2f, 0f, f * 0.3f, 0f};
            if (i == 10) v[5] = 0.25f;                  // the wrist alone flicks, mid-take
            tr.put(t, v, null);
        }
        tr.simplifyRange(0L, 1000L, 0.001f);
        boolean kept = false;
        for (MeshPoseTrack.Pose p : tr.poses()) if (p.timeMs == 500L) kept = true;
        // If this pose were dropped, the wrist's flick would vanish. If it were kept for the wrist
        // but not the elbow, the two would be keyed at different instants — the wobble.
        check("a pose that matters to ONE pin is kept for the whole chain", kept);
        // Its SHOULDERS survive too, and that is correct: without the sample either side, the
        // flick would ramp in from the start of the take instead of happening when it happened.
        System.out.println("    the take thinned to " + tr.size() + " poses: "
                + java.util.Arrays.toString(tr.times()));
        check("everything else went (" + tr.size() + " poses left)", tr.size() <= 5);
    }

    static void onePinsTapeIgnoresAnotherPinsPoses() {
        MeshPoseTrack tr = new MeshPoseTrack(4);
        tr.put(0L, new float[]{0f, 0f, 0f, 0f}, null);
        tr.put(1000L, new float[]{0.4f, 0f, 0f, 0f}, null);
        tr.putHandle(500L, 0, 2, new float[]{0.20f, 0f}, null);   // on pin A's own straight line
        long[] tapeB = tr.componentTimes(new int[]{2, 3}, 1e-4f);
        // Pin B never moved, so its tape is its two ends — not the pose someone else recorded.
        check("pin B's tape shows 2 keys, not 3 (" + tapeB.length + ")", tapeB.length == 2);
        long[] tapeA = tr.componentTimes(new int[]{0, 1}, 1e-4f);
        check("and pin A's middle key is redundant too, so it also shows 2 (" + tapeA.length + ")",
                tapeA.length == 2);
    }

    static void badInputIsRefusedNotStored() {
        MeshPoseTrack tr = new MeshPoseTrack(4);
        boolean a = tr.putComponents(0L, new int[]{0, 9}, new float[]{1f, 1f}, null);
        boolean b = tr.putComponents(0L, new int[]{0, 1}, new float[]{1f}, null);
        boolean c = tr.putHandle(0L, 7, 2, new float[]{1f, 1f}, null);
        check("an out-of-range component is refused", !a);
        check("mismatched arrays are refused", !b);
        check("an out-of-range handle is refused", !c);
        check("and nothing was stored by any of them", tr.isEmpty());
    }

    // ── the registry ────────────────────────────────────────────────────────────────────────

    /**
     * Until this passes, a saved puppet reopens as an undeformed picture — the pose survives in the
     * JSON and there is nothing to apply it to.
     */
    static void puppetRoundTripsThroughTheRegistry() {
        check("the registry knows what a puppet is", MeshTopologies.isKnown("puppet"));
        PuppetTopology t = new PuppetTopology(horseshoeRing(), 6, tipPins());
        MeshTopology back = MeshTopologies.create("puppet", t.params());
        check("it rebuilds one from its own params", back instanceof PuppetTopology);
        if (back == null) return;
        check("with the same vertices", back.vertexCount() == t.vertexCount());
        check("the same triangles", back.indexCount() == t.indexCount());
        check("the same pins", back.handleCount() == t.handleCount());
        // topologyId is what the renderer uses to decide its buffers are stale. Same puppet, same
        // id, or every reload silently rebuilds every mesh.
        check("and the same identity", back.topologyId() == t.topologyId());
        MeshDeformer d = MeshTopologies.deformerFor(back);
        check("and the registry hands back a deformer that supports it",
                d instanceof PuppetDeformer && d.supports(back));
        check("garbage params give null, not an exception",
                MeshTopologies.create("puppet", new float[]{9f, 9f}) == null);
    }

    // ── plumbing ────────────────────────────────────────────────────────────────────────────

    static String fmt(float f) { return String.format("%.4f", f); }

    static void check(String what, boolean ok) {
        if (ok) { passed++; System.out.println("  ok   " + what); }
        else { failed++; System.out.println("  FAIL " + what); }
    }
}
