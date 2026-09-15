import com.fadcam.ui.faditor.transform.mesh.*;

/**
 * THE AUTHORED KNOBS, and what happens to an animated puppet when its pins change.
 *
 * <p>Three sliders were being authored in the drawer, written to the project file, read back on
 * load — and consumed by nothing. Softness, Stiff area/Strength, and Edge expansion all moved a
 * number that no code ever looked at. That is the worst kind of unfinished, because it looks
 * finished from every direction except the picture.
 *
 * <p>So each of these tests turns a knob and measures the picture. A test that only checked the
 * value was stored would have passed every day the sliders did nothing.
 *
 * <p>The fourth section is not a knob but a silent data loss: adding a pin to a character that is
 * already animated must not cost the animation, and removing one must not slide every other pin's
 * performance onto its neighbour.
 *
 * <p>Run: {@code bash tools/jvm-harness/run-puppet.sh}
 */
public class PuppetKnobsTest {

    private static int passed = 0, failed = 0;

    public static void main(String[] args) {
        System.out.println("-- softness --");
        softnessChangesHowFarAPinReaches();
        neutralSoftnessIsExactlyTheOldBehaviour();
        softnessSurvivesTheFileFormat();

        System.out.println();
        System.out.println("-- stiffness --");
        aStiffPinHoldsItsNeighbourhoodRigid();
        strengthZeroIsAnOrdinaryPin();
        stiffnessStaysInsideItsArea();
        stiffnessNeverBreaksThePartitionOfUnity();

        System.out.println();
        System.out.println("-- edge expansion --");
        expansionGrowsTheOutline();
        expansionKeepsTheShape();
        expansionRefusesToEatTheCharacter();

        System.out.println();
        System.out.println("-- pins change, animation must not --");
        addingAPinKeepsEveryKeyframe();
        removingAPinDoesNotShiftTheOthers();
        theObviousWrongWayIsMeasurablyWrong();
        aBadRemapChangesNothingAtAll();

        System.out.println();
        System.out.println(failed == 0 ? "ALL GREEN (" + passed + "/" + (passed + failed) + ")"
                : "FAILURES: " + failed + " (passed " + passed + ")");
        if (failed > 0) System.exit(1);
    }

    // ── fixtures ────────────────────────────────────────────────────────────────────────────

    /** A plain blob: a square limb with a pin at each end. */
    static float[] bar() {
        return new float[]{0.1f, 0.35f, 0.9f, 0.35f, 0.9f, 0.65f, 0.1f, 0.65f};
    }

    /**
     * FOUR pins along the bar, not two.
     *
     * <p>Two would be the obvious fixture and it would measure nothing: with two control points
     * rigid MLS has barely more freedom than a similarity transform, so the far end swings the
     * same distance whatever the weights say. {@code PuppetWeightsTest.twoPinsCannotLocaliseAtAll}
     * records that. A knob can only be seen to work on a rig that could have held still.
     */
    static float[] fourPins() {
        return new float[]{0.2f, 0.5f, 0.4f, 0.5f, 0.6f, 0.5f, 0.8f, 0.5f};
    }

    static PuppetTopology topo(float softness, float[] area, float[] strength) {
        return new PuppetTopology(new float[][]{bar()}, 6, fourPins(), softness, area, strength);
    }

    /** Lift the leftmost pin and leave the other three where they are. */
    static float[] liftLeft() {
        return new float[]{0f, 0.25f, 0f, 0f, 0f, 0f, 0f, 0f};
    }

    static MeshBuffers solved(PuppetTopology t, float[] pose) {
        MeshBuffers b = new MeshBuffers();
        b.bind(t);
        return new PuppetDeformer().solve(t, b, pose) ? b : null;
    }

    /** How far the far END of the bar moved — the part the near pin should barely own. */
    static float farEndMove(PuppetTopology t, MeshBuffers b) {
        float worst = 0f;
        for (int v = 0; v < t.vertexCount(); v++) {
            if (b.rest[v * 2] < 0.75f) continue;
            float dx = b.positions[v * 2] - b.rest[v * 2];
            float dy = b.positions[v * 2 + 1] - b.rest[v * 2 + 1];
            worst = Math.max(worst, (float) Math.sqrt(dx * dx + dy * dy));
        }
        return worst;
    }

    // ── softness ────────────────────────────────────────────────────────────────────────────

    static void softnessChangesHowFarAPinReaches() {
        float[] pose = liftLeft();
        float hard = farEndMove(topo(0.0f, null, null), solved(topo(0.0f, null, null), pose));
        float mid = farEndMove(topo(0.5f, null, null), solved(topo(0.5f, null, null), pose));
        float soft = farEndMove(topo(1.0f, null, null), solved(topo(1.0f, null, null), pose));
        System.out.printf("    far end follows the near pin: hard %.4f, neutral %.4f, soft %.4f%n",
                hard, mid, soft);
        // The whole meaning of the word: a soft character carries the motion further along itself.
        check("soft spreads the motion further than neutral", soft > mid);
        check("and neutral further than hard", mid > hard);
        check("the slider is not merely stored — it visibly changes the pose",
                soft > hard * 1.25f);
    }

    static void neutralSoftnessIsExactlyTheOldBehaviour() {
        // 1/d^2 is what this engine did before the slider was wired. A character posed yesterday
        // must pose identically today, or turning a knob nobody touched would have moved every
        // existing project.
        check("neutral softness is the plain 1/d^2 falloff",
                Math.abs(PuppetWeights.exponentFor(0.5f) - 1.0f) < 1e-6f);
        check("hard is a steeper falloff", PuppetWeights.exponentFor(0f) > 1.5f);
        check("soft is a shallower one", PuppetWeights.exponentFor(1f) < 0.7f);
        check("a nonsense value falls back rather than producing NaN",
                !Float.isNaN(PuppetWeights.exponentFor(Float.NaN)));
    }

    static void softnessSurvivesTheFileFormat() {
        PuppetTopology t = topo(0.85f, null, null);
        MeshTopology back = MeshTopologies.create("puppet", t.params());
        check("a puppet with softness round-trips", back instanceof PuppetTopology);
        if (!(back instanceof PuppetTopology)) return;
        check("with the softness it was saved with",
                Math.abs(((PuppetTopology) back).softness() - 0.85f) < 1e-5f);
        // Softness changes the weight table, so it is structure: two characters that pose
        // differently must not share an identity, or a reload would keep the wrong table.
        check("and a different softness is a different topology",
                topo(0.2f, null, null).topologyId() != topo(0.8f, null, null).topologyId());
    }

    // ── stiffness ───────────────────────────────────────────────────────────────────────────

    /**
     * A stiff pin says "this part is a plank". Its neighbourhood should take ITS motion and
     * nobody else's — so when the OTHER pin moves, a stiff region stays put.
     */
    static void aStiffPinHoldsItsNeighbourhoodRigid() {
        float[] area = {0f, 0f, 0f, 0.35f};
        float[] strength = {0f, 0f, 0f, 1f};                // the RIGHTMOST pin is stiff
        float[] pose = liftLeft();

        float loose = farEndMove(topo(0.5f, null, null), solved(topo(0.5f, null, null), pose));
        PuppetTopology stiff = topo(0.5f, area, strength);
        float held = farEndMove(stiff, solved(stiff, pose));
        System.out.printf("    far end when the right pin is stiff: %.4f (loose %.4f)%n",
                held, loose);
        check("a stiff pin's neighbourhood resists the other pin", held < loose * 0.6f);
        check("...and the ordinary one really did move, so this is a comparison", loose > 0.02f);
    }

    static void strengthZeroIsAnOrdinaryPin() {
        float[] pose = liftLeft();
        PuppetTopology plain = topo(0.5f, null, null);
        PuppetTopology zeroed = topo(0.5f,
                new float[]{0.4f, 0.4f, 0.4f, 0.4f}, new float[]{0f, 0f, 0f, 0f});
        float a = farEndMove(plain, solved(plain, pose));
        float b = farEndMove(zeroed, solved(zeroed, pose));
        // An area with no strength must be inert, or every pin would be slightly stiff by default.
        check("area without strength changes nothing (" + fmt(a) + " vs " + fmt(b) + ")",
                Math.abs(a - b) < 1e-6f);
    }

    static void stiffnessStaysInsideItsArea() {
        float[] pose = liftLeft();
        // A reach so small it covers almost nothing should behave like no stiffness at all.
        PuppetTopology tiny = topo(0.5f,
                new float[]{0f, 0f, 0f, 0.001f}, new float[]{0f, 0f, 0f, 1f});
        PuppetTopology plain = topo(0.5f, null, null);
        float a = farEndMove(tiny, solved(tiny, pose));
        float b = farEndMove(plain, solved(plain, pose));
        check("a reach of nearly zero reaches nearly nothing (" + fmt(a) + " vs " + fmt(b) + ")",
                Math.abs(a - b) < 0.02f);
    }

    static void stiffnessNeverBreaksThePartitionOfUnity() {
        PuppetTopology t = topo(0.3f,
                new float[]{0.5f, 0.5f, 0.5f, 0.5f}, new float[]{0.8f, 0f, 0f, 1f});
        PuppetWeights w = t.weights();
        check("a stiff puppet has a table", w != null);
        if (w == null) return;
        float worst = 0f;
        boolean clean = true;
        for (int v = 0; v < w.vertexCount(); v++) {
            float sum = 0f;
            for (int i = 0; i < w.pinCount(); i++) {
                float x = w.weight(v, i);
                if (Float.isNaN(x) || x < -1e-6f || x > 1.0001f) clean = false;
                sum += x;
            }
            worst = Math.max(worst, Math.abs(1f - sum));
        }
        // Two overlapping stiff pins is the case most likely to produce a row that does not sum
        // to one, and a row that does not sum to one is a vertex that scales instead of moving.
        check("every row still sums to 1 with overlapping stiff pins (" + fmt(worst) + ")",
                worst < 1e-4f);
        check("and nothing left [0,1]", clean);
    }

    // ── edge expansion ──────────────────────────────────────────────────────────────────────

    static void expansionGrowsTheOutline() {
        float[] square = {0.3f, 0.3f, 0.7f, 0.3f, 0.7f, 0.7f, 0.3f, 0.7f};
        float before = Math.abs(AlphaContour.signedArea2(square)) * 0.5f;
        float[] grown = AlphaContour.expand(square, 0.02f);
        float after = Math.abs(AlphaContour.signedArea2(grown)) * 0.5f;
        System.out.printf("    square area %.4f -> %.4f%n", before, after);
        // OUTWARD. Inward would eat the character's outline, and it would look like a bad trace.
        check("the outline grows rather than shrinks", after > before);
        check("by roughly the perimeter times the amount ("
                        + fmt(after - before) + ", expected about "
                        + fmt(1.6f * 0.02f) + ")",
                Math.abs((after - before) - 1.6f * 0.02f) < 0.01f);
    }

    static void expansionKeepsTheShape() {
        float[] square = {0.3f, 0.3f, 0.7f, 0.3f, 0.7f, 0.7f, 0.3f, 0.7f};
        float[] grown = AlphaContour.expand(square, 0.02f);
        check("it has the same number of points", grown.length == square.length);
        // Each corner moves diagonally outward: a mitre, not a rounded-off corner.
        boolean corners = grown[0] < square[0] && grown[1] < square[1]
                && grown[4] > square[4] && grown[5] > square[5];
        check("every corner moved outward along its own diagonal", corners);
        check("the winding is unchanged, so the triangulator sees what it expects",
                Math.signum(AlphaContour.signedArea2(grown))
                        == Math.signum(AlphaContour.signedArea2(square)));
    }

    static void expansionRefusesToEatTheCharacter() {
        float[] square = {0.3f, 0.3f, 0.7f, 0.3f, 0.7f, 0.7f, 0.3f, 0.7f};
        check("zero does nothing", AlphaContour.expand(square, 0f) == square);
        check("negative does nothing", AlphaContour.expand(square, -1f) == square);
        check("a degenerate ring is handed straight back",
                AlphaContour.expand(new float[]{0f, 0f}, 0.1f).length == 2);
        // An absurd amount would turn a concave shape inside out. Capped, and the result is still
        // a bigger version of the same shape rather than a knot.
        float[] huge = AlphaContour.expand(square, 5f);
        check("an absurd amount is capped, not obeyed",
                Math.abs(AlphaContour.signedArea2(huge))
                        > Math.abs(AlphaContour.signedArea2(square)));
        check("...and stays finite", allFinite(huge));
    }

    // ── pins change ─────────────────────────────────────────────────────────────────────────

    /** Build a spec with three pins and a two-keyframe animation on each. */
    static MeshWarpSpec animated() {
        PuppetTopology t = new PuppetTopology(new float[][]{bar()}, 5,
                new float[]{0.2f, 0.5f, 0.5f, 0.5f, 0.8f, 0.5f});
        MeshWarpSpec spec = new MeshWarpSpec(t);
        MeshPoseTrack tr = spec.ensureTrack();
        tr.put(0L, new float[]{0f, 0f, 0f, 0f, 0f, 0f}, null);
        tr.put(1000L, new float[]{0.01f, 0.1f, 0.02f, 0.2f, 0.03f, 0.3f}, null);
        return spec;
    }

    static void addingAPinKeepsEveryKeyframe() {
        MeshWarpSpec spec = animated();
        PuppetTopology grown = new PuppetTopology(new float[][]{bar()}, 5,
                new float[]{0.2f, 0.5f, 0.5f, 0.5f, 0.8f, 0.5f, 0.6f, 0.4f});   // a FOURTH pin
        boolean ok = PuppetPoseRemap.apply(spec, grown, new int[]{0, 1, 2, -1});
        check("the spec moves onto the bigger topology", ok);
        if (!ok) return;
        check("the animation is still there", spec.track() != null && spec.track().size() == 2);
        float[] at = new float[grown.handleArity()];
        spec.handlesAt(1000L, at);
        check("pin 0 kept its performance", Math.abs(at[1] - 0.1f) < 1e-5f);
        check("pin 2 kept its performance", Math.abs(at[5] - 0.3f) < 1e-5f);
        check("and the new pin starts at rest", at[6] == 0f && at[7] == 0f);
    }

    static void removingAPinDoesNotShiftTheOthers() {
        MeshWarpSpec spec = animated();
        // Remove the MIDDLE pin, which is what renumbers everything after it.
        PuppetTopology shrunk = new PuppetTopology(new float[][]{bar()}, 5,
                new float[]{0.2f, 0.5f, 0.8f, 0.5f});
        boolean ok = PuppetPoseRemap.apply(spec, shrunk, new int[]{0, 2});
        check("the spec moves onto the smaller topology", ok);
        if (!ok) return;
        float[] at = new float[shrunk.handleArity()];
        spec.handlesAt(1000L, at);
        check("the surviving first pin kept its own performance",
                Math.abs(at[1] - 0.1f) < 1e-5f);
        // THE ONE THAT MATTERS. Copy-by-index would have handed this pin the DELETED pin's
        // animation, and nothing would have complained.
        check("the pin after the deleted one kept ITS own, not its neighbour's ("
                        + fmt(at[3]) + ", wanted 0.30)",
                Math.abs(at[3] - 0.3f) < 1e-5f);
    }

    static void theObviousWrongWayIsMeasurablyWrong() {
        // The tempting implementation, written out so the difference is on the record rather than
        // argued about: copy by index into the new arity.
        float[] old = {0.01f, 0.1f, 0.02f, 0.2f, 0.03f, 0.3f};
        float[] naive = new float[4];
        System.arraycopy(old, 0, naive, 0, 4);
        float[] correct = PuppetPoseRemap.byPinIndex(new int[]{0, 2}, 2).remap(old);
        check("copy-by-index really does give the second pin the deleted pin's pose",
                Math.abs(naive[3] - 0.2f) < 1e-6f);
        check("...and the mapping gives it its own", Math.abs(correct[3] - 0.3f) < 1e-6f);
        check("so the two disagree, which is the bug this file exists to stop",
                Math.abs(naive[3] - correct[3]) > 0.05f);
    }

    static void aBadRemapChangesNothingAtAll() {
        MeshWarpSpec spec = animated();
        PuppetTopology four = new PuppetTopology(new float[][]{bar()}, 5,
                new float[]{0.2f, 0.5f, 0.5f, 0.5f, 0.8f, 0.5f, 0.6f, 0.4f});
        // A mapping of the wrong length for the topology it claims to describe.
        boolean ok = PuppetPoseRemap.apply(spec, four, new int[]{0, 1, 2});
        check("a mapping that does not fit the topology is refused", !ok);
        check("and the spec is untouched, so a refused rebuild keeps the animation",
                spec.arity() == 6 && spec.track() != null && spec.track().size() == 2);
    }

    // ── plumbing ────────────────────────────────────────────────────────────────────────────

    static boolean allFinite(float[] a) {
        for (float f : a) if (Float.isNaN(f) || Float.isInfinite(f)) return false;
        return true;
    }

    static String fmt(float f) { return String.format("%.4f", f); }

    static void check(String what, boolean ok) {
        if (ok) { passed++; System.out.println("  ok   " + what); }
        else { failed++; System.out.println("  FAIL " + what); }
    }
}
